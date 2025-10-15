package com.dipalma.whatif.tools;

import com.dipalma.whatif.preprocessing.DataPreprocessor;

/**
 * Lightweight runner to preprocess one or more CSV files using the project's
 * DataPreprocessor. This avoids running the full DatasetGenerator/Main which
 * may trigger network operations.
 */
public class PreprocessRunner {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: PreprocessRunner <csv-file> [more csv files...]");
            System.exit(1);
        }

        for (String filename : args) {
            System.out.println("Preprocessing: " + filename);
            try {
                DataPreprocessor p = new DataPreprocessor(filename);
                p.processData();
                System.out.println("Created: " + filename.replace(".csv", "_processed.csv"));
            } catch (Exception e) {
                System.err.println("Failed preprocessing " + filename + ":");
                e.printStackTrace();
                System.exit(2);
            }
        }
    }
}
