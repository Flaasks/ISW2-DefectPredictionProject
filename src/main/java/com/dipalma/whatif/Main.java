package com.dipalma.whatif;

import com.dipalma.whatif.analysis.WhatIfSimulator;
import com.dipalma.whatif.analysis.FeatureComparer;
import com.dipalma.whatif.analysis.DataAnalyzer;
import com.dipalma.whatif.classification.ClassifierRunner;
import com.dipalma.whatif.preprocessing.DataPreprocessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {


        log.info("Starting What-If Analysis Data Generation...");

        final String BK_PROCESSED = "BOOKKEEPER_processed.csv";
        final String SN_PROCESSED = "SYNCOPE_processed.csv";


        try {

            // --- STAGE 1: DATASET CREATION ---
            log.info("--- [1/3] CREATING DATASETS ---");
            DatasetGenerator bookkeeperGenerator = new DatasetGenerator("BOOKKEEPER", "https://github.com/apache/bookkeeper.git");
            bookkeeperGenerator.generateCsv();

            DatasetGenerator syncopeGenerator = new DatasetGenerator("SYNCOPE", "https://github.com/apache/syncope.git");
            syncopeGenerator.generateCsv();
            log.info("--- DATASET CREATION COMPLETE ---");


            // --- STAGE 2: DATA PREPROCESSING ---
            log.info("--- [2/3] PREPROCESSING DATASETS ---");
            DataPreprocessor bookkeeperProcessor = new DataPreprocessor("BOOKKEEPER.csv");
            bookkeeperProcessor.processData();

            DataPreprocessor syncopeProcessor = new DataPreprocessor("SYNCOPE.csv");
            syncopeProcessor.processData();
            log.info("--- PREPROCESSING COMPLETE ---");


            // --- STAGE 3: CLASSIFIER EVALUATION ---
            log.info("--- [3/4] EVALUATING CLASSIFIERS ---");
            ClassifierRunner bookkeeperRunner = new ClassifierRunner(BK_PROCESSED);
            bookkeeperRunner.runClassification();

            ClassifierRunner syncopeRunner = new ClassifierRunner(SN_PROCESSED);
            syncopeRunner.runClassification();
            log.info("--- CLASSIFIER EVALUATION COMPLETE ---");

            // --- STAGE 4: FEATURE & METHOD SELECTION (NEW STEP) ---
            log.info("--- [4/4] SELECTING FEATURE AND METHOD FOR SIMULATION ---");
            // We use the original CSV to get true feature values and the processed CSV for correlation
            DataAnalyzer bookkeeperAnalyzer = new DataAnalyzer("BOOKKEEPER.csv", BK_PROCESSED);
            bookkeeperAnalyzer.findActionableFeatureAndMethod();

            DataAnalyzer syncopeAnalyzer = new DataAnalyzer("SYNCOPE.csv", SN_PROCESSED);
            syncopeAnalyzer.findActionableFeatureAndMethod();
            log.info("--- FEATURE & METHOD SELECTION COMPLETE ---");


            log.info("--- CLASSIFIER EVALUATION COMPLETE ---");

            FeatureComparer comparer = new FeatureComparer();

            log.info("--- METHOD COMPARING COMPLETE ---");
            // --- Analysis for BookKeeper ---
            String bookkeeperOriginal = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif/Bookkeeper_Original.txt";
            String bookkeeperRefactored = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif//Bookkeeper_Refactored.txt";
            comparer.compareMethods(bookkeeperOriginal, bookkeeperRefactored);

            // --- Analysis for Syncope ---
            String syncopeOriginal = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif/Syncope_Original.txt";
            String syncopeRefactored = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif/Syncope_Refactored.txt";
            comparer.compareMethods(syncopeOriginal, syncopeRefactored);

            // STAGE 3: FINAL WHAT-IF ANALYSIS
            log.info("--- What-if Analysis ---");

            log.info("--- Analysis for BOOKKEEPER ---");
            WhatIfSimulator bookkeeperSimulator = new WhatIfSimulator(BK_PROCESSED);
            bookkeeperSimulator.runFullDatasetSimulation();

            log.info("--- Analysis for SYNCOPE ---");
            WhatIfSimulator syncopeSimulator = new WhatIfSimulator(SN_PROCESSED);
            syncopeSimulator.runFullDatasetSimulation();

        } catch (Exception e) {
            e.printStackTrace();
        }

        log.info("All projects processed and evaluated.");
    }
}