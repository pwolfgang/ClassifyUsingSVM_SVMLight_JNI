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

import edu.temple.cla.papolicy.wolfgang.texttools.util.Greater;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Preprocessor;
import edu.temple.cla.papolicy.wolfgang.texttools.util.SimpleDataSource;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Util;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Vocabulary;
import edu.temple.cla.papolicy.wolfgang.texttools.util.WordCounter;
import edu.temple.cla.wolfgang.filesort.FileSort;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 *
 * @author Paul Wolfgang
 */
public class Main implements Callable<Void> {

    @CommandLine.Option(names = "--datasource", required = true, description = "File containing the datasource properties")
    private String dataSourceFileName;
    
    @CommandLine.Option(names = "--table_name", required = true, description = "The name of the table containing the data")
    private String tableName;

    @CommandLine.Option(names = "--id_column", required = true, description = "Column(s) containing the ID")
    private String idColumn;
    
    @CommandLine.Option(names = "--text_column", required = true, description = "Column(s) containing the text")
    private String textColumn;

    @CommandLine.Option(names = "--code_column", required = true, description = "Column(s) containing the code")
    private String codeColumn;
    
    @CommandLine.Option(names = "--output_table_name", description = "Table where results are written")
    private String outputTableName;

    @CommandLine.Option(names = "--output_code_col", required = true, description = "Column where the result is set")
    private String outputCodeCol;
    
    @CommandLine.Option(names = "--model", description = "Directory where model files are written")
    private String modelDir = "SVM_Model_Dir";
    
    @CommandLine.Option(names = "--feature_dir", description = "Directory where feature files are written")
    private String featureDir = "SVM_Classification_Features";
    
    @CommandLine.Option(names = "--result_dir", description = "Directory for intermediat files")
    private String resultDir = "SVM_Classification_Results";
    
    @CommandLine.Option(names = "--use_even", description = "Use even numbered samples for training")
    private Boolean useEven = false;
    
    @CommandLine.Option(names = "--use_odd", description = "Use odd numbered samples for training")
    private Boolean useOdd = false;

    @CommandLine.Option(names = "--compute_major", description = "Major code is computed from minor code")
    private Boolean computeMajor = false;

    @CommandLine.Option(names = "--remove_stopwords", description = "Remove common \"stop words\" from the text.")
    private String removeStopWords;

    @CommandLine.Option(names = "--do_stemming", description = "Pass all words through stemming algorithm")
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
     * @return null.
     */
    @Override
    public Void call() {

        List<String> ids = new ArrayList<>();
        List<String> ref = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<ArrayList<String>> text = new ArrayList<>();
        List<WordCounter> counts = new ArrayList<>();
        List<SortedMap<Integer, Double>> attributes = new ArrayList<>();
        Vocabulary vocabulary = new Vocabulary();
        Map<String, List<SortedMap<Integer, Double>>> trainingSets = new TreeMap<>();
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
        Preprocessor preprocessor = new Preprocessor(doStemming, removeStopWords);
        lines.stream()
                .map(line -> preprocessor.preprocess(line))
                .forEach(words -> {
                    WordCounter counter = new WordCounter();
                    words.forEach(word -> {
                        counter.updateCounts(word);
                        counts.add(counter);
                    });
                });
        File modelParent = new File(modelDir);
        modelParent.mkdirs();
        File vocabFile = new File(modelParent, "vocab.bin");
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(vocabFile))) {
            vocabulary = (Vocabulary) ois.readObject();
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        for (WordCounter counter : counts) {
            attributes.add(Util.computeAttributes(counter, vocabulary, 0.0));
        }
        File featureDirFile = new File(featureDir);
        Util.delDir(featureDirFile);
        featureDirFile.mkdirs();
        File featureOutputFile = new File(featureDirFile, "testset");
        try (PrintWriter out = new PrintWriter(new FileWriter(featureOutputFile))) {
            for (SortedMap<Integer, Double> features : attributes) {
                Util.writeFeatureLine(out, 0, features);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        classifyTest(modelParent, featureOutputFile, resultDir);
        consolidateResult(new File(resultDir), ids);
        String outputTable = outputTableName != null ? outputTableName : tableName;
        if (outputCodeCol != null) {
            System.err.println("Inserting result into database");
            outputToDatabase(dataSourceFileName,
                    outputTable,
                    idColumn,
                    outputCodeCol,
                    resultDir);
        }
        System.err.println("SUCESSFUL COMPLETION");
        return null;
    }

    /**
     * Function to run the test file agains each of the SVM models
     *
     * @param modelDir The the directory containg the models
     * @param testFile The the test file
     * @param outputDir The name of the output directory
     */
    public static void classifyTest(
            File modelDir,
            File testFile,
            String outputDir) {
        try {
            File outputDirFile = new File(outputDir);
            Util.delDir(outputDirFile);
            outputDirFile.mkdirs();
            String[] modelFileNames = modelDir.list();
            ProcessBuilder pb = new ProcessBuilder();
            for (String modelFileName : modelFileNames) {
                if (modelFileName.startsWith("svm")) {
                    int posFirstDot = modelFileName.indexOf('.');
                    int posSecondDot = modelFileName.indexOf('.', posFirstDot + 1);
                    String posModel
                            = modelFileName.substring(posFirstDot + 1, posSecondDot);
                    String negModel = modelFileName.substring(posSecondDot + 1);
                    File modelFile = new File(modelDir, modelFileName);
                    File resultFile = new File(outputDir, "result." + posModel
                            + "." + negModel);
                    File outputFile = new File(outputDir, "out." + posModel
                            + "." + negModel);
                    List<String> command = new ArrayList<>();
                    command.add("svm_classify");
                    command.add(testFile.getPath());
                    command.add(modelFile.getPath());
                    command.add(resultFile.getPath());
                    pb.command(command);
                    Process p = pb.start();
                    System.out.println(command);
                    InputStream processOut = p.getInputStream();
                    BufferedReader rdr = new BufferedReader(new InputStreamReader(processOut));
                    try (PrintWriter pwtr = new PrintWriter(new FileWriter(outputFile))) {
                        String line;
                        while ((line = rdr.readLine()) != null) {
                            pwtr.println(line);
                        }
                    }
                    p.waitFor();
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Method to consolidate the results of SVM model testing. Each file in the
     * resultDir of the form result.&lt;cat1&gt;.&lt;cat2&gt; contains the result
     * for each test case against the pair &lt;cat1&gt; &lt;cat2&gt; An
     * intermediate file is created to contain one record for each test
     * consisting of the ID for this test case, the winning category, and the
     * score. This file is sorted by id and winning category. Finally the result
     * file is created consising of one record per id giving the id followed by
     * the categories in decending order of frequency.
     *
     * @param resultDir The directory containing the result files
     * @param ids A List&lt;String&gt; containing the IDs.
     */
    public static void consolidateResult(
            File resultDir,
            List<String> ids) {
        try {
            String[] resultFileNames = resultDir.list();
            File temp = new File(resultDir, "temp");
            try (PrintWriter tempOut = new PrintWriter(new FileWriter(temp))) {
                System.out.println("Building consilidated result file");
                for (String resultFileName : resultFileNames) {
                    if (resultFileName.startsWith("result.")) {
                        int posFirstDot = resultFileName.indexOf('.');
                        int posSecondDot = resultFileName.indexOf('.', posFirstDot + 1);
                        String posModel = resultFileName.substring(posFirstDot + 1,
                                posSecondDot);
                        String negModel = resultFileName.substring(posSecondDot + 1);
                        System.out.println("Reading " + resultFileName);
                        try (BufferedReader in = new BufferedReader(new FileReader(new File(resultDir,
                                resultFileName)))) {
                            int index = -1;
                            String line;
                            String winner;
                            String looser;
                            double score;
                            while ((line = in.readLine()) != null) {
                                ++index;
                                score = Double.parseDouble(line);
                                if (score < 0.0) {
                                    winner = negModel;
                                    looser = posModel;
                                    score = -score;
                                } else {
                                    winner = posModel;
                                    looser = negModel;
                                }
                                tempOut.printf("%s %s %f", ids.get(index), winner, score);
                                tempOut.println();
                            }
                        }
                    }
                }
            }
            File sortedTemp = new File(resultDir, "sortedTemp");
            System.out.println("Begin Sort");
            FileSort.sort(temp.getAbsolutePath(), sortedTemp.getAbsolutePath(),
                    resultDir, Long.MAX_VALUE);
            System.out.println("End Sort");
            BufferedReader sortedInput
                    = new BufferedReader(new FileReader(sortedTemp));
            File finalResult = new File(resultDir, "final_result.txt");
            try (PrintWriter finalOut = new PrintWriter(new FileWriter(finalResult))) {
                String currentId = null;
                String line;
                Map<String, Integer> counts = new HashMap<>();
                while ((line = sortedInput.readLine()) != null) {
                    String[] tokens = line.split("\\s+");
                    if (!tokens[0].equals(currentId)) {
                        summarize(currentId, counts, finalOut);
                        currentId = tokens[0];
                    }
                    Integer count = counts.getOrDefault(tokens[1], 0);
                    count = count + 1;
                    counts.put(tokens[1], count);
                }
                summarize(currentId, counts, finalOut);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Method to summarize the results for a given ID
     *
     * @param currentID The current ID
     * @param counts The map from category to times this category occurs
     * @param finalOut The printwriter where the result line is written
     * @throws java.lang.Exception If there is an error.
     */
    public static void summarize(
            String currentID,
            Map<String, Integer> counts,
            PrintWriter finalOut) throws Exception {
        if (currentID == null) {
            return;
        }
        SortedMap<Integer, String> sortedMap
                = new TreeMap<>(new Greater<>());
        counts.forEach((k, v) -> sortedMap.put(v, k));
        finalOut.print(currentID);
        sortedMap.forEach((k, v) -> {
            finalOut.print(" ");
            finalOut.print(v);
        });
        finalOut.println();
        counts.clear();
    }

    /**
     * Method to write the classification results to the database
     *
     * @param dataSourceFileName The file containing the datasource
     * @param tableName The name of the table
     * @param idColumn The column contining the ID
     * @param outputCodeCol The column where the results are set
     * @param resultDir The directory containing the results
     */
    public static void outputToDatabase(
            String dataSourceFileName,
            String tableName,
            String idColumn,
            String outputCodeCol,
            String resultDir) {
        try {
            SimpleDataSource sds = new SimpleDataSource(dataSourceFileName);
            File resultFile = new File(resultDir, "final_result.txt");
            try (Connection conn = sds.getConnection();
                    Statement stmt = conn.createStatement();
                    BufferedReader in = new BufferedReader(new FileReader(resultFile));) {
                in.lines().forEach(line -> {
                    String[] tokens = line.split("\\s+");
                    if (tokens.length >= 2) {
                        String query = "UPDATE " + tableName
                                + " SET " + outputCodeCol + "="
                                + tokens[1] + " WHERE "
                                + idColumn + "='" + tokens[0] + "'";
                        try {
                            stmt.executeUpdate(query);
                        } catch (SQLException sqex) {
                            System.err.println("Error executing update query");
                            System.err.println(query);
                            sqex.printStackTrace();
                            System.err.println("EXITING");
                            System.exit(1);
                        }
                    }
                });
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }
}
