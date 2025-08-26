package com.dipalma.whatif.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.InfoGainAttributeEval;
import weka.attributeSelection.Ranker;
import weka.core.Instances;
import weka.core.converters.CSVLoader;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataAnalyzer {

    private final String originalCsvPath;
    private final String processedCsvPath;
    private Instances data;
    private static final Logger log = LoggerFactory.getLogger(DataAnalyzer.class);
    private static final String RANK_ROW_FMT = "%-4d | %-7.4f | %s";

    public DataAnalyzer(String originalCsvPath, String processedCsvPath) {
        this.originalCsvPath = originalCsvPath;
        this.processedCsvPath = processedCsvPath;
    }

    private void loadProcessedData() throws IOException {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(processedCsvPath));
        this.data = loader.getDataSet();
        if (data.classIndex() == -1) {
            data.setClassIndex(data.numAttributes() - 1);
        }
    }

    public void findActionableFeatureAndMethod() throws Exception {
        if (this.data == null) {
            loadProcessedData();
        }

        log.info("--- Step 4: Calculating Feature Correlation with Bugginess ---");
        AttributeSelection selector = new AttributeSelection();
        InfoGainAttributeEval evaluator = new InfoGainAttributeEval();
        Ranker search = new Ranker();
        selector.setEvaluator(evaluator);
        selector.setSearch(search);
        selector.SelectAttributes(data);

        // rankedAttributes() returns features from best to worst.
        double[][] rankedAttributes = selector.rankedAttributes();
        log.info("Rank | Score   | Feature");

        // *** THE FIX IS HERE ***
        // We use a simple counter variable for the rank instead of calling a non-existent method.
        int rank = 1;
        for (double[] rankedAttribute : rankedAttributes) {
            // Weka returns an array where [0] is the attribute index and [1] is its score.
            int index = (int) rankedAttribute[0];
            double score = rankedAttribute[1];
            if (log.isDebugEnabled()) {
                String attrName = data.attribute(index).name();
                log.debug("{}", String.format(RANK_ROW_FMT, rank, score, attrName));
            }
            rank++;
        }


        log.info("--- Step 5: Identifying Top Actionable Feature (AFeature) ---");
        List<String> actionableFeatures = Arrays.asList("LOC", "CyclomaticComplexity", "ParameterCount", "Duplication");
        String aFeature = "";
        double highestScore = -1.0;

        // Iterate through the ranked list to find the first one that is "actionable"
        for (double[] rankedAttribute : rankedAttributes) {
            String featureName = data.attribute((int) rankedAttribute[0]).name();
            if (actionableFeatures.contains(featureName)) {
                aFeature = featureName;
                highestScore = rankedAttribute[1];
                break; // Found the highest ranked actionable feature
            }
        }
        log.info("Identified AFeature: {} (Score: {})", aFeature, String.format("%.4f", highestScore));

        log.info("--- Step 6: Identifying Target Method (AFMethod) ---");
        findHighImpactMethod(aFeature);
    }

    private void findHighImpactMethod(String aFeature) throws IOException {
        Reader in = new FileReader(originalCsvPath);
        CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(in);
        List<CSVRecord> records = parser.getRecords();

        if (records.isEmpty()) {
            log.info("Dataset is empty, cannot find AFMethod.");
            return;
        }

        String lastRelease = records.getLast().get("Release");

        Optional<CSVRecord> afMethodOpt = records.stream()
                .filter(r -> r.get("Release").equals(lastRelease))
                .filter(r -> r.get("IsBuggy").equalsIgnoreCase("yes"))
                .max(Comparator.comparingDouble(r -> Double.parseDouble(r.get(aFeature))));

        if (afMethodOpt.isPresent()) {
            CSVRecord afMethod = afMethodOpt.get();
            log.info("Identified AFMethod (buggy method in last release with highest {}):", aFeature);
            log.info("  MethodName: {}", afMethod.get("MethodName"));
            log.info("  {} Value: {}", aFeature, afMethod.get(aFeature));
        } else {
            log.warn("Could not find any buggy methods in the last release ({}) to select AFMethod.", lastRelease);
        }
    }
}