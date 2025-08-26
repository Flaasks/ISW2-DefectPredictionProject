package com.dipalma.whatif.model;

import java.time.LocalDate;
import java.util.Objects;


/**
 * Represents a single, sorted release of a project.
 */
public final class ProjectRelease implements Comparable<ProjectRelease> {
    private final String name;
    private final LocalDate releaseDate;
    private final int index;


    public ProjectRelease(String name, LocalDate releaseDate, int index) {
        this.name = name;
        this.releaseDate = releaseDate;
        this.index = index;
    }

    public String name() { return name; }
    public LocalDate releaseDate() { return releaseDate; }
    public int index() { return index; }

    @Override
    public int compareTo(ProjectRelease other) {
        return this.releaseDate.compareTo(other.releaseDate);
    }

    // Standard equals, hashCode, and toString for record-like behavior
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ProjectRelease) obj;
        return Objects.equals(this.name, that.name) &&
                Objects.equals(this.releaseDate, that.releaseDate) &&
                this.index == that.index;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, releaseDate, index);
    }

    @Override
    public String toString() {
        return "ProjectRelease[" +
                "name=" + name + ", " +
                "releaseDate=" + releaseDate + ", " +
                "index=" + index + ']';
    }
}