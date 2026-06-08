# SysON Architecture Knowledge Base

> **Purpose:** Single-source reference for agents making architectural decisions about extending SysON — particularly for adding account management, role/permission systems, and access control to model elements.
> **Version:** v2025.6.1-rbac (fork commit `4b4ef7879`)
> **Live deployment:** `https://syson.damuza-consulting.com` (Docker container `syson:syson-rbac:latest` on `:8080`, nginx reverse proxy on `:443`)

---

## 1. EXECUTIVE SUMMARY

SysON is a **web-based SysML v2 modeling workbench** built on **Eclipse Sirius Web** — a Spring Boot + React framework for collaborative modeling. The fork at `/root/syson-fork` adds: JWT authentication, tenant/user management, project-level RBAC, element-level persistence, Git-like version control (branches/commits/baselines), and canonical JSON export.

**Architecture:** Thin custom layer (~55 frontend files, ~20 backend service files) on a thick Sirius Web framework. Customization is via extension points, not framework modification.

**Key fact for access control design:** The entire model is hierarchical (packages containing elements), and the current access model only gates at the **project** level. There's no per-element, per-package, or per-diagram access control. This is the primary gap.

**Sidecar architecture:** Enterprise features (auth, RBAC, element persistence, version control, history/warehouse, locks) are **additive sidecars** that run alongside the upstream Sirius Web editor. They never modify upstream core tables, never replace `document.content` persistence, never block editor saves, and never redefine upstream GraphQL types. See `SYSON_STABILIZATION_GUIDE.md` for the full build/deploy path, sidecar rules, and recovery procedure.

**Upstream-aligned commit:** `540ea78c86ab9b3af3acc9413a6e51dd7e795a19` — the known-good Sirius Web/SysON baseline. All enterprise extensions were added on top of this commit.

---

## 2. REPOSITORY STRUCTURE

```
/root/syson-fork/
├── backend/
│   ├── application/
│   │   ├── syson-application/          ★ MAIN APP — Spring Boot, auth, REST, VC, GraphQL compat
│   │   ├── syson-application-configuration/   Sirius integration, EMF config, libraries, nodes, omnibox
│   │   ├── syson-sysml-import/         Textual SysML → EMF
│   │   ├── syson-sysml-export/         EMF → textual SysML
│   │   ├── syson-sysml-validation/     Validation rules
│   │   └── syson-frontend/             Frontend asset packaging (no Java source)
│   ├── services/
│   │   ├── syson-services/             ★ Core modeling services (labels, init, move, delete, tools)
│   │   ├── syson-sysml-rest-api-services/  SysML-object REST + JSON serialization
│   │   ├── syson-direct-edit-grammar/  ANTLRv4 grammar for on-diagram editing
│   │   └── 7 more (diagram/form/model/representation/table/tree/metamodel services) — config only
│   ├── views/
│   │   ├── syson-diagram-common-view/  ★ Shared diagram node/edge/tool abstractions
│   │   ├── syson-diagram-general-view/ General View diagram
│   │   ├── syson-diagram-actionflow-view/ Action Flow diagram
│   │   ├── syson-diagram-interconnection-view/ Interconnection View diagram
│   │   ├── syson-diagram-statetransition-view/ State Transition diagram
│   │   ├── syson-tree-explorer-view/   Explorer tree with filters
│   │   └── 4 more (common/standard/table-requirements/diagram-tests)
│   ├── metamodel/
│   │   ├── syson-sysml-metamodel/      150+ SysMLv2 type interfaces (EMF)
│   │   ├── syson-sysml-metamodel-edit/  Item providers for UI editing
│   │   └── syson-siriusweb-customnodes-metamodel/  4 custom node styles
│   ├── releng/                          Resources, checkstyle, test coverage
│   └── tests/syson-tests/               Architecture/coding-rule integration tests
├── frontend/
│   ├── syson/                          ★ Main React app (entry, theme, extensions, auth.js)
│   └── syson-components/               ★ Component library (diagram nodes, toggles, textual insert)
├── doc/                                 ADRs, plans, docs site
├── scripts/                             Deployment/verification scripts
└── docker-compose.yml                   PostgreSQL 12 + app on :8080
```

**Module count:** 31 Maven modules (6 application, 10 service, 10 view, 4 metamodel, 1 test).

---

## 3. TECHNOLOGY STACK

| Layer | Technology | Version |
|-------|-----------|---------|
| Backend framework | Spring Boot 3.x (Jakarta EE) | — |
| Security | Spring Security 6 + JJWT (HMAC-SHA256) | — |
| Modeling framework | Eclipse Sirius Web + EMF | — |
| Persistence | JPA/Hibernate + PostgreSQL 12/16 | — |
| Migrations | Liquibase (upstream) + Flyway (fork) | Dual |
| Frontend | React 18 + TypeScript 5.4 | Vite 5.2 |
| UI library | MUI v7.0.2 | — |
| GraphQL client | Apollo Client 3.10.4 | — |
| State machines | XState 4.32.1 | — |
| Diagram canvas | xyflow/react 12.6.0 | — |
| Routing | react-router-dom 6.26 | — |
| Real-time | GraphQL subscriptions over WebSocket | `subscriptions-transport-ws` |
| Grammar parsing | ANTLRv4 | Direct-edit |
| Textual import | SysIDE CLI (Node.js) | `syside-cli.js` |

---

## 4. AUTHENTICATION & AUTHORIZATION (CURRENT STATE)

### 4.1 Authentication Flow

```
Browser loads index.html
  └─ <script src="/auth.js"> executes BEFORE React
       ├─ loadState() → localStorage.syson_auth
       ├─ IF no token:
       │    blockApp() → injects <style>#root{display:none!important}
       │    showLogin('') → renders login overlay
       ├─ IF token present:
       │    Start 4h refresh interval
       │    Mount user bar after DOMContentLoaded
       └─ Login flow:
            POST /api/auth/login → JWT
            localStorage.syson_auth = {token, email, roles, tenantId}
            window.location.reload()
```

### 4.2 Backend Auth Architecture

```
Request → JwtAuthenticationFilter (validate JWT, set SecurityContext + request attrs)
        → TenantFilter (set TenantContext thread-local)
        → Controller (reads TenantContext)
        → Response → TenantContext.clear()
```

**Key classes:**
- `SecurityConfig` — Stateless JWT, CSRF disabled. Public paths: `/api/auth/**`, `/api/graphql`, `/actuator/**`. Admin-only: `/api/v1/user/admin/**` requires `ROLE_SUPERUSER` or `ROLE_ADMIN`.
- `JwtService` — Generates/validates JWTs (24h expiry). Claims: `sub`=email, `tenantId`, `userId`.
- `SysonUserDetailsService` — Loads user by email, resolves `ROLE_*` authorities from tenant memberships.
- `JwtAuthenticationFilter` — OncePerRequestFilter, extracts Bearer token, sets SecurityContext.
- `TenantFilter` — Sets thread-local `TenantContext` (tenantId + userId).
- `AdminSeeder` — Seeds default tenant + superuser on startup (credentials from env: `SYSON_BOOTSTRAP_EMAIL`/`SYSON_BOOTSTRAP_PASSWORD`, defaults `admin`/`admin`).
- `ProjectAccessService` — Project-level RBAC: `hasProjectAccess(projectId, requiredRole)` with role ranking admin(3) > user(2) > viewer(1).

### 4.3 Role System

**Two-tier role model:**

| Tier | Table | Roles | Scope |
|------|-------|-------|-------|
| Tenant (organization) | `syson_tenant_memberships` | `superuser`, `admin`, `editor`, `viewer` | Global tenant operations |
| Project | `syson_project_members` | `admin`, `user`, `viewer` | Per-project access |

**Spring Security mapping:** `ROLE_` + role.toUpperCase() (e.g., `ROLE_SUPERUSER`, `ROLE_ADMIN`).

**Current authorization gaps:**
- No `@PreAuthorize` on element/VC controllers (marked as "Phase 5" TODO)
- Tenant isolation NOT enforced on queries
- No per-element or per-package access control

### 4.4 REST API Surface

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `/api/auth/login` | POST | Public | Authenticate, return JWT + roles |
| `/api/auth/refresh` | POST | Public | Refresh JWT |
| `/api/auth/logout` | POST | Public | No-op (client discards token) |
| `/api/v1/user/me` | GET | Auth | Current user profile |
| `/api/v1/user/me/password` | PUT | Auth | Change password |
| `/api/v1/user/me/projects` | GET | Auth | List user's project memberships |
| `/api/v1/user/ping` | GET | Public | Health check |
| `/api/v1/user/admin/users` | GET | Admin | List all users |
| `/api/v1/user/admin/users` | POST | Admin | Create user |
| `/api/v1/user/admin/users/{id}/password` | PUT | Admin | Reset user password |
| `/api/v1/user/admin/projects/{id}/members` | GET/POST/DELETE | Admin | Manage project memberships |
| `/api/v1/projects/{pid}/branches/{bid}/elements` | GET | Auth | List elements |
| `/api/v1/projects/{pid}/branches/{bid}/elements/{eid}` | GET | Auth | Get element |
| `/api/v1/projects/{pid}/branches/{bid}/elements/{eid}/children` | GET | Auth | Element children |
| `/api/v1/projects/{pid}/branches/{bid}/relationships` | GET | Auth | List relationships |
| `/api/v1/projects/{pid}/branches/{bid}/diagrams/{did}/nodes` | GET | Auth | Diagram nodes |
| `/api/v1/projects/{pid}/branches/{bid}/export` | GET | Auth | Canonical JSON export |
| `/api/v1/projects/{pid}/branches` | GET/POST | Auth | Branch CRUD |
| `/api/v1/projects/{pid}/branches/{bid}/commits` | GET/POST | Auth | Commit history/create |
| `/api/v1/projects/{pid}/branches/{bid}/baselines` | GET/POST | Auth | Baseline list/create |

### 4.5 GraphQL Compatibility Layer

Required by the Sirius Web frontend bootstrap. Without these fields, React renders blank.

| DataFetcher | GraphQL Field | Return Value |
|-------------|--------------|--------------|
| `ViewerLanguageDataFetcher` | `Viewer.language` | `"en"` |
| `ViewerNamespacesDataFetcher` | `Viewer.namespaces` | `[]` (empty list) |
| `ViewerCapabilitiesDataFetcher` | `Viewer.capabilities` | Permissive defaults (all true) |
| `ProjectCapabilitiesDataFetcher` | `Project.capabilities` | Permissive defaults (all true) |

**Schema file:** `backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls`

---

## 5. DATABASE SCHEMA (COMPLETE)

### 5.1 Upstream Sirius Web Tables (Liquibase, always present)

| Table | Purpose | Key Columns |
|-------|---------|------------|
| `projects` | Project metadata | `id` (UUID PK), `name`, `description`, `schema_version`, `view_config` (JSONB) |
| `elements` | Semantic model elements (hierarchical) | `id` (UUID PK), `project_id`, `type`, `name`, `parent_id` (self-ref), `attributes` (JSONB) |
| `connections` | Relationships between elements | `id`, `project_id`, `scope_id`, `source_id`, `target_id` (all FK→elements) |
| `diagrams` | Diagram definitions | `id`, `project_id`, `name`, `type` |
| `symbols` | Visual nodes on diagrams | `id`, `diagram_id`, `element_id`, `x`, `y`, `width`, `height` |
| `links` | Visual edges between symbols | `id`, `diagram_id`, `from_symbol_id`, `to_symbol_id`, `type` |

### 5.2 SysON Fork Tables (Flyway V2-V5, defined but NOT applied to live DB)

#### Auth Tables (V2)
| Table | Purpose | Key Columns |
|-------|---------|------------|
| `syson_users` | User accounts | `id` (UUID PK), `email` (UNIQUE), `name`, `password_hash`, `is_active`, `failed_login_attempts`, `locked_until` |
| `syson_tenants` | Multi-tenant orgs | `id` (UUID PK), `name`, `mode` (default 'onprem') |
| `syson_tenant_memberships` | User↔Tenant roles | Composite PK `(user_id, tenant_id)`, `role` CHECK (superuser/admin/editor/viewer) |
| `syson_sessions` | JWT session tracking (schema exists, unused) | `token_jti`, `user_id`, `tenant_id`, `expires_at` |
| `syson_audit_events` | Audit log (schema exists, unused) | `tenant_id`, `actor_id`, `action`, `target_type`, `target_id`, `outcome`, `metadata` (JSONB) |

#### Element Persistence Tables (V3)
| Table | Purpose | Key Columns |
|-------|---------|------------|
| `syson_elements` | Denormalized SysML elements (branch-aware) | `id`, `project_id`, `branch_id`, `sysml_type` (discriminator), `name`, `owner_id` (self-ref), `body` (TEXT), `is_abstract`, `is_variation`, `attributes` (JSONB), `is_deleted` |
| `syson_relationships` | Element cross-references | `id`, `project_id`, `branch_id`, `rel_type`, `source_id`, `target_id` (FK→elements), `source_role`, `target_role`, `is_deleted` |
| `syson_diagrams` | Diagram headers (branch-aware) | `id`, `project_id`, `branch_id`, `view_id`, `diagram_kind`, `is_deleted` |
| `syson_diagram_nodes` | Node positions/styles | `diagram_id`, `element_id`, `sysml_node_type`, `x/y/w/h`, `style` (JSONB), `is_deleted` |
| `syson_diagram_edges` | Edge routing/styles | `diagram_id`, `relationship_id`, `source_node_id`, `target_node_id`, `edge_type`, `routing_points` (JSONB), `style` (JSONB), `is_deleted` |

#### Version Control Tables (V4)
| Table | Purpose | Key Columns |
|-------|---------|------------|
| `syson_branches` | Git-like branches | `branch_id`, `project_id`, `tenant_id`, `name`, `branch_type` (main/feature/release/hotfix), `head_commit_id`, `is_protected` |
| `syson_commits` | Immutable commits | `commit_id`, `project_id`, `branch_id`, `commit_number` (sequential), `message`, `author_user_id`, `change_count`, `commit_hash`, `parent_commit_ids` (JSONB) |
| `syson_changes` | Per-commit change records | `commit_id`, `change_seq`, `object_type`, `object_id`, `operation` (create/update/delete), `before_object`/`after_object` (JSONB), `patch` (JSONB) |
| `syson_baselines` | Named snapshots | `baseline_id`, `project_id`, `commit_id` (FK), `baseline_code`, `name`, `status` (draft/approved), `approved_by` |

#### Project Membership Table (V5)
| Table | Purpose | Key Columns |
|-------|---------|------------|
| `syson_project_members` | User↔Project roles | Composite PK `(project_id TEXT, user_id UUID)`, `role` CHECK (admin/user/viewer) |

**Critical schema fact:** `project_id` is **TEXT** (not UUID) because it references the upstream Sirius Web `project.id` which uses string-formatted UUIDs. This affects any join query design.

### 5.3 Dual Persistence Reality

SysON has **two element storage layers** that coexist:

| Aspect | Layer A (upstream Sirius Web) | Layer B (SysON fork) |
|--------|------------------------------|----------------------|
| Migration | Liquibase | Flyway V3 |
| Branch aware | No | Yes |
| Soft delete | No | Yes (`is_deleted`) |
| Version controlled | No | Yes (via `syson_changes`) |
| Sync mechanism | — | `SaveEventListener` syncs EMF→SQL on save |
| Used by | Sirius Web GraphQL/diagram engine | SysON REST API + canonical JSON export |

**Both layers are active simultaneously.** The upstream tables drive the React frontend via GraphQL. The SysON tables drive the REST API and version control.

### 5.4 Important FK Design Decision

None of the JPA entities use `@ManyToOne` or `@OneToMany` annotations. All foreign keys are stored as plain UUID columns mapped 1:1 to database tables. This is a deliberate design choice to avoid coupling to the upstream Sirius Web entity model. Any account management extension should follow this pattern.

---

## 6. FRONTEND ARCHITECTURE

### 6.1 Component Tree

```
<SiriusWebApplication>                          (from Sirius Web framework)
├── <NavigationBar>
│   ├── <SysONNavigationBarIcon />              Custom logo
│   ├── <SysONNavigationBarMenuIcon />          Custom hamburger menu
│   └── #syson-user-bar                         Injected by auth.js (vanilla JS)
├── <Router>
│   ├── "/" → ProjectList                       Landing: list accessible projects
│   ├── "/projects/:id/edit" → EditProjectView  ★ Main workspace
│   │   ├── <ExplorerTreeView />                Left panel: tree navigation
│   │   │   └── Context menus (New Object, Insert SysML text, Download)
│   │   ├── <WorkbenchArea />                   Center: diagrams, forms, tables
│   │   │   ├── <DiagramRepresentation>         (xyflow/react canvas)
│   │   │   │   ├── <DiagramPanel>
│   │   │   │   │   └── <SysONDiagramPanelMenu>  Icons, inherited members toggles
│   │   │   │   └── Custom SysML nodes:
│   │   │   │       <SysMLPackageNode /> <SysMLNoteNode />
│   │   │   │       <SysMLImportedPackageNode /> <SysMLViewFrameNode />
│   │   │   ├── <FormRepresentation />
│   │   │   ├── <TableRepresentation />
│   │   │   └── <GanttRepresentation />
│   │   └── <DetailsPanel />                    Right panel: properties
│   ├── "/projects/upload" → UploadProject
│   ├── "/projects/new" → NewProject
│   └── "/settings" → Settings
├── <Omnibox>                                   Command palette
├── <InsertTextualSysMLv2Modal />               Global modal for SysML text input
├── <SysONFooter />                             App footer
└── <ToastStack />
```

### 6.2 Authentication Overlay (auth.js)

- **Zero-dependency vanilla JS** loaded in `<head>` BEFORE React
- Monkey-patches `window.fetch` and `XMLHttpRequest` to add `Authorization: Bearer` header
- Login overlay hides `#root` with `display: none !important` CSS rule (Vite/React can't override)
- Full page reload after login (so Apollo Client initializes with token)
- Dashboard overlay: profile, password change, project list

### 6.3 State Management

| Layer | Technology | Scope |
|-------|-----------|-------|
| Apollo Client cache | GraphQL response caching | All domain data |
| XState machines | State machines (Sirius Web internal) | UI workflows |
| React Context | ServerContext, DiagramContext | Cross-component config |
| React useState | Local component state | Per-component |
| Extension Registry | Plugin system | Component registration |
| localStorage | `syson_auth` key | JWT, email, roles |

### 6.4 Custom Diagram Nodes

Each custom node follows a 4-file pattern:
- `*.tsx` — React component
- `*Converter.ts` — GraphQL → xyflow format
- `*LayoutHandler.ts` — Auto-layout logic
- `*.types.ts` — TypeScript types

Registered via `NodeTypeRegistry` + `NodeTypeContribution` entries.

### 6.5 GraphQL Operations (Custom SysON-Specific)

**Queries:**
- `viewer.showDiagramsIconsValue` — User's icon display preference
- `viewer.showDiagramsInheritedMembersValue` — Inherited members visibility
- `viewer.showDiagramsInheritedMembersFromStandardLibrariesValue` — Std lib inherited members

**Mutations:**
- `insertTextualSysMLv2` — Create model from SysMLv2 textual syntax
- `showDiagramsIcons` / `showDiagramsInheritedMembers` / `showDiagramsInheritedMembersFromStandardLibraries` — Toggle preferences

All other GraphQL operations are provided by the Sirius Web framework internally (getProjects, getTree, diagramEvent, invokeEditingContextAction, etc.).

---

## 7. DIAGRAM SYSTEM

### 7.1 Diagram Views (each is a separate Spring backend module)

| View | Module | Purpose |
|------|--------|---------|
| General View | `syson-diagram-general-view` | Main SysML diagram — all element types |
| Interconnection View | `syson-diagram-interconnection-view` | Part/port interconnections, bindings, flow connections |
| Action Flow View | `syson-diagram-actionflow-view` | Activity/action modeling |
| State Transition View | `syson-diagram-statetransition-view` | State machine modeling |

### 7.2 How Views Work

Each view module extends abstract classes from `syson-diagram-common-view`:
- **Abstract edge providers** (15 types): Allocate, FeatureTyping, Subclassification, Transition, etc.
- **Abstract node providers** (20+ types): Definition, Usage, Package, Compartment, Port, Action nodes
- **Abstract tool providers** (30+ types): Creation tools for each element/edge type
- **Abstract services**: Create, edge tool, node tool, label, visibility management

Concrete views override these for diagram-specific behavior (e.g., General View shows all element types; Action Flow only shows action-related elements).

### 7.3 GraphQL Schema Extensions

| Schema File | Module | Content |
|-------------|--------|---------|
| `syson-diagrams.graphqls` | diagram-common-view | Viewer preferences + toggle mutations |
| `sysmlcustomnodes.graphqls` | application-configuration | Custom node style types |
| `syson-import.graphqls` | sysml-import | `insertTextualSysMLv2` mutation |
| `syson-auth-compat.graphqls` | syson-application | Auth compatibility fields |

---

## 8. VERSION CONTROL SYSTEM

Git-like append-only model with branch isolation.

### 8.1 Model

- **Branches:** Per-project, per-tenant. Types: main/feature/release/hotfix. Track head_commit_id.
- **Commits:** Immutable, sequentially numbered within branch. Store author, message, hash, parent commit IDs (JSONB for merge support).
- **Changes:** Per-commit, per-object. Track create/update/delete operations with before/after snapshots + patches.
- **Baselines:** Named snapshots pinned to commits. Draft→Approved workflow. Per-project, per-tenant.

### 8.2 Key Classes

| Class | Role |
|-------|------|
| `VersionControlController` | REST API at `/api/v1/projects/{pid}/branches/...` |
| `VersionControlService` | Business logic (branch/commit/baseline/change operations) |
| `SaveEventListener` | Syncs EMF model state to element tables on save (the bridge between upstream EMF and SysON SQL) |
| `ElementPersistenceService` | Reads from element tables |
| `CanonicalJsonService` | Assembles complete JSON export for a project branch |

### 8.3 Relationship to Access Control

The version control system is the **natural place** to add access control:
- **Branch protection** (`is_protected` flag exists) — can prevent direct commits to protected branches
- **Commit authorship** (`author_user_id` FK to `syson_users`) — already tracked
- **Baseline approval** (`approved_by` FK to `syson_users`) — already tracked
- **Missing:** No per-branch access control (who can read/write to which branch)
- **Missing:** No element-level permissions (who can modify which parts of the model)
- **Missing:** No commit signing or review requirements

---

## 9. SYSM VL2 METAMODEL (DOMAIN MODEL)

### 9.1 Metamodel Structure

The SysMLv2 metamodel is EMF-generated with 150+ interfaces and 150+ implementation classes.

**Core type hierarchy:**
```
Element
├── Namespace
│   └── Package
├── Definition
│   ├── PartDefinition
│   ├── ActionDefinition
│   ├── StateDefinition
│   ├── RequirementDefinition
│   ├── CaseDefinition
│   ├── ConnectionDefinition
│   ├── PortDefinition
│   ├── AttributeDefinition
│   ├── ItemDefinition
│   ├── ConstraintDefinition
│   ├── AllocationDefinition
│   └── InterfaceDefinition
├── Usage
│   ├── PartUsage
│   ├── ActionUsage
│   ├── StateUsage
│   ├── RequirementUsage
│   ├── CaseUsage
│   ├── ConnectionUsage
│   ├── PortUsage
│   ├── AttributeUsage
│   ├── ItemUsage
│   ├── ConstraintUsage
│   ├── AllocationUsage
│   ├── InterfaceUsage
│   ├── FlowConnectionUsage
│   └── BindingConnectorAsUsage
├── Relationship
│   ├── Subclassification
│   ├── Subsetting
│   ├── Redefinition
│   ├── FeatureTyping
│   ├── FeatureChaining
│   ├── Dependency
│   ├── Succession
│   ├── TransitionUsage
│   ├── Conjugation
│   └── Specialization
├── Expression (various)
├── Annotation / Comment / Documentation
├── MetadataDefinition / MetadataUsage
└── Control/Action Nodes (Accept, Perform, Decision, Fork, Join, Merge, Start, Done)
```

### 9.2 How This Relates to Access Control

Everything is an `Element`. The containment hierarchy (via `owner_id` in `syson_elements`) creates a tree of elements within a project. Access control could be applied at any level:
- Project-level (already exists via `syson_project_members`)
- Package-level (natural boundary — "this subsystem")
- Element-level (fine-grained — "this specific part")
- View/diagram-level (visual access)

---

## 10. DEPLOYMENT ARCHITECTURE (LIVE)

```
Internet
  │
  ▼
Nginx (:443, syson.damuza-consulting.com)
├── /auth.js → direct-served from /var/www/syson/auth.js
├── /api/* → proxy_pass http://127.0.0.1:8080
└── /* → proxy_pass http://127.0.0.1:8080 (with WebSocket upgrade)
       │
       ▼
Docker: syson (:8080, image syson-rbac:latest)
  Spring Boot serving:
  - Compiled React frontend (static resources)
  - GraphQL API (/api/graphql)
  - REST API (/api/v1/*, /api/auth/*)
  - WebSocket subscriptions (/subscriptions)
       │
       ▼
Docker: eim-postgres (:55432, postgres:16)
  Database: eim
  Contains: upstream Sirius Web tables (via Liquibase)
  Missing: SysON Flyway tables (not yet applied)
```

**Key deployment facts:**
- PostgreSQL is shared with the EIM application (database `eim`)
- SysON Flyway migrations (V2-V5) are defined but NOT applied to the running database
- The `syson_project_members` table doesn't exist in the live DB yet
- Auth currently works via the in-code AdminSeeder and in-memory user data (the JPA entities compile but the tables don't exist)
- Nginx serves `auth.js` directly for fast auth-only iterations

---

## 11. ARCHITECTURE FOR ACCOUNT MANAGEMENT EXTENSION

### 11.1 What Already Exists

| Feature | Status |
|---------|--------|
| User accounts (email, password hash) | ✅ `syson_users` table defined |
| JWT authentication | ✅ Working end-to-end |
| Password change | ✅ `PUT /api/v1/user/me/password` |
| Project-level RBAC (admin/user/viewer) | ✅ `syson_project_members` defined |
| Tenant-level roles (superuser/admin/editor/viewer) | ✅ `syson_tenant_memberships` defined |
| Admin user CRUD | ✅ `UserController` admin endpoints |
| Admin project membership management | ✅ `UserController` admin endpoints |
| Account lockout (failed attempts) | ✅ Schema has `failed_login_attempts`, `locked_until` |
| Audit events table | ✅ Schema exists, no code writes to it |
| Session tracking table | ✅ Schema exists, not actively used |
| Branch protection flag | ✅ `is_protected` column exists |
| Branch per-tenant isolation | ✅ `tenant_id` on branches |

### 11.2 What's Missing (Prioritized Gaps)

#### Critical — Must Have for Account Management

1. **No self-registration** — Needs `POST /api/auth/register` endpoint
2. **No email verification** — `SysonUser` has no `email_verified` field; needs token table
3. **No password reset** — Needs `password_reset_tokens` table + email integration
4. **No invitation system** — Needs invite tokens + acceptance flow for team onboarding
5. **Account management UI** — Dashboard overlay exists in auth.js but is minimal (only profile + password change + project list)

#### Important — For Production Access Control

6. **No per-element access control** — Currently only project-level gates exist
7. **No per-branch access control** — Branches have `is_protected` flag but no user-level read/write permissions
8. **No per-package/package-level permissions** — Natural organizational boundary not gated
9. **Element-level permissions model** — No concept of "this user can read/edit/approve this element"
10. **No tenant isolation enforcement** — `tenant_id` exists on branches but not enforced on queries
11. **`syson_audit_events` unused** — Table exists but no code writes to it
12. **No UI for account management** — All admin operations are REST-only, no admin panel in the frontend

#### Nice-to-Have

13. **No 2FA/TOTP support**
14. **No OAuth/OIDC integration**
15. **No session management** (logout is client-side only, no token revocation list)
16. **`AdminSeeder` uses hardcoded UUIDs**
17. **No email service integration** (for verification, reset, invitations)
18. **JWT secret hardcoded** in application.properties (overridable via env)

### 11.3 Design Constraints for Account Management Extension

1. **Dual persistence reality:** New tables must work with both Liquibase (upstream) and Flyway (fork). Follow the Flyway pattern for SysON-owned tables.
2. **No JPA relationships to upstream entities:** Use plain FK columns (no `@ManyToOne`/`@OneToMany`) to avoid coupling.
3. **TenantContext pattern:** All new services should use `TenantContext.getUserIdAsUuid()` for current user resolution.
4. **auth.js is deliberately NOT React:** Account management UI can be React (in the main app) OR vanilla JS (in auth.js). Dashboard overlay is already vanilla JS in auth.js. New React components can be added to the frontend packages.
5. **Extension Registry:** New UI features should plug into Sirius Web's extension points.
6. **REST vs GraphQL:** Auth/user management uses REST. Core modeling uses GraphQL. Account management features (profile, settings, team management) would naturally be REST.
7. **Frontend thin layer:** The frontend is intentionally thin (~55 custom files). Complex features may benefit from backend-heavy design.
8. **Project ID is TEXT not UUID:** `syson_project_members.project_id` is TEXT because upstream `project.id` is UUID-as-string. Design accordingly.

### 11.4 Recommended Approach for Database Extension

**New tables needed:**

| Table | Purpose |
|-------|---------|
| `syson_password_reset_tokens` | Password reset tokens with expiry |
| `syson_email_verifications` | Email verification tokens |
| `syson_invitations` | Project/tenant invitation tokens + acceptance workflow |
| `syson_element_permissions` | Per-element access control (element_id, user_id/role_id, permission_type) |
| `syson_roles` or `syson_permission_sets` | Named permission sets beyond simple admin/user/viewer |

**Tables to activate:**
- `syson_sessions` — already defined, start writing to it
- `syson_audit_events` — already defined, add audit logging to all auth/membership operations

**Schema modifications:**
- `syson_users` — add `email_verified BOOLEAN DEFAULT FALSE`, `verification_token`, `avatar_url`
- `syson_branches` — add user-level read/write permissions (or a separate join table)
- `syson_elements` — consider an `access_level` column or separate permissions table

### 11.5 Integration Points for New Account Management

| Layer | Integration Point | How |
|-------|------------------|-----|
| Backend auth | `SecurityConfig` | Add new public paths (register, verify, reset-password) |
| Backend auth | `AuthController` | Add register, verify-email, request-reset, reset-password endpoints |
| Backend auth | `UserController` | Add profile update, avatar, notification settings |
| Backend auth | `AdminSeeder` | Keep for bootstrap, mark as "initial setup only" |
| Backend auth | `TenantFilter` | Ensure new endpoints work with TenantContext |
| Backend persistence | `SaveEventListener` | Consider adding audit event writes on element changes |
| Frontend | `auth.js` dashboard overlay | Extend with account management (or replace with React) |
| Frontend | Sirius Web extension points | New account-related pages/panels |
| Frontend | Navigation bar | User menu with account settings |
| Database | Flyway V6 | New migration for account management tables |
| Database | Existing `syson_*` tables | Index for efficient permission checks |

---

## 12. KEY ARCHITECTURAL PATTERNS

### 12.1 Extension Registry Pattern
All customizations plug into Sirius Web's extension points. Custom components are registered via the `ExtensionRegistry` at app startup. The `SysONExtensionRegistryMergeStrategy` controls how SysON extensions merge with default Sirius Web extensions.

### 12.2 Middleware/Interceptor Pattern
Auth is injected via filter chain: `JwtAuthenticationFilter` → `TenantFilter` → Controller. This pattern should be extended for permission checks (add a `PermissionFilter` that checks element/branch access after TenantFilter sets the context).

### 12.3 Dual Persistence Pattern
Two storage systems coexist: upstream EMF/Liquibase (for Sirius Web compatibility) and SysON Flyway/JPA (for SysON-specific features). The `SaveEventListener` bridges them by syncing EMF to SQL on save. Never modify the upstream Sirius Web tables directly — always layer SysON tables alongside.

### 12.4 Stateless Auth Pattern
JWT stored in localStorage, sent via monkey-patched `fetch()`/`XHR`. No server-side sessions (despite `syson_sessions` table existing). Token refresh every 4 hours. Full page reload after login to ensure Apollo Client initializes with token.

### 12.5 Thin Frontend Pattern
The entire SysON frontend customization is ~55 files. All heavy lifting (routing, layout, form handling, table rendering, diagram editing, validation, selection, collaboration) is done by the Sirius Web framework. New features should follow this pattern: thin, plug-in, don't reimplement framework features.

---

## 13. FILE REFERENCE MAP

### Key Backend Files (for modification)

| File | What It Does |
|------|-------------|
| `application/syson-application/src/main/java/org/eclipse/syson/SysONApplication.java` | Spring Boot entry point |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/SecurityConfig.java` | Spring Security configuration |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/JwtAuthenticationFilter.java` | JWT filter |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/TenantFilter.java` | Tenant context filter |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/TenantContext.java` | Thread-local tenant context |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/JwtService.java` | JWT generation/validation |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/SysonUserDetailsService.java` | User loading for Spring Security |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/AuthController.java` | Login/refresh/logout endpoints |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/UserController.java` | User/profile/admin endpoints |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/ProjectAccessService.java` | Project membership operations |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/AdminSeeder.java` | Bootstrap admin user |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/entity/` | JPA entities (SysonUser, SysonTenant, etc.) |
| `application/syson-application/src/main/java/org/eclipse/syson/auth/repository/` | JPA repositories (UserRepository, etc.) |
| `application/syson-application/src/main/java/org/eclipse/syson/persistence/SaveEventListener.java` | EMF→SQL sync bridge |
| `application/syson-application/src/main/java/org/eclipse/syson/persistence/ElementPersistenceService.java` | Element read service |
| `application/syson-application/src/main/java/org/eclipse/syson/persistence/CanonicalJsonService.java` | JSON export service |
| `application/syson-application/src/main/java/org/eclipse/syson/persistence/ElementRestController.java` | Element REST endpoints |
| `application/syson-application/src/main/java/org/eclipse/syson/persistence/entity/` | Element JPA entities |
| `application/syson-application/src/main/java/org/eclipse/syson/vc/` | Version control (controller, service, entities, repos) |
| `application/syson-application/src/main/resources/application.properties` | App configuration |
| `application/syson-application/src/main/resources/db/migration/` | Flyway migrations (V2-V5) |
| `application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls` | GraphQL compat schema |

### Key Frontend Files

| File | What It Does |
|------|-------------|
| `frontend/syson/public/auth.js` | ★ Authentication overlay, JWT interceptor, dashboard |
| `frontend/syson/src/index.tsx` | React entry point |
| `frontend/syson/src/core/URL.ts` | API URL resolution |
| `frontend/syson/src/extensions/` | Footer, nav icons, context menus |
| `frontend/syson/src/theme/sysonTheme.ts` | MUI theme |
| `frontend/syson-components/src/nodes/` | Custom diagram nodes (4 types × 4 files) |
| `frontend/syson-components/src/extensions/` | Diagram toggles, textual insert modal, omnibox |

### Deployment Files

| File | What It Does |
|------|-------------|
| `docker-compose.yml` | Docker stack definition |
| `/etc/nginx/sites-enabled/syson` | Nginx reverse proxy config |
| `/var/www/syson/auth.js` | Live auth.js (deployed copy) |
| `scripts/check-syson-login-regression.sh` | Login regression test |
| `AGENTS.md` | Agent guardrails + login overlay rules |

---

## 14. SOFT DEPRECATION: REQUIREMENTS MANAGER PILOT

The SysON fork was initially forked for an EIM/Requirements Manager integration. The Requirements Manager Pilot v2 runs separately at `requirements.damuza-consulting.com` (`:4020`/`:4021`) with its own PostgreSQL 16 instance. The two systems share the same PostgreSQL host (`eim-postgres` container) but use different databases.

If you encounter references to EIM integration in the SysON codebase, they are legacy/planned and not yet wired into the live system. The only active integration point is the shared PostgreSQL container.

---

*Generated: June 06, 2026 — from live codebase analysis of /root/syson-fork branch `rbac`*
