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
        } catch (Exception ignored) {}
        split(inputCsv, outPrefix, af.orElse(null));
    }

    /**
     * Split with explicit actionableFeature (may be null to autodetect). This method
     * creates A_train/A_test stratified by the class label, B+, B, and C. B will be
     * created from B+ by setting the smell-related columns to 0.
     */
    public static void split(String inputCsv, String outPrefix, String actionableFeature) throws IOException {
        try (Reader in = new FileReader(inputCsv);
             CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(in)) {

            List<String> headers = parser.getHeaderNames();
            List<Map<String, String>> rows = new ArrayList<>();
            for (var r : parser) rows.add(r.toMap());

            // Determine the single actionable feature to use for B+/B/C. If not provided, try autodetect.
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

            // Stratified split: group by class label, shuffle within groups, then take 80% from each
            Map<String, List<Map<String, String>>> byClass = new LinkedHashMap<>();
            for (var r : rows) {
                String cls = r.getOrDefault("IsBuggy", "no");
                byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(r);
            }

            List<Map<String, String>> train = new ArrayList<>();
            List<Map<String, String>> test = new ArrayList<>();
            Random rng = new Random(42);
            for (var kv : byClass.entrySet()) {
                List<Map<String, String>> grp = kv.getValue();
                Collections.shuffle(grp, rng);
                int n = grp.size();
                int trainSize = (int) Math.round(n * 0.8);
                if (trainSize < 1 && n > 0) trainSize = 1; // ensure at least one sample
                train.addAll(grp.subList(0, Math.min(trainSize, n)));
                if (trainSize < n) test.addAll(grp.subList(trainSize, n));
            }

            writeCsv(outPrefix + "_A_train.csv", headers, train);
            writeCsv(outPrefix + "_A_test.csv", headers, test);

            List<Map<String, String>> bPlus = new ArrayList<>();
            List<Map<String, String>> c = new ArrayList<>();
            for (var row : rows) {
                String v = row.getOrDefault(topFeature, "0");
                double d = 0;
                try { d = Double.parseDouble(v); } catch (Exception ignored) {}
                if (d > 0.0) bPlus.add(row); else c.add(row);
            }

            writeCsv(outPrefix + "_Bplus.csv", headers, bPlus);
            writeCsv(outPrefix + "_C.csv", headers, c);

            // Create B by copying B+ and zeroing only the top actionable feature
            List<Map<String, String>> b = new ArrayList<>();
            int modified = 0;
            for (var row : bPlus) {
                Map<String, String> copy = new LinkedHashMap<>(row);
                if (copy.containsKey(topFeature)) {
                    String old = copy.get(topFeature);
                    if (old != null && !old.trim().isEmpty() && !old.trim().equals("0") && !old.trim().equals("0.0")) {
                        copy.put(topFeature, "0");
                        modified++;
                    }
                }
                b.add(copy);
            }
            writeCsv(outPrefix + "_B.csv", headers, b);
            System.out.println("DatasetSplitter: zeroed feature '" + topFeature + "' in B for " + modified + " rows.");
        }
    }

    /**
     * Split input CSV in memory and return Instances for train/test and B+/B/C as file paths are optional.
     */
    public static InMemorySplit splitInMemory(String inputCsv, String actionableFeature) throws Exception {
        // Load as Instances
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(inputCsv));
        Instances all = loader.getDataSet();
        if (all.classIndex() == -1) all.setClassIndex(all.numAttributes() - 1);

        // Stratified split in memory
        all.randomize(new Random(42));
        if (all.classAttribute().isNominal()) all.stratify(5); // small stratify to keep groups

        int trainSize = (int) Math.round(all.numInstances() * 0.8);
        if (trainSize < 1 && all.numInstances() > 0) trainSize = 1;
        Instances train = new Instances(all, 0, trainSize);
        Instances test = new Instances(all, trainSize, all.numInstances() - trainSize);

        // Determine actionable feature if not provided
        String topFeature = actionableFeature;
        if (topFeature == null) {
            try {
                var opt = FeatureAnalyzer.selectTopActionableFeature(inputCsv);
                topFeature = opt.orElse(null);
            } catch (Exception ignored) {
                topFeature = null;
            }
        }

        // Create B+ and C by filtering on actionableFeature
        Instances bPlus = new Instances(all, 0);
        Instances c = new Instances(all, 0);
        int featureIndex = -1;
        if (topFeature != null && all.attribute(topFeature) != null) featureIndex = all.attribute(topFeature).index();
        for (int i = 0; i < all.numInstances(); i++) {
            double val = featureIndex >= 0 ? all.instance(i).value(featureIndex) : 0.0;
            if (val > 0.0) bPlus.add(all.instance(i)); else c.add(all.instance(i));
        }

        // Create B by copying B+ and setting top feature to 0
        Instances b = new Instances(bPlus);
        if (featureIndex >= 0) {
            for (int i = 0; i < b.numInstances(); i++) b.instance(i).setValue(featureIndex, 0.0);
        }

        return new InMemorySplit(all, train, test, bPlus, b, c);
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
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withHeader(headers.toArray(new String[0])))) {
            for (var r : rows) {
                List<String> rec = new ArrayList<>();
                for (String h : headers) rec.add(r.getOrDefault(h, ""));
                printer.printRecord(rec);
            }
        }
    }
}
