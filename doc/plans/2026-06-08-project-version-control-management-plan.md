# Project Version Control Management — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add a complete project version control management UI to SysON, inspired by BowTie Pilot's GitGraph system, allowing users to view branches, commits, baselines, tags, merge requests, and select a working branch that controls which model context loads in the SysON editor.

**Architecture:** Sidecar extension on existing SysON VC tables. New REST endpoints expose tag/MR lists and VC settings. Frontend UI injected via auth.js overlay (admin console + dashboard). Branch selection stored in project settings + localStorage, propagated to editor via URL hash.

**Tech Stack:** Spring Boot REST (existing), PostgreSQL sidecar tables, vanilla JS in auth.js, SVG-based GitGraph visualization.

---

## Constraints

1. **No Sirius core modifications** — all changes in SysON sidecar layers
2. **Sidecar architecture preserved** — new endpoints in existing controllers
3. **Backward compatible** — existing editor path unchanged
4. **Admin-only VC management** — only superuser/admin can change default branch
5. **All users can view VC graph** — read-only graph visible to all authenticated users

---

## Tasks

### Task 1: V20 Migration — Project VC Settings
- File: `backend/.../db/migration/V20__project_version_control_settings.sql`
- Add `default_branch_id` setting via existing `syson_project_settings` table
- No new table needed — reuse V19's key/value pattern

### Task 2: Backend — MergeRequestRepository Extensions
- File: `.../locks/repository/MergeRequestRepository.java`
- Add: `findByProjectIdOrderByCreatedAtDesc(String projectId)`
- Add: `countByProjectId(String projectId)`
- Add: `countByProjectIdAndStatus(String projectId, String status)`

### Task 3: Backend — TagRepository Extensions
- File: `.../locks/repository/TagRepository.java`
- Add: `countByProjectId(String projectId)`

### Task 4: Backend — New REST Endpoints in VersionControlController
- `GET /projects/{pid}/tags` — list all tags
- `GET /projects/{pid}/merge-requests` — list all MRs
- `GET /projects/{pid}/settings/default-branch` — get default branch ID
- `POST /projects/{pid}/settings/default-branch` — set default branch ID
- Update `GET /projects/{pid}/version-control/overview` — add `openMRCount`

### Task 5: Frontend — Admin Console Version Control Panel
- Add "Version Control" section to admin overlay
- Project selector dropdown
- Load VC tree data (branches, commits, baselines, tags)
- SVG GitGraph visualization (inspired by BowTie Pilot's VersionGraph)
- Branch list with head commit info
- Baseline list with status
- Tag list
- MR list with status
- Default branch selector (admin only)

### Task 6: Frontend — Dashboard VC Button
- Add "🔀 Version Control" button per project in dashboard
- Opens VC overlay for that specific project

### Task 7: Frontend — Branch Selection → Model Loading
- When user selects a default branch, store in localStorage
- On project open, read localStorage for branch context
- Pass branchId in URL hash for element locking integration
- "Open in Editor" button that navigates to project with branch context

### Task 8: Automated Regression Tests
- File: `scripts/check-syson-project-version-control.py`
- Tests: overview fields, tree fields, tag list, MR list, default branch GET/SET, auth checks

### Task 9: Regression Verification
- Run login regression
- Run enterprise regression
- Run editor UI regression
- Run element locking regression
- Run new VC management regression

### Task 10: Commit and Push
- Commit to rbac + main
