package org.eclipse.syson.warehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.syson.auth.AdminService;
import org.eclipse.syson.vc.entity.BranchEntity;
import org.eclipse.syson.vc.entity.ChangeEntity;
import org.eclipse.syson.vc.entity.CommitEntity;
import org.eclipse.syson.vc.repository.BranchRepository;
import org.eclipse.syson.vc.repository.ChangeRepository;
import org.eclipse.syson.vc.repository.CommitRepository;
import org.eclipse.syson.warehouse.entity.BranchHeadEntity;
import org.eclipse.syson.warehouse.entity.CommitParentEntity;
import org.eclipse.syson.warehouse.entity.HeadElementEntity;
import org.eclipse.syson.warehouse.entity.HeadElementId;
import org.eclipse.syson.warehouse.entity.HeadRelationshipEntity;
import org.eclipse.syson.warehouse.entity.HeadRelationshipId;
import org.eclipse.syson.warehouse.entity.ObjectVersionEntity;
import org.eclipse.syson.warehouse.entity.ObjectVersionEntity.ObjectVersionId;
import org.eclipse.syson.warehouse.repository.BranchHeadRepository;
import org.eclipse.syson.warehouse.repository.CommitParentRepository;
import org.eclipse.syson.warehouse.repository.HeadElementRepository;
import org.eclipse.syson.warehouse.repository.HeadRelationshipRepository;
import org.eclipse.syson.warehouse.repository.ObjectVersionRepository;
import org.eclipse.syson.warehouse.service.CanonicalExtractor.CanonicalElement;
import org.eclipse.syson.warehouse.service.CanonicalExtractor.CanonicalRelationship;
import org.eclipse.syson.warehouse.service.CanonicalExtractor.CanonicalSnapshot;
import org.eclipse.syson.warehouse.service.ModelDiffService.DiffResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the element warehouse commit pipeline:
 * extract → diff → commit → head materialization → object versions.
 */
@Service
public class CommitService {

    private static final Logger logger = LoggerFactory.getLogger(CommitService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CanonicalExtractor extractor;
    private final ModelDiffService diffService;
    private final HeadElementRepository headElementRepo;
    private final HeadRelationshipRepository headRelationshipRepo;
    private final BranchHeadRepository branchHeadRepo;
    private final CommitRepository commitRepo;
    private final ChangeRepository changeRepo;
    private final BranchRepository branchRepo;
    private final CommitParentRepository commitParentRepo;
    private final ObjectVersionRepository objectVersionRepo;

    public CommitService(CanonicalExtractor extractor, ModelDiffService diffService,
                         HeadElementRepository headElementRepo, HeadRelationshipRepository headRelationshipRepo,
                         BranchHeadRepository branchHeadRepo, CommitRepository commitRepo,
                         ChangeRepository changeRepo, BranchRepository branchRepo,
                         CommitParentRepository commitParentRepo, ObjectVersionRepository objectVersionRepo) {
        this.extractor = extractor;
        this.diffService = diffService;
        this.headElementRepo = headElementRepo;
        this.headRelationshipRepo = headRelationshipRepo;
        this.branchHeadRepo = branchHeadRepo;
        this.commitRepo = commitRepo;
        this.changeRepo = changeRepo;
        this.branchRepo = branchRepo;
        this.commitParentRepo = commitParentRepo;
        this.objectVersionRepo = objectVersionRepo;
    }

    /**
     * Main entry point: extract, diff, commit, and materialize head state.
     * Returns the commit ID if changes were made, or empty if no-op.
     */
    @Transactional
    public Optional<UUID> commitEditingContext(IEditingContext editingContext,
                                                String projectId, UUID branchId, UUID userId) {
        Instant start = Instant.now();

        // 1. Extract new canonical snapshot
        CanonicalSnapshot snapshot = extractor.extract(editingContext, projectId, branchId);

        // 2. Load previous head state
        List<CanonicalElement> prevElements = loadPreviousElements(projectId, branchId);
        List<CanonicalRelationship> prevRelationships = loadPreviousRelationships(projectId, branchId);

        // 3. Diff
        List<DiffResult> elementDiffs = diffService.diffElements(prevElements, snapshot.elements());
        List<DiffResult> relDiffs = diffService.diffRelationships(prevRelationships, snapshot.relationships());

        List<DiffResult> allDiffs = new ArrayList<>();
        allDiffs.addAll(elementDiffs);
        allDiffs.addAll(relDiffs);

        // 4. Skip if no changes
        if (allDiffs.isEmpty()) {
            logger.debug("No changes detected for project={}, branch={}; skipping commit", projectId, branchId);
            return Optional.empty();
        }

        // 5. Resolve/create branch
        BranchEntity branch = resolveOrCreateBranch(projectId, branchId, userId);

        // 6. Create commit
        long commitNumber = nextCommitNumber(projectId, branchId);
        UUID parentCommitId = branch.getHeadCommitId();

        CommitEntity commit = new CommitEntity();
        commit.setProjectId(UUID.fromString(projectId));
        commit.setBranchId(branchId);
        commit.setCommitNumber(commitNumber);
        commit.setMessage("Auto-extracted: " + allDiffs.size() + " changes");
        commit.setAuthorUserId(userId);
        commit.setChangeCount(allDiffs.size());
        commit.setCommitHash(StableIdService.sha256(projectId + ":" + branchId + ":" + commitNumber));
        commit.setSource("warehouse");
        commitRepo.save(commit);

        // 7. Write commit parent
        if (parentCommitId != null) {
            commitParentRepo.save(new CommitParentEntity(commit.getCommitId(), parentCommitId, 1));
        }

        // 8. Write changes
        int seq = 1;
        for (DiffResult diff : allDiffs) {
            ChangeEntity change = new ChangeEntity();
            change.setProjectId(UUID.fromString(projectId)); // V4 uses UUID
            change.setCommitId(commit.getCommitId());
            change.setChangeSeq(seq++);
            change.setObjectType(diff.objectType());
            change.setObjectId(UUID.nameUUIDFromBytes(diff.stableObjectId().getBytes()));
            change.setOperation(diff.operation());
            change.setBeforeHash(diff.beforeObject() != null ? StableIdService.sha256(diff.beforeObject()) : null);
            change.setAfterHash(diff.afterObject() != null ? StableIdService.sha256(diff.afterObject()) : null);
            change.setBeforeObject(diff.beforeObject());
            change.setAfterObject(diff.afterObject());
            change.setProjectRef(projectId);
            change.setBranchId(branchId);
            change.setStableObjectId(diff.stableObjectId());
            change.setChangedFields(toJson(diff.changedFields()));
            change.setCreatedBy(userId);
            changeRepo.save(change);
        }

        // 9. Materialize head elements
        materializeElements(projectId, branchId, snapshot.elements(), commit.getCommitId());
        materializeRelationships(projectId, branchId, snapshot.relationships(), commit.getCommitId());

        // 10. Update object versions
        updateObjectVersions(projectId, snapshot, allDiffs, commit.getCommitId(), commitNumber);

        // 11. Update branch head
        branch.setHeadCommitId(commit.getCommitId());
        branchRepo.save(branch);

        // 12. Update branch head cache
        updateBranchHeadCache(projectId, branchId, commit.getCommitId(), snapshot);

        long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
        logger.info("Warehouse commit {} for project={}: {} changes in {}ms",
            commit.getCommitId(), projectId, allDiffs.size(), elapsed);

        return Optional.of(commit.getCommitId());
    }

    private List<CanonicalElement> loadPreviousElements(String projectId, UUID branchId) {
        return headElementRepo.findByProjectIdAndBranchIdAndDeletedFalse(projectId, branchId)
            .stream()
            .map(e -> new CanonicalElement(
                e.getId().getStableId(), e.getSysmlType(), e.getName(), e.getBody(),
                e.getOwnerStableId(), e.getQualifiedName(), e.getDocumentId(),
                Map.of(), e.getRawObject(), e.getObjectHash()
            ))
            .toList();
    }

    private List<CanonicalRelationship> loadPreviousRelationships(String projectId, UUID branchId) {
        return headRelationshipRepo.findByProjectIdAndBranchIdAndDeletedFalse(projectId, branchId)
            .stream()
            .map(r -> new CanonicalRelationship(
                r.getId().getStableId(), r.getRelType(), r.getSourceStableId(), r.getTargetStableId(),
                r.getSourceRole(), r.getTargetRole(), Map.of(), r.getRawObject(), r.getObjectHash()
            ))
            .toList();
    }

    private void materializeElements(String projectId, UUID branchId,
                                      List<CanonicalElement> elements, UUID commitId) {
        // Mark all existing as deleted first
        var existing = headElementRepo.findByProjectIdAndBranchIdAndDeletedFalse(projectId, branchId);
        for (var e : existing) {
            e.setDeleted(true);
            e.setDeletedCommitId(commitId);
        }
        headElementRepo.saveAll(existing);

        // Upsert current elements
        Timestamp now = Timestamp.from(Instant.now());
        for (CanonicalElement ce : elements) {
            HeadElementId id = new HeadElementId(projectId, branchId, ce.stableId());
            HeadElementEntity entity = headElementRepo.findById(id).orElse(new HeadElementEntity());
            entity.setId(id);
            entity.setSysmlType(ce.sysmlType());
            entity.setName(ce.name());
            entity.setBody(ce.body());
            entity.setOwnerStableId(ce.ownerStableId());
            entity.setQualifiedName(ce.qualifiedName());
            entity.setDocumentId(ce.documentId());
            entity.setAttributes(toJson(ce.attributes()));
            entity.setRawObject(ce.rawObject());
            entity.setObjectHash(ce.objectHash());
            entity.setDeleted(false);
            entity.setDeletedCommitId(null);
            if (entity.getCreatedCommitId() == null) {
                entity.setCreatedCommitId(commitId);
            }
            entity.setUpdatedCommitId(commitId);
            entity.setUpdatedAt(now);
            headElementRepo.save(entity);
        }
    }

    private void materializeRelationships(String projectId, UUID branchId,
                                           List<CanonicalRelationship> rels, UUID commitId) {
        var existing = headRelationshipRepo.findByProjectIdAndBranchIdAndDeletedFalse(projectId, branchId);
        for (var r : existing) {
            r.setDeleted(true);
            r.setDeletedCommitId(commitId);
        }
        headRelationshipRepo.saveAll(existing);

        Timestamp now = Timestamp.from(Instant.now());
        for (CanonicalRelationship cr : rels) {
            HeadRelationshipId id = new HeadRelationshipId(projectId, branchId, cr.stableId());
            HeadRelationshipEntity entity = headRelationshipRepo.findById(id).orElse(new HeadRelationshipEntity());
            entity.setId(id);
            entity.setRelType(cr.relType());
            entity.setSourceStableId(cr.sourceStableId());
            entity.setTargetStableId(cr.targetStableId());
            entity.setSourceRole(cr.sourceRole());
            entity.setTargetRole(cr.targetRole());
            entity.setDeleted(false);
            entity.setDeletedCommitId(null);
            if (entity.getCreatedCommitId() == null) {
                entity.setCreatedCommitId(commitId);
            }
            entity.setUpdatedCommitId(commitId);
            entity.setUpdatedAt(now);
            entity.setObjectHash(cr.objectHash());
            entity.setRawObject(cr.rawObject());
            headRelationshipRepo.save(entity);
        }
    }

    private void updateObjectVersions(String projectId, CanonicalSnapshot snapshot,
                                       List<DiffResult> diffs, UUID commitId, long commitNumber) {
        for (DiffResult diff : diffs) {
            // Mark previous current as not current
            ObjectVersionEntity prev = objectVersionRepo.findCurrentVersion(
                projectId, diff.stableObjectId(), diff.objectType());
            if (prev != null) {
                prev.setCurrent(false);
                prev.setValidToCommitNumber(commitNumber - 1);
                objectVersionRepo.save(prev);
            }

            // Create new version
            ObjectVersionId id = new ObjectVersionId(projectId, diff.objectType(), diff.stableObjectId(), commitId);
            ObjectVersionEntity version = new ObjectVersionEntity();
            version.setId(id);
            version.setValidFromCommitNumber(commitNumber);
            version.setCurrent(true);
            version.setObjectHash(diff.afterObject() != null ? StableIdService.sha256(diff.afterObject()) : "");
            version.setObjectJson(diff.afterObject() != null ? diff.afterObject() : "{}");
            version.setCreatedAt(Timestamp.from(Instant.now()));
            objectVersionRepo.save(version);
        }
    }

    private void updateBranchHeadCache(String projectId, UUID branchId, UUID commitId, CanonicalSnapshot snapshot) {
        BranchHeadEntity.BranchHeadId bhId = new BranchHeadEntity.BranchHeadId(projectId, branchId);
        BranchHeadEntity bh = branchHeadRepo.findById(bhId).orElse(new BranchHeadEntity());
        bh.setId(bhId);
        bh.setHeadCommitId(commitId);
        bh.setCanonicalHash(snapshot.canonicalHash());
        bh.setObjectCount(snapshot.elements().size());
        bh.setRelationshipCount(snapshot.relationships().size());
        bh.setLastExtractedAt(Timestamp.from(Instant.now()));
        branchHeadRepo.save(bh);
    }

    private BranchEntity resolveOrCreateBranch(String projectId, UUID branchId, UUID userId) {
        Optional<BranchEntity> existing = branchRepo.findByBranchIdAndIsDeletedFalse(branchId);
        if (existing.isPresent()) return existing.get();

        BranchEntity branch = new BranchEntity();
        branch.setBranchId(branchId);
        branch.setProjectId(UUID.fromString(projectId));
        branch.setTenantId(UUID.fromString(projectId)); // Default tenant = project
        branch.setName("main");
        branch.setBranchType("main");
        branch.setCreatedBy(userId);
        branchRepo.save(branch);
        return branch;
    }

    private long nextCommitNumber(String projectId, UUID branchId) {
        // Use timestamp-based number for simplicity
        return System.currentTimeMillis();
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
