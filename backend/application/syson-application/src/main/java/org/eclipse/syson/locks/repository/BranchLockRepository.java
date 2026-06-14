package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.BranchLock;
import org.eclipse.syson.locks.entity.BranchLockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BranchLockRepository extends JpaRepository<BranchLock, BranchLockId> {

    Optional<BranchLock> findByProjectIdAndBranchIdAndLockType(String projectId, UUID branchId, String lockType);

    /**
     * Finds an existing lock with a pessimistic write lock (SELECT ... FOR UPDATE).
     * Prevents TOCTOU race condition in lock acquisition.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bl FROM BranchLock bl WHERE bl.id.projectId = :projectId AND bl.id.branchId = :branchId AND bl.id.lockType = :lockType")
    Optional<BranchLock> findByProjectIdAndBranchIdAndLockTypeForUpdate(
            @Param("projectId") String projectId,
            @Param("branchId") UUID branchId,
            @Param("lockType") String lockType);

    List<BranchLock> findByOwnerUserIdAndExpiresAtAfter(UUID ownerUserId, OffsetDateTime now);
}
