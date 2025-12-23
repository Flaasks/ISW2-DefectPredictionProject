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
import weka.core.SerializationHelper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import java.util.ArrayList;
import java.util.List;

import java.io.File;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClassifierRunner {

    private final String csvFilePath;
    private Instances data;
    private List<String> lastTrainingHeader = null;
    private static final Logger log = LoggerFactory.getLogger(ClassifierRunner.class);
    private static final String ROW_FMT    = "%-20s | %-10.3f | %-10.3f | %-10.3f | %-10.3f";
    private static final String HEADER_FMT = "%-20s | %-10s | %-10s | %-10s | %-10s";

    public ClassifierRunner(String csvFilePath) {
        this.csvFilePath = csvFilePath;
    }


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
            // Wrap with resample and evaluate via shared utility
            weka.classifiers.meta.FilteredClassifier fc = com.dipalma.whatif.util.ClassificationUtils.wrapWithResample(baseClassifier, 1);

            double[] prec = new double[1];
            double[] rec  = new double[1];
            double[] kap  = new double[1];
            double avgAuc = com.dipalma.whatif.util.ClassificationUtils.evaluate10x10CV(fc, this.data, prec, rec, kap);

            if (log.isInfoEnabled()) {
                log.info("{}", String.format(
                        ROW_FMT,
                        baseClassifier.getClass().getSimpleName(),
                        avgAuc,
                        prec[0],
                        rec[0],
                        kap[0]
                ));
            }
        }
    }

    /**
     * Train classifiers on provided Instances and return the best classifier (by average AUC over repeats).
     */
    public Classifier trainBestClassifier(Instances dataset) throws Exception {
        Classifier[] classifiers = {
                new RandomForest(),
                new NaiveBayes(),
                new IBk(3)
        };

    FilteredClassifier best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Classifier c : classifiers) {
            FilteredClassifier fc = com.dipalma.whatif.util.ClassificationUtils.wrapWithResample(c, 1);
            double avgAuc = com.dipalma.whatif.util.ClassificationUtils.evaluate10x10CV(fc, dataset, null, null, null);
            if (avgAuc > bestScore) {
                bestScore = avgAuc;
                best = fc;
                // Train best on full dataset AFTER selection 
                // but postpone building until after loop to keep best as chosen fc
            }
        }
        if (best != null) {
            // record header for later validation
            lastTrainingHeader = new ArrayList<>();
            for (int i = 0; i < dataset.numAttributes(); i++) lastTrainingHeader.add(dataset.attribute(i).name());
            best.buildClassifier(dataset);
        }
        return best;
    }

    public void saveModel(Classifier cls, String path) throws Exception {
        SerializationHelper.write(path, cls);
    }

    public void predictToCsv(Classifier cls, String inputCsv, String outCsv) throws Exception {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new java.io.File(inputCsv));
        Instances data = loader.getDataSet();
        data.setClassIndex(data.numAttributes() - 1);

        // Validate attribute headers if we have a recorded training header
        if (lastTrainingHeader != null) {
            if (lastTrainingHeader.size() != data.numAttributes()) {
                throw new IllegalArgumentException("Input CSV does not match training attributes (different attribute count). Use the same processed CSV used for training.");
            }
            for (int i = 0; i < data.numAttributes(); i++) {
                if (!lastTrainingHeader.get(i).equals(data.attribute(i).name())) {
                    throw new IllegalArgumentException("Input CSV attribute names/order differ from training dataset. Ensure you pass the same processed CSV or reorder attributes.");
                }
            }
        }

        try (java.io.FileWriter fw = new java.io.FileWriter(outCsv);
             CSVPrinter printer = new CSVPrinter(fw, CSVFormat.DEFAULT)) {
            // header
            List<String> header = new ArrayList<>();
            for (int i = 0; i < data.numAttributes(); i++) header.add(data.attribute(i).name());
            header.add("PredictedIsBuggy");
            printer.printRecord(header);

            for (int i = 0; i < data.numInstances(); i++) {
                double pred = cls.classifyInstance(data.instance(i));
                List<String> rec = new ArrayList<>();
                for (int a = 0; a < data.numAttributes(); a++) rec.add(data.instance(i).toString(a));
                rec.add(data.classAttribute().value((int) pred));
                printer.printRecord(rec);
            }
        }
    }

    /**
     * Count actual and predicted buggy instances for an in-memory Instances object.
     * Returns an int array [actualCount, predictedCount].
     */
    public int[] actualAndPredicted(Classifier cls, Instances data) throws Exception {
        if (data == null || data.numInstances() == 0) return new int[]{0, 0};
        if (data.classIndex() == -1) data.setClassIndex(data.numAttributes() - 1);

        weka.core.Attribute classAttr = data.classAttribute();
        // Determine positive class index, fallback to second nominal value
        int positiveIndex = classAttr.indexOfValue("1");
        if (positiveIndex == -1) positiveIndex = classAttr.indexOfValue("true");
        if (positiveIndex == -1) positiveIndex = Math.min(1, classAttr.numValues() - 1);

        int actual = 0;
        int predicted = 0;

        for (int i = 0; i < data.numInstances(); i++) {
            weka.core.Instance inst = data.instance(i);

            // count actual based on instance class value
            int instClassIndex = (int) inst.classValue();
            if (instClassIndex == positiveIndex) actual++;

            int predIndex;
            try {
                double p = cls.classifyInstance(inst);
                predIndex = (int) p;
            } catch (Exception e) {
                double[] dist = cls.distributionForInstance(inst);
                predIndex = 0;
                for (int j = 1; j < dist.length; j++) {
                    if (dist[j] > dist[predIndex]) predIndex = j;
                }
            }

            if (predIndex == positiveIndex) predicted++;
        }

        return new int[]{actual, predicted};
    }
}