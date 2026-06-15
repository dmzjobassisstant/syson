package org.eclipse.syson.history.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.sirius.components.core.api.IEditingContext;
import org.eclipse.sirius.web.domain.boundedcontexts.semanticdata.Document;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.eclipse.syson.vc.entity.BranchEntity;
import org.eclipse.syson.vc.repository.BranchRepository;
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
    private final BranchRepository branchRepository;
    private final AuditLogService auditLogService;

    public ModelSaveHistoryService(SysmlCanonicalExtractor sysmlCanonicalExtractor,
                                    SysmlModelDiffService sysmlModelDiffService,
                                    HeadMaterializationService headMaterializationService,
                                    CommitPersistenceService commitPersistenceService,
                                    BranchHeadRepository branchHeadRepository,
                                    BranchRepository branchRepository,
                                    AuditLogService auditLogService) {
        this.sysmlCanonicalExtractor = sysmlCanonicalExtractor;
        this.sysmlModelDiffService = sysmlModelDiffService;
        this.headMaterializationService = headMaterializationService;
        this.commitPersistenceService = commitPersistenceService;
        this.branchHeadRepository = branchHeadRepository;
        this.branchRepository = branchRepository;
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
        if (previousCanonicalJson == null || previousCanonicalJson.isBlank()) {
            return null;
        }
        String previousHash = branchHeadRepository.getCanonicalHash(projectId, branchId);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> root = mapper.readValue(previousCanonicalJson, Map.class);
            List<SysmlCanonicalExtractor.CanonicalElement> elements = new ArrayList<>();
            Object rawElements = root.get("elements");
            if (rawElements instanceof Map<?, ?> elementMap) {
                for (Map.Entry<?, ?> entry : elementMap.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> e) {
                        Map<String, Object> attributes = e.get("attributes") instanceof Map<?, ?> attrs
                                ? (Map<String, Object>) attrs : Map.of();
                        elements.add(new SysmlCanonicalExtractor.CanonicalElement(
                                String.valueOf(entry.getKey()),
                                valueAsString(e.get("id")),
                                valueAsString(e.get("type")),
                                valueAsString(e.get("name")),
                                valueAsString(e.get("owner")),
                                null,
                                attributes,
                                valueAsString(e.get("rawJson")),
                                valueAsString(e.get("hash"))));
                    }
                }
            }
            List<SysmlCanonicalExtractor.CanonicalRelationship> relationships = new ArrayList<>();
            Object rawRelationships = root.get("relationships");
            if (rawRelationships instanceof Map<?, ?> relationshipMap) {
                for (Map.Entry<?, ?> entry : relationshipMap.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> r) {
                        Map<String, Object> attributes = r.get("attributes") instanceof Map<?, ?> attrs
                                ? (Map<String, Object>) attrs : Map.of();
                        relationships.add(new SysmlCanonicalExtractor.CanonicalRelationship(
                                String.valueOf(entry.getKey()),
                                valueAsString(r.get("id")),
                                valueAsString(r.get("type")),
                                valueAsString(r.get("sourceId")),
                                valueAsString(r.get("targetId")),
                                valueAsString(r.get("sourceRole")),
                                valueAsString(r.get("targetRole")),
                                valueAsString(r.get("owner")),
                                attributes,
                                valueAsString(r.get("rawJson")),
                                valueAsString(r.get("hash"))));
                    }
                }
            }
            return new SysmlCanonicalExtractor.CanonicalModelSnapshot(
                    projectId, branchId, elements, relationships, previousCanonicalJson, previousHash);
        } catch (Exception e) {
            return new SysmlCanonicalExtractor.CanonicalModelSnapshot(
                    projectId, branchId, List.of(), List.of(), previousCanonicalJson, previousHash);
        }
    }

    private static String valueAsString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Processes a save from document content strings (non-EMF path).
     * <p>
     * Used by the {@link SemanticDataSaveListener} when the save event
     * provides document content directly rather than an editing context.
     * </p>
     *
     * @param documents set of Sirius documents with content
     * @param projectId the project identifier
     * @param branchId  the branch identifier
     * @param userId    the user performing the save
     */
    public void processSaveFromDocuments(Set<Document> documents, String projectId, UUID branchId, UUID userId) {
        // Convert Set<Document> to Map<UUID, String> for the extractor
        Map<UUID, String> docContents = documents.stream()
                .filter(d -> d.getContent() != null && !d.getContent().isBlank())
                .collect(Collectors.toMap(Document::getId, Document::getContent, (a, b) -> b));

        if (docContents.isEmpty()) {
            return;
        }

        // 1. Extract current snapshot from document content
        SysmlCanonicalExtractor.CanonicalModelSnapshot currentSnapshot =
                sysmlCanonicalExtractor.extractFromDocuments(docContents, projectId, branchId);

        // 2. Load previous head
        SysmlCanonicalExtractor.CanonicalModelSnapshot previousSnapshot = loadPreviousSnapshot(projectId, branchId);

        // 3. Compute diffs
        List<SysmlModelDiffService.ObjectDiff> diffs = sysmlModelDiffService.diff(previousSnapshot, currentSnapshot);

        // 4. If no changes, return early
        if (diffs.isEmpty()) {
            return;
        }

        // 5. Persist commit
        org.eclipse.syson.vc.dto.CommitDto commit = commitPersistenceService.persistCommit(
                UUID.fromString(projectId), branchId, userId, "Model save: " + diffs.size() + " changes", diffs);

        // 6. Materialize HEAD
        headMaterializationService.materializeHead(projectId, branchId, commit.commitId(), currentSnapshot, diffs);

        // 7. Audit log
        auditLogService.log(
                userId.toString(),
                "model_save",
                projectId,
                AuditEventType.MODEL_SAVE,
                "Model saved with " + diffs.size() + " changes"
        );
    }

    /**
     * Processes a save from raw document content rows.
     *
     * @param docContents map of Sirius document IDs to serialized document JSON
     * @param projectId the project identifier
     * @param branchId  the branch identifier
     * @param userId    the user performing the save
     */
    public boolean processSaveFromDocumentContents(Map<UUID, String> docContents, String projectId, UUID branchId, UUID userId) {
        if (docContents == null || docContents.isEmpty()) {
            return false;
        }

        SysmlCanonicalExtractor.CanonicalModelSnapshot currentSnapshot =
                sysmlCanonicalExtractor.extractFromDocuments(docContents, projectId, branchId);
        SysmlCanonicalExtractor.CanonicalModelSnapshot previousSnapshot = loadPreviousSnapshot(projectId, branchId);
        List<SysmlModelDiffService.ObjectDiff> diffs = sysmlModelDiffService.diff(previousSnapshot, currentSnapshot);
        if (diffs.isEmpty()) {
            return false;
        }

        org.eclipse.syson.vc.dto.CommitDto commit = commitPersistenceService.persistCommit(
                UUID.fromString(projectId), branchId, userId, "Model save: " + diffs.size() + " changes", diffs);
        headMaterializationService.materializeHead(projectId, branchId, commit.commitId(), currentSnapshot, diffs);
        auditLogService.log(
                userId.toString(),
                "model_save",
                projectId,
                AuditEventType.MODEL_SAVE,
                "Model saved with " + diffs.size() + " changes"
        );
        return true;
    }

    /**
     * Resolves the main branch ID for a project, creating one if needed.
     *
     * @param projectId the project identifier (String)
     * @return the main branch UUID, or null if no branch exists
     */
    public UUID resolveMainBranchId(String projectId) {
        // Look for a 'main' branch in the syson_branches table
        List<BranchEntity> branches = branchRepository.findByProjectIdAndIsDeletedFalse(UUID.fromString(projectId));
        return branches.stream()
                .filter(b -> "main".equals(b.getName()) || "main".equals(b.getBranchType()))
                .findFirst()
                .map(BranchEntity::getBranchId)
                .orElse(null);
    }
}
