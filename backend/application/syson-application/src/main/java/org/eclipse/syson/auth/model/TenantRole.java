package org.eclipse.syson.auth.model;

public enum TenantRole {
    VIEWER(1), EDITOR(2), ADMIN(3), SUPERUSER(4);

    private final int rank;

    TenantRole(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return this.rank;
    }

    public static TenantRole from(String value) {
        return TenantRole.valueOf(value.trim().toUpperCase());
    }

    public String dbValue() {
        return this.name().toLowerCase();
    }
}
