package org.eclipse.syson.history.service;

import java.util.List;
import java.util.UUID;

import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the full model save pipeline: extract canonical snapshot,
 * compute diffs, persist commit, materialize HEAD state, and audit log.
 *
 * @author Syson
 */
@Service
@Transactional
public class ModelSaveHistoryService {

    private final SysmlCanonicalExtractor sysmlCanonicalExtractor;
    private final SysmlModelDiffService sysmlModelDiffService;
    private final HeadMaterializationService headMaterializationService;
    private final CommitPersistenceService commitPersistenceService;
    private final BranchHeadRepository branchHeadRepository;
    private final AuditLogService auditLogService;

    public ModelSaveHistoryService(SysmlCanonicalExtractor sysmlCanonicalExtractor,
                                    SysmlModelDiffService sysmlModelDiffService,
                                    HeadMaterializationService headMaterializationService,
                                    CommitPersistenceService commitPersistenceService,
                                    BranchHeadRepository branchHeadRepository,
                                    AuditLogService auditLogService) {
        this.sysmlCanonicalExtractor = sysmlCanonicalExtractor;
        this.sysmlModelDiffService = sysmlModelDiffService;
        this.headMaterializationService = headMaterializationService;
        this.commitPersistenceService = commitPersistenceService;
        this.branchHeadRepository = branchHeadRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Processes a model save operation by running the full history pipeline.
     *
     * @param editingContext
     *            the editing context containing the model
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param userId
     *            the user performing the save
     */
    public void processSave(IEditingContext editingContext, String projectId, UUID branchId, UUID userId) {
        // 1. Extract current snapshot
        SysmlCanonicalExtractor.CanonicalModelSnapshot currentSnapshot =
                sysmlCanonicalExtractor.extractFromEditingContext(editingContext, projectId, branchId);

        // 2. Load previous head canonical_json to build previous snapshot
        SysmlCanonicalExtractor.CanonicalModelSnapshot previousSnapshot = loadPreviousSnapshot(projectId, branchId);

        // 3. Compute diffs
        List<SysmlModelDiffService.ObjectDiff> diffs = sysmlModelDiffService.diff(previousSnapshot, currentSnapshot);

        // 4. If no changes, return early
        if (diffs.isEmpty()) {
            return;
        }

        // 5. Persist commit (convert String projectId to UUID)
        org.eclipse.syson.vc.dto.CommitDto commit = commitPersistenceService.persistCommit(
                UUID.fromString(projectId), branchId, userId, "Model save: " + diffs.size() + " changes", diffs);

        // 6. Materialize HEAD
        headMaterializationService.materializeHead(projectId, branchId, commit.commitId(), currentSnapshot, diffs);

        // 7. Audit log
        auditLogService.log(
                userId.toString(),
                projectId,
                branchId.toString(),
                AuditEventType.MODEL_SAVE,
                "Model saved with " + diffs.size() + " changes"
        );
    }

    /**
     * Ensures a default 'main' branch exists for the given project.
     *
     * @param projectId
     *            the project identifier
     * @param tenantId
     *            the tenant identifier
     * @param userId
     *            the user creating the branch
     */
    public void ensureDefaultBranch(String projectId, UUID tenantId, UUID userId) {
        branchHeadRepository.ensureDefaultBranch(projectId, tenantId, userId);
    }

    /**
     * Loads the previous snapshot from the branch head.
     */
    private SysmlCanonicalExtractor.CanonicalModelSnapshot loadPreviousSnapshot(String projectId, UUID branchId) {
        String previousCanonicalJson = branchHeadRepository.getCanonicalJson(projectId, branchId);
        if (previousCanonicalJson == null) {
            return null;
        }
        // Return a minimal snapshot with just the canonical JSON and hash for diffing
        // The actual element/relationship data will be reconstructed if needed
        String previousHash = branchHeadRepository.getCanonicalHash(projectId, branchId);
        return new SysmlCanonicalExtractor.CanonicalModelSnapshot(
                projectId, branchId,
                List.of(), List.of(),
                previousCanonicalJson, previousHash);
    }
}
