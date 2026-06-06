# SysON RBAC + Element-Level Persistence + Version Control — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.
> **Inspired by:** BowTie Pilot's sophisticated RBAC, head-table architecture, and append-only version control.

**Goal:** Add role-based access control, per-element database persistence (replacing opaque JSON blobs), and comprehensive versioning control to the SysON SysML v2 modeling platform.

**Architecture:** Add three new subsystems alongside the existing Sirius Web stack:
1. **Auth/RBAC** — PostgreSQL-backed users, tenants, roles, JWT sessions
2. **Element persistence** — per-element SQL tables (elements, relationships, diagram_nodes, etc.) replacing EMF JSON blobs
3. **Version control** — append-only commit/changelog with SHA-256 diffing, branches, baselines

All three run alongside Sirius Web — this is NOT a full replacement. The Sirius GraphQL API continues to work; new REST endpoints add RBAC-gated element access and version control.

**Tech Stack:** Java 21, Spring Boot 4, PostgreSQL 16, JWT (jjwt), existing Sirius Web + GraphQL

---

## Phase 0: Database Schema Foundation

### Task 0.1: Create auth schema migration

**Files:**
- Create: `backend/application/syson-application/src/main/resources/db/migration/V2__auth_schema.sql`

```sql
-- RBAC: users, tenants, memberships, sessions
CREATE TABLE IF NOT EXISTS syson_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255),
    password_hash VARCHAR(512) NOT NULL,  -- bcrypt: $2a$...
    is_active BOOLEAN DEFAULT TRUE,
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS syson_tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    mode VARCHAR(50) DEFAULT 'onprem',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS syson_tenant_memberships (
    user_id UUID REFERENCES syson_users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES syson_tenants(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL CHECK (role IN ('superuser','admin','editor','viewer')),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (user_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS syson_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES syson_users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES syson_tenants(id) ON DELETE CASCADE,
    token_jti VARCHAR(255) UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS syson_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES syson_tenants(id),
    actor_id UUID REFERENCES syson_users(id),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100),
    target_id VARCHAR(255),
    outcome VARCHAR(50) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Seed default tenant + superuser (password: SysONS3cr3t! changeme)
INSERT INTO syson_tenants (id, name) 
VALUES ('00000000-0000-0000-0000-000000000001', 'Default Organization')
ON CONFLICT DO NOTHING;

INSERT INTO syson_users (id, email, name, password_hash)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin@localhost', 'SuperUser',
        '$2a$10$placeholder_use_security_config_to_generate')
ON CONFLICT DO NOTHING;

INSERT INTO syson_tenant_memberships (user_id, tenant_id, role)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'superuser')
ON CONFLICT DO NOTHING;
```

### Task 0.2: Create element-level persistence schema

**Files:**
- Create: `backend/application/syson-application/src/main/resources/db/migration/V3__element_persistence.sql`

```sql
-- Per-element persistence: replaces EMF JSON blob storage with queryable element records
-- NOTE: This runs ALONGSIDE Sirius Web — existing document.content column is kept.
-- The APIs added in Phase 2 write to these tables directly.

CREATE TABLE IF NOT EXISTS syson_elements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,  -- branch UUID (resolved from name like BowTie)
    sysml_type VARCHAR(100) NOT NULL,  -- 'Package','PartDefinition','PartUsage','PortDefinition',etc.
    name VARCHAR(500) NOT NULL,
    owner_id UUID,  -- parent element
    body TEXT,
    is_abstract BOOLEAN DEFAULT FALSE,
    is_variation BOOLEAN DEFAULT FALSE,
    attributes JSONB DEFAULT '[]',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_elements_project ON syson_elements(project_id, branch_id);
CREATE INDEX idx_syson_elements_type ON syson_elements(project_id, branch_id, sysml_type);
CREATE INDEX idx_syson_elements_owner ON syson_elements(owner_id);
CREATE INDEX idx_syson_elements_deleted ON syson_elements(project_id, branch_id, is_deleted);

CREATE TABLE IF NOT EXISTS syson_relationships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    rel_type VARCHAR(100) NOT NULL,  -- 'Composition','Aggregation','Generalization','Association',etc.
    name VARCHAR(500),
    source_id UUID NOT NULL REFERENCES syson_elements(id),
    target_id UUID NOT NULL REFERENCES syson_elements(id),
    source_role VARCHAR(255),
    target_role VARCHAR(255),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_rels_project ON syson_relationships(project_id, branch_id);
CREATE INDEX idx_syson_rels_source ON syson_relationships(source_id);
CREATE INDEX idx_syson_rels_target ON syson_relationships(target_id);

CREATE TABLE IF NOT EXISTS syson_diagrams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    view_id UUID,  -- Sirius Web view ID
    name VARCHAR(500),
    diagram_kind VARCHAR(100),  -- 'GeneralView','InterconnectionView','ActionFlowView','StateTransitionView'
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS syson_diagram_nodes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id UUID NOT NULL REFERENCES syson_diagrams(id) ON DELETE CASCADE,
    element_id UUID REFERENCES syson_elements(id),
    sysml_node_type VARCHAR(100),
    x DOUBLE PRECISION DEFAULT 0,
    y DOUBLE PRECISION DEFAULT 0,
    w DOUBLE PRECISION DEFAULT 100,
    h DOUBLE PRECISION DEFAULT 60,
    style JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_dn_diagram ON syson_diagram_nodes(diagram_id);

CREATE TABLE IF NOT EXISTS syson_diagram_edges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id UUID NOT NULL REFERENCES syson_diagrams(id) ON DELETE CASCADE,
    relationship_id UUID REFERENCES syson_relationships(id),
    source_node_id UUID REFERENCES syson_diagram_nodes(id),
    target_node_id UUID REFERENCES syson_diagram_nodes(id),
    edge_type VARCHAR(100),
    routing_points JSONB DEFAULT '[]',
    style JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    is_deleted BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_syson_de_diagram ON syson_diagram_edges(diagram_id);
```

### Task 0.3: Create version control schema

**Files:**
- Create: `backend/application/syson-application/src/main/resources/db/migration/V4__version_control.sql`

```sql
-- Append-only version control: model_commits + model_changes pattern from BowTie

CREATE TABLE IF NOT EXISTS syson_branches (
    branch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    branch_type VARCHAR(50) DEFAULT 'main' CHECK (branch_type IN ('main','feature','release','hotfix')),
    head_commit_id UUID,
    base_commit_id UUID,
    parent_branch_id UUID,
    is_protected BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID REFERENCES syson_users(id)
);
CREATE INDEX idx_syson_branches_project ON syson_branches(project_id, tenant_id);
CREATE UNIQUE INDEX idx_syson_branches_name ON syson_branches(project_id, name) WHERE NOT is_deleted;

CREATE TABLE IF NOT EXISTS syson_commits (
    commit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    branch_id UUID NOT NULL REFERENCES syson_branches(branch_id),
    commit_number BIGINT NOT NULL,  -- monotonic per branch
    message TEXT DEFAULT '',
    author_user_id UUID REFERENCES syson_users(id),
    change_count INT DEFAULT 0,
    commit_hash VARCHAR(64),  -- SHA-256 of previous + this commit's data
    parent_commit_ids JSONB DEFAULT '[]',
    committed_at TIMESTAMPTZ DEFAULT now(),
    source VARCHAR(50) DEFAULT 'direct',
    status VARCHAR(50) DEFAULT 'committed'
);
CREATE INDEX idx_syson_commits_branch ON syson_commits(project_id, branch_id, committed_at DESC);
CREATE UNIQUE INDEX idx_syson_commits_number ON syson_commits(project_id, branch_id, commit_number);

CREATE TABLE IF NOT EXISTS syson_changes (
    change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    change_seq INT NOT NULL,
    object_type VARCHAR(100) NOT NULL,  -- 'element','relationship','diagram_node','diagram_edge'
    object_id UUID NOT NULL,
    operation VARCHAR(10) NOT NULL CHECK (operation IN ('create','update','delete')),
    before_hash VARCHAR(64),
    after_hash VARCHAR(64),
    patch JSONB,  -- JSON Patch (RFC 6902) for updates
    before_object JSONB,
    after_object JSONB,
    created_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID
);
CREATE INDEX idx_syson_changes_object ON syson_changes(project_id, object_type, object_id, created_at DESC);

CREATE TABLE IF NOT EXISTS syson_baselines (
    baseline_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    baseline_code VARCHAR(50),
    name VARCHAR(500),
    commit_id UUID NOT NULL REFERENCES syson_commits(commit_id),
    status VARCHAR(50) DEFAULT 'draft' CHECK (status IN ('draft','approved')),
    approved_by UUID REFERENCES syson_users(id),
    approved_at TIMESTAMPTZ,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    created_by UUID REFERENCES syson_users(id)
);
```

---

## Phase 1: Auth/RBAC (Java Backend)

### Task 1.1: Add Spring Security + JWT dependencies

**Files:**
- Modify: `backend/application/syson-application/pom.xml` — add dependencies:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### Task 1.2: Create Auth entities and repositories

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/entity/SysonUser.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/entity/SysonTenant.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/entity/TenantMembership.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/repository/UserRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/repository/TenantRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/repository/MembershipRepository.java`

### Task 1.3: Create JWT service

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/JwtService.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/AuthController.java` — `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`

### Task 1.4: Create Spring Security filter chain

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/SecurityConfig.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/JwtAuthenticationFilter.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/SysonUserDetailsService.java`

### Task 1.5: Seed default admin on startup

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/AdminSeeder.java` — `@PostConstruct` that seeds `admin@localhost` with bcrypt-hashed password from env var `SYSON_BOOTSTRAP_PASSWORD`

---

## Phase 2: Element Persistence API

### Task 2.1: Create element/relationship JPA repositories

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/repository/ElementRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/repository/RelationshipRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/repository/DiagramRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/repository/DiagramNodeRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/repository/DiagramEdgeRepository.java`

### Task 2.2: Create element persistence service

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/ElementPersistenceService.java`
  - `getElements(projectId, branchId)` → list
  - `getElementById(projectId, branchId, elementId)` → single
  - `getChildren(parentId)` → hierarchy
  - `getRelationships(projectId, branchId)` → list
  - `getDiagramNodes(diagramId)` → symbol positions
  - Follows BowTie's head-table pattern: queries always include `is_deleted=false`

### Task 2.3: Create REST controller for elements

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/ElementRestController.java`
```java
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@PreAuthorize("hasRole('editor') or hasRole('admin') or hasRole('superuser')")
public class ElementRestController {
    @GetMapping("/branches/{branchId}/elements")
    public List<ElementDto> getElements(@PathVariable UUID projectId, @PathVariable UUID branchId);
    
    @GetMapping("/branches/{branchId}/elements/{elementId}")
    public ElementDto getElement(@PathVariable UUID projectId, @PathVariable UUID branchId, @PathVariable UUID elementId);
    
    @GetMapping("/branches/{branchId}/elements/{elementId}/children")
    public List<ElementDto> getChildren(@PathVariable UUID projectId, @PathVariable UUID branchId, @PathVariable UUID elementId);
    
    @GetMapping("/branches/{branchId}/relationships")
    public List<RelationshipDto> getRelationships(@PathVariable UUID projectId, @PathVariable UUID branchId);
    
    @GetMapping("/branches/{branchId}/diagrams/{diagramId}/nodes")
    public List<DiagramNodeDto> getDiagramNodes(@PathVariable UUID projectId, @PathVariable UUID branchId, @PathVariable UUID diagramId);
}
```

---

## Phase 3: Version Control

### Task 3.1: Create commit/changelog repositories

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/repository/CommitRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/repository/ChangeRepository.java`
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/repository/BranchRepository.java`

### Task 3.2: Create version control service with SHA-256 diffing

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/VersionControlService.java`
  - `createCommit(projectId, branchId, userId, message, changes)` — BEGIN TRANSACTION, insert commit + changes, update head tables
  - `getCommitHistory(projectId, branchId)` — timeline
  - `getCommitDiff(projectId, commitId)` — what changed in a commit
  - `createBranch(projectId, name, type, parentBranchId, userId)`
  - `createBaseline(projectId, commitId, code, name, userId)`

### Task 3.3: Create version control REST controller

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/vc/VersionControlController.java`
```java
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class VersionControlController {
    @GetMapping("/branches")
    List<BranchDto> getBranches();
    
    @PostMapping("/branches")
    BranchDto createBranch(@RequestBody CreateBranchRequest req);
    
    @GetMapping("/branches/{branchId}/commits")
    List<CommitDto> getCommits(@PathVariable UUID branchId);
    
    @GetMapping("/branches/{branchId}/commits/{commitId}")
    CommitDto getCommit(@PathVariable UUID branchId, @PathVariable UUID commitId);
    
    @GetMapping("/branches/{branchId}/commits/{commitId}/diff")
    List<ChangeDto> getCommitDiff(@PathVariable UUID branchId, @PathVariable UUID commitId);
    
    @PostMapping("/baselines")
    BaselineDto createBaseline(@RequestBody CreateBaselineRequest req);
}
```

---

## Phase 4: Save Integration

### Task 4.1: Intercept Sirius Web saves to populate element tables

**Files:**
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/persistence/SaveEventListener.java`
  - Listens for Sirius Web editing context save events
  - Extracts elements/relationships/diagrams from EMF ResourceSet
  - Upserts into `syson_elements`, `syson_relationships`, etc.
  - Creates a commit in `syson_commits` + `syson_changes`

### Task 4.2: Create BowTie-style JSON export endpoint

**Files:**
- Modify: `ElementRestController.java` — add:
```java
@GetMapping("/branches/{branchId}/export")
public CanonicalJsonDto exportCanonicalJson(@PathVariable UUID projectId, @PathVariable UUID branchId);
```
  - Assembles elements + relationships + diagrams into a canonical JSON structure
  - Caches in a `head_project_state`-equivalent table for fast reads

---

## Phase 5: Tenant Isolation

### Task 5.1: Add tenant context to all queries

**Files:**
- Modify: All repositories to filter by tenant
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/TenantContext.java` — ThreadLocal tenant resolver
- Create: `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/TenantFilter.java` — extracts tenant from JWT claims

---

## Key Files Created Summary

| Phase | Files Created | Purpose |
|-------|--------------|---------|
| Phase 0 | 3 SQL migration files | Auth + element + VC tables |
| Phase 1 | 12 Java files | Auth entities, repos, JWT, security config |
| Phase 2 | 8 Java files | Element repos, persistence service, REST controller |
| Phase 3 | 6 Java files | VC repos, diff engine, VC controller |
| Phase 4 | 2 Java files | Sirius save interceptor, JSON export |
| Phase 5 | 2 Java files | Tenant context, filter |

**Total: ~33 new files, ~2,500 LOC across all phases.**

## Verification Checklist

- [ ] `POST /api/auth/login` returns JWT with roles
- [ ] `GET /api/v1/projects/{id}/branches/{bid}/elements` requires auth, returns correct elements
- [ ] `POST /api/v1/projects/{id}/branches` creates a branch
- [ ] Save in Sirius Web UI triggers element table update + commit creation
- [ ] `GET /api/v1/projects/{id}/branches/{bid}/commits` shows history
- [ ] `GET /api/v1/projects/{id}/branches/{bid}/export` returns canonical JSON matching the model
- [ ] Tenant isolation: user in tenant A can't see tenant B's projects
