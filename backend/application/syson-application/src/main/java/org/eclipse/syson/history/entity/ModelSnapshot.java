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
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapped to the {@code syson_model_snapshots} table. An immutable point-in-time snapshot of a
 * branch model at a given commit, used for diffing, rollback, and archival.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_model_snapshots")
public class ModelSnapshot {

    @Id
    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "commit_id")
    private UUID commitId;

    @Column(name = "snapshot_kind", length = 50)
    private String snapshotKind;

    @Column(name = "canonical_hash", length = 128)
    private String canonicalHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_json", columnDefinition = "jsonb")
    private String canonicalJson;

    @Column(name = "object_count")
    private Integer objectCount;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public ModelSnapshot() {
    }

    public UUID getSnapshotId() {
        return this.snapshotId;
    }

    public void setSnapshotId(UUID snapshotId) {
        this.snapshotId = snapshotId;
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

    public UUID getCommitId() {
        return this.commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public String getSnapshotKind() {
        return this.snapshotKind;
    }

    public void setSnapshotKind(String snapshotKind) {
        this.snapshotKind = snapshotKind;
    }

    public String getCanonicalHash() {
        return this.canonicalHash;
    }

    public void setCanonicalHash(String canonicalHash) {
        this.canonicalHash = canonicalHash;
    }

    public String getCanonicalJson() {
        return this.canonicalJson;
    }

    public void setCanonicalJson(String canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public Integer getObjectCount() {
        return this.objectCount;
    }

    public void setObjectCount(Integer objectCount) {
        this.objectCount = objectCount;
    }

    public Long getSizeBytes() {
        return this.sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
