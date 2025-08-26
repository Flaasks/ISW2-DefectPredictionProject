package com.dipalma.whatif.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Represents a bug ticket fetched from JIRA.
 */
public final class JiraTicket {
    private final String key;
    private final LocalDateTime creationDate;
    private final List<String> affectedVersions;
    private String fixCommitHash;
    private LocalDateTime resolutionDate;
    private int introductionVersionIndex = -1;
    private int openingVersionIndex = -1;
    private int fixedVersionIndex = -1;

    public JiraTicket(String key, LocalDateTime creationDate, List<String> affectedVersions) {
        this.key = key;
        this.creationDate = creationDate;
        this.affectedVersions = affectedVersions;
    }

    // Getters
    public String getKey() { return key; }
    public LocalDateTime getCreationDate() { return creationDate; }
    public LocalDateTime getResolutionDate() { return resolutionDate; }
    public String getFixCommitHash() { return fixCommitHash; }
    public List<String> getAffectedVersions() { return affectedVersions; }
    public int getIntroductionVersionIndex() { return introductionVersionIndex; }
    public int getOpeningVersionIndex() { return openingVersionIndex; }
    public int getFixedVersionIndex() { return fixedVersionIndex; }

    // Setters
    public void setResolutionDate(LocalDateTime resolutionDate) { this.resolutionDate = resolutionDate; }
    public void setFixCommitHash(String fixCommitHash) { this.fixCommitHash = fixCommitHash; }
    public void setIntroductionVersionIndex(int iv) { this.introductionVersionIndex = iv; }
    public void setOpeningVersionIndex(int ov) { this.openingVersionIndex = ov; }
    public void setFixedVersionIndex(int fv) { this.fixedVersionIndex = fv; }

    // Standard equals, hashCode, toString
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JiraTicket that = (JiraTicket) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}