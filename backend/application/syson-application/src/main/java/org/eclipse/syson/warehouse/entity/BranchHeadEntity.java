package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_branch_heads")
public class BranchHeadEntity {
    @EmbeddedId private BranchHeadId id;
    @Column(name = "head_commit_id") private UUID headCommitId;
    @Column(name = "canonical_hash") private String canonicalHash;
    @Column(name = "canonical_json", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String canonicalJson;
    @Column(name = "object_count") private int objectCount;
    @Column(name = "relationship_count") private int relationshipCount;
    @Column(name = "diagram_count") private int diagramCount;
    @Column(name = "last_extracted_at") private Timestamp lastExtractedAt;
    @Column(name = "extraction_version") private String extractionVersion;

    public BranchHeadEntity() {}
    public BranchHeadId getId() { return id; }
    public void setId(BranchHeadId id) { this.id = id; }
    public UUID getHeadCommitId() { return headCommitId; }
    public void setHeadCommitId(UUID v) { this.headCommitId = v; }
    public String getCanonicalHash() { return canonicalHash; }
    public void setCanonicalHash(String v) { this.canonicalHash = v; }
    public String getCanonicalJson() { return canonicalJson; }
    public void setCanonicalJson(String v) { this.canonicalJson = v; }
    public int getObjectCount() { return objectCount; }
    public void setObjectCount(int v) { this.objectCount = v; }
    public int getRelationshipCount() { return relationshipCount; }
    public void setRelationshipCount(int v) { this.relationshipCount = v; }
    public int getDiagramCount() { return diagramCount; }
    public void setDiagramCount(int v) { this.diagramCount = v; }
    public Timestamp getLastExtractedAt() { return lastExtractedAt; }
    public void setLastExtractedAt(Timestamp v) { this.lastExtractedAt = v; }
    public String getExtractionVersion() { return extractionVersion; }
    public void setExtractionVersion(String v) { this.extractionVersion = v; }

    @Embeddable
    public static class BranchHeadId implements Serializable {
        @Column(name = "project_id") private String projectId;
        @Column(name = "branch_id") private UUID branchId;
        public BranchHeadId() {}
        public BranchHeadId(String p, UUID b) { this.projectId = p; this.branchId = b; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String v) { this.projectId = v; }
        public UUID getBranchId() { return branchId; }
        public void setBranchId(UUID v) { this.branchId = v; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BranchHeadId c)) return false;
            return Objects.equals(projectId, c.projectId) && Objects.equals(branchId, c.branchId);
        }
        @Override public int hashCode() { return Objects.hash(projectId, branchId); }
    }
}
