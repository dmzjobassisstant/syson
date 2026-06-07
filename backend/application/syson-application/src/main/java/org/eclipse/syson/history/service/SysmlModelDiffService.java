package org.eclipse.syson.history.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * Computes diffs between two canonical model snapshots. Identifies creates,
 * updates, and deletes by comparing stable IDs and object hashes.
 *
 * @author Syson
 */
@Service
public class SysmlModelDiffService {

    /**
     * Record representing a diff between two versions of an object.
     */
    public record ObjectDiff(
            String stableId,
            String objectType,
            String operation,
            String beforeHash,
            String afterHash,
            String patch,
            String beforeObject,
            String afterObject,
            List<String> changedFields
    ) {}

    /**
     * Computes the diff between two canonical model snapshots.
     *
     * @param previous
     *            the previous snapshot
     * @param current
     *            the current snapshot
     * @return a list of object diffs
     */
    public List<ObjectDiff> diff(SysmlCanonicalExtractor.CanonicalModelSnapshot previous,
                                  SysmlCanonicalExtractor.CanonicalModelSnapshot current) {
        List<ObjectDiff> diffs = new ArrayList<>();

        // Index previous elements and relationships by stableId
        Map<String, SysmlCanonicalExtractor.CanonicalElement> prevElements = new HashMap<>();
        Map<String, SysmlCanonicalExtractor.CanonicalRelationship> prevRelationships = new HashMap<>();
        if (previous != null) {
            for (SysmlCanonicalExtractor.CanonicalElement elem : previous.elements()) {
                prevElements.put(elem.stableId(), elem);
            }
            for (SysmlCanonicalExtractor.CanonicalRelationship rel : previous.relationships()) {
                prevRelationships.put(rel.stableId(), rel);
            }
        }

        // Index current elements and relationships by stableId
        Map<String, SysmlCanonicalExtractor.CanonicalElement> currElements = new HashMap<>();
        Map<String, SysmlCanonicalExtractor.CanonicalRelationship> currRelationships = new HashMap<>();
        if (current != null) {
            for (SysmlCanonicalExtractor.CanonicalElement elem : current.elements()) {
                currElements.put(elem.stableId(), elem);
            }
            for (SysmlCanonicalExtractor.CanonicalRelationship rel : current.relationships()) {
                currRelationships.put(rel.stableId(), rel);
            }
        }

        // Find element creates and updates
        Set<String> allElementIds = new HashSet<>();
        allElementIds.addAll(prevElements.keySet());
        allElementIds.addAll(currElements.keySet());

        for (String stableId : allElementIds) {
            SysmlCanonicalExtractor.CanonicalElement prevElem = prevElements.get(stableId);
            SysmlCanonicalExtractor.CanonicalElement currElem = currElements.get(stableId);

            if (prevElem == null && currElem != null) {
                // Create
                diffs.add(new ObjectDiff(
                        stableId, currElem.sysmlType(), "CREATE",
                        null, currElem.objectHash(),
                        null, null, currElem.rawJson(),
                        List.of()));
            } else if (prevElem != null && currElem == null) {
                // Delete
                diffs.add(new ObjectDiff(
                        stableId, prevElem.sysmlType(), "DELETE",
                        prevElem.objectHash(), null,
                        null, prevElem.rawJson(), null,
                        List.of()));
            } else if (prevElem != null && currElem != null) {
                // Potential update
                if (!prevElem.objectHash().equals(currElem.objectHash())) {
                    List<String> changedFields = findChangedFields(prevElem.attributes(), currElem.attributes());
                    String patch = buildPatch(prevElem.attributes(), currElem.attributes(), changedFields);
                    diffs.add(new ObjectDiff(
                            stableId, currElem.sysmlType(), "UPDATE",
                            prevElem.objectHash(), currElem.objectHash(),
                            patch, prevElem.rawJson(), currElem.rawJson(),
                            changedFields));
                }
            }
        }

        // Find relationship creates and updates
        Set<String> allRelIds = new HashSet<>();
        allRelIds.addAll(prevRelationships.keySet());
        allRelIds.addAll(currRelationships.keySet());

        for (String stableId : allRelIds) {
            SysmlCanonicalExtractor.CanonicalRelationship prevRel = prevRelationships.get(stableId);
            SysmlCanonicalExtractor.CanonicalRelationship currRel = currRelationships.get(stableId);

            if (prevRel == null && currRel != null) {
                diffs.add(new ObjectDiff(
                        stableId, currRel.relType(), "CREATE",
                        null, currRel.objectHash(),
                        null, null, currRel.rawJson(),
                        List.of()));
            } else if (prevRel != null && currRel == null) {
                diffs.add(new ObjectDiff(
                        stableId, prevRel.relType(), "DELETE",
                        prevRel.objectHash(), null,
                        null, prevRel.rawJson(), null,
                        List.of()));
            } else if (prevRel != null && currRel != null) {
                if (!prevRel.objectHash().equals(currRel.objectHash())) {
                    List<String> changedFields = findChangedFields(prevRel.attributes(), currRel.attributes());
                    String patch = buildPatch(prevRel.attributes(), currRel.attributes(), changedFields);
                    diffs.add(new ObjectDiff(
                            stableId, currRel.relType(), "UPDATE",
                            prevRel.objectHash(), currRel.objectHash(),
                            patch, prevRel.rawJson(), currRel.rawJson(),
                            changedFields));
                }
            }
        }

        return diffs;
    }

    /**
     * Finds field names that differ between two attribute maps.
     */
    private List<String> findChangedFields(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());

        for (String key : allKeys) {
            Object beforeVal = before.get(key);
            Object afterVal = after.get(key);
            if (!objectsEqual(beforeVal, afterVal)) {
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * Builds a JSON patch string for changed fields.
     */
    private String buildPatch(Map<String, Object> before, Map<String, Object> after, List<String> changedFields) {
        if (changedFields.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (String field : changedFields) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(field)).append('"');
            sb.append(":{\"before\":");
            appendJsonValue(sb, before.get(field));
            sb.append(",\"after\":");
            appendJsonValue(sb, after.get(field));
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private boolean objectsEqual(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toString().equals(b.toString());
    }
}
