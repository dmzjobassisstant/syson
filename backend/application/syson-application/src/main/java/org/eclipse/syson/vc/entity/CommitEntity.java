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
 * JPA entity mapped to the {@code syson_commits} table.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_commits")
public class CommitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "commit_id")
    private UUID commitId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "commit_number", nullable = false)
    private long commitNumber;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(name = "change_count")
    private int changeCount;

    @Column(name = "commit_hash", length = 64)
    private String commitHash;

    @Column(name = "parent_commit_ids", columnDefinition = "JSONB")
    private String parentCommitIds;

    @Column(name = "committed_at")
    private OffsetDateTime committedAt;

    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "status", length = 50)
    private String status;

    public CommitEntity() {
    }

    public UUID getCommitId() {
        return this.commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public UUID getProjectId() {
        return this.projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return this.branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public long getCommitNumber() {
        return this.commitNumber;
    }

    public void setCommitNumber(long commitNumber) {
        this.commitNumber = commitNumber;
    }

    public String getMessage() {
        return this.message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public UUID getAuthorUserId() {
        return this.authorUserId;
    }

    public void setAuthorUserId(UUID authorUserId) {
        this.authorUserId = authorUserId;
    }

    public int getChangeCount() {
        return this.changeCount;
    }

    public void setChangeCount(int changeCount) {
        this.changeCount = changeCount;
    }

    public String getCommitHash() {
        return this.commitHash;
    }

    public void setCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    public String getParentCommitIds() {
        return this.parentCommitIds;
    }

    public void setParentCommitIds(String parentCommitIds) {
        this.parentCommitIds = parentCommitIds;
    }

    public OffsetDateTime getCommittedAt() {
        return this.committedAt;
    }

    public void setCommittedAt(OffsetDateTime committedAt) {
        this.committedAt = committedAt;
    }

    public String getSource() {
        return this.source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
