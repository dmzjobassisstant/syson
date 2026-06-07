package org.eclipse.syson.locks.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.syson.history.entity.HeadElement;
import org.eclipse.syson.history.entity.HeadRelationship;
import org.eclipse.syson.history.repository.HeadElementRepository;
import org.eclipse.syson.history.repository.HeadRelationshipRepository;
import org.eclipse.syson.locks.entity.IntegrityCheck;
import org.eclipse.syson.locks.repository.IntegrityCheckRepository;
import org.springframework.stereotype.Service;

/**
 * Performs integrity checks on the HEAD state of a branch. Detects dangling
 * relationships, missing containment owners, cyclic containment, and elements
 * with null SysML types.
 *
 * @author Syson
 */
@Service
public class IntegrityCheckService {

    private final HeadElementRepository headElementRepository;
    private final HeadRelationshipRepository headRelationshipRepository;
    private final IntegrityCheckRepository integrityCheckRepository;

    public IntegrityCheckService(HeadElementRepository headElementRepository,
                                  HeadRelationshipRepository headRelationshipRepository,
                                  IntegrityCheckRepository integrityCheckRepository) {
        this.headElementRepository = headElementRepository;
        this.headRelationshipRepository = headRelationshipRepository;
        this.integrityCheckRepository = integrityCheckRepository;
    }

    /**
     * Runs a full integrity check on the HEAD state of a branch.
     *
     * @param projectId
     *            the project identifier
     * @param branchId
     *            the branch identifier
     * @param userId
     *            the user requesting the check
     * @return the integrity check result
     */
    public IntegrityCheck runCheck(String projectId, UUID branchId, UUID userId) {
        List<HeadElement> elements = headElementRepository.findByProjectIdAndBranchIdAndDeletedFalse(projectId, branchId);
        List<HeadRelationship> relationships = headRelationshipRepository.findByProjectIdAndBranchIdAndDeletedFalse(projectId, branchId);

        // Build index of element stable IDs
        Set<String> elementIds = new HashSet<>();
        for (HeadElement elem : elements) {
            elementIds.add(elem.getStableId());
        }

        List<Map<String, Object>> findings = new ArrayList<>();

        // Check 1: Dangling relationships (source/target exists and not deleted)
        for (HeadRelationship rel : relationships) {
            if (rel.getSourceId() != null && !elementIds.contains(rel.getSourceId())) {
                addFinding(findings, "DANGLING_RELATIONSHIP_SOURCE",
                        "Relationship " + rel.getStableId() + " references non-existent source: " + rel.getSourceId(),
                        "ERROR", rel.getStableId());
            }
            if (rel.getTargetId() != null && !elementIds.contains(rel.getTargetId())) {
                addFinding(findings, "DANGLING_RELATIONSHIP_TARGET",
                        "Relationship " + rel.getStableId() + " references non-existent target: " + rel.getTargetId(),
                        "ERROR", rel.getStableId());
            }
        }

        // Check 2: Missing containment owners
        for (HeadElement elem : elements) {
            if (elem.getOwnerId() != null && !elem.getOwnerId().isEmpty() && !elementIds.contains(elem.getOwnerId())) {
                addFinding(findings, "MISSING_OWNER",
                        "Element " + elem.getStableId() + " references non-existent owner: " + elem.getOwnerId(),
                        "ERROR", elem.getStableId());
            }
        }

        // Check 3: Cyclic containment (warn)
        Map<String, String> ownerMap = new HashMap<>();
        for (HeadElement elem : elements) {
            if (elem.getOwnerId() != null && !elem.getOwnerId().isEmpty()) {
                ownerMap.put(elem.getStableId(), elem.getOwnerId());
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String elemId : ownerMap.keySet()) {
            if (detectCycle(elemId, ownerMap, visited, inStack)) {
                addFinding(findings, "CYCLIC_CONTAINMENT",
                        "Cyclic containment detected involving element: " + elemId,
                        "WARNING", elemId);
            }
        }

        // Check 4: Elements with null sysmlType
        for (HeadElement elem : elements) {
            if (elem.getSysmlType() == null || elem.getSysmlType().isEmpty()) {
                addFinding(findings, "NULL_SYSML_TYPE",
                        "Element " + elem.getStableId() + " has null or empty sysmlType",
                        "WARNING", elem.getStableId());
            }
        }

        // Compute findings hash
        String findingsJson = buildFindingsJson(findings);

        // Create integrity check entity
        IntegrityCheck check = new IntegrityCheck();
        check.setCheckId(UUID.randomUUID());
        check.setProjectId(projectId);
        check.setBranchId(branchId);
        check.setCheckedBy(userId);
        check.setCheckedAt(OffsetDateTime.now());
        check.setErrorCount((int) findings.stream().filter(f -> "ERROR".equals(f.get("severity"))).count());
        check.setWarningCount((int) findings.stream().filter(f -> "WARNING".equals(f.get("severity"))).count());
        check.setFindings(findingsJson);
        check.setStatus(check.getErrorCount() > 0 ? "FAIL" : "PASS");

        integrityCheckRepository.save(check);
        return check;
    }

    /**
     * Detects cycles in the containment graph using DFS.
     */
    private boolean detectCycle(String nodeId, Map<String, String> ownerMap,
                                 Set<String> visited, Set<String> inStack) {
        if (inStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }
        visited.add(nodeId);
        inStack.add(nodeId);

        String owner = ownerMap.get(nodeId);
        if (owner != null && ownerMap.containsKey(owner)) {
            if (detectCycle(owner, ownerMap, visited, inStack)) {
                return true;
            }
        }

        inStack.remove(nodeId);
        return false;
    }

    private void addFinding(List<Map<String, Object>> findings, String code, String message,
                             String severity, String elementId) {
        Map<String, Object> finding = new HashMap<>();
        finding.put("code", code);
        finding.put("message", message);
        finding.put("severity", severity);
        finding.put("elementId", elementId);
        findings.add(finding);
    }

    private String buildFindingsJson(List<Map<String, Object>> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Map<String, Object> finding : findings) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{');
            boolean firstField = true;
            for (Map.Entry<String, Object> entry : finding.entrySet()) {
                if (!firstField) {
                    sb.append(',');
                }
                firstField = false;
                sb.append('"').append(entry.getKey()).append("\":\"")
                  .append(entry.getValue() != null ? escapeJson(entry.getValue().toString()) : "null")
                  .append('"');
            }
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
