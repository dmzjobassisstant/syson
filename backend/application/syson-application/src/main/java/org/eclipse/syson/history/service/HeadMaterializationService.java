package org.eclipse.syson.history.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.history.entity.HeadElement;
import org.eclipse.syson.history.entity.HeadRelationship;
import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.eclipse.syson.history.repository.HeadElementRepository;
import org.eclipse.syson.history.repository.HeadRelationshipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes the HEAD state of a branch by applying diffs to the head
 * element and relationship tables. Maintains the current state of each
 * branch for fast querying.
 *
 * @author Syson
 */
@Service
@Transactional
public class HeadMaterializationService {

    private final HeadElementRepository headElementRepository;
    private final HeadRelationshipRepository headRelationshipRepository;
    private final BranchHeadRepository branchHeadRepository;
    private final SysmlObjectHasher sysmlObjectHasher;

    public HeadMaterializationService(HeadElementRepository headElementRepository,
                                       HeadRelationshipRepository headRelationshipRepository,
                                       BranchHeadRepository branchHeadRepository,
                                       SysmlObjectHasher sysmlObjectHasher) {
        this.headElementRepository = headElementRepository;
        this.headRelationshipRepository = headRelationshipRepository;
        this.branchHeadRepository = branchHeadRepository;
        this.sysmlObjectHasher = sysmlObjectHasher;
    }

    /**
     * Materializes the HEAD state by applying diffs to the persistent storage.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param commitId
     *            the commit identifier
     * @param snapshot
     *            the canonical model snapshot
     * @param diffs
     *            the list of diffs to apply
     */
    public void materializeHead(String projectId, UUID branchId, UUID commitId,
                                 SysmlCanonicalExtractor.CanonicalModelSnapshot snapshot,
                                 List<SysmlModelDiffService.ObjectDiff> diffs) {
        int createdCount = 0;
        int updatedCount = 0;
        int deletedCount = 0;

        for (SysmlModelDiffService.ObjectDiff diff : diffs) {
            switch (diff.operation()) {
                case "CREATE" -> {
                    handleCreate(projectId, branchId, commitId, snapshot, diff);
                    createdCount++;
                }
                case "UPDATE" -> {
                    handleUpdate(projectId, branchId, commitId, snapshot, diff);
                    updatedCount++;
                }
                case "DELETE" -> {
                    handleDelete(projectId, branchId, commitId, diff);
                    deletedCount++;
                }
            }
        }

        // Upsert branch head record
        upsertBranchHead(projectId, branchId, commitId, snapshot.canonicalHash(),
                createdCount, updatedCount, deletedCount);
    }

    private void handleCreate(String projectId, UUID branchId, UUID commitId,
                               SysmlCanonicalExtractor.CanonicalModelSnapshot snapshot,
                               SysmlModelDiffService.ObjectDiff diff) {
        // Determine if this is a relationship or element by looking it up in the snapshot
        SysmlCanonicalExtractor.CanonicalRelationship relationship = findRelationship(snapshot, diff.stableId());
        if (relationship != null) {
            HeadRelationship rel = new HeadRelationship();
            rel.setProjectId(projectId);
            rel.setBranchId(branchId);
            rel.setStableId(relationship.stableId());
            rel.setRelationshipId(relationship.relationshipId());
            rel.setRelType(relationship.relType());
            rel.setSourceId(relationship.sourceId());
            rel.setTargetId(relationship.targetId());
            rel.setSourceRole(relationship.sourceRole());
            rel.setTargetRole(relationship.targetRole());
            rel.setOwnerId(relationship.ownerId());
            rel.setAttributes(relationship.attributes() != null ? sysmlObjectHasher.canonicalizeJson(relationship.attributes()) : "{}");
            rel.setObjectHash(relationship.objectHash());
            rel.setCreatedCommitId(commitId);
            rel.setDeleted(false);
            headRelationshipRepository.save(rel);
        } else {
            SysmlCanonicalExtractor.CanonicalElement element = findElement(snapshot, diff.stableId());
            if (element != null) {
                HeadElement elem = new HeadElement();
                elem.setProjectId(projectId);
                elem.setBranchId(branchId);
                elem.setStableId(element.stableId());
                elem.setElementId(element.elementId());
                elem.setSysmlType(element.sysmlType());
                elem.setName(element.name());
                elem.setOwnerId(element.ownerId());
                elem.setQualifiedName(element.qualifiedName());
                elem.setAttributes(element.attributes() != null ? sysmlObjectHasher.canonicalizeJson(element.attributes()) : "{}");
                elem.setObjectHash(element.objectHash());
                elem.setCreatedCommitId(commitId);
                elem.setDeleted(false);
                headElementRepository.save(elem);
            }
        }
    }

    private void handleUpdate(String projectId, UUID branchId, UUID commitId,
                               SysmlCanonicalExtractor.CanonicalModelSnapshot snapshot,
                               SysmlModelDiffService.ObjectDiff diff) {
        SysmlCanonicalExtractor.CanonicalRelationship relationship = findRelationship(snapshot, diff.stableId());
        if (relationship != null) {
            HeadRelationship existing = headRelationshipRepository.findByProjectIdAndBranchIdAndStableId(projectId, branchId, diff.stableId()).orElse(null);
            if (existing != null) {
                existing.setRelType(relationship.relType());
                existing.setSourceId(relationship.sourceId());
                existing.setTargetId(relationship.targetId());
                existing.setSourceRole(relationship.sourceRole());
                existing.setTargetRole(relationship.targetRole());
                existing.setOwnerId(relationship.ownerId());
                existing.setAttributes(relationship.attributes() != null ? sysmlObjectHasher.canonicalizeJson(relationship.attributes()) : "{}");
                existing.setObjectHash(relationship.objectHash());
                existing.setUpdatedCommitId(commitId);
                headRelationshipRepository.save(existing);
            }
        } else {
            SysmlCanonicalExtractor.CanonicalElement element = findElement(snapshot, diff.stableId());
            if (element != null) {
                HeadElement existing = headElementRepository.findByProjectIdAndBranchIdAndStableId(projectId, branchId, diff.stableId()).orElse(null);
                if (existing != null) {
                    existing.setName(element.name());
                    existing.setOwnerId(element.ownerId());
                    existing.setQualifiedName(element.qualifiedName());
                    existing.setAttributes(element.attributes() != null ? sysmlObjectHasher.canonicalizeJson(element.attributes()) : "{}");
                    existing.setObjectHash(element.objectHash());
                    existing.setUpdatedCommitId(commitId);
                    headElementRepository.save(existing);
                }
            }
        }
    }

    private void handleDelete(String projectId, UUID branchId, UUID commitId,
                               SysmlModelDiffService.ObjectDiff diff) {
        HeadElement elem = headElementRepository.findByProjectIdAndBranchIdAndStableId(projectId, branchId, diff.stableId()).orElse(null);
        if (elem != null) {
            elem.setDeleted(true);
            elem.setDeletedCommitId(commitId);
            headElementRepository.save(elem);
            return;
        }

        HeadRelationship rel = headRelationshipRepository.findByProjectIdAndBranchIdAndStableId(projectId, branchId, diff.stableId()).orElse(null);
        if (rel != null) {
            rel.setDeleted(true);
            rel.setDeletedCommitId(commitId);
            headRelationshipRepository.save(rel);
        }
    }

    private void upsertBranchHead(String projectId, UUID branchId, UUID commitId,
                                   String canonicalHash, int created, int updated, int deleted) {
        branchHeadRepository.upsertBranchHead(projectId, branchId, commitId, canonicalHash, created, updated, deleted);
    }

    private SysmlCanonicalExtractor.CanonicalElement findElement(SysmlCanonicalExtractor.CanonicalModelSnapshot snapshot, String stableId) {
        return snapshot.elements().stream()
                .filter(e -> e.stableId().equals(stableId))
                .findFirst()
                .orElse(null);
    }

    private SysmlCanonicalExtractor.CanonicalRelationship findRelationship(SysmlCanonicalExtractor.CanonicalModelSnapshot snapshot, String stableId) {
        return snapshot.relationships().stream()
                .filter(r -> r.stableId().equals(stableId))
                .findFirst()
                .orElse(null);
    }
}
