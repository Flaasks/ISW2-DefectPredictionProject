package com.dipalma.whatif.tools;

import com.dipalma.whatif.analysis.DatasetSplitter;
import com.dipalma.whatif.analysis.FeatureAnalyzer;
import com.dipalma.whatif.classification.ClassifierRunner;

import org.apache.commons.csv.CSVFormat;

import java.util.LinkedHashMap;
import java.util.Map;

public class AnalysisRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.exit(1);
        }
        String processed = args[0];
        String prefix = args.length > 1 ? args[1] : processed.replace("_processed.csv", "");

        var opt = FeatureAnalyzer.selectTopActionableFeature(processed);
        if (opt.isEmpty()) {
            System.exit(2);
        }
        String topFeature = opt.get();

        // Split and create B using topFeature
        DatasetSplitter.split(processed, prefix, topFeature);

        // Train classifier on A_train
        String aTrain = prefix + "_A_train.csv";
        ClassifierRunner runner = new ClassifierRunner(aTrain);
        runner.loadAndPrepareData();
        var trainLoader = new weka.core.converters.CSVLoader();
        trainLoader.setSource(new java.io.File(aTrain));
        var trainData = trainLoader.getDataSet();
        trainData.setClassIndex(trainData.numAttributes() - 1);
        var cls = runner.trainBestClassifier(trainData);
        runner.saveModel(cls, prefix + "_model.bin");

        // Predict on datasets: A_test, Bplus, B, C
        Map<String, String> datasets = new LinkedHashMap<>();
        datasets.put("A", prefix + "_A_test.csv");
        datasets.put("B+", prefix + "_Bplus.csv");
        datasets.put("B", prefix + "_B.csv");
        datasets.put("C", prefix + "_C.csv");

        Map<String, Integer> actual = new LinkedHashMap<>();
        Map<String, Integer> predicted = new LinkedHashMap<>();

        for (var e : datasets.entrySet()) {
            String name = e.getKey();
            String path = e.getValue();
            String out = path.replace(".csv", "_pred.csv");
            runner.predictToCsv(cls, path, out);
            // compute counts
                    var parser = com.dipalma.whatif.util.CsvUtils.parseWithHeader(new java.io.FileReader(out));
            int act = 0, pred = 0;
            for (var r : parser) {
                String a = r.get("IsBuggy");
                String p = r.get("PredictedIsBuggy");
                if (a != null && (a.equalsIgnoreCase("yes") || a.equalsIgnoreCase("true") || a.equals("1"))) act++;
                if (p != null && (p.equalsIgnoreCase("yes") || p.equalsIgnoreCase("true") || p.equals("1"))) pred++;
            }
            parser.close();
            actual.put(name, act);
            predicted.put(name, pred);
        }


    }
}
