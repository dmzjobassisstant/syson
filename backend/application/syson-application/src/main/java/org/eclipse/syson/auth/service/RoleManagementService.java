package org.eclipse.syson.auth.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.eclipse.syson.auth.entity.TenantMembership;
import org.eclipse.syson.auth.entity.TenantMembership.TenantMembershipId;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.model.TenantRole;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleManagementService {
    private final MembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

    public RoleManagementService(MembershipRepository membershipRepository, AuditLogService auditLogService) {
        this.membershipRepository = membershipRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void assignTenantRole(UUID userId, UUID tenantId, TenantRole role) {
        TenantMembership membership = this.membershipRepository.findByIdUserIdAndIdTenantId(userId, tenantId)
                .orElseGet(() -> {
                    TenantMembership created = new TenantMembership(userId, tenantId, role.dbValue());
                    created.setCreatedAt(OffsetDateTime.now());
                    return created;
                });
        membership.setRole(role.dbValue());
        this.membershipRepository.save(membership);
        this.auditLogService.recordRoleChange(userId, tenantId, "tenant", role.dbValue());
    }

    @Transactional
    public void removeTenantRole(UUID userId, UUID tenantId, TenantRole role) {
        this.membershipRepository.deleteById(new TenantMembershipId(userId, tenantId));
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_ROLE_REMOVED, userId, tenantId, role.dbValue());
    }

    public void requireTenantAdmin(UUID tenantId) {
        UUID userId = org.eclipse.syson.auth.TenantContext.getUserIdAsUuid();
        boolean allowed = this.membershipRepository.findByIdUserIdAndIdTenantId(userId, tenantId)
                .map(membership -> TenantRole.from(membership.getRole()).getRank() >= TenantRole.ADMIN.getRank())
                .orElse(false);
        if (!allowed) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Tenant admin role required");
        }
    }
}
