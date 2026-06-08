package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.HeadPresentationElementEntity;
import org.eclipse.syson.warehouse.entity.HeadPresentationElementEntity.PresentationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface HeadPresentationElementRepository extends JpaRepository<HeadPresentationElementEntity, PresentationId> {
    List<HeadPresentationElementEntity> findByProjectIdAndBranchIdAndDeletedFalse(String projectId, UUID branchId);
    List<HeadPresentationElementEntity> findByProjectIdAndBranchIdAndDiagramStableIdAndDeletedFalse(String projectId, UUID branchId, String diagramId);
}
