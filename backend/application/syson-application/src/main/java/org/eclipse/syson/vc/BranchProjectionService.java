package org.eclipse.syson.vc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.eclipse.syson.history.service.SysmlCanonicalExtractor;
import org.eclipse.syson.settings.ProjectSettingService;
import org.eclipse.syson.vc.entity.BranchEntity;
import org.eclipse.syson.vc.repository.BranchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Bridges the enterprise branch/head-table sidecar with Sirius Web's blob based
 * editor contract. The sidecar remains branch-specific and object-level, while
 * this service projects the selected branch HEAD back into the current Sirius
 * {@code document.content} rows so the stock editor can load it.
 */
@Service
@Transactional
public class BranchProjectionService {

    private final EntityManager entityManager;

    private final BranchRepository branchRepository;

    private final BranchHeadRepository branchHeadRepository;

    private final SysmlCanonicalExtractor sysmlCanonicalExtractor;

    private final ProjectSettingService projectSettingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BranchProjectionService(EntityManager entityManager,
                                   BranchRepository branchRepository,
                                   BranchHeadRepository branchHeadRepository,
                                   SysmlCanonicalExtractor sysmlCanonicalExtractor,
                                   ProjectSettingService projectSettingService) {
        this.entityManager = entityManager;
        this.branchRepository = branchRepository;
        this.branchHeadRepository = branchHeadRepository;
        this.sysmlCanonicalExtractor = sysmlCanonicalExtractor;
        this.projectSettingService = projectSettingService;
    }

    public ApplyBranchResult applyBranch(UUID projectId, UUID branchId, UUID userId) {
        BranchEntity branch = this.branchRepository.findByBranchIdAndIsDeletedFalse(branchId)
                .filter(b -> projectId.equals(b.getProjectId()))
                .orElseThrow(() -> new IllegalArgumentException("Branch not found for project: " + branchId));

        String projectRef = projectId.toString();
        String canonicalJson = this.branchHeadRepository.getCanonicalJson(projectRef, branchId);
        boolean seeded = false;
        if (!containsSiriusDocuments(canonicalJson)) {
            canonicalJson = seedBranchHeadFromCurrentSirius(projectRef, branchId);
            seeded = true;
        }

        int appliedDocuments = applyCanonicalProjectionToSiriusDocuments(projectRef, canonicalJson);
        setActiveBranch(projectRef, branchId, userId);
        return new ApplyBranchResult(branch.getBranchId(), branch.getName(), appliedDocuments, seeded);
    }

    public void setActiveBranch(String projectId, UUID branchId, UUID userId) {
        // ProjectSettingService stores JSONB values. Keep the existing string-json convention.
        this.projectSettingService.set(projectId, "default_branch_id", "\"" + branchId + "\"",
                "Active branch for Sirius editor projection and sidecar saves", userId);
    }

    public String seedBranchHeadFromCurrentSirius(String projectId, UUID branchId) {
        return seedBranchHeadFromCurrentSirius(projectId, branchId, null);
    }

    public String seedBranchHeadFromCurrentSirius(String projectId, UUID branchId, UUID commitId) {
        Map<UUID, String> documents = loadCurrentSiriusDocuments(projectId);
        if (documents.isEmpty()) {
            throw new IllegalStateException("No Sirius documents found for project " + projectId);
        }
        SysmlCanonicalExtractor.CanonicalModelSnapshot snapshot = this.sysmlCanonicalExtractor
                .extractFromDocuments(documents, projectId, branchId);
        this.branchHeadRepository.upsertBranchHead(projectId, branchId, commitId, snapshot.canonicalHash(),
                snapshot.canonicalJson(), snapshot.elements().size(), snapshot.relationships().size(), 0);
        return snapshot.canonicalJson();
    }

    public Map<UUID, String> loadCurrentSiriusDocuments(String projectId) {
        @SuppressWarnings("unchecked")
        var rows = this.entityManager.createNativeQuery("""
                SELECT d.id, d.content
                FROM project_semantic_data psd
                JOIN document d ON d.semantic_data_id = psd.semantic_data_id
                WHERE psd.project_id = ?1
                ORDER BY d.name, d.id
                """)
                .setParameter(1, projectId)
                .getResultList();
        Map<UUID, String> documents = new LinkedHashMap<>();
        for (Object rowObj : rows) {
            Object[] row = (Object[]) rowObj;
            if (row[0] != null && row[1] != null) {
                documents.put((UUID) row[0], row[1].toString());
            }
        }
        return documents;
    }

    private int applyCanonicalProjectionToSiriusDocuments(String projectId, String canonicalJson) {
        Map<String, String> documents = extractSiriusDocuments(canonicalJson);
        if (documents.isEmpty()) {
            throw new IllegalStateException("Selected branch has no Sirius document projection");
        }
        int updated = 0;
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            int count = this.entityManager.createNativeQuery("""
                    UPDATE document d
                    SET content = ?1, last_modified_on = CURRENT_TIMESTAMP
                    FROM project_semantic_data psd
                    WHERE d.semantic_data_id = psd.semantic_data_id
                      AND psd.project_id = ?2
                      AND d.id = CAST(?3 AS uuid)
                    """)
                    .setParameter(1, entry.getValue())
                    .setParameter(2, projectId)
                    .setParameter(3, entry.getKey())
                    .executeUpdate();
            updated += count;
        }
        if (updated == 0) {
            throw new IllegalStateException("Branch projection did not match any Sirius documents");
        }
        // Touch semantic_data/project join timestamps so Sirius cache invalidation sees activity.
        this.entityManager.createNativeQuery("""
                UPDATE project_semantic_data
                SET last_modified_on = CURRENT_TIMESTAMP
                WHERE project_id = ?1
                """)
                .setParameter(1, projectId)
                .executeUpdate();
        return updated;
    }

    private boolean containsSiriusDocuments(String canonicalJson) {
        return !extractSiriusDocuments(canonicalJson).isEmpty();
    }

    private Map<String, String> extractSiriusDocuments(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> root = this.objectMapper.readValue(canonicalJson, new TypeReference<>() {});
            Object docs = root.get("siriusDocuments");
            if (!(docs instanceof Map<?, ?> rawDocs)) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawDocs.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record ApplyBranchResult(UUID branchId, String name, int appliedDocuments, boolean seededFromCurrentSirius) {
    }
}
