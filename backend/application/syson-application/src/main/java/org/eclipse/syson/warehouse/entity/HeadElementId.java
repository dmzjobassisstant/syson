package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class HeadElementId implements Serializable {

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "stable_id")
    private String stableId;

    public HeadElementId() {}

    public HeadElementId(String projectId, UUID branchId, String stableId) {
        this.projectId = projectId;
        this.branchId = branchId;
        this.stableId = stableId;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public UUID getBranchId() { return branchId; }
    public void setBranchId(UUID branchId) { this.branchId = branchId; }
    public String getStableId() { return stableId; }
    public void setStableId(String stableId) { this.stableId = stableId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeadElementId other)) return false;
        return Objects.equals(projectId, other.projectId)
            && Objects.equals(branchId, other.branchId)
            && Objects.equals(stableId, other.stableId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, branchId, stableId);
    }
}
