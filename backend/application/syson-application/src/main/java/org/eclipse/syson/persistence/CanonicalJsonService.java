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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.persistence.repository.DiagramEdgeRepository;
import org.eclipse.syson.persistence.repository.DiagramNodeRepository;
import org.eclipse.syson.persistence.repository.DiagramRepository;
import org.eclipse.syson.persistence.repository.ElementRepository;
import org.eclipse.syson.persistence.repository.RelationshipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles a canonical JSON representation of a project branch from the
 * element-level persistence tables.
 * <p>
 * The exported JSON includes {@code elements}, {@code relationships},
 * and {@code diagrams} (with their nodes and edges) — a complete
 * serialization suitable for tool interchange and offline analysis.
 * </p>
 *
 * @author syson-team
 */
@Service
public class CanonicalJsonService {

    private static final Logger logger = LoggerFactory.getLogger(CanonicalJsonService.class);

    private final ElementRepository elementRepository;

    private final RelationshipRepository relationshipRepository;

    private final DiagramRepository diagramRepository;

    private final DiagramNodeRepository diagramNodeRepository;

    private final DiagramEdgeRepository diagramEdgeRepository;

    private final ObjectMapper objectMapper;

    public CanonicalJsonService(ElementRepository elementRepository,
                                RelationshipRepository relationshipRepository,
                                DiagramRepository diagramRepository,
                                DiagramNodeRepository diagramNodeRepository,
                                DiagramEdgeRepository diagramEdgeRepository) {
        this.elementRepository = elementRepository;
        this.relationshipRepository = relationshipRepository;
        this.diagramRepository = diagramRepository;
        this.diagramNodeRepository = diagramNodeRepository;
        this.diagramEdgeRepository = diagramEdgeRepository;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Exports the canonical JSON for a project branch.
     *
     * @param projectId the project UUID
     * @param branchId  the branch UUID
     * @return a pretty-printed JSON string with elements, relationships, and diagrams
     */
    public String exportCanonicalJson(UUID projectId, UUID branchId) {
        OffsetDateTime exportedAt = OffsetDateTime.now();

        List<Map<String, Object>> elements = this.elementRepository
                .findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId)
                .stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("projectId", e.getProjectId());
                    m.put("branchId", e.getBranchId());
                    m.put("sysmlType", e.getSysmlType());
                    m.put("name", e.getName());
                    m.put("ownerId", e.getOwnerId());
                    m.put("body", e.getBody());
                    m.put("isAbstract", e.isAbstract());
                    m.put("isVariation", e.isVariation());
                    m.put("attributes", e.getAttributes());
                    m.put("createdAt", e.getCreatedAt());
                    m.put("updatedAt", e.getUpdatedAt());
                    return m;
                })
                .toList();

        List<Map<String, Object>> relationships = this.relationshipRepository
                .findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId)
                .stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("projectId", r.getProjectId());
                    m.put("branchId", r.getBranchId());
                    m.put("relType", r.getRelType());
                    m.put("name", r.getName());
                    m.put("sourceId", r.getSourceId());
                    m.put("targetId", r.getTargetId());
                    m.put("sourceRole", r.getSourceRole());
                    m.put("targetRole", r.getTargetRole());
                    m.put("metadata", r.getMetadata());
                    m.put("createdAt", r.getCreatedAt());
                    m.put("updatedAt", r.getUpdatedAt());
                    return m;
                })
                .toList();

        List<Map<String, Object>> diagrams = this.diagramRepository
                .findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId)
                .stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("projectId", d.getProjectId());
                    m.put("branchId", d.getBranchId());
                    m.put("viewId", d.getViewId());
                    m.put("name", d.getName());
                    m.put("diagramKind", d.getDiagramKind());
                    m.put("createdAt", d.getCreatedAt());
                    m.put("updatedAt", d.getUpdatedAt());

                    List<Map<String, Object>> nodes = this.diagramNodeRepository
                            .findByDiagramIdAndIsDeletedFalse(d.getId())
                            .stream()
                            .map(n -> {
                                Map<String, Object> nm = new LinkedHashMap<>();
                                nm.put("id", n.getId());
                                nm.put("diagramId", n.getDiagramId());
                                nm.put("elementId", n.getElementId());
                                nm.put("sysmlNodeType", n.getSysmlNodeType());
                                nm.put("x", n.getX());
                                nm.put("y", n.getY());
                                nm.put("w", n.getW());
                                nm.put("h", n.getH());
                                nm.put("style", n.getStyle());
                                nm.put("createdAt", n.getCreatedAt());
                                nm.put("updatedAt", n.getUpdatedAt());
                                return nm;
                            })
                            .toList();

                    List<Map<String, Object>> edges = this.diagramEdgeRepository
                            .findByDiagramIdAndIsDeletedFalse(d.getId())
                            .stream()
                            .map(ed -> {
                                Map<String, Object> em = new LinkedHashMap<>();
                                em.put("id", ed.getId());
                                em.put("diagramId", ed.getDiagramId());
                                em.put("relationshipId", ed.getRelationshipId());
                                em.put("sourceNodeId", ed.getSourceNodeId());
                                em.put("targetNodeId", ed.getTargetNodeId());
                                em.put("edgeType", ed.getEdgeType());
                                em.put("routingPoints", ed.getRoutingPoints());
                                em.put("style", ed.getStyle());
                                em.put("createdAt", ed.getCreatedAt());
                                em.put("updatedAt", ed.getUpdatedAt());
                                return em;
                            })
                            .toList();

                    m.put("nodes", nodes);
                    m.put("edges", edges);
                    return m;
                })
                .toList();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("projectId", projectId);
        root.put("branchId", branchId);
        root.put("exportedAt", exportedAt);
        root.put("elements", elements);
        root.put("relationships", relationships);
        root.put("diagrams", diagrams);

        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            logger.error("Failed to serialize canonical JSON for project={}, branch={}", projectId, branchId, e);
            return "{\"error\":\"JSON serialization failed\"}";
        }
    }

    /**
     * Exports the canonical JSON as a structured map (for use by the REST controller).
     */
    public Map<String, Object> exportCanonicalMap(UUID projectId, UUID branchId) {
        String json = this.exportCanonicalJson(projectId, branchId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = this.objectMapper.readValue(json, Map.class);
            return result;
        } catch (Exception e) {
            logger.error("Failed to parse canonical JSON map for project={}, branch={}", projectId, branchId, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "export failed");
            return error;
        }
    }
}
