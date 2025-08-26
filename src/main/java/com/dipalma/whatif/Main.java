package com.dipalma.whatif;

import com.dipalma.whatif.analysis.WhatIfSimulator;
import com.dipalma.whatif.analysis.FeatureComparer;
import com.dipalma.whatif.analysis.DataAnalyzer;
import com.dipalma.whatif.classification.ClassifierRunner;
import com.dipalma.whatif.preprocessing.DataPreprocessor;

public class Main {
    public static void main(String[] args) {
        System.out.println("Starting What-If Analysis Data Generation...");

        try {
            // --- STAGE 1: DATASET CREATION ---
            System.out.println("\n--- [1/3] CREATING DATASETS ---");
            DatasetGenerator bookkeeperGenerator = new DatasetGenerator("BOOKKEEPER", "https://github.com/apache/bookkeeper.git");
            bookkeeperGenerator.generateCsv();

            DatasetGenerator syncopeGenerator = new DatasetGenerator("SYNCOPE", "https://github.com/apache/syncope.git");
            syncopeGenerator.generateCsv();
            System.out.println("--- DATASET CREATION COMPLETE ---");


            // --- STAGE 2: DATA PREPROCESSING ---
            System.out.println("\n--- [2/3] PREPROCESSING DATASETS ---");
            DataPreprocessor bookkeeperProcessor = new DataPreprocessor("BOOKKEEPER.csv");
            bookkeeperProcessor.processData();

            DataPreprocessor syncopeProcessor = new DataPreprocessor("SYNCOPE.csv");
            syncopeProcessor.processData();
            System.out.println("--- PREPROCESSING COMPLETE ---");


            // --- STAGE 3: CLASSIFIER EVALUATION ---
            System.out.println("\n--- [3/4] EVALUATING CLASSIFIERS ---");
            ClassifierRunner bookkeeperRunner = new ClassifierRunner("BOOKKEEPER_processed.csv");
            bookkeeperRunner.runClassification();

            ClassifierRunner syncopeRunner = new ClassifierRunner("SYNCOPE_processed.csv");
            syncopeRunner.runClassification();
            System.out.println("--- CLASSIFIER EVALUATION COMPLETE ---");

            // --- STAGE 4: FEATURE & METHOD SELECTION (NEW STEP) ---
            System.out.println("\n--- [4/4] SELECTING FEATURE AND METHOD FOR SIMULATION ---");
            // We use the original CSV to get true feature values and the processed CSV for correlation
            DataAnalyzer bookkeeperAnalyzer = new DataAnalyzer("BOOKKEEPER.csv", "BOOKKEEPER_processed.csv");
            bookkeeperAnalyzer.findActionableFeatureAndMethod();

            DataAnalyzer syncopeAnalyzer = new DataAnalyzer("SYNCOPE.csv", "SYNCOPE_processed.csv");
            syncopeAnalyzer.findActionableFeatureAndMethod();
            System.out.println("--- FEATURE & METHOD SELECTION COMPLETE ---");


            System.out.println("--- CLASSIFIER EVALUATION COMPLETE ---");

            FeatureComparer comparer = new FeatureComparer();

            System.out.println("--- METHOD COMPARING COMPLETE ---");
            // --- Analysis for BookKeeper ---
            String bookkeeperOriginal = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif/Bookkeeper_Original.txt";
            String bookkeeperRefactored = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif//Bookkeeper_Refactored.txt";
            comparer.compareMethods(bookkeeperOriginal, bookkeeperRefactored);

            // --- Analysis for Syncope ---
            String syncopeOriginal = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif/Syncope_Original.txt";
            String syncopeRefactored = "D:/isw2project/DefectPredictionProject/src/main/java/com/dipalma/whatif/Syncope_Refactored.txt";
            comparer.compareMethods(syncopeOriginal, syncopeRefactored);

            // STAGE 3: FINAL WHAT-IF ANALYSIS
            System.out.println("\n--- What-if Analysis ---");

            System.out.println("\n--- Analysis for BOOKKEEPER ---");
            WhatIfSimulator bookkeeperSimulator = new WhatIfSimulator("BOOKKEEPER_processed.csv");
            bookkeeperSimulator.runFullDatasetSimulation();

            System.out.println("\n--- Analysis for SYNCOPE ---");
            WhatIfSimulator syncopeSimulator = new WhatIfSimulator("SYNCOPE_processed.csv");
            syncopeSimulator.runFullDatasetSimulation();

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\nAll projects processed and evaluated.");
    }
}