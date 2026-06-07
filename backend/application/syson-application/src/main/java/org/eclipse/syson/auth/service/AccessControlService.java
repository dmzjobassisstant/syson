package org.eclipse.syson.auth.service;

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.auth.entity.ElementPermission;
import org.eclipse.syson.auth.entity.ProjectMembership;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.model.ProjectRole;
import org.eclipse.syson.auth.model.TenantRole;
import org.eclipse.syson.auth.repository.ElementPermissionRepository;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.ProjectMembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessControlService {
    private final MembershipRepository membershipRepository;
    private final ProjectMembershipRepository projectMembershipRepository;
    private final ElementPermissionRepository elementPermissionRepository;
    private final AuditLogService auditLogService;

    public AccessControlService(MembershipRepository membershipRepository, ProjectMembershipRepository projectMembershipRepository,
            ElementPermissionRepository elementPermissionRepository, AuditLogService auditLogService) {
        this.membershipRepository = membershipRepository;
        this.projectMembershipRepository = projectMembershipRepository;
        this.elementPermissionRepository = elementPermissionRepository;
        this.auditLogService = auditLogService;
    }

    public boolean canReadProject(UUID userId, String projectId) {
        return this.hasProjectRole(userId, projectId, ProjectRole.VIEWER);
    }

    public boolean canWriteProject(UUID userId, String projectId) {
        return this.hasProjectRole(userId, projectId, ProjectRole.USER);
    }

    public boolean canReadElement(UUID userId, String projectId, String elementId) {
        return this.hasElementPermission(userId, projectId, elementId, "read") || this.canReadProject(userId, projectId);
    }

    public boolean canWriteElement(UUID userId, String projectId, String elementId) {
        return this.hasElementPermission(userId, projectId, elementId, "write") || this.canWriteProject(userId, projectId);
    }

    @Transactional
    public void grantProjectRole(String projectId, UUID userId, ProjectRole role) {
        ProjectMembership membership = this.projectMembershipRepository.findByIdProjectIdAndIdUserId(projectId, userId)
                .orElseGet(() -> new ProjectMembership(projectId, userId, role.dbValue()));
        membership.setRole(role.dbValue());
        this.projectMembershipRepository.save(membership);
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_PROJECT_ROLE_GRANTED, userId, userId, projectId + ":" + role.dbValue());
    }

    @Transactional
    public void revokeProjectRole(String projectId, UUID userId) {
        this.projectMembershipRepository.deleteByIdProjectIdAndIdUserId(projectId, userId);
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_PROJECT_ROLE_REVOKED, userId, userId, projectId);
    }

    public void requireProjectRead(String projectId) {
        UUID userId = org.eclipse.syson.auth.TenantContext.getUserIdAsUuid();
        if (!this.canReadProject(userId, projectId)) {
            this.auditLogService.recordAccessDecision(userId, "project", projectId, false);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project access denied");
        }
    }

    public void requireProjectWrite(String projectId) {
        UUID userId = org.eclipse.syson.auth.TenantContext.getUserIdAsUuid();
        if (!this.canWriteProject(userId, projectId)) {
            this.auditLogService.recordAccessDecision(userId, "project", projectId, false);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project write access denied");
        }
    }

    public void requireElementRead(String projectId, String elementId) {
        UUID userId = org.eclipse.syson.auth.TenantContext.getUserIdAsUuid();
        if (!this.canReadElement(userId, projectId, elementId)) {
            this.auditLogService.recordAccessDecision(userId, "element", elementId, false);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Element access denied");
        }
    }

    public void requireElementWrite(String projectId, String elementId) {
        UUID userId = org.eclipse.syson.auth.TenantContext.getUserIdAsUuid();
        if (!this.canWriteElement(userId, projectId, elementId)) {
            this.auditLogService.recordAccessDecision(userId, "element", elementId, false);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Element write access denied");
        }
    }

    private boolean hasProjectRole(UUID userId, String projectId, ProjectRole requiredRole) {
        if (userId == null) {
            return false;
        }
        if (this.hasTenantRole(userId, TenantRole.ADMIN)) {
            return true;
        }
        return this.projectMembershipRepository.findByIdProjectIdAndIdUserId(projectId, userId)
                .map(member -> ProjectRole.from(member.getRole()).getRank() >= requiredRole.getRank())
                .orElse(false);
    }

    private boolean hasTenantRole(UUID userId, TenantRole requiredRole) {
        return this.membershipRepository.findByIdUserId(userId).stream()
                .map(member -> TenantRole.from(member.getRole()))
                .anyMatch(role -> role.getRank() >= requiredRole.getRank());
    }

    private boolean hasElementPermission(UUID userId, String projectId, String elementId, String permission) {
        UUID elementUuid;
        try {
            elementUuid = UUID.fromString(elementId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        List<ElementPermission> permissions = this.elementPermissionRepository.findByProjectIdAndElementId(projectId, elementUuid);
        int requiredRank = permissionRank(permission);
        return permissions.stream()
                .filter(row -> "user".equals(row.getSubjectType()) && userId.toString().equals(row.getSubjectId()))
                .anyMatch(row -> permissionRank(row.getPermission()) >= requiredRank);
    }

    private static int permissionRank(String permission) {
        return switch (permission.toLowerCase()) {
            case "admin" -> 4;
            case "write" -> 3;
            case "comment" -> 2;
            default -> 1;
        };
    }
}
