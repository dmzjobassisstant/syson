package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_head_diagrams")
public class HeadDiagramEntity {
    @EmbeddedId private HeadDiagramId id;
    @Column(name = "representation_id") private String representationId;
    @Column(name = "target_object_id") private String targetObjectId;
    @Column(name = "name") private String name;
    @Column(name = "diagram_kind") private String diagramKind;
    @Column(name = "raw_object", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String rawObject;
    @Column(name = "object_hash") private String objectHash;
    @Column(name = "created_commit_id") private UUID createdCommitId;
    @Column(name = "updated_commit_id") private UUID updatedCommitId;
    @Column(name = "deleted_commit_id") private UUID deletedCommitId;
    @Column(name = "is_deleted") private boolean deleted;
    @Column(name = "updated_at") private Timestamp updatedAt;

    public HeadDiagramEntity() {}
    public HeadDiagramId getId() { return id; }
    public void setId(HeadDiagramId id) { this.id = id; }
    public String getRepresentationId() { return representationId; }
    public void setRepresentationId(String v) { this.representationId = v; }
    public String getTargetObjectId() { return targetObjectId; }
    public void setTargetObjectId(String v) { this.targetObjectId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDiagramKind() { return diagramKind; }
    public void setDiagramKind(String v) { this.diagramKind = v; }
    public String getRawObject() { return rawObject; }
    public void setRawObject(String v) { this.rawObject = v; }
    public String getObjectHash() { return objectHash; }
    public void setObjectHash(String v) { this.objectHash = v; }
    public UUID getCreatedCommitId() { return createdCommitId; }
    public void setCreatedCommitId(UUID v) { this.createdCommitId = v; }
    public UUID getUpdatedCommitId() { return updatedCommitId; }
    public void setUpdatedCommitId(UUID v) { this.updatedCommitId = v; }
    public UUID getDeletedCommitId() { return deletedCommitId; }
    public void setDeletedCommitId(UUID v) { this.deletedCommitId = v; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean v) { this.deleted = v; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp v) { this.updatedAt = v; }

    @Embeddable
    public static class HeadDiagramId implements Serializable {
        @Column(name = "project_id") private String projectId;
        @Column(name = "branch_id") private UUID branchId;
        @Column(name = "stable_id") private String stableId;
        public HeadDiagramId() {}
        public HeadDiagramId(String p, UUID b, String s) { this.projectId = p; this.branchId = b; this.stableId = s; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String v) { this.projectId = v; }
        public UUID getBranchId() { return branchId; }
        public void setBranchId(UUID v) { this.branchId = v; }
        public String getStableId() { return stableId; }
        public void setStableId(String v) { this.stableId = v; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HeadDiagramId c)) return false;
            return Objects.equals(projectId, c.projectId) && Objects.equals(branchId, c.branchId) && Objects.equals(stableId, c.stableId);
        }
        @Override public int hashCode() { return Objects.hash(projectId, branchId, stableId); }
    }
}
