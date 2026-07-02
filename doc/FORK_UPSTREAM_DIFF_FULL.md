# SysON Fork vs Upstream — Custom Additions & Rebase Risk Assessment

**Fork:** `dmzjobassisstant/syson` branch `rbac` (HEAD `fb46e2a1a`)
**Upstream:** `eclipse-syson/syson` (`upstream/main`)
**Fork-point / upstream-aligned commit:** `540ea78c8` ("[1396] Add Edge representation of IncludeUseCaseUsage for General View") — an ancestor of `upstream/main`
**Fork version:** `2025.6.1` (Spring Boot 3.5.0 parent, Sirius Web `2025.6.1`)
**Upstream/main version:** `2026.5.0` — **the fork is 502 commits behind upstream/main**
**Custom commits on top of fork-point:** **69 commits**, 314 files added, 6 upstream files modified

---

## Executive Summary

The fork implements an **enterprise sidecar layer** on top of SysON 2025.6.1: JWT authentication, RBAC, multi-tenant user management, element persistence, Git-like version control (branches/commits/baselines/tags/merge), model history warehouse, element locking, audit logging, and an LLM chat pipeline. Almost all of it is **additive** (new packages, new `syson_*` tables, new REST endpoints under `/api/v1`). The design intent is that sidecar failures never break the Sirius editor save path.

The rebase risk concentrates in **6 modified upstream files** and **4 cross-cutting concerns** (Flyway enabled alongside Liquibase, the GraphQL compatibility shim, the `auth.js` frontend injection, and 4 migrations that patch upstream tables). Because the fork has not tracked upstream for ~500 commits, the upstream `2025.6.1 → 2026.5.0` jump will touch many of the same surface areas (GraphQL schema, application.properties, pom.xml, diagram views) and require careful conflict resolution.

---

## Rebase Risk Legend

| Level | Meaning |
|-------|---------|
| **LOW** | Purely additive — new files/packages/tables that upstream never touches. No merge conflict possible. |
| **MEDIUM** | Wraps or hooks into upstream behavior via extension points (Spring beans, event listeners, `extend type` GraphQL). Conflicts likely only if upstream changes the extension contract. |
| **HIGH** | Modifies an upstream file in-place, or patches upstream DB tables/catalog. Will conflict on any upstream change to the same file. |

---

## Module 1 — Authentication & Security (`org.eclipse.syson.auth`, 56 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `SecurityConfig.java` | Spring Security filter chain: stateless JWT, CSRF off, method security, JSON 401/403 for `/api/**`, permit `/api/auth/**`, `/api/graphql`, `/actuator/**` | **Replaces upstream's default security** (upstream SysON ships no `SecurityConfig`) | Wraps upstream servlet | **HIGH** — controls the entire HTTP security surface; any upstream security change conflicts |
| `JwtAuthenticationFilter` | Extracts/validates JWT from `Authorization: Bearer`, loads `SysonUserDetailsService`, sets `SecurityContext` | Inserted before `UsernamePasswordAuthenticationFilter` | Additive filter | **MEDIUM** |
| `JwtService` / `TokenService` | HMAC-SHA256 JWT sign/verify/refresh; secret from `syson.auth.jwt.secret` | New | Additive | LOW |
| `TenantContext` + `TenantFilter` | ThreadLocal tenant isolation; `TenantFilter` runs after JWT filter | New | Additive | LOW |
| `SysonUserDetailsService` | Bridges `syson_users` → Spring Security `UserDetails` | New | Additive | LOW |
| `AuthController` (`/api/auth/**`) | `login`, `refresh`, `logout` — JWT issuance | New endpoints | Additive | LOW |
| `UserController` (`/api/v1/user/**`) | `/me`, `/me/password`, `/me/projects`, `/ping`, password reset, **admin** user/tenant/project-member/audit endpoints (30+ mappings) | New endpoints | Additive (but see Controller Registration Constraint below) | LOW |
| `AdminSeeder` / `AdminService` | Seeds default `admin`/`admin` superuser on startup | New | Additive | LOW |
| `ProjectAccessService` | RBAC permission checks per project (SUPERUSER/ADMIN/USER/VIEWER) | New | Additive | LOW |
| Entities (`SysonUser`, `SysonTenant`, `ProjectMembership`, `TenantMembership`, `AuditEvent`, `Invitation`, `PasswordResetToken`, `EmailVerificationToken`, `BranchPermission`, `ElementPermission`) | JPA entities mapped to `syson_*` tables | New | Additive | LOW |
| Repositories (10) | Spring Data JPA repos for above entities | New | Additive | LOW |
| Services (`AccessControlService`, `AccountAdministrationService`, `AuditLogService`, `PasswordResetService`, `RoleManagementService`) | Business logic | New | Additive | LOW |
| `audit/RbacAuditTrail*` | Separate RBAC audit trail entity/repo/service (JSONB metadata) | New | Additive | LOW |
| `model/` (`TenantRole`, `ProjectRole`, `AuditEventType`) | Enums | New | Additive | LOW |

**pom.xml deps added:** `spring-boot-starter-security`, `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `jjwt-api/impl/jackson`, `flyway-core`, `flyway-database-postgresql`

---

## Module 2 — GraphQL Compatibility Shim (`org.eclipse.syson.auth` DataFetchers + `syson-auth-compat.graphqls`)

This module bridges the **2025.6.1 backend** to a frontend that expects newer Sirius Web GraphQL fields. It is the single most fragile integration point.

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `syson-auth-compat.graphqls` | `extend type Viewer` (language, namespaces, capabilities, allProjectTemplates); `extend type Project` (capabilities); `extend type EditingContext` (workbenchConfiguration, representationDescription); `extend input CreateProjectInput` (templateId, libraryIds); new types `ViewerCapabilities`, `ProjectCapabilities`, `WorkbenchConfiguration`, etc. | **Extends upstream GraphQL types** via `extend` (does not redefine) | Wraps upstream schema | **HIGH** — if upstream 2026.x adds any of these fields natively, GraphQL Java rejects duplicates → startup `ValidationError` |
| `ViewerLanguageDataFetcher`, `ViewerNamespacesDataFetcher`, `ViewerCapabilitiesDataFetcher`, `ProjectCapabilitiesDataFetcher`, `ViewerAllProjectTemplatesDataFetcher` | DataFetchers for the compat fields | Implement upstream `DataFetcher` interface | Wraps upstream | **MEDIUM** |
| `ProjectTemplateContextSchemaPatcher` (`implements TypeDefinitionConfigurer`) | Programmatically adds `context` argument to existing `Viewer.projectTemplates` field + injects `ProjectTemplateContext` enum (avoids redefining upstream types) | Mutates upstream `TypeDefinitionRegistry` at startup | Wraps upstream | **HIGH** — targets specific upstream field definition; breaks if upstream renames/removes `projectTemplates` |
| `EditingContextWorkbenchConfigurationDataFetcher`, `EditingContextRepresentationDescriptionDataFetcher` | Serve `workbenchConfiguration` (newer frontend bootstrap query not in 2025.6.1) | Wraps upstream `EditingContext` | **MEDIUM** |
| `DiagramDescriptionCompatCodeRegistryTransformer` (`implements IGraphQLCodeRegistryTransformer`) | Null-safe `nodeDescriptions` / `dropNodeCompatibility` wiring | Wraps upstream code registry | **MEDIUM** |

> **Rebase note:** Upstream 2026.x likely added native `workbenchConfiguration`, `capabilities`, etc. On rebase, the compat shim must be removed field-by-field as upstream provides them, or startup fails with duplicate-type errors.

---

## Module 3 — Element Persistence (`org.eclipse.syson.persistence`, 17 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `ElementRestController` (`/api/v1/projects/{id}/branches/{id}/elements...`) | CRUD REST for elements, relationships, diagrams, nodes, canonical JSON export | New endpoints | Additive | LOW |
| `ElementPersistenceService`, `CanonicalJsonService` | Extract/serialize SysML elements to sidecar tables | New | Additive | LOW |
| `SaveEventListener` | Hooks into Sirius save lifecycle | New | Additive | LOW |
| Entities (`ElementEntity`, `RelationshipEntity`, `DiagramEntity`, `DiagramNodeEntity`, `DiagramEdgeEntity`) + Repositories (5) | Map to `syson_elements`, `syson_relationships`, `syson_diagrams`, `syson_diagram_nodes`, `syson_diagram_edges` | New | Additive | LOW |

---

## Module 4 — Version Control (`org.eclipse.syson.vc`, 16 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `VersionControlController` (`/api/v1/projects/{id}/...`) | 30+ endpoints: branches CRUD, commits, baselines, VC overview/tree/compare, branch lock, element locks, save trigger | New endpoints | Additive | LOW |
| `VersionControlService`, `BranchDiffService`, `BranchProjectionService` | Git-like branch/commit/baseline/diff logic | New | Additive | LOW |
| Entities (`BranchEntity`, `CommitEntity`, `ChangeEntity`, `BaselineEntity`) + Repositories (4) | Map to `syson_branches`, `syson_commits`, `syson_changes`, `syson_baselines` | New | Additive | LOW |

---

## Module 5 — Model History / Warehouse (`org.eclipse.syson.history`, 31 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| **`SemanticDataSaveListener`** | `@TransactionalEventListener` on upstream `SemanticDataUpdatedEvent` → triggers extract→diff→commit→materialize head. **`@Transactional(propagation=REQUIRES_NEW)`** so failures never roll back the Sirius save. | **Hooks into upstream Sirius Web save event** | Wraps upstream (shadow mode) | **HIGH** — depends on upstream `org.eclipse.sirius.web.domain.boundedcontexts.semanticdata.events.SemanticDataUpdatedEvent`; if upstream renames/removes this event class, extraction silently stops |
| `ModelSaveHistoryService`, `HeadMaterializationService`, `ElementHistoryService`, `ModelReconstructionService`, `CommitPersistenceService`, `VersionGraphService`, `StableSysmlIdService`, `SysmlCanonicalExtractor`, `SysmlModelDiffService`, `SysmlObjectHasher` | Append-only history pipeline (BowTie Pilot pattern) | New | Additive | LOW |
| Entities (Head* with composite IDs: `HeadElement`, `HeadRelationship`, `HeadDiagram`, `HeadPresentationElement`, `BranchHead`, `CommitParent`, `ModelSnapshot`) + Repositories (7) | Materialized "head" tables for fast current-state queries | New | Additive | LOW |

---

## Module 6 — Locks / Merge / Integrity (`org.eclipse.syson.locks`, 17 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `ElementLockService`, `BranchLockService`, `IntegrityCheckService` | Element-level and branch-level locking, merge integrity checks | New | Additive | LOW |
| Entities (`ElementLock`, `BranchLock`, `MergeRequest`, `MergeConflict`, `IntegrityCheck`, `Tag`) + Repositories (6) | Map to `syson_element_locks`, `syson_branch_locks`, `syson_merge_requests`, `syson_merge_conflicts`, `syson_integrity_checks`, `syson_tags` | New | Additive | LOW |

---

## Module 7 — LLM Chat Pipeline (`org.eclipse.syson.chat`, 22 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `ChatController` (`/api/v1/projects/{id}/chat/**`) | `/process`, `/generate`, `/modify`, `/execute`, `/conversations` — LLM-driven SysML modification with change approval | New endpoints | Additive | LOW |
| `SysmlValidationController` (`/api/v1/sysml/validate`) | SysML syntax validation endpoint | New endpoint | Additive | LOW |
| `ChatService`, `LlmClientService`, `ChangeExecutionService`, `ModelSerializationService`, `SysmlSyntaxValidator`, `ChatStructuredOutputParser` | LLM orchestration, structured output parsing, change execution | New | Additive | LOW |
| Entities (`ChatConversationEntity`, `ChatMessageEntity`) + Repositories (2) | Map to `syson_chat_conversations`, `syson_chat_messages` | New | Additive | LOW |

---

## Module 8 — Project Settings (`org.eclipse.syson.settings`, 3 files)

| Addition | What it does | Extends/Replaces | Sidecar? | Risk |
|----------|--------------|------------------|----------|------|
| `ProjectSettingService`, `ProjectSettingRepository`, `ProjectSetting` | Per-project JSONB settings (e.g. `element_locking_enabled`, `default_branch`) | New | Additive | LOW |

---

## Module 9 — Flyway Migrations (20 files, `db/migration/V2–V22`)

**Upstream has NO `db/migration/` directory** — upstream uses Liquibase (`db/db.changelog-master.xml`). The fork adds Flyway **alongside** Liquibase.

### Sidecar tables (LOW risk — upstream never touches these)

| Migration | Tables created |
|-----------|---------------|
| V2 | `syson_users`, `syson_tenants`, `syson_tenant_memberships`, `syson_sessions`, `syson_audit_events` |
| V3 | `syson_elements`, `syson_relationships`, `syson_diagrams`, `syson_diagram_nodes`, `syson_diagram_edges` |
| V4 | `syson_branches`, `syson_commits`, `syson_changes`, `syson_baselines` |
| V5 | `syson_project_members` |
| V6 | `syson_branch_permissions`, `syson_element_permissions`, `syson_invitations`, `syson_password_reset_tokens`, `syson_email_verification_tokens` |
| V7 | `syson_head_elements`, `syson_head_relationships`, `syson_head_diagrams`, `syson_head_presentation_elements`, `syson_branch_heads`, `syson_commit_parents`, `syson_model_snapshots` |
| V8 | `syson_branch_locks`, `syson_element_locks`, `syson_merge_requests`, `syson_merge_conflicts`, `syson_integrity_checks`, `syson_tags` |
| V13 | `syson_rbac_audit_trail` |
| V14 | (audit trail — see V13/V14 naming overlap) |
| V15 | `syson_platform_settings` |
| V16 | Rebuilds warehouse head tables (drops/recreates V7 head tables) |
| V17 | `syson_object_versions`, rebuilds `syson_commit_parents` |
| V18 | `syson_element_locks` (idempotent re-add) |
| V19 | `syson_project_settings` |
| V20 | Indexes on `syson_changes`, etc. |
| V22 | `syson_chat_conversations`, `syson_chat_messages` |

> **Note:** V21 does not exist (gap in numbering). V16/V17/V18 show signs of iterative schema evolution (drops + recreates).

### Migrations that PATCH UPSTREAM TABLES (HIGH risk)

| Migration | What it patches | Why | Risk |
|-----------|----------------|-----|------|
| **V9** | `ALTER TABLE document ALTER COLUMN is_read_only SET DEFAULT false` | Sirius Web 2025.6.1 inserts document rows without setting `is_read_only`, but the column had no DEFAULT | **HIGH** — touches upstream `document` table |
| **V10** | `ALTER TABLE representation_metadata/representation_content ALTER COLUMN representation_metadata_id SET DEFAULT gen_random_uuid()` | Upstream JDBC binds UUID but column is TEXT in live DB | **HIGH** — touches upstream tables |
| **V11** | `CREATE CAST (uuid AS text) WITH INOUT AS IMPLICIT` | Patches **PostgreSQL catalog** so UUID params auto-cast to TEXT columns (requires superuser) | **HIGH** — global DB catalog change; may conflict with upstream schema assumptions |
| **V12** | `ALTER TABLE representation_metadata ALTER COLUMN semantic_data_id DROP NOT NULL` | Upstream inserts representation_content with null semantic_data_id | **HIGH** — weakens upstream NOT NULL constraint |

> **Rebase note:** V9–V12 are workarounds for 2025.6.1 schema quirks. Upstream 2026.x may have fixed these natively, making the migrations redundant or conflicting. Each must be audited against the upstream 2026 Liquibase changelog.

---

## Module 10 — Modified Upstream Files (6 files, HIGH risk)

These are the **only** files modified in-place from the fork-point. Every one will conflict if upstream touches it.

| File | Modification | Risk |
|------|-------------|------|
| **`backend/application/syson-application/pom.xml`** | Added 9 dependencies (security, JPA, web, JWT, Flyway). **Parent unchanged** (Spring Boot 3.5.0). Version stays `2025.6.1`. | **HIGH** — upstream 2026.x changes version to `2026.5.0`, changes artifact names (e.g. `syson-diagram-general-view` → `syson-standard-diagrams-view`), and restructures dependencies |
| **`application.properties`** | Added `spring.flyway.enabled=true` + `spring.flyway.locations`, `liquibase.analytics.enabled=false`, `syson.auth.jwt.secret`, `logging.level.org.eclipse.sirius.web=debug` | **HIGH** — upstream 2026.x properties will have diverged significantly |
| **`syson-import.graphqls`** | Added 4 mutations: `updateElement`, `deleteElement`, `addChildElement`, `manageRelationship` (+ their Input/Payload types) | **HIGH** — upstream GraphQL schema changes are the most common conflict source |
| **`frontend/syson/index.html`** | Changed `<title>SysON</title>` → `<title>SysMLv2 Architect</title>` + added `<script src="/auth.js"></script>` | **MEDIUM** — small but the auth.js injection is load-bearing |
| **`frontend/syson/public/favicon.png`** | Rebranded favicon | LOW (binary) |
| **`frontend/syson/public/favicon.svg`** | Rebranded favicon | LOW (binary) |

---

## Module 11 — Frontend: `auth.js` System (3,671 lines, fully custom)

**Not in upstream at all.** This is the fork's entire frontend authentication + enterprise UI layer, injected as a plain `<script>` before the React app boots.

| Component | What it does | Risk |
|-----------|-------------|------|
| **Login overlay** (`#syson-auth-overlay`) | Custom HTML/CSS login card rendered before React mounts; `blockApp()` injects `#root { display: none !important }` to hide the SPA until authenticated | **HIGH** — fragile boot sequence (`loadState → blockApp → showLogin`); documented as repeatedly broken by agents |
| **JWT interceptor** | Monkey-patches `window.fetch` and `XMLHttpRequest.setRequestHeader` to inject `Authorization: Bearer <jwt>` on every request | **MEDIUM** — wraps all upstream network calls |
| **Token refresh** | `refreshToken()` calls `/api/auth/refresh` before expiry | Additive | LOW |
| **User bar** (`#syson-user-bar`) | Persistent top-right bar with email, role badge, logout/dashboard/admin buttons; uses `MutationObserver` to re-mount on SPA route changes | **MEDIUM** — DOM manipulation over React |
| **Dashboard overlay** | Profile, password change, "my projects" — full-page overlay | Additive | LOW |
| **Admin console** | User management, project membership, audit trail viewer — rendered as overlay HTML | Additive | LOW |
| **Version control UI** (GitGraph) | Branch graph, branch selector dropdown, branch creation, element locking toggles | Additive | LOW |
| **i18n fix** (`fixVisibleTranslationKeys`) | Patches raw translation keys visible in UI | Wraps upstream | **MEDIUM** |

**Files:** `frontend/syson/public/auth.js` (source), `frontend/syson-webapp/src/main/resources/static/auth.js` (baked into JAR), served live by nginx at `/var/www/syson/auth.js`.

---

## Module 12 — Frontend React Extensions (2 files)

| File | What it does | Risk |
|------|-------------|------|
| `SysONBranchIndicator.tsx` | React component showing current branch name in diagram toolbar (polls `/api/v1/projects/{id}/settings/default-branch`) | Additive | LOW |
| `SysONSaveButton.tsx` | React save button that triggers history pipeline via save endpoint | Additive | LOW |

> **Note:** These are not wired into the upstream React app's extension registry in the diff — they appear to be standalone components. Confirm whether they are actually rendered.

---

## Module 13 — i18n Files (28 JSON files)

28 translation files under `backend/application/syson-application/src/main/resources/i18n/{en,fr}/` — **not in upstream** at the fork-point. These provide the `/api/locales/{lang}/{namespace}.json` endpoint backing. Upstream may have added its own i18n in 2026.x.

---

## Module 14 — Documentation & Scripts (24 files)

All additive, LOW risk: `SYSON_STABILIZATION_GUIDE.md`, `AGENTS.md`, `SYSON_ARCHITECTURE_KB.md`, `SIRIUS_WEB_INTERFACE_CONTROL_DOCUMENT.md`, `SIRIUS_WEB_INTEGRATION_KB.md`, `SYSON_ENTERPRISE_ACCESS_AUDIT_HANDOFF.md`, `DEPENDENCY_RISK_AUDIT.md`, various logs, `scripts/check-syson-login-regression.sh`, `scripts/check-syson-enterprise-access-regression.sh`, etc.

---

## Cross-Cutting Rebase Concerns

### 1. Version Drift (CRITICAL)
The fork is on **2025.6.1**; upstream/main is **2026.5.0** (502 commits ahead). Upstream has:
- Renamed modules (`syson-diagram-general-view` → `syson-standard-diagrams-view`, removed `syson-table-requirements-view`)
- Changed version numbers throughout all pom.xml files
- Likely evolved the GraphQL schema (potentially adding native `workbenchConfiguration`, `capabilities`, etc.)

**The fork CANNOT fast-forward.** A rebase requires resolving the 6 modified files against 502 commits of upstream changes.

### 2. Flyway vs Liquibase
Upstream uses **Liquibase only**. The fork enables **Flyway alongside Liquibase** (`spring.flyway.enabled=true`). On rebase:
- Keep Flyway for `syson_*` migrations
- Ensure upstream Liquibase changes don't conflict with V9–V12 (which patch the same upstream tables)
- The live deployment runs with `SPRING_FLYWAY_ENABLED=false` (manual SQL application)

### 3. Controller Registration Constraint
The fork documents that new `@RestController` classes **silently fail to register** in SysON due to servlet filter scope. All enterprise endpoints are crammed into `UserController`, `AuthController`, `ElementRestController`, `VersionControlController`. Exception: `LocaleController` works standalone because `/api/locales/**` doesn't conflict with Sirius Web paths. This is an architectural constraint, not a rebase conflict, but it limits how endpoints can be reorganized.

### 4. Save Event Hook Fragility
`SemanticDataSaveListener` depends on `org.eclipse.sirius.web.domain.boundedcontexts.semanticdata.events.SemanticDataUpdatedEvent`. If upstream 2026.x renames or restructures this event class, the entire history pipeline silently stops working (shadow mode = no error, just no history recorded).

---

## Recommended Rebase Strategy

1. **Do NOT attempt a direct `git rebase upstream/main`** — 502 commits × 6 modified files = merge hell.
2. **Use the stabilization-branch layering approach** from `SYSON_STABILIZATION_GUIDE.md` §4.2:
   - Start from a fresh upstream 2026.x checkout
   - Cherry-pick extensions in layers: auth → RBAC → persistence → VC → history → locks → chat
   - Test the Sirius editor path after each layer
3. **Re-evaluate V9–V12** against upstream 2026.x schema — they may be obsolete.
4. **Re-evaluate the GraphQL compat shim** field-by-field — upstream 2026.x likely provides `workbenchConfiguration`, `capabilities`, etc. natively.
5. **Keep `auth.js` as-is** — it's independent of upstream React code and injects via `index.html`.
6. **Preserve `SPRING_FLYWAY_ENABLED=false`** in the live deployment.
