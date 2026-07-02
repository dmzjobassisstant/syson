# SysON Fork Extensions vs Upstream

Detailed comparison of what this fork adds compared to upstream SysON (eclipse-syson/syson) and Sirius Web (eclipse-sirius/sirius-web). Organized for conflict management during upstream rebase/merge.

**Branch:** `rbac`
**Upstream fork-point commit:** `540ea78c86ab9b3af3acc9413a6e51dd7e795a19`
**Fork version:** `2025.6.1` (upstream is now at `2026.5.0` — 502 commits ahead)
**Custom commits on rbac:** 69
**Files added by fork:** 314
**Upstream files modified in-place:** 6

---

## Extension Architecture: The Sidecar Principle

All extensions are designed as **additive sidecars** — they run alongside the upstream Sirius Web editor without modifying its persistence path. The core Sirius editor path (`document.content → Explorer → Details → Diagrams`) remains untouched.

```
┌─────────────────────────────────────────────────┐
│           Sirius Web Editor (upstream)           │
│  document.content ──► Explorer ──► Details       │
│  representation_content ──► Diagram canvas        │
│  Liquibase core: project, semantic_data, document│
└───────────────────┬─────────────────────────────┘
                    │ read-only
                    ▼
┌─────────────────────────────────────────────────┐
│         Fork Sidecar Layer (extensions)          │
│  syson_users, syson_tenants, syson_sessions      │
│  syson_audit_events, syson_elements (history)    │
│  syson_branches, syson_commits, syson_changes    │
│  JWT auth, RBAC, audit, admin APIs               │
│  Direct element mutations (updateElement etc.)   │
└─────────────────────────────────────────────────┘
```

---

## Module-by-Module Diff

### 1. Authentication & Authorization (`org.eclipse.syson.auth`)

**Risk level: MEDIUM** — wraps upstream SecurityConfig, adds JWT filter chain

| Component | Type | Upstream Equivalent | Rebase Risk |
|-----------|------|-------------------|-------------|
| `AuthController` | New REST controller | None (upstream has no auth) | Low — additive |
| `UserController` | New REST controller | None | Low — additive |
| `SecurityConfig` | **Modifies** upstream | Replaces `SecurityConfig.java` | **HIGH** — must reconcile with upstream security changes |
| `JwtService` | New | None | Low |
| `SysonUserDetailsService` | New | None | Low |
| `AdminService` | New | None | Low |
| `AdminSeeder` | New | None | Low |
| `ProjectAccessService` | New | None | Low |
| JPA entities: `SysonUser`, `SysonTenant`, `AuditEvent`, etc. | New | None | Low — additive |
| JPA repositories | New | None | Low — additive |

**Database tables added:** `syson_users`, `syson_tenants`, `syson_tenant_memberships`, `syson_project_memberships`, `syson_audit_events`, `syson_password_reset_tokens`, `syson_email_verification_tokens`, `syson_invitations`, `syson_branch_permissions`, `syson_element_permissions`

**Files that conflict on rebase:**
- `SecurityConfig.java` — this is the primary conflict point. The fork replaces the upstream security configuration entirely. Must manually merge any upstream security changes.

### 2. Direct Element Mutations (`org.eclipse.syson.sysml.dto`, `org.eclipse.syson.sysml.datafetchers`)

**Risk level: LOW** — purely additive classes in the `syson-sysml-import` module

| Component | Type | Upstream Equivalent | Rebase Risk |
|-----------|------|-------------------|-------------|
| `UpdateElementInput` | New record | None | Low |
| `UpdateElementEventHandler` | New service | None | Low |
| `DeleteElementInput` | New record | None | Low |
| `DeleteElementEventHandler` | New service | None | Low |
| `AddChildElementInput` | New record | None | Low |
| `AddChildElementEventHandler` | New service | None | Low |
| `ManageRelationshipInput` | New record | None | Low |
| `ManageRelationshipEventHandler` | New service | None | Low |
| `Mutation*DataFetcher` (4 files) | New data fetchers | None | Low |
| `syson-import.graphqls` | **Modified** | Adds 4 mutations to existing schema | **MEDIUM** — must merge with upstream schema additions |

**Pattern used:** All use `IEditingContextEventHandler` (not `IRepresentationEventHandler`), matching the existing `insertTextualSysMLv2` pattern. This is upstream-compatible — no modification to the event dispatch mechanism.

**Files that conflict on rebase:**
- `syson-import.graphqls` — extends the upstream `extend type Mutation` block. Must manually merge if upstream adds new mutations to the same file.

### 3. GraphQL Compatibility Layer

**Risk level: MEDIUM** — adds fields to upstream GraphQL types

| File | Purpose | Rebase Risk |
|------|---------|-------------|
| `syson-auth-compat.graphqls` | Adds `viewer.language`, `viewer.namespaces`, `viewer.capabilities`, `project.capabilities` | **MEDIUM** — extends upstream Query type |
| `ViewerLanguageDataFetcher.java` | Resolves `viewer.language` | Low — additive |
| `ViewerNamespacesDataFetcher.java` | Resolves `viewer.namespaces` | Low — additive |
| `*CapabilitiesDataFetcher.java` | Resolves `viewer.capabilities` | Low — additive |

**Why these exist:** The bundled frontend JAR expects these fields. Without them, the React app fails to mount on page load (blank screen after login). This is a fork-specific fix for the frontend/backend contract mismatch.

### 4. Version Control (`org.eclipse.syson.vc`)

**Risk level: LOW** — entirely additive REST controller + JPA entities

| Component | Type | Rebase Risk |
|-----------|------|-------------|
| `VersionControlController` | New REST controller | Low |
| Branch/commit/tag JPA entities | New | Low |
| Element lock entities | New | Low |

**Database tables:** `syson_branches`, `syson_branch_heads`, `syson_commits`, `syson_changes`, `syson_tags`, `syson_merge_requests`, `syson_element_locks`

### 5. Element Persistence & History (`org.eclipse.syson.persistence`)

**Risk level: LOW** — additive REST + shadow-mode save listener

| Component | Type | Rebase Risk |
|-----------|------|-------------|
| `ElementRestController` | New REST controller | Low |
| `SemanticDataSaveListener` | New event listener | **MEDIUM** — hooks into upstream save event; must verify event signature on rebase |
| Element extraction to head tables | New | Low |

**Database tables:** `syson_head_elements`, `syson_branch_heads`

### 6. Frontend (`auth.js`, `test-harness.html`)

**Risk level: HIGH for auth.js** — heavily customized

| File | Purpose | Rebase Risk |
|------|---------|-------------|
| `frontend/syson/public/auth.js` | JWT login overlay, token interceptor, user bar, admin panel | **HIGH** — 800+ lines, not in upstream |
| `frontend/syson/public/test-harness.html` | API testing tool | Low — standalone HTML |

**auth.js is the #1 regression source.** It runs before the React app boots and controls the entire authentication flow. Never refactor its boot path (`loadState → blockApp → showLogin`).

### 7. Chat/AI (`org.eclipse.syson.chat`)

**Risk level: LOW** — additive stub controllers

| Component | Purpose | Rebase Risk |
|-----------|---------|-------------|
| `ChatController` | AI chat interface (stub) | Low |
| `SysmlValidationController` | SysML code validation | Low |

### 8. Database Migrations That Modify Upstream Tables

**Risk level: HIGH** — these are the most dangerous migrations for rebase

| Migration | Action | Risk |
|-----------|--------|------|
| V9 | Adds `is_read_only` column to upstream `document` table | **HIGH** — modifies core Sirius table |
| V10–V11 | Adds columns to `representation_metadata` / `representation_content` | **HIGH** — modifies core Sirius tables |
| V12 | Creates a global PostgreSQL `CREATE CAST` | **HIGH** — affects entire database |
| V1–V8, V13–V20 | Creates `syson_*` tables only | Low — additive |

**On rebase:** Review all V9–V12 migrations. If upstream has added the same columns or changed these tables, the migrations will fail. Must reconcile manually.

---

## Conflict Resolution Strategy

### Low-risk (additive): No action needed
New Java packages, new REST controllers, new JPA entities, new database tables. These don't touch upstream code and won't conflict.

### Medium-risk: Manual merge required
1. **`syson-import.graphqls`** — If upstream adds mutations to this file, manually merge the `extend type Mutation` block to include both upstream and fork mutations.
2. **`syson-auth-compat.graphqls`** — If upstream adds `viewer.*` fields, merge to avoid duplicate field definitions.
3. **`SemanticDataSaveListener`** — Verify the upstream `SemanticDataUpdatedEvent` class signature hasn't changed.

### High-risk: Careful reconciliation required
1. **`SecurityConfig.java`** — The fork's version replaces upstream entirely. Must manually diff and apply any upstream security changes (new filters, endpoint rules, CSRF changes).
2. **`auth.js`** — Not in upstream at all. On rebase, this file is preserved as-is. If the upstream frontend changes how authentication works, `auth.js` may need updates.

---

## Database Migration Safety

**Core Sirius tables (never modify):** `project`, `semantic_data`, `document`, `representation_metadata`, `representation_content`

**Fork tables (additive):** All `syson_*` prefixed tables. Created by Liquibase migrations that are fork-specific.

**Exception:** `document.is_read_only` — a column added to the upstream `document` table via V9 migration. This is the only modification to an upstream table.

---

## Build System

The fork uses the same Maven structure as upstream, with one critical difference:

**Never use `-am` (also-make):** It rebuilds the frontend stub JAR (2 KB, no `index.html`). The fork uses a prebuilt 1.75 MB frontend JAR from the Maven cache.

```bash
# Correct build command:
mvn -pl backend/application/syson-application -DskipTests -Dcheckstyle.skip=true package -o
```

The `-o` (offline) flag prevents GitHub Packages authentication failures.

---

## Files Modified vs Added (Summary)

### Modified upstream files (conflict-prone — all 6):
1. `backend/application/syson-application/pom.xml` — adds dependencies for auth/VC/persistence modules
2. `backend/application/syson-application/src/main/resources/application.properties` — Flyway/Liquibase/JWT settings
3. `backend/application/syson-sysml-import/src/main/resources/schema/syson-import.graphqls` — adds 4 mutation definitions
4. `frontend/syson/index.html` — adds `<script src="/auth.js">` injection
5. Two favicon files — cosmetic

### Added files (conflict-free):
- **314 files** across 7 modules: auth (~60 classes), VC (~40 classes), persistence (~15 classes), direct mutations (12 classes), GraphQL compat (7 data fetchers), frontend (auth.js 3,671 lines + test harness), documentation
- 20 Liquibase migration XMLs (fork-specific changeset IDs)
- 4 GraphQL schema extension files (`.graphqls`)
