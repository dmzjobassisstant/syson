package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.ElementLock;
import org.eclipse.syson.locks.entity.ElementLockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ElementLockRepository extends JpaRepository<ElementLock, ElementLockId> {

    Optional<ElementLock> findByProjectIdAndBranchIdAndStableIdAndLockType(
            String projectId, UUID branchId, String stableId, String lockType);

    Optional<ElementLock> findByProjectIdAndBranchIdAndStableIdAndExpiresAtAfter(
            String projectId, UUID branchId, String stableId, OffsetDateTime now);

    List<ElementLock> findByOwnerUserIdAndExpiresAtAfter(UUID ownerUserId, OffsetDateTime now);
}
