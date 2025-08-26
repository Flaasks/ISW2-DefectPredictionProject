package com.dipalma.whatif.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


public final class TrackedMethod {
    private final String id;
    private final String signature;
    private final String filepath;
    private final Map<String, Number> features = new HashMap<>();

    public TrackedMethod(String id, String signature, String filepath) {
        this.id = id;
        this.signature = signature;
        this.filepath = filepath;
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