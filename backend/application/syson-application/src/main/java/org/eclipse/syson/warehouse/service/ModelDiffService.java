package org.eclipse.syson.warehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.eclipse.syson.warehouse.service.CanonicalExtractor.CanonicalElement;
import org.eclipse.syson.warehouse.service.CanonicalExtractor.CanonicalRelationship;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Diffs two canonical snapshots to produce create/update/delete change records.
 */
@Service
public class ModelDiffService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public List<DiffResult> diffElements(List<CanonicalElement> previous, List<CanonicalElement> current) {
        Map<String, CanonicalElement> prevMap = new HashMap<>();
        for (var e : previous) prevMap.put(e.stableId(), e);
        Map<String, CanonicalElement> currMap = new HashMap<>();
        for (var e : current) currMap.put(e.stableId(), e);

        List<DiffResult> results = new ArrayList<>();
        for (var entry : currMap.entrySet()) {
            CanonicalElement prev = prevMap.get(entry.getKey());
            CanonicalElement curr = entry.getValue();
            if (prev == null) {
                results.add(new DiffResult(curr.stableId(), "element", "create", null, serialize(curr), List.of()));
            } else if (!Objects.equals(prev.objectHash(), curr.objectHash())) {
                List<String> changed = diffFields(prev, curr);
                results.add(new DiffResult(curr.stableId(), "element", "update", serialize(prev), serialize(curr), changed));
            }
        }
        for (var entry : prevMap.entrySet()) {
            if (!currMap.containsKey(entry.getKey())) {
                results.add(new DiffResult(entry.getKey(), "element", "delete", serialize(entry.getValue()), null, List.of()));
            }
        }
        return results;
    }

    public List<DiffResult> diffRelationships(List<CanonicalRelationship> previous, List<CanonicalRelationship> current) {
        Map<String, CanonicalRelationship> prevMap = new HashMap<>();
        for (var r : previous) prevMap.put(r.stableId(), r);
        Map<String, CanonicalRelationship> currMap = new HashMap<>();
        for (var r : current) currMap.put(r.stableId(), r);

        List<DiffResult> results = new ArrayList<>();
        for (var entry : currMap.entrySet()) {
            CanonicalRelationship prev = prevMap.get(entry.getKey());
            CanonicalRelationship curr = entry.getValue();
            if (prev == null) {
                results.add(new DiffResult(curr.stableId(), "relationship", "create", null, serializeRel(curr), List.of()));
            } else if (!Objects.equals(prev.objectHash(), curr.objectHash())) {
                results.add(new DiffResult(curr.stableId(), "relationship", "update", serializeRel(prev), serializeRel(curr), List.of()));
            }
        }
        for (var entry : prevMap.entrySet()) {
            if (!currMap.containsKey(entry.getKey())) {
                results.add(new DiffResult(entry.getKey(), "relationship", "delete", serializeRel(entry.getValue()), null, List.of()));
            }
        }
        return results;
    }

    private List<String> diffFields(CanonicalElement prev, CanonicalElement curr) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(prev.name(), curr.name())) changed.add("name");
        if (!Objects.equals(prev.body(), curr.body())) changed.add("body");
        if (!Objects.equals(prev.sysmlType(), curr.sysmlType())) changed.add("sysmlType");
        if (!Objects.equals(prev.ownerStableId(), curr.ownerStableId())) changed.add("ownerStableId");
        if (!Objects.equals(prev.attributes(), curr.attributes())) changed.add("attributes");
        return changed;
    }

    private String serialize(CanonicalElement e) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("stableId", e.stableId());
            m.put("sysmlType", e.sysmlType());
            m.put("name", e.name());
            m.put("body", e.body());
            m.put("ownerStableId", e.ownerStableId());
            m.put("qualifiedName", e.qualifiedName());
            m.put("attributes", e.attributes());
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String serializeRel(CanonicalRelationship r) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("stableId", r.stableId());
            m.put("relType", r.relType());
            m.put("source", r.sourceStableId());
            m.put("target", r.targetStableId());
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    public record DiffResult(
        String stableObjectId, String objectType, String operation,
        String beforeObject, String afterObject, List<String> changedFields
    ) {}
}
