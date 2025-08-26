package com.dipalma.whatif.analysis;


import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;
import com.dipalma.whatif.connectors.GitConnector;
import com.dipalma.whatif.model.TrackedMethod;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MethodTracker {
    private final GitConnector git;
    private final Map<String, TrackedMethod> lastKnownMethods = new HashMap<>();

    public MethodTracker(GitConnector git) {
        this.git = git;
    }

    public List<TrackedMethod> getMethodsForRelease(RevCommit releaseCommit) throws IOException, GitAPIException {
        String commitId = releaseCommit.getName();
        List<String> javaFiles = git.getJavaFilesForCommit(commitId);

        List<TrackedMethod> currentMethods = new ArrayList<>();
        Map<TrackedMethod, CallableDeclaration<?>> methodAstMap = new HashMap<>();

        for (String file : javaFiles) {
            String content = git.getFileContent(file, commitId);
            if (content == null || content.isEmpty()) continue;

            try {
                CompilationUnit cu = StaticJavaParser.parse(content);
                cu.findAll(CallableDeclaration.class).forEach(callable -> {
                    String signature = callable.getSignature().asString();
                    String fullSignatureKey = file + "::" + signature;

                    String id;
                    if (lastKnownMethods.containsKey(fullSignatureKey)) {
                        id = lastKnownMethods.get(fullSignatureKey).id();
                    } else {
                        id = UUID.randomUUID().toString();
                    }

                    TrackedMethod trackedMethod = new TrackedMethod(id, signature, file);
                    currentMethods.add(trackedMethod);
                    methodAstMap.put(trackedMethod, callable);
                });
            } catch (Exception e) {
                System.out.println("Warning: Failed to parse Java file: " + file + " in commit " + commitId + " | " + e.getMessage());
            }
        }

        // Calculate all features now that we have all methods for this release
        for(TrackedMethod method : currentMethods) {
            CallableDeclaration<?> callable = methodAstMap.get(method);
            calculateAllFeatures(method, callable, releaseCommit);
        }

        lastKnownMethods.clear();
        currentMethods.forEach(m -> lastKnownMethods.put(m.filepath() + "::" + m.signature(), m));

        return currentMethods;
    }

    private void calculateAllFeatures(TrackedMethod method, CallableDeclaration<?> callable, RevCommit releaseCommit) {
        int loc = callable.getEnd().map(p -> p.line).orElse(0) - callable.getBegin().map(p -> p.line).orElse(0);
        method.addFeature("LOC", loc);

        AtomicInteger complexity = new AtomicInteger(1);
        callable.walk(node -> {
            if (node instanceof IfStmt || node instanceof ForStmt || node instanceof WhileStmt ||
                    node instanceof DoStmt || node instanceof SwitchEntry || node instanceof CatchClause ||
                    node instanceof ConditionalExpr) {
                complexity.incrementAndGet();
            }
        });
        method.addFeature("CyclomaticComplexity", complexity.get());
        method.addFeature("ParameterCount", callable.getParameters().size());
        method.addFeature("Duplication", 0); // Placeholder until the full release can be analyzed

        try {
            Map<String, Number> changeFeatures = calculateChangeHistoryFeatures(method, callable, releaseCommit);
            method.addAllFeatures(changeFeatures);
        } catch (Exception e) {
            System.out.println("Warning: Could not compute full history for method: " + method.signature());
            addPlaceholderChangeFeatures(method);
        }
    }

    /**
     * Accurately calculates all change history features by analyzing git diffs.
     */
    private Map<String, Number> calculateChangeHistoryFeatures(TrackedMethod trackedMethod, CallableDeclaration<?> callable, RevCommit releaseCommit) throws GitAPIException, IOException {
        Map<String, Number> features = new HashMap<>();
        Set<String> authors = new HashSet<>();
        int revisionCount = 0;
        int totalLinesAdded = 0;
        int totalLinesDeleted = 0;
        int maxChurn = 0;

        // Get the line range for the current method to check against diffs
        int methodStartLine = callable.getBegin().map(p -> p.line).orElse(-1);
        int methodEndLine = callable.getEnd().map(p -> p.line).orElse(-1);
        if (methodStartLine == -1) { // Cannot determine range, return placeholders
            return getPlaceholderChangeFeatures();
        }

        Iterable<RevCommit> commits = git.getGit().log().add(releaseCommit.getId()).addPath(trackedMethod.filepath()).call();

        for (RevCommit commit : commits) {
            if (commit.getParentCount() == 0) continue;
            RevCommit parent = commit.getParent(0);

            try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(git.getRepository());
                List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());

                for (DiffEntry diff : diffs) {
                    if (!diff.getNewPath().equals(trackedMethod.filepath())) continue;

                    FileHeader fileHeader = diffFormatter.toFileHeader(diff);
                    for (Edit edit : fileHeader.toEditList()) {
                        // A simple overlap check. A more advanced check would track line number changes.
                        int changeStart = edit.getBeginB();
                        int changeEnd = edit.getEndB();
                        if (Math.max(methodStartLine, changeStart) <= Math.min(methodEndLine, changeEnd)) {
                            revisionCount++;
                            authors.add(commit.getAuthorIdent().getName());
                            int linesAdded = edit.getLengthB();
                            int linesDeleted = edit.getLengthA();
                            totalLinesAdded += linesAdded;
                            totalLinesDeleted += linesDeleted;
                            int currentChurn = linesAdded + linesDeleted;
                            if (currentChurn > maxChurn) {
                                maxChurn = currentChurn;
                            }
                            // Break from inner loops once we've attributed this commit's change
                            break;
                        }
                    }
                }
            }
        }

        features.put("NR", revisionCount);
        features.put("NAuth", authors.size());
        features.put("stmtAdded", totalLinesAdded);
        features.put("stmtDeleted", totalLinesDeleted);
        features.put("maxChurn", maxChurn);
        double avgChurn = (revisionCount > 0) ? (double)(totalLinesAdded + totalLinesDeleted) / revisionCount : 0;
        features.put("avgChurn", avgChurn);

        return features;
    }

    private Map<String, Number> getPlaceholderChangeFeatures() {
        Map<String, Number> features = new HashMap<>();
        features.put("NR", 0);
        features.put("NAuth", 0);
        features.put("stmtAdded", 0);
        features.put("stmtDeleted", 0);
        features.put("maxChurn", 0);
        features.put("avgChurn", 0);
        return features;
    }

    private void addPlaceholderChangeFeatures(TrackedMethod method) {
        method.addAllFeatures(getPlaceholderChangeFeatures());
    }
}