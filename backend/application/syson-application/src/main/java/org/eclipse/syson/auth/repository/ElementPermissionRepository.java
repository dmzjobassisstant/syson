package org.eclipse.syson.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ElementPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ElementPermissionRepository extends JpaRepository<ElementPermission, UUID> {
    List<ElementPermission> findByProjectIdAndBranchIdAndElementId(String projectId, UUID branchId, UUID elementId);
    List<ElementPermission> findByProjectIdAndElementId(String projectId, UUID elementId);
    Optional<ElementPermission> findByProjectIdAndBranchIdAndElementIdAndSubjectTypeAndSubjectId(String projectId, UUID branchId, UUID elementId, String subjectType, String subjectId);
}
