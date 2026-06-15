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
package org.eclipse.syson.vc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code syson_changes} table.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_changes")
public class ChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "change_id")
    private UUID changeId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "commit_id", nullable = false)
    private UUID commitId;

    @Column(name = "change_seq", nullable = false)
    private int changeSeq;

    @Column(name = "object_type", nullable = false, length = 100)
    private String objectType;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(name = "operation", nullable = false, length = 10)
    private String operation;

    @Column(name = "before_hash", length = 64)
    private String beforeHash;

    @Column(name = "after_hash", length = 64)
    private String afterHash;

    @Column(name = "patch", columnDefinition = "JSONB")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String patch;

    @Column(name = "before_object", columnDefinition = "JSONB")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String beforeObject;

    @Column(name = "after_object", columnDefinition = "JSONB")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String afterObject;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "project_ref")
    private String projectRef;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "stable_object_id")
    private String stableObjectId;

    @Column(name = "changed_fields", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String changedFields;

    @Column(name = "extractor_version")
    private String extractorVersion;

    public ChangeEntity() {
    }

    public UUID getChangeId() {
        return this.changeId;
    }

    public void setChangeId(UUID changeId) {
        this.changeId = changeId;
    }

    public UUID getProjectId() {
        return this.projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getCommitId() {
        return this.commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public int getChangeSeq() {
        return this.changeSeq;
    }

    public void setChangeSeq(int changeSeq) {
        this.changeSeq = changeSeq;
    }

    public String getObjectType() {
        return this.objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public UUID getObjectId() {
        return this.objectId;
    }

    public void setObjectId(UUID objectId) {
        this.objectId = objectId;
    }

    public String getOperation() {
        return this.operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getBeforeHash() {
        return this.beforeHash;
    }

    public void setBeforeHash(String beforeHash) {
        this.beforeHash = beforeHash;
    }

    public String getAfterHash() {
        return this.afterHash;
    }

    public void setAfterHash(String afterHash) {
        this.afterHash = afterHash;
    }

    public String getPatch() {
        return this.patch;
    }

    public void setPatch(String patch) {
        this.patch = patch;
    }

    public String getBeforeObject() {
        return this.beforeObject;
    }

    public void setBeforeObject(String beforeObject) {
        this.beforeObject = beforeObject;
    }

    public String getAfterObject() {
        return this.afterObject;
    }

    public void setAfterObject(String afterObject) {
        this.afterObject = afterObject;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatedBy() {
        return this.createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public String getProjectRef() { return projectRef; }
    public void setProjectRef(String v) { this.projectRef = v; }
    public UUID getBranchId() { return branchId; }
    public void setBranchId(UUID v) { this.branchId = v; }
    public String getStableObjectId() { return stableObjectId; }
    public void setStableObjectId(String v) { this.stableObjectId = v; }
    public String getChangedFields() { return changedFields; }
    public void setChangedFields(String v) { this.changedFields = v; }
    public String getExtractorVersion() { return extractorVersion; }
    public void setExtractorVersion(String v) { this.extractorVersion = v; }
}
