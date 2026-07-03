package org.eclipse.syson.history.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.vc.repository.BranchRepository;
import org.eclipse.syson.vc.repository.ChangeRepository;
import org.eclipse.syson.vc.repository.CommitRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Provides element-level history by querying change records and joining with
 * commit and branch information. Walks branch ancestry via parent_branch_id
 * chains to find all changes affecting a given element.
 *
 * @author Syson
 */
@Service
public class ElementHistoryService {

    private final ChangeRepository changeRepository;
    private final CommitRepository commitRepository;
    private final BranchRepository branchRepository;
    private final JdbcTemplate jdbcTemplate;

    public ElementHistoryService(ChangeRepository changeRepository,
                                  CommitRepository commitRepository,
                                  BranchRepository branchRepository,
                                  JdbcTemplate jdbcTemplate) {
        this.changeRepository = changeRepository;
        this.commitRepository = commitRepository;
        this.branchRepository = branchRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Gets the full history for an element across all relevant branches.
     *
     * @param projectId
     *            the project identifier
     * @param stableId
     *            the stable ID of the element
     * @param branchId
     *            the branch identifier
     * @return a list of history entries
     */
    public List<Map<String, Object>> getElementHistory(String projectId, String stableId, UUID branchId) {
        List<Map<String, Object>> history = new ArrayList<>();

        UUID projectUuid = safeUuid(projectId);
        if (projectUuid == null || stableId == null || stableId.isBlank()) {
            return history;
        }

        // Resolve the input ID to all possible element IDs that might appear in
        // syson_changes. The input could be:
        //   - A document ID (tree root) → resolve to root elementId
        //   - An XMI fragment id → resolve to elementId from document content
        //   - An elementId / stable_object_id → use directly
        List<String> resolvedIds = resolveElementIds(projectId, stableId);
        resolvedIds.add(stableId); // always include the raw input as fallback

        for (String resolvedId : resolvedIds) {
            List<Object[]> changeRecords = changeRepository.findHistoryByObjectRefAndProjectId(resolvedId, projectUuid);
            for (Object[] record : changeRecords) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("commitId", record[0] != null ? record[0].toString() : null);
                entry.put("operation", record[1]);
                entry.put("branchName", record[2]);
                entry.put("author", record[3] != null ? record[3].toString() : null);
                entry.put("message", record[4]);
                entry.put("committedAt", record[5] instanceof OffsetDateTime odt ? odt.toString() : record[5]);
                entry.put("changedFields", record[6]);
                entry.put("patch", record[7]);
                history.add(entry);
            }
        }

        return history;
    }

    /**
     * Resolves a selection ID (document ID, XMI fragment ID, or tree item ID)
     * to elementId values used in syson_changes. Queries the document content
     * JSONB to find matching elements.
     */
    private List<String> resolveElementIds(String projectId, String selectionId) {
        List<String> resolved = new ArrayList<>();
        try {
            // 1. If selectionId is a document ID, get the root element's elementId
            List<String> rootElementIds = jdbcTemplate.queryForList(
                "SELECT content::jsonb -> 'content' -> 0 -> 'data' ->> 'elementId' " +
                "FROM document d " +
                "JOIN project_semantic_data psd ON d.semantic_data_id = psd.semantic_data_id " +
                "WHERE d.id = ?::uuid AND psd.project_id = ?",
                String.class, selectionId, projectId);
            resolved.addAll(rootElementIds);

            // Also get the root XMI id
            List<String> rootXmiIds = jdbcTemplate.queryForList(
                "SELECT content::jsonb -> 'content' -> 0 ->> 'id' " +
                "FROM document d " +
                "JOIN project_semantic_data psd ON d.semantic_data_id = psd.semantic_data_id " +
                "WHERE d.id = ?::uuid AND psd.project_id = ?",
                String.class, selectionId, projectId);
            resolved.addAll(rootXmiIds);

            // 2. If selectionId is an XMI id, find the elementId from document content
            // Search recursively in the content JSONB for elements with matching id
            List<String> elementIdsFromXmi = jdbcTemplate.queryForList(
                "SELECT elem.value -> 'data' ->> 'elementId' " +
                "FROM document d " +
                "JOIN project_semantic_data psd ON d.semantic_data_id = psd.semantic_data_id, " +
                "LATERAL jsonb_array_elements(d.content::jsonb -> 'content') AS doc_elem, " +
                "LATERAL jsonb_array_elements(doc_elem -> 'data' -> 'ownedRelationship') AS rel_elem, " +
                "LATERAL jsonb_array_elements(rel_elem -> 'data' -> 'ownedRelatedElement') AS elem " +
                "WHERE psd.project_id = ? " +
                "AND (elem.value ->> 'id' = ? OR elem.value -> 'data' ->> 'elementId' = ?)",
                String.class, projectId, selectionId, selectionId);
            resolved.addAll(elementIdsFromXmi);
        } catch (Exception e) {
            // Resolution failed — just use the raw ID (the native query covers it)
        }
        return resolved;
    }

    /**
     * Collects all branch IDs in the ancestry chain by walking parent_branch_id.
     *
     * @param branchId
     *            the starting branch ID
     * @return list of branch IDs including the starting branch and all ancestors
     */
    private List<UUID> collectBranchAncestry(UUID branchId) {
        List<UUID> ancestry = new ArrayList<>();
        UUID currentBranchId = branchId;
        while (currentBranchId != null) {
            ancestry.add(currentBranchId);
            UUID parentBranchId = branchRepository.getParentBranchId(currentBranchId);
            if (parentBranchId != null && !ancestry.contains(parentBranchId)) {
                currentBranchId = parentBranchId;
            } else {
                break;
            }
        }
        return ancestry;
    }

    private UUID safeUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
