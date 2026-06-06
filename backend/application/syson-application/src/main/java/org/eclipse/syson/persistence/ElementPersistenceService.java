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
package org.eclipse.syson.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.persistence.dto.DiagramNodeDto;
import org.eclipse.syson.persistence.dto.ElementDto;
import org.eclipse.syson.persistence.dto.RelationshipDto;
import org.eclipse.syson.persistence.repository.DiagramNodeRepository;
import org.eclipse.syson.persistence.repository.ElementRepository;
import org.eclipse.syson.persistence.repository.RelationshipRepository;
import org.springframework.stereotype.Service;

/**
 * Service layer for element-level persistence operations.
 * <p>
 * Translates between JPA entities and DTOs exposed by the REST API.
 * Soft-deleted records are excluded from all queries via
 * repository methods that filter by {@code isDeleted = false}.
 * </p>
 *
 * @author syson-team
 */
@Service
public class ElementPersistenceService {

    private final ElementRepository elementRepository;

    private final RelationshipRepository relationshipRepository;

    private final DiagramNodeRepository diagramNodeRepository;

    public ElementPersistenceService(ElementRepository elementRepository,
                                     RelationshipRepository relationshipRepository,
                                     DiagramNodeRepository diagramNodeRepository) {
        this.elementRepository = elementRepository;
        this.relationshipRepository = relationshipRepository;
        this.diagramNodeRepository = diagramNodeRepository;
    }

    /**
     * Returns all non-deleted elements for the given project and branch.
     */
    public List<ElementDto> getElements(UUID projectId, UUID branchId) {
        return this.elementRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns a single non-deleted element by its id, scoped to project and branch.
     */
    public Optional<ElementDto> getElement(UUID projectId, UUID branchId, UUID elementId) {
        return this.elementRepository.findByProjectIdAndBranchIdAndIdAndIsDeletedFalse(projectId, branchId, elementId)
                .map(this::toDto);
    }

    /**
     * Returns all non-deleted elements whose {@code ownerId} matches the given parent id.
     */
    public List<ElementDto> getChildren(UUID parentId) {
        return this.elementRepository.findByOwnerIdAndIsDeletedFalse(parentId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns all non-deleted relationships for the given project and branch.
     */
    public List<RelationshipDto> getRelationships(UUID projectId, UUID branchId) {
        return this.relationshipRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns all non-deleted diagram nodes for the given diagram.
     */
    public List<DiagramNodeDto> getDiagramNodes(UUID diagramId) {
        return this.diagramNodeRepository.findByDiagramIdAndIsDeletedFalse(diagramId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // --- entity → DTO mappers ---

    private ElementDto toDto(org.eclipse.syson.persistence.entity.ElementEntity e) {
        return new ElementDto(
                e.getId(),
                e.getProjectId(),
                e.getBranchId(),
                e.getSysmlType(),
                e.getName(),
                e.getOwnerId(),
                e.getBody(),
                e.isAbstract(),
                e.isVariation(),
                e.getAttributes(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private RelationshipDto toDto(org.eclipse.syson.persistence.entity.RelationshipEntity r) {
        return new RelationshipDto(
                r.getId(),
                r.getProjectId(),
                r.getBranchId(),
                r.getRelType(),
                r.getName(),
                r.getSourceId(),
                r.getTargetId(),
                r.getSourceRole(),
                r.getTargetRole(),
                r.getMetadata(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private DiagramNodeDto toDto(org.eclipse.syson.persistence.entity.DiagramNodeEntity n) {
        return new DiagramNodeDto(
                n.getId(),
                n.getDiagramId(),
                n.getElementId(),
                n.getSysmlNodeType(),
                n.getX(),
                n.getY(),
                n.getW(),
                n.getH(),
                n.getStyle(),
                n.getCreatedAt(),
                n.getUpdatedAt()
        );
    }
}
