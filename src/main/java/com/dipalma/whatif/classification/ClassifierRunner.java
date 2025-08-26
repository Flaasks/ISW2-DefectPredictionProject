package com.dipalma.whatif.classification;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.unsupervised.attribute.NumericToNominal;

import java.io.File;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClassifierRunner {

    private final String csvFilePath;
    private Instances data;
    private static final Logger log = LoggerFactory.getLogger(ClassifierRunner.class);
    private static final String ROW_FMT    = "%-20s | %-10.3f | %-10.3f | %-10.3f | %-10.3f";
    private static final String HEADER_FMT = "%-20s | %-10s | %-10s | %-10s | %-10s";

    public ClassifierRunner(String csvFilePath) {
        this.csvFilePath = csvFilePath;
    }

    /**
     * This method is now simplified. It only loads the CSV and ensures the class is nominal.
     * All column removal is now done in the DataPreprocessor.
     */
    public void loadAndPrepareData() throws Exception {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(csvFilePath));

        Instances loadedData = loader.getDataSet();

        // Set the class attribute to be the last one
        int classAttrIndex = loadedData.numAttributes() - 1;
        loadedData.setClassIndex(classAttrIndex);

        // This final check ensures the class is nominal for the classifiers
        if (loadedData.classAttribute().isNumeric()) {
            NumericToNominal num2nom = new NumericToNominal();
            num2nom.setAttributeIndices("last");
            num2nom.setInputFormat(loadedData);
            this.data = Filter.useFilter(loadedData, num2nom);
        } else {
            this.data = loadedData;
        }

        if (log.isInfoEnabled()) {
            var clsAttr = this.data.classAttribute();
            var kind    = clsAttr.isNominal() ? "Nominal" : "Categorical";
            log.info("Clean data loaded. Class attribute '{}' is: {}", clsAttr.name(), kind);
        }
        log.info("Using {} attributes for classification.", this.data.numAttributes());
    }

    public void runClassification() throws Exception {
        if (this.data == null) {
            loadAndPrepareData();
        }

        log.info("--- Starting Classifier Evaluation for: {} ---", csvFilePath);
        log.info("Validation Method: 10 times 10-fold Cross-Validation");

        Classifier[] classifiers = {
                new RandomForest(),
                new NaiveBayes(),
                new IBk(3)
        };

        if (log.isInfoEnabled()) {
            log.info("{}", String.format(HEADER_FMT, "Classifier", "AUC", "Precision", "Recall", "Kappa"));
        }
        for (Classifier baseClassifier : classifiers) {
            Resample resample = new Resample();
            resample.setBiasToUniformClass(1.0);

            FilteredClassifier classifierWithResample = new FilteredClassifier();
            classifierWithResample.setClassifier(baseClassifier);
            classifierWithResample.setFilter(resample);

            int numRepeats = 10;
            double totalAuc = 0;
            double totalPrecision = 0;
            double totalKappa = 0;
            double totalRecall = 0;

            for (int i = 0; i < numRepeats; i++) {
                Evaluation eval = new Evaluation(this.data);
                eval.crossValidateModel(classifierWithResample, this.data, 10, new Random(i));

                totalAuc += eval.weightedAreaUnderROC();
                totalPrecision += eval.weightedPrecision();
                totalRecall += eval.weightedRecall();
                totalKappa += eval.kappa();
            }

            if (log.isInfoEnabled()) {
                log.info("{}", String.format(
                        ROW_FMT,
                        baseClassifier.getClass().getSimpleName(),
                        totalAuc / numRepeats,
                        totalPrecision / numRepeats,
                        totalRecall / numRepeats,
                        totalKappa / numRepeats
                ));
            }
        }
    }
}