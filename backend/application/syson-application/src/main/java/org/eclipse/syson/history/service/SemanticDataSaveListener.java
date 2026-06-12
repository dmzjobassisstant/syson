package org.eclipse.syson.history.service;

import java.util.UUID;

import org.eclipse.sirius.web.domain.boundedcontexts.semanticdata.SemanticData;
import org.eclipse.sirius.web.domain.boundedcontexts.semanticdata.events.SemanticDataUpdatedEvent;
import org.eclipse.syson.auth.service.AuditLogService;
import org.eclipse.syson.settings.ProjectSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for Sirius Web {@link SemanticDataUpdatedEvent} and triggers
 * the enterprise model history pipeline: extract → diff → commit → materialize head.
 * <p>
 * Shadow mode: if extraction fails, the error is logged and audited but
 * the editor save is NOT blocked.
 * </p>
 *
 * @author syson-team
 */
@Service
public class SemanticDataSaveListener {

    private static final Logger logger = LoggerFactory.getLogger(SemanticDataSaveListener.class);

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ModelSaveHistoryService modelSaveHistoryService;
    private final AuditLogService auditLogService;
    private final jakarta.persistence.EntityManager entityManager;
    private final ProjectSettingService projectSettingService;

    public SemanticDataSaveListener(
            ModelSaveHistoryService modelSaveHistoryService,
            AuditLogService auditLogService,
            jakarta.persistence.EntityManager entityManager,
            ProjectSettingService projectSettingService) {
        this.modelSaveHistoryService = modelSaveHistoryService;
        this.auditLogService = auditLogService;
        this.entityManager = entityManager;
        this.projectSettingService = projectSettingService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener
    public void onSemanticDataUpdated(SemanticDataUpdatedEvent event) {
        SemanticData semanticData = event.semanticData();
        UUID semanticDataId = semanticData.getId();

        try {
            // Look up the project this semantic data belongs to via native query
            String projectId = findProjectIdBySemanticDataId(semanticDataId);

            if (projectId == null) {
                logger.debug("No project found for semantic data {}; skipping history extraction", semanticDataId);
                return;
            }

            // Resolve the active branch for this project. The editor branch
            // selector stores it in project settings; fall back to main only
            // when no active branch has been selected yet.
            UUID branchId = resolveActiveBranchId(projectId);
            if (branchId == null) {
                branchId = this.modelSaveHistoryService.resolveMainBranchId(projectId);
            }

            if (branchId == null) {
                // Auto-create a default 'main' branch for this project
                UUID tenantId = SYSTEM_USER_ID; // default tenant
                this.modelSaveHistoryService.ensureDefaultBranch(projectId, tenantId, SYSTEM_USER_ID);
                branchId = this.modelSaveHistoryService.resolveMainBranchId(projectId);
                if (branchId != null) {
                    logger.info("Auto-created default 'main' branch for project {}", projectId);
                }
            }

            if (branchId == null) {
                logger.warn("Could not create main branch for project {}; skipping history extraction", projectId);
                return;
            }

            // Process the save through the history pipeline
            this.modelSaveHistoryService.processSaveFromDocuments(
                    semanticData.getDocuments(),
                    projectId,
                    branchId,
                    SYSTEM_USER_ID);

            logger.info("History extraction completed for semanticData={}, project={}, branch={}",
                    semanticDataId, projectId, branchId);

        } catch (Exception e) {
            // Shadow mode: log and audit the failure, but do NOT block the editor save
            logger.warn("History extraction failed for semanticData={}: {}", semanticDataId, e.getMessage(), e);
            try {
                this.auditLogService.record(
                        "model.history.extraction_failed",
                        SYSTEM_USER_ID,
                        "semantic_data",
                        semanticDataId.toString(),
                        "warning",
                        "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception auditEx) {
                logger.warn("Failed to audit history extraction failure: {}", auditEx.getMessage());
            }
        }
    }

    private UUID resolveActiveBranchId(String projectId) {
        String branchIdStr = this.projectSettingService.get(projectId, "default_branch_id", "");
        if (branchIdStr == null || branchIdStr.isBlank()) {
            return null;
        }
        if (branchIdStr.startsWith("\"") && branchIdStr.endsWith("\"")) {
            branchIdStr = branchIdStr.substring(1, branchIdStr.length() - 1);
        }
        try {
            return UUID.fromString(branchIdStr);
        } catch (Exception e) {
            logger.debug("Invalid active branch setting for project {}: {}", projectId, branchIdStr);
            return null;
        }
    }

    private String findProjectIdBySemanticDataId(UUID semanticDataId) {
        try {
            var results = this.entityManager.createNativeQuery(
                    "SELECT project_id FROM project_semantic_data WHERE semantic_data_id = ?1 LIMIT 1")
                    .setParameter(1, semanticDataId)
                    .getResultList();
            if (!results.isEmpty()) {
                return results.get(0).toString();
            }
        } catch (Exception e) {
            logger.debug("Failed to look up project for semantic data {}: {}", semanticDataId, e.getMessage());
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
