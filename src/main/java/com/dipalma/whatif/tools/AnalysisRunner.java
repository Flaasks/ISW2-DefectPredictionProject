package com.dipalma.whatif.tools;

import com.dipalma.whatif.analysis.DatasetSplitter;
import com.dipalma.whatif.analysis.FeatureAnalyzer;
import com.dipalma.whatif.classification.ClassifierRunner;


import java.util.LinkedHashMap;
import java.util.Map;

public class AnalysisRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.exit(1);
        }
        String processed = args[0];
        String prefix = args.length > 1 ? args[1] : processed.replace("_processed.csv", "");

        String topFeature = selectActionableFeature(processed);
        
        // Split and create B using topFeature
        DatasetSplitter.split(processed, prefix, topFeature);

        // Train classifier on A_train
        String aTrain = prefix + "_A_train.csv";
        var cls = trainClassifier(aTrain);

        // Predict on datasets: A_test, Bplus, B, C
        Map<String, String> datasets = buildDatasetsMap(prefix);
        Map<String, Integer> actual = new LinkedHashMap<>();
        Map<String, Integer> predicted = new LinkedHashMap<>();

        ClassifierRunner runner = new ClassifierRunner(aTrain);
        processDatasets(cls, datasets, runner, actual, predicted);
    }

    private static String selectActionableFeature(String processed) throws Exception {
        var opt = FeatureAnalyzer.selectTopActionableFeature(processed);
        if (opt.isEmpty()) {
            System.exit(2);
        }
        return opt.get();
    }

    private static weka.classifiers.Classifier trainClassifier(String aTrain) throws Exception {
        ClassifierRunner runner = new ClassifierRunner(aTrain);
        runner.loadAndPrepareData();
        
        var trainLoader = new weka.core.converters.CSVLoader();
        trainLoader.setSource(new java.io.File(aTrain));
        var trainData = trainLoader.getDataSet();
        trainData.setClassIndex(trainData.numAttributes() - 1);
        
        var cls = runner.trainBestClassifier(trainData);
        runner.saveModel(cls, aTrain.replace("_A_train.csv", "_model.bin"));
        return cls;
    }

    private static Map<String, String> buildDatasetsMap(String prefix) {
        Map<String, String> datasets = new LinkedHashMap<>();
        datasets.put("A", prefix + "_A_test.csv");
        datasets.put("B+", prefix + "_Bplus.csv");
        datasets.put("B", prefix + "_B.csv");
        datasets.put("C", prefix + "_C.csv");
        return datasets;
    }

    private static void processDatasets(weka.classifiers.Classifier cls,
                                        Map<String, String> datasets,
                                        ClassifierRunner runner,
                                        Map<String, Integer> actual,
                                        Map<String, Integer> predicted) throws Exception {
        for (var e : datasets.entrySet()) {
            String name = e.getKey();
            String path = e.getValue();
            String out = path.replace(".csv", "_pred.csv");
            runner.predictToCsv(cls, path, out);
            
            CountResult counts = countPredictions(out);
            actual.put(name, counts.actual);
            predicted.put(name, counts.predicted);
        }
    }

    private static class CountResult {
        final int actual;
        final int predicted;
        CountResult(int actual, int predicted) {
            this.actual = actual;
            this.predicted = predicted;
        }
    }

    private static CountResult countPredictions(String csvPath) throws Exception {
        int act = 0;
        int pred = 0;
        
        try (var parser = com.dipalma.whatif.util.CsvUtils.parseWithHeader(new java.io.FileReader(csvPath))) {
            for (var r : parser) {
                if (isBuggyValue(r.get("IsBuggy"))) act++;
                if (isBuggyValue(r.get("PredictedIsBuggy"))) pred++;
            }
        }
        
        return new CountResult(act, pred);
    }

    private static boolean isBuggyValue(String value) {
        return value != null 
            && (value.equalsIgnoreCase("yes") 
                || value.equalsIgnoreCase("true") 
                || value.equals("1"));
    }
}
