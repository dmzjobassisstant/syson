package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.*;
import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "syson_head_relationships")
public class HeadRelationshipEntity {
    @EmbeddedId private HeadRelationshipId id;
    @Column(name = "rel_type") private String relType;
    @Column(name = "source_stable_id") private String sourceStableId;
    @Column(name = "target_stable_id") private String targetStableId;
    @Column(name = "source_role") private String sourceRole;
    @Column(name = "target_role") private String targetRole;
    @Column(name = "owner_stable_id") private String ownerStableId;
    @Column(name = "name") private String name;
    @Column(name = "attributes", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String attributes;
    @Column(name = "raw_object", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String rawObject;
    @Column(name = "object_hash") private String objectHash;
    @Column(name = "created_commit_id") private UUID createdCommitId;
    @Column(name = "updated_commit_id") private UUID updatedCommitId;
    @Column(name = "deleted_commit_id") private UUID deletedCommitId;
    @Column(name = "is_deleted") private boolean deleted;
    @Column(name = "created_at") private Timestamp createdAt;
    @Column(name = "updated_at") private Timestamp updatedAt;

    public HeadRelationshipEntity() {}
    public HeadRelationshipId getId() { return id; }
    public void setId(HeadRelationshipId id) { this.id = id; }
    public String getRelType() { return relType; }
    public void setRelType(String v) { this.relType = v; }
    public String getSourceStableId() { return sourceStableId; }
    public void setSourceStableId(String v) { this.sourceStableId = v; }
    public String getTargetStableId() { return targetStableId; }
    public void setTargetStableId(String v) { this.targetStableId = v; }
    public String getSourceRole() { return sourceRole; }
    public void setSourceRole(String v) { this.sourceRole = v; }
    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String v) { this.targetRole = v; }
    public String getOwnerStableId() { return ownerStableId; }
    public void setOwnerStableId(String v) { this.ownerStableId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String v) { this.attributes = v; }
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
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp v) { this.createdAt = v; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp v) { this.updatedAt = v; }
}
