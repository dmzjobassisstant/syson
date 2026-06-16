package org.eclipse.syson.history.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import org.eclipse.syson.vc.dto.ChangeDto;
import org.eclipse.syson.vc.dto.CommitDto;
import org.eclipse.syson.vc.VersionControlService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists commits by delegating to the existing {@link VersionControlService}.
 * <p>
 * Converts {@link SysmlModelDiffService.ObjectDiff} instances into
 * {@link ChangeDto} instances and delegates commit creation, hash chain
 * computation, and branch head updates to the proven VC service.
 * </p>
 *
 * @author syson-team
 */
@Service
@Transactional
public class CommitPersistenceService {

    private final VersionControlService versionControlService;

    public CommitPersistenceService(VersionControlService versionControlService) {
        this.versionControlService = versionControlService;
    }

    /**
     * Persists a new commit with its associated diffs.
     *
     * @param projectId the project UUID
     * @param branchId  the branch UUID
     * @param userId    the author
     * @param message   commit message
     * @param diffs     list of object diffs from the model diff service
     * @return the persisted commit DTO
     */
    public CommitDto persistCommit(UUID projectId, UUID branchId, UUID userId,
                                   String message, List<SysmlModelDiffService.ObjectDiff> diffs) {
        List<ChangeDto> changes = diffs.stream()
                .map(d -> {
                    // Serialize changedFields list to JSON array string
                    String changedFieldsJson;
                    if (d.changedFields() == null || d.changedFields().isEmpty()) {
                        changedFieldsJson = "[]";
                    } else {
                        StringBuilder sb = new StringBuilder("[");
                        for (int i = 0; i < d.changedFields().size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append("\"").append(d.changedFields().get(i)
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")).append("\"");
                        }
                        sb.append("]");
                        changedFieldsJson = sb.toString();
                    }
                    return new ChangeDto(
                            null,                                    // changeId - auto-generated
                            projectId,                               // projectId
                            null,                                    // commitId - set by VC service
                            0,                                       // changeSeq - set by VC service
                            "element",                               // objectType category for syson_changes
                            stableIdToUuid(d.stableId()),            // objectId (UUID)
                            d.operation(),                           // operation
                            d.beforeHash(),                          // beforeHash
                            d.afterHash(),                           // afterHash
                            d.patch(),                               // patch
                            d.beforeObject(),                        // beforeObject
                            d.afterObject(),                         // afterObject
                            null,                                    // createdAt - set by VC service
                            userId,                                  // createdBy
                            d.stableId(),                            // stableObjectId
                            changedFieldsJson);                      // changedFields as JSON array
                })
                .toList();

        return this.versionControlService.createCommit(projectId, branchId, userId, message, changes);
    }

    /**
     * Converts a stable ID string to a deterministic UUID using SHA-256.
     * This ensures the same stable ID always maps to the same UUID.
     */
    private UUID stableIdToUuid(String stableId) {
        if (stableId == null) {
            return UUID.randomUUID();
        }
        try {
            // Try direct parse first
            return UUID.fromString(stableId);
        } catch (IllegalArgumentException e) {
            // Generate deterministic UUID from SHA-256 hash
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException nsae) {
                throw new IllegalStateException("SHA-256 not available", nsae);
            }
            byte[] hash = md.digest(stableId.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash);
        }
    }
}
