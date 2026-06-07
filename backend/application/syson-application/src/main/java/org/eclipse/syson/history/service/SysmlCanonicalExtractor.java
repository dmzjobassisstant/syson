package org.eclipse.syson.history.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.sirius.components.emf.services.api.IEMFEditingContext;
import org.springframework.stereotype.Service;

/**
 * Extracts a canonical snapshot of the current SysML model from an EMF editing
 * context. Walks the entire ResourceSet recursively to produce a deterministic
 * representation of all elements and relationships.
 *
 * @author Syson
 */
@Service
public class SysmlCanonicalExtractor {

    private final StableSysmlIdService stableSysmlIdService;
    private final SysmlObjectHasher sysmlObjectHasher;

    public SysmlCanonicalExtractor(StableSysmlIdService stableSysmlIdService, SysmlObjectHasher sysmlObjectHasher) {
        this.stableSysmlIdService = stableSysmlIdService;
        this.sysmlObjectHasher = sysmlObjectHasher;
    }

    /**
     * Record representing a complete canonical model snapshot.
     */
    public record CanonicalModelSnapshot(
            String projectId,
            UUID branchId,
            List<CanonicalElement> elements,
            List<CanonicalRelationship> relationships,
            String canonicalJson,
            String canonicalHash
    ) {}

    /**
     * Record representing a canonical model element.
     */
    public record CanonicalElement(
            String stableId,
            String elementId,
            String sysmlType,
            String name,
            String ownerId,
            String qualifiedName,
            Map<String, Object> attributes,
            String rawJson,
            String objectHash
    ) {}

    /**
     * Record representing a canonical model relationship.
     */
    public record CanonicalRelationship(
            String stableId,
            String relationshipId,
            String relType,
            String sourceId,
            String targetId,
            String sourceRole,
            String targetRole,
            String ownerId,
            Map<String, Object> attributes,
            String rawJson,
            String objectHash
    ) {}

    /**
     * Extracts a canonical model snapshot from the given editing context.
     *
     * @param editingContext
     *            the editing context containing the model
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @return a canonical model snapshot
     */
    public CanonicalModelSnapshot extractFromEditingContext(IEditingContext editingContext, String projectId, UUID branchId) {
        List<CanonicalElement> elements = new ArrayList<>();
        List<CanonicalRelationship> relationships = new ArrayList<>();

        if (editingContext instanceof IEMFEditingContext emfEditingContext) {
            ResourceSet resourceSet = emfEditingContext.getDomain().getResourceSet();
            for (Resource resource : resourceSet.getResources()) {
                String resourceUri = resource.getURI() != null ? resource.getURI().toString() : "";
                if (isLibraryResource(resourceUri)) {
                    continue;
                }
                String documentId = resourceUri;
                for (EObject eObject : resource.getContents()) {
                    walkEObject(eObject, documentId, "", projectId, elements, relationships);
                }
            }
        }

        Map<String, Object> snapshotMap = new HashMap<>();
        snapshotMap.put("projectId", projectId);
        snapshotMap.put("branchId", branchId != null ? branchId.toString() : null);
        snapshotMap.put("elementCount", elements.size());
        snapshotMap.put("relationshipCount", relationships.size());

        String canonicalJson = sysmlObjectHasher.canonicalizeJson(snapshotMap);
        String canonicalHash = sysmlObjectHasher.hashObject(canonicalJson);

        return new CanonicalModelSnapshot(projectId, branchId, elements, relationships, canonicalJson, canonicalHash);
    }

    /**
     * Checks if a resource URI points to a standard library resource.
     */
    private boolean isLibraryResource(String resourceUri) {
        return resourceUri.contains("sysml.libraries") || resourceUri.contains("kerml.libraries");
    }

    /**
     * Recursively walks an EObject tree, extracting elements and relationships.
     */
    @SuppressWarnings("unchecked")
    private void walkEObject(EObject eObject, String documentId, String parentContainmentPath,
                             String projectId, List<CanonicalElement> elements,
                             List<CanonicalRelationship> relationships) {
        EClass eClass = eObject.eClass();
        String eClassName = eClass.getName();
        String containmentPath = parentContainmentPath.isEmpty() ? eClassName : parentContainmentPath + "/" + eClassName;

        String name = resolveName(eObject);
        String stableId = stableSysmlIdService.stableIdFor(documentId, containmentPath, eClassName, name);

        // Extract all structural features into attributes
        Map<String, Object> attributes = new HashMap<>();
        String sourceId = null;
        String targetId = null;
        String sourceRole = null;
        String targetRole = null;
        String ownerId = null;

        for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
            if (!feature.isChangeable()) {
                continue;
            }
            String featureName = feature.getName();
            try {
                Object value = eObject.eGet(feature);
                if (value != null) {
                    if (feature instanceof EReference reference) {
                        if (reference.isContainment()) {
                            // Skip containment references; they are walked recursively
                            continue;
                        }
                        if (reference.isMany()) {
                            List<EObject> refs = (List<EObject>) value;
                            List<String> refIds = new ArrayList<>();
                            for (EObject ref : refs) {
                                refIds.add(resolveReferenceId(ref, documentId, projectId));
                            }
                            attributes.put(featureName, refIds);
                        } else {
                            attributes.put(featureName, resolveReferenceId((EObject) value, documentId, projectId));
                        }
                    } else {
                        attributes.put(featureName, value.toString());
                    }
                }
            } catch (Exception e) {
                // Skip features that cannot be read
            }
        }

        // Detect relationships by checking for source/target patterns
        boolean isRelationship = detectRelationship(eClass);
        String qualifiedName = buildQualifiedName(eObject, containmentPath);

        Map<String, Object> sortedAttributes = new TreeMap<>(attributes);
        String rawJson = sysmlObjectHasher.canonicalizeJson(sortedAttributes);
        String objectHash = sysmlObjectHasher.hashObject(rawJson);

        String elementId = eObject.eResource() != null && eObject.eResource().getURIFragment(eObject) != null
                ? eObject.eResource().getURIFragment(eObject) : stableId;

        if (isRelationship) {
            sourceId = resolveRelatedId(eObject, "source", documentId, projectId);
            targetId = resolveRelatedId(eObject, "target", documentId, projectId);
            sourceRole = resolveRelatedRole(eObject, "source");
            targetRole = resolveRelatedRole(eObject, "target");

            CanonicalRelationship relationship = new CanonicalRelationship(
                    stableId, elementId, eClassName,
                    sourceId, targetId, sourceRole, targetRole,
                    ownerId, sortedAttributes, rawJson, objectHash);
            relationships.add(relationship);
        } else {
            CanonicalElement element = new CanonicalElement(
                    stableId, elementId, eClassName,
                    name, ownerId, qualifiedName,
                    sortedAttributes, rawJson, objectHash);
            elements.add(element);
        }

        // Walk containment children
        for (EReference containmentRef : eClass.getEAllContainments()) {
            try {
                Object value = eObject.eGet(containmentRef);
                if (value instanceof List<?> children) {
                    for (Object child : children) {
                        if (child instanceof EObject childEObject) {
                            walkEObject(childEObject, documentId, containmentPath, projectId, elements, relationships);
                        }
                    }
                } else if (value instanceof EObject childEObject) {
                    walkEObject(childEObject, documentId, containmentPath, projectId, elements, relationships);
                }
            } catch (Exception e) {
                // Skip unreadable containment references
            }
        }
    }

    /**
     * Checks if an EClass represents a relationship type.
     */
    private boolean detectRelationship(EClass eClass) {
        return eClass.getEStructuralFeature("source") != null
                && eClass.getEStructuralFeature("target") != null;
    }

    /**
     * Resolves the name of an EObject.
     */
    private String resolveName(EObject eObject) {
        EStructuralFeature nameFeature = eObject.eClass().getEStructuralFeature("name");
        if (nameFeature != null) {
            Object value = eObject.eGet(nameFeature);
            return value != null ? value.toString() : null;
        }
        return null;
    }

    /**
     * Builds a qualified name for the given containment path.
     */
    private String buildQualifiedName(EObject eObject, String containmentPath) {
        String name = resolveName(eObject);
        if (name != null && !name.isEmpty()) {
            return containmentPath.isEmpty() ? name : containmentPath + "::" + name;
        }
        return containmentPath;
    }

    /**
     * Resolves a reference ID for a related EObject.
     */
    private String resolveReferenceId(EObject ref, String documentId, String projectId) {
        if (ref == null) {
            return null;
        }
        EClass refClass = ref.eClass();
        String refName = resolveName(ref);
        String refContainmentPath = buildContainmentPath(ref);
        return stableSysmlIdService.stableIdFor(documentId, refContainmentPath, refClass.getName(), refName);
    }

    /**
     * Resolves a related element ID by feature name (source/target).
     */
    private String resolveRelatedId(EObject eObject, String featureName, String documentId, String projectId) {
        EStructuralFeature feature = eObject.eClass().getEStructuralFeature(featureName);
        if (feature != null) {
            try {
                Object value = eObject.eGet(feature);
                if (value instanceof EObject ref) {
                    return resolveReferenceId(ref, documentId, projectId);
                }
            } catch (Exception e) {
                // Feature not readable
            }
        }
        return null;
    }

    /**
     * Resolves a related element's role by feature name.
     */
    private String resolveRelatedRole(EObject eObject, String featureName) {
        EStructuralFeature feature = eObject.eClass().getEStructuralFeature(featureName);
        if (feature != null) {
            try {
                Object value = eObject.eGet(feature);
                if (value instanceof EObject ref) {
                    return resolveName(ref);
                }
            } catch (Exception e) {
                // Feature not readable
            }
        }
        return null;
    }

    /**
     * Builds the containment path for an EObject by walking up its container chain.
     */
    private String buildContainmentPath(EObject eObject) {
        StringBuilder path = new StringBuilder();
        EObject current = eObject.eContainer();
        while (current != null) {
            String name = current.eClass().getName();
            if (path.length() > 0) {
                path.insert(0, name + "/");
            } else {
                path.append(name);
            }
            current = current.eContainer();
        }
        return path.toString();
    }

    /**
     * Extracts a canonical model snapshot from serialized document content strings.
     * <p>
     * This is the non-EMF path used by the save event listener. It parses the
     * EMF JSON format directly to produce elements and relationships without
     * requiring an active editing context.
     * </p>
     *
     * @param documents map of document ID to document content JSON
     * @param projectId the project identifier
     * @param branchId  the branch identifier
     * @return canonical model snapshot
     */
    @SuppressWarnings("unchecked")
    public CanonicalModelSnapshot extractFromDocuments(
            Map<UUID, String> documents, String projectId, UUID branchId) {

        List<CanonicalElement> elements = new ArrayList<>();
        List<CanonicalRelationship> relationships = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : documents.entrySet()) {
            UUID docId = entry.getKey();
            String content = entry.getValue();
            if (content == null || content.isBlank()) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> root = mapper.readValue(content, Map.class);

                // EMF JSON has "json" and "ns" metadata plus object entries
                // Walk all top-level keys that are object entries (have "eClass" or similar)
                for (Map.Entry<String, Object> objEntry : root.entrySet()) {
                    if (objEntry.getKey().equals("json") || objEntry.getKey().equals("ns")
                            || objEntry.getKey().equals("migration")) {
                        continue;
                    }
                    if (objEntry.getValue() instanceof Map<?, ?> objMap) {
                        processJsonObject(objMap, docId.toString(), projectId, branchId, null, elements, relationships);
                    }
                }
            } catch (Exception e) {
                // Skip malformed documents
            }
        }

        // Build canonical JSON from elements and relationships
        String canonicalJson = buildCanonicalJson(elements, relationships);
        String canonicalHash = this.sysmlObjectHasher.hashObject(canonicalJson);

        return new CanonicalModelSnapshot(projectId, branchId, elements, relationships, canonicalJson, canonicalHash);
    }

    @SuppressWarnings("unchecked")
    private void processJsonObject(Map<?, ?> obj, String docId, String projectId, UUID branchId,
                                    String ownerId, List<CanonicalElement> elements,
                                    List<CanonicalRelationship> relationships) {
        String id = getStringValue(obj, "id");
        String eClass = getStringValue(obj, "eClass");
        if (id == null) {
            return;
        }

        // Extract name from various SysML name features
        String name = getStringValue(obj, "name");
        if (name == null) name = getStringValue(obj, "declaredName");
        if (name == null) name = getStringValue(obj, "label");

        String sysmlType = eClass != null ? extractTypeName(eClass) : "Unknown";

        // Build stable ID
        String stableId = this.stableSysmlIdService.stableIdFor(docId, ownerId != null ? ownerId : "", sysmlType, name != null ? name : "");

        // Build attributes map
        Map<String, Object> attributes = new TreeMap<>();
        for (Map.Entry<?, ?> feature : obj.entrySet()) {
            String key = (String) feature.getKey();
            if (!key.equals("id") && !key.equals("eClass")) {
                attributes.put(key, feature.getValue());
            }
        }

        String rawJson = this.sysmlObjectHasher.canonicalizeJson((Map<String, Object>) obj);
        String objectHash = this.sysmlObjectHasher.hashObject(rawJson);

        elements.add(new CanonicalElement(stableId, id, sysmlType, name, ownerId, null, attributes, rawJson, objectHash));

        // Process containment children
        for (Map.Entry<?, ?> feature : obj.entrySet()) {
            Object value = feature.getValue();
            if (value instanceof Map<?, ?> childMap) {
                if (childMap.containsKey("eClass") || childMap.containsKey("id")) {
                    processJsonObject(childMap, docId, projectId, branchId, id, elements, relationships);
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> childMap) {
                        if (childMap.containsKey("eClass") || childMap.containsKey("id")) {
                            processJsonObject(childMap, docId, projectId, branchId, id, elements, relationships);
                        }
                    }
                }
            }
        }
    }

    private String getStringValue(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String extractTypeName(String eClassUrl) {
        if (eClassUrl == null) return "Unknown";
        // eClass URLs look like "http://www.eclipse.org/syson/sysml#/PartDefinition"
        int lastSlash = eClassUrl.lastIndexOf('/');
        int lastHash = eClassUrl.lastIndexOf('#');
        int start = Math.max(lastSlash, lastHash);
        if (start >= 0 && start < eClassUrl.length() - 1) {
            return eClassUrl.substring(start + 1);
        }
        return eClassUrl;
    }

    private String buildCanonicalJson(List<CanonicalElement> elements, List<CanonicalRelationship> relationships) {
        Map<String, Object> result = new TreeMap<>();
        Map<String, Map<String, Object>> elementMap = new TreeMap<>();
        for (CanonicalElement e : elements) {
            Map<String, Object> em = new TreeMap<>();
            em.put("id", e.elementId());
            em.put("type", e.sysmlType());
            em.put("name", e.name());
            if (e.ownerId() != null) em.put("owner", e.ownerId());
            em.put("hash", e.objectHash());
            elementMap.put(e.stableId(), em);
        }
        result.put("elements", elementMap);
        result.put("elementCount", elements.size());
        result.put("relationshipCount", relationships.size());
        return this.sysmlObjectHasher.canonicalizeJson(result);
    }
}
