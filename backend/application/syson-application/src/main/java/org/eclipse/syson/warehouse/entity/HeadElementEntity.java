package org.eclipse.syson.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Materialized current-state element per project/branch.
 * PK: (projectId, branchId, stableId).
 */
@Entity
@Table(name = "syson_head_elements")
public class HeadElementEntity {

    @EmbeddedId
    private HeadElementId id;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "owner_stable_id")
    private String ownerStableId;

    @Column(name = "qualified_name")
    private String qualifiedName;

    @Column(name = "sysml_type")
    private String sysmlType;

    @Column(name = "name")
    private String name;

    @Column(name = "body")
    private String body;

    @Column(name = "attributes", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String attributes;

    @Column(name = "raw_object", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String rawObject;

    @Column(name = "object_hash")
    private String objectHash;

    @Column(name = "created_commit_id")
    private UUID createdCommitId;

    @Column(name = "updated_commit_id")
    private UUID updatedCommitId;

    @Column(name = "deleted_commit_id")
    private UUID deletedCommitId;

    @Column(name = "is_deleted")
    private boolean deleted;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    public HeadElementEntity() {}

    public HeadElementId getId() { return id; }
    public void setId(HeadElementId id) { this.id = id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public String getOwnerStableId() { return ownerStableId; }
    public void setOwnerStableId(String ownerStableId) { this.ownerStableId = ownerStableId; }
    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }
    public String getSysmlType() { return sysmlType; }
    public void setSysmlType(String sysmlType) { this.sysmlType = sysmlType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
    public String getRawObject() { return rawObject; }
    public void setRawObject(String rawObject) { this.rawObject = rawObject; }
    public String getObjectHash() { return objectHash; }
    public void setObjectHash(String objectHash) { this.objectHash = objectHash; }
    public UUID getCreatedCommitId() { return createdCommitId; }
    public void setCreatedCommitId(UUID createdCommitId) { this.createdCommitId = createdCommitId; }
    public UUID getUpdatedCommitId() { return updatedCommitId; }
    public void setUpdatedCommitId(UUID updatedCommitId) { this.updatedCommitId = updatedCommitId; }
    public UUID getDeletedCommitId() { return deletedCommitId; }
    public void setDeletedCommitId(UUID deletedCommitId) { this.deletedCommitId = deletedCommitId; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
