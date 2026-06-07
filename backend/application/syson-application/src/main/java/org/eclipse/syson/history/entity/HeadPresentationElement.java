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
 * JPA entity mapped to the {@code syson_head_presentation_elements} table. Represents the current (head)
 * state of a diagram node or edge (presentation element) in a given project branch.
 *
 * @author syson-team
 */
@Entity
@Table(name = "syson_head_presentation_elements")
@IdClass(HeadPresentationElementId.class)
public class HeadPresentationElement {

    @Id
    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Id
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Id
    @Column(name = "presentation_id", nullable = false, length = 512)
    private String presentationId;

    @Column(name = "diagram_id", length = 512)
    private String diagramId;

    @Column(name = "semantic_element_id", length = 512)
    private String semanticElementId;

    @Column(name = "presentation_type", length = 255)
    private String presentationType;

    @Column(name = "parent_presentation_id", length = 512)
    private String parentPresentationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bounds", columnDefinition = "jsonb")
    private String bounds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style", columnDefinition = "jsonb")
    private String style;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_object", columnDefinition = "jsonb")
    private String rawObject;

    @Column(name = "object_hash", length = 128)
    private String objectHash;

    @Column(name = "created_commit_id")
    private UUID createdCommitId;

    @Column(name = "updated_commit_id")
    private UUID updatedCommitId;

    @Column(name = "deleted_commit_id")
    private UUID deletedCommitId;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public HeadPresentationElement() {
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

    public String getPresentationId() {
        return this.presentationId;
    }

    public void setPresentationId(String presentationId) {
        this.presentationId = presentationId;
    }

    public String getDiagramId() {
        return this.diagramId;
    }

    public void setDiagramId(String diagramId) {
        this.diagramId = diagramId;
    }

    public String getSemanticElementId() {
        return this.semanticElementId;
    }

    public void setSemanticElementId(String semanticElementId) {
        this.semanticElementId = semanticElementId;
    }

    public String getPresentationType() {
        return this.presentationType;
    }

    public void setPresentationType(String presentationType) {
        this.presentationType = presentationType;
    }

    public String getParentPresentationId() {
        return this.parentPresentationId;
    }

    public void setParentPresentationId(String parentPresentationId) {
        this.parentPresentationId = parentPresentationId;
    }

    public String getBounds() {
        return this.bounds;
    }

    public void setBounds(String bounds) {
        this.bounds = bounds;
    }

    public String getStyle() {
        return this.style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getRawObject() {
        return this.rawObject;
    }

    public void setRawObject(String rawObject) {
        this.rawObject = rawObject;
    }

    public String getObjectHash() {
        return this.objectHash;
    }

    public void setObjectHash(String objectHash) {
        this.objectHash = objectHash;
    }

    public UUID getCreatedCommitId() {
        return this.createdCommitId;
    }

    public void setCreatedCommitId(UUID createdCommitId) {
        this.createdCommitId = createdCommitId;
    }

    public UUID getUpdatedCommitId() {
        return this.updatedCommitId;
    }

    public void setUpdatedCommitId(UUID updatedCommitId) {
        this.updatedCommitId = updatedCommitId;
    }

    public UUID getDeletedCommitId() {
        return this.deletedCommitId;
    }

    public void setDeletedCommitId(UUID deletedCommitId) {
        this.deletedCommitId = deletedCommitId;
    }

    public boolean isDeleted() {
        return this.deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
