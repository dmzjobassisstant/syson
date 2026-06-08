package org.eclipse.syson.warehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.sirius.components.emf.services.api.IEMFEditingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extracts canonical SysML elements from an EMF editing context.
 * Produces stable-ID-based element/relationship records with full attribute capture.
 */
@Service
public class CanonicalExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CanonicalExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final StableIdService stableIdService;

    public CanonicalExtractor(StableIdService stableIdService) {
        this.stableIdService = stableIdService;
    }

    /**
     * Extract all elements and relationships from the editing context.
     */
    public CanonicalSnapshot extract(IEditingContext editingContext, String projectId, UUID branchId) {
        if (!(editingContext instanceof IEMFEditingContext emfCtx)) {
            return new CanonicalSnapshot(projectId, branchId, List.of(), List.of(), List.of(), List.of(), "");
        }

        ResourceSet resourceSet = emfCtx.getDomain().getResourceSet();
        if (resourceSet == null) {
            return new CanonicalSnapshot(projectId, branchId, List.of(), List.of(), List.of(), List.of(), "");
        }

        List<CanonicalElement> elements = new ArrayList<>();
        List<CanonicalRelationship> relationships = new ArrayList<>();

        for (Resource resource : resourceSet.getResources()) {
            for (EObject root : resource.getContents()) {
                extractTree(root, null, null, elements, relationships);
            }
        }

        String canonicalHash = StableIdService.sha256(elements.size() + ":" + relationships.size());
        logger.info("Extracted {} elements, {} relationships from project={}", elements.size(), relationships.size(), projectId);

        return new CanonicalSnapshot(projectId, branchId, elements, relationships, List.of(), List.of(), canonicalHash);
    }

    private void extractTree(EObject eObject, String ownerId, UUID documentId,
                             List<CanonicalElement> elements, List<CanonicalRelationship> relationships) {
        if (eObject == null || eObject.eClass() == null) return;

        // Skip transient/proxy objects
        if (eObject.eIsProxy()) return;

        String stableId = stableIdService.stableId(eObject);
        String sysmlType = eObject.eClass().getName();
        String name = extractName(eObject);
        String body = extractBody(eObject);
        Map<String, Object> attributes = extractAttributes(eObject);
        String rawJson = serializeEObject(eObject);
        String objectHash = StableIdService.sha256(rawJson);
        String qualifiedName = buildQualifiedName(eObject);

        CanonicalElement element = new CanonicalElement(
            stableId, sysmlType, name, body, ownerId, qualifiedName,
            documentId, attributes, rawJson, objectHash
        );
        elements.add(element);

        // Extract non-containment cross-references as relationships
        for (EReference ref : eObject.eClass().getEAllReferences()) {
            if (ref.isContainment()) continue;
            Object val = eObject.eGet(ref);
            if (val instanceof EObject target && !target.eIsProxy()) {
                String targetId = stableIdService.stableId(target);
                CanonicalRelationship rel = new CanonicalRelationship(
                    stableId + ":" + ref.getName() + ":" + targetId,
                    ref.getName(), stableId, targetId,
                    ref.getEContainingClass().getName(), null,
                    Map.of(), "{}", StableIdService.sha256(ref.getName() + ":" + targetId)
                );
                relationships.add(rel);
            } else if (val instanceof EList<?> list) {
                for (Object item : list) {
                    if (item instanceof EObject target && !target.eIsProxy()) {
                        String targetId = stableIdService.stableId(target);
                        CanonicalRelationship rel = new CanonicalRelationship(
                            stableId + ":" + ref.getName() + ":" + targetId,
                            ref.getName(), stableId, targetId,
                            ref.getEContainingClass().getName(), null,
                            Map.of(), "{}", StableIdService.sha256(ref.getName() + ":" + targetId)
                        );
                        relationships.add(rel);
                    }
                }
            }
        }

        // Recurse into containment children
        for (EReference ref : eObject.eClass().getEAllContainments()) {
            Object val = eObject.eGet(ref);
            if (val instanceof EObject child) {
                extractTree(child, stableId, documentId, elements, relationships);
            } else if (val instanceof EList<?> children) {
                for (Object item : children) {
                    if (item instanceof EObject child) {
                        extractTree(child, stableId, documentId, elements, relationships);
                    }
                }
            }
        }
    }

    private String extractName(EObject eObject) {
        try {
            EStructuralFeature nameFeature = eObject.eClass().getEStructuralFeature("declaredName");
            if (nameFeature == null) nameFeature = eObject.eClass().getEStructuralFeature("name");
            if (nameFeature != null) {
                Object val = eObject.eGet(nameFeature);
                return val != null ? val.toString() : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractBody(EObject eObject) {
        try {
            EStructuralFeature bodyFeature = eObject.eClass().getEStructuralFeature("body");
            if (bodyFeature != null) {
                Object val = eObject.eGet(bodyFeature);
                return val != null ? val.toString() : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAttributes(EObject eObject) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (EAttribute attr : eObject.eClass().getEAllAttributes()) {
            try {
                Object val = eObject.eGet(attr);
                if (val != null && !attr.isMany()) {
                    attrs.put(attr.getName(), val);
                } else if (val instanceof List<?> list && !list.isEmpty()) {
                    attrs.put(attr.getName(), list);
                }
            } catch (Exception ignored) {}
        }
        return attrs;
    }

    private String buildQualifiedName(EObject eObject) {
        StringBuilder qn = new StringBuilder();
        EObject current = eObject;
        while (current != null) {
            String name = extractName(current);
            if (name != null && !name.isBlank()) {
                if (qn.length() > 0) qn.insert(0, "::");
                qn.insert(0, name);
            }
            current = current.eContainer();
        }
        return qn.toString();
    }

    private String serializeEObject(EObject eObject) {
        try {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("eClass", eObject.eClass().getName());
            for (EStructuralFeature f : eObject.eClass().getEAllStructuralFeatures()) {
                try {
                    Object val = eObject.eGet(f);
                    if (val != null) {
                        serialized.put(f.getName(), val instanceof EObject ? "[ref]" : val);
                    }
                } catch (Exception ignored) {}
            }
            return MAPPER.writeValueAsString(serialized);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // --- Records ---

    public record CanonicalSnapshot(
        String projectId, UUID branchId,
        List<CanonicalElement> elements,
        List<CanonicalRelationship> relationships,
        List<Object> diagrams,
        List<Object> presentations,
        String canonicalHash
    ) {}

    public record CanonicalElement(
        String stableId, String sysmlType, String name, String body,
        String ownerStableId, String qualifiedName, UUID documentId,
        Map<String, Object> attributes, String rawObject, String objectHash
    ) {}

    public record CanonicalRelationship(
        String stableId, String relType, String sourceStableId, String targetStableId,
        String sourceRole, String targetRole,
        Map<String, Object> attributes, String rawObject, String objectHash
    ) {}
}
