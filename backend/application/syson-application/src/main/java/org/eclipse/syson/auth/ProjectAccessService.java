package org.eclipse.syson.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.repository.ProjectMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectAccessService {

    private final ProjectMembershipRepository membershipRepository;

    public ProjectAccessService(ProjectMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    private UUID currentUserId() {
        UUID uid = TenantContext.getUserIdAsUuid();
        if (uid == null) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return uid;
    }

    public List<ProjectMembership> getMyProjects() {
        return this.membershipRepository.findByIdUserId(this.currentUserId());
    }

    public List<ProjectMembership> getProjectMembers(String projectId) {
        return this.membershipRepository.findByIdProjectId(projectId);
    }

    @Transactional
    public void assignUserToProject(String projectId, UUID userId, String role) {
        ProjectMembership pm = new ProjectMembership(projectId, userId, role);
        pm.setCreatedAt(OffsetDateTime.now());
        this.membershipRepository.save(pm);
    }

    @Transactional
    public void removeUserFromProject(String projectId, UUID userId) {
        this.membershipRepository.deleteByIdProjectIdAndIdUserId(projectId, userId);
    }

    public boolean hasProjectAccess(String projectId, String requiredRole) {
        UUID uid = this.currentUserId();
        return this.membershipRepository.findByIdProjectIdAndIdUserId(projectId, uid)
                .map(m -> roleRank(m.getRole()) >= roleRank(requiredRole))
                .orElse(false);
    }

    public String getProjectRole(String projectId) {
        UUID uid = this.currentUserId();
        return this.membershipRepository.findByIdProjectIdAndIdUserId(projectId, uid)
                .map(ProjectMembership::getRole)
                .orElse(null);
    }

    private static int roleRank(String role) {
        return switch (role == null ? "" : role.toLowerCase()) {
            case "superuser", "admin" -> 3;
            case "editor", "user" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
