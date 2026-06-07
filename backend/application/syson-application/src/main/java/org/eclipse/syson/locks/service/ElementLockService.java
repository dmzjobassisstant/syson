package org.eclipse.syson.locks.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.locks.entity.ElementLock;
import org.eclipse.syson.locks.repository.ElementLockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages element-level locks for fine-grained collaborative editing.
 * Follows the same pattern as BranchLockService but operates on individual
 * model elements.
 *
 * @author Syson
 */
@Service
@Transactional
public class ElementLockService {

    private final ElementLockRepository elementLockRepository;
    private final AuditLogService auditLogService;

    public ElementLockService(ElementLockRepository elementLockRepository, AuditLogService auditLogService) {
        this.elementLockRepository = elementLockRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Acquires a lock on an element. If an expired lock exists, it is stolen.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param stableId
     *            the stable ID of the element
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
     * @return the acquired element lock
     * @throws IllegalStateException
     *             if an active lock exists held by another user
     */
    public ElementLock acquireLock(String projectId, UUID branchId, String stableId, UUID userId,
                                    String sessionId, String deviceId, String reason, int ttlMinutes) {
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(projectId, branchId, stableId, "edit");

        if (existingLock.isPresent()) {
            ElementLock lock = existingLock.get();
            if (lock.getOwnerUserId().equals(userId)) {
                lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
                lock.setOwnerSessionId(sessionId);
                elementLockRepository.save(lock);
                return lock;
            }
            if (lock.getExpiresAt().isAfter(OffsetDateTime.now())) {
                throw new IllegalStateException(
                        String.format("Element %s on branch %s is already locked by user %s (expires: %s)",
                                stableId, branchId, lock.getOwnerUserId(), lock.getExpiresAt()));
            }
            lock.setOwnerUserId(userId);
            lock.setOwnerSessionId(sessionId);
            lock.setReason(reason);
            lock.setAcquiredAt(OffsetDateTime.now());
            lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
            elementLockRepository.save(lock);

            auditLogService.log(userId.toString(), projectId, branchId.toString(),
                    AuditEventType.LOCK_STOLEN, "Stole expired element lock from user " + lock.getOwnerUserId());
            return lock;
        }

        ElementLock lock = new ElementLock();
        lock.setProjectId(projectId);
        lock.setBranchId(branchId);
        lock.setStableId(stableId);
        lock.setLockType("edit");
        lock.setOwnerUserId(userId);
        lock.setOwnerSessionId(sessionId);
        lock.setReason(reason);
        lock.setAcquiredAt(OffsetDateTime.now());
        lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
        elementLockRepository.save(lock);

        auditLogService.log(userId.toString(), projectId, branchId.toString(),
                AuditEventType.LOCK_ACQUIRED, "Acquired element lock on " + stableId + ": " + reason);
        return lock;
    }

    /**
     * Releases an element lock. Only the lock owner can release it.
     */
    public void releaseLock(String projectId, UUID branchId, String stableId, UUID userId) {
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(projectId, branchId, stableId, "edit");
        if (existingLock.isEmpty()) {
            return;
        }
        ElementLock lock = existingLock.get();
        if (!lock.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException(
                    String.format("Lock on element %s is owned by user %s, not user %s",
                            stableId, lock.getOwnerUserId(), userId));
        }
        elementLockRepository.delete(lock);

        auditLogService.log(userId.toString(), projectId, branchId.toString(),
                AuditEventType.LOCK_RELEASED, "Released element lock on " + stableId);
    }

    /**
     * Refreshes an existing element lock with a new TTL.
     */
    public void refreshLock(String projectId, UUID branchId, String stableId, UUID userId, int ttlMinutes) {
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(projectId, branchId, stableId, "edit");
        if (existingLock.isEmpty()) {
            throw new IllegalStateException("No active lock found on element " + stableId);
        }
        ElementLock lock = existingLock.get();
        if (!lock.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("Only the lock owner can refresh the lock");
        }
        lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
        elementLockRepository.save(lock);
    }

    /**
     * Gets the active lock on an element.
     */
    public Optional<ElementLock> getLock(String projectId, UUID branchId, String stableId) {
        return elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(projectId, branchId, stableId, "edit");
    }

    /**
     * Forces release of an element lock (admin override).
     */
    public void forceRelease(String projectId, UUID branchId, String stableId, UUID adminUserId) {
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(projectId, branchId, stableId, "edit");
        if (existingLock.isPresent()) {
            ElementLock lock = existingLock.get();
            elementLockRepository.delete(lock);

            auditLogService.log(adminUserId.toString(), projectId, branchId.toString(),
                    AuditEventType.LOCK_FORCE_RELEASED,
                    "Admin forced release of element lock on " + stableId + " owned by user " + lock.getOwnerUserId());
        }
    }
}
