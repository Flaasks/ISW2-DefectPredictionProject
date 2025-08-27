package com.dipalma.whatif.connectors;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.dipalma.whatif.model.JiraTicket;
import com.dipalma.whatif.model.ProjectRelease;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitConnector {
    private final String remoteUrl;
    private final String localPath;
    private Repository repository;
    private Git git;
    private static final Logger log = LoggerFactory.getLogger(GitConnector.class);

    // ... (constructor and cloneOrOpenRepo methods remain the same)
    public GitConnector(String remoteUrl, String localPath) {
        this.remoteUrl = remoteUrl;
        this.localPath = "temp-repo/" + localPath;
    }

    public void cloneOrOpenRepo() throws IOException, GitAPIException {
        File localDir = new File(localPath);
        if (localDir.exists()) {
            log.info("Opening existing repository at {}", localPath);
            git = Git.open(localDir);
            repository = git.getRepository();
        } else {
            log.info("Cloning {} to {}...", remoteUrl, localPath);
            git = Git.cloneRepository().setURI(remoteUrl).setDirectory(localDir).call();
            repository = git.getRepository();
            log.info("Clone complete.");
        }
    }


    /**
     * Identifies the exact methods modified in a ticket's fix-commit.
     * @return A map where the key is the bug ID and the value is a list of affected method signatures.
     */
    public Map<String, List<String>> getBugToMethodsMap(List<JiraTicket> tickets) throws IOException {
        log.info("Mapping bug fixes to specific methods...");
        Map<String, List<String>> bugToMethods = new HashMap<>();
        try (RevWalk revWalk = new RevWalk(repository)) {

            for (JiraTicket ticket : tickets) {
                String fixHash = ticket.getFixCommitHash();
                if (fixHash == null) {
                    continue;
                }
                RevCommit commit = revWalk.parseCommit(repository.resolve(fixHash));
                if (commit.getParentCount() > 0) {
                    RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());

                    List<DiffEntry> diffs = getDiff(parent, commit);
                    List<String> affectedMethods = new ArrayList<>();

                    for (DiffEntry diff : diffs) {
                        if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY
                                && diff.getNewPath().endsWith(".java")) {
                            affectedMethods.addAll(getModifiedMethods(diff, commit));
                        }
                    }

                    bugToMethods.put(ticket.getKey(), affectedMethods);
                }
            }
        }
        log.info("Finished mapping bugs to methods.");
        return bugToMethods;
    }

    /**
     * Helper method to parse a diff and find which methods were modified.
     */
    private List<String> getModifiedMethods(DiffEntry diff, RevCommit commit) throws IOException {
        List<String> modifiedMethods = new ArrayList<>();
        String newPath = diff.getNewPath();
        String fileContent = getFileContent(newPath, commit.getName());

        if (fileContent.isEmpty()) return modifiedMethods;

        List<MethodDeclaration> methods;
        try {
            methods = new ArrayList<>(StaticJavaParser.parse(fileContent).findAll(MethodDeclaration.class));
        } catch (Exception e) { return modifiedMethods; }

        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            FileHeader fileHeader = diffFormatter.toFileHeader(diff);
            for (Edit edit : fileHeader.toEditList()) {
                for (MethodDeclaration method : methods) {
                    if (method.getRange().isPresent()) {
                        int methodStart = method.getRange().get().begin.line;
                        int methodEnd = method.getRange().get().end.line;
                        if (Math.max(methodStart, edit.getBeginB()) <= Math.min(methodEnd, edit.getEndB())) {
                            modifiedMethods.add(newPath + "::" + method.getSignature().asString());
                        }
                    }
                }
            }
        }
        return modifiedMethods;
    }

    public Map<String, RevCommit> getReleaseCommits(List<ProjectRelease> releases) throws IOException {
        Map<String, RevCommit> releaseCommits = new HashMap<>();
        Collection<Ref> allTags = repository.getRefDatabase().getRefsByPrefix("refs/tags/");
        Map<String, Ref> tagMap = new HashMap<>();
        for (Ref tagRef : allTags) {
            tagMap.put(tagRef.getName().substring("refs/tags/".length()), tagRef);
        }
        try (RevWalk walk = new RevWalk(repository)) {
            for (ProjectRelease release : releases) {
                String releaseName = release.name();
                Ref tagRef = null;
                if (tagMap.containsKey(releaseName)) tagRef = tagMap.get(releaseName);
                else if (tagMap.containsKey("v" + releaseName)) tagRef = tagMap.get("v" + releaseName);
                else if (tagMap.containsKey("release-" + releaseName)) tagRef = tagMap.get("release-" + releaseName);
                else if (tagMap.containsKey("syncope-" + releaseName)) tagRef = tagMap.get("syncope-" + releaseName);
                else if (tagMap.containsKey("bookkeeper-" + releaseName)) tagRef = tagMap.get("bookkeeper-" + releaseName);
                if (tagRef != null) {
                    releaseCommits.put(release.name(), walk.parseCommit(tagRef.getObjectId()));
                } else {
                    log.warn("Could not find a matching Git tag for JIRA release: {}", release.name());
                }
            }
        }
        return releaseCommits;
    }

    public void findAndSetFixCommits(List<JiraTicket> tickets) throws GitAPIException, IOException {
        log.info("Scanning git log to link commits to JIRA tickets...");
        Pattern pattern = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");
        Map<String, JiraTicket> ticketMap = new HashMap<>();
        for (JiraTicket ticket : tickets) {
            ticketMap.put(ticket.getKey(), ticket);
        }
        Iterable<RevCommit> commits = git.log().all().call();
        for (RevCommit commit : commits) {
            Matcher matcher = pattern.matcher(commit.getFullMessage());
            while (matcher.find()) {
                String ticketKey = matcher.group(1);
                if (ticketMap.containsKey(ticketKey)) {
                    JiraTicket ticket = ticketMap.get(ticketKey);
                    if (ticket.getFixCommitHash() == null) {
                        ticket.setFixCommitHash(commit.getName());
                        ticket.setResolutionDate(LocalDateTime.ofInstant(commit.getAuthorIdent().getWhenAsInstant(), ZoneId.systemDefault()));
                    }
                }
            }
        }
        log.info("Finished scanning git log.");
    }
    public List<DiffEntry> getDiff(RevCommit commit1, RevCommit commit2) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);
            try (ObjectReader reader = repository.newObjectReader()) {
                CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
                oldTreeParser.reset(reader, commit1.getTree().getId());
                CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
                newTreeParser.reset(reader, commit2.getTree().getId());
                return diffFormatter.scan(oldTreeParser, newTreeParser);
            }
        }
    }
    public List<String> getJavaFilesForCommit(String commitId) throws GitAPIException, IOException {
        git.checkout().setName(commitId).call(); // Checkout the specific commit
        List<String> javaFiles = new ArrayList<>();
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.reset(repository.resolve("HEAD^{tree}"));
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                if (treeWalk.getPathString().endsWith(".java") && !treeWalk.getPathString().toLowerCase().contains("test")) {
                    javaFiles.add(treeWalk.getPathString());
                }
            }
        }
        return javaFiles;
    }
    public String getFileContent(String filePath, String commitId) throws IOException {
        ObjectId objId = repository.resolve(commitId + ":" + filePath);
        if (objId == null) return "";
        return new String(repository.open(objId).getBytes(), StandardCharsets.UTF_8);
    }
    public Git getGit() { return git; }
    public Repository getRepository() { return repository; }
}