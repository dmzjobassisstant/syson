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

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.sirius.components.emf.services.api.IEMFEditingContext;
import org.eclipse.syson.persistence.entity.DiagramEdgeEntity;
import org.eclipse.syson.persistence.entity.DiagramEntity;
import org.eclipse.syson.persistence.entity.DiagramNodeEntity;
import org.eclipse.syson.persistence.entity.ElementEntity;
import org.eclipse.syson.persistence.entity.RelationshipEntity;
import org.eclipse.syson.persistence.repository.DiagramEdgeRepository;
import org.eclipse.syson.persistence.repository.DiagramNodeRepository;
import org.eclipse.syson.persistence.repository.DiagramRepository;
import org.eclipse.syson.persistence.repository.ElementRepository;
import org.eclipse.syson.persistence.repository.RelationshipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listens for Sirius Web editing context save events and synchronizes
 * the EMF model state into the element-level persistence tables.
 * <p>
 * Provides a public {@link #syncFromEditingContext(IEditingContext, UUID, UUID)}
 * method that existing event handlers or processors can call during save.
 * </p>
 *
 * @author syson-team
 */
@Component
public class SaveEventListener {

    private final Logger logger = LoggerFactory.getLogger(SaveEventListener.class);

    private final ElementRepository elementRepository;

    private final RelationshipRepository relationshipRepository;

    private final DiagramRepository diagramRepository;

    private final DiagramNodeRepository diagramNodeRepository;

    private final DiagramEdgeRepository diagramEdgeRepository;

    public SaveEventListener(ElementRepository elementRepository,
                             RelationshipRepository relationshipRepository,
                             DiagramRepository diagramRepository,
                             DiagramNodeRepository diagramNodeRepository,
                             DiagramEdgeRepository diagramEdgeRepository) {
        this.elementRepository = elementRepository;
        this.relationshipRepository = relationshipRepository;
        this.diagramRepository = diagramRepository;
        this.diagramNodeRepository = diagramNodeRepository;
        this.diagramEdgeRepository = diagramEdgeRepository;
    }

    /**
     * Synchronizes the full EMF model state from an editing context into
     * the element-level persistence tables for the given project and branch.
     * <p>
     * Existing records for the project + branch are soft-deleted before the
     * sync so that removed model elements disappear from the tables.
     * </p>
     *
     * @param editingContext the Sirius Web editing context
     * @param projectId      the project UUID
     * @param branchId       the branch UUID
     */
    @Transactional
    public void syncFromEditingContext(IEditingContext editingContext, UUID projectId, UUID branchId) {
        if (!(editingContext instanceof IEMFEditingContext emfCtx)) {
            this.logger.debug("Editing context {} is not an EMF editing context; skipping sync", editingContext.getId());
            return;
        }

        ResourceSet resourceSet = emfCtx.getDomain().getResourceSet();
        if (resourceSet == null) {
            this.logger.warn("ResourceSet is null for editing context {}", editingContext.getId());
            return;
        }

        Instant start = java.time.Instant.now();
        this.logger.info("Syncing element tables for project={}, branch={}", projectId, branchId);

        // Soft-delete existing records for this project + branch
        this.softDeleteExisting(projectId, branchId);

        // Map from EObject identity to assigned UUID (for relationship linking)
        Map<EObject, UUID> assignedIds = new HashMap<>();
        OffsetDateTime now = OffsetDateTime.now();

        // Phase 1: Upsert elements
        for (Resource resource : resourceSet.getResources()) {
            for (EObject eObject : resource.getContents()) {
                this.syncEObject(eObject, projectId, branchId, null, assignedIds, now);
            }
        }

        // Phase 2: Create relationships from cross-references
        for (Resource resource : resourceSet.getResources()) {
            for (EObject eObject : resource.getContents()) {
                this.syncRelationships(eObject, projectId, branchId, assignedIds, now);
            }
        }

        long elapsed = java.time.Duration.between(start, java.time.Instant.now()).toMillis();
        this.logger.info("Element table sync completed for project={}, branch={}: {} elements, {} relationships in {} ms",
                projectId, branchId, assignedIds.size(), "…", elapsed);
    }

    // --- private helpers ---

    /**
     * Soft-deletes all non-deleted records for the given project + branch
     * across all element tables.
     */
    private void softDeleteExisting(UUID projectId, UUID branchId) {
        var elements = this.elementRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId);
        for (ElementEntity e : elements) {
            e.setDeleted(true);
        }
        this.elementRepository.saveAll(elements);

        var relationships = this.relationshipRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId);
        for (RelationshipEntity r : relationships) {
            r.setDeleted(true);
        }
        this.relationshipRepository.saveAll(relationships);

        var diagrams = this.diagramRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId);
        for (DiagramEntity d : diagrams) {
            d.setDeleted(true);
        }
        this.diagramRepository.saveAll(diagrams);

        // Diagram nodes and edges are child-of-diagram; soft-delete via diagram
        for (DiagramEntity d : diagrams) {
            var nodes = this.diagramNodeRepository.findByDiagramIdAndIsDeletedFalse(d.getId());
            for (DiagramNodeEntity n : nodes) {
                n.setDeleted(true);
            }
            this.diagramNodeRepository.saveAll(nodes);

            var edges = this.diagramEdgeRepository.findByDiagramIdAndIsDeletedFalse(d.getId());
            for (DiagramEdgeEntity e : edges) {
                e.setDeleted(true);
            }
            this.diagramEdgeRepository.saveAll(edges);
        }
    }

    /**
     * Recursively syncs an EObject and its containment children as ElementEntity rows.
     */
    private void syncEObject(EObject eObject, UUID projectId, UUID branchId,
                             UUID ownerId, Map<EObject, UUID> assignedIds, OffsetDateTime now) {
        UUID elementId = assignedIds.get(eObject);
        if (elementId == null) {
            elementId = UUID.randomUUID();
            assignedIds.put(eObject, elementId);
        }

        String sysmlType = eObject.eClass().getName();
        String name = getStringAttribute(eObject, "name");
        if (name == null || name.isEmpty()) {
            name = sysmlType + "_" + elementId.toString().substring(0, 8);
        }

        ElementEntity entity = new ElementEntity();
        entity.setId(elementId);
        entity.setProjectId(projectId);
        entity.setBranchId(branchId);
        entity.setSysmlType(sysmlType);
        entity.setName(name);
        entity.setOwnerId(ownerId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(false);

        this.elementRepository.save(entity);

        // Recurse into containment children
        for (EReference ref : eObject.eClass().getEAllContainments()) {
            Object value = eObject.eGet(ref);
            if (value instanceof EList<?> list) {
                for (Object item : list) {
                    if (item instanceof EObject child) {
                        this.syncEObject(child, projectId, branchId, elementId, assignedIds, now);
                    }
                }
            } else if (value instanceof EObject child) {
                this.syncEObject(child, projectId, branchId, elementId, assignedIds, now);
            }
        }
    }

    /**
     * Creates RelationshipEntity rows for non-containment EReferences
     * between already-synced elements.
     */
    private void syncRelationships(EObject eObject, UUID projectId, UUID branchId,
                                   Map<EObject, UUID> assignedIds, OffsetDateTime now) {
        UUID sourceId = assignedIds.get(eObject);
        if (sourceId == null) {
            return;
        }

        for (EReference ref : eObject.eClass().getEAllReferences()) {
            if (ref.isContainment() || ref.isContainer()) {
                continue;
            }

            Object value = eObject.eGet(ref);
            Iterable<?> targets;
            if (value instanceof EList<?> list) {
                targets = list;
            } else if (value instanceof EObject single) {
                targets = java.util.List.of(single);
            } else {
                continue;
            }

            for (Object target : targets) {
                if (!(target instanceof EObject targetObj)) {
                    continue;
                }
                UUID targetId = assignedIds.get(targetObj);
                if (targetId == null) {
                    continue;
                }

                RelationshipEntity rel = new RelationshipEntity();
                rel.setId(UUID.randomUUID());
                rel.setProjectId(projectId);
                rel.setBranchId(branchId);
                rel.setRelType(ref.getName());
                rel.setName(ref.getName());
                rel.setSourceId(sourceId);
                rel.setTargetId(targetId);
                rel.setSourceRole(eObject.eClass().getName());
                rel.setTargetRole(targetObj.eClass().getName());
                rel.setCreatedAt(now);
                rel.setUpdatedAt(now);
                rel.setDeleted(false);

                this.relationshipRepository.save(rel);
            }
        }
    }

    /**
     * Safely reads a string attribute from an EObject, returning null if not present.
     */
    private static String getStringAttribute(EObject eObject, String attrName) {
        try {
            Object value = eObject.eGet(eObject.eClass().getEStructuralFeature(attrName));
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
