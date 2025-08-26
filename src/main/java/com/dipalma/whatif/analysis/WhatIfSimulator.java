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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhatIfSimulator {

    private final String processedCsvPath;  
    private Instances datasetA;
    private static final Logger log = LoggerFactory.getLogger(WhatIfSimulator.class);
    private static final String TABLE_HEADER_FMT = "| %-20s | %-15s | %-15s |";
    private static final String ROW_FMT_NO_NL    = "| %-20s | %-15d | %-15d |";

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

        log.info("Loaded and prepared dataset A with {} instances and {} attributes.", datasetA.numInstances(), datasetA.numAttributes());
    }

    /**
     * Runs the final What-If analysis (Steps 10-13).
     */
    public void runFullDatasetSimulation() throws Exception {
        // Load the data first
        loadAndPrepareData();

        // --- Step 10: Create datasets B+, C, and B ---
        log.info("--- Step 10: Creating What-If Datasets ---");
        // Our chosen Actionable Feature
        String aFeature = "LOC";
        Attribute locAttribute = datasetA.attribute(aFeature);
        if (locAttribute == null) {
            log.info("Error: Could not find AFeature '{}' in the dataset.", aFeature);
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
        log.info("Created Dataset B+ (size: {}) and C (size: {})", datasetBplus.numInstances(), datasetC.numInstances());

        Instances datasetB = new Instances(datasetBplus);
        for (int i = 0; i < datasetB.numInstances(); i++) {
            datasetB.instance(i).setValue(locAttribute, 1.0);
        }
        log.info("Created synthetic Dataset B by setting LOC to 1 for all instances in B+.");

        // --- Step 11: Train BClassifier on the full dataset A ---
        log.info("--- Step 11: Training BClassifier (RandomForest) on full dataset A ---");
        Classifier bClassifier = new RandomForest();
        Resample resample = new Resample();
        resample.setBiasToUniformClass(1.0);

        FilteredClassifier trainedModel = new FilteredClassifier();
        trainedModel.setFilter(resample);
        trainedModel.setClassifier(bClassifier);

        trainedModel.buildClassifier(datasetA);
        log.info("Model training complete.");

        // --- Step 12: Predict on all datasets and create the results table ---
        log.info("--- Step 12: Predicting Defectiveness and Creating Results Table ---");
        int defectsInA = countDefectivePredictions(trainedModel, datasetA);
        int defectsInBplus = countDefectivePredictions(trainedModel, datasetBplus);
        double defectsInB = countDefectivePredictions(trainedModel, datasetB);
        int defectsInC = countDefectivePredictions(trainedModel, datasetC);

        log.info("                      WHAT-IF ANALYSIS RESULTS                      ");
        if (log.isInfoEnabled()) {
            log.info("{}", String.format(TABLE_HEADER_FMT, "Dataset", "Total Instances", "Predicted Defects"));
            log.info("{}", String.format(ROW_FMT_NO_NL, "A (Full Dataset)",  datasetA.numInstances(), defectsInA));
            log.info("{}", String.format(ROW_FMT_NO_NL, "B+ (LOC > 1)",      datasetBplus.numInstances(), defectsInBplus));
            log.info("{}", String.format(ROW_FMT_NO_NL, "B (B+ with LOC=1)", datasetB.numInstances(),    defectsInB));
            log.info("{}", String.format(ROW_FMT_NO_NL, "C (LOC <= 1)",      datasetC.numInstances(),    defectsInC));
        }

        // --- Step 13: Analyze the table and answer the main question ---
        log.info("--- Step 13: Final Analysis ---");
        if (defectsInBplus > 0) {
            double preventable = defectsInBplus - defectsInB;
            double reductionOutOfPreventable = (preventable / defectsInBplus) * 100;

            log.info("By simulating the reduction of LOC, the number of predicted buggy methods in the 'at-risk' group (B+) dropped from {} to {}.",
                    defectsInBplus, defectsInB);
            if (log.isInfoEnabled()) {
                String pct = String.format("%.2f", reductionOutOfPreventable);
                log.info("This represents a {}% reduction among the methods that could be refactored.", pct);
            }
            log.info("ANSWER: An estimated {} buggy methods could have been prevented by having low Lines of Code.",
                    Math.round(preventable));
        } else {
            log.info("No defects were predicted in the 'at-risk' group (B+), so no preventable defects were found.");
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