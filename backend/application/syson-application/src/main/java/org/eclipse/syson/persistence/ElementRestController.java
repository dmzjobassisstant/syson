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
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.persistence.dto.DiagramNodeDto;
import org.eclipse.syson.persistence.dto.ElementDto;
import org.eclipse.syson.persistence.dto.RelationshipDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for element-level persistence operations.
 * <p>
 * All endpoints live under {@code /api/v1} and return DTOs.
 * Tenant-isolation annotations ({@code @PreAuthorize}) will be added in Phase 5.
 * </p>
 *
 * @author syson-team
 */
@RestController
@RequestMapping("/api/v1")
public class ElementRestController {

    private final ElementPersistenceService persistenceService;

    private final CanonicalJsonService canonicalJsonService;

    public ElementRestController(ElementPersistenceService persistenceService,
                                  CanonicalJsonService canonicalJsonService) {
        this.persistenceService = persistenceService;
        this.canonicalJsonService = canonicalJsonService;
    }

    /**
     * Lists all elements in a project branch.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/elements")
    public ResponseEntity<List<ElementDto>> getElements(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        return ResponseEntity.ok(this.persistenceService.getElements(projectId, branchId));
    }

    /**
     * Returns a single element by id, scoped to project and branch.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/elements/{elementId}")
    public ResponseEntity<ElementDto> getElement(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID elementId) {
        return this.persistenceService.getElement(projectId, branchId, elementId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns children (owned elements) of the given element.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/elements/{elementId}/children")
    public ResponseEntity<List<ElementDto>> getChildren(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID elementId) {
        return ResponseEntity.ok(this.persistenceService.getChildren(elementId));
    }

    /**
     * Lists all relationships in a project branch.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/relationships")
    public ResponseEntity<List<RelationshipDto>> getRelationships(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        return ResponseEntity.ok(this.persistenceService.getRelationships(projectId, branchId));
    }

    /**
     * Lists all diagram nodes for a given diagram.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/diagrams/{diagramId}/nodes")
    public ResponseEntity<List<DiagramNodeDto>> getDiagramNodes(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID diagramId) {
        return ResponseEntity.ok(this.persistenceService.getDiagramNodes(diagramId));
    }

    /**
     * Exports the canonical JSON assembly for a project branch:
     * elements + relationships + diagrams (with nodes and edges).
     * Uses {@link CanonicalJsonService} which assembles a complete
     * serialization from the element-level persistence tables.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/export")
    public ResponseEntity<Map<String, Object>> exportBranch(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        return ResponseEntity.ok(this.canonicalJsonService.exportCanonicalMap(projectId, branchId));
    }
}
