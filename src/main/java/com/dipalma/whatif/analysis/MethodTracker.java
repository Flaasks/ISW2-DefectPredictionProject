package com.dipalma.whatif.analysis;


import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.expr.SimpleName;
import com.dipalma.whatif.connectors.GitConnector;
import com.dipalma.whatif.model.TrackedMethod;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MethodTracker {
    private final GitConnector git;
    private final Map<String, TrackedMethod> lastKnownMethods = new HashMap<>();
    private static final Logger log = LoggerFactory.getLogger(MethodTracker.class);

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
                    // store method positions (1-based) for later mapping of edits
                    callable.getBegin().ifPresent(begin -> trackedMethod.setStartLine(begin.line));
                    callable.getEnd().ifPresent(end -> trackedMethod.setEndLine(end.line));
                    currentMethods.add(trackedMethod);
                    methodAstMap.put(trackedMethod, callable);
                });
            } catch (Exception e) {
                log.warn("Failed to parse Java file {} in commit {} | {}",
                        file, commitId, e.getMessage(), e);
            }
        }

        // Calculate all features now that we have all methods for this release
    // Fingerprint method bodies (normalize identifiers) and count identical fingerprints
        Map<String, Integer> fingerprintCount = new HashMap<>();
        Map<TrackedMethod, String> methodFingerprint = new HashMap<>();
        for (Map.Entry<TrackedMethod, CallableDeclaration<?>> e : methodAstMap.entrySet()) {
            CallableDeclaration<?> callable = e.getValue();
            String normalized = "";
            try {
                CallableDeclaration<?> clone = callable.clone();
                clone.findAll(SimpleName.class).forEach(sn -> sn.setIdentifier("__ID__"));
                normalized = clone.toString().replaceAll("\\s+", " ").trim();
            } catch (Exception ex) {
                normalized = callable.toString().replaceAll("\\s+", " ").trim();
            }
            String fp = sha256(normalized);
            methodFingerprint.put(e.getKey(), fp);
            fingerprintCount.put(fp, fingerprintCount.getOrDefault(fp, 0) + 1);
        }

        // compute static features; initialize history accumulators
        for (TrackedMethod method : currentMethods) {
            CallableDeclaration<?> callable = methodAstMap.get(method);
            calculateStaticFeatures(method, callable);
        }

        // Accumulate change history by scanning commits once per release and mapping edits to methods
        accumulateHistoryForRelease(methodAstMap, releaseCommit);

        // after accumulation, flush history features into each method's feature map
        for (TrackedMethod method : currentMethods) {
            method.flushHistoryFeatures();
        }

        lastKnownMethods.clear();
        currentMethods.forEach(m -> lastKnownMethods.put(m.filepath() + "::" + m.signature(), m));

        return currentMethods;
    }

    private void calculateStaticFeatures(TrackedMethod method, CallableDeclaration<?> callable) {
        // JavaParser positions are 1-based inclusive: include both begin and end line
        int loc = callable.getEnd().map(p -> p.line).orElse(0) - callable.getBegin().map(p -> p.line).orElse(0) + 1;
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
        // NumberOfBranches: count decision points (if, loops, switch entries, ternary)
        int branches = countDecisionPoints(callable);
        method.addFeature("NumberOfBranches", branches);
        method.addFeature("ParameterCount", callable.getParameters().size());
    }

    private int countDecisionPoints(CallableDeclaration<?> callable) {
        int count = 0;
        try {
            count += callable.findAll(IfStmt.class).size();
            count += callable.findAll(ForStmt.class).size();
            count += callable.findAll(ForEachStmt.class).size();
            count += callable.findAll(WhileStmt.class).size();
            count += callable.findAll(DoStmt.class).size();
            count += callable.findAll(ConditionalExpr.class).size();
            // count switch entries (cases/default)
            int switchCases = callable.findAll(SwitchStmt.class).stream().mapToInt(sw -> sw.getEntries().size()).sum();
            count += switchCases;
        } catch (Exception e) {
            // be defensive: if parsing traversal fails, return 0
            return 0;
        }
        return count;
    }

    private int countElseParts(CallableDeclaration<?> callable) {
        int cnt = 0;
        try {
            cnt = (int) callable.findAll(IfStmt.class).stream().filter(ifstmt -> ifstmt.getElseStmt().isPresent()).count();
        } catch (Exception e) {
            // defensive: return 0 on failure
            return 0;
        }
        return cnt;
    }

    /** Scan commits for the release and accumulate edits into tracked methods */
    private void accumulateHistoryForRelease(
            Map<TrackedMethod, CallableDeclaration<?>> methodAstMap,
            RevCommit releaseCommit
    ) throws IOException {
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter fmt = newDiffFormatter(repo)) {

            walk.markStart(releaseCommit);
            Map<String, List<TrackedMethod>> methodsByFile = buildMethodIndex(methodAstMap);

            for (RevCommit commit : walk) {
                if (commit.getParentCount() == 0) continue;
                processCommitHistory(commit, walk, fmt, methodsByFile);
            }
        }
    }

    private Map<String, List<TrackedMethod>> buildMethodIndex(Map<TrackedMethod, CallableDeclaration<?>> methodAstMap) {
        Map<String, List<TrackedMethod>> methodsByFile = new HashMap<>();
        for (TrackedMethod tm : methodAstMap.keySet()) {
            methodsByFile.computeIfAbsent(tm.filepath(), k -> new ArrayList<>()).add(tm);
        }
        return methodsByFile;
    }

    private void processCommitHistory(RevCommit commit, RevWalk walk, DiffFormatter fmt,
                                      Map<String, List<TrackedMethod>> methodsByFile) throws IOException {
        RevCommit parent = walk.parseCommit(commit.getParent(0).getId());
        List<DiffEntry> diffs = fmt.scan(parent.getTree(), commit.getTree());

        CommitContext ctx = new CommitContext(commit, parent);
        processDiffsForCommit(diffs, fmt, methodsByFile, ctx);
        flushAccumulatedChanges(ctx);
    }

    private static class CommitContext {
        final RevCommit commit;
        final RevCommit parent;
        final Map<TrackedMethod, int[]> perMethodAccumulator = new IdentityHashMap<>();
        final Map<String, Map<String, Integer>> oldElseByFile = new HashMap<>();
        final Map<String, Map<String, Integer>> newElseByFile = new HashMap<>();
        final Set<String> seenEdits = new HashSet<>();

        CommitContext(RevCommit commit, RevCommit parent) {
            this.commit = commit;
            this.parent = parent;
        }
    }

    private void processDiffsForCommit(List<DiffEntry> diffs, DiffFormatter fmt,
                                       Map<String, List<TrackedMethod>> methodsByFile,
                                       CommitContext ctx) throws IOException {
        for (DiffEntry diff : diffs) {
            String path = diff.getNewPath() == null ? diff.getOldPath() : diff.getNewPath();
            List<TrackedMethod> methods = methodsByFile.get(path);
            if (methods == null || methods.isEmpty()) continue;

            ensureElseCountsCached(path, ctx);
            processEditsInDiff(diff, path, fmt, methods, ctx);
        }
    }

    private void ensureElseCountsCached(String path, CommitContext ctx) {
        if (ctx.oldElseByFile.containsKey(path)) return;

        try {
            String oldContent = git.getFileContent(path, ctx.parent.getName());
            String newContent = git.getFileContent(path, ctx.commit.getName());

            ctx.oldElseByFile.put(path, computeElseCountsForContent(oldContent));
            ctx.newElseByFile.put(path, computeElseCountsForContent(newContent));
        } catch (Exception ex) {
            log.debug("Could not compute else counts for {} in commit {}: {}", path, ctx.commit.getName(), ex.getMessage());
            ctx.oldElseByFile.put(path, Collections.emptyMap());
            ctx.newElseByFile.put(path, Collections.emptyMap());
        }
    }

    private Map<String, Integer> computeElseCountsForContent(String content) {
        Map<String, Integer> map = new HashMap<>();
        if (content == null || content.isEmpty()) return map;

        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            cu.findAll(CallableDeclaration.class).forEach(cd -> {
                try {
                    map.put(cd.getSignature().asString(), countElseParts(cd));
                } catch (Exception ex) { /* ignore individual failures */ }
            });
        } catch (Exception ex) { /* ignore parse errors */ }
        return map;
    }

    private void processEditsInDiff(DiffEntry diff, String path, DiffFormatter fmt,
                                    List<TrackedMethod> methods, CommitContext ctx) throws IOException {
        FileHeader header = fmt.toFileHeader(diff);
        for (Edit edit : header.toEditList()) {
            String editKey = buildEditKey(path, edit, ctx.commit);
            if (ctx.seenEdits.contains(editKey)) continue;
            ctx.seenEdits.add(editKey);

            processSingleEdit(edit, methods, ctx);
        }
    }

    private String buildEditKey(String path, Edit edit, RevCommit commit) {
        return commit.getName() + ":" + path + ":" +
                edit.getBeginA() + "," + edit.getEndA() + ":" +
                edit.getBeginB() + "," + edit.getEndB();
    }

    private void processSingleEdit(Edit edit, List<TrackedMethod> methods, CommitContext ctx) {
        final int MAX_EDIT_LINES = 200;
        int editBegin = edit.getBeginB() + 1;
        int editEnd = edit.getEndB();

        int added = Math.min(linesAdded(edit), MAX_EDIT_LINES);
        int deleted = Math.min(linesDeleted(edit), MAX_EDIT_LINES);
        int churn = added + deleted;

        mapEditToMethods(editBegin, editEnd, added, deleted, churn, methods, ctx);
    }

    private void mapEditToMethods(int editBegin, int editEnd,
                                   int added, int deleted, int churn,
                                   List<TrackedMethod> methods, CommitContext ctx) {
        boolean mappedAny = false;
        for (TrackedMethod tm : methods) {
            if (editOverlapsMethod(tm, editBegin, editEnd)) {
                accumulateEditStats(tm, added, deleted, churn, ctx);
                mappedAny = true;
            }
        }

        if (!mappedAny) {
            log.debug("Edit {}-{} not mapped to any method", editBegin, editEnd);
        }
    }

    private boolean editOverlapsMethod(TrackedMethod tm, int editBegin, int editEnd) {
        if (!tm.hasPosition()) return false;
        return Math.max(tm.getStartLine(), editBegin) <= Math.min(tm.getEndLine(), editEnd);
    }

    private void accumulateEditStats(TrackedMethod tm, int added, int deleted, int churn, CommitContext ctx) {
        int[] acc = ctx.perMethodAccumulator.computeIfAbsent(tm, k -> new int[3]);
        acc[0] += added;
        acc[1] += deleted;
        acc[2] += churn;
    }

    private void flushAccumulatedChanges(CommitContext ctx) {
        for (Map.Entry<TrackedMethod, int[]> e : ctx.perMethodAccumulator.entrySet()) {
            updateMethodWithCommitData(e.getKey(), e.getValue(), ctx);
        }
    }

    private void updateMethodWithCommitData(TrackedMethod tm, int[] acc, CommitContext ctx) {
        int added = acc[0];
        int deleted = acc[1];
        int total = acc[2];

        tm.incrNr();
        tm.addAuthor(ctx.commit.getAuthorIdent().getEmailAddress());
        tm.addStmtAdded(added);
        tm.addStmtDeleted(deleted);
        tm.addTotalChurn(total);
        tm.updateMaxChurn(added - deleted);

        updateElseAddedForMethod(tm, ctx);
    }

    private void updateElseAddedForMethod(TrackedMethod tm, CommitContext ctx) {
        try {
            String path = tm.filepath();
            String sig = tm.signature();

            int oldElse = ctx.oldElseByFile.getOrDefault(path, Collections.emptyMap()).getOrDefault(sig, 0);
            int newElse = ctx.newElseByFile.getOrDefault(path, Collections.emptyMap()).getOrDefault(sig, 0);

            if (newElse > oldElse) {
                tm.addElseAdded(newElse - oldElse);
            }
        } catch (Exception ex) {
            // ignore elseAdded computation errors so history still accumulates
        }
    }


    private static DiffFormatter newDiffFormatter(Repository repo) {
        DiffFormatter fmt = new DiffFormatter(DisabledOutputStream.INSTANCE);
        fmt.setRepository(repo);
        return fmt;
    }

    private static int linesAdded(Edit edit) {
        // JGit Edit indices are 0-based and end is exclusive. Number of added lines is endB - beginB.
        return Math.max(0, edit.getEndB() - edit.getBeginB());
    }

    private static int linesDeleted(Edit edit) {
        // Number of deleted lines is endA - beginA (0-based, end exclusive).
        return Math.max(0, edit.getEndA() - edit.getBeginA());
    }


    /**
     * Accurately calculates all change history features by analyzing git diffs.
     */
    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toString(s.hashCode());
        }
    }
}