# SysON Element Warehouse + Data History + Properties History Button

> **For Hermes / future agents:** Use `subagent-driven-development` to implement this plan task-by-task. Read `/root/syson-fork/AGENTS.md` first. Do **not** refactor `auth.js` login boot path. Do **not** move endpoints into new REST controller classes unless live request mapping is proven.

**Goal:** Replace SysON's blob-only persistence with a structured element warehouse that extracts SysML elements, relationships, and diagrams into normalized database tables on every editor save, maintains append-only change history, and exposes an element history button in the Sirius properties panel — all without modifying the Sirius editor code.

**Architecture:** Sidecar pattern — keep `document.content` and `representation_content.content` as Sirius compatibility storage. On every save, extract canonical objects from EMF, diff against current head state, write append-only change records, and materialize the new head. The properties panel gets a "History" button via `auth.js` MutationObserver that opens an overlay showing element evolution.

**Tech Stack:** Java 17/Spring Boot 3.5, Sirius Web/EMF, PostgreSQL 16 JSONB, JPA, Flyway migrations applied manually (Flyway disabled in container), `auth.js` for UI injection.

---

## 0. Current state assessment

### What exists (V3/V4)

- `syson_elements` — uses random UUIDs, soft-delete + rewrite, no history
- `syson_relationships` — same pattern
- `syson_diagrams`, `syson_diagram_nodes`, `syson_diagram_edges` — same
- `syson_branches`, `syson_commits`, `syson_changes`, `syson_baselines` — schema exists but `SaveEventListener.syncFromEditingContext()` has no caller; no commits/changes are ever written
- `SaveEventListener` — generates random UUIDs for every EObject, destroys stable identity

### What's needed

1. **Stable element identity** — use Sirius/EMF IDs, not random UUIDs
2. **Structured extraction** — extract all SysML attributes, not just name/type
3. **Append-only history** — diff-based change tracking, not soft-delete rewrite
4. **Head materialization** — current-state tables for fast queries
5. **Element history API** — query changes for a specific element
6. **Properties panel history button** — UI to view element evolution

### Reference: BowTie Pilot pattern

BowTie uses:
- `model_commits` — append-only commit log with hash chain
- `model_changes` — append-only change log with before/after JSONB objects
- `object_versions` — current/historical object state with validity ranges
- `head_elements`, `head_relationships`, `head_diagrams`, `head_diagram_symbols`, `head_diagram_links` — materialized current state per branch
- `head_project_state` — cached canonical JSON + counts per branch

SysON will adapt this pattern with TEXT project IDs (matching upstream Sirius).

---

## 1. Schema design

### V16 — Element warehouse head tables

New tables that supersede V3's random-ID tables. V3 tables remain untouched for backward compat.

```sql
-- V16__element_warehouse_head_tables.sql

-- Stable element identity using Sirius/EMF IDs
CREATE TABLE IF NOT EXISTS syson_head_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,          -- Sirius/EMF xmi:id or deterministic hash
    document_id UUID,                 -- source document reference
    owner_stable_id TEXT,             -- containment parent
    qualified_name TEXT,
    sysml_type VARCHAR(150) NOT NULL, -- e.g. PartDefinition, PortUsage, Package
    name TEXT,
    body TEXT,                        -- textual body/value if present
    attributes JSONB NOT NULL DEFAULT '{}',  -- all scalar attributes
    raw_object JSONB NOT NULL,        -- full serialized EObject for reconstruction
    object_hash VARCHAR(64) NOT NULL, -- SHA-256 of canonical JSON
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, stable_id)
);

CREATE INDEX IF NOT EXISTS idx_she_type ON syson_head_elements(project_id, branch_id, sysml_type) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_owner ON syson_head_elements(project_id, branch_id, owner_stable_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_qname ON syson_head_elements(project_id, branch_id, qualified_name) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_attrs_gin ON syson_head_elements USING GIN (attributes);

-- Relationships between elements
CREATE TABLE IF NOT EXISTS syson_head_relationships (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    rel_type VARCHAR(150) NOT NULL,   -- e.g. FeatureTyping, SuccessionFlow, BindingConnector
    source_stable_id TEXT,
    target_stable_id TEXT,
    source_role TEXT,
    target_role TEXT,
    owner_stable_id TEXT,
    name TEXT,
    attributes JSONB NOT NULL DEFAULT '{}',
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, stable_id)
);

CREATE INDEX IF NOT EXISTS idx_shr_source ON syson_head_relationships(project_id, branch_id, source_stable_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shr_target ON syson_head_relationships(project_id, branch_id, target_stable_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shr_type ON syson_head_relationships(project_id, branch_id, rel_type) WHERE NOT is_deleted;

-- Diagrams
CREATE TABLE IF NOT EXISTS syson_head_diagrams (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    representation_id TEXT,
    target_object_id TEXT,            -- semantic element the diagram describes
    name TEXT,
    diagram_kind VARCHAR(150),
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, stable_id)
);

-- Diagram presentation elements (nodes, edges, labels)
CREATE TABLE IF NOT EXISTS syson_head_presentation_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    diagram_stable_id TEXT NOT NULL,
    semantic_element_id TEXT,         -- links to syson_head_elements.stable_id
    presentation_type VARCHAR(80) NOT NULL, -- node, edge, label
    parent_presentation_id TEXT,
    bounds JSONB,                     -- {x, y, width, height}
    style JSONB,
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, stable_id)
);

CREATE INDEX IF NOT EXISTS idx_shpe_diagram ON syson_head_presentation_elements(project_id, branch_id, diagram_stable_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shpe_semantic ON syson_head_presentation_elements(project_id, branch_id, semantic_element_id) WHERE NOT is_deleted;

-- Branch head cache
CREATE TABLE IF NOT EXISTS syson_branch_heads (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    head_commit_id UUID,
    canonical_hash VARCHAR(64),
    canonical_json JSONB,
    object_count INT DEFAULT 0,
    relationship_count INT DEFAULT 0,
    diagram_count INT DEFAULT 0,
    last_extracted_at TIMESTAMPTZ DEFAULT now(),
    extraction_version VARCHAR(50) DEFAULT 'v1',
    PRIMARY KEY (project_id, branch_id)
);
```

### V17 — Append-only change history

Expands V4's `syson_changes` and adds `syson_commit_parents` for hash chain.

```sql
-- V17__element_warehouse_change_history.sql

-- Expand syson_changes to support TEXT object IDs and more object types
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS branch_id UUID;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS stable_object_id TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS changed_fields JSONB DEFAULT '[]';
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(50) DEFAULT 'v1';

-- Expand object type constraint
ALTER TABLE syson_changes DROP CONSTRAINT IF EXISTS syson_changes_object_type_check;
ALTER TABLE syson_changes ADD CONSTRAINT syson_changes_object_type_check
CHECK (object_type IN (
  'element', 'relationship', 'diagram', 'presentation', 'document', 'metadata'
));

-- Commit parent chain
CREATE TABLE IF NOT EXISTS syson_commit_parents (
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id) ON DELETE CASCADE,
    parent_commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    parent_order INT NOT NULL DEFAULT 1,
    PRIMARY KEY (commit_id, parent_commit_id)
);

-- Object versions (current + historical state per element)
CREATE TABLE IF NOT EXISTS syson_object_versions (
    project_id TEXT NOT NULL,
    object_type TEXT NOT NULL,        -- element, relationship, diagram, presentation
    stable_object_id TEXT NOT NULL,
    commit_id UUID NOT NULL,
    valid_from_commit_number BIGINT NOT NULL,
    valid_to_commit_number BIGINT,
    is_current BOOLEAN NOT NULL,
    object_hash TEXT NOT NULL,
    object_json JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, object_type, stable_object_id, commit_id)
);

CREATE INDEX IF NOT EXISTS idx_sov_current ON syson_object_versions(project_id, object_type, is_current) WHERE is_current = TRUE;
CREATE INDEX IF NOT EXISTS idx_sov_timeline ON syson_object_versions(project_id, object_type, stable_object_id, valid_from_commit_number DESC);
```

---

## 2. Implementation tasks

### Task 1: Incorporate audit trail tests into regression framework

**Objective:** Add audit trail verification to the existing regression test suite.

**Files:**
- Modify: `scripts/check-syson-editor-ui-regression.py` — add audit trail tests
- Modify: `scripts/check-syson-enterprise-access-regression.sh` — add audit trail endpoint checks

**New tests to add:**
- T22: Admin-only audit trail endpoint returns JSON (not HTML)
- T23: Non-admin gets 403 on audit trail endpoint
- T24: Audit trail contains login_success events after test login
- T25: Audit trail pagination works (size, page, sort params)

**Verification:**
```bash
cd /root/syson-fork
python3 scripts/check-syson-editor-ui-regression.py
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
```

### Task 2: Apply V16/V17 schema migrations

**Objective:** Create the element warehouse head tables and change history tables.

**Files:**
- Create: `backend/application/syson-application/src/main/resources/db/migration/V16__element_warehouse_head_tables.sql`
- Create: `backend/application/syson-application/src/main/resources/db/migration/V17__element_warehouse_change_history.sql`

**Apply manually (Flyway disabled):**
```bash
docker exec -i syson-postgres psql -U syson -d syson < V16__element_warehouse_head_tables.sql
docker exec -i syson-postgres psql -U syson -d syson < V17__element_warehouse_change_history.sql
```

**Verification:**
```sql
SELECT table_name FROM information_schema.tables
WHERE table_name LIKE 'syson_head_%' OR table_name IN ('syson_branch_heads', 'syson_commit_parents', 'syson_object_versions')
ORDER BY table_name;
```

### Task 3: Create JPA entities for warehouse tables

**Objective:** Map new tables to JPA entities with proper JSONB handling.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/HeadElementEntity.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/HeadRelationshipEntity.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/HeadDiagramEntity.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/HeadPresentationElementEntity.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/BranchHeadEntity.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/ObjectVersionEntity.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/entity/CommitParentEntity.java`

**Rules:**
- Composite keys via `@IdClass` or `@EmbeddedId`
- JSONB fields use `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")`
- Explicit getters/setters, no Lombok
- `projectId` as `String` (TEXT), not UUID

### Task 4: Create JPA repositories

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/repository/HeadElementRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/repository/HeadRelationshipRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/repository/HeadDiagramRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/repository/HeadPresentationElementRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/repository/BranchHeadRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/repository/ObjectVersionRepository.java`

**Key queries:**
```java
// Element history — all changes for a specific element
@Query("SELECT v FROM ObjectVersionEntity v WHERE v.projectId = :projectId AND v.stableObjectId = :stableId AND v.objectType = 'element' ORDER BY v.validFromCommitNumber DESC")
List<ObjectVersionEntity> findElementHistory(@Param("projectId") String projectId, @Param("stableId") String stableId);

// Current element state
Optional<HeadElementEntity> findByProjectIdAndBranchIdAndStableIdAndIsDeletedFalse(String projectId, UUID branchId, String stableId);
```

### Task 5: Stable SysML ID service

**Objective:** Generate deterministic, stable IDs for EMF objects that survive repeated extraction.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/service/StableIdService.java`
- Create: `backend/application/syson-application/src/test/java/org/eclipse/syson/warehouse/service/StableIdServiceTest.java`

**Rules:**
- Prefer existing Sirius/EMF `xmi:id` when available (most SysMLv2 objects have one)
- Fall back to deterministic hash: `SHA-256(documentId + containmentPath + eClass + name)`
- Never use `UUID.randomUUID()` for canonical object IDs
- Return `String` (TEXT), not UUID

### Task 6: Canonical SysML extractor

**Objective:** Extract structured element data from EMF objects into canonical form.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/service/CanonicalExtractor.java`
- Create: `backend/application/syson-application/src/test/java/org/eclipse/syson/warehouse/service/CanonicalExtractorTest.java`

**Extraction rules:**
- For each `EObject` in the resource set:
  - Extract `stableId` via `StableIdService`
  - Extract `sysmlType` from `eClass().getName()`
  - Extract `name` from `getName()` or named feature
  - Extract `body` from textual body/value features
  - Extract all scalar attributes into `attributes` JSONB (key = EStructuralFeature name, value = serialized value)
  - Extract `ownerStableId` from containment parent
  - Extract `qualifiedName` from containment path
  - Serialize full `EObject` to JSON for `rawObject`
  - Compute SHA-256 hash of canonical JSON for `objectHash`
- For cross-references (non-containment):
  - Create `CanonicalRelationship` with source, target, type, roles
- Skip standard libraries (use existing `SysMLv2EditingContextPersistenceFilter` logic)

**Output record:**
```java
public record CanonicalSnapshot(
    String projectId,
    UUID branchId,
    List<CanonicalElement> elements,
    List<CanonicalRelationship> relationships,
    List<CanonicalDiagram> diagrams,
    List<CanonicalPresentationElement> presentations,
    String canonicalHash
) {}
```

### Task 7: Model diff service

**Objective:** Compare previous head snapshot with new extraction to produce change records.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/service/ModelDiffService.java`
- Create: `backend/application/syson-application/src/test/java/org/eclipse/syson/warehouse/service/ModelDiffServiceTest.java`

**Behavior:**
- Index previous and new snapshots by `stableId`
- For each element:
  - If in new but not previous → `create`
  - If in both but hash differs → `update` with `changedFields` list
  - If in previous but not new → `delete`
- Same for relationships, diagrams, presentations
- For updates, store `beforeObject` and `afterObject` JSONB
- Compute `patch` as JSON diff of changed fields

### Task 8: Commit persistence + head materialization service

**Objective:** Write commit records, change records, and update head tables atomically.

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/service/CommitService.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/service/HeadMaterializationService.java`

**Commit flow:**
1. Resolve/create default `main` branch for project
2. Load current head snapshot from `syson_branch_heads`
3. Extract new canonical snapshot from editing context
4. Diff old vs new → change records
5. If no changes → skip commit (no-op save)
6. Create `syson_commits` row with hash chain
7. Create `syson_changes` rows (one per change)
8. Create `syson_commit_parents` row
9. Upsert `syson_head_*` tables (create/update/delete)
10. Create `syson_object_versions` rows for changed elements
11. Update `syson_branch_heads` cache
12. All in one transaction

### Task 9: Wire save event hook

**Objective:** Connect the extraction pipeline to actual Sirius editor saves.

**Files:**
- Modify: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/SaveEventListener.java` — replace random-ID sync with warehouse commit
- Or create: `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/service/SaveWarehouseHook.java`

**Approach:**
- Find the correct Sirius save event hook:
  - `SemanticDataUpdatedEvent` with `@TransactionalEventListener` (preferred)
  - Or `RepresentationContentUpdatedEvent`
  - Or decorate `IResourceToDocumentService.toDocument()`
- On save: call `CommitService.commitEditingContext(editingContext, projectId, branchId, userId)`
- If extraction fails: log warning but do NOT block the editor save (shadow mode)
- Write `syson_audit_events` with extraction metadata

**Acceptance:**
- Create a model element in the SysON UI
- Verify: new `syson_commits` row, correct `syson_changes` rows, `syson_head_elements` has the element
- Edit the element name
- Verify: new commit, change record with before/after, head element updated
- Save without changes
- Verify: no new commit created

### Task 10: Element history API endpoint

**Objective:** REST endpoint to query change history for a specific element.

**Files:**
- Add to existing controller (e.g., `ElementRestController` or `UserController`):
  - `GET /api/v1/projects/{projectId}/elements/{stableId}/history`

**Response DTO:**
```json
{
  "projectId": "...",
  "stableId": "...",
  "sysmlType": "PartDefinition",
  "name": "Engine Assembly",
  "history": [
    {
      "commitId": "...",
      "commitNumber": 14,
      "operation": "update",
      "changedFields": ["name", "attributes.mass"],
      "beforeObject": { ... },
      "afterObject": { ... },
      "author": { "id": "...", "email": "..." },
      "committedAt": "2026-06-08T22:00:00Z"
    },
    {
      "commitId": "...",
      "commitNumber": 7,
      "operation": "create",
      "afterObject": { ... },
      "author": { "id": "...", "email": "..." },
      "committedAt": "2026-06-08T20:00:00Z"
    }
  ],
  "totalVersions": 3
}
```

**Access control:** Project member + read permission. Audit-log the query.

### Task 11: Properties panel history button (auth.js)

**Objective:** Add a "History" button to the Sirius properties panel that shows element change history.

**File:** `frontend/syson/public/auth.js`

**Approach (zero-editor-change):**
1. Add `MutationObserver` that watches for the properties panel DOM
2. When properties panel renders (detected by class/data attributes):
   - Extract the selected element ID from the panel's data or GraphQL response
   - Inject a small "📋 History" button in the properties panel header
3. On click:
   - Fetch `/api/v1/projects/{projectId}/elements/{stableId}/history`
   - Show an overlay/modal with:
     - Element name + type at top
     - Timeline of changes (commit number, date, author, operation)
     - Expandable rows showing changed fields with before/after values
     - Color-coded: green for creates, yellow for updates, red for deletes
4. Close button dismisses the overlay

**Rules:**
- Self-contained function only — do not modify existing login/interceptor code
- Inline styles for the overlay (avoid CSS conflicts)
- Use `_origFetch` for API calls (not the monkey-patched fetch)
- If element ID cannot be extracted, hide the button gracefully
- Button only visible to authenticated users

### Task 12: Element history regression tests

**Objective:** End-to-end tests for the element warehouse and history pipeline.

**Files:**
- Create: `scripts/check-syson-element-warehouse-regression.sh`

**Test sequence:**
1. Login as admin
2. Create/get test project
3. Check initial head element count
4. Perform a model update (create element via GraphQL)
5. Verify commit created with changes
6. Verify head element table updated
7. Verify object_versions has history entries
8. Query element history API — verify response shape
9. Perform no-op save — verify no new commit
10. Check element history shows create + update
11. Verify viewer cannot access admin warehouse endpoints
12. Verify audit trail logged the warehouse operations

### Task 13: Commit and deploy

**Objective:** Build, deploy, verify all regressions pass.

```bash
cd /root/syson-fork
git add -A
git commit -m "feat: element warehouse extraction + data history + properties history button

- V16: syson_head_elements/relationships/diagrams/presentations + branch_heads
- V17: expanded syson_changes + commit_parents + object_versions
- StableIdService: deterministic Sirius/EMF-based element identity
- CanonicalExtractor: structured SysML extraction with all attributes
- ModelDiffService: diff-based change detection (create/update/delete)
- CommitService: atomic commit + head materialization + history
- SaveWarehouseHook: wired to Sirius save events (shadow mode)
- Element history REST API: GET /api/v1/projects/{id}/elements/{id}/history
- auth.js: History button in properties panel with change timeline overlay
- Regression tests: audit trail + element warehouse + history pipeline"

# Rebuild backend (no -am)
cd backend && mvn clean install -pl application/syson-application -o -DskipTests -Dcheckstyle.skip=true

# Repackage frontend JAR
cd /root/syson-fork
FRONTEND_JAR=$(ls -t frontend/syson-webapp/target/syson-webapp-*.jar | head -1)
cp "$FRONTEND_JAR" /tmp/syson-frontend-backup.jar
jar uf "$FRONTEND_JAR" -C frontend/syson/public auth.js

# Rebuild Docker
docker build -t syson-rbac:latest .

# Redeploy (preserve env)
docker stop syson && docker rm syson
docker run -d --name syson --env-file /tmp/syson.env -p 8080:8080 syson-rbac:latest

# Run all regressions
bash scripts/check-syson-login-regression.sh
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
python3 scripts/check-syson-editor-ui-regression.py
bash scripts/check-syson-element-warehouse-regression.sh
```

---

## 3. Known pitfalls

- **Do not use UUID.randomUUID()** for element identity — use Sirius/EMF IDs
- **Do not block editor saves** if extraction fails — shadow mode first
- **Do not create new REST controllers** — add endpoints to existing working controllers
- **Do not refactor auth.js login boot path** — add history button as self-contained function
- **Do not use branch names as DB keys** — always use branch UUIDs
- **JSONB fields** must be valid JSON strings; use `@JdbcTypeCode(SqlTypes.JSON)`
- **Flyway is disabled** — apply migrations manually via psql
- **Build without -am** to preserve prebuilt frontend JAR
- **TEXT project IDs** in all new tables (matching upstream Sirius)

---

## 4. Acceptance criteria

- [ ] Audit trail tests (T22-T25) pass in regression suite
- [ ] V16/V17 tables created with correct indexes
- [ ] Stable ID service produces deterministic IDs across repeated extraction
- [ ] Canonical extractor captures all SysML attributes (not just name/type)
- [ ] Editor save creates object-level changes and updates head tables
- [ ] No-op save creates no commit
- [ ] Element history API returns paginated change timeline
- [ ] Properties panel History button opens overlay with change timeline
- [ ] Overlay shows before/after values for changed fields
- [ ] All existing regression tests still pass (login, enterprise, editor UI)
- [ ] No Sirius editor behavior changes (zero-editor-change principle)
- [ ] Element IDs are stable across server restarts

---

## 5. Implementation order

1. **Task 1** — Audit trail regression tests (quick win, incorporates existing work)
2. **Task 2** — V16/V17 schema (apply manually to live DB)
3. **Task 3+4** — JPA entities + repositories
4. **Task 5** — Stable ID service + tests
5. **Task 6** — Canonical extractor + tests
6. **Task 7** — Diff service + tests
7. **Task 8** — Commit + head materialization service
8. **Task 9** — Wire save event hook (shadow mode)
9. **Task 10** — Element history API
10. **Task 11** — Properties panel history button
11. **Task 12** — End-to-end regression tests
12. **Task 13** — Build, deploy, verify

Each task should be committed separately. Run existing regressions after each task to catch breakage early.
