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


        log.info("Starting What-If Analysis Data Generation");

        final String BK_PROCESSED = "BOOKKEEPER_processed.csv";
        final String SN_PROCESSED = "SYNCOPE_processed.csv";

        java.io.File bkProcessed = new java.io.File(BK_PROCESSED);
        java.io.File snProcessed = new java.io.File(SN_PROCESSED);


        
        try {


            // STAGE 1: DATASET CREATION
            log.info("--- CREATING DATASETS ---");
            if (bkProcessed.exists()) {
                log.info("Found existing {} - skipping dataset generation.", BK_PROCESSED);
            } else {
                DatasetGenerator bookkeeperGenerator = new DatasetGenerator("BOOKKEEPER", "https://github.com/apache/bookkeeper.git");
                bookkeeperGenerator.generateCsv();
            }

            if (snProcessed.exists()) {
                log.info("Found existing {} - skipping dataset generation.", SN_PROCESSED);
            } else {
                DatasetGenerator syncopeGenerator = new DatasetGenerator("SYNCOPE", "https://github.com/apache/syncope.git");
                syncopeGenerator.generateCsv();
            }
            log.info("--- DATASET CREATION COMPLETE ---");


            // STAGE 2: DATA PREPROCESSING 
            log.info("--- PREPROCESSING DATASETS ---");
            if (bkProcessed.exists()) {
                log.info("Skipping preprocessing for BOOKKEEPER because {} already exists", BK_PROCESSED);
            } else {
                DataPreprocessor bookkeeperProcessor = new DataPreprocessor("BOOKKEEPER.csv");
                bookkeeperProcessor.processData();
            }

            if (snProcessed.exists()) {
                log.info("Skipping preprocessing for SYNCOPE because {} already exists", SN_PROCESSED);
            } else {
                DataPreprocessor syncopeProcessor = new DataPreprocessor("SYNCOPE.csv");
                syncopeProcessor.processData();
            }
            log.info("--- PREPROCESSING COMPLETE ---");


            // STAGE 3: CLASSIFIER EVALUATION 
            log.info("--- EVALUATING CLASSIFIERS ---");
            ClassifierRunner bookkeeperRunner = new ClassifierRunner(BK_PROCESSED);
            bookkeeperRunner.runClassification();

            ClassifierRunner syncopeRunner = new ClassifierRunner(SN_PROCESSED);
            syncopeRunner.runClassification();
            log.info("--- CLASSIFIER EVALUATION COMPLETE ---");

            // STAGE 4: FEATURE & METHOD SELECTION
            log.info("--- SELECTING FEATURE AND METHOD FOR SIMULATION ---");
            DataAnalyzer bookkeeperAnalyzer = new DataAnalyzer("BOOKKEEPER.csv", BK_PROCESSED);
            bookkeeperAnalyzer.findActionableFeatureAndMethod();
            String bkSelectedMethod = bookkeeperAnalyzer.getSelectedMethodName();
            String bkSelectedFeature = bookkeeperAnalyzer.getSelectedFeatureName();
            log.info("Bookkeeper selected AFMethod: {}", bkSelectedMethod);
            log.info("Bookkeeper selected AFeature: {}", bkSelectedFeature);

            DataAnalyzer syncopeAnalyzer = new DataAnalyzer("SYNCOPE.csv", SN_PROCESSED);
            syncopeAnalyzer.findActionableFeatureAndMethod();
            String snSelectedMethod = syncopeAnalyzer.getSelectedMethodName();
            String snSelectedFeature = syncopeAnalyzer.getSelectedFeatureName();
            log.info("Syncope selected AFMethod: {}", snSelectedMethod);
            log.info("Syncope selected AFeature: {}", snSelectedFeature);
            log.info("--- FEATURE & METHOD SELECTION COMPLETE ---");


            FeatureComparer comparer = new FeatureComparer();

            log.info("--- METHOD COMPARING COMPLETE ---");

            String bookkeeperOriginal = "src/main/java/com/dipalma/whatif/Bookkeeper_Original.txt";
            String bookkeeperRefactored = "src/main/java/com/dipalma/whatif/Bookkeeper_Refactored.txt";
            comparer.compareMethods(bookkeeperOriginal, bookkeeperRefactored);


            String syncopeOriginal = "src/main/java/com/dipalma/whatif/Syncope_Original.txt";
            String syncopeRefactored = "src/main/java/com/dipalma/whatif/Syncope_Refactored.txt";
            comparer.compareMethods(syncopeOriginal, syncopeRefactored);

            // STAGE 5: FINAL WHAT-IF ANALYSIS
            log.info("--- What-if Analysis ---");

            log.info("--- Analysis for BOOKKEEPER ---");
            WhatIfSimulator bookkeeperSimulator = new WhatIfSimulator(BK_PROCESSED);
            bookkeeperSimulator.runFullDatasetSimulation(bkSelectedFeature);

            log.info("--- Analysis for SYNCOPE ---");
            WhatIfSimulator syncopeSimulator = new WhatIfSimulator(SN_PROCESSED);
            syncopeSimulator.runFullDatasetSimulation(snSelectedFeature);

        } catch (Exception e) {
            e.printStackTrace();
        }

        log.info("All projects processed and evaluated.");
    }
}