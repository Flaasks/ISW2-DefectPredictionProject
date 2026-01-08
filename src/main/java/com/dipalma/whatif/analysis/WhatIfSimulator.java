package com.dipalma.whatif.analysis;

import com.dipalma.whatif.classification.ClassifierRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.classifiers.Classifier;
import com.dipalma.whatif.util.ClassLabelUtils;
import weka.core.Instances;

import java.io.FileWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public class WhatIfSimulator {

    private final String processedCsvPath;
    private static final Logger log = LoggerFactory.getLogger(WhatIfSimulator.class);

    public WhatIfSimulator(String processedCsvPath) {
        this.processedCsvPath = processedCsvPath;
    }

    // Use shared utility to determine positive class index

    // Calculate prevalence-matched threshold: find threshold t such that predictions on A match Actual_A
    private double prevalenceMatchedThreshold(Classifier cls, Instances data, int posIdx, int actualPos) throws Exception {
        int n = data.numInstances();
        if (actualPos <= 0) return 1.0;
        if (actualPos >= n) return 0.0;
        
        ArrayList<Double> probs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double[] dist = cls.distributionForInstance(data.instance(i));
            double p = (posIdx >= 0 && posIdx < dist.length) ? dist[posIdx] : 0.0;
            probs.add(p);
        }
        
        Collections.sort(probs, Collections.reverseOrder());
        double threshold = probs.get(Math.min(actualPos - 1, probs.size() - 1));
        return Math.clamp(threshold + 1e-9, 0.0, 1.0);
    }

    // Count predicted positives using threshold instead of argmax
    private int countPredictedWithThreshold(Classifier cls, Instances data, int posIdx, double threshold) throws Exception {
        int count = 0;
        for (int i = 0; i < data.numInstances(); i++) {
            double[] dist = cls.distributionForInstance(data.instance(i));
            double p = (posIdx >= 0 && posIdx < dist.length) ? dist[posIdx] : 0.0;
            if (p >= threshold) count++;
        }
        return count;
    }

    public void runFullDatasetSimulation() throws Exception {
        runFullDatasetSimulation(null);
    }

    /**
     * Run the simulation using a preselected actionable feature. If actionableFeature is null,
     * the simulator will detect it automatically (legacy behavior).
     */
    public void runFullDatasetSimulation(String actionableFeature) throws Exception {
        log.info("Starting what-if simulation for {}", processedCsvPath);

        // 1) determine top actionable feature (prefer provided value)
        String topFeature = actionableFeature;
        if (topFeature == null) {
            var opt = FeatureAnalyzer.selectTopActionableFeature(processedCsvPath);
            if (opt.isEmpty()) {
                log.error("No actionable feature detected for {}", processedCsvPath);
                return;
            }
            topFeature = opt.get();
            log.info("Top actionable feature (detected): {}", topFeature);
        } else {
            log.info("Top actionable feature (provided): {}", topFeature);
        }

        // 2) split datasets in memory and create B set
        DatasetSplitter.InMemorySplit split = DatasetSplitter.splitInMemory(processedCsvPath, topFeature);

    // 3) Train best classifier on the full A dataset (use all processed data)
    ClassifierRunner runner = new ClassifierRunner(processedCsvPath); // path kept for logging only
    Instances trainData = split.all; // train on full processed dataset A
    Classifier cls = runner.trainBestClassifier(trainData);
        if (cls == null) {
            log.error("trainBestClassifier returned null for {}", processedCsvPath);
            return;
        }

        // 4) Count actual values using existing method
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("A", runner.actualAndPredicted(cls, split.all)[0]);
        actual.put("B+", runner.actualAndPredicted(cls, split.bPlus)[0]);
        actual.put("B", runner.actualAndPredicted(cls, split.b)[0]);
        actual.put("C", runner.actualAndPredicted(cls, split.c)[0]);

        // 5) Calculate prevalence-matched threshold on A and use it for all predictions
        int posIdx = ClassLabelUtils.positiveIndex(split.all);
        int aActual = actual.get("A");
        double threshold = prevalenceMatchedThreshold(cls, split.all, posIdx, aActual);
        if (log.isInfoEnabled()) {
            DecimalFormat tdf = new DecimalFormat("0.0000");
            log.info("Computed prevalence-matched threshold: {} (to match {} actual positives in A)", tdf.format(threshold), aActual);
        }

        // 6) Count predicted using threshold instead of argmax
        Map<String, Integer> predicted = new LinkedHashMap<>();
        predicted.put("A", countPredictedWithThreshold(cls, split.all, posIdx, threshold));
        predicted.put("B+", countPredictedWithThreshold(cls, split.bPlus, posIdx, threshold));
        predicted.put("B", countPredictedWithThreshold(cls, split.b, posIdx, threshold));
        predicted.put("C", countPredictedWithThreshold(cls, split.c, posIdx, threshold));

        // 7) Compute Drop and Reduction
        int aActualVal = actual.get("A");
        int bPlusActual = actual.get("B+");
        int bPred = predicted.get("B");

        // DROP: (Actual_B+ - Predicted_B) / Actual_B+ × 100
        double dropPct = (bPlusActual > 0) ? ((bPlusActual - bPred) * 100.0 / bPlusActual) : 0.0;

        // REDUCTION: (Actual_B+ - Predicted_B) / Actual_A × 100
        double reductionPct = (aActualVal > 0) ? ((bPlusActual - bPred) * 100.0 / aActualVal) : 0.0;

        DecimalFormat df = new DecimalFormat("0.##");
        String prefix = processedCsvPath.replaceAll("_processed\\.csv$", "");
        String summary = prefix + "_whatif_summary.csv";
        try (FileWriter fw = new FileWriter(summary)) {
            fw.write("Dataset,Actual,Predicted\n");
            for (var entry : actual.entrySet()) {
                fw.write(entry.getKey() + "," + entry.getValue() + "," + predicted.get(entry.getKey()) + "\n");
            }
            fw.write("Drop," + df.format(dropPct) + "%\n");
            fw.write("Reduction," + df.format(reductionPct) + "%\n");
        }

        if (log.isInfoEnabled()) {
            log.info("What-if analysis complete for {}. Summary written to {}", processedCsvPath, summary);
            log.info("Drop = {}%, Reduction = {}%", df.format(dropPct), df.format(reductionPct));
        }
    }
}