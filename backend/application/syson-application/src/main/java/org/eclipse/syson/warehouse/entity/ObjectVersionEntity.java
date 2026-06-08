package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "syson_object_versions")
public class ObjectVersionEntity {
    @EmbeddedId private ObjectVersionId id;
    @Column(name = "valid_from_commit_number") private long validFromCommitNumber;
    @Column(name = "valid_to_commit_number") private Long validToCommitNumber;
    @Column(name = "is_current") private boolean current;
    @Column(name = "object_hash") private String objectHash;
    @Column(name = "object_json", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String objectJson;
    @Column(name = "created_at") private Timestamp createdAt;

    public ObjectVersionEntity() {}
    public ObjectVersionId getId() { return id; }
    public void setId(ObjectVersionId id) { this.id = id; }
    public long getValidFromCommitNumber() { return validFromCommitNumber; }
    public void setValidFromCommitNumber(long v) { this.validFromCommitNumber = v; }
    public Long getValidToCommitNumber() { return validToCommitNumber; }
    public void setValidToCommitNumber(Long v) { this.validToCommitNumber = v; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean v) { this.current = v; }
    public String getObjectHash() { return objectHash; }
    public void setObjectHash(String v) { this.objectHash = v; }
    public String getObjectJson() { return objectJson; }
    public void setObjectJson(String v) { this.objectJson = v; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp v) { this.createdAt = v; }

    @Embeddable
    public static class ObjectVersionId implements Serializable {
        @Column(name = "project_id") private String projectId;
        @Column(name = "object_type") private String objectType;
        @Column(name = "stable_object_id") private String stableObjectId;
        @Column(name = "commit_id") private UUID commitId;
        public ObjectVersionId() {}
        public ObjectVersionId(String p, String t, String s, UUID c) {
            this.projectId = p; this.objectType = t; this.stableObjectId = s; this.commitId = c;
        }
        public String getProjectId() { return projectId; }
        public void setProjectId(String v) { this.projectId = v; }
        public String getObjectType() { return objectType; }
        public void setObjectType(String v) { this.objectType = v; }
        public String getStableObjectId() { return stableObjectId; }
        public void setStableObjectId(String v) { this.stableObjectId = v; }
        public UUID getCommitId() { return commitId; }
        public void setCommitId(UUID v) { this.commitId = v; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ObjectVersionId c)) return false;
            return Objects.equals(projectId, c.projectId) && Objects.equals(objectType, c.objectType)
                && Objects.equals(stableObjectId, c.stableObjectId) && Objects.equals(commitId, c.commitId);
        }
        @Override public int hashCode() { return Objects.hash(projectId, objectType, stableObjectId, commitId); }
    }
}
