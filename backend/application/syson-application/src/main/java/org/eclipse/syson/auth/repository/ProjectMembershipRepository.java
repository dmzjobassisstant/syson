package org.eclipse.syson.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.entity.ProjectMembership.ProjectMembershipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, ProjectMembershipId> {

    List<ProjectMembership> findByIdProjectId(String projectId);

    List<ProjectMembership> findByIdUserId(UUID userId);

    Optional<ProjectMembership> findByIdProjectIdAndIdUserId(String projectId, UUID userId);

    void deleteByIdProjectIdAndIdUserId(String projectId, UUID userId);

    boolean existsByIdProjectIdAndIdUserId(String projectId, UUID userId);
}
