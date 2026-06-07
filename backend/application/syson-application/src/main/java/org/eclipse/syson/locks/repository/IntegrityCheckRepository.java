package org.eclipse.syson.locks.repository;

import org.eclipse.syson.locks.entity.IntegrityCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IntegrityCheckRepository extends JpaRepository<IntegrityCheck, UUID> {

    Optional<IntegrityCheck> findTopByProjectIdAndBranchIdOrderByCheckedAtDesc(String projectId, UUID branchId);

    List<IntegrityCheck> findByProjectIdAndBranchIdOrderByCheckedAtDesc(String projectId, UUID branchId);
}
