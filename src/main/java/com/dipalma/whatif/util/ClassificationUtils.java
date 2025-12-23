package com.dipalma.whatif.util;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.meta.FilteredClassifier;
import weka.core.Instances;
import weka.filters.supervised.instance.Resample;

import java.util.Random;

public final class ClassificationUtils {
    private ClassificationUtils() {}

    /**
     * Wrap a base classifier with a Resample filter configured to bias to uniform class
     * Behavior mirrors the repeated setup present in ClassifierRunner
     */
    public static FilteredClassifier wrapWithResample(Classifier base, int seed) throws Exception {
        Resample resample = new Resample();
        resample.setBiasToUniformClass(1.0);
        resample.setNoReplacement(false);
        resample.setSampleSizePercent(100.0);
        resample.setRandomSeed(seed);

        FilteredClassifier fc = new FilteredClassifier();
        fc.setClassifier(weka.classifiers.AbstractClassifier.makeCopy(base));
        fc.setFilter(resample);
        return fc;
    }

    /**
     * Run 10x10 cross validation and return averages; if out arrays provided, fills them
     */
    public static double evaluate10x10CV(FilteredClassifier fc,
                                         Instances data,
                                         double[] outPrecision,
                                         double[] outRecall,
                                         double[] outKappa) throws Exception {
        int repeats = 10;
        double totalAuc = 0.0;
        double totalPrec = 0.0;
        double totalRec = 0.0;
        double totalKap = 0.0;

        Resample resample = (Resample) fc.getFilter();
        for (int i = 0; i < repeats; i++) {
            resample.setRandomSeed(i);
            Evaluation eval = new Evaluation(data);
            eval.crossValidateModel(fc, data, 10, new Random(i));
            totalAuc += eval.weightedAreaUnderROC();
            if (outPrecision != null) totalPrec += eval.weightedPrecision();
            if (outRecall != null) totalRec += eval.weightedRecall();
            if (outKappa != null) totalKap += eval.kappa();
        }

        if (outPrecision != null) outPrecision[0] = totalPrec / repeats;
        if (outRecall != null) outRecall[0] = totalRec / repeats;
        if (outKappa != null) outKappa[0] = totalKap / repeats;
        return totalAuc / repeats;
    }
}
