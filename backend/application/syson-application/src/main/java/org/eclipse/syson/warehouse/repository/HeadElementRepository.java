package org.eclipse.syson.warehouse.repository;

import org.eclipse.syson.warehouse.entity.HeadElementEntity;
import org.eclipse.syson.warehouse.entity.HeadElementId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HeadElementRepository extends JpaRepository<HeadElementEntity, HeadElementId> {

    Optional<HeadElementEntity> findByProjectIdAndBranchIdAndStableIdAndDeletedFalse(
        String projectId, UUID branchId, String stableId);

    List<HeadElementEntity> findByProjectIdAndBranchIdAndDeletedFalse(String projectId, UUID branchId);

    List<HeadElementEntity> findByProjectIdAndBranchIdAndSysmlTypeAndDeletedFalse(
        String projectId, UUID branchId, String sysmlType);

    @Query("SELECT e FROM HeadElementEntity e WHERE e.id.projectId = :projectId AND e.id.branchId = :branchId AND e.deleted = false AND LOWER(e.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<HeadElementEntity> findByNameContainingIgnoreCase(
        @Param("projectId") String projectId, @Param("branchId") UUID branchId, @Param("name") String name);
}
