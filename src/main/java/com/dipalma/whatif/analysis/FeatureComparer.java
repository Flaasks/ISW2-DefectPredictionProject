package com.dipalma.whatif.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureComparer {

    private static final Logger log = LoggerFactory.getLogger(FeatureComparer.class);
    private static final String HEADER_FMT = "%-25s | %-15s | %-15s";
    private static final String ROW_FMT    = "%-25s | %-15s | %-15s%s";

    /**
     * Analyzes two text files and compares the features of the method found in each.
     */
    public void compareMethods(String originalFilePath, String refactoredFilePath) throws IOException {
        log.info("--- Comparing Features of Original vs. Refactored Method ---");

        log.info("Analyzing original file: {}", originalFilePath);
        Map<String, Number> featuresBefore = extractFeaturesFromFile(originalFilePath);

    log.info("Analyzing refactored file: {}", refactoredFilePath);
    Map<String, Number> featuresAfter = extractFeaturesFromFile(refactoredFilePath);

    // Per requisito: quando si compara l'originale con il refactor, NR e NAuth devono aumentare di 1
    featuresAfter.put("NR", featuresAfter.getOrDefault("NR", 1).intValue() + 1);
    featuresAfter.put("NAuth", featuresAfter.getOrDefault("NAuth", 1).intValue() + 1);

        printComparison(featuresBefore, featuresAfter);
    }

    private Map<String, Number> extractFeaturesFromFile(String filePath) throws IOException {
        String content = Files.readString(new File(filePath).toPath());

        String nameToken = content.trim().split("\\(")[0].trim();
        if (nameToken.contains(" ")) {
            nameToken = nameToken.substring(nameToken.lastIndexOf(' ') + 1);
        }

        CallableDeclaration<?> callable;
        try {
            if (Character.isUpperCase(nameToken.charAt(0))) {
                String parsableString = "class Dummy { " + content + " }";
                CompilationUnit cu = StaticJavaParser.parse(parsableString);
                callable = cu.findFirst(ConstructorDeclaration.class)
                        .orElseThrow(() -> new IllegalStateException("Could not find constructor in dummy class."));
            } else {
                callable = StaticJavaParser.parseMethodDeclaration(content);
            }
        } catch (Exception e) {
            log.error("FATAL: Failed to parse content from file: {}", filePath, e);
            return new HashMap<>();
        }

        return calculateFeatures(callable);
    }

    private Map<String, Number> calculateFeatures(CallableDeclaration<?> callable) {
        Map<String, Number> features = new HashMap<>();

    // JavaParser positions are 1-based inclusive: include both begin and end line
    int loc = callable.getEnd().map(p -> p.line).orElse(0) - callable.getBegin().map(p -> p.line).orElse(0) + 1;
        features.put("LOC", loc);

        AtomicInteger complexity = new AtomicInteger(1);
        callable.walk(node -> {
            if (node instanceof IfStmt || node instanceof ForStmt || node instanceof WhileStmt ||
                    node instanceof DoStmt || node instanceof SwitchEntry || node instanceof CatchClause ||
                    node instanceof ConditionalExpr) {
                complexity.incrementAndGet();
            }
        });
        features.put("CyclomaticComplexity", complexity.get());
    // NumberOfBranches: count decision points (if, loops, switch entries, ternary)
    int branches = countDecisionPoints(callable);
    features.put("NumberOfBranches", branches);
    log.debug("FeatureComparer: computed NumberOfBranches={} for callable {}", branches, callable.getNameAsString());
        features.put("ParameterCount", callable.getParameters().size());
    
    // baseline history values for standalone snippets
    features.put("NR", 1);
    features.put("NAuth", 1);

        return features;
    }

    private void printComparison(Map<String, Number> before, Map<String, Number> after) {
        log.info("--- Feature Comparison Result ---");
        if (log.isInfoEnabled()) {
            log.info("{}", String.format(HEADER_FMT, "Feature", "Before Refactor", "After Refactor"));
        }

    List<String> featureNames = Arrays.asList("LOC", "CyclomaticComplexity", "ParameterCount", "NumberOfBranches", "NR", "NAuth");

        for(String feature : featureNames) {
            String beforeValue = before.getOrDefault(feature, 0).toString();
            String afterValue = after.getOrDefault(feature, 0).toString();

            String marker = !beforeValue.equals(afterValue) ? "CHANGED" : "";
            if (log.isInfoEnabled()) {
                log.info("{}", String.format(ROW_FMT, feature, beforeValue, afterValue, marker));
            }
        }
    }

    // Count decision points inside a callable (if, for, foreach, while, do, ternary, switch cases)
    private int countDecisionPoints(CallableDeclaration<?> callable) {
        if (callable == null) return 0;
        int count = 0;
        try {
            count += callable.findAll(IfStmt.class).size();
            count += callable.findAll(ForStmt.class).size();
            count += callable.findAll(ForEachStmt.class).size();
            count += callable.findAll(WhileStmt.class).size();
            count += callable.findAll(DoStmt.class).size();
            count += callable.findAll(ConditionalExpr.class).size();
            int switchCases = callable.findAll(SwitchStmt.class).stream().mapToInt(sw -> sw.getEntries().size()).sum();
            count += switchCases;
        } catch (Exception e) {
            return 0;
        }
        return count;
    }
}