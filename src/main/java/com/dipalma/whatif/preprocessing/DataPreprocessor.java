package com.dipalma.whatif.preprocessing;


import weka.core.Attribute;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.core.converters.CSVSaver;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.Standardize;
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

    private record Bounds(int attrIndex, double lower, double upper) {
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

        // 2. Sanitize
        Instances sanitizedData = sanitizeData(data);
        log.info("Data sanitized.");

        // 3. Remove outliers
        Instances dataWithoutOutliers = removeOutliers(sanitizedData);
        log.info("Data shape after outlier removal: {} rows.", dataWithoutOutliers.numInstances());

        // 4. Remove constant numeric features
        Instances dataWithoutUseless = removeConstantAttributes(dataWithoutOutliers);
        log.info("Data shape after removing useless attributes: {} rows.", dataWithoutUseless.numInstances());

        // 5. Scale the data
        Instances scaledData = scaleData(dataWithoutUseless);
        log.info("Data successfully scaled.");

        // 6. *** NEW AND FINAL STEP: Remove identifier columns ***
        Instances finalData = removeIdentifierColumns(scaledData);
        log.info("Identifier columns removed. Final data has {} attributes.", finalData.numAttributes());

        // 7. Save the final, clean data
        saveToCsv(finalData, this.outputFilePath);
        log.info("Processed data saved to: {}", this.outputFilePath);
    }

    private Instances removeIdentifierColumns(Instances data) throws Exception {
        Remove removeFilter = new Remove();
        // Set the indices to remove. Weka's range list is 1-based.
        removeFilter.setAttributeIndices("1-3");
        removeFilter.setInputFormat(data);
        return Filter.useFilter(data, removeFilter);
    }

    /**
     * New method to find and replace any NaN or Infinite values.
     */
    private Instances sanitizeData(Instances data) {
        // Calculate column means for replacement
        double[] means = new double[data.numAttributes()];
        for (int j = 0; j < data.numAttributes(); j++) {
            if (data.attribute(j).isNumeric()) {
                means[j] = data.meanOrMode(j);
            }
        }

        for (int i = 0; i < data.numInstances(); i++) {
            for (int j = 0; j < data.numAttributes(); j++) {
                if (data.attribute(j).isNumeric()) {
                    double value = data.instance(i).value(j);
                    if (Double.isNaN(value) || Double.isInfinite(value)) {
                        // Replace non-finite value with the mean of the column
                        data.instance(i).setValue(j, means[j]);
                    }
                }
            }
        }
        return data;
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
            if (attr.isNumeric() && !RELEASE_ATTR.equalsIgnoreCase(attr.name())) {
                indices.add(i);
            }
        }
        return indices;
    }

    private static List<Bounds> computeBoundsList(Instances data, List<Integer> attrIndices) {
        List<Bounds> list = new ArrayList<>();
        for (int attrIndex : attrIndices) {
            DescriptiveStatistics stats = new DescriptiveStatistics();
            for (int r = 0; r < data.numInstances(); r++) {
                stats.addValue(data.instance(r).value(attrIndex));
            }
            double std = stats.getStandardDeviation();
            if (std == 0.0 || Double.isNaN(std)) {
                continue; // colonna costante -> nessun outlier secondo 3σ
            }
            double mean = stats.getMean();
            double delta = OUTLIER_STD_MULTIPLIER * std;
            list.add(new Bounds(attrIndex, mean - delta, mean + delta));
        }
        return list;
    }

    private static boolean isOutlierInstance(Instances data, int rowIndex, List<Bounds> boundsList) {
        for (Bounds b : boundsList) {
            double v = data.instance(rowIndex).value(b.attrIndex);
            if (v < b.lower || v > b.upper) {
                return true;
            }
        }
        return false;
    }


    private Instances removeOutliers(Instances data) {
        // copia la struttura, poi svuota (come prima)
        Instances filtered = new Instances(data);
        filtered.delete();

        if (data.numInstances() == 0 || data.numAttributes() == 0) {
            return filtered;
        }

        List<Integer> numericAttrIndices = getNumericAttrIndices(data);
        if (numericAttrIndices.isEmpty()) {
            // nessun attributo numerico (eccetto "Release") -> ritorna i dati originali
            return new Instances(data);
        }

        List<Bounds> boundsList = computeBoundsList(data, numericAttrIndices);
        if (boundsList.isEmpty()) {
            // tutte colonne costanti (std=0) -> nessun outlier secondo 3σ
            return new Instances(data);
        }

        boolean[] toRemove = new boolean[data.numInstances()];
        for (int i = 0; i < data.numInstances(); i++) {
            if (isOutlierInstance(data, i, boundsList)) {
                toRemove[i] = true;
            }
        }

        for (int i = 0; i < data.numInstances(); i++) {
            if (!toRemove[i]) {
                filtered.add(data.instance(i));
            }
        }
        return filtered;
    }


    private Instances removeConstantAttributes(Instances data) throws Exception {
        List<Integer> constantAttrIndices = new ArrayList<>();

        // Loop through all attributes to find which ones are constant
        for (int i = 0; i < data.numAttributes(); i++) {
            Attribute attribute = data.attribute(i);

            // We only consider numeric attributes that are NOT the class attribute
            if (attribute.isNumeric() && i != data.classIndex()) {
                // Use a Set to find the number of unique values in the column
                Set<Double> uniqueValues = new HashSet<>();
                for (int j = 0; j < data.numInstances(); j++) {
                    uniqueValues.add(data.instance(j).value(i));
                    // If we find more than one unique value, we can stop checking this column
                    if (uniqueValues.size() > 1) {
                        break;
                    }
                }

                // If, after checking all rows, there's only 1 unique value, the column is constant
                if (uniqueValues.size() <= 1) {
                    constantAttrIndices.add(i);
                    log.debug("Marking constant attribute for removal: {}", attribute.name());
                }
            }
        }

        if (constantAttrIndices.isEmpty()) {
            return data; // No attributes to remove
        }

        // Use the simple 'Remove' filter to delete the identified columns
        Remove removeFilter = new Remove();
        int[] indicesToRemove = constantAttrIndices.stream().mapToInt(i -> i).toArray();
        removeFilter.setAttributeIndicesArray(indicesToRemove);
        removeFilter.setInputFormat(data);

        return Filter.useFilter(data, removeFilter);
    }

    private Instances scaleData(Instances data) throws Exception {
        Standardize filter = new Standardize();
        filter.setInputFormat(data);
        return Filter.useFilter(data, filter);
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