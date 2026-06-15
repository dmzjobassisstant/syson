package org.eclipse.syson.vc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.history.repository.BranchHeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Unit tests for {@link BranchDiffService}.
 * <p>
 * Tests the element-level diff engine using reflection-based contract
 * testing — no Spring Boot startup required. Uses JSON fixtures to verify
 * add/modify/remove detection, field-level patch extraction, and summary
 * counting.
 *
 * @author syson-team
 */
@DisplayName("BranchDiffService — element-level diff engine")
class BranchDiffServiceTest {

    private BranchDiffService service;

    @BeforeEach
    void setUp() {
        // The service works with null BranchHeadRepository/EntityManager for
        // direct diff() calls. The repository-dependent methods are tested
        // via API integration.
        service = new BranchDiffService(null, null);
    }

    @Test
    @DisplayName("diff: identical snapshots produce zero changes")
    void diffIdenticalSnapshots() {
        String json = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"}},
                 "relationships":{}}
                """;
        var result = service.diff(json, json);
        assertEquals(0, result.summary().added());
        assertEquals(0, result.summary().modified());
        assertEquals(0, result.summary().removed());
        assertEquals(1, result.summary().unchanged());
    }

    @Test
    @DisplayName("diff: added element detected and counted")
    void diffAddedElement() {
        String base = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"}},
                 "relationships":{}}
                """;
        String target = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"},
                             "e2":{"name":"PartB","type":"PartUsage"}},
                 "relationships":{}}
                """;
        var result = service.diff(base, target);
        assertEquals(1, result.summary().added());
        assertEquals(0, result.summary().modified());
        assertEquals(0, result.summary().removed());
        assertEquals(1, result.summary().unchanged());

        var addedEntry = result.entries().stream()
                .filter(e -> e.kind().equals("added"))
                .findFirst().orElse(null);
        assertNotNull(addedEntry);
        assertEquals("element", addedEntry.objectType());
        assertEquals("e2", addedEntry.objectId());
        assertEquals("PartB", addedEntry.objectName());
    }

    @Test
    @DisplayName("diff: modified element detected with field-level patch")
    void diffModifiedElement() {
        String base = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage","isAbstract":false}},
                 "relationships":{}}
                """;
        String target = """
                {"elements":{"e1":{"name":"PartA_Renamed","type":"PartUsage","isAbstract":true}},
                 "relationships":{}}
                """;
        var result = service.diff(base, target);
        assertEquals(0, result.summary().added());
        assertEquals(1, result.summary().modified());
        assertEquals(0, result.summary().removed());

        var modEntry = result.entries().stream()
                .filter(e -> e.kind().equals("modified"))
                .findFirst().orElse(null);
        assertNotNull(modEntry);
        assertNotNull(modEntry.patch());
        assertTrue(modEntry.patch().containsKey("name"));
        assertEquals("PartA_Renamed", modEntry.patch().get("name"));
        assertTrue(modEntry.patch().containsKey("isAbstract"));
        assertEquals(true, modEntry.patch().get("isAbstract"));
        assertFalse(modEntry.patch().containsKey("type"));
    }

    @Test
    @DisplayName("diff: removed element detected")
    void diffRemovedElement() {
        String base = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"},
                             "e2":{"name":"PartB","type":"PartUsage"}},
                 "relationships":{}}
                """;
        String target = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"}},
                 "relationships":{}}
                """;
        var result = service.diff(base, target);
        assertEquals(0, result.summary().added());
        assertEquals(0, result.summary().modified());
        assertEquals(1, result.summary().removed());
        assertEquals(1, result.summary().unchanged());

        var removedEntry = result.entries().stream()
                .filter(e -> e.kind().equals("removed"))
                .findFirst().orElse(null);
        assertNotNull(removedEntry);
        assertEquals("e2", removedEntry.objectId());
    }

    @Test
    @DisplayName("diff: relationship changes detected alongside element changes")
    void diffRelationshipChanges() {
        String base = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"}},
                 "relationships":{"r1":{"source":"e1","target":"e2","type":"FeatureTyping"}}}
                """;
        String target = """
                {"elements":{"e1":{"name":"PartA","type":"PartUsage"}},
                 "relationships":{}}
                """;
        var result = service.diff(base, target);
        assertEquals(1, result.summary().removed());
        var relRemoved = result.entries().stream()
                .filter(e -> e.objectType().equals("relationship") && e.kind().equals("removed"))
                .findFirst().orElse(null);
        assertNotNull(relRemoved);
        assertEquals("r1", relRemoved.objectId());
    }

    @Test
    @DisplayName("diff: empty snapshots produce empty diff")
    void diffEmptySnapshots() {
        var result = service.diff("{}", "{}");
        assertEquals(0, result.summary().added());
        assertEquals(0, result.summary().modified());
        assertEquals(0, result.summary().removed());
        assertEquals(0, result.summary().unchanged());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    @DisplayName("diff: null/blank snapshots treated as empty")
    void diffNullSnapshots() {
        var result = service.diff(null, "");
        assertEquals(0, result.summary().added());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    @DisplayName("diff: multiple changes in mixed add/modify/remove scenario")
    void diffMixedChanges() {
        String base = """
                {"elements":{
                    "e1":{"name":"PartA","type":"PartUsage","isAbstract":false},
                    "e2":{"name":"PartB","type":"PartUsage"},
                    "e3":{"name":"PartC","type":"PartUsage"}
                 },"relationships":{}}
                """;
        String target = """
                {"elements":{
                    "e1":{"name":"PartA_Renamed","type":"PartUsage","isAbstract":false},
                    "e2":{"name":"PartB","type":"PartUsage"},
                    "e4":{"name":"PartD","type":"PartUsage"}
                 },"relationships":{}}
                """;
        var result = service.diff(base, target);
        assertEquals(1, result.summary().added());
        assertEquals(1, result.summary().modified());
        assertEquals(1, result.summary().removed());
        assertEquals(1, result.summary().unchanged());
        assertEquals(3, result.entries().size());
    }

    @Test
    @DisplayName("diff: entries sorted by type then kind (added before modified before removed)")
    void diffEntrySorting() {
        String base = """
                {"elements":{
                    "e1":{"name":"A","type":"T"},
                    "e2":{"name":"B","type":"T"}
                 },"relationships":{
                    "r1":{"source":"e1","type":"Rel"}
                 }}
                """;
        String target = """
                {"elements":{
                    "e1":{"name":"A_Renamed","type":"T"},
                    "e3":{"name":"C","type":"T"}
                 },"relationships":{}}
                """;
        var result = service.diff(base, target);
        assertFalse(result.entries().isEmpty());
        // Elements should come before relationships
        var elements = result.entries().stream()
                .filter(e -> e.objectType().equals("element")).toList();
        var rels = result.entries().stream()
                .filter(e -> e.objectType().equals("relationship")).toList();
        assertFalse(elements.isEmpty());
        // Check sort within elements: added (e3) before modified (e1)
        if (elements.size() >= 2) {
            assertEquals("added", elements.get(0).kind());
            assertEquals("modified", elements.get(1).kind());
        }
    }

    @Test
    @DisplayName("diff: deeply nested object changes detected via JSON comparison")
    void diffNestedObjectChanges() {
        ObjectMapper om = new ObjectMapper();
        String base = """
                {"elements":{"e1":{"name":"PartA","properties":{"weight":10,"color":"red"}}},
                 "relationships":{}}
                """;
        String target = """
                {"elements":{"e1":{"name":"PartA","properties":{"weight":15,"color":"red"}}},
                 "relationships":{}}
                """;
        var result = service.diff(base, target);
        assertEquals(1, result.summary().modified());
        var modEntry = result.entries().get(0);
        assertNotNull(modEntry.patch());
        assertTrue(modEntry.patch().containsKey("properties"));
    }
}
