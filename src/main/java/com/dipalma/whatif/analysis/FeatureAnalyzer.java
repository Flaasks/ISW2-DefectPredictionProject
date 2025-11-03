package com.dipalma.whatif.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * Lightweight feature analyzer to compute point-biserial (Pearson with binary class)
 * correlations between numeric features and the binary IsBuggy label.
 */
public class FeatureAnalyzer {

    public static Map<String, Double> computeCorrelations(String csvFilePath) throws IOException {
        try (Reader in = new FileReader(csvFilePath);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(in)) {

            List<String> headers = parser.getHeaderNames();
            List<Map<String, String>> rows = new ArrayList<>();
            for (var rec : parser) rows.add(rec.toMap());

            int n = rows.size();
            // find class column (IsBuggy)
            String classCol = headers.stream().filter(h -> h.equalsIgnoreCase("IsBuggy") || h.equalsIgnoreCase("IsBuggy?")).findFirst().orElse("IsBuggy");

            // convert to numeric arrays
            Map<String, double[]> numericCols = new LinkedHashMap<>();
            for (String h : headers) {
                if (h.equalsIgnoreCase("Project") || h.equalsIgnoreCase("MethodName") || h.equalsIgnoreCase("Release")) continue;
                double[] arr = new double[n];
                boolean allNumeric = true;
                for (int i = 0; i < n; i++) {
                    String val = rows.get(i).get(h);
                    try { arr[i] = Double.parseDouble(val); } catch (Exception e) { allNumeric = false; break; }
                }
                if (allNumeric) numericCols.put(h, arr);
            }

            // class array (binary yes/no -> 1/0)
            double[] cls = new double[n];
            for (int i = 0; i < n; i++) {
                String v = rows.get(i).get(classCol);
                cls[i] = (v != null && (v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true") || v.equals("1"))) ? 1.0 : 0.0;
            }

            Map<String, Double> corrs = new LinkedHashMap<>();
            for (var entry : numericCols.entrySet()) {
                double r = pearsonCorr(entry.getValue(), cls);
                corrs.put(entry.getKey(), r);
            }
            return corrs;
        }
    }

    private static double pearsonCorr(double[] x, double[] y) {
        if (x.length != y.length || x.length == 0) return 0.0;
        int n = x.length;
        double meanX = Arrays.stream(x).average().orElse(0);
        double meanY = Arrays.stream(y).average().orElse(0);
        double num = 0, sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            num += dx * dy;
            sx += dx * dx;
            sy += dy * dy;
        }
        double den = Math.sqrt(sx * sy);
        return den == 0 ? 0.0 : num / den;
    }

    /**
     * Choose the top actionable feature (from a predefined set) with highest absolute correlation with bugginess.
     */
    public static Optional<String> selectTopActionableFeature(String csvFilePath) throws IOException {
        Map<String, Double> corrs = computeCorrelations(csvFilePath);
    // define actionable features (complexity + smells). Exclude LOC from automatic actionable list
    List<String> actionable = List.of("CyclomaticComplexity", "ParameterCount", "Duplication", "NR", "NAuth", "stmtAdded", "stmtDeleted", "maxChurn", "avgChurn");
        String best = null;
        double bestVal = 0.0;
        for (String f : actionable) {
            if (corrs.containsKey(f)) {
                double v = Math.abs(corrs.get(f));
                if (v > bestVal) { bestVal = v; best = f; }
            }
        }
        return Optional.ofNullable(best);
    }
}
