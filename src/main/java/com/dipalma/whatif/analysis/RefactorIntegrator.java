package com.dipalma.whatif.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Reads original/refactored method files (plain snippets) and computes per-method deltas
 */
public class RefactorIntegrator {

    private RefactorIntegrator() {
    }

    public static class FeatureDelta {
        public final int locDelta;
        public final int complexityDelta;

        public FeatureDelta(int locDelta, int complexityDelta) {
            this.locDelta = locDelta;
            this.complexityDelta = complexityDelta;
        }
    }

    public static Map<String, FeatureDelta> computeDeltas(File originalFile, File refactoredFile) throws IOException {
        String orig = Files.readString(originalFile.toPath());
        String ref = Files.readString(refactoredFile.toPath());

        Map<String, MethodDeclaration> origMethods = parseMethods(orig);
        Map<String, MethodDeclaration> refMethods = parseMethods(ref);

        Map<String, FeatureDelta> deltas = new HashMap<>();
        for (var entry : origMethods.entrySet()) {
            String sig = entry.getKey();
            MethodDeclaration om = entry.getValue();
            MethodDeclaration rm = refMethods.get(sig);
            if (rm == null) continue;
            int locO = om.getEnd().map(p -> p.line).orElse(0) - om.getBegin().map(p -> p.line).orElse(0);
            int locR = rm.getEnd().map(p -> p.line).orElse(0) - rm.getBegin().map(p -> p.line).orElse(0);
            int cO = computeCyclomatic(om);
            int cR = computeCyclomatic(rm);
            deltas.put(sig, new FeatureDelta(locR - locO, cR - cO));
        }
        return deltas;
    }

    private static Map<String, MethodDeclaration> parseMethods(String src) {
        Map<String, MethodDeclaration> map = new HashMap<>();
        try {
            // wrap in a dummy class if necessary
            String wrapped = src;
            if (!src.contains("class")) wrapped = "public class X { " + src + " }";
            var cu = StaticJavaParser.parse(wrapped);
            cu.findAll(MethodDeclaration.class).forEach(m -> map.put(m.getSignature().asString(), m));
        } catch (Exception e) {
            // fallback: no parse
        }
        return map;
    }

    private static int computeCyclomatic(MethodDeclaration m) {
        return com.dipalma.whatif.util.ComplexityUtils.computeCyclomatic(m);
    }
}
