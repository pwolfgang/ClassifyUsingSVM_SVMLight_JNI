/* 
 * Copyright (c) 2018, Temple University
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * All advertising materials features or use of this software must display 
 *   the following  acknowledgement
 *   This product includes software developed by Temple University
 * * Neither the name of the copyright holder nor the names of its 
 *   contributors may be used to endorse or promote products derived 
 *   from this software without specific prior written permission. 
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package edu.temple.cla.wolfgang.texttools.classifyusingsvm;

import edu.temple.cla.papolicy.wolfgang.texttools.util.Preprocessor;
import edu.temple.cla.papolicy.wolfgang.texttools.util.SimpleDataSource;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Util;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Vocabulary;
import edu.temple.cla.papolicy.wolfgang.texttools.util.WordCounter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import tw.edu.ntu.csie.libsvm.svm;
import tw.edu.ntu.csie.libsvm.svm_model;
import tw.edu.ntu.csie.libsvm.svm_node;

/**
 *
 * @author Paul Wolfgang
 */
public class Main implements Callable<Void> {

    @CommandLine.Option(names = "--datasource", required = true,
            description = "File containing the datasource properties")
    private String dataSourceFileName;

    @CommandLine.Option(names = "--table_name", required = true,
            description = "The name of the table containing the data")
    private String tableName;

    @CommandLine.Option(names = "--id_column", required = true,
            description = "Column(s) containing the ID")
    private String idColumn;

    @CommandLine.Option(names = "--text_column", required = true,
            description = "Column(s) containing the text")
    private String textColumn;

    @CommandLine.Option(names = "--code_column", required = true,
            description = "Column(s) containing the code")
    private String codeColumn;

    @CommandLine.Option(names = "--output_table_name",
            description = "Table where results are written")
    private String outputTableName;

    @CommandLine.Option(names = "--output_code_col", required = true,
            description = "Column where the result is set")
    private String outputCodeCol;

    @CommandLine.Option(names = "--model",
            description = "Directory where model files are written")
    private String modelDir = "SVM_Model_Dir";

    @CommandLine.Option(names = "--feature_dir",
            description = "Directory where feature files are written")
    private String featureDir = "SVM_Classification_Features";

    @CommandLine.Option(names = "--result_dir",
            description = "Directory for intermediat files")
    private String resultDir = "SVM_Classification_Results";

    @CommandLine.Option(names = "--use_even",
            description = "Use even numbered samples for training")
    private Boolean useEven = false;

    @CommandLine.Option(names = "--use_odd",
            description = "Use odd numbered samples for training")
    private Boolean useOdd = false;

    @CommandLine.Option(names = "--compute_major",
            description = "Major code is computed from minor code")
    private Boolean computeMajor = false;

    @CommandLine.Option(names = "--remove_stopwords",
            description = "Remove common \"stop words\" from the text.")
    private String removeStopWords;

    @CommandLine.Option(names = "--do_stemming",
            description = "Pass all words through stemming algorithm")
    private String doStemming;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        CommandLine.call(new Main(), System.err, args);
    }

    /**
     * Execute the main program. This method is called after the command line
     * parameters have been populated.
     *
     * @return null.
     */
    @Override
    public Void call() {

        List<String> ids = new ArrayList<>();
        List<String> ref = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Vocabulary vocabulary;
        Util.readFromDatabase(dataSourceFileName,
                tableName,
                idColumn,
                textColumn,
                codeColumn,
                computeMajor,
                useEven,
                useOdd,
                ids,
                lines,
                ref);
        List<svm_node[]> problems = new ArrayList<>();
        File modelParent = new File(modelDir);
        File vocabFile = new File(modelParent, "vocab.bin");
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(vocabFile))) {
            vocabulary = (Vocabulary) ois.readObject();
            Preprocessor preprocessor = new Preprocessor(doStemming, removeStopWords);
            lines.stream()
                    .map(line -> preprocessor.preprocess(line))
                    .forEach(words -> {
                        WordCounter counter = new WordCounter();
                        words.forEach(counter::updateCounts);
                        problems.add(Util.convereToSVMNode(Util.computeAttributes(counter, vocabulary, 0.0)));
                    });
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        SortedMap<Integer, Map<String, Integer>> results = new TreeMap<>();
        classifyTest(modelParent, problems, results);
        List<Integer> categories = new ArrayList<>();
        System.err.println("Consolidating Results");
        consolidateResult(results, ids, categories);
        String outputTable = outputTableName != null ? outputTableName : tableName;
        if (outputCodeCol != null) {
            System.err.println("Inserting result into database");
            outputToDatabase(dataSourceFileName,
                    outputTable,
                    idColumn,
                    outputCodeCol,
                    ids,
                    categories);
        }
        System.err.println("SUCESSFUL COMPLETION");
        return null;
    }

    /**
     * Function to run the test file agains each of the SVM models
     *
     * @param modelDir The the directory containg the models
     * @param problems The list of problems to be classified
     * @param results Map of problem indices vs list of results.
     */
    public static void classifyTest(
            File modelDir,
            List<svm_node[]> problems,
            SortedMap<Integer,Map<String, Integer>> results) {
        String[] modelFileNames = modelDir.list();
        for (String modelFileName : modelFileNames) {
            if (modelFileName.startsWith("svm")) {
                int posFirstDot = modelFileName.indexOf('.');
                int posSecondDot = modelFileName.indexOf('.', posFirstDot + 1);
                String posModel
                        = modelFileName.substring(posFirstDot + 1, posSecondDot);
                String negModel = modelFileName.substring(posSecondDot + 1);
                File modelFile = new File(modelDir, modelFileName);
                svm_model model = null;
                System.out.println("Reading model " + modelFile);
                try {
                    model = svm.svm_load_model(modelFile.getPath());
                } catch (IOException ioex) {
                    throw new RuntimeException("Error reading model", ioex);
                }
                for (int i = 0; i < problems.size(); i++) {
                    double result = svm.svm_predict(model, problems.get(i));
                    Map<String, Integer> resultMap = results.getOrDefault(i, new HashMap<>());
                    if (result > 0) {
                        int countOfCat = resultMap.getOrDefault(posModel, 0);
                        countOfCat++;
                        resultMap.put(posModel, countOfCat);
                    } else {
                        int countOfCat = resultMap.getOrDefault(negModel, 0);
                        countOfCat++;
                        resultMap.put(negModel, countOfCat);
                    }
                    results.put(i, resultMap);
                }
            }
        }
    }

    /**
     * Method to consolidate the results of SVM model testing. Each file in the
     * resultDir of the form result.&lt;cat1&gt;.&lt;cat2&gt; contains the
     * result for each test case against the pair &lt;cat1&gt; &lt;cat2&gt; An
     * intermediate file is created to contain one record for each test
     * consisting of the ID for this test case, the winning category, and the
     * score. This file is sorted by id and winning category. Finally the result
     * file is created consising of one record per id giving the id followed by
     * the categories in decending order of frequency.
     *
     * @param results The Map of problems indices to Map of category counts.
     * @param ids A List&lt;String&gt; containing the IDs.
     * @param categories The result list of computed categories.
     */
    public static void consolidateResult(
            SortedMap<Integer, Map<String, Integer>> results,
            List<String> ids,
            List<Integer> categories) {
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Integer> result = results.get(i);
            SortedMap<Integer, String> invertedResult = new TreeMap<>();
            result.forEach((k, v) -> invertedResult.put(v, k));
            Integer maxKey = invertedResult.lastKey();
            String winningCategory = invertedResult.get(maxKey);
            categories.add(new Integer(winningCategory));
        }
    }

    /**
     * Method to write the classification results to the database
     *
     * @param dataSourceFileName The file containing the datasource
     * @param tableName The name of the table
     * @param idColumn The column containing the ID
     * @param outputCodeCol The column where the results are set
     * @param ids The list of ids
     * @param cats The corresponding list if categories.
     */
    public static void outputToDatabase(
            String dataSourceFileName,
            String tableName,
            String idColumn,
            String outputCodeCol,
            List<String> ids,
            List<Integer> cats) {
        try {
            SimpleDataSource sds = new SimpleDataSource(dataSourceFileName);
            try (Connection conn = sds.getConnection();
                    Statement stmt = conn.createStatement();) {
                stmt.executeUpdate("DROP TABLE IF EXISTS NewCodes");
                stmt.executeUpdate("CREATE TABLE NewCodes (ID char(11) primary key, Code int)");
                StringBuilder stb = new StringBuilder("INSERT INTO NewCodes (ID, Code) VALUES");
                StringJoiner sj = new StringJoiner(",\n");
                for (int i = 0; i < ids.size(); i++) {
                    sj.add(String.format("(\'%s\', %d)", ids.get(i), cats.get(i)));
                }
                stb.append(sj);
                stmt.executeUpdate(stb.toString());
                stmt.executeUpdate("UPDATE " + tableName + " join NewCodes on "
                        + tableName + ".ID=NewCodes.ID SET " + tableName + "."
                        + outputCodeCol + "=NewCodes.Code");
            } catch (SQLException ex) {
                throw ex;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }
}
