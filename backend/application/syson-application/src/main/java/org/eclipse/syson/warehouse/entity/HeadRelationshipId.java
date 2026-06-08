package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class HeadRelationshipId implements Serializable {
    @Column(name = "project_id") private String projectId;
    @Column(name = "branch_id") private UUID branchId;
    @Column(name = "stable_id") private String stableId;

    public HeadRelationshipId() {}
    public HeadRelationshipId(String projectId, UUID branchId, String stableId) {
        this.projectId = projectId; this.branchId = branchId; this.stableId = stableId;
    }
    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { this.projectId = v; }
    public UUID getBranchId() { return branchId; }
    public void setBranchId(UUID v) { this.branchId = v; }
    public String getStableId() { return stableId; }
    public void setStableId(String v) { this.stableId = v; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeadRelationshipId c)) return false;
        return Objects.equals(projectId, c.projectId) && Objects.equals(branchId, c.branchId) && Objects.equals(stableId, c.stableId);
    }
    @Override public int hashCode() { return Objects.hash(projectId, branchId, stableId); }
}
