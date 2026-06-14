package org.eclipse.syson.history.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.history.entity.HeadElement;
import org.eclipse.syson.history.entity.HeadRelationship;
import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.eclipse.syson.history.repository.HeadElementRepository;
import org.eclipse.syson.history.repository.HeadRelationshipRepository;
import org.eclipse.syson.history.repository.ModelSnapshotRepository;
import org.springframework.stereotype.Service;

/**
 * Reconstructs the canonical model representation from HEAD state or cached
 * snapshots.
 *
 * @author Syson
 */
@Service
public class ModelReconstructionService {

    private final HeadElementRepository headElementRepository;
    private final HeadRelationshipRepository headRelationshipRepository;
    private final BranchHeadRepository branchHeadRepository;
    private final ModelSnapshotRepository modelSnapshotRepository;

    public ModelReconstructionService(HeadElementRepository headElementRepository,
                                       HeadRelationshipRepository headRelationshipRepository,
                                       BranchHeadRepository branchHeadRepository,
                                       ModelSnapshotRepository modelSnapshotRepository) {
        this.headElementRepository = headElementRepository;
        this.headRelationshipRepository = headRelationshipRepository;
        this.branchHeadRepository = branchHeadRepository;
        this.modelSnapshotRepository = modelSnapshotRepository;
    }

    /**
     * Reconstructs the canonical model representation for a branch.
     * Returns cached canonical_json from syson_branch_heads if available.
     * Otherwise reconstructs from head_elements and head_relationships.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @return a map representing the canonical model
     */
    public Map<String, Object> reconstructCanonical(String projectId, UUID branchId) {
        // Try cached canonical JSON first
        String cachedCanonicalJson = branchHeadRepository.getCanonicalJson(projectId, branchId);
        if (cachedCanonicalJson != null && !cachedCanonicalJson.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("source", "cache");
            result.put("projectId", projectId);
            result.put("branchId", branchId.toString());
            result.put("canonicalJson", cachedCanonicalJson);
            result.put("canonicalHash", branchHeadRepository.getCanonicalHash(projectId, branchId));
            return result;
        }

        // Reconstruct from head tables
        List<HeadElement> elements = headElementRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId);
        List<HeadRelationship> relationships = headRelationshipRepository.findByProjectIdAndBranchIdAndIsDeletedFalse(projectId, branchId);

        List<Map<String, Object>> elementMaps = new ArrayList<>();
        for (HeadElement elem : elements) {
            Map<String, Object> elemMap = new HashMap<>();
            elemMap.put("stableId", elem.getStableId());
            elemMap.put("sysmlType", elem.getSysmlType());
            elemMap.put("name", elem.getName());
            elemMap.put("ownerId", elem.getOwnerStableId());
            elemMap.put("qualifiedName", elem.getQualifiedName());
            elemMap.put("attributes", elem.getAttributes());
            elemMap.put("objectHash", elem.getObjectHash());
            elementMaps.add(elemMap);
        }

        List<Map<String, Object>> relationshipMaps = new ArrayList<>();
        for (HeadRelationship rel : relationships) {
            Map<String, Object> relMap = new HashMap<>();
            relMap.put("stableId", rel.getStableId());
            relMap.put("relType", rel.getRelType());
            relMap.put("sourceId", rel.getSourceStableId());
            relMap.put("targetId", rel.getTargetStableId());
            relMap.put("sourceRole", rel.getSourceRole());
            relMap.put("targetRole", rel.getTargetRole());
            relMap.put("ownerId", rel.getOwnerStableId());
            relMap.put("attributes", rel.getAttributes());
            relMap.put("objectHash", rel.getObjectHash());
            relationshipMaps.add(relMap);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("source", "reconstructed");
        result.put("projectId", projectId);
        result.put("branchId", branchId.toString());
        result.put("elements", elementMaps);
        result.put("relationships", relationshipMaps);
        result.put("elementCount", elementMaps.size());
        result.put("relationshipCount", relationshipMaps.size());
        return result;
    }
}
