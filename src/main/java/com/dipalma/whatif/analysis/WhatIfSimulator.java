package com.dipalma.whatif.analysis;

import weka.classifiers.Classifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.unsupervised.attribute.NumericToNominal;

import java.io.File;

public class WhatIfSimulator {

    private final String processedCsvPath;  
    private Instances datasetA;

    public WhatIfSimulator(String processedCsvPath) {
        this.processedCsvPath = processedCsvPath;
    }

    private void loadAndPrepareData() throws Exception {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(processedCsvPath));
        Instances rawData = loader.getDataSet();

        int classAttrIndex = rawData.numAttributes() - 1;
        rawData.setClassIndex(classAttrIndex);

        if (rawData.classAttribute().isNumeric()) {
            NumericToNominal num2nom = new NumericToNominal();
            num2nom.setAttributeIndices("last");
            num2nom.setInputFormat(rawData);
            this.datasetA = Filter.useFilter(rawData, num2nom);
        } else {
            this.datasetA = rawData;
        }

        System.out.println("Loaded and prepared dataset A with " + datasetA.numInstances() + " instances and " + datasetA.numAttributes() + " attributes.");
    }

    /**
     * Runs the final What-If analysis (Steps 10-13).
     */
    public void runFullDatasetSimulation() throws Exception {
        // Load the data first
        loadAndPrepareData();

        // --- Step 10: Create datasets B+, C, and B ---
        System.out.println("\n--- Step 10: Creating What-If Datasets ---");
        // Our chosen Actionable Feature
        String aFeature = "LOC";
        Attribute locAttribute = datasetA.attribute(aFeature);
        if (locAttribute == null) {
            System.out.println("Error: Could not find AFeature '" + aFeature + "' in the dataset.");
            return;
        }

        Instances datasetBplus = new Instances(datasetA, 0);
        Instances datasetC = new Instances(datasetA, 0);

        for (int i = 0; i < datasetA.numInstances(); i++) {
            if (datasetA.instance(i).value(locAttribute) > 1) {
                datasetBplus.add(datasetA.instance(i));
            } else {
                datasetC.add(datasetA.instance(i));
            }
        }
        System.out.println("Created Dataset B+ (size: " + datasetBplus.numInstances() + ") and C (size: " + datasetC.numInstances() + ")");

        Instances datasetB = new Instances(datasetBplus);
        for (int i = 0; i < datasetB.numInstances(); i++) {
            datasetB.instance(i).setValue(locAttribute, 1.0);
        }
        System.out.println("Created synthetic Dataset B by setting LOC to 1 for all instances in B+.");

        // --- Step 11: Train BClassifier on the full dataset A ---
        System.out.println("\n--- Step 11: Training BClassifier (RandomForest) on full dataset A ---");
        Classifier bClassifier = new RandomForest();
        Resample resample = new Resample();
        resample.setBiasToUniformClass(1.0);

        FilteredClassifier trainedModel = new FilteredClassifier();
        trainedModel.setFilter(resample);
        trainedModel.setClassifier(bClassifier);

        trainedModel.buildClassifier(datasetA);
        System.out.println("Model training complete.");

        // --- Step 12: Predict on all datasets and create the results table ---
        System.out.println("\n--- Step 12: Predicting Defectiveness and Creating Results Table ---");
        int defectsInA = countDefectivePredictions(trainedModel, datasetA);
        int defectsInBplus = countDefectivePredictions(trainedModel, datasetBplus);
        int defectsInB = countDefectivePredictions(trainedModel, datasetB);
        int defectsInC = countDefectivePredictions(trainedModel, datasetC);

        final String ROW_FMT = "| %-20s | %-15d | %-15d |%n";

        System.out.println("                      WHAT-IF ANALYSIS RESULTS                      ");
        System.out.printf("| %-20s | %-15s | %-15s |%n", "Dataset", "Total Instances", "Predicted Defects");
        System.out.printf(ROW_FMT, "A (Full Dataset)", datasetA.numInstances(), defectsInA);
        System.out.printf(ROW_FMT, "B+ (LOC > 1)", datasetBplus.numInstances(), defectsInBplus);
        System.out.printf(ROW_FMT, "B (B+ with LOC=1)", datasetB.numInstances(), defectsInB);
        System.out.printf(ROW_FMT, "C (LOC <= 1)", datasetC.numInstances(), defectsInC);

        // --- Step 13: Analyze the table and answer the main question ---
        System.out.println("\n--- Step 13: Final Analysis ---");
        if (defectsInBplus > 0) {
            double preventable = defectsInBplus - defectsInB;
            double reductionOutOfPreventable = (preventable / defectsInBplus) * 100;

            System.out.printf("By simulating the reduction of LOC, the number of predicted buggy methods in the 'at-risk' group (B+) dropped from %d to %d.%n", defectsInBplus, defectsInB);
            System.out.printf("This represents a %.2f%% reduction among the methods that could be refactored.%n", reductionOutOfPreventable);
            System.out.printf("\nANSWER: An estimated %.0f buggy methods could have been prevented by having low Lines of Code.%n", preventable);
        } else {
            System.out.println("No defects were predicted in the 'at-risk' group (B+), so no preventable defects were found.");
        }
    }

    private int countDefectivePredictions(Classifier model, Instances data) throws Exception {
        int defectiveCount = 0;
        double buggyClassIndex = data.classAttribute().indexOfValue("1");
        if (buggyClassIndex == -1) {
            buggyClassIndex = data.classAttribute().indexOfValue("yes");
        }

        for (int i = 0; i < data.numInstances(); i++) {
            if (model.classifyInstance(data.instance(i)) == buggyClassIndex) {
                defectiveCount++;
            }
        }
        return defectiveCount;
    }
}