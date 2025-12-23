package com.dipalma.whatif.preprocessing;


import weka.core.Attribute;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.core.converters.CSVSaver;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.Normalize;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataPreprocessor {

    private final String inputFilePath;
    private final String outputFilePath;

    private static final Logger log = LoggerFactory.getLogger(DataPreprocessor.class);

    private static final String RELEASE_ATTR = "Release";
    private static final double OUTLIER_STD_MULTIPLIER = 3.0;

    private record Bounds(double lower, double upper) {
    }

    public DataPreprocessor(String inputFilePath) {
        this.inputFilePath = inputFilePath;
        this.outputFilePath = inputFilePath.replace(".csv", "_processed.csv");
    }

    public void processData() throws Exception {
        // 1. Load
        Instances data = loadCsv(this.inputFilePath);
        if (data.classIndex() == -1) {
            data.setClassIndex(data.numAttributes() - 1);
        }
        log.info("Original data shape: {} rows, {} attributes.", data.numInstances(), data.numAttributes());

        // 2. Remove identifier columns early so identifiers don't affect numeric transforms
        Instances withoutIds = removeIdentifierColumns(data);
        // Ensure class is last after structural change
        withoutIds.setClassIndex(withoutIds.numAttributes() - 1);
        log.info("Identifier columns removed early. Shape: {} rows, {} attributes.", withoutIds.numInstances(), withoutIds.numAttributes());

        // 3. Sanitize (replace NaN/Inf with column mean)
        SanitizeResult sanitizeResult = sanitizeData(withoutIds);
        Instances sanitizedData = sanitizeResult.instances();
        log.info("Data sanitized. Imputed values count: {}", sanitizeResult.totalImputed());

        // 4. Winsorize outliers (cap values) instead of deleting rows
        WinsorizeResult winsorized = winsorizeOutliers(sanitizedData);
        Instances dataWinsorized = winsorized.instances();
        log.info("Winsorization complete. Total values capped: {}", winsorized.totalCapped());

        // 5. Remove constant numeric features (after winsorization)
        Instances dataWithoutUseless = removeConstantAttributes(dataWinsorized);
        dataWithoutUseless.setClassIndex(dataWithoutUseless.numAttributes() - 1);
        log.info("Data shape after removing useless attributes: {} rows, {} attributes.", dataWithoutUseless.numInstances(), dataWithoutUseless.numAttributes());

        // 6. Scale the data (ignore the class attribute)
        Instances scaledData = scaleData(dataWithoutUseless);
        scaledData.setClassIndex(scaledData.numAttributes() - 1);
        log.info("Data successfully scaled.");

        // 7. Save the final, clean data
        saveToCsv(scaledData, this.outputFilePath);
        log.info("Processed data saved to: {}", this.outputFilePath);
    }

    private Instances removeIdentifierColumns(Instances data) throws Exception {
        // Remove common identifier columns by name if present (Project, MethodName, Release)
        List<Integer> toRemove = new ArrayList<>();
        for (String id : new String[]{"Project", "MethodName", "RELEASE_ATTR"}) {
            var attr = data.attribute(id);
            if (attr != null) toRemove.add(attr.index());
        }

        if (toRemove.isEmpty()) return data;

        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndicesArray(toRemove.stream().mapToInt(Integer::intValue).toArray());
        removeFilter.setInputFormat(data);
        Instances filtered = Filter.useFilter(data, removeFilter);

        // Ensure class attribute is the last attribute
        filtered.setClassIndex(filtered.numAttributes() - 1);
        return filtered;
    }


    private record SanitizeResult(Instances instances, int totalImputed) {}

    private SanitizeResult sanitizeData(Instances data) {
        // Calculate column means for replacement
        double[] means = new double[data.numAttributes()];
        for (int j = 0; j < data.numAttributes(); j++) {
            if (data.attribute(j).isNumeric()) {
                means[j] = data.meanOrMode(j);
            }
        }

        int totalImputed = 0;
        for (int i = 0; i < data.numInstances(); i++) {
            for (int j = 0; j < data.numAttributes(); j++) {
                if (data.attribute(j).isNumeric()) {
                    double value = data.instance(i).value(j);
                    if (Double.isNaN(value) || Double.isInfinite(value)) {
                        // Replace non-finite value with the mean of the column
                        data.instance(i).setValue(j, means[j]);
                        totalImputed++;
                    }
                }
            }
        }
        return new SanitizeResult(data, totalImputed);
    }

    private record WinsorizeResult(Instances instances, int totalCapped) {}

    private WinsorizeResult winsorizeOutliers(Instances data) {
        Instances result = new Instances(data);
        int totalCapped = 0;

        List<Integer> numericAttrIndices = getNumericAttrIndices(data);

        for (int attrIndex : numericAttrIndices) {
            Bounds b = computeBounds(data, attrIndex);
            if (b == null) continue; // constant column or invalid stats

            for (int i = 0; i < result.numInstances(); i++) {
                double v = result.instance(i).value(attrIndex);
                if (Double.isNaN(v) || Double.isInfinite(v)) continue; // already sanitized earlier
                if (v < b.lower) {
                    result.instance(i).setValue(attrIndex, b.lower);
                    totalCapped++;
                } else if (v > b.upper) {
                    result.instance(i).setValue(attrIndex, b.upper);
                    totalCapped++;
                }
            }
        }

        return new WinsorizeResult(result, totalCapped);
    }

    private Instances loadCsv(String filename) throws IOException {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(filename));
        return loader.getDataSet();
    }

    private static List<Integer> getNumericAttrIndices(Instances data) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            Attribute attr = data.attribute(i);
            if (attr.isNumeric() && !RELEASE_ATTR.equalsIgnoreCase(attr.name()) && i != data.classIndex()) {
                indices.add(i);
            }
        }
        return indices;
    }

    private static Bounds computeBounds(Instances data, int attrIndex) {
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (int r = 0; r < data.numInstances(); r++) {
            stats.addValue(data.instance(r).value(attrIndex));
        }
        double std = stats.getStandardDeviation();
        if (std == 0.0 || Double.isNaN(std)) {
            return null; // costant column: no outlier
        }
        double mean = stats.getMean();
        double delta = OUTLIER_STD_MULTIPLIER * std;
        return new Bounds(mean - delta, mean + delta);
    }


    private Instances removeConstantAttributes(Instances data) throws Exception {
        List<Integer> constantAttrIndices = new ArrayList<>();

        for (int i = 0; i < data.numAttributes(); i++) {
            if (isNumericNonClass(data, i) && hasAtMostOneUniqueValue(data, i)) {
                constantAttrIndices.add(i);

                final int idx = i;
                log.atInfo()
                        .setMessage("Marking constant attribute for removal: {}")
                        .addArgument(() -> data.attribute(idx).name())
                        .log();
            }
        }

        if (constantAttrIndices.isEmpty()) {
            return data;
        }

        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndicesArray(
                constantAttrIndices.stream().mapToInt(Integer::intValue).toArray()
        );
        removeFilter.setInputFormat(data);

        return Filter.useFilter(data, removeFilter);
    }


    private static boolean isNumericNonClass(Instances data, int index) {
        Attribute attr = data.attribute(index);
        return attr.isNumeric() && index != data.classIndex();
    }

    private static boolean hasAtMostOneUniqueValue(Instances data, int attrIndex) {
        Set<Double> unique = new HashSet<>();
        for (int r = 0; r < data.numInstances(); r++) {
            unique.add(data.instance(r).value(attrIndex));
            if (unique.size() > 1) {
                return false; // appena trovi 2 valori diversi, NON è costante
            }
        }
        return true; // 0 o 1 valore distinto -> costante
    }


    private Instances scaleData(Instances data) throws Exception {
        Normalize norm = new Normalize();
        norm.setIgnoreClass(true);
        norm.setInputFormat(data);
        return Filter.useFilter(data, norm);
    }

    /**
     * Updated to save back to CSV format.
     */
    private void saveToCsv(Instances data, String filename) throws IOException {
        CSVSaver saver = new CSVSaver();
        saver.setInstances(data);
        saver.setFile(new File(filename));
        saver.writeBatch();
    }
}