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

public class FeatureComparer {

    /**
     * Analyzes two text files and compares the features of the method found in each.
     */
    public void compareMethods(String originalFilePath, String refactoredFilePath) throws IOException {
        System.out.println("\n--- Comparing Features of Original vs. Refactored Method ---");

        System.out.println("Analyzing original file: " + originalFilePath);
        Map<String, Number> featuresBefore = extractFeaturesFromFile(originalFilePath);

        System.out.println("Analyzing refactored file: " + refactoredFilePath);
        Map<String, Number> featuresAfter = extractFeaturesFromFile(refactoredFilePath);

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
            System.err.println("FATAL: Failed to parse content from file: " + filePath);
            e.printStackTrace();
            return new HashMap<>();
        }

        return calculateFeatures(callable);
    }

    private Map<String, Number> calculateFeatures(CallableDeclaration<?> callable) {
        Map<String, Number> features = new HashMap<>();

        int loc = callable.getEnd().map(p -> p.line).orElse(0) - callable.getBegin().map(p -> p.line).orElse(0);
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
        features.put("ParameterCount", callable.getParameters().size());

        features.put("Duplication", 0);
        features.put("NR", 2);
        features.put("NAuth", 2);

        return features;
    }

    private void printComparison(Map<String, Number> before, Map<String, Number> after) {
        System.out.println("\n--- Step 9: Feature Comparison Result ---");
        System.out.printf("%-25s | %-15s | %-15s%n", "Feature", "Before Refactor", "After Refactor");

        List<String> featureNames = Arrays.asList("LOC", "CyclomaticComplexity", "ParameterCount", "Duplication", "NR", "NAuth");

        for(String feature : featureNames) {
            // *** THE FIX IS HERE: Use a valid Number (0) as the default value ***
            String beforeValue = before.getOrDefault(feature, 0).toString();
            String afterValue = after.getOrDefault(feature, 0).toString();

            String marker = !beforeValue.equals(afterValue) ? " <-- CHANGED" : "";
            System.out.printf("%-25s | %-15s | %-15s%s%n", feature, beforeValue, afterValue, marker);
        }
    }
}