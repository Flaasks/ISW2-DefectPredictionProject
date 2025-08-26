package com.dipalma.whatif.model;

import java.time.LocalDate;


/**
 * Represents a single, sorted release of a project.
 */
public record ProjectRelease(String name, LocalDate releaseDate, int index) implements Comparable<ProjectRelease> {

    @Override
    public int compareTo(ProjectRelease other) {
        return this.releaseDate.compareTo(other.releaseDate);
    }

    @Override
    public String toString() {
        return "ProjectRelease[" +
                "name=" + name + ", " +
                "releaseDate=" + releaseDate + ", " +
                "index=" + index + ']';
    }
}