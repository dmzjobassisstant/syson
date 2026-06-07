# SysON Enterprise Element History, Warehouse, Locking, and Version Control Implementation Plan

> **For Hermes / future agents:** Use `subagent-driven-development` to implement this plan task-by-task. Read `/root/syson-fork/AGENTS.md` and `/root/syson-fork/SYSON_ENTERPRISE_ACCESS_AUDIT_HANDOFF.md` first. Do **not** refactor `auth.js` login boot path. Do **not** move endpoints into new REST controller classes unless live request mapping is proven.

**Goal:** Convert SysON from Sirius Web's whole-document JSON persistence into an enterprise SaaS-grade hybrid persistence architecture with stable per-element records, append-only object history, fast branch head tables, branch/merge/baseline/tag APIs, audit/lock controls, and GitGraph-style UI visualization — while preserving upstream Sirius editor behavior and avoiding database blow-up from full blob history.

**Architecture:** Keep Sirius Web's `document.content` and `representation_content.content` as compatibility/write-through storage for the existing editor. Add a BowTie-inspired sidecar persistence/version-control layer that extracts canonical SysML and diagram objects from each save, diffs them against branch head, writes append-only change events, materializes normalized current-state head tables, and updates the Sirius blobs only when reconstruction/import paths require it. The editor can continue doing whole-model saves; the backend must persist them as object-level deltas.

**Tech Stack:** Java 17/Spring Boot 3.5, Sirius Web/EMF, PostgreSQL 16 JSONB, JPA repositories already in `syson-application`, Flyway SQL migrations applied manually/live with Flyway disabled, zero-editor-change middleware/interceptor pattern preferred.

---

## 0. Executive evaluation

SysON currently has the same core limitation as early BowTie Pilot: the working editor persists a whole serialized model document rather than first-class database elements. In SysON, the upstream source of truth is Sirius Web:

- `document.content TEXT` — serialized EMF/SysML JSON for semantic documents.
- `representation_content.content TEXT` — serialized Sirius diagram/representation JSON.
- `semantic_data`, `project_semantic_data`, and representation metadata tables connect those documents to projects.

This is fine for a modeling workbench but insufficient for enterprise SaaS requirements:

- per-element history and auditability;
- traceability analysis across multiple SysML levels;
- data warehousing and reporting over historical evolution;
- element/package/diagram-specific locks and lockout;
- branch/merge/baseline/tag version control with conflict detection;
- granular permissions and lifecycle workflows;
- efficient retention without storing full document blobs on every save.

BowTie Pilot's backend is the right class of solution, but SysON needs a cleaner, stricter adaptation:

1. **Preserve Sirius as editor runtime.** Do not rewrite the editor. Treat `document.content` as upstream compatibility storage and a recovery snapshot, not the only analytical source of truth.
2. **Extract stable objects on save.** Use stable Sirius/EMF IDs, not random UUIDs. Current `SaveEventListener` is not wired and generates random IDs; that must be replaced before history can be meaningful.
3. **Diff canonical object maps, not raw blobs.** Whole save input is acceptable, but persisted changes must be object-level operations.
4. **Use append-only history + materialized head tables.** This is the BowTie pattern that prevents database bloat while enabling fast reads and analytics.
5. **Use UUID branch IDs only.** Avoid BowTie's legacy ambiguity around branch name `main` vs internal ID.
6. **Add API and UI around version refs.** GitGraph-style graph can be ported conceptually, but the backend DTOs must be SysON/SysML-specific.
7. **Use enterprise controls everywhere.** Locks, optimistic commit checks, audit events, integrity checks, access checks, retention policies, and deterministic hashes are not optional add-ons.

---

## 1. Grounded findings from the current codebase

### 1.1 SysON storage today

Confirmed current upstream/Sirius storage:

- `document.content` is `TEXT` JSON, not `BYTEA`.
- `representation_content.content` is also `TEXT` JSON.
- Live examples include semantic document blobs from ~2.6 KB to ~140 KB and diagram content around ~1 MB.

Existing repo references:

- `SYSON_ARCHITECTURE_KB.md` — architecture and existing augmentation notes.
- `backend/application/syson-application/src/main/resources/db/migration/V3__element_persistence.sql`
- `backend/application/syson-application/src/main/resources/db/migration/V4__version_control.sql`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/SaveEventListener.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/VersionControlService.java`

### 1.2 Existing SysON sidecar tables are useful but insufficient

Current V3 tables:

- `syson_elements`
- `syson_relationships`
- `syson_diagrams`
- `syson_diagram_nodes`
- `syson_diagram_edges`

Current V4 tables:

- `syson_branches`
- `syson_commits`
- `syson_changes`
- `syson_baselines`

Current V6 access/audit tables:

- `syson_audit_events`
- `syson_element_permissions`
- `syson_branch_permissions`

These are good seeds, but not production-complete:

- `SaveEventListener.syncFromEditingContext(...)` has no caller; save events do not create history.
- It generates random UUIDs for every `EObject`, which destroys stable history.
- It soft-deletes and rewrites current rows wholesale.
- It does not populate attributes/body/diagrams deeply enough.
- V3/V4 use `project_id UUID` while some upstream/Auth project IDs are `TEXT`; this must be normalized.
- JSONB fields are mapped as `String`; this is acceptable only if all writes use valid canonical JSON.
- V4 has commits/changes but no automatic diff pipeline, no head-state materialization, no merge/conflict/tags/locks/snapshots/object versions.

### 1.3 BowTie transferable backend pattern

BowTie's mature pattern is:

- Append-only history tables:
  - `model_commits`
  - `model_changes`
  - `commit_parents`
  - `model_tags`
  - `model_baselines`
  - `merge_requests`
  - `merge_conflicts`
- Branch-specific materialized current-state tables:
  - `head_project_state`
  - `head_elements`
  - `head_relationships`
  - `head_diagrams`
  - `head_diagram_symbols`
  - `head_diagram_links`
  - `head_saved_views`
- Save algorithm:
  1. resolve branch ref;
  2. load branch head canonical JSON;
  3. diff previous vs incoming object maps;
  4. create append-only commit/change rows;
  5. apply changes to head tables;
  6. update cached head blob and branch head commit atomically.
- Load algorithm:
  1. return cached `head_project_state.canonical_json` if valid;
  2. otherwise reconstruct from normalized head tables and cache.
- Locking:
  - branch locks with session/device/user ownership and expiration;
  - conflict on active lock; steal only if expired.
- Integrity checks:
  - validate relationships, symbols, visual links, parent/owner references, deleted references.
- GitGraph UI:
  - backend `overview` and `tree` endpoints;
  - SVG lanes, branches, merge edges, baselines, density modes.

For SysON, copy the **pattern**, not the exact schema blindly.

---

## 2. Target architecture

### 2.1 Source-of-truth rule

The target architecture has two layers:

1. **Sirius compatibility layer**
   - Tables: upstream `document`, `representation_content`.
   - Purpose: keep the existing SysON editor and Sirius GraphQL functional.
   - Contains latest serialized editor documents and possibly periodic snapshots.

2. **Enterprise model warehouse/version layer**
   - Tables: new/expanded `syson_*` normalized head/history tables.
   - Purpose: object-level history, querying, branch/merge/baseline/tag, locks, permissions, audit, analytics.
   - This layer becomes the business source of truth for traceability and enterprise controls.

Do **not** remove or bypass Sirius document persistence in phase 1. Instead, make sidecar extraction reliable, measurable, and reversible.

### 2.2 Core services to add

Create these packages under:

- `backend/application/syson-application/src/main/java/org/eclipse/syson/warehouse/`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/history/`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/locks/`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/integrity/`

Services:

- `StableSysmlIdService`
- `SysmlCanonicalExtractor`
- `RepresentationCanonicalExtractor`
- `CanonicalJsonServiceV2`
- `SysmlObjectHasher`
- `SysmlModelDiffService`
- `BranchResolverService`
- `HeadMaterializationService`
- `CommitPersistenceService`
- `ModelSaveHistoryService`
- `ModelReconstructionService`
- `ElementHistoryService`
- `ModelCompareService`
- `MergeService`
- `BranchLockService`
- `IntegrityCheckService`
- `WarehouseQueryService`
- `VersionGraphService`

### 2.3 Save pipeline target

Every editor save should eventually perform this transaction:

1. Identify project, semantic data, documents, representation contents, current user, tenant, and branch.
2. Extract canonical SysML object map from EMF/Sirius content using stable IDs.
3. Extract canonical diagram/presentation object map from `representation_content.content`.
4. Resolve branch UUID; create default `main` branch if missing.
5. Acquire/validate branch or element lock if configured.
6. Validate optimistic concurrency: request base/head commit must match current branch head unless saving a merge/conflict resolution.
7. Load previous branch head object maps.
8. Diff object maps by stable object ID.
9. If no meaningful changes: update heartbeat/cache metadata only; do not create commit.
10. Run integrity checks.
11. Insert `syson_commits` and ordered `syson_changes` rows.
12. Upsert materialized `head_*` tables.
13. Update `syson_branches.head_commit_id`.
14. Optionally update `document.content` / `representation_content.content` compatibility blobs.
15. Write `syson_audit_events` with valid JSONB metadata.

This must be atomic at DB level: branch head must never point to an incomplete commit.

---

## 3. Schema design

### 3.1 Do not patch V3/V4 in place for live systems

Because live migrations have been applied manually and Flyway is disabled in the running container, create additive migrations:

- `V7__enterprise_model_history_head_tables.sql`
- `V8__enterprise_locks_integrity_merge_tags.sql`
- `V9__enterprise_history_backfill_indexes.sql`

Make all migrations `IF NOT EXISTS` where safe. Use backfill scripts for changing existing columns.

### 3.2 Normalize project IDs

Before adding new history tables, decide one canonical `project_id` type.

Recommendation: use `TEXT` project IDs in all new tables, because upstream Sirius `project(id)` is effectively text-like and `syson_project_members.project_id` already uses `TEXT`.

Do not keep adding new UUID-only tables that cannot join to upstream project rows reliably.

If existing V3/V4 UUID tables must remain, add compatibility columns:

```sql
ALTER TABLE syson_elements ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_relationships ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_branches ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_commits ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS project_ref TEXT;
```

Then populate `project_ref` and make all new APIs use `project_ref`.

### 3.3 Required new/expanded tables

#### `syson_branch_heads`

Purpose: one current materialized head per project/branch.

```sql
CREATE TABLE IF NOT EXISTS syson_branch_heads (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL REFERENCES syson_branches(branch_id),
    tenant_id UUID NOT NULL,
    head_commit_id UUID,
    semantic_data_id UUID,
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

#### `syson_head_elements`

Purpose: current branch state for SysML semantic objects.

```sql
CREATE TABLE IF NOT EXISTS syson_head_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    element_id TEXT NOT NULL,
    stable_id TEXT NOT NULL,
    document_id UUID,
    owner_id TEXT,
    qualified_name TEXT,
    sysml_type VARCHAR(150) NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_she_type ON syson_head_elements(project_id, branch_id, sysml_type) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_owner ON syson_head_elements(project_id, branch_id, owner_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_qname ON syson_head_elements(project_id, branch_id, qualified_name) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_she_attrs_gin ON syson_head_elements USING GIN (attributes);
```

#### `syson_head_relationships`

Purpose: current branch state for semantic cross-references and SysML relationships.

```sql
CREATE TABLE IF NOT EXISTS syson_head_relationships (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    relationship_id TEXT NOT NULL,
    stable_id TEXT NOT NULL,
    rel_type VARCHAR(150) NOT NULL,
    source_id TEXT,
    target_id TEXT,
    source_role TEXT,
    target_role TEXT,
    owner_id TEXT,
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
CREATE INDEX IF NOT EXISTS idx_shr_source ON syson_head_relationships(project_id, branch_id, source_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shr_target ON syson_head_relationships(project_id, branch_id, target_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shr_type ON syson_head_relationships(project_id, branch_id, rel_type) WHERE NOT is_deleted;
```

#### `syson_head_diagrams` and `syson_head_presentation_elements`

Purpose: diagram and presentation history, separately from semantic elements.

```sql
CREATE TABLE IF NOT EXISTS syson_head_diagrams (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    diagram_id TEXT NOT NULL,
    representation_id TEXT,
    target_object_id TEXT,
    name TEXT,
    diagram_kind VARCHAR(150),
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, diagram_id)
);

CREATE TABLE IF NOT EXISTS syson_head_presentation_elements (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    presentation_id TEXT NOT NULL,
    diagram_id TEXT NOT NULL,
    semantic_element_id TEXT,
    presentation_type VARCHAR(80) NOT NULL,
    parent_presentation_id TEXT,
    bounds JSONB,
    style JSONB,
    raw_object JSONB NOT NULL,
    object_hash VARCHAR(64) NOT NULL,
    created_commit_id UUID,
    updated_commit_id UUID,
    deleted_commit_id UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (project_id, branch_id, presentation_id)
);
CREATE INDEX IF NOT EXISTS idx_shpe_diagram ON syson_head_presentation_elements(project_id, branch_id, diagram_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS idx_shpe_semantic ON syson_head_presentation_elements(project_id, branch_id, semantic_element_id) WHERE NOT is_deleted;
```

#### Expand `syson_changes`

Add support for more object types and canonical hashes:

```sql
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS project_ref TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS branch_id UUID;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS object_path TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS changed_fields JSONB DEFAULT '[]';
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS conflict_key TEXT;
ALTER TABLE syson_changes ADD COLUMN IF NOT EXISTS extractor_version VARCHAR(50) DEFAULT 'v1';
```

If the existing CHECK constraint blocks new object types, replace it safely:

```sql
ALTER TABLE syson_changes DROP CONSTRAINT IF EXISTS syson_changes_object_type_check;
ALTER TABLE syson_changes ADD CONSTRAINT syson_changes_object_type_check
CHECK (object_type IN (
  'element', 'relationship', 'diagram', 'presentation', 'document', 'metadata', 'permission', 'branch'
));
```

#### `syson_commit_parents`

Do not rely only on JSON arrays for parent commits.

```sql
CREATE TABLE IF NOT EXISTS syson_commit_parents (
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id) ON DELETE CASCADE,
    parent_commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    parent_order INT NOT NULL DEFAULT 1,
    PRIMARY KEY (commit_id, parent_commit_id)
);
```

#### Tags, merge, locks, integrity

```sql
CREATE TABLE IF NOT EXISTS syson_tags (
    tag_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    branch_id UUID,
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_by UUID REFERENCES syson_users(id),
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(project_id, name)
);

CREATE TABLE IF NOT EXISTS syson_merge_requests (
    merge_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    source_branch_id UUID NOT NULL,
    target_branch_id UUID NOT NULL,
    base_commit_id UUID,
    source_commit_id UUID,
    target_commit_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'open',
    title TEXT,
    description TEXT,
    created_by UUID REFERENCES syson_users(id),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS syson_merge_conflicts (
    conflict_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merge_request_id UUID NOT NULL REFERENCES syson_merge_requests(merge_request_id) ON DELETE CASCADE,
    object_type VARCHAR(100) NOT NULL,
    object_id TEXT NOT NULL,
    field_path TEXT,
    base_value JSONB,
    source_value JSONB,
    target_value JSONB,
    resolution JSONB,
    status VARCHAR(50) NOT NULL DEFAULT 'unresolved',
    resolved_by UUID REFERENCES syson_users(id),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS syson_branch_locks (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    lock_type VARCHAR(50) NOT NULL DEFAULT 'write',
    owner_user_id UUID NOT NULL REFERENCES syson_users(id),
    owner_session_id TEXT,
    owner_device_id TEXT,
    reason TEXT,
    acquired_at TIMESTAMPTZ DEFAULT now(),
    refreshed_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, branch_id, lock_type)
);

CREATE TABLE IF NOT EXISTS syson_element_locks (
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    stable_id TEXT NOT NULL,
    lock_type VARCHAR(50) NOT NULL DEFAULT 'edit',
    owner_user_id UUID NOT NULL REFERENCES syson_users(id),
    owner_session_id TEXT,
    reason TEXT,
    acquired_at TIMESTAMPTZ DEFAULT now(),
    refreshed_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, branch_id, stable_id, lock_type)
);

CREATE TABLE IF NOT EXISTS syson_integrity_checks (
    check_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    commit_id UUID,
    status VARCHAR(50) NOT NULL,
    error_count INT DEFAULT 0,
    warning_count INT DEFAULT 0,
    findings JSONB NOT NULL DEFAULT '[]',
    checked_at TIMESTAMPTZ DEFAULT now(),
    checked_by UUID REFERENCES syson_users(id)
);
```

---

## 4. API contract

Do not create a new REST controller class until the mapping issue is solved. Add endpoints to existing working controllers or first prove a new controller maps live. Preferred interim host:

- `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/ElementRestController.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/VersionControlController.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/UserController.java` for admin/security operations only.

All endpoints must return JSON, and tests must reject `text/html` SPA fallback.

### 4.1 Version refs and graph

- `GET /api/v1/projects/{projectId}/version-control/overview`
- `GET /api/v1/projects/{projectId}/version-control/tree?density=standard|full|baselines`
- `GET /api/v1/projects/{projectId}/version-control/refs`
- `GET /api/v1/projects/{projectId}/version-control/commits/{commitId}`
- `GET /api/v1/projects/{projectId}/version-control/compare?base={ref}&target={ref}`
- `POST /api/v1/projects/{projectId}/version-control/tags`
- `GET /api/v1/projects/{projectId}/version-control/tags`

Tree DTO:

```json
{
  "projectId": "...",
  "branches": [
    { "branchId": "...", "name": "main", "lane": 0, "headCommitId": "...", "protected": false }
  ],
  "commits": [
    {
      "commitId": "...",
      "branchId": "...",
      "commitNumber": 14,
      "parents": ["..."],
      "message": "Update interface block",
      "author": { "id": "...", "name": "..." },
      "committedAt": "...",
      "changeCount": 12,
      "hash": "..."
    }
  ],
  "baselines": [
    { "baselineId": "...", "commitId": "...", "name": "PDR", "status": "approved" }
  ],
  "tags": [
    { "tagId": "...", "commitId": "...", "name": "v1.0" }
  ]
}
```

### 4.2 Branch/merge/baseline

- `GET /api/v1/projects/{projectId}/branches`
- `POST /api/v1/projects/{projectId}/branches`
- `PUT /api/v1/projects/{projectId}/branches/{branchId}/protect`
- `POST /api/v1/projects/{projectId}/merge-requests`
- `GET /api/v1/projects/{projectId}/merge-requests`
- `GET /api/v1/projects/{projectId}/merge-requests/{mergeRequestId}`
- `POST /api/v1/projects/{projectId}/merge-requests/{mergeRequestId}/resolve`
- `POST /api/v1/projects/{projectId}/merge-requests/{mergeRequestId}/merge`
- `GET /api/v1/projects/{projectId}/baselines`
- `POST /api/v1/projects/{projectId}/baselines`
- `GET /api/v1/projects/{projectId}/baselines/{baselineId}/model`

### 4.3 Object history and warehouse query

- `GET /api/v1/projects/{projectId}/branches/{branchId}/elements/{stableId}/history`
- `GET /api/v1/projects/{projectId}/branches/{branchId}/elements/{stableId}/trace?depth=3&direction=both`
- `GET /api/v1/projects/{projectId}/branches/{branchId}/query/elements?type=PartDefinition&name=...`
- `POST /api/v1/projects/{projectId}/branches/{branchId}/query/traceability`
- `GET /api/v1/projects/{projectId}/branches/{branchId}/integrity/latest`
- `POST /api/v1/projects/{projectId}/branches/{branchId}/integrity/run`
- `GET /api/v1/projects/{projectId}/branches/{branchId}/cache-health`

### 4.4 Locks

- `GET /api/v1/projects/{projectId}/branches/{branchId}/lock`
- `POST /api/v1/projects/{projectId}/branches/{branchId}/lock`
- `PUT /api/v1/projects/{projectId}/branches/{branchId}/lock`
- `DELETE /api/v1/projects/{projectId}/branches/{branchId}/lock`
- `GET /api/v1/projects/{projectId}/branches/{branchId}/elements/{stableId}/lock`
- `POST /api/v1/projects/{projectId}/branches/{branchId}/elements/{stableId}/lock`
- `PUT /api/v1/projects/{projectId}/branches/{branchId}/elements/{stableId}/lock`
- `DELETE /api/v1/projects/{projectId}/branches/{branchId}/elements/{stableId}/lock`

Lock failure must return `409` JSON with current owner/session/expires metadata.

---

## 5. Implementation phases and tasks

### Phase A — Foundation and safety tests

#### Task A1: Write API fallback regression tests

**Files:**

- Create: `backend/application/syson-application/src/test/java/org/eclipse/syson/history/ApiJsonFallbackRegressionTest.java`

**Objective:** Prove protected history/version endpoints cannot return SPA HTML.

**Test behavior:**

- Unauthenticated `/api/v1/projects/test/version-control/tree` returns 401/403 JSON, not 200 HTML.
- Viewer without branch permission returns 403 JSON.

**Run:**

```bash
cd /root/syson-fork
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -Dtest=ApiJsonFallbackRegressionTest test -o
```

#### Task A2: Write stable ID unit tests

**Files:**

- Create: `backend/application/syson-application/src/test/java/org/eclipse/syson/history/StableSysmlIdServiceTest.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/history/StableSysmlIdService.java`

**Objective:** Stable object IDs survive repeated extraction.

**Required behavior:**

- Prefer existing Sirius/EMF ID when available.
- Fall back to deterministic path ID: `documentId + containmentPath + eClass + name`, hashed with SHA-256.
- Never use `UUID.randomUUID()` for canonical object IDs.

#### Task A3: Add JSON canonicalization and hashing tests

**Files:**

- Create: `SysmlObjectHasherTest.java`
- Create: `SysmlObjectHasher.java`

**Objective:** Hashes are deterministic regardless of JSON field order.

**Implementation rule:** Use Jackson `ObjectMapper` with ordered map keys and stable serialization. Do not use raw `toString()` or unordered `JSON.stringify` style behavior.

### Phase B — Schema v7/v8

#### Task B1: Add V7 head/history schema

**Files:**

- Create: `backend/application/syson-application/src/main/resources/db/migration/V7__enterprise_model_history_head_tables.sql`

Include:

- `syson_branch_heads`
- `syson_head_elements`
- `syson_head_relationships`
- `syson_head_diagrams`
- `syson_head_presentation_elements`
- `syson_commit_parents`
- additive columns on `syson_changes`
- indexes listed above

**Verification:** Run migration against disposable PostgreSQL, not production first.

#### Task B2: Add V8 locks/tags/merge/integrity schema

**Files:**

- Create: `V8__enterprise_locks_integrity_merge_tags.sql`

Include:

- `syson_tags`
- `syson_merge_requests`
- `syson_merge_conflicts`
- `syson_branch_locks`
- `syson_element_locks`
- `syson_integrity_checks`

#### Task B3: Add JPA entities and repositories

**Files:**

- Create entity/repository pairs under:
  - `org.eclipse.syson.history.entity`
  - `org.eclipse.syson.history.repository`
  - `org.eclipse.syson.locks.entity`
  - `org.eclipse.syson.locks.repository`
  - `org.eclipse.syson.integrity.entity`
  - `org.eclipse.syson.integrity.repository`

**Rules:**

- Explicit getters/setters; no Lombok.
- JSONB fields must be valid JSON strings or use Hibernate JSON mapping consistently.
- Use `projectId` as `String`/`TEXT` in new entities.

### Phase C — Canonical extraction

#### Task C1: Replace random-ID extraction

**Files:**

- Modify: `SaveEventListener.java` or supersede with `SysmlCanonicalExtractor.java`.

**Objective:** Build a `CanonicalModelSnapshot`:

```java
public record CanonicalModelSnapshot(
    String projectId,
    UUID branchId,
    List<CanonicalElement> elements,
    List<CanonicalRelationship> relationships,
    List<CanonicalDiagram> diagrams,
    List<CanonicalPresentationElement> presentationElements,
    String canonicalJson,
    String canonicalHash
) {}
```

**Rules:**

- Stable ID is string, not random UUID.
- Preserve source `documentId`, `semanticDataId`, and `representationId`.
- Extract all scalar attributes into `attributes` JSONB.
- Extract containment owner.
- Extract non-containment references as relationships.
- Skip standard libraries via existing `SysMLv2EditingContextPersistenceFilter` logic.

#### Task C2: Diagram extraction

**Files:**

- Create: `RepresentationCanonicalExtractor.java`

**Objective:** Parse `representation_content.content` JSON into diagrams and presentation elements.

**Required fields:**

- diagram id
- target object id
- kind/type
- nodes/symbols/presentation IDs
- bounds
- style
- semantic element links
- edges/links/routing points

#### Task C3: Backfill command

**Files:**

- Create: `HistoryBackfillService.java`
- Add an admin endpoint to existing working controller or a CLI runner gated by profile.

**Objective:** For existing projects, read current `document.content` and `representation_content.content`, create default main branch/head, and generate an initial import commit.

**Endpoint:**

- `POST /api/v1/user/admin/history/backfill?projectId=...`

Must be admin-only and audit-logged.

### Phase D — Diff, commit, and head materialization

#### Task D1: Diff service

**Files:**

- Create: `SysmlModelDiffService.java`
- Test: `SysmlModelDiffServiceTest.java`

**Behavior:**

- Index previous and current snapshots by `stableId`.
- Emit create/update/delete for elements, relationships, diagrams, presentations.
- For updates, include changed field paths and JSON Patch-style patch.
- Store full `before_object` and `after_object` for audit/reconstruction.

#### Task D2: Head materialization

**Files:**

- Create: `HeadMaterializationService.java`
- Test: `HeadMaterializationServiceTest.java`

**Behavior:**

- Apply create/update/delete to `syson_head_*` tables.
- For deletes, set `is_deleted=true`; do not physically delete by default.
- Update created/updated/deleted commit IDs.
- Update `syson_branch_heads` canonical cache and counts.

#### Task D3: Commit persistence

**Files:**

- Modify/supersede: `VersionControlService.java`
- Create: `CommitPersistenceService.java`

**Required improvements over current V4 service:**

- Write `syson_commit_parents` rows.
- Populate `before_hash` and `after_hash`.
- Use canonical hash chain.
- Use branch UUIDs only.
- Validate optimistic expected head commit.
- No branch head update until all changes and head tables are applied.

### Phase E — Save event integration

#### Task E1: Find and wire the correct Sirius save event hook

**Candidate hooks already identified:**

- `SemanticDataUpdatedEvent` with `@TransactionalEventListener`
- `RepresentationContentUpdatedEvent` with `@TransactionalEventListener`
- `IResourceToDocumentService.toDocument(Resource, boolean)` decoration
- `IEditingContextEventProcessorInitializationHook` only for initialization/migration, not every save

**Objective:** Ensure every real editor save triggers extraction/diff/commit exactly once.

**Acceptance test:**

- Create/update a model through normal SysON UI/API save path.
- Confirm one new `syson_commits` row and correct `syson_changes` rows.
- Confirm repeated save with no changes creates no commit.

#### Task E2: Add branch context handling

**Problem:** Sirius editor may not know selected branch yet.

**Implementation:**

- Default to main branch for legacy editor sessions.
- Store current branch in user/session/project metadata.
- Add `X-SysON-Branch-Id` header support in auth/fetch interceptor only after backend branch APIs exist.
- Never use branch display name as DB key.

### Phase F — Reconstruction, compare, and traceability

#### Task F1: Model reconstruction service

**Files:**

- Create: `ModelReconstructionService.java`

**Behavior:**

- Return `syson_branch_heads.canonical_json` fast path if hash valid.
- Otherwise reconstruct from `syson_head_*` and cache.
- Support reconstruction at a commit or baseline by replaying change ancestry from branch base/snapshot.

#### Task F2: Snapshot/retention strategy

**Schema:**

```sql
CREATE TABLE IF NOT EXISTS syson_model_snapshots (
    snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id TEXT NOT NULL,
    branch_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    snapshot_kind VARCHAR(50) DEFAULT 'periodic',
    canonical_hash VARCHAR(64) NOT NULL,
    canonical_json JSONB NOT NULL,
    object_count INT DEFAULT 0,
    size_bytes BIGINT,
    created_at TIMESTAMPTZ DEFAULT now()
);
```

**Policy:**

- Snapshot every N commits or when cumulative patch size exceeds threshold.
- Keep append-only changes forever for audit unless retention policy explicitly archives.
- Compress/archive old snapshots if needed later; do not prematurely delete audit changes.

#### Task F3: Traceability query service

**Files:**

- Create: `WarehouseQueryService.java`

**Queries:**

- upstream/downstream relationships to depth N;
- elements by type/name/qualified path/attribute JSONB;
- impact analysis from changed element since baseline;
- history of element and all connected relationships;
- branch compare impact summary.

### Phase G — Locks and integrity

#### Task G1: Branch and element locks

**Files:**

- Create: `BranchLockService.java`
- Create: `ElementLockService.java`
- Tests: lock acquire/refresh/release/conflict/expiry.

**Behavior:**

- Active lock blocks conflicting save with 409 JSON.
- Expired lock can be stolen transactionally.
- Only owner can refresh/release.
- Admin can force-release with audit event.

#### Task G2: Integrity checks

**Files:**

- Create: `IntegrityCheckService.java`

Checks:

- source/target exists and is not deleted;
- containment owner exists;
- no cyclic containment;
- diagram presentation references existing semantic objects;
- presentation edges reference existing nodes;
- required SysML fields exist by type;
- deleted elements are not referenced by active relationships;
- branch head hash equals materialized object hashes.

### Phase H — UI: version graph and controls

#### Task H1: Add version-control overlay without breaking login

**File:**

- `frontend/syson/public/auth.js`

**Rule:** Self-contained functions only. Do not refactor boot/login/interceptor functions.

Add:

- toolbar button: `Version Graph`
- overlay that fetches `/api/v1/projects/{projectId}/version-control/tree`
- SVG GitGraph-like lanes, branch rails, commit dots, baseline/tag badges
- density selector: baselines/standard/full
- click commit: show changes, author, date, impacted element count

If project ID cannot be derived reliably from the Sirius route, first add a backend/user project selector endpoint and show graph from dashboard project cards.

#### Task H2: Admin/enterprise controls

Add UI for:

- branch protect/unprotect;
- acquire/release branch lock;
- force-release locks for admin;
- create baseline/tag;
- run integrity check;
- view cache health.

Keep this in overlays/popovers; do not clutter rows with per-row buttons.

---

## 6. Testing and verification strategy

### Required automated tests

- Stable ID extraction repeated-save test.
- Canonical object hash deterministic test.
- Diff create/update/delete tests for elements/relationships/diagrams.
- Head materialization tests.
- Commit hash chain integrity tests.
- No-op save creates no commit.
- Branch creation materializes source branch head.
- Element history includes current branch + ancestor commits only.
- Compare reconstructs full model states, not just commit-local changes.
- Lock conflict/expiry tests.
- Integrity check tests.
- API JSON fallback tests.

### Required regression scripts

Create:

- `scripts/check-syson-history-regression.sh`
- `scripts/check-syson-version-graph-regression.sh`

History script should:

1. login as admin;
2. create or select test project;
3. backfill/import current model;
4. record initial commit count;
5. perform a controlled model update;
6. verify commit/change/head rows;
7. verify element history endpoint;
8. verify no-op save does not add commit;
9. verify viewer cannot mutate branch/locks;
10. verify integrity endpoint returns JSON and passes/warns correctly.

Keep existing scripts green:

```bash
cd /root/syson-fork
node -c frontend/syson/public/auth.js
bash scripts/check-syson-login-regression.sh
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -Dtest=EnterpriseAccountAccessAuditControllerRedTest,EnterpriseAccountAccessAuditRedTest test -o
```

---

## 7. Enterprise SaaS non-negotiables

- Multi-tenant isolation: every table includes tenant/project scoping or joins to it reliably.
- Access checks: every branch/object/history endpoint must enforce project membership plus branch/element permissions.
- Audit trail: admin, lock, branch, merge, baseline, restore, force-release, and permission operations must write `syson_audit_events` with valid JSONB metadata.
- Idempotent save behavior: no-op saves must not create noisy history.
- Optimistic concurrency: saves should include expected base/head commit; stale writers receive conflict response.
- Locking: locks expire and include session/device metadata.
- Data retention: avoid full blob on every commit; use object deltas + periodic snapshots.
- Integrity: every commit has hash chain and optional integrity check record.
- Observability: expose counts, duration, changed object count, extraction version, cache health.
- Backward compatibility: Sirius editor continues to work if sidecar extraction fails in warn-only mode during early rollout; later strict mode can block bad saves.
- Disaster recovery: branch head can be reconstructed from snapshots + changes; do not rely only on `document.content`.

---

## 8. Rollout plan

1. **Phase 0: Read-only backfill.** Extract current projects into head tables and initial commits. Do not alter editor save behavior.
2. **Phase 1: Shadow save pipeline.** On save, extract/diff/write history in shadow mode. If extraction fails, log and audit warning but do not block editor save.
3. **Phase 2: Strict integrity for new branches.** Require lock/concurrency/integrity checks for branch-aware editing paths.
4. **Phase 3: Version graph UI.** Expose graph and history views. Keep editing unchanged.
5. **Phase 4: Branch-aware editor sessions.** Add branch selector/session context and explicit branch writes.
6. **Phase 5: Merge/baseline workflows.** Enable protected branches, merge requests, baseline/tag creation.
7. **Phase 6: Warehouse analytics.** Add traceability query UI/API and export/report endpoints.

---

## 9. Known pitfalls for implementers

- Do not use `UUID.randomUUID()` for element identity in history.
- Do not treat `document.content` as binary; it is text JSON.
- Do not assume Flyway runs live; migrations may need manual application because `SPRING_FLYWAY_ENABLED=false`.
- Do not create new REST controller classes unless mapping is proven live.
- Do not trust HTTP 200; verify JSON content type/body.
- Do not refactor `auth.js` login boot path.
- Do not store raw strings in JSONB fields.
- Do not use branch names as primary keys.
- Do not compare only commit-local changes for merge/compare; reconstruct full ref state and diff.
- Do not implement full blob history as the primary history mechanism; use deltas plus periodic snapshots.

---

## 10. Acceptance criteria

The implementation is complete when all of this is true:

- A normal SysON editor save creates object-level changes and updates branch head tables.
- A repeated no-op save creates no commit.
- Element IDs are stable across repeated extraction and across server restart.
- Element history returns branch + ancestor changes with changed fields and before/after objects.
- Traceability queries can traverse relationships multi-level from a selected element.
- Branches use UUID IDs, materialize head state, and can be protected.
- Locks prevent conflicting writes and expire safely.
- Baselines/tags can anchor commits and reconstruct model state.
- Compare reconstructs full states at refs and reports object-level differences.
- Integrity checks detect dangling relationships and diagram references.
- Version graph UI shows branches, commits, merges, baselines, and tags without breaking login.
- Existing login/admin enterprise regressions remain green.
- Backend targeted tests and new history tests pass.
- Live deployment is verified against `https://syson.damuza-consulting.com` with JSON-body checks.

---

## 11. Immediate next task for another agent

Start with **Phase A and Phase B only**. Do not jump to UI.

Recommended first implementation commit sequence:

1. `test: add stable SysML id and API fallback regression tests`
2. `feat: add stable SysML object hashing utilities`
3. `db: add enterprise history head table migrations`
4. `feat: add history head JPA entities and repositories`
5. `test: add canonical diff and head materialization tests`

Stop after those are green and commit. Then implement extraction/diff integration in the next pass.
