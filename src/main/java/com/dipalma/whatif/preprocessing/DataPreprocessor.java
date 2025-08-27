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

public class DataPreprocessor {

    private final String inputFilePath;
    private final String outputFilePath;

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
        System.out.println("Original data shape: " + data.numInstances() + " rows, " + data.numAttributes() + " attributes.");

        // 2. Sanitize
        Instances sanitizedData = sanitizeData(data);
        System.out.println("Data sanitized.");

        // 3. Remove outliers
        Instances dataWithoutOutliers = removeOutliers(sanitizedData);
        System.out.println("Data shape after outlier removal: " + dataWithoutOutliers.numInstances() + " rows.");

        // 4. Remove constant numeric features
        Instances dataWithoutUseless = removeConstantAttributes(dataWithoutOutliers);
        System.out.println("Data shape after removing useless attributes: " + dataWithoutUseless.numInstances() + " rows.");

        // 5. Scale the data
        Instances scaledData = scaleData(dataWithoutUseless);
        System.out.println("Data successfully scaled.");

        // 6. *** NEW AND FINAL STEP: Remove identifier columns ***
        Instances finalData = removeIdentifierColumns(scaledData);
        System.out.println("Identifier columns removed. Final data has " + finalData.numAttributes() + " attributes.");

        // 7. Save the final, clean data
        saveToCsv(finalData, this.outputFilePath);
        System.out.println("Processed data saved to: " + this.outputFilePath);
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

    private Instances removeOutliers(Instances data) {
        Instances filteredData = new Instances(data);
        filteredData.delete(); // Start with an empty structure

        List<Integer> numericAttrIndices = new ArrayList<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (data.attribute(i).isNumeric() && !data.attribute(i).name().equalsIgnoreCase("Release")) {
                numericAttrIndices.add(i);
            }
        }

        boolean[] toRemove = new boolean[data.numInstances()];
        for (int attrIndex : numericAttrIndices) {
            DescriptiveStatistics stats = new DescriptiveStatistics();
            data.forEach(instance -> stats.addValue(instance.value(attrIndex)));
            double mean = stats.getMean();
            double stdDev = stats.getStandardDeviation();
            if (stdDev == 0) continue;
            double lowerBound = mean - (3 * stdDev);
            double upperBound = mean + (3 * stdDev);

            for(int i = 0; i < data.numInstances(); i++){
                if(!toRemove[i]){ // Don't re-check rows already marked for removal
                    double value = data.instance(i).value(attrIndex);
                    if(value < lowerBound || value > upperBound){
                        toRemove[i] = true;
                    }
                }
            }
        }

        for(int i = 0; i < data.numInstances(); i++){
            if(!toRemove[i]){
                filteredData.add(data.instance(i));
            }
        }
        return filteredData;
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
                    System.out.println("Marking constant attribute for removal: " + attribute.name());
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