package com.dipalma.whatif.tools;

import com.dipalma.whatif.analysis.WhatIfSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhatIfRunner {
    private static final Logger log = LoggerFactory.getLogger(WhatIfRunner.class);

    public static void main(String[] args) {
        try {
            String bk = "BOOKKEEPER_processed.csv";
            String sn = "SYNCOPE_processed.csv";

            log.info("Running what-if simulation for {}", bk);
            WhatIfSimulator bkSim = new WhatIfSimulator(bk);
            bkSim.runFullDatasetSimulation();

            log.info("Running what-if simulation for {}", sn);
            WhatIfSimulator snSim = new WhatIfSimulator(sn);
            snSim.runFullDatasetSimulation();

            log.info("What-if runs completed.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
