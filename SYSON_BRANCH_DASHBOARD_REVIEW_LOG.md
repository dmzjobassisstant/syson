# SysON Branch Indicator + Dashboard Project Visibility Review Log

## Scope

User reported:
- Editor did not show which branch the model is on.
- Dashboard only showed a few projects.

## Guardrails

- Preserved Sirius editor path; enterprise features remain sidecar additions.
- Did not refactor auth.js login boot flow.
- Tested through the public nginx URL and backend API.

## Findings

1. Dashboard project visibility was limited by `/api/v1/user/me/projects` returning only explicit rows in `syson_project_members`.
   - Live DB had 39 rows in `project` but only 10 `syson_project_members` rows for the admin user.
   - Superuser/admin users therefore only saw 10 projects.
2. The admin console element-locking project list called `/api/v1/user/projects`, which is not a JSON API and falls through to the SPA HTML shell.
3. The branch indicator existed in `auth.js`, but it was fragile across React route transitions:
   - It set an `injected` flag after the first header attempt.
   - It disconnected the observer after 10 seconds.
   - If the user opened a project/editor after the initial dashboard route, the badge could remain hidden/missing.
4. Several branch/save sidecar calls used `_origFetch` without explicit Authorization headers, bypassing the global fetch interceptor.

## Fixes Applied

- `UserController.myProjects()` now returns all `project` rows for `superuser`/`admin` roles, ordered newest first; scoped users still get only their explicit memberships.
- `ProjectAccessService.roleRank()` now recognizes `superuser` and `editor` roles.
- `auth.js` branch indicator is now idempotent and route-transition safe:
  - hides outside project routes;
  - reattaches if React replaces the header;
  - listens to body mutations and history route changes;
  - displays `🌿 Branch: <name>` with branch UUID in tooltip.
- `auth.js` project list calls now use `/api/v1/user/me/projects` with Authorization.
- Element-locking project rows now handle `{projectId, projectName}` response shape.
- Save/default-branch sidecar calls now include Authorization.
- Synced `frontend/syson/public/auth.js` to `frontend/syson-webapp/src/main/resources/static/auth.js` and live `/var/www/syson/auth.js`.
- Rebuilt and redeployed the `syson` container with preserved host networking/environment.

## Verification

- `node -c frontend/syson/public/auth.js` passed.
- Maven package passed:
  - `mvn -pl backend/application/syson-application -DskipTests -Dcheckstyle.skip=true package -o`
- Frontend JAR verified real:
  - nested `syson-frontend-2025.6.1.jar` = 1,762,222 bytes;
  - contains `static/index.html` and `static/auth.js`.
- Docker image rebuilt and `syson` restarted; health became ready.
- `bash scripts/check-syson-login-regression.sh` passed.
- `BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh` passed.
- API verification after deploy:
  - `/api/v1/user/me/projects` returns 39 projects for admin.
  - default branch and version-control tree endpoints return JSON.
- Browser/Playwright verification via `https://syson.damuza-consulting.com/projects/<id>/edit`:
  - editor body contains `🌿 Branch: main`;
  - `#syson-branch-ind` exists and is visible;
  - branch tooltip contains the branch UUID;
  - Dashboard project buttons count = 39.
  - Screenshot: `/tmp/syson_branch_dashboard_verify.png`

## Remaining Risks

- Repository already had pre-existing uncommitted version-control files and untracked `SysONBranchIndicator.tsx` / `SysONSaveButton.tsx` before this pass. They were not introduced by this review.
