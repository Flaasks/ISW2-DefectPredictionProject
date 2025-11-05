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
                    callable.getBegin().ifPresent(b -> trackedMethod.setStartLine(b.line));
                    callable.getEnd().ifPresent(e -> trackedMethod.setEndLine(e.line));
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
            String fp = methodFingerprint.getOrDefault(method, "");
            // Duplication feature removed (replaced by NumberOfBranches)
            // compute static features (LOC, complexity, params)
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
        // Duplication already set earlier
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

            // build index of methods by filepath for quick lookup
            Map<String, List<TrackedMethod>> methodsByFile = new HashMap<>();
            for (TrackedMethod tm : methodAstMap.keySet()) {
                methodsByFile.computeIfAbsent(tm.filepath(), k -> new ArrayList<>()).add(tm);
            }

            final int MAX_EDIT_LINES = 200; // cap per edit to avoid huge single-hunk inflation
            for (RevCommit commit : walk) {
                if (commit.getParentCount() == 0) continue;

                RevCommit parent = walk.parseCommit(commit.getParent(0).getId());
                List<DiffEntry> diffs = fmt.scan(parent.getTree(), commit.getTree());

                // For this commit, we will aggregate edits per method to ensure NR is counted once
                Map<TrackedMethod, int[]> perMethodAccumulator = new IdentityHashMap<>();

                // cache else-part counts per file for this commit (old vs new)
                Map<String, Map<String, Integer>> oldElseByFile = new HashMap<>();
                Map<String, Map<String, Integer>> newElseByFile = new HashMap<>();

                // dedupe edits per commit/path/begin/end to avoid double counting
                Set<String> seenEdits = new HashSet<>();

                for (DiffEntry diff : diffs) {
                    String path = diff.getNewPath() == null ? diff.getOldPath() : diff.getNewPath();
                    List<TrackedMethod> methods = methodsByFile.get(path);
                    if (methods == null || methods.isEmpty()) continue;

                    // compute else counts for old and new file versions once per path
                    if (!oldElseByFile.containsKey(path) && !newElseByFile.containsKey(path)) {
                        try {
                            String oldContent = git.getFileContent(path, parent.getName());
                            String newContent = git.getFileContent(path, commit.getName());
                            Map<String, Integer> oldMap = new HashMap<>();
                            Map<String, Integer> newMap = new HashMap<>();
                            if (oldContent != null && !oldContent.isEmpty()) {
                                try {
                                    CompilationUnit oldCu = StaticJavaParser.parse(oldContent);
                                    oldCu.findAll(CallableDeclaration.class).forEach(cd -> {
                                        try {
                                            String sig = cd.getSignature().asString();
                                            int cnt = countElseParts(cd);
                                            oldMap.put(sig, cnt);
                                        } catch (Exception ex) { /* ignore individual failures */ }
                                    });
                                } catch (Exception ex) { /* ignore parse errors */ }
                            }
                            if (newContent != null && !newContent.isEmpty()) {
                                try {
                                    CompilationUnit newCu = StaticJavaParser.parse(newContent);
                                    newCu.findAll(CallableDeclaration.class).forEach(cd -> {
                                        try {
                                            String sig = cd.getSignature().asString();
                                            int cnt = countElseParts(cd);
                                            newMap.put(sig, cnt);
                                        } catch (Exception ex) { /* ignore individual failures */ }
                                    });
                                } catch (Exception ex) { /* ignore parse errors */ }
                            }
                            oldElseByFile.put(path, oldMap);
                            newElseByFile.put(path, newMap);
                        } catch (Exception ex) {
                            log.debug("Could not compute else counts for {} in commit {}: {}", path, commit.getName(), ex.getMessage());
                            oldElseByFile.put(path, Collections.emptyMap());
                            newElseByFile.put(path, Collections.emptyMap());
                        }
                    }

                    FileHeader header = fmt.toFileHeader(diff);
                    List<Edit> edits = header.toEditList();

                    for (Edit edit : edits) {
                        // dedupe key
                        String editKey = commit.getName() + ":" + path + ":" + edit.getBeginA() + "," + edit.getEndA() + ":" + edit.getBeginB() + "," + edit.getEndB();
                        if (seenEdits.contains(editKey)) continue;
                        seenEdits.add(editKey);

                        // convert to 1-based inclusive range for comparison
                        int editBegin = edit.getBeginB() + 1;
                        int editEnd = edit.getEndB(); // end is exclusive in JGit; treat as inclusive upper bound

                        boolean mappedAny = false;
                        int addedRaw = linesAdded(edit);
                        int deletedRaw = linesDeleted(edit);
                        int added = Math.min(addedRaw, MAX_EDIT_LINES);
                        int deleted = Math.min(deletedRaw, MAX_EDIT_LINES);
                        int churn = added + deleted;

                        for (TrackedMethod tm : methods) {
                            if (!tm.hasPosition()) continue;
                            int mStart = tm.getStartLine();
                            int mEnd = tm.getEndLine();
                            if (Math.max(mStart, editBegin) <= Math.min(mEnd, editEnd)) {
                                // accumulate per-method for this commit
                                int[] acc = perMethodAccumulator.computeIfAbsent(tm, k -> new int[3]);
                                acc[0] += added;    // added
                                acc[1] += deleted;  // deleted
                                acc[2] += churn;    // total churn for this commit
                                mappedAny = true;
                                // continue: an edit may overlap multiple methods
                            }
                        }

                        if (!mappedAny) {
                            log.debug("Edit {}-{} in {} not mapped to any method", editBegin, editEnd, path);
                        }
                    }
                }

                // After processing all diffs/edits for this commit, flush accumulators into methods
                for (Map.Entry<TrackedMethod, int[]> e : perMethodAccumulator.entrySet()) {
                    TrackedMethod tm = e.getKey();
                    int[] acc = e.getValue();
                    int added = acc[0];
                    int deleted = acc[1];
                    int total = acc[2];
                    // count this commit as one revision touching the method
                    tm.incrNr();
                    tm.addAuthor(commit.getAuthorIdent().getEmailAddress());
                    tm.addStmtAdded(added);
                    tm.addStmtDeleted(deleted);
                    // totalChurn remains sum of added+deleted across history
                    tm.addTotalChurn(total);
                    // maxChurn should be based on net change (added - deleted) per commit
                    int net = added - deleted;
                    tm.updateMaxChurn(net);
                    // compute elseAdded by comparing old/new method bodies when available
                    try {
                        String path = tm.filepath();
                        String sig = tm.signature();
                        int oldElse = 0;
                        int newElse = 0;
                        Map<String, Integer> oldMap = oldElseByFile.getOrDefault(path, Collections.emptyMap());
                        Map<String, Integer> newMap = newElseByFile.getOrDefault(path, Collections.emptyMap());
                        if (oldMap.containsKey(sig)) oldElse = oldMap.get(sig);
                        if (newMap.containsKey(sig)) newElse = newMap.get(sig);
                        if (newElse > oldElse) {
                            tm.addElseAdded(newElse - oldElse);
                        }
                    } catch (Exception ex) {
                        // ignore elseAdded computation errors so history still accumulates
                    }
                }
            }
        }
    }

    // Piccolo contenitore per accumulare i contatori
    // Old per-commit helpers removed; accumulation performed in accumulateHistoryForRelease

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


    /** Ritorna true se il diff tocca il file del metodo (gestisce rename/oldPath/newPath). */
    private static boolean affectsFile(DiffEntry diff, String filepath) {
        return filepath.equals(diff.getNewPath()) || filepath.equals(diff.getOldPath());
    }

    /** Overlap semplice tra il range del metodo e il range della edit nella "B side" (post-change). */
    private static boolean overlapsMethod(int methodStart, int methodEnd, Edit edit) {
        // JavaParser line numbers are 1-based inclusive. JGit Edit uses 0-based indices
        // with end exclusive. Convert begin to 1-based inclusive to compare ranges.
        int changeStart = edit.getBeginB() + 1; // convert 0-based -> 1-based
        int changeEnd = edit.getEndB();        // end is exclusive in JGit; using as-is maps to inclusive 1-based
        return Math.max(methodStart, changeStart) <= Math.min(methodEnd, changeEnd);
    }

    /**
     * Accurately calculates all change history features by analyzing git diffs.
     */
    // Old calculateChangeHistoryFeatures / placeholders removed: history now stored in TrackedMethod

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