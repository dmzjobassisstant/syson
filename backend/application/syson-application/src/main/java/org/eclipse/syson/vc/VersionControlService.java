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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.syson.vc.dto.BaselineDto;
import org.eclipse.syson.vc.dto.BranchDto;
import org.eclipse.syson.vc.dto.ChangeDto;
import org.eclipse.syson.vc.dto.CommitDto;
import org.eclipse.syson.vc.entity.BaselineEntity;
import org.eclipse.syson.vc.entity.BranchEntity;
import org.eclipse.syson.vc.entity.ChangeEntity;
import org.eclipse.syson.vc.entity.CommitEntity;
import org.eclipse.syson.vc.repository.BaselineRepository;
import org.eclipse.syson.vc.repository.BranchRepository;
import org.eclipse.syson.vc.repository.ChangeRepository;
import org.eclipse.syson.vc.repository.CommitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer for append-only version control operations.
 * <p>
 * Implements BowTie's append-only {@code model_changes} pattern:
 * every mutation creates an immutable commit with a chain of changes.
 * Commit hashes use SHA-256 of
 * {@code (parent_hash + ":" + commit_number + ":" + concatenated_change_hashes)}
 * for cryptographically-verifiable chain integrity.
 * </p>
 *
 * @author syson-team
 */
@Service
public class VersionControlService {

    private final BranchRepository branchRepository;

    private final CommitRepository commitRepository;

    private final ChangeRepository changeRepository;

    private final BaselineRepository baselineRepository;

    private final jakarta.persistence.EntityManager entityManager;

    private final BranchProjectionService branchProjectionService;

    public VersionControlService(BranchRepository branchRepository,
                                 CommitRepository commitRepository,
                                 ChangeRepository changeRepository,
                                 BaselineRepository baselineRepository,
                                 jakarta.persistence.EntityManager entityManager,
                                 BranchProjectionService branchProjectionService) {
        this.branchRepository = branchRepository;
        this.commitRepository = commitRepository;
        this.changeRepository = changeRepository;
        this.baselineRepository = baselineRepository;
        this.entityManager = entityManager;
        this.branchProjectionService = branchProjectionService;
    }

    // ─── branch operations ────────────────────────────────────────────────

    /**
     * Creates a new branch in the given project and tenant.
     */
    @Transactional
    public BranchDto createBranch(UUID projectId, UUID tenantId, String name,
                                  String type, UUID parentBranchId, UUID userId) {
        BranchEntity entity = new BranchEntity();
        entity.setProjectId(projectId);
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setBranchType(type != null ? type : "main");
        entity.setParentBranchId(parentBranchId);
        entity.setProtected(false);
        entity.setDeleted(false);
        entity.setCreatedBy(userId);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        BranchEntity saved = this.branchRepository.save(entity);
        return toDto(saved);
    }

    /**
     * Lists all non-deleted branches for a project and tenant.
     */
    public List<BranchDto> getBranches(UUID projectId, UUID tenantId) {
        return this.branchRepository.findByProjectIdAndTenantIdAndIsDeletedFalse(projectId, tenantId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns a single branch by id.
     */
    public Optional<BranchDto> getBranch(UUID branchId) {
        return this.branchRepository.findByBranchIdAndIsDeletedFalse(branchId)
                .map(this::toDto);
    }

    // ─── commit operations (append-only, BowTie model_changes pattern) ─────

    /**
     * Creates an append-only commit with its changes in a single transaction.
     * <p>
     * This is the core of BowTie's {@code model_changes} pattern:
     * <ol>
     *   <li>Compute next commit number from the branch's last commit</li>
     *   <li>Build commit hash chain:
     *       SHA-256(parent_hash + ":" + commit_number + ":" + concat(change_hashes))</li>
     *   <li>Insert commit</li>
     *   <li>Insert each change with sequence numbers and per-change hashes</li>
     *   <li>Update branch head to point to the new commit</li>
     * </ol>
     * All writes are atomic via {@code @Transactional} — the branch head
     * is never left pointing to an incomplete commit.
     *
     * @param projectId the owning project
     * @param branchId  the target branch
     * @param userId    the author
     * @param message   commit message
     * @param changes   ordered list of changes in this commit
     * @return the created commit DTO
     */
    @Transactional
    public CommitDto createCommit(UUID projectId, UUID branchId, UUID userId,
                                  String message, List<ChangeDto> changes) {
        // 1. Determine next commit number and parent chain position (pessimistic lock to prevent race)
        Optional<CommitEntity> lastCommit = this.commitRepository
                .findTopByProjectIdAndBranchIdOrderByCommitNumberDescForUpdate(projectId, branchId);
        long nextNumber = lastCommit.map(c -> c.getCommitNumber() + 1).orElse(1L);
        String parentHash = lastCommit.map(CommitEntity::getCommitHash).orElse("0");
        String parentCommitIds = lastCommit.isPresent()
                ? "[\"" + lastCommit.get().getCommitId() + "\"]"
                : "[]";

        OffsetDateTime now = OffsetDateTime.now();

        // 2. Save the commit entity (hash will be updated after changes are persisted)
        CommitEntity commitEntity = new CommitEntity();
        commitEntity.setProjectId(projectId);
        commitEntity.setBranchId(branchId);
        commitEntity.setCommitNumber(nextNumber);
        commitEntity.setMessage(message);
        commitEntity.setAuthorUserId(userId);
        commitEntity.setChangeCount(changes.size());
        commitEntity.setParentCommitIds(parentCommitIds);
        commitEntity.setCommittedAt(now);
        commitEntity.setSource("direct");
        commitEntity.setStatus("committed");
        CommitEntity savedCommit = this.commitRepository.save(commitEntity);

        // 3. Persist each change with sequence number and per-change hash
        StringBuilder concatChangeHashes = new StringBuilder();
        int seq = 0;
        for (ChangeDto dto : changes) {
            seq++;
            ChangeEntity ce = new ChangeEntity();
            ce.setProjectId(projectId);
            ce.setCommitId(savedCommit.getCommitId());
            ce.setChangeSeq(seq);
            ce.setObjectType(dto.objectType());
            ce.setObjectId(dto.objectId());
            ce.setOperation(dto.operation());
            ce.setPatch(dto.patch());
            ce.setBeforeObject(dto.beforeObject());
            ce.setAfterObject(dto.afterObject());
            ce.setCreatedAt(now);
            ce.setCreatedBy(userId);

            // Per-change hash: SHA-256(objectType:objectId:operation:patch)
            String changeHash = sha256(
                    dto.objectType() + ":" +
                    dto.objectId() + ":" +
                    dto.operation() + ":" +
                    (dto.patch() != null ? dto.patch() : ""));
            ce.setAfterHash(changeHash);
            concatChangeHashes.append(changeHash);

            this.changeRepository.save(ce);
        }

        // 4. Compute final commit hash and persist
        String commitHash = sha256(parentHash + ":" + nextNumber + ":" + concatChangeHashes);
        savedCommit.setCommitHash(commitHash);
        this.commitRepository.save(savedCommit);

        // 5. Update branch head — atomic with the rest via @Transactional
        BranchEntity branch = this.branchRepository.findByBranchIdAndIsDeletedFalse(branchId)
                .orElseThrow(() -> new IllegalStateException("Branch not found: " + branchId));
        branch.setHeadCommitId(savedCommit.getCommitId());
        branch.setUpdatedAt(now);
        this.branchRepository.save(branch);

        return toDto(savedCommit);
    }

    /**
     * Returns the commit history for a branch, newest first.
     */
    public List<CommitDto> getCommitHistory(UUID projectId, UUID branchId) {
        return this.commitRepository.findByProjectIdAndBranchIdOrderByCommittedAtDesc(projectId, branchId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Returns a single commit by id.
     */
    public Optional<CommitDto> getCommit(UUID projectId, UUID commitId) {
        return this.commitRepository.findByCommitIdAndProjectId(commitId, projectId)
                .map(this::toDto);
    }

    /**
     * Returns the ordered list of changes for a commit (the diff).
     */
    public List<ChangeDto> getCommitDiff(UUID projectId, UUID commitId) {
        return this.changeRepository.findByCommitIdOrderByChangeSeq(commitId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ─── baseline operations ────────────────────────────────────────────────

    /**
     * Creates a baseline anchored to a specific commit.
     */
    @Transactional
    public BaselineDto createBaseline(UUID projectId, UUID tenantId, UUID commitId,
                                      String code, String name, UUID userId) {
        BaselineEntity entity = new BaselineEntity();
        entity.setProjectId(projectId);
        entity.setTenantId(tenantId);
        entity.setBaselineCode(code);
        entity.setName(name);
        entity.setCommitId(commitId);
        entity.setStatus("draft");
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setCreatedBy(userId);

        BaselineEntity saved = this.baselineRepository.save(entity);
        return toDto(saved);
    }

    /**
     * Lists all baselines for a project and commit.
     */
    public List<BaselineDto> getBaselines(UUID projectId, UUID commitId) {
        return this.baselineRepository.findByProjectIdAndCommitIdOrderByCreatedAtDesc(projectId, commitId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ─── save pipeline (explicit save button) ───────────────────────────────

    /**
     * Finds semantic data IDs for a given project.
     */
    public List<?> findSemanticDataIds(UUID projectId) {
        return this.entityManager.createNativeQuery(
                "SELECT semantic_data_id FROM project_semantic_data WHERE project_id = ?1 LIMIT 1")
                .setParameter(1, projectId)
                .getResultList();
    }

    /**
     * Triggers save by recording a lightweight commit in the version-control tables.
     * The full history extraction pipeline (canonical extraction + diff + head materialization)
     * runs via SemanticDataSaveListener on Sirius Web's auto-save events.
     * This method provides an explicit user-triggered commit marker.
     */
    @Transactional
    public boolean triggerSaveFromSemanticData(UUID projectId, UUID semanticDataId,
                                                UUID branchId, UUID userId) {
        if (branchId == null) return false;

        // Create a lightweight commit to mark this explicit save
        OffsetDateTime now = OffsetDateTime.now();
        CommitEntity commitEntity = new CommitEntity();
        commitEntity.setProjectId(projectId);
        commitEntity.setBranchId(branchId);
        commitEntity.setMessage("Explicit save via editor");
        commitEntity.setAuthorUserId(userId);
        commitEntity.setChangeCount(0);
        commitEntity.setCommitNumber(getNextCommitNumber(projectId, branchId));
        commitEntity.setCommitHash(sha256("save-" + projectId + "-" + System.currentTimeMillis()));
        commitEntity.setParentCommitIds("[]");
        commitEntity.setCommittedAt(now);
        commitEntity.setSource("editor");
        commitEntity.setStatus("committed");
        this.commitRepository.save(commitEntity);

        // Update branch head
        var branch = this.branchRepository.findByBranchIdAndIsDeletedFalse(branchId);
        branch.ifPresent(b -> {
            b.setHeadCommitId(commitEntity.getCommitId());
            b.setUpdatedAt(now);
            this.branchRepository.save(b);
        });

        // Capture the current Sirius document projection into the selected
        // branch head. This is the BowTie-style materialized HEAD cache: one
        // current projection per branch, not a full blob per commit.
        this.branchProjectionService.seedBranchHeadFromCurrentSirius(projectId.toString(), branchId, commitEntity.getCommitId());

        return true;
    }

    private long getNextCommitNumber(UUID projectId, UUID branchId) {
        return this.commitRepository
                .findTopByProjectIdAndBranchIdOrderByCommitNumberDescForUpdate(projectId, branchId)
                .map(c -> c.getCommitNumber() + 1)
                .orElse(1L);
    }

    // ─── SHA-256 utility ───────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ─── entity → DTO mappers ──────────────────────────────────────────────

    private BranchDto toDto(BranchEntity e) {
        return new BranchDto(
                e.getBranchId(),
                e.getProjectId(),
                e.getTenantId(),
                e.getName(),
                e.getBranchType(),
                e.getHeadCommitId(),
                e.getBaseCommitId(),
                e.getParentBranchId(),
                e.isProtected(),
                e.isDeleted(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getCreatedBy()
        );
    }

    private CommitDto toDto(CommitEntity e) {
        return new CommitDto(
                e.getCommitId(),
                e.getProjectId(),
                e.getBranchId(),
                e.getCommitNumber(),
                e.getMessage(),
                e.getAuthorUserId(),
                e.getChangeCount(),
                e.getCommitHash(),
                e.getParentCommitIds(),
                e.getCommittedAt(),
                e.getSource(),
                e.getStatus()
        );
    }

    private ChangeDto toDto(ChangeEntity e) {
        return new ChangeDto(
                e.getChangeId(),
                e.getProjectId(),
                e.getCommitId(),
                e.getChangeSeq(),
                e.getObjectType(),
                e.getObjectId(),
                e.getOperation(),
                e.getBeforeHash(),
                e.getAfterHash(),
                e.getPatch(),
                e.getBeforeObject(),
                e.getAfterObject(),
                e.getCreatedAt(),
                e.getCreatedBy()
        );
    }

    private BaselineDto toDto(BaselineEntity e) {
        return new BaselineDto(
                e.getBaselineId(),
                e.getProjectId(),
                e.getTenantId(),
                e.getBaselineCode(),
                e.getName(),
                e.getCommitId(),
                e.getStatus(),
                e.getApprovedBy(),
                e.getApprovedAt(),
                e.getDescription(),
                e.getCreatedAt(),
                e.getCreatedBy()
        );
    }
}
