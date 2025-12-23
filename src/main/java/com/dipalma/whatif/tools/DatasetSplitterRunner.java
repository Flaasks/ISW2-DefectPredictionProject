package com.dipalma.whatif.tools;

import com.dipalma.whatif.analysis.DatasetSplitter;

public class DatasetSplitterRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.exit(1);
        }
        DatasetSplitter.split(args[0], args[1]);
    }
}
