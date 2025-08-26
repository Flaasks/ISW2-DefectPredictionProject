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
import org.eclipse.jgit.revwalk.RevWalk;
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

    // Piccolo contenitore per accumulare i contatori
    private static final class ChangeStats {
        int revisions = 0;
        final Set<String> authors = new HashSet<>();
        int linesAdded = 0;
        int linesDeleted = 0;
        int maxChurn = 0;
        int totalChurn = 0; // per la media
    }

    /**
     * Cammina la history fino a releaseCommit (incluso) e accumula le metriche
     * su tutte le edit che si sovrappongono alle linee del metodo.
     */
    private ChangeStats collectChangeStats(
            String filepath,
            int methodStartLine,
            int methodEndLine,
            RevCommit releaseCommit
    ) throws IOException {

        ChangeStats stats = new ChangeStats();

        try (RevWalk walk = new RevWalk(git.getRepository());
             DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            diffFormatter.setRepository(git.getRepository());
            walk.markStart(releaseCommit);

            for (RevCommit commit : walk) {
                if (commit.getParentCount() == 0) {
                    continue; // primo commit senza parent: niente diff da analizzare
                }

                RevCommit parent = walk.parseCommit(commit.getParent(0).getId());
                List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());

                boolean touchedThisCommit = false;
                int churnThisCommit = 0;

                for (DiffEntry diff : diffs) {
                    if (!affectsFile(diff, filepath)) {
                        continue;
                    }

                    FileHeader header = diffFormatter.toFileHeader(diff);
                    for (Edit edit : header.toEditList()) {
                        if (!overlapsMethod(methodStartLine, methodEndLine, edit)) {
                            continue;
                        }

                        if (!touchedThisCommit) {
                            touchedThisCommit = true;
                            stats.revisions++;
                            stats.authors.add(commit.getAuthorIdent().getEmailAddress());
                        }

                        int added = edit.getEndB() - edit.getBeginB();
                        int deleted = edit.getEndA() - edit.getBeginA();

                        stats.linesAdded += added;
                        stats.linesDeleted += deleted;
                        churnThisCommit += (added + deleted);
                    }
                }

                if (churnThisCommit > 0) {
                    stats.totalChurn += churnThisCommit;
                    if (churnThisCommit > stats.maxChurn) {
                        stats.maxChurn = churnThisCommit;
                    }
                }
            }
        }

        return stats;
    }

    /** Ritorna true se il diff tocca il file del metodo (gestisce rename/oldPath/newPath). */
    private static boolean affectsFile(DiffEntry diff, String filepath) {
        return filepath.equals(diff.getNewPath()) || filepath.equals(diff.getOldPath());
    }

    /** Overlap semplice tra il range del metodo e il range della edit nella "B side" (post-change). */
    private static boolean overlapsMethod(int methodStart, int methodEnd, Edit edit) {
        int changeStart = edit.getBeginB();
        int changeEnd = edit.getEndB();
        return Math.max(methodStart, changeStart) <= Math.min(methodEnd, changeEnd);
    }

    /**
     * Accurately calculates all change history features by analyzing git diffs.
     */
    private Map<String, Number> calculateChangeHistoryFeatures(
            TrackedMethod trackedMethod,
            CallableDeclaration<?> callable,
            RevCommit releaseCommit
    ) throws IOException {

        if (trackedMethod == null || callable == null || releaseCommit == null) {
            return getPlaceholderChangeFeatures();
        }

        final int methodStartLine = callable.getBegin().map(p -> p.line).orElse(-1);
        final int methodEndLine   = callable.getEnd().map(p -> p.line).orElse(-1);
        if (methodStartLine < 0 || methodEndLine < 0) {
            return getPlaceholderChangeFeatures();
        }

        final ChangeStats stats = collectChangeStats(
                trackedMethod.filepath(),
                methodStartLine,
                methodEndLine,
                releaseCommit
        );

        Map<String, Number> features = new HashMap<>();
        features.put("NR",        stats.revisions);
        features.put("NAuth",     stats.authors.size());
        features.put("stmtAdded", stats.linesAdded);
        features.put("stmtDeleted", stats.linesDeleted);
        features.put("maxChurn",  stats.maxChurn);
        features.put("avgChurn",  stats.revisions == 0 ? 0.0 : (double) stats.totalChurn / stats.revisions);
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