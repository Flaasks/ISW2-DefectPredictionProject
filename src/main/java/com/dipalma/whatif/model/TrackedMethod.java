package com.dipalma.whatif.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;


public final class TrackedMethod {
    private final String id;
    private final String signature;
    private final String filepath;
    private final Map<String, Number> features = new HashMap<>();
    // transient accumulation fields used during history computation
    private int startLine = -1; // 1-based inclusive
    private int endLine = -1;   // 1-based inclusive

    private int nr = 0;
    private final Set<String> authors = new HashSet<>();
    private int stmtAdded = 0;
    private int stmtDeleted = 0;
    private int totalChurn = 0;
    private int maxChurn = 0;
    private int elseAdded = 0;

    public TrackedMethod(String id, String signature, String filepath) {
        this.id = id;
        this.signature = signature;
        this.filepath = filepath;
    }

    // position helpers
    public void setStartLine(int startLine) { this.startLine = startLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public boolean hasPosition() { return startLine > 0 && endLine > 0; }

    // accumulation helpers for change history
    public void incrNr() { this.nr++; }
    public int getNr() { return this.nr; }
    public void addAuthor(String a) { if (a != null) this.authors.add(a); }
    public int getNAuth() { return this.authors.size(); }
    public void addStmtAdded(int n) { this.stmtAdded += Math.max(0, n); }
    public void addStmtDeleted(int n) { this.stmtDeleted += Math.max(0, n); }
    public int getStmtAdded() { return this.stmtAdded; }
    public int getStmtDeleted() { return this.stmtDeleted; }
    public void addTotalChurn(int n) { this.totalChurn += Math.abs(n); }
    /**
     * Update maxChurn using the net change (added - deleted) for a single commit.
     * This records the maximum net churn observed across commits.
     */
    public void updateMaxChurn(int netChange) { this.maxChurn = Math.max(this.maxChurn, netChange); }
    public int getMaxChurn() { return this.maxChurn; }
    public int getTotalChurn() { return this.totalChurn; }

    public void addElseAdded(int n) { this.elseAdded += Math.max(0, n); }
    public int getElseAdded() { return this.elseAdded; }

    // flush accumulated history into feature map
    public void flushHistoryFeatures() {
        this.features.put("NR", this.nr);
        this.features.put("NAuth", this.getNAuth());
        this.features.put("stmtAdded", this.stmtAdded);
        this.features.put("stmtDeleted", this.stmtDeleted);
        // replace maxChurn historic metric with elseAdded per user request
        this.features.put("elseAdded", this.elseAdded);
        double avg = this.nr == 0 ? 0.0 : (double) this.totalChurn / this.nr;
        this.features.put("avgChurn", avg);
    }

    public String id() { return id; }
    public String signature() { return signature; }
    public String filepath() { return filepath; }
    public Map<String, Number> getFeatures() { return features; }

    public void addFeature(String name, Number value) {
        this.features.put(name, value);
    }
    public void addAllFeatures(Map<String, Number> features) {
        this.features.putAll(features);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (TrackedMethod) obj;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}