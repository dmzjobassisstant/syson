package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.BranchHeadEntity;
import org.eclipse.syson.warehouse.entity.BranchHeadEntity.BranchHeadId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BranchHeadRepository extends JpaRepository<BranchHeadEntity, BranchHeadId> {
    Optional<BranchHeadEntity> findByProjectIdAndBranchId(String projectId, UUID branchId);
}
