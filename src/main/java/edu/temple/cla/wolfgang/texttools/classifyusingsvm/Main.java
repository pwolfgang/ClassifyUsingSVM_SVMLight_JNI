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

import edu.temple.cla.papolicy.wolfgang.texttools.util.CommonFrontEnd;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Util;
import edu.temple.cla.papolicy.wolfgang.texttools.util.Vocabulary;
import edu.temple.cla.papolicy.wolfgang.texttools.util.WordCounter;
import edu.temple.cla.wolfgang.jnisvmlight.SVMLight;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/**
 * Program to classify text using SVM. This program classifies text samples
 * (e.g. bills and resolutions) following the algorithm described by Purpura,
 * S., Hillard D.
 * <a href="www.purpuras.net/dgo2006%20Purpura%20Hillard%20Classifying%20Congressional%20Legislation.pdf">
 * Automated Classification of Congressional Legislation </a> Proceedings of the
 * Seventh International Conference on Digital Government Research. San Diego,
 * CA. It adapted from <a href="http://www.purpuras.net/pac/run-svm-text.html">
 * run_svm.pl </a>. Each sample is tested against pair-wise classifiers for each
 * pair of categories. The category with the largest number of positive results
 * is considered the resulting category. The actual SVM calculations are
 * performed using <a href="http://svmlight.joachims.org/"> svm_light </a>
 * by calling svm_classify through a JNI bridge (JNISvmLight).
 *
 * @author Paul Wolfgang
 */
public class Main implements Callable<Void> {

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

    private final String[] args;

    private static final SVMLight svmLight = new SVMLight();

    public Main(String[] args) {
        this.args = args;
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        Main main = new Main(args);
        CommandLine commandLine = new CommandLine(main);
        commandLine.setUnmatchedArgumentsAllowed(true).parse(args);
        try {
            main.call();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Execute the main program. This method is called after the command line
     * parameters have been populated.
     *
     * @return null.
     */
    @Override
    public Void call() {
        long start = System.nanoTime();
        CommonFrontEnd commonFrontEnd = new CommonFrontEnd();
        CommandLine commandLine = new CommandLine(commonFrontEnd);
        commandLine.setUnmatchedArgumentsAllowed(true);
        commandLine.parse(args);
        List<Map<String, Object>> cases = new ArrayList<>();
        List<SortedMap<Integer, Double>> docs = new ArrayList<>();
        Vocabulary unusedVocab = commonFrontEnd.loadData(cases);
        File modelParent = new File(modelDir);
        File vocabFile = new File(modelParent, "vocab.bin");
        Vocabulary vocabulary = null; //The model vocabulary
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(vocabFile))) {
            vocabulary = (Vocabulary) ois.readObject();
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        double gamma = 1.0 / vocabulary.numFeatures();
        for (Map<String, Object> c : cases) {
            docs.add(Util.computeAttributes((WordCounter) c.get("counts"), vocabulary, gamma));
        }
        SortedMap<Integer, Map<String, Integer>> results = new TreeMap<>();
        classifyTest(modelParent, docs, results);
        System.err.println("Consolidating Results");
        consolidateResult(results, cases);
        String outputTable = outputTableName != null ? outputTableName : commonFrontEnd.getTableName();
        if (outputCodeCol != null) {
            System.err.println("Inserting result into database");
            commonFrontEnd.outputToDatabase(outputTable,
                    outputCodeCol,
                    cases, "newCode");
        }
        System.err.println("SUCESSFUL COMPLETION");
        long end = System.nanoTime();
        System.err.println("TOTAL TIME " + (end - start) / 1.0e9 + "sec.");
        System.err.println("SUCESSFUL COMPLETION");
        return null;
    }

    /**
     * Function to run the test file against each of the SVM models
     *
     * @param modelDir The the directory containing the models
     * @param problems The list of problems to be classified
     * @param results Map of problem indices vs list of results.
     */
    public static void classifyTest(
            File modelDir,
            List<SortedMap<Integer, Double>> problems,
            SortedMap<Integer, Map<String, Integer>> results) {
        String[] modelFileNames = modelDir.list((f, n) -> n.startsWith("svm"));
        if (modelFileNames.length > 0) {
            for (String modelFileName : modelFileNames) {
                int posFirstDot = modelFileName.indexOf('.');
                int posSecondDot = modelFileName.indexOf('.', posFirstDot + 1);
                String posModel
                        = modelFileName.substring(posFirstDot + 1, posSecondDot);
                String negModel = modelFileName.substring(posSecondDot + 1);
                File modelFile = new File(modelDir, modelFileName);
                String modelFilePath = modelFile.getPath();
                double[] dists = svmLight.SVMClassify(modelFilePath, problems, problems.size());
                for (int i = 0; i < problems.size(); i++) {
                    Map<String, Integer> resultMap = results.getOrDefault(i, new HashMap<>());
                    if (dists[i] > 0) {
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
        } else {
            String modelDirName = modelDir.getName();
            int lastIndexOfUC = modelDirName.lastIndexOf("_");
            if (lastIndexOfUC > 0) {
                String cat = modelDirName.substring(lastIndexOfUC+1) + "00";
                Map<String, Integer> defaultMap = new HashMap<>();
                defaultMap.put(cat, 1);
                for (int i = 0; i < problems.size(); i++) {
                    results.put(i, defaultMap);
                }
            }
        }
    }

    /**
     * Method to consolidate the results of SVM model testing. Each entry in the
     * map results contains the results of running each record against each pair
     * of categories. This is itself a map of category mapped to the number of
     * times that category was chosen. The category with the highest count is
     * considered the winning category.
     *
     * @param results The Map of problems indexed to Map of category counts.
     * @param cases The list of cases to be classified.
     */
    public static void consolidateResult(
            SortedMap<Integer, Map<String, Integer>> results,
            List<Map<String, Object>> cases) {
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Integer> result = results.get(i);
            SortedMap<Integer, String> invertedResult = new TreeMap<>();
            result.forEach((k, v) -> invertedResult.put(v, k));
            Integer maxKey = invertedResult.lastKey();
            String winningCategory = invertedResult.get(maxKey);
            int winningCategoryInt;
            if (winningCategory.equalsIgnoreCase("true")) {
                winningCategoryInt = 1;
            } else if (winningCategory.equalsIgnoreCase("false")) {
                winningCategoryInt = 0;
            } else {
                winningCategoryInt = Integer.parseInt(winningCategory);
            }
            cases.get(i).put("newCode", winningCategoryInt);
        }
    }

}
