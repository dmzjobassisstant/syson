package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.HeadDiagramEntity;
import org.eclipse.syson.warehouse.entity.HeadDiagramEntity.HeadDiagramId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface HeadDiagramRepository extends JpaRepository<HeadDiagramEntity, HeadDiagramId> {
    List<HeadDiagramEntity> findByProjectIdAndBranchIdAndDeletedFalse(String projectId, UUID branchId);
}
