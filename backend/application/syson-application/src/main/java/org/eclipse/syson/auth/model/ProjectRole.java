package org.eclipse.syson.auth.model;

public enum ProjectRole {
    VIEWER(1), USER(2), ADMIN(3);

    private final int rank;

    ProjectRole(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return this.rank;
    }

    public static ProjectRole from(String value) {
        return ProjectRole.valueOf(value.trim().toUpperCase());
    }

    public String dbValue() {
        return this.name().toLowerCase();
    }
}
