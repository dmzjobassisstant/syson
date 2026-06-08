package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_head_presentation_elements")
public class HeadPresentationElementEntity {
    @EmbeddedId private PresentationId id;
    @Column(name = "diagram_stable_id") private String diagramStableId;
    @Column(name = "semantic_element_id") private String semanticElementId;
    @Column(name = "presentation_type") private String presentationType;
    @Column(name = "parent_presentation_id") private String parentPresentationId;
    @Column(name = "bounds", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String bounds;
    @Column(name = "style", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String style;
    @Column(name = "raw_object", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String rawObject;
    @Column(name = "object_hash") private String objectHash;
    @Column(name = "created_commit_id") private UUID createdCommitId;
    @Column(name = "updated_commit_id") private UUID updatedCommitId;
    @Column(name = "deleted_commit_id") private UUID deletedCommitId;
    @Column(name = "is_deleted") private boolean deleted;
    @Column(name = "updated_at") private Timestamp updatedAt;

    public HeadPresentationElementEntity() {}
    public PresentationId getId() { return id; }
    public void setId(PresentationId id) { this.id = id; }
    public String getDiagramStableId() { return diagramStableId; }
    public void setDiagramStableId(String v) { this.diagramStableId = v; }
    public String getSemanticElementId() { return semanticElementId; }
    public void setSemanticElementId(String v) { this.semanticElementId = v; }
    public String getPresentationType() { return presentationType; }
    public void setPresentationType(String v) { this.presentationType = v; }
    public String getParentPresentationId() { return parentPresentationId; }
    public void setParentPresentationId(String v) { this.parentPresentationId = v; }
    public String getBounds() { return bounds; }
    public void setBounds(String v) { this.bounds = v; }
    public String getStyle() { return style; }
    public void setStyle(String v) { this.style = v; }
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
    public static class PresentationId implements Serializable {
        @Column(name = "project_id") private String projectId;
        @Column(name = "branch_id") private UUID branchId;
        @Column(name = "stable_id") private String stableId;
        public PresentationId() {}
        public PresentationId(String p, UUID b, String s) { this.projectId = p; this.branchId = b; this.stableId = s; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String v) { this.projectId = v; }
        public UUID getBranchId() { return branchId; }
        public void setBranchId(UUID v) { this.branchId = v; }
        public String getStableId() { return stableId; }
        public void setStableId(String v) { this.stableId = v; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PresentationId c)) return false;
            return Objects.equals(projectId, c.projectId) && Objects.equals(branchId, c.branchId) && Objects.equals(stableId, c.stableId);
        }
        @Override public int hashCode() { return Objects.hash(projectId, branchId, stableId); }
    }
}
