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
 * JPA entity mapped to the {@code syson_head_elements} table. Represents the current (head) state of a
 * SysML element in a given project branch.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_head_elements")
@IdClass(HeadElementId.class)
public class HeadElement {

    @Id
    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Id
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Id
    @Column(name = "stable_id", nullable = false, length = 512)
    private String stableId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "owner_stable_id", length = 512)
    private String ownerStableId;

    @Column(name = "qualified_name", columnDefinition = "TEXT")
    private String qualifiedName;

    @Column(name = "sysml_type", length = 255)
    private String sysmlType;

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

    public HeadElement() {
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

    public UUID getDocumentId() {
        return this.documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getOwnerStableId() {
        return this.ownerStableId;
    }

    public void setOwnerStableId(String ownerStableId) {
        this.ownerStableId = ownerStableId;
    }

    public String getQualifiedName() {
        return this.qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getSysmlType() {
        return this.sysmlType;
    }

    public void setSysmlType(String sysmlType) {
        this.sysmlType = sysmlType;
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
