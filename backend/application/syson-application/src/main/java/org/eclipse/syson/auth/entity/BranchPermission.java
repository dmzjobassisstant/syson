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
@Table(name = "syson_branch_permissions")
public class BranchPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "project_id", nullable = false) private String projectId;
    @Column(name = "branch_id", nullable = false) private UUID branchId;
    @Column(name = "subject_type", nullable = false) private String subjectType;
    @Column(name = "subject_id", nullable = false) private String subjectId;
    @Column(name = "permission", nullable = false) private String permission;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    public UUID getId() { return this.id; } public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return this.tenantId; } public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getProjectId() { return this.projectId; } public void setProjectId(String projectId) { this.projectId = projectId; }
    public UUID getBranchId() { return this.branchId; } public void setBranchId(UUID branchId) { this.branchId = branchId; }
    public String getSubjectType() { return this.subjectType; } public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return this.subjectId; } public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getPermission() { return this.permission; } public void setPermission(String permission) { this.permission = permission; }
    public UUID getCreatedBy() { return this.createdBy; } public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return this.createdAt; } public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
