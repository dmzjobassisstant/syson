/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.vc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.auth.TenantContext;
import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.repository.UserRepository;
import org.eclipse.syson.history.service.ModelReconstructionService;
import org.eclipse.syson.history.service.VersionGraphService;
import org.eclipse.syson.history.service.VersionGraphService.VersionGraphData;
import org.eclipse.syson.locks.entity.BranchLock;
import org.eclipse.syson.locks.entity.ElementLock;
import org.eclipse.syson.locks.entity.IntegrityCheck;
import org.eclipse.syson.locks.repository.IntegrityCheckRepository;
import org.eclipse.syson.locks.service.BranchLockService;
import org.eclipse.syson.locks.service.ElementLockService;
import org.eclipse.syson.locks.service.ElementLockService.RecursiveLockResult;
import org.eclipse.syson.locks.service.IntegrityCheckService;
import org.eclipse.syson.locks.entity.MergeRequest;
import org.eclipse.syson.locks.repository.MergeRequestRepository;
import org.eclipse.syson.locks.repository.TagRepository;
import org.eclipse.syson.settings.ProjectSettingService;
import org.eclipse.syson.vc.dto.BaselineDto;
import org.eclipse.syson.vc.dto.BranchDto;
import org.eclipse.syson.vc.dto.ChangeDto;
import org.eclipse.syson.vc.dto.CommitDto;
import org.eclipse.syson.vc.entity.BranchEntity;
import org.eclipse.syson.vc.repository.BaselineRepository;
import org.eclipse.syson.vc.repository.BranchRepository;
import org.eclipse.syson.vc.repository.ChangeRepository;
import org.eclipse.syson.vc.repository.CommitRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for version control operations.
 * <p>
 * All endpoints live under {@code /api/v1} and expose the append-only
 * BowTie {@code model_changes} pattern via branches, commits, changes,
 * and baselines.
 * </p>
 *
 * @author syson-team
 */
@RestController
@RequestMapping("/api/v1")
public class VersionControlController {

    private final VersionControlService versionControlService;
    private final VersionGraphService versionGraphService;
    private final BranchLockService branchLockService;
    private final ElementLockService elementLockService;
    private final IntegrityCheckService integrityCheckService;
    private final IntegrityCheckRepository integrityCheckRepository;
    private final ModelReconstructionService modelReconstructionService;
    private final UserRepository userRepository;
    private final ProjectSettingService projectSettingService;
    private final BranchRepository branchRepository;
    private final CommitRepository commitRepository;
    private final ChangeRepository changeRepository;
    private final BaselineRepository baselineRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final TagRepository tagRepository;
    private final BranchProjectionService branchProjectionService;

    public VersionControlController(VersionControlService versionControlService,
                                     VersionGraphService versionGraphService,
                                     BranchLockService branchLockService,
                                     ElementLockService elementLockService,
                                     IntegrityCheckService integrityCheckService,
                                     IntegrityCheckRepository integrityCheckRepository,
                                     ModelReconstructionService modelReconstructionService,
                                     UserRepository userRepository,
                                     ProjectSettingService projectSettingService,
                                     BranchRepository branchRepository,
                                     CommitRepository commitRepository,
                                     ChangeRepository changeRepository,
                                     BaselineRepository baselineRepository,
                                     MergeRequestRepository mergeRequestRepository,
                                     TagRepository tagRepository,
                                     BranchProjectionService branchProjectionService) {
        this.versionControlService = versionControlService;
        this.versionGraphService = versionGraphService;
        this.branchLockService = branchLockService;
        this.elementLockService = elementLockService;
        this.integrityCheckService = integrityCheckService;
        this.integrityCheckRepository = integrityCheckRepository;
        this.modelReconstructionService = modelReconstructionService;
        this.userRepository = userRepository;
        this.projectSettingService = projectSettingService;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.changeRepository = changeRepository;
        this.baselineRepository = baselineRepository;
        this.mergeRequestRepository = mergeRequestRepository;
        this.tagRepository = tagRepository;
        this.branchProjectionService = branchProjectionService;
    }

    // ─── branches ────────────────────────────────────────────────────────

    /**
     * Lists all branches for a project.
     */
    @GetMapping("/projects/{projectId}/branches")
    public ResponseEntity<List<BranchDto>> getBranches(
            @PathVariable UUID projectId,
            @RequestParam UUID tenantId) {
        return ResponseEntity.ok(this.versionControlService.getBranches(projectId, tenantId));
    }

    /**
     * Creates a new branch.
     */
    @PostMapping("/projects/{projectId}/branches")
    public ResponseEntity<BranchDto> createBranch(
            @PathVariable UUID projectId,
            @RequestBody CreateBranchRequest request) {
        UUID tenantId = request.tenantId() != null ? request.tenantId()
                : UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userId = request.userId() != null ? request.userId()
                : TenantContext.getUserIdAsUuid();
        BranchDto branch = this.versionControlService.createBranch(
                projectId,
                tenantId,
                request.name(),
                request.branchType(),
                request.parentBranchId(),
                userId);
        try {
            this.branchProjectionService.seedBranchHeadFromCurrentSirius(projectId.toString(), branch.branchId());
        } catch (Exception ignored) {
            // Branches can be created before the Sirius document exists. The
            // first apply/save will seed the branch projection if needed.
        }
        return ResponseEntity.ok(branch);
    }

    // ─── commits ─────────────────────────────────────────────────────────

    /**
     * Lists the commit history for a branch, newest first.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/commits")
    public ResponseEntity<List<CommitDto>> getCommitHistory(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        return ResponseEntity.ok(this.versionControlService.getCommitHistory(projectId, branchId));
    }

    /**
     * Returns a single commit by id.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/commits/{commitId}")
    public ResponseEntity<CommitDto> getCommit(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID commitId) {
        return this.versionControlService.getCommit(projectId, commitId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Creates a new commit (append-only) with its changes.
     */
    @PostMapping("/projects/{projectId}/branches/{branchId}/commits")
    public ResponseEntity<CommitDto> createCommit(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @RequestBody CreateCommitRequest request) {
        CommitDto commit = this.versionControlService.createCommit(
                projectId,
                branchId,
                request.userId(),
                request.message(),
                request.changes());
        return ResponseEntity.ok(commit);
    }

    // ─── diffs ───────────────────────────────────────────────────────────

    /**
     * Returns the ordered list of changes for a commit (the diff).
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/commits/{commitId}/diff")
    public ResponseEntity<List<ChangeDto>> getCommitDiff(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @PathVariable UUID commitId) {
        return ResponseEntity.ok(this.versionControlService.getCommitDiff(projectId, commitId));
    }

    // ─── baselines ───────────────────────────────────────────────────────

    /**
     * Lists all baselines anchored to a commit.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/baselines")
    public ResponseEntity<List<BaselineDto>> getBaselines(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @RequestParam UUID commitId) {
        return ResponseEntity.ok(this.versionControlService.getBaselines(projectId, commitId));
    }

    /**
     * Creates a new baseline anchored to a specific commit.
     */
    @PostMapping("/projects/{projectId}/branches/{branchId}/baselines")
    public ResponseEntity<BaselineDto> createBaseline(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @RequestBody CreateBaselineRequest request) {
        BaselineDto baseline = this.versionControlService.createBaseline(
                projectId,
                request.tenantId(),
                request.commitId(),
                request.code(),
                request.name(),
                request.userId());
        return ResponseEntity.ok(baseline);
    }

    // ─── overview ────────────────────────────────────────────────────────

    /**
     * Returns aggregate counts for the version control data in a project.
     */
    @GetMapping("/projects/{projectId}/version-control/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @PathVariable UUID projectId) {
        long branchCount = this.branchRepository.countByProjectIdAndIsDeletedFalse(projectId);
        long commitCount = this.commitRepository.countByProjectId(projectId);
        long changeCount = this.changeRepository.countByProjectId(projectId);
        long baselineCount = this.baselineRepository.countByProjectId(projectId);
        long tagCount = this.versionGraphService.getVersionGraph(projectId).tags().size();
        long openMRCount = this.mergeRequestRepository.countByProjectIdAndStatus(projectId.toString(), "open");

        Map<String, Object> overview = new HashMap<>();
        overview.put("branchCount", branchCount);
        overview.put("commitCount", commitCount);
        overview.put("changeCount", changeCount);
        overview.put("baselineCount", baselineCount);
        overview.put("tagCount", tagCount);
        overview.put("openMRCount", openMRCount);
        return ResponseEntity.ok(overview);
    }

    // ─── save (explicit history extraction) ───────────────────────────────

    /**
     * Triggers an explicit save + history extraction for the current project state.
     * Used by the save button in the editor toolbar.
     * Finds the project's semantic data documents and runs the history pipeline.
     */
    @PostMapping("/projects/{projectId}/save")
    public ResponseEntity<Map<String, Object>> saveProject(
            @PathVariable UUID projectId,
            @RequestBody(required = false) SaveRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Find semantic data for this project via native query
            List<?> sdRows = this.versionControlService.findSemanticDataIds(projectId);
            if (sdRows.isEmpty()) {
                result.put("saved", false);
                result.put("message", "No semantic data found for this project");
                return ResponseEntity.ok(result);
            }

            UUID semanticDataId = (UUID) sdRows.get(0);
            UUID branchId = request != null && request.branchId() != null
                    ? request.branchId()
                    : resolveBranchForSave(projectId);

            // Call the save pipeline via the history service
            boolean success = this.versionControlService.triggerSaveFromSemanticData(
                    projectId, semanticDataId, branchId, TenantContext.getUserIdAsUuid());
            result.put("saved", success);
            result.put("branchId", branchId.toString());
            result.put("message", success ? "Save complete — history recorded" : "Save completed (no new changes)");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("saved", false);
            result.put("message", "Save failed: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    private UUID resolveBranchForSave(UUID projectId) {
        // Try default branch from settings
        String branchIdStr = this.projectSettingService.get(projectId.toString(), "default_branch_id", "");
        if (branchIdStr.startsWith("\"") && branchIdStr.endsWith("\"")) {
            branchIdStr = branchIdStr.substring(1, branchIdStr.length() - 1);
        }
        if (!branchIdStr.isEmpty()) {
            try { return UUID.fromString(branchIdStr); } catch (Exception e) { /* fall through */ }
        }
        // Fallback: first non-deleted branch
        var branches = this.branchRepository.findByProjectIdAndIsDeletedFalse(projectId);
        return branches.stream()
                .filter(b -> "main".equals(b.getName()))
                .findFirst()
                .map(b -> b.getBranchId())
                .orElse(branches.isEmpty() ? null : branches.get(0).getBranchId());
    }

    // ─── version graph (tree) ────────────────────────────────────────────

    /**
     * Returns the complete version graph for the GitGraph UI visualization,
     * including branches, commits, baselines, and tags.
     */
    @GetMapping("/projects/{projectId}/version-control/tree")
    public ResponseEntity<VersionGraphData> getVersionTree(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(this.versionGraphService.getVersionGraph(projectId));
    }

    // ─── compare ─────────────────────────────────────────────────────────

    /**
     * Compares two commits by returning the changes (diffs) at each commit
     * and the reconstructed canonical model at each commit's branch HEAD.
     */
    @GetMapping("/projects/{projectId}/version-control/compare")
    public ResponseEntity<CompareResult> compare(
            @PathVariable UUID projectId,
            @RequestParam UUID base,
            @RequestParam UUID target) {
        List<ChangeDto> baseChanges = this.versionControlService.getCommitDiff(projectId, base);
        List<ChangeDto> targetChanges = this.versionControlService.getCommitDiff(projectId, target);

        Optional<CommitDto> baseCommit = this.versionControlService.getCommit(projectId, base);
        Optional<CommitDto> targetCommit = this.versionControlService.getCommit(projectId, target);

        Map<String, Object> baseReconstruction = baseCommit
                .map(c -> this.modelReconstructionService.reconstructCanonical(projectId.toString(), c.branchId()))
                .orElse(Map.of());
        Map<String, Object> targetReconstruction = targetCommit
                .map(c -> this.modelReconstructionService.reconstructCanonical(projectId.toString(), c.branchId()))
                .orElse(Map.of());

        return ResponseEntity.ok(new CompareResult(
                base, target, baseChanges, targetChanges,
                baseReconstruction, targetReconstruction));
    }

    // ─── branch locks ────────────────────────────────────────────────────

    /**
     * Returns the current lock on a branch, or 404 if no lock exists.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/lock")
    public ResponseEntity<BranchLock> getLock(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        return this.branchLockService.getLock(projectId.toString(), branchId, "branch")
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Acquires a lock on a branch. Returns 409 if already locked by another user.
     */
    @PostMapping("/projects/{projectId}/branches/{branchId}/lock")
    public ResponseEntity<?> acquireLock(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId,
            @RequestBody AcquireLockRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        try {
            BranchLock lock = this.branchLockService.acquireLock(
                    projectId.toString(), branchId, userId,
                    request.sessionId(), request.deviceId(),
                    request.reason(), request.ttlMinutes());
            return ResponseEntity.ok(lock);
        } catch (IllegalStateException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(409).body(error);
        }
    }

    /**
     * Releases the lock on a branch.
     */
    @DeleteMapping("/projects/{projectId}/branches/{branchId}/lock")
    public ResponseEntity<Void> releaseLock(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        UUID userId = TenantContext.getUserIdAsUuid();
        this.branchLockService.releaseLock(projectId.toString(), branchId, "branch", userId);
        return ResponseEntity.noContent().build();
    }

    // ─── element locks ───────────────────────────────────────────────────

    /**
     * Returns all active element locks for a project.
     */
    @GetMapping("/projects/{projectId}/element-locks")
    public ResponseEntity<List<ElementLock>> getProjectElementLocks(
            @PathVariable UUID projectId) {
        List<ElementLock> locks = this.elementLockService.getActiveLocks(projectId.toString());
        return ResponseEntity.ok(locks);
    }

    /**
     * Returns the lock status of a specific element, or 404 if unlocked.
     */
    @GetMapping("/projects/{projectId}/elements/{stableId}/lock")
    public ResponseEntity<ElementLock> getElementLock(
            @PathVariable UUID projectId,
            @PathVariable String stableId,
            @RequestParam UUID branchId) {
        return this.elementLockService.getLock(projectId.toString(), branchId, stableId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Acquires a lock on an element. Returns 409 if already locked by another user.
     */
    @PostMapping("/projects/{projectId}/elements/{stableId}/lock")
    public ResponseEntity<?> acquireElementLock(
            @PathVariable UUID projectId,
            @PathVariable String stableId,
            @RequestBody AcquireElementLockRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        String username = this.userRepository.findById(userId)
                .map(SysonUser::getEmail)
                .orElse(userId.toString());
        try {
            ElementLock lock = this.elementLockService.acquireLock(
                    projectId.toString(), request.branchId(), stableId, userId,
                    username, request.sessionId(), request.deviceId(),
                    request.reason(), request.ttlMinutes());
            return ResponseEntity.ok(lock);
        } catch (IllegalStateException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(409).body(error);
        }
    }

    /**
     * Releases the lock on an element. Only the lock owner can release.
     */
    @DeleteMapping("/projects/{projectId}/elements/{stableId}/lock")
    public ResponseEntity<Void> releaseElementLock(
            @PathVariable UUID projectId,
            @PathVariable String stableId,
            @RequestParam UUID branchId) {
        UUID userId = TenantContext.getUserIdAsUuid();
        this.elementLockService.releaseLock(projectId.toString(), branchId, stableId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Releases all element locks held by the current user in a project (called on save).
     */
    @PostMapping("/projects/{projectId}/element-locks/release-all")
    public ResponseEntity<Map<String, Object>> releaseAllElementLocks(
            @PathVariable UUID projectId,
            @RequestBody ReleaseAllLocksRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        int released = this.elementLockService.releaseLocksForSave(
                projectId.toString(), request.branchId(), userId);
        Map<String, Object> result = new HashMap<>();
        result.put("released", released);
        return ResponseEntity.ok(result);
    }

    // ─── integrity checks ────────────────────────────────────────────────

    /**
     * Returns the latest integrity check for a branch, or 404 if none exists.
     */
    @GetMapping("/projects/{projectId}/branches/{branchId}/integrity/latest")
    public ResponseEntity<IntegrityCheck> getLatestIntegrityCheck(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        return this.integrityCheckRepository
                .findTopByProjectIdAndBranchIdOrderByCheckedAtDesc(projectId.toString(), branchId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Runs a new integrity check on the HEAD state of a branch.
     */
    @PostMapping("/projects/{projectId}/branches/{branchId}/integrity/run")
    public ResponseEntity<IntegrityCheck> runIntegrityCheck(
            @PathVariable UUID projectId,
            @PathVariable UUID branchId) {
        UUID userId = TenantContext.getUserIdAsUuid();
        IntegrityCheck check = this.integrityCheckService.runCheck(projectId.toString(), branchId, userId);
        return ResponseEntity.ok(check);
    }

    // ─── project settings ────────────────────────────────────────────────

    /**
     * Returns whether element locking is enabled for this project.
     */
    @GetMapping("/projects/{projectId}/settings/element-locking")
    public ResponseEntity<Map<String, Object>> getElementLockingSetting(
            @PathVariable UUID projectId) {
        boolean enabled = this.projectSettingService.isEnabled(projectId.toString(), "element_locking_enabled");
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", enabled);
        return ResponseEntity.ok(result);
    }

    /**
     * Enables or disables element locking for this project (admin only).
     */
    @PostMapping("/projects/{projectId}/settings/element-locking")
    public ResponseEntity<Map<String, Object>> setElementLockingSetting(
            @PathVariable UUID projectId,
            @RequestBody SetElementLockingRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        this.projectSettingService.set(projectId.toString(), "element_locking_enabled",
                String.valueOf(request.enabled()), "Enable element-level edit locking", userId);
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", request.enabled());
        return ResponseEntity.ok(result);
    }

    // ─── recursive element locks ─────────────────────────────────────────

    /**
     * Recursively locks an element and all its children.
     * Returns 409 with conflict details if any child is locked by another user.
     */
    @PostMapping("/projects/{projectId}/elements/{stableId}/lock-recursive")
    public ResponseEntity<?> acquireRecursiveLock(
            @PathVariable UUID projectId,
            @PathVariable String stableId,
            @RequestBody AcquireElementLockRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        String username = this.userRepository.findById(userId)
                .map(SysonUser::getEmail)
                .orElse(userId.toString());
        RecursiveLockResult result = this.elementLockService.acquireLockRecursive(
                projectId.toString(), request.branchId(), stableId, userId,
                username, request.sessionId(), request.reason(), request.ttlMinutes());
        if (result.isSuccess()) {
            Map<String, Object> body = new HashMap<>();
            body.put("lockedCount", result.lockedStableIds().size());
            body.put("lockedIds", result.lockedStableIds());
            return ResponseEntity.ok(body);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Cannot lock: " + result.conflicts().size() + " element(s) locked by other users");
            error.put("conflicts", result.conflicts());
            return ResponseEntity.status(409).body(error);
        }
    }

    /**
     * Recursively unlocks an element and all its children (owned by current user).
     */
    @DeleteMapping("/projects/{projectId}/elements/{stableId}/lock-recursive")
    public ResponseEntity<Map<String, Object>> releaseRecursiveLock(
            @PathVariable UUID projectId,
            @PathVariable String stableId,
            @RequestParam UUID branchId) {
        UUID userId = TenantContext.getUserIdAsUuid();
        int released = this.elementLockService.releaseLockRecursive(
                projectId.toString(), branchId, stableId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("released", released);
        return ResponseEntity.ok(result);
    }

    // ─── tags ────────────────────────────────────────────────────────────

    /**
     * Lists all tags for a project.
     */
    @GetMapping("/projects/{projectId}/tags")
    public ResponseEntity<List<org.eclipse.syson.locks.entity.Tag>> getTags(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(this.tagRepository.findByProjectIdOrderByName(projectId.toString()));
    }

    // ─── merge requests ──────────────────────────────────────────────────

    /**
     * Lists all merge requests for a project.
     */
    @GetMapping("/projects/{projectId}/merge-requests")
    public ResponseEntity<List<MergeRequest>> getMergeRequests(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(this.mergeRequestRepository.findByProjectIdOrderByCreatedAtDesc(projectId.toString()));
    }

    // ─── default branch settings ─────────────────────────────────────────

    /**
     * Returns the default branch ID for this project, or null if not set.
     */
    @GetMapping("/projects/{projectId}/settings/default-branch")
    public ResponseEntity<Map<String, Object>> getDefaultBranch(
            @PathVariable UUID projectId) {
        String branchId = this.projectSettingService.get(projectId.toString(), "default_branch_id", "");
        // Strip JSONB quotes if present (stored as "\"value\"")
        if (branchId.startsWith("\"") && branchId.endsWith("\"")) {
            branchId = branchId.substring(1, branchId.length() - 1);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("branchId", branchId.isEmpty() ? null : branchId);
        return ResponseEntity.ok(result);
    }

    /**
     * Applies a branch as the active editor context. This writes the selected
     * branch HEAD projection back into Sirius document.content rows so the
     * stock Sirius Web workbench can load the branch after a browser reload.
     */
    @PostMapping("/projects/{projectId}/version-control/apply-branch")
    public ResponseEntity<Map<String, Object>> applyBranch(
            @PathVariable UUID projectId,
            @RequestBody SetDefaultBranchRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        UUID branchId = UUID.fromString(request.branchId());
        BranchProjectionService.ApplyBranchResult applied = this.branchProjectionService.applyBranch(projectId, branchId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("branchId", applied.branchId());
        result.put("name", applied.name());
        result.put("appliedDocuments", applied.appliedDocuments());
        result.put("seededFromCurrentSirius", applied.seededFromCurrentSirius());
        return ResponseEntity.ok(result);
    }

    /**
     * Sets the default branch ID for this project (admin only).
     */
    @PostMapping("/projects/{projectId}/settings/default-branch")
    public ResponseEntity<Map<String, Object>> setDefaultBranch(
            @PathVariable UUID projectId,
            @RequestBody SetDefaultBranchRequest request) {
        UUID userId = TenantContext.getUserIdAsUuid();
        // Wrap as JSON string for JSONB column
        String jsonValue = "\"" + request.branchId() + "\"";
        this.projectSettingService.set(projectId.toString(), "default_branch_id",
                jsonValue, "Default branch for model loading", userId);
        Map<String, Object> result = new HashMap<>();
        result.put("branchId", request.branchId());
        return ResponseEntity.ok(result);
    }

    // ─── request records ─────────────────────────────────────────────────

    public record CreateBranchRequest(
            UUID tenantId,
            String name,
            String branchType,
            UUID parentBranchId,
            UUID userId) {
    }

    public record CreateCommitRequest(
            UUID userId,
            String message,
            List<ChangeDto> changes) {
    }

    public record CreateBaselineRequest(
            UUID tenantId,
            UUID commitId,
            String code,
            String name,
            UUID userId) {
    }

    public record AcquireLockRequest(
            String reason,
            int ttlMinutes,
            String sessionId,
            String deviceId) {
    }

    public record CompareResult(
            UUID baseCommitId,
            UUID targetCommitId,
            List<ChangeDto> baseChanges,
            List<ChangeDto> targetChanges,
            Map<String, Object> baseReconstruction,
            Map<String, Object> targetReconstruction) {
    }

    public record AcquireElementLockRequest(
            UUID branchId,
            String reason,
            int ttlMinutes,
            String sessionId,
            String deviceId) {
    }

    public record ReleaseAllLocksRequest(
            UUID branchId) {
    }

    public record SetElementLockingRequest(
            boolean enabled) {
    }

    public record SetDefaultBranchRequest(
            String branchId) {
    }

    public record SaveRequest(
            UUID branchId) {
    }
}
