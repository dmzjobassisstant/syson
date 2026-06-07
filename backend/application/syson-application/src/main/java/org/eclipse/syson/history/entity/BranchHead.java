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
package org.eclipse.syson.history.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapped to the {@code syson_branch_heads} table. Stores the materialized branch head snapshot
 * for a given project/branch, including canonical JSON and counts.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_branch_heads")
@IdClass(BranchHeadId.class)
public class BranchHead {

    @Id
    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Id
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "head_commit_id")
    private UUID headCommitId;

    @Column(name = "semantic_data_id")
    private UUID semanticDataId;

    @Column(name = "canonical_hash", length = 128)
    private String canonicalHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_json", columnDefinition = "jsonb")
    private String canonicalJson;

    @Column(name = "object_count")
    private int objectCount;

    @Column(name = "relationship_count")
    private int relationshipCount;

    @Column(name = "diagram_count")
    private int diagramCount;

    @Column(name = "last_extracted_at")
    private OffsetDateTime lastExtractedAt;

    @Column(name = "extraction_version")
    private Integer extractionVersion;

    public BranchHead() {
    }

    public String getProjectId() {
        return this.projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public UUID getBranchId() {
        return this.branchId;
    }

    public void setBranchId(UUID branchId) {
        this.branchId = branchId;
    }

    public UUID getTenantId() {
        return this.tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getHeadCommitId() {
        return this.headCommitId;
    }

    public void setHeadCommitId(UUID headCommitId) {
        this.headCommitId = headCommitId;
    }

    public UUID getSemanticDataId() {
        return this.semanticDataId;
    }

    public void setSemanticDataId(UUID semanticDataId) {
        this.semanticDataId = semanticDataId;
    }

    public String getCanonicalHash() {
        return this.canonicalHash;
    }

    public void setCanonicalHash(String canonicalHash) {
        this.canonicalHash = canonicalHash;
    }

    public String getCanonicalJson() {
        return this.canonicalJson;
    }

    public void setCanonicalJson(String canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public int getObjectCount() {
        return this.objectCount;
    }

    public void setObjectCount(int objectCount) {
        this.objectCount = objectCount;
    }

    public int getRelationshipCount() {
        return this.relationshipCount;
    }

    public void setRelationshipCount(int relationshipCount) {
        this.relationshipCount = relationshipCount;
    }

    public int getDiagramCount() {
        return this.diagramCount;
    }

    public void setDiagramCount(int diagramCount) {
        this.diagramCount = diagramCount;
    }

    public OffsetDateTime getLastExtractedAt() {
        return this.lastExtractedAt;
    }

    public void setLastExtractedAt(OffsetDateTime lastExtractedAt) {
        this.lastExtractedAt = lastExtractedAt;
    }

    public Integer getExtractionVersion() {
        return this.extractionVersion;
    }

    public void setExtractionVersion(Integer extractionVersion) {
        this.extractionVersion = extractionVersion;
    }
}
