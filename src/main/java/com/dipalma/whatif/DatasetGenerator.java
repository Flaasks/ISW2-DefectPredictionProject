package com.dipalma.whatif;

import com.dipalma.whatif.analysis.MethodTracker;
import com.dipalma.whatif.connectors.GitConnector;
import com.dipalma.whatif.connectors.JiraConnector;
import com.dipalma.whatif.model.JiraTicket;
import com.dipalma.whatif.model.ProjectRelease;
import com.dipalma.whatif.model.TrackedMethod;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class DatasetGenerator {

    private final String projectKey;
    private final GitConnector git;

    public DatasetGenerator(String projectKey, String gitUrl) {
        this.projectKey = projectKey;
        this.git = new GitConnector(gitUrl, projectKey);
    }

    public void generateCsv() {
        try {
            JiraConnector jira = new JiraConnector(projectKey);
            List<ProjectRelease> releases = jira.getProjectReleases();
            List<JiraTicket> tickets = jira.getBugTickets();

            git.cloneOrOpenRepo();
            git.findAndSetFixCommits(tickets);

            setVersionIndices(tickets, releases);

            double pMedian = calculateProportionCoefficient(tickets);
            System.out.println("Calculated P-coefficient for " + projectKey + ": " + pMedian);

            Map<String, List<String>> bugToMethodsMap = git.getBugToMethodsMap(tickets);

            Map<String, RevCommit> releaseCommits = git.getReleaseCommits(releases);
            MethodTracker tracker = new MethodTracker(git);

            int releaseCutoff = (int) (releases.size() * 0.34);
            List<ProjectRelease> releasesToAnalyze = releases.subList(0, releaseCutoff);

            // *** CORRECTED CSV HEADERS ***
            // Using the single "MethodName" column and replacing "NSmells" with "Duplication"
            String[] headers = {"Project", "MethodName", "Release", "LOC", "CyclomaticComplexity", "ParameterCount", "Duplication", "NR", "NAuth", "stmtAdded", "stmtDeleted", "maxChurn", "avgChurn", "IsBuggy"};
            List<String[]> csvData = new ArrayList<>();
            csvData.add(headers);

            for (ProjectRelease release : releasesToAnalyze) {
                if (!releaseCommits.containsKey(release.name())) {
                    System.out.println("Skipping release " + release.name() + " as no matching Git tag was found.");
                    continue;
                }

                System.out.println("Analyzing release: " + release.name());
                RevCommit releaseCommit = releaseCommits.get(release.name());

                // This call now returns methods with all features already calculated.
                List<TrackedMethod> methods = tracker.getMethodsForRelease(releaseCommit);

                for (TrackedMethod method : methods) {
                    // *** FIX: The redundant feature calculation line has been removed. ***

                    boolean isBuggy = isMethodBuggy(method, release, tickets, pMedian, bugToMethodsMap);
                    Map<String, Number> features = method.getFeatures();

                    // Construct the specified identifier (e.g., /path/to/file.java/methodName(params))
                    String methodName = method.filepath() + "/" + method.signature();

                    // *** CORRECTED DATA ROW ***
                    // This now matches the corrected headers perfectly.
                    csvData.add(new String[]{
                            projectKey,
                            methodName,
                            release.name(),
                            features.getOrDefault("LOC", 0).toString(),
                            features.getOrDefault("CyclomaticComplexity", 0).toString(),
                            features.getOrDefault("ParameterCount", 0).toString(),
                            features.getOrDefault("Duplication", 0).toString(), // Added new feature
                            features.getOrDefault("NR", 0).toString(),
                            features.getOrDefault("NAuth", 0).toString(),
                            features.getOrDefault("stmtAdded", 0).toString(),
                            features.getOrDefault("stmtDeleted", 0).toString(),
                            features.getOrDefault("maxChurn", 0).toString(),
                            features.getOrDefault("avgChurn", 0).toString(),
                            isBuggy ? "yes" : "no"
                    });
                }
            }
            writeToCsv(projectKey + ".csv", csvData);
        } catch (IOException | GitAPIException e) {
            e.printStackTrace();
        }
    }

    // ... (isMethodBuggy, setVersionIndices, and other helper methods remain the same)
    private boolean isMethodBuggy(TrackedMethod method, ProjectRelease currentRelease, List<JiraTicket> allTickets, double pMedian, Map<String, List<String>> bugToMethodsMap) {
        String methodKey = method.filepath() + "::" + method.signature();
        for (JiraTicket ticket : allTickets) {
            List<String> fixedMethods = bugToMethodsMap.get(ticket.getKey());
            if (fixedMethods == null || !fixedMethods.contains(methodKey)) {
                continue;
            }
            int iv = ticket.getIntroductionVersionIndex();
            int fv = ticket.getFixedVersionIndex();
            if (iv <= 0 && fv > 0 && ticket.getOpeningVersionIndex() > 0 && fv > ticket.getOpeningVersionIndex()) {
                iv = (int) Math.round(fv - (fv - ticket.getOpeningVersionIndex()) * pMedian);
                if (iv < 1) iv = 1;
            }
            if (iv > 0 && fv > 0 && currentRelease.index() >= iv && currentRelease.index() < fv) {
                return true;
            }
        }
        return false;
    }
    private void setVersionIndices(List<JiraTicket> tickets, List<ProjectRelease> releases) {
        Map<String, Integer> releaseNameIndexMap = releases.stream().collect(Collectors.toMap(ProjectRelease::name, ProjectRelease::index));
        for (JiraTicket ticket : tickets) {
            ticket.setOpeningVersionIndex(findReleaseIndexForDate(ticket.getCreationDate().toLocalDate(), releases));
            if (ticket.getResolutionDate() != null) {
                ticket.setFixedVersionIndex(findReleaseIndexForDate(ticket.getResolutionDate().toLocalDate(), releases));
            }
            ticket.getAffectedVersions().stream()
                    .map(releaseNameIndexMap::get)
                    .filter(Objects::nonNull)
                    .min(Integer::compareTo)
                    .ifPresent(ticket::setIntroductionVersionIndex);
        }
    }
    private int findReleaseIndexForDate(LocalDate date, List<ProjectRelease> releases) {
        for (ProjectRelease release : releases) {
            if (!date.isAfter(release.releaseDate())) {
                return release.index();
            }
        }
        return releases.isEmpty() ? -1 : releases.get(releases.size() - 1).index();
    }
    private double calculateProportionCoefficient(List<JiraTicket> tickets) {
        List<Double> pValues = new ArrayList<>();
        for (JiraTicket ticket : tickets) {
            if (ticket.getIntroductionVersionIndex() > 0 && ticket.getFixedVersionIndex() > 0 && ticket.getOpeningVersionIndex() > 0) {
                if (ticket.getFixedVersionIndex() > ticket.getOpeningVersionIndex()) {
                    double p = (double) (ticket.getFixedVersionIndex() - ticket.getIntroductionVersionIndex()) / (ticket.getFixedVersionIndex() - ticket.getOpeningVersionIndex());
                    pValues.add(p);
                }
            }
        }
        if (pValues.isEmpty()) return 1.5;
        pValues.sort(Comparator.naturalOrder());
        if (pValues.size() % 2 == 1) {
            return pValues.get(pValues.size() / 2);
        } else {
            return (pValues.get(pValues.size() / 2 - 1) + pValues.get(pValues.size() / 2)) / 2.0;
        }
    }
    private void writeToCsv(String fileName, List<String[]> data) throws IOException {
        try (FileWriter out = new FileWriter(fileName);
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecords(data);
        }
        System.out.println("Successfully created " + fileName);
    }
}