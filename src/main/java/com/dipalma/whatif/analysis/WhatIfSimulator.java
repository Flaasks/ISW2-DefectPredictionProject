package com.dipalma.whatif.analysis;

import com.dipalma.whatif.classification.ClassifierRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.classifiers.Classifier;
import weka.core.Instances;

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

    // 3) Train best classifier on the full A dataset (use all processed data per directive)
    ClassifierRunner runner = new ClassifierRunner(processedCsvPath); // path kept for logging only
    Instances trainData = split.all; // train on full processed dataset A
    Classifier cls = runner.trainBestClassifier(trainData);
        if (cls == null) {
            log.error("trainBestClassifier returned null for {}", processedCsvPath);
            return;
        }
        // 4) Predict in-memory and compute counts for A_test, B+, B, C
        Map<String, Integer> actual = new LinkedHashMap<>();
        Map<String, Integer> predicted = new LinkedHashMap<>();

    // Use the entire processed dataset as A (do NOT use only the test split). This ensures "Actual" for A
    // reflects the whole processed CSV as requested.
    int[] aCounts = runner.actualAndPredicted(cls, split.all);
    actual.put("A", aCounts[0]); predicted.put("A", aCounts[1]);

        int[] bPlusCounts = runner.actualAndPredicted(cls, split.bPlus);
        actual.put("B+", bPlusCounts[0]); predicted.put("B+", bPlusCounts[1]);

        int[] bCounts = runner.actualAndPredicted(cls, split.b);
        actual.put("B", bCounts[0]); predicted.put("B", bCounts[1]);

        int[] cCounts = runner.actualAndPredicted(cls, split.c);
        actual.put("C", cCounts[0]); predicted.put("C", cCounts[1]);

        // 5) Compute Drop and Reduction and write summary
        int bplusPred = predicted.getOrDefault("B+", 0);
        int bPred = predicted.getOrDefault("B", 0);
        int aActual = actual.getOrDefault("A", 0);
        int drop = Math.max(0, bplusPred - bPred);
        double dropPct = bplusPred == 0 ? 0.0 : (drop * 100.0) / bplusPred;
        double rawReductionPct = aActual == 0 ? 0.0 : (drop * 100.0) / aActual;
        // Reduction represents the share of actual buggy instances in A that would be removed.
        // Cap at 100% because you cannot reduce more than the total actual buggy instances.
        double reductionPct = Math.min(100.0, rawReductionPct);
        if (rawReductionPct > 100.0) {
            log.warn("Computed raw reduction {}% is >100% (drop={} aActual={}). Capping to 100%.", rawReductionPct, drop, aActual);
        }

        DecimalFormat df = new DecimalFormat("0.##");
    String prefix = processedCsvPath.replaceAll("_processed\\.csv$", "");
    String summary = prefix + "_whatif_summary.csv";
        try (FileWriter fw = new FileWriter(summary)) {
            fw.write("Dataset,Actual,Predicted\n");
            for (String k : actual.keySet()) fw.write(k + "," + actual.get(k) + "," + predicted.get(k) + "\n");
            fw.write("Drop," + drop + "," + df.format(dropPct) + "%\n");
            // write both raw and capped reduction to help debugging if needed
            fw.write("ReductionRaw," + drop + "," + df.format(rawReductionPct) + "%\n");
            fw.write("ReductionCapped," + drop + "," + df.format(reductionPct) + "%\n");
        }

        log.info("What-if analysis complete for {}. Summary written to {}", processedCsvPath, summary);
        log.info("Drop = {} ({}%), Reduction = {}% (raw={}%)", drop, df.format(dropPct), df.format(reductionPct), df.format(rawReductionPct));
    }
}