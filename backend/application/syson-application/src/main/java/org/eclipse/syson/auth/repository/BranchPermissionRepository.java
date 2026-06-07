package org.eclipse.syson.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.BranchPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BranchPermissionRepository extends JpaRepository<BranchPermission, UUID> {
    List<BranchPermission> findByProjectIdAndBranchId(String projectId, UUID branchId);
    Optional<BranchPermission> findByProjectIdAndBranchIdAndSubjectTypeAndSubjectId(String projectId, UUID branchId, String subjectType, String subjectId);
}
