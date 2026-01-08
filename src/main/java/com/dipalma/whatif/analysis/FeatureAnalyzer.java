package com.dipalma.whatif.analysis;

import org.apache.commons.csv.CSVParser;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;


public class FeatureAnalyzer {

    private FeatureAnalyzer() {
    }

    public static Map<String, Double> computeCorrelations(String csvFilePath) throws IOException {
        try (Reader in = new FileReader(csvFilePath);
             CSVParser parser = com.dipalma.whatif.util.CsvUtils.parseWithHeader(in)) {

            List<String> headers = parser.getHeaderNames();
            List<Map<String, String>> rows = new ArrayList<>();
            for (var rec : parser) rows.add(rec.toMap());

            String classCol = findClassColumn(headers);
            Map<String, double[]> numericCols = extractNumericColumns(headers, rows);
            double[] classArray = convertClassToBinary(rows, classCol);

            return computeCorrelationsForFeatures(numericCols, classArray);
        }
    }

    private static String findClassColumn(List<String> headers) {
        return headers.stream()
                .filter(h -> h.equalsIgnoreCase("IsBuggy") || h.equalsIgnoreCase("IsBuggy?"))
                .findFirst()
                .orElse("IsBuggy");
    }

    private static Map<String, double[]> extractNumericColumns(List<String> headers, List<Map<String, String>> rows) {
        Map<String, double[]> numericCols = new LinkedHashMap<>();
        int n = rows.size();
        
        for (String h : headers) {
            if (shouldSkipColumn(h)) continue;
            
            double[] arr = tryParseColumnAsNumeric(rows, h, n);
            if (arr.length > 0) {
                numericCols.put(h, arr);
            }
        }
        
        return numericCols;
    }

    private static boolean shouldSkipColumn(String columnName) {
        return columnName.equalsIgnoreCase("Project") 
            || columnName.equalsIgnoreCase("MethodName") 
            || columnName.equalsIgnoreCase("Release");
    }

    private static double[] tryParseColumnAsNumeric(List<Map<String, String>> rows, String columnName, int size) {
        double[] arr = new double[size];
        
        for (int i = 0; i < size; i++) {
            String val = rows.get(i).get(columnName);
            try {
                arr[i] = Double.parseDouble(val);
            } catch (Exception e) {
                return new double[0]; // Not all numeric, skip this column
            }
        }
        
        return arr;
    }

    private static double[] convertClassToBinary(List<Map<String, String>> rows, String classCol) {
        int n = rows.size();
        double[] cls = new double[n];
        
        for (int i = 0; i < n; i++) {
            String v = rows.get(i).get(classCol);
            cls[i] = isBuggyValue(v) ? 1.0 : 0.0;
        }
        
        return cls;
    }

    private static boolean isBuggyValue(String value) {
        return value != null 
            && (value.equalsIgnoreCase("yes") 
                || value.equalsIgnoreCase("true") 
                || value.equals("1"));
    }

    private static Map<String, Double> computeCorrelationsForFeatures(Map<String, double[]> numericCols, double[] classArray) {
        Map<String, Double> corrs = new LinkedHashMap<>();
        
        for (var entry : numericCols.entrySet()) {
            double r = pearsonCorr(entry.getValue(), classArray);
            corrs.put(entry.getKey(), r);
        }
        
        return corrs;
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
     * Choose the top actionable feature with highest absolute correlation with bugginess.
     */
    public static Optional<String> selectTopActionableFeature(String csvFilePath) throws IOException {
        Map<String, Double> corrs = computeCorrelations(csvFilePath);
    // define actionable features (complexity + history). Exclude LOC from automatic actionable list
    List<String> actionable = List.of("CyclomaticComplexity", "ParameterCount", "NumberOfBranches", "elseAdded", "NR", "NAuth", "stmtAdded", "stmtDeleted", "avgChurn");
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
