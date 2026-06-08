# SysON RBAC + Audit Trail Implementation Plan

> **Date:** 2026-06-08
> **Branch:** rbac
> **Baseline:** `bbf7eeee0` (21/21 editor UI tests passing)
> **Live:** `https://syson.damuza-consulting.com`

---

## 1. Executive Summary

This plan defines a complete Role-Based Access Control system for the SysON platform with:

1. **Platform-level roles** (tenant-wide): SuperUser, Project Admin, User, Viewer
2. **Project-level roles**: Admin, User (assignable by SuperUser and Project Admin)
3. **Context-sensitive menus**: Platform config vs project config based on navigation
4. **Audit trail module**: Immutable log of all RBAC configuration changes, SuperUser read-only

The existing schema (V2–V12) provides the foundation. This plan extends it with V13–V15 migrations, new backend services, and frontend UI.

---

## 2. Role Hierarchy and Permission Matrix

### 2.1 Platform Roles (Tenant-Level)

| Role | Level | Description |
|------|-------|-------------|
| **SuperUser** | 4 | Full platform control. Can manage all users, all projects, all settings. Only role that can access the audit trail. Can assign any platform role. |
| **Project Admin** | 3 | Can manage projects they administer. Can assign project-level roles (Admin/User) to other users. Can create/delete projects. Cannot access platform config or audit trail. |
| **User** | 2 | Can work on projects they're assigned to. Can create models, diagrams, edit elements. Cannot manage users or projects. |
| **Viewer** | 1 | Read-only access to assigned projects. Cannot create, edit, or delete anything. |

### 2.2 Project Roles

| Role | Who Can Assign | Description |
|------|---------------|-------------|
| **Admin** | SuperUser, Project Admin | Full control over the project. Can manage project members, settings, and models. |
| **User** | SuperUser, Project Admin | Can edit models, create diagrams, work within the project. |
| **Viewer** | SuperUser, Project Admin | Read-only access to the project. |

### 2.3 Permission Matrix

| Action | SuperUser | Project Admin | User | Viewer |
|--------|-----------|---------------|------|--------|
| **Platform** | | | | |
| Manage all users (CRUD) | ✓ | — | — | — |
| Assign platform roles | ✓ | — | — | — |
| View audit trail | ✓ | — | — | — |
| Platform settings | ✓ | — | — | — |
| **Projects** | | | | |
| Create project | ✓ | ✓ | — | — |
| Delete any project | ✓ | — | — | — |
| Delete own project | ✓ | ✓ | — | — |
| Manage project members | ✓ | ✓ (admin projects) | — | — |
| Assign project roles | ✓ | ✓ (admin projects) | — | — |
| **Models (within assigned project)** | | | | |
| Create/edit models | ✓ | ✓ | ✓ | — |
| Create/edit diagrams | ✓ | ✓ | ✓ | — |
| View models | ✓ | ✓ | ✓ | ✓ |
| View diagrams | ✓ | ✓ | ✓ | ✓ |

### 2.4 Effective Role Resolution

When a user's effective permissions are evaluated:

1. **SuperUser** bypasses all checks — always has full access
2. **Project Admin** has admin access to projects where they're a member with role `admin`
3. **User/Viewer** access is determined by their project membership role
4. If a user is not a member of a project, they have no access (unless SuperUser)

---

## 3. Context-Sensitive Menus

### 3.1 Navigation Contexts

| Context | URL Pattern | Visible Menus |
|---------|-------------|---------------|
| **Project Browser** | `/projects` | Platform config (SuperUser only), User menu, Dashboard |
| **Editor Workbench** | `/projects/:id/edit` | Project config (Admin+), User menu, Dashboard |
| **Admin Console** | (overlay) | Full user management, audit trail (SuperUser only) |

### 3.2 Menu Structure

**Platform Configuration** (SuperUser only, visible on `/projects`):
- User Management (list, create, edit, deactivate)
- Role Assignment (platform-level)
- Audit Trail (read-only)
- Platform Settings

**Project Configuration** (visible on `/projects/:id/edit`, role-gated):
- Project Members (Admin+ can manage)
- Project Settings (Admin+ can edit)
- Project Info (all assigned users can view)

### 3.3 UI Implementation

The auth.js user bar will show context-appropriate buttons:
- On `/projects`: "Dashboard" + "Admin" (if SuperUser) + "Sign out"
- On `/projects/:id/edit`: "Dashboard" + "Project Settings" (if Admin+) + "Admin" (if SuperUser) + "Sign out"

The Admin console overlay will have tabs:
- **Users** (SuperUser): list/create/edit/deactivate users
- **Projects** (SuperUser): list all projects, manage members
- **Audit Trail** (SuperUser, read-only): search/filter/view audit events

---

## 4. Database Schema (V13–V15)

### V13 — RBAC Enhancement

```sql
-- 1. Normalize platform roles: rename 'editor' → 'user' in tenant_memberships
--    (The existing CHECK allows superuser/admin/editor/viewer;
--     we rename editor→user to match the new hierarchy)
UPDATE syson_tenant_memberships SET role = 'user' WHERE role = 'editor';
ALTER TABLE syson_tenant_memberships
  DROP CONSTRAINT IF EXISTS syson_tenant_memberships_role_check;
ALTER TABLE syson_tenant_memberships
  ADD CONSTRAINT syson_tenant_memberships_role_check
  CHECK (role IN ('superuser', 'admin', 'user', 'viewer'));

-- 2. Add project role: 'editor' → 'user' normalization already done (V5 has admin/user/viewer)
--    No change needed to syson_project_members — it already uses admin/user/viewer.

-- 3. Add role_changed_at and role_changed_by to track role history
ALTER TABLE syson_tenant_memberships
  ADD COLUMN IF NOT EXISTS role_changed_at TIMESTAMPTZ DEFAULT now(),
  ADD COLUMN IF NOT EXISTS role_changed_by UUID REFERENCES syson_users(id);

ALTER TABLE syson_project_members
  ADD COLUMN IF NOT EXISTS role_changed_at TIMESTAMPTZ DEFAULT now(),
  ADD COLUMN IF NOT EXISTS role_changed_by UUID REFERENCES syson_users(id);

-- 4. Add is_system_admin flag to syson_users for quick SuperUser lookup
ALTER TABLE syson_users
  ADD COLUMN IF NOT EXISTS is_system_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Set the default admin user as system admin
UPDATE syson_users SET is_system_admin = TRUE
  WHERE email = 'admin@localhost' AND EXISTS (
    SELECT 1 FROM syson_tenant_memberships
    WHERE user_id = syson_users.id AND role = 'superuser'
  );
```

### V14 — Audit Trail Module

```sql
-- Dedicated audit trail table for RBAC configuration changes.
-- Separate from syson_audit_events (which tracks general application events).
-- This table is append-only — no UPDATE or DELETE should be permitted.
CREATE TABLE IF NOT EXISTS syson_rbac_audit_trail (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type      VARCHAR(100) NOT NULL,
  -- 'user_created', 'user_deactivated', 'user_reactivated',
  -- 'platform_role_changed', 'project_role_changed',
  -- 'project_created', 'project_deleted',
  -- 'member_added', 'member_removed', 'member_role_changed',
  -- 'password_reset', 'password_changed', 'login_success', 'login_failed'

  actor_id        UUID NOT NULL REFERENCES syson_users(id),
  actor_email     VARCHAR(255) NOT NULL,
  actor_role      VARCHAR(50) NOT NULL,

  target_type     VARCHAR(100) NOT NULL,
  -- 'user', 'project', 'project_member', 'platform_role'

  target_id       VARCHAR(255) NOT NULL,
  target_email    VARCHAR(255),

  project_id      TEXT,
  -- For project-level events, the project this relates to

  old_value       JSONB,
  -- Previous state: { "role": "user" }, { "active": true }, etc.

  new_value       JSONB,
  -- New state: { "role": "admin" }, { "active": false }, etc.

  reason          TEXT,
  -- Optional human-readable reason for the change

  ip_address      VARCHAR(45),
  user_agent      TEXT,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes for common query patterns
CREATE INDEX idx_rbac_audit_trail_actor ON syson_rbac_audit_trail(actor_id);
CREATE INDEX idx_rbac_audit_trail_target ON syson_rbac_audit_trail(target_type, target_id);
CREATE INDEX idx_rbac_audit_trail_project ON syson_rbac_audit_trail(project_id);
CREATE INDEX idx_rbac_audit_trail_event_type ON syson_rbac_audit_trail(event_type);
CREATE INDEX idx_rbac_audit_trail_created_at ON syson_rbac_audit_trail(created_at);

-- Row-level security: only superusers can read (enforced at application level)
-- The table itself has no RLS — access control is in the Java service layer.

-- Revoke direct write access from the application role to enforce append-only
-- (application writes go through the service which uses INSERT only)
-- REVOKE UPDATE, DELETE ON syson_rbac_audit_trail FROM syson;
```

### V15 — Platform Settings Table

```sql
CREATE TABLE IF NOT EXISTS syson_platform_settings (
  key             VARCHAR(255) PRIMARY KEY,
  value           JSONB NOT NULL,
  description     TEXT,
  updated_by      UUID REFERENCES syson_users(id),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed default settings
INSERT INTO syson_platform_settings (key, value, description) VALUES
  ('platform.name', '"SysON"', 'Platform display name'),
  ('platform.allow_self_registration', 'false', 'Allow users to self-register'),
  ('platform.default_project_role', '"user"', 'Default role for new project members'),
  ('platform.max_projects_per_user', '0', 'Max projects per user (0 = unlimited)')
ON CONFLICT (key) DO NOTHING;
```

---

## 5. Backend Implementation

### 5.1 New Java Files

**Package: `org.eclipse.syson.auth.audit`**

| File | Purpose |
|------|---------|
| `RbacAuditTrailEntity.java` | JPA entity for `syson_rbac_audit_trail` |
| `RbacAuditTrailRepository.java` | Spring Data JPA repository |
| `RbacAuditTrailService.java` | Service: append events, query with filters, pagination |
| `RbacAuditTrailController.java` | REST endpoints: GET /api/v1/user/admin/audit-trail |

**Package: `org.eclipse.syson.auth`** (modifications)

| File | Changes |
|------|---------|
| `UserController.java` | Add audit trail logging to all admin endpoints |
| `AdminService.java` (new) | Centralized admin operations with audit logging |
| `ProjectAccessService.java` | Add audit logging for project membership changes |
| `SecurityConfig.java` | Add `/api/v1/user/admin/audit-trail` to SuperUser-only |
| `AuthController.java` | Add audit logging for login success/failure |

### 5.2 New REST Endpoints

All under `/api/v1/user/admin/` (requires SuperUser):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/audit-trail` | List audit events (paginated, filterable) |
| GET | `/audit-trail?event_type=X` | Filter by event type |
| GET | `/audit-trail?target_type=X&target_id=Y` | Filter by target |
| GET | `/audit-trail?project_id=X` | Filter by project |
| GET | `/audit-trail?from=DATE&to=DATE` | Filter by date range |
| GET | `/audit-trail/stats` | Summary counts by event type |

**Query parameters:**
- `page` (default 0), `size` (default 50)
- `event_type`, `target_type`, `target_id`, `project_id`
- `actor_id`, `from` (ISO date), `to` (ISO date)
- `sort` (default `created_at,desc`)

### 5.3 Audit Event Types

| Event Type | Trigger | old_value | new_value |
|------------|---------|-----------|-----------|
| `user_created` | Admin creates user | — | `{ email, role, active }` |
| `user_deactivated` | Admin deactivates user | `{ active: true }` | `{ active: false }` |
| `user_reactivated` | Admin reactivates user | `{ active: false }` | `{ active: true }` |
| `platform_role_changed` | Admin changes platform role | `{ role: old }` | `{ role: new }` |
| `password_reset` | Admin resets user password | — | `{ by: admin_email }` |
| `password_changed` | User changes own password | — | — |
| `project_created` | User creates project | — | `{ name }` |
| `project_deleted` | Admin deletes project | `{ name }` | — |
| `member_added` | Admin adds user to project | — | `{ user_email, role }` |
| `member_removed` | Admin removes user from project | `{ user_email, role }` | — |
| `member_role_changed` | Admin changes project role | `{ role: old }` | `{ role: new }` |
| `login_success` | User logs in | — | `{ ip }` |
| `login_failed` | Failed login attempt | — | `{ email, ip, reason }` |

---

## 6. Frontend Implementation

### 6.1 auth.js Changes

**Context-sensitive menu rendering:**

```javascript
function getContextSensitiveMenus() {
  var path = window.location.pathname;
  var isProjectBrowser = path === '/projects' || path === '/';
  var isEditor = /^\/projects\/[^/]+\/edit/.test(path);
  var isSuperUser = state.roles.indexOf('superuser') !== -1;
  var isAdmin = isSuperUser || state.roles.indexOf('admin') !== -1;

  var menus = [];

  // Dashboard — always visible
  menus.push({ id: 'syson-dashboard-btn', label: 'Dashboard', title: 'Dashboard' });

  // Admin — SuperUser only
  if (isSuperUser) {
    menus.push({ id: 'syson-admin-btn', label: 'Admin', title: 'Administration' });
  }

  // Project Settings — Admin+ in editor context
  if (isEditor && isAdmin) {
    menus.push({ id: 'syson-project-settings-btn', label: 'Project Settings', title: 'Project Settings' });
  }

  // Sign out — always
  menus.push({ id: 'syson-logout-btn', label: 'Sign out', title: 'Sign out' });

  return menus;
}
```

**Admin console tabs:**
- Users tab: user list, create user form, role dropdown, activate/deactivate
- Audit Trail tab: event list with filters, date range, event type dropdown

### 6.2 Admin Console: Audit Trail Tab

The admin console overlay will get a new "Audit Trail" tab that:
- Lists events in a table: Time | Actor | Action | Target | Details
- Supports filtering by event type, date range, target
- Shows old_value → new_value diffs for role changes
- Is read-only (no edit/delete buttons)
- Is only visible to SuperUsers

---

## 7. Implementation Order

### Phase 1: Database (V13–V15)
1. Write V13 migration: normalize roles, add tracking columns
2. Write V14 migration: create audit trail table
3. Write V15 migration: create platform settings table
4. Apply all migrations to live DB

### Phase 2: Backend
1. Create `RbacAuditTrailEntity` + `RbacAuditTrailRepository`
2. Create `RbacAuditTrailService` with append + query methods
3. Create `AdminService` wrapping user/project operations with audit logging
4. Add audit logging to existing endpoints (UserController, AuthController, ProjectAccessService)
5. Create `RbacAuditTrailController` (SuperUser-only GET endpoints)
6. Update SecurityConfig for audit trail endpoint

### Phase 3: Frontend
1. Update `mountUserBar()` for context-sensitive menus
2. Add "Project Settings" button in editor for Admin+
3. Add "Audit Trail" tab to admin console (SuperUser only)
4. Implement audit trail table UI with filters

### Phase 4: Testing
1. Update regression test suite with RBAC-specific tests
2. Test role hierarchy enforcement
3. Test audit trail access (SuperUser can read, others cannot)
4. Test context-sensitive menus at all levels
5. Screenshots and commit

---

## 8. Files to Create/Modify

### New Files
- `backend/.../db/migration/V13__rbac_role_normalization.sql`
- `backend/.../db/migration/V14__rbac_audit_trail.sql`
- `backend/.../db/migration/V15__platform_settings.sql`
- `backend/.../auth/audit/RbacAuditTrailEntity.java`
- `backend/.../auth/audit/RbacAuditTrailRepository.java`
- `backend/.../auth/audit/RbacAuditTrailService.java`
- `backend/.../auth/audit/RbacAuditTrailController.java`
- `backend/.../auth/AdminService.java`

### Modified Files
- `backend/.../auth/UserController.java` — audit logging
- `backend/.../auth/AuthController.java` — audit logging
- `backend/.../auth/service/ProjectAccessService.java` — audit logging
- `backend/.../auth/SecurityConfig.java` — audit trail endpoint
- `frontend/syson/public/auth.js` — context menus, audit trail tab
- `scripts/check-syson-editor-ui-regression.py` — RBAC tests

---

## 9. Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Breaking existing admin APIs | All changes are additive; existing endpoints keep working |
| Audit trail performance | Indexes on common query columns; pagination enforced |
| Role normalization (editor→user) | V13 UPDATE runs before constraint change; existing data preserved |
| Frontend regression | Full 21-test regression suite runs after every change |
| Audit trail tampering | Application-level enforcement: service only allows INSERT, never UPDATE/DELETE |
