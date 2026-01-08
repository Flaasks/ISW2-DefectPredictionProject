package com.dipalma.whatif.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import java.io.File;

public class DatasetSplitter {

    private DatasetSplitter() {
    }

    /**
     * Split A into train/test (80/20) and create B+, B, C based on the actionable feature:
     * - B+ contains rows from A where actionableFeature > 0
     * - C contains rows from A where actionableFeature == 0
     * - B is a copy of B+ with actionableFeature set to 0
     */
    public static void split(String inputCsv, String outPrefix) throws IOException {
        // autodetect actionable feature if possible
        Optional<String> af = Optional.empty();
        try {
            af = FeatureAnalyzer.selectTopActionableFeature(inputCsv);
        } catch (Exception ignored) {
            // Autodetection failure is non-fatal; fallback to explicit feature resolution
        }
        split(inputCsv, outPrefix, af.orElse(null));
    }

    /**
     * Split with explicit actionableFeature (may be null to autodetect). This method
     * creates A_train/A_test stratified by the class label, B+, B, and C. B will be
     * created from B+ by setting the smell-related columns to 0.
     */
    public static void split(String inputCsv, String outPrefix, String actionableFeature) throws IOException {
        try (Reader in = new FileReader(inputCsv);
             CSVParser parser = com.dipalma.whatif.util.CsvUtils.parseWithHeader(in)) {

            List<String> headers = parser.getHeaderNames();
            List<Map<String, String>> rows = new ArrayList<>();
            for (var r : parser) rows.add(r.toMap());

            String topFeature = determineActionableFeature(inputCsv, actionableFeature);
            
            SplitData trainTestSplit = createStratifiedSplit(rows);
            writeCsv(outPrefix + "_A_train.csv", headers, trainTestSplit.train);
            writeCsv(outPrefix + "_A_test.csv", headers, trainTestSplit.test);

            PartitionedData partitions = partitionByFeature(rows, topFeature);
            writeCsv(outPrefix + "_Bplus.csv", headers, partitions.bPlus);
            writeCsv(outPrefix + "_C.csv", headers, partitions.c);

            List<Map<String, String>> b = createBWithZeroedFeature(partitions.bPlus, topFeature);
            writeCsv(outPrefix + "_B.csv", headers, b);
        }
    }

    private static String determineActionableFeature(String inputCsv, String actionableFeature) throws IOException {
        String topFeature = actionableFeature;
        if (topFeature == null) {
            try {
                var opt = FeatureAnalyzer.selectTopActionableFeature(inputCsv);
                topFeature = opt.orElse(null);
            } catch (Exception e) {
                topFeature = null;
            }
        }
        if (topFeature == null || topFeature.isBlank()) {
            throw new IOException("Could not determine top actionable feature to create B+/B/C. Please provide it or ensure FeatureAnalyzer can detect one.");
        }
        return topFeature;
    }

    private static class SplitData {
        final List<Map<String, String>> train;
        final List<Map<String, String>> test;
        SplitData(List<Map<String, String>> train, List<Map<String, String>> test) {
            this.train = train;
            this.test = test;
        }
    }

    private static SplitData createStratifiedSplit(List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> byClass = new LinkedHashMap<>();
        for (var r : rows) {
            String cls = r.getOrDefault("IsBuggy", "no");
            byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(r);
        }

        List<Map<String, String>> train = new ArrayList<>();
        List<Map<String, String>> test = new ArrayList<>();
        Random rng = new Random(42);
        
        for (var grp : byClass.values()) {
            Collections.shuffle(grp, rng);
            int n = grp.size();
            int trainSize = calculateTrainSize(n);
            train.addAll(grp.subList(0, Math.min(trainSize, n)));
            if (trainSize < n) test.addAll(grp.subList(trainSize, n));
        }
        
        return new SplitData(train, test);
    }

    private static int calculateTrainSize(int totalSize) {
        int size = (int) Math.round(totalSize * 0.8);
        return (size < 1 && totalSize > 0) ? 1 : size;
    }

    private static class PartitionedData {
        final List<Map<String, String>> bPlus;
        final List<Map<String, String>> c;
        PartitionedData(List<Map<String, String>> bPlus, List<Map<String, String>> c) {
            this.bPlus = bPlus;
            this.c = c;
        }
    }

    private static PartitionedData partitionByFeature(List<Map<String, String>> rows, String feature) {
        List<Map<String, String>> bPlus = new ArrayList<>();
        List<Map<String, String>> c = new ArrayList<>();
        
        for (var row : rows) {
            double value = parseFeatureValue(row, feature);
            if (value > 0.0) {
                bPlus.add(row);
            } else {
                c.add(row);
            }
        }
        
        return new PartitionedData(bPlus, c);
    }

    private static double parseFeatureValue(Map<String, String> row, String feature) {
        String v = row.getOrDefault(feature, "0");
        if (v == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException nfe) {
            return 0.0;
        }
    }

    private static List<Map<String, String>> createBWithZeroedFeature(List<Map<String, String>> bPlus, String feature) {
        List<Map<String, String>> b = new ArrayList<>();
        for (var row : bPlus) {
            Map<String, String> copy = new LinkedHashMap<>(row);
            if (shouldZeroFeature(copy, feature)) {
                copy.put(feature, "0");
            }
            b.add(copy);
        }
        return b;
    }

    private static boolean shouldZeroFeature(Map<String, String> row, String feature) {
        if (!row.containsKey(feature)) return false;
        String old = row.get(feature);
        return old != null && !old.trim().isEmpty() && !old.trim().equals("0") && !old.trim().equals("0.0");
    }

    /**
     * Split input CSV in memory and return Instances for train/test and B+/B/C as file paths are optional.
     */
    public static InMemorySplit splitInMemory(String inputCsv, String actionableFeature) throws IOException {
        Instances all = loadAndPrepareInstances(inputCsv);
        
        InMemoryTrainTest trainTest = createInMemoryTrainTest(all);
        String topFeature = resolveActionableFeature(inputCsv, actionableFeature);
        InMemoryPartitions partitions = partitionInstances(all, topFeature);
        
        return new InMemorySplit(all, trainTest.train, trainTest.test, partitions.bPlus, partitions.b, partitions.c);
    }

    private static Instances loadAndPrepareInstances(String inputCsv) throws IOException {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(inputCsv));
        Instances all = loader.getDataSet();
        if (all.classIndex() == -1) {
            all.setClassIndex(all.numAttributes() - 1);
        }
        return all;
    }

    private static class InMemoryTrainTest {
        final Instances train;
        final Instances test;
        InMemoryTrainTest(Instances train, Instances test) {
            this.train = train;
            this.test = test;
        }
    }

    private static InMemoryTrainTest createInMemoryTrainTest(Instances all) {
        all.randomize(new Random(42));
        if (all.classAttribute().isNominal()) {
            all.stratify(5);
        }

        int trainSize = calculateTrainSize(all.numInstances());
        Instances train = new Instances(all, 0, trainSize);
        Instances test = new Instances(all, trainSize, all.numInstances() - trainSize);
        
        return new InMemoryTrainTest(train, test);
    }

    private static String resolveActionableFeature(String inputCsv, String actionableFeature) {
        if (actionableFeature != null) {
            return actionableFeature;
        }
        
        try {
            var opt = FeatureAnalyzer.selectTopActionableFeature(inputCsv);
            return opt.orElse(null);
        } catch (IOException ioe) {
            return null;
        }
    }

    private static class InMemoryPartitions {
        final Instances bPlus;
        final Instances b;
        final Instances c;
        InMemoryPartitions(Instances bPlus, Instances b, Instances c) {
            this.bPlus = bPlus;
            this.b = b;
            this.c = c;
        }
    }

    private static InMemoryPartitions partitionInstances(Instances all, String topFeature) {
        int featureIndex = getFeatureIndex(all, topFeature);
        
        Instances bPlus = new Instances(all, 0);
        Instances c = new Instances(all, 0);
        
        partitionByFeatureValue(all, bPlus, c, featureIndex);
        
        Instances b = createBFromBPlus(bPlus, featureIndex);
        
        return new InMemoryPartitions(bPlus, b, c);
    }

    private static int getFeatureIndex(Instances all, String topFeature) {
        if (topFeature != null && all.attribute(topFeature) != null) {
            return all.attribute(topFeature).index();
        }
        return -1;
    }

    private static void partitionByFeatureValue(Instances all, Instances bPlus, Instances c, int featureIndex) {
        for (int i = 0; i < all.numInstances(); i++) {
            double val = featureIndex >= 0 ? all.instance(i).value(featureIndex) : 0.0;
            if (val > 0.0) {
                bPlus.add(all.instance(i));
            } else {
                c.add(all.instance(i));
            }
        }
    }

    private static Instances createBFromBPlus(Instances bPlus, int featureIndex) {
        Instances b = new Instances(bPlus);
        if (featureIndex >= 0) {
            for (int i = 0; i < b.numInstances(); i++) {
                b.instance(i).setValue(featureIndex, 0.0);
            }
        }
        return b;
    }

    public static class InMemorySplit {
        public final Instances all;
        public final Instances train;
        public final Instances test;
        public final Instances bPlus;
        public final Instances b;
        public final Instances c;

        public InMemorySplit(Instances all, Instances train, Instances test, Instances bPlus, Instances b, Instances c) {
            this.all = all; this.train = train; this.test = test; this.bPlus = bPlus; this.b = b; this.c = c;
        }
    }

    private static void writeCsv(String path, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (FileWriter out = new FileWriter(path);
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord(headers);
            for (var r : rows) {
                List<String> rec = new ArrayList<>();
                for (String h : headers) rec.add(r.getOrDefault(h, ""));
                printer.printRecord(rec);
            }
        }
    }
}
