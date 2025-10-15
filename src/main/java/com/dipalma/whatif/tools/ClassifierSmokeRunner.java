package com.dipalma.whatif.tools;

import com.dipalma.whatif.classification.ClassifierRunner;
import weka.classifiers.Classifier;

public class ClassifierSmokeRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ClassifierSmokeRunner <dataset-prefix>");
            System.exit(1);
        }
        String prefix = args[0];
        String train = prefix + "_A_train.csv";
        String test = prefix + "_A_test.csv";

        // Load training instances directly
        weka.core.converters.CSVLoader loader = new weka.core.converters.CSVLoader();
        loader.setSource(new java.io.File(train));
        weka.core.Instances trainData = loader.getDataSet();
        trainData.setClassIndex(trainData.numAttributes() - 1);

        com.dipalma.whatif.classification.ClassifierRunner runner = new com.dipalma.whatif.classification.ClassifierRunner(train);
        weka.classifiers.Classifier cls = runner.trainBestClassifier(trainData);
        if (cls != null) {
            runner.saveModel(cls, prefix + "_model.bin");
            runner.predictToCsv(cls, test, prefix + "_A_test_pred.csv");
            System.out.println("Smoke test complete. Predictions written to " + prefix + "_A_test_pred.csv");
        } else {
            System.err.println("trainBestClassifier did not return a classifier");
        }
    }
}
