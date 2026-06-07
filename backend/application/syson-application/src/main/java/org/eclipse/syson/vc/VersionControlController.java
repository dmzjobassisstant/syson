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
import org.eclipse.syson.history.service.ModelReconstructionService;
import org.eclipse.syson.history.service.VersionGraphService;
import org.eclipse.syson.history.service.VersionGraphService.VersionGraphData;
import org.eclipse.syson.locks.entity.BranchLock;
import org.eclipse.syson.locks.entity.IntegrityCheck;
import org.eclipse.syson.locks.repository.IntegrityCheckRepository;
import org.eclipse.syson.locks.service.BranchLockService;
import org.eclipse.syson.locks.service.IntegrityCheckService;
import org.eclipse.syson.vc.dto.BaselineDto;
import org.eclipse.syson.vc.dto.BranchDto;
import org.eclipse.syson.vc.dto.ChangeDto;
import org.eclipse.syson.vc.dto.CommitDto;
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
    private final IntegrityCheckService integrityCheckService;
    private final IntegrityCheckRepository integrityCheckRepository;
    private final ModelReconstructionService modelReconstructionService;
    private final BranchRepository branchRepository;
    private final CommitRepository commitRepository;
    private final ChangeRepository changeRepository;
    private final BaselineRepository baselineRepository;

    public VersionControlController(VersionControlService versionControlService,
                                     VersionGraphService versionGraphService,
                                     BranchLockService branchLockService,
                                     IntegrityCheckService integrityCheckService,
                                     IntegrityCheckRepository integrityCheckRepository,
                                     ModelReconstructionService modelReconstructionService,
                                     BranchRepository branchRepository,
                                     CommitRepository commitRepository,
                                     ChangeRepository changeRepository,
                                     BaselineRepository baselineRepository) {
        this.versionControlService = versionControlService;
        this.versionGraphService = versionGraphService;
        this.branchLockService = branchLockService;
        this.integrityCheckService = integrityCheckService;
        this.integrityCheckRepository = integrityCheckRepository;
        this.modelReconstructionService = modelReconstructionService;
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.changeRepository = changeRepository;
        this.baselineRepository = baselineRepository;
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
        BranchDto branch = this.versionControlService.createBranch(
                projectId,
                request.tenantId(),
                request.name(),
                request.branchType(),
                request.parentBranchId(),
                request.userId());
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

        Map<String, Object> overview = new HashMap<>();
        overview.put("branchCount", branchCount);
        overview.put("commitCount", commitCount);
        overview.put("changeCount", changeCount);
        overview.put("baselineCount", baselineCount);
        overview.put("tagCount", tagCount);
        return ResponseEntity.ok(overview);
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
}
