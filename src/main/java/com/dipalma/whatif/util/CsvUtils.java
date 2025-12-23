package com.dipalma.whatif.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.io.IOException;
import java.io.Reader;

public final class CsvUtils {
    private CsvUtils() {}

    /**
     * Parse CSV using the first record as header and skip it in iteration
    */
    public static CSVParser parseWithHeader(Reader in) throws IOException {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(in);
    }
}
