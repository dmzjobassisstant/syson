package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.BranchLock;
import org.eclipse.syson.locks.entity.BranchLockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BranchLockRepository extends JpaRepository<BranchLock, BranchLockId> {

    Optional<BranchLock> findByProjectIdAndBranchIdAndLockType(String projectId, UUID branchId, String lockType);

    List<BranchLock> findByOwnerUserIdAndExpiresAtAfter(UUID ownerUserId, OffsetDateTime now);
}
