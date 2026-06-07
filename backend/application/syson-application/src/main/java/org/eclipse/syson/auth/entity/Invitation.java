package org.eclipse.syson.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "syson_invitations")
public class Invitation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "project_id") private String projectId;
    @Column(name = "email", nullable = false) private String email;
    @Column(name = "tenant_role") private String tenantRole;
    @Column(name = "project_role") private String projectRole;
    @Column(name = "token_hash", nullable = false, unique = true) private String tokenHash;
    @Column(name = "invited_by") private UUID invitedBy;
    @Column(name = "accepted_by") private UUID acceptedBy;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "accepted_at") private OffsetDateTime acceptedAt;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    public UUID getId() { return this.id; } public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return this.tenantId; } public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getProjectId() { return this.projectId; } public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEmail() { return this.email; } public void setEmail(String email) { this.email = email; }
    public String getTenantRole() { return this.tenantRole; } public void setTenantRole(String tenantRole) { this.tenantRole = tenantRole; }
    public String getProjectRole() { return this.projectRole; } public void setProjectRole(String projectRole) { this.projectRole = projectRole; }
    public String getTokenHash() { return this.tokenHash; } public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public UUID getInvitedBy() { return this.invitedBy; } public void setInvitedBy(UUID invitedBy) { this.invitedBy = invitedBy; }
    public UUID getAcceptedBy() { return this.acceptedBy; } public void setAcceptedBy(UUID acceptedBy) { this.acceptedBy = acceptedBy; }
    public OffsetDateTime getExpiresAt() { return this.expiresAt; } public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getAcceptedAt() { return this.acceptedAt; } public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public OffsetDateTime getRevokedAt() { return this.revokedAt; } public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public OffsetDateTime getCreatedAt() { return this.createdAt; } public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
