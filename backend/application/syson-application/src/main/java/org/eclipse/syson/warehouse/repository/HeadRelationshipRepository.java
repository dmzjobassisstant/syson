package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.HeadRelationshipEntity;
import org.eclipse.syson.warehouse.entity.HeadRelationshipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HeadRelationshipRepository extends JpaRepository<HeadRelationshipEntity, HeadRelationshipId> {
    List<HeadRelationshipEntity> findByProjectIdAndBranchIdAndDeletedFalse(String projectId, UUID branchId);
    List<HeadRelationshipEntity> findByProjectIdAndBranchIdAndSourceStableIdAndDeletedFalse(String projectId, UUID branchId, String sourceId);
    List<HeadRelationshipEntity> findByProjectIdAndBranchIdAndTargetStableIdAndDeletedFalse(String projectId, UUID branchId, String targetId);
}
