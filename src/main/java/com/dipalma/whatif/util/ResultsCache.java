package com.dipalma.whatif.util;

import java.io.*;
import java.util.*;

public final class ResultsCache {
    private ResultsCache() {}

    private static final String CACHE_FILE = "whatif_results_cache.dat";

    public static class CachedResults implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String bookkeeperSelectedMethod;
        public final String bookkeeperSelectedFeature;
        public final String syncopeSelectedMethod;
        public final String syncopeSelectedFeature;
        public final long generatedAt;

        public CachedResults(String bkMethod, String bkFeature, String snMethod, String snFeature) {
            this.bookkeeperSelectedMethod = bkMethod;
            this.bookkeeperSelectedFeature = bkFeature;
            this.syncopeSelectedMethod = snMethod;
            this.syncopeSelectedFeature = snFeature;
            this.generatedAt = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "CachedResults{" +
                    "bkMethod='" + bookkeeperSelectedMethod + '\'' +
                    ", bkFeature='" + bookkeeperSelectedFeature + '\'' +
                    ", snMethod='" + syncopeSelectedMethod + '\'' +
                    ", snFeature='" + syncopeSelectedFeature + '\'' +
                    ", generatedAt=" + new java.util.Date(generatedAt) +
                    '}';
        }
    }

    public static void saveResults(CachedResults results) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(CACHE_FILE))) {
            oos.writeObject(results);
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(ResultsCache.class)
                    .warn("Failed to save results cache: {}", e.getMessage());
        }
    }

    public static Optional<CachedResults> loadResults() {
        File cacheFile = new File(CACHE_FILE);
        if (!cacheFile.exists()) {
            return Optional.empty();
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
            CachedResults results = (CachedResults) ois.readObject();
            return Optional.of(results);
        } catch (IOException | ClassNotFoundException e) {
            org.slf4j.LoggerFactory.getLogger(ResultsCache.class)
                    .warn("Failed to load results cache: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static void clearCache() {
        File cacheFile = new File(CACHE_FILE);
        if (cacheFile.exists() && !cacheFile.delete()) {
            org.slf4j.LoggerFactory.getLogger(ResultsCache.class)
                    .warn("Failed to delete cache file");
        }
    }

    public static String getCacheFilePath() {
        return CACHE_FILE;
    }
}
