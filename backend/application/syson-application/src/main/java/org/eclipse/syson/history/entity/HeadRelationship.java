/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.history.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapped to the {@code syson_head_relationships} table. Represents the current (head) state of a
 * SysML relationship in a given project branch.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_head_relationships")
@IdClass(HeadRelationshipId.class)
public class HeadRelationship {

    @Id
    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Id
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Id
    @Column(name = "stable_id", nullable = false, length = 512)
    private String stableId;

    @Column(name = "relationship_id", length = 512)
    private String relationshipId;

    @Column(name = "rel_type", length = 255)
    private String relType;

    @Column(name = "source_id", length = 512)
    private String sourceId;

    @Column(name = "target_id", length = 512)
    private String targetId;

    @Column(name = "source_role", length = 255)
    private String sourceRole;

    @Column(name = "target_role", length = 255)
    private String targetRole;

    @Column(name = "owner_id", length = 512)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private String attributes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_object", columnDefinition = "jsonb")
    private String rawObject;

    @Column(name = "object_hash", length = 128)
    private String objectHash;

    @Column(name = "created_commit_id")
    private UUID createdCommitId;

    @Column(name = "updated_commit_id")
    private UUID updatedCommitId;

    @Column(name = "deleted_commit_id")
    private UUID deletedCommitId;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public HeadRelationship() {
    }

    public String getProjectId() {
        return this.projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return this.branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public String getStableId() {
        return this.stableId;
    }

    public void setStableId(String stableId) {
        this.stableId = stableId;
    }

    public String getRelationshipId() {
        return this.relationshipId;
    }

    public void setRelationshipId(String relationshipId) {
        this.relationshipId = relationshipId;
    }

    public String getRelType() {
        return this.relType;
    }

    public void setRelType(String relType) {
        this.relType = relType;
    }

    public String getSourceId() {
        return this.sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getTargetId() {
        return this.targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getSourceRole() {
        return this.sourceRole;
    }

    public void setSourceRole(String sourceRole) {
        this.sourceRole = sourceRole;
    }

    public String getTargetRole() {
        return this.targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public String getOwnerId() {
        return this.ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getAttributes() {
        return this.attributes;
    }

    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    public String getRawObject() {
        return this.rawObject;
    }

    public void setRawObject(String rawObject) {
        this.rawObject = rawObject;
    }

    public String getObjectHash() {
        return this.objectHash;
    }

    public void setObjectHash(String objectHash) {
        this.objectHash = objectHash;
    }

    public UUID getCreatedCommitId() {
        return this.createdCommitId;
    }

    public void setCreatedCommitId(UUID createdCommitId) {
        this.createdCommitId = createdCommitId;
    }

    public UUID getUpdatedCommitId() {
        return this.updatedCommitId;
    }

    public void setUpdatedCommitId(UUID updatedCommitId) {
        this.updatedCommitId = updatedCommitId;
    }

    public UUID getDeletedCommitId() {
        return this.deletedCommitId;
    }

    public void setDeletedCommitId(UUID deletedCommitId) {
        this.deletedCommitId = deletedCommitId;
    }

    public boolean isDeleted() {
        return this.deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
