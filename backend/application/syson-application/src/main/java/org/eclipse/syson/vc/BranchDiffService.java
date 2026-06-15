package org.eclipse.syson.vc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Element-level diff engine for SysON version control.
 * <p>
 * Compares two canonical JSON snapshots (as stored in
 * {@code syson_branch_heads.canonical_json}) and produces a structured
 * {@link DiffResult} with per-element and per-relationship changes.
 * <p>
 * Inspired by bowtie-pilot's {@code canonical-diff.ts} but adapted for
 * SysON's canonical JSON shape.
 */
@Service
public class BranchDiffService {

    private final BranchHeadRepository branchHeadRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BranchDiffService(BranchHeadRepository branchHeadRepository, EntityManager entityManager) {
        this.branchHeadRepository = branchHeadRepository;
        this.entityManager = entityManager;
    }

    /**
     * Compute a diff between two canonical JSON snapshots.
     *
     * @param baseJson   the "before" canonical JSON (e.g. baseline or parent HEAD)
     * @param targetJson the "after" canonical JSON (e.g. current branch HEAD)
     * @return structured diff result
     */
    public DiffResult diff(String baseJson, String targetJson) {
        Map<String, Map<String, Object>> base = parseCanonical(baseJson);
        Map<String, Map<String, Object>> target = parseCanonical(targetJson);

        List<DiffEntry> entries = new ArrayList<>();
        int added = 0, modified = 0, removed = 0, unchanged = 0;

        // Diff elements and relationships
        for (String objectType : List.of("elements", "relationships")) {
            Map<String, Object> baseObjects = extractTypedMap(base, objectType);
            Map<String, Object> targetObjects = extractTypedMap(target, objectType);

            // All IDs from both sides
            Map<String, Object> allIds = new TreeMap<>();
            allIds.putAll(baseObjects);
            allIds.putAll(targetObjects);

            for (String objectId : allIds.keySet()) {
                Object baseObj = baseObjects.get(objectId);
                Object targetObj = targetObjects.get(objectId);
                String typeName = objectType.equals("elements") ? "element" : "relationship";

                if (baseObj == null && targetObj != null) {
                    // Added
                    entries.add(new DiffEntry(typeName, objectId,
                            getObjectName(targetObj), "added", null, null,
                            (Map<String, Object>) targetObj));
                    added++;
                } else if (baseObj != null && targetObj == null) {
                    // Removed
                    entries.add(new DiffEntry(typeName, objectId,
                            getObjectName(baseObj), "removed", null,
                            (Map<String, Object>) baseObj, null));
                    removed++;
                } else if (baseObj != null && targetObj != null) {
                    // Check for modification
                    Map<String, Object> patch = deepDiff((Map<String, Object>) baseObj,
                            (Map<String, Object>) targetObj);
                    if (patch.isEmpty()) {
                        unchanged++;
                    } else {
                        entries.add(new DiffEntry(typeName, objectId,
                                getObjectName(targetObj), "modified", patch,
                                (Map<String, Object>) baseObj,
                                (Map<String, Object>) targetObj));
                        modified++;
                    }
                }
            }
        }

        // Sort: elements first, then relationships; within each: added, modified, removed
        entries.sort(Comparator.comparing((DiffEntry e) -> e.objectType())
                .thenComparing(e -> kindOrder(e.kind()))
                .thenComparing(e -> e.objectName() != null ? e.objectName() : ""));

        return new DiffResult(
                new DiffSummary(added, modified, removed, unchanged),
                entries);
    }

    /**
     * Diff a branch against its creation baseline (the snapshot captured when
     * the branch was created from its parent).
     *
     * @param projectId the project UUID as string
     * @param branchId  the branch UUID
     * @return structured diff result, or empty diff if no baseline exists
     */
    public DiffResult diffVsBranchPoint(String projectId, java.util.UUID branchId) {
        String targetJson = this.branchHeadRepository.getCanonicalJson(projectId, branchId);
        String baselineJson = getBaselineSnapshot(projectId, branchId);
        if (baselineJson == null || baselineJson.isBlank()) {
            // No baseline — try diffing against parent branch HEAD
            return diffVsParentLatest(projectId, branchId);
        }
        return diff(baselineJson, targetJson != null ? targetJson : "{}");
    }

    /**
     * Diff a branch against its parent branch's latest HEAD.
     *
     * @param projectId the project UUID as string
     * @param branchId  the branch UUID
     * @return structured diff result
     */
    public DiffResult diffVsParentLatest(String projectId, java.util.UUID branchId) {
        String targetJson = this.branchHeadRepository.getCanonicalJson(projectId, branchId);
        java.util.UUID parentId = getParentBranchId(projectId, branchId);
        if (parentId == null) {
            return new DiffResult(new DiffSummary(0, 0, 0, 0), List.of());
        }
        String baseJson = this.branchHeadRepository.getCanonicalJson(projectId, parentId);
        return diff(baseJson != null ? baseJson : "{}",
                    targetJson != null ? targetJson : "{}");
    }

    /**
     * Diff two arbitrary branches.
     */
    public DiffResult diffBranches(String projectId, java.util.UUID baseBranchId, java.util.UUID targetBranchId) {
        String baseJson = this.branchHeadRepository.getCanonicalJson(projectId, baseBranchId);
        String targetJson = this.branchHeadRepository.getCanonicalJson(projectId, targetBranchId);
        return diff(baseJson != null ? baseJson : "{}",
                    targetJson != null ? targetJson : "{}");
    }

    // ─── Private helpers ───────────────────────────────────────────────

    private String getBaselineSnapshot(String projectId, java.util.UUID branchId) {
        try {
            // Query syson_baselines for the auto-created branch-point baseline
            var result = this.entityManager
                    .createNativeQuery("""
                            SELECT canonical_snapshot FROM syson_baselines
                            WHERE project_id = CAST(?1 AS uuid)
                              AND branch_id = ?2
                              AND canonical_snapshot IS NOT NULL
                            ORDER BY created_at DESC LIMIT 1
                            """)
                    .setParameter(1, projectId)
                    .setParameter(2, branchId)
                    .getResultList();
            if (!result.isEmpty()) {
                return result.get(0).toString();
            }
        } catch (Exception e) {
            // Fall through
        }
        return null;
    }

    private java.util.UUID getParentBranchId(String projectId, java.util.UUID branchId) {
        try {
            var result = this.entityManager
                    .createNativeQuery("""
                            SELECT parent_branch_id FROM syson_branches
                            WHERE branch_id = ?1 AND project_id = CAST(?2 AS uuid)
                            """)
                    .setParameter(1, branchId)
                    .setParameter(2, projectId)
                    .getResultList();
            if (!result.isEmpty() && result.get(0) != null) {
                return (java.util.UUID) result.get(0);
            }
        } catch (Exception e) {
            // Fall through
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseCanonical(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return this.objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractTypedMap(Map<String, Map<String, Object>> root, String key) {
        Object val = root.get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return Map.of();
    }

    private String getObjectName(Object obj) {
        if (obj instanceof Map) {
            Object name = ((Map<String, Object>) obj).get("name");
            Object type = ((Map<String, Object>) obj).get("type");
            if (name != null) return name.toString();
            if (type != null) return type.toString();
        }
        return "";
    }

    /**
     * Field-level deep diff. Returns a map of only the changed fields:
     * {@code {field: newValue}}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepDiff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> patch = new LinkedHashMap<>();
        // All keys from both sides
        java.util.Set<String> allKeys = new java.util.TreeSet<>();
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());
        for (String key : allKeys) {
            Object b = before.get(key);
            Object a = after.get(key);
            if (!objectsEqual(b, a)) {
                patch.put(key, a);
            }
        }
        return patch;
    }

    private boolean objectsEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // Compare by JSON canonical form
        try {
            String ja = this.objectMapper.writeValueAsString(a);
            String jb = this.objectMapper.writeValueAsString(b);
            return ja.equals(jb);
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    private int kindOrder(String kind) {
        return switch (kind) {
            case "added" -> 0;
            case "modified" -> 1;
            case "removed" -> 2;
            default -> 3;
        };
    }

    // ─── Result records ───────────────────────────────────────────────

    public record DiffResult(DiffSummary summary, List<DiffEntry> entries) {}

    public record DiffSummary(int added, int modified, int removed, int unchanged) {}

    public record DiffEntry(
            String objectType,    // "element" | "relationship"
            String objectId,      // stableId
            String objectName,    // human-readable name
            String kind,          // "added" | "modified" | "removed"
            Map<String, Object> patch,      // modified only: changed fields
            Map<String, Object> beforeObject,  // removed/modified: before state
            Map<String, Object> afterObject   // added/modified: after state
    ) {}
}
