package org.eclipse.syson.locks.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.locks.entity.BranchLock;
import org.eclipse.syson.locks.repository.BranchLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages branch-level locks for collaborative editing. Supports acquiring,
 * releasing, refreshing, and forcing locks with audit logging.
 *
 * @author Syson
 */
@Service
@Transactional
public class BranchLockService {

    private final BranchLockRepository branchLockRepository;
    private final AuditLogService auditLogService;

    public BranchLockService(BranchLockRepository branchLockRepository, AuditLogService auditLogService) {
        this.branchLockRepository = branchLockRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Acquires a lock on a branch. If an expired lock exists, it is stolen.
     * Throws an exception if an active lock is held by another user.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param userId
     *            the user requesting the lock
     * @param sessionId
     *            the session identifier
     * @param deviceId
     *            the device identifier
     * @param reason
     *            the reason for acquiring the lock
     * @param ttlMinutes
     *            time-to-live in minutes
     * @return the acquired branch lock
     * @throws IllegalStateException
     *             if an active lock exists held by another user
     */
    public BranchLock acquireLock(String projectId, UUID branchId, UUID userId,
                                   String sessionId, String deviceId, String reason, int ttlMinutes) {
        Optional<BranchLock> existingLock = branchLockRepository.findByProjectIdAndBranchIdAndLockType(projectId, branchId, "branch");

        if (existingLock.isPresent()) {
            BranchLock lock = existingLock.get();
            if (lock.getOwnerUserId().equals(userId)) {
                // Refresh existing lock for same user
                lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
                lock.setOwnerSessionId(sessionId);
                lock.setOwnerDeviceId(deviceId);
                branchLockRepository.save(lock);
                return lock;
            }
            if (lock.getExpiresAt().isAfter(OffsetDateTime.now())) {
                throw new IllegalStateException(
                        String.format("Branch %s is already locked by user %s (expires: %s)",
                                branchId, lock.getOwnerUserId(), lock.getExpiresAt()));
            }
            // Steal expired lock
            lock.setOwnerUserId(userId);
            lock.setOwnerSessionId(sessionId);
            lock.setOwnerDeviceId(deviceId);
            lock.setReason(reason);
            lock.setAcquiredAt(OffsetDateTime.now());
            lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
            branchLockRepository.save(lock);

            auditLogService.log(userId.toString(), projectId, branchId.toString(),
                    AuditEventType.LOCK_STOLEN, "Stole expired branch lock from user " + lock.getOwnerUserId());
            return lock;
        }

        // Create new lock
        BranchLock lock = new BranchLock();
        lock.setProjectId(projectId);
        lock.setBranchId(branchId);
        lock.setLockType("branch");
        lock.setOwnerUserId(userId);
        lock.setOwnerSessionId(sessionId);
        lock.setOwnerDeviceId(deviceId);
        lock.setReason(reason);
        lock.setAcquiredAt(OffsetDateTime.now());
        lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
        branchLockRepository.save(lock);

        auditLogService.log(userId.toString(), projectId, branchId.toString(),
                AuditEventType.LOCK_ACQUIRED, "Acquired branch lock: " + reason);
        return lock;
    }

    /**
     * Releases a lock on a branch. Only the lock owner can release it.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param lockType
     *            the lock type
     * @param userId
     *            the user releasing the lock
     */
    public void releaseLock(String projectId, UUID branchId, String lockType, UUID userId) {
        Optional<BranchLock> existingLock = branchLockRepository.findByProjectIdAndBranchIdAndLockType(projectId, branchId, lockType);
        if (existingLock.isEmpty()) {
            return;
        }
        BranchLock lock = existingLock.get();
        if (!lock.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException(
                    String.format("Lock on branch %s is owned by user %s, not user %s",
                            branchId, lock.getOwnerUserId(), userId));
        }
        branchLockRepository.delete(lock);

        auditLogService.log(userId.toString(), projectId, branchId.toString(),
                AuditEventType.LOCK_RELEASED, "Released branch lock");
    }

    /**
     * Refreshes an existing lock with a new TTL.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param lockType
     *            the lock type
     * @param userId
     *            the user refreshing the lock
     * @param ttlMinutes
     *            new time-to-live in minutes
     */
    public void refreshLock(String projectId, UUID branchId, String lockType, UUID userId, int ttlMinutes) {
        Optional<BranchLock> existingLock = branchLockRepository.findByProjectIdAndBranchIdAndLockType(projectId, branchId, lockType);
        if (existingLock.isEmpty()) {
            throw new IllegalStateException("No active lock found on branch " + branchId);
        }
        BranchLock lock = existingLock.get();
        if (!lock.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("Only the lock owner can refresh the lock");
        }
        lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
        branchLockRepository.save(lock);
    }

    /**
     * Gets the active lock on a branch.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param lockType
     *            the lock type
     * @return an optional containing the lock if found
     */
    public Optional<BranchLock> getLock(String projectId, UUID branchId, String lockType) {
        return branchLockRepository.findByProjectIdAndBranchIdAndLockType(projectId, branchId, lockType);
    }

    /**
     * Forces release of a lock (admin override).
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param lockType
     *            the lock type
     * @param adminUserId
     *            the admin user performing the force release
     */
    public void forceRelease(String projectId, UUID branchId, String lockType, UUID adminUserId) {
        Optional<BranchLock> existingLock = branchLockRepository.findByProjectIdAndBranchIdAndLockType(projectId, branchId, lockType);
        if (existingLock.isPresent()) {
            BranchLock lock = existingLock.get();
            branchLockRepository.delete(lock);

            auditLogService.log(adminUserId.toString(), projectId, branchId.toString(),
                    AuditEventType.LOCK_FORCE_RELEASED,
                    "Admin forced release of branch lock owned by user " + lock.getOwnerUserId());
        }
    }
}
