package org.eclipse.syson.locks.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_element_locks")
@IdClass(ElementLockId.class)
public class ElementLock {

    @Id
    @Column(name = "project_id")
    private String projectId;

    @Id
    @Column(name = "branch_id")
    private UUID branchId;

    @Id
    @Column(name = "stable_id")
    private String stableId;

    @Id
    @Column(name = "lock_type")
    private String lockType = "edit";

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owner_username")
    private String ownerUsername;

    @Column(name = "owner_session_id")
    private String ownerSessionId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "acquired_at")
    private OffsetDateTime acquiredAt;

    @Column(name = "refreshed_at")
    private OffsetDateTime refreshedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public ElementLock() {
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

    public String getStableId() {
        return stableId;
    }

    public void setStableId(String stableId) {
        this.stableId = stableId;
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

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public String getOwnerSessionId() {
        return ownerSessionId;
    }

    public void setOwnerSessionId(String ownerSessionId) {
        this.ownerSessionId = ownerSessionId;
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
