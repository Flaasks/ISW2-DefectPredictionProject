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
            System.exit(1);
        }

        for (String filename : args) {
            try {
                DataPreprocessor p = new DataPreprocessor(filename);
                p.processData();
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(2);
            }
        }
    }
}
