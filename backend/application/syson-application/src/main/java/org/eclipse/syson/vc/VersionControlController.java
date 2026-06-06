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
package org.eclipse.syson.vc;

import java.util.List;
import java.util.UUID;

import org.eclipse.syson.vc.dto.BaselineDto;
import org.eclipse.syson.vc.dto.BranchDto;
import org.eclipse.syson.vc.dto.ChangeDto;
import org.eclipse.syson.vc.dto.CommitDto;
import org.springframework.http.ResponseEntity;
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

    public VersionControlController(VersionControlService versionControlService) {
        this.versionControlService = versionControlService;
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
}
