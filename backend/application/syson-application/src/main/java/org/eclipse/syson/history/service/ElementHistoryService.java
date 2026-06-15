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

    public ElementHistoryService(ChangeRepository changeRepository,
                                  CommitRepository commitRepository,
                                  BranchRepository branchRepository) {
        this.changeRepository = changeRepository;
        this.commitRepository = commitRepository;
        this.branchRepository = branchRepository;
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

        // Collect all branch IDs in the ancestry chain
        List<UUID> branchAncestry = collectBranchAncestry(branchId);

        UUID projectUuid = safeUuid(projectId);
        if (projectUuid == null || stableId == null || stableId.isBlank()) {
            return history;
        }

        List<Object[]> changeRecords = changeRepository.findHistoryByObjectRefAndProjectId(stableId, projectUuid);

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

        return history;
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
