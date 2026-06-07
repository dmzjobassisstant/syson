package org.eclipse.syson.locks.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_branch_locks")
@IdClass(BranchLockId.class)
public class BranchLock {

    @Id
    @Column(name = "project_id")
    private String projectId;

    @Id
    @Column(name = "branch_id")
    private UUID branchId;

    @Id
    @Column(name = "lock_type")
    private String lockType = "write";

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owner_session_id")
    private String ownerSessionId;

    @Column(name = "owner_device_id")
    private String ownerDeviceId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "acquired_at")
    private OffsetDateTime acquiredAt;

    @Column(name = "refreshed_at")
    private OffsetDateTime refreshedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public BranchLock() {
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public String getLockType() {
        return lockType;
    }

    public void setLockType(String lockType) {
        this.lockType = lockType;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getOwnerSessionId() {
        return ownerSessionId;
    }

    public void setOwnerSessionId(String ownerSessionId) {
        this.ownerSessionId = ownerSessionId;
    }

    public String getOwnerDeviceId() {
        return ownerDeviceId;
    }

    public void setOwnerDeviceId(String ownerDeviceId) {
        this.ownerDeviceId = ownerDeviceId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(OffsetDateTime acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public OffsetDateTime getRefreshedAt() {
        return refreshedAt;
    }

    public void setRefreshedAt(OffsetDateTime refreshedAt) {
        this.refreshedAt = refreshedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
