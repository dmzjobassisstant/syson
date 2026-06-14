package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.ElementLock;
import org.eclipse.syson.locks.entity.ElementLockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ElementLockRepository extends JpaRepository<ElementLock, ElementLockId> {

    Optional<ElementLock> findByProjectIdAndBranchIdAndStableIdAndLockType(
            String projectId, UUID branchId, String stableId, String lockType);

    /**
     * Finds an existing element lock with a pessimistic write lock (SELECT ... FOR UPDATE).
     * Prevents TOCTOU race condition in concurrent lock acquisition.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT el FROM ElementLock el WHERE el.id.projectId = :projectId AND el.id.branchId = :branchId AND el.id.stableId = :stableId AND el.id.lockType = :lockType")
    Optional<ElementLock> findByProjectIdAndBranchIdAndStableIdAndLockTypeForUpdate(
            @Param("projectId") String projectId,
            @Param("branchId") UUID branchId,
            @Param("stableId") String stableId,
            @Param("lockType") String lockType);

    Optional<ElementLock> findByProjectIdAndBranchIdAndStableIdAndExpiresAtAfter(
            String projectId, UUID branchId, String stableId, OffsetDateTime now);

    List<ElementLock> findByOwnerUserIdAndExpiresAtAfter(UUID ownerUserId, OffsetDateTime now);

    List<ElementLock> findByProjectIdAndExpiresAtAfter(String projectId, OffsetDateTime now);

    @Modifying
    void deleteByProjectIdAndBranchIdAndStableIdAndLockType(
            String projectId, UUID branchId, String stableId, String lockType);
}
