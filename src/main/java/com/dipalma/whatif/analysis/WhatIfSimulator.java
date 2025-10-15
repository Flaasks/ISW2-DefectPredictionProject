package com.dipalma.whatif.analysis;

import com.dipalma.whatif.classification.ClassifierRunner;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.classifiers.Classifier;
import weka.core.Instances;
import weka.core.converters.CSVLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * New WhatIfSimulator: automated what-if pipeline using DatasetSplitter and ClassifierRunner.
 */
public class WhatIfSimulator {

    private final String processedCsvPath;
    private static final Logger log = LoggerFactory.getLogger(WhatIfSimulator.class);

    public WhatIfSimulator(String processedCsvPath) {
        this.processedCsvPath = processedCsvPath;
    }

    public void runFullDatasetSimulation() throws Exception {
        log.info("Starting what-if simulation for {}", processedCsvPath);

        // 1) detect top actionable feature
        var opt = FeatureAnalyzer.selectTopActionableFeature(processedCsvPath);
        if (opt.isEmpty()) {
            log.error("No actionable feature detected for {}", processedCsvPath);
            return;
        }
        String topFeature = opt.get();
        log.info("Top actionable feature: {}", topFeature);

        // 2) split datasets and create B using DatasetSplitter
        String prefix = processedCsvPath.replaceAll("_processed\\.csv$", "");
        DatasetSplitter.split(processedCsvPath, prefix, topFeature);

        // 3) Train best classifier on A_train
        String aTrain = prefix + "_A_train.csv";
        ClassifierRunner runner = new ClassifierRunner(aTrain);
        // load and prepare done inside trainBestClassifier by passing Instances
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(aTrain));
        Instances trainData = loader.getDataSet();
        trainData.setClassIndex(trainData.numAttributes() - 1);

        Classifier cls = runner.trainBestClassifier(trainData);
        if (cls == null) {
            log.error("trainBestClassifier returned null for {}", aTrain);
            return;
        }

        // 4) Predict on A_test, B+, B, C
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
            // predict (this will also validate header consistency)
            runner.predictToCsv(cls, path, out);

            // compute counts
            try (CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(new FileReader(out))) {
                int act = 0, pred = 0;
                for (CSVRecord r : parser) {
                    String a = r.get("IsBuggy");
                    String p = r.get("PredictedIsBuggy");
                    if (a != null && (a.equalsIgnoreCase("yes") || a.equalsIgnoreCase("true") || a.equals("1"))) act++;
                    if (p != null && (p.equalsIgnoreCase("yes") || p.equalsIgnoreCase("true") || p.equals("1"))) pred++;
                }
                actual.put(name, act);
                predicted.put(name, pred);
            }
        }

        // 5) Compute Drop and Reduction and write summary
        int bplusPred = predicted.getOrDefault("B+", 0);
        int bPred = predicted.getOrDefault("B", 0);
        int aActual = actual.getOrDefault("A", 0);
        int drop = Math.max(0, bplusPred - bPred);
        double dropPct = bplusPred == 0 ? 0.0 : (drop * 100.0) / bplusPred;
        double reductionPct = aActual == 0 ? 0.0 : (drop * 100.0) / aActual;

        DecimalFormat df = new DecimalFormat("0.##");
        String summary = prefix + "_whatif_summary.csv";
        try (FileWriter fw = new FileWriter(summary)) {
            fw.write("Dataset,Actual,Predicted\n");
            for (String k : actual.keySet()) fw.write(k + "," + actual.get(k) + "," + predicted.get(k) + "\n");
            fw.write("Drop," + drop + "," + df.format(dropPct) + "%\n");
            fw.write("Reduction," + drop + "," + df.format(reductionPct) + "%\n");
        }

        log.info("What-if analysis complete for {}. Summary written to {}", processedCsvPath, summary);
        log.info("Drop = {} ({}%), Reduction = {}%", drop, df.format(dropPct), df.format(reductionPct));
    }
}