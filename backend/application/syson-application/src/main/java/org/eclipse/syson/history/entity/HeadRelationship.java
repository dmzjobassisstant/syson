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

    @Column(name = "rel_type", length = 255)
    private String relType;

    @Column(name = "source_stable_id", length = 512)
    private String sourceStableId;

    @Column(name = "target_stable_id", length = 512)
    private String targetStableId;

    @Column(name = "source_role", length = 255)
    private String sourceRole;

    @Column(name = "target_role", length = 255)
    private String targetRole;

    @Column(name = "owner_stable_id", length = 512)
    private String ownerStableId;

    @Column(name = "name")
    private String name;

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

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

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

    public String getRelType() {
        return this.relType;
    }

    public void setRelType(String relType) {
        this.relType = relType;
    }

    public String getSourceStableId() {
        return this.sourceStableId;
    }

    public void setSourceStableId(String sourceStableId) {
        this.sourceStableId = sourceStableId;
    }

    public String getTargetStableId() {
        return this.targetStableId;
    }

    public void setTargetStableId(String targetStableId) {
        this.targetStableId = targetStableId;
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

    public String getOwnerStableId() {
        return this.ownerStableId;
    }

    public void setOwnerStableId(String ownerStableId) {
        this.ownerStableId = ownerStableId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
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
        return this.isDeleted;
    }

    public void setDeleted(boolean isDeleted) {
        this.isDeleted = isDeleted;
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
