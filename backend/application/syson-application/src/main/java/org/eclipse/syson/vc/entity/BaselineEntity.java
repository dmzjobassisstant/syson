/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.vc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code syson_baselines} table.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_baselines")
public class BaselineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "baseline_id")
    private UUID baselineId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "baseline_code", length = 50)
    private String baselineCode;

    @Column(name = "name", length = 500)
    private String name;

    @Column(name = "commit_id", nullable = false)
    private UUID commitId;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "branch_id")
    private UUID branchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_snapshot", columnDefinition = "jsonb")
    private String canonicalSnapshot;

    public BaselineEntity() {
    }

    public UUID getBaselineId() {
        return this.baselineId;
    }

    public void setBaselineId(UUID baselineId) {
        this.baselineId = baselineId;
    }

    public UUID getProjectId() {
        return this.projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getTenantId() {
        return this.tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getBaselineCode() {
        return this.baselineCode;
    }

    public void setBaselineCode(String baselineCode) {
        this.baselineCode = baselineCode;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getCommitId() {
        return this.commitId;
    }

    public void setCommitId(UUID commitId) {
        this.commitId = commitId;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getApprovedBy() {
        return this.approvedBy;
    }

    public void setApprovedBy(UUID approvedBy) {
        this.approvedBy = approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return this.approvedAt;
    }

    public void setApprovedAt(OffsetDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public OffsetDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatedBy() {
        return this.createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getBranchId() {
        return this.branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public String getCanonicalSnapshot() {
        return this.canonicalSnapshot;
    }

    public void setCanonicalSnapshot(String canonicalSnapshot) {
        this.canonicalSnapshot = canonicalSnapshot;
    }
}
