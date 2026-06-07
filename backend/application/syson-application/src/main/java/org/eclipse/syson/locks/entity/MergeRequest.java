package org.eclipse.syson.locks.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_merge_requests")
public class MergeRequest {

    @Id
    @Column(name = "merge_request_id")
    private UUID mergeRequestId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "source_branch_id")
    private UUID sourceBranchId;

    @Column(name = "target_branch_id")
    private UUID targetBranchId;

    @Column(name = "base_commit_id")
    private UUID baseCommitId;

    @Column(name = "source_commit_id")
    private UUID sourceCommitId;

    @Column(name = "target_commit_id")
    private UUID targetCommitId;

    @Column(name = "status")
    private String status;

    @Column(name = "title")
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public MergeRequest() {
    }

    public UUID getMergeRequestId() {
        return mergeRequestId;
    }

    public void setMergeRequestId(UUID mergeRequestId) {
        this.mergeRequestId = mergeRequestId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getSourceBranchId() {
        return sourceBranchId;
    }

    public void setSourceBranchId(UUID sourceBranchId) {
        this.sourceBranchId = sourceBranchId;
    }

    public UUID getTargetBranchId() {
        return targetBranchId;
    }

    public void setTargetBranchId(UUID targetBranchId) {
        this.targetBranchId = targetBranchId;
    }

    public UUID getBaseCommitId() {
        return baseCommitId;
    }

    public void setBaseCommitId(UUID baseCommitId) {
        this.baseCommitId = baseCommitId;
    }

    public UUID getSourceCommitId() {
        return sourceCommitId;
    }

    public void setSourceCommitId(UUID sourceCommitId) {
        this.sourceCommitId = sourceCommitId;
    }

    public UUID getTargetCommitId() {
        return targetCommitId;
    }

    public void setTargetCommitId(UUID targetCommitId) {
        this.targetCommitId = targetCommitId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
