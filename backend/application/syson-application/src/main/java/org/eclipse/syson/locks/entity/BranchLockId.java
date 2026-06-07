package org.eclipse.syson.locks.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class BranchLockId implements Serializable {

    private String projectId;
    private UUID branchId;
    private String lockType;

    public BranchLockId() {
    }

    public BranchLockId(String projectId, UUID branchId, String lockType) {
        this.projectId = projectId;
        this.branchId = branchId;
        this.lockType = lockType;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public String getLockType() {
        return lockType;
    }

    public void setLockType(String lockType) {
        this.lockType = lockType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BranchLockId that = (BranchLockId) o;
        return Objects.equals(projectId, that.projectId)
                && Objects.equals(branchId, that.branchId)
                && Objects.equals(lockType, that.lockType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, branchId, lockType);
    }
}
