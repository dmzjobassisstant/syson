package org.eclipse.syson.locks.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.history.repository.HeadElementRepository;
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
    private final HeadElementRepository headElementRepository;
    private final AuditLogService auditLogService;

    public ElementLockService(ElementLockRepository elementLockRepository,
                               HeadElementRepository headElementRepository,
                               AuditLogService auditLogService) {
        this.elementLockRepository = elementLockRepository;
        this.headElementRepository = headElementRepository;
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
                                    String username, String sessionId, String deviceId, String reason, int ttlMinutes) {
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate(projectId, branchId, stableId, "edit");

        if (existingLock.isPresent()) {
            ElementLock lock = existingLock.get();
            if (lock.getOwnerUserId().equals(userId)) {
                lock.setExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
                lock.setOwnerSessionId(sessionId);
                lock.setOwnerUsername(username);
                elementLockRepository.save(lock);
                return lock;
            }
            if (lock.getExpiresAt().isAfter(OffsetDateTime.now())) {
                throw new IllegalStateException(
                        String.format("Element %s on branch %s is already locked by %s (expires: %s)",
                                stableId, branchId, lock.getOwnerUsername() != null ? lock.getOwnerUsername() : lock.getOwnerUserId(), lock.getExpiresAt()));
            }
            lock.setOwnerUserId(userId);
            lock.setOwnerUsername(username);
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
        lock.setOwnerUsername(username);
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
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate(projectId, branchId, stableId, "edit");
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
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate(projectId, branchId, stableId, "edit");
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
        Optional<ElementLock> existingLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate(projectId, branchId, stableId, "edit");
        if (existingLock.isPresent()) {
            ElementLock lock = existingLock.get();
            elementLockRepository.delete(lock);

            auditLogService.log(adminUserId.toString(), projectId, branchId.toString(),
                    AuditEventType.LOCK_FORCE_RELEASED,
                    "Admin forced release of element lock on " + stableId + " owned by user " + lock.getOwnerUserId());
        }
    }

    /**
     * Returns all active (non-expired) locks for a project.
     */
    public List<ElementLock> getActiveLocks(String projectId) {
        return elementLockRepository.findByProjectIdAndExpiresAtAfter(projectId, OffsetDateTime.now());
    }

    /**
     * Releases all locks held by a user on elements in a specific semantic data context.
     * Called automatically when the user saves to unlock edited elements.
     */
    public int releaseLocksForSave(String projectId, UUID branchId, UUID userId) {
        List<ElementLock> userLocks = elementLockRepository.findByOwnerUserIdAndExpiresAtAfter(userId, OffsetDateTime.now());
        int released = 0;
        for (ElementLock lock : userLocks) {
            if (lock.getProjectId().equals(projectId)
                    && (branchId == null || lock.getBranchId().equals(branchId))) {
                elementLockRepository.delete(lock);
                released++;
            }
        }
        if (released > 0) {
            auditLogService.log(userId.toString(), projectId, branchId != null ? branchId.toString() : "all",
                    AuditEventType.LOCK_RELEASED, "Auto-released " + released + " element locks on save");
        }
        return released;
    }

    /**
     * Recursively locks an element and all its descendants.
     * Returns a result with locked IDs and any conflicts (children locked by others).
     *
     * @return RecursiveLockResult with lockedStableIds and conflicts
     */
    public RecursiveLockResult acquireLockRecursive(String projectId, UUID branchId, String stableId,
                                                     UUID userId, String username, String sessionId,
                                                     String reason, int ttlMinutes) {
        List<String> lockedIds = new ArrayList<>();
        List<LockConflict> conflicts = new ArrayList<>();

        // First, check the root element
        Optional<ElementLock> rootLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(
                projectId, branchId, stableId, "edit");
        if (rootLock.isPresent() && rootLock.get().getExpiresAt().isAfter(OffsetDateTime.now())
                && !rootLock.get().getOwnerUserId().equals(userId)) {
            conflicts.add(new LockConflict(stableId, rootLock.get().getOwnerUsername(), "Root element"));
            return new RecursiveLockResult(lockedIds, conflicts);
        }

        // Lock the root element
        acquireLock(projectId, branchId, stableId, userId, username, sessionId, null, reason, ttlMinutes);
        lockedIds.add(stableId);

        // Find all descendants
        List<String> descendants = headElementRepository.findDescendantStableIds(projectId, branchId.toString(), stableId);

        // Check each descendant for conflicts before locking
        for (String childId : descendants) {
            Optional<ElementLock> childLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(
                    projectId, branchId, childId, "edit");
            if (childLock.isPresent() && childLock.get().getExpiresAt().isAfter(OffsetDateTime.now())
                    && !childLock.get().getOwnerUserId().equals(userId)) {
                conflicts.add(new LockConflict(childId, childLock.get().getOwnerUsername(), "Descendant"));
            }
        }

        // If any conflicts, abort — don't lock any children
        if (!conflicts.isEmpty()) {
            // Release the root lock we just acquired
            releaseLock(projectId, branchId, stableId, userId);
            lockedIds.clear();
            return new RecursiveLockResult(lockedIds, conflicts);
        }

        // No conflicts — lock all descendants
        for (String childId : descendants) {
            try {
                acquireLock(projectId, branchId, childId, userId, username, sessionId, null, "Recursive lock from " + stableId, ttlMinutes);
                lockedIds.add(childId);
            } catch (IllegalStateException e) {
                // Race condition: someone locked it between our check and now
                conflicts.add(new LockConflict(childId, null, "Race condition: " + e.getMessage()));
                // Rollback: release all locked so far
                for (String locked : lockedIds) {
                    releaseLock(projectId, branchId, locked, userId);
                }
                lockedIds.clear();
                return new RecursiveLockResult(lockedIds, conflicts);
            }
        }

        auditLogService.log(userId.toString(), projectId, branchId.toString(),
                AuditEventType.LOCK_ACQUIRED, "Recursive lock on " + stableId + " + " + (lockedIds.size() - 1) + " descendants");
        return new RecursiveLockResult(lockedIds, conflicts);
    }

    /**
     * Recursively unlocks an element and all its descendants (owned by the user).
     */
    public int releaseLockRecursive(String projectId, UUID branchId, String stableId, UUID userId) {
        int released = 0;

        // Release root
        Optional<ElementLock> rootLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(
                projectId, branchId, stableId, "edit");
        if (rootLock.isPresent() && rootLock.get().getOwnerUserId().equals(userId)) {
            elementLockRepository.delete(rootLock.get());
            released++;
        }

        // Find and release all descendants owned by this user
        List<String> descendants = headElementRepository.findDescendantStableIds(projectId, branchId.toString(), stableId);
        for (String childId : descendants) {
            Optional<ElementLock> childLock = elementLockRepository.findByProjectIdAndBranchIdAndStableIdAndLockType(
                    projectId, branchId, childId, "edit");
            if (childLock.isPresent() && childLock.get().getOwnerUserId().equals(userId)) {
                elementLockRepository.delete(childLock.get());
                released++;
            }
        }

        if (released > 0) {
            auditLogService.log(userId.toString(), projectId, branchId.toString(),
                    AuditEventType.LOCK_RELEASED, "Recursive unlock on " + stableId + " + " + (released - 1) + " descendants");
        }
        return released;
    }

    public record RecursiveLockResult(List<String> lockedStableIds, List<LockConflict> conflicts) {
        public boolean isSuccess() { return conflicts.isEmpty(); }
    }

    public record LockConflict(String stableId, String lockedBy, String reason) {
    }
}
