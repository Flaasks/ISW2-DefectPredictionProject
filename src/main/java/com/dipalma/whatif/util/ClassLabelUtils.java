package com.dipalma.whatif.util;

import weka.core.Attribute;
import weka.core.Instances;

public final class ClassLabelUtils {
    private ClassLabelUtils() {}

    /**
     * Determine index of positive class. Order chosen to match WhatIfSimulator behavior
     */
    public static int positiveIndex(Instances data) {
        Attribute classAttr = data.classAttribute();
        int idx = classAttr.indexOfValue("yes");
        if (idx == -1) idx = classAttr.indexOfValue("1");
        if (idx == -1) idx = classAttr.indexOfValue("true");
        if (idx == -1) idx = Math.min(1, Math.max(0, classAttr.numValues() - 1));
        return idx;
    }
}
