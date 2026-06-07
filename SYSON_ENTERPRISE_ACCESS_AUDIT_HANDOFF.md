# SysON Enterprise Account / Access / Audit Handoff

This file is intentionally blunt. Future agents: do not overwrite or simplify this work unless you have run the regression commands at the bottom and have a concrete failing result.

## Current implementation status

Implemented in this fork:

- Enterprise account administration under the existing working `UserController` namespace:
  - `GET /api/v1/user/admin/users`
  - `POST /api/v1/user/admin/users`
  - `PUT /api/v1/user/admin/users/{userId}/password`
  - `PUT /api/v1/user/admin/users/{userId}/deactivate`
  - `PUT /api/v1/user/admin/users/{userId}/reactivate`
  - `PUT /api/v1/user/admin/tenants/{tenantId}/roles/{userId}`
  - `GET /api/v1/user/admin/projects/{projectId}/members`
  - `POST /api/v1/user/admin/projects/{projectId}/members`
  - `DELETE /api/v1/user/admin/projects/{projectId}/members/{userId}`
  - `GET /api/v1/user/admin/audit/events`
- Self-service endpoints:
  - `GET /api/v1/user/me`
  - `PUT /api/v1/user/me/password`
  - `GET /api/v1/user/me/projects`
  - `POST /api/v1/user/password/reset/request`
  - `POST /api/v1/user/password/reset/complete`
- Additive V6 schema:
  - account metadata columns on `syson_users`
  - audit events
  - password reset tokens
  - email verification tokens
  - invitations
  - element-level permissions
  - branch permissions
- Backend services/entities/repositories for account admin, roles, access control, password reset, and audit logging.
- `auth.js` admin UI overlay, added without changing the fragile login boot path.
- Regression scripts:
  - `scripts/check-syson-login-regression.sh`
  - `scripts/check-syson-enterprise-access-regression.sh`

## Critical architectural guardrails

### 1. Do not create new REST controller classes for new SysON APIs

In this SysON/Sirius Web app, newly created `@RestController` beans can compile and instantiate but never receive request mappings. The symptom is a protected-looking API path returning the SPA `index.html` with HTTP 200.

Use existing working controllers instead. Enterprise admin endpoints currently live in:

- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/UserController.java`

Do not split them into a new `AdminController` unless you have first proven its mappings are registered and hit live.

### 2. Do not refactor `auth.js` login boot path

`frontend/syson/public/auth.js` is fragile because it is injected before the Sirius React app. The unauthenticated path must remain:

1. `loadState()`
2. no token detected
3. `blockApp()`
4. `showLogin('')`

`blockApp()` must inject `#root { display: none !important; }` as a style tag. Login success must call `window.location.reload()`, not just remove the overlay.

### 3. Protected API checks must validate body shape, not just status code

SysON's SPA fallback can return HTTP 200 and `text/html` for API-looking URLs. A passing security check must prove JSON was returned or denied correctly.

For protected API regressions, check:

- HTTP status
- `Content-Type`
- JSON body shape

A 200 `text/html` response is not a valid API success.

### 4. API denial must not be swallowed by SPA fallback

`SecurityConfig` now has explicit API `AuthenticationEntryPoint` and `AccessDeniedHandler` branches. For `/api/**`:

- unauthenticated => `401 {"error":"unauthorized"}`
- unauthorized role => `403 {"error":"forbidden"}`

Do not remove those handlers. They prevent the previous false-200 `index.html` fallback.

### 5. V6 audit metadata is JSONB

`AuditEvent.metadata` maps to PostgreSQL `jsonb`. Do not write raw strings like `admin` into it. Use valid JSON strings, e.g. `{ "message": "admin" }`.

The current `AuditLogService.recordAccountEvent(...)` wraps simple metadata strings into JSON.

## Known live deployment pattern

Existing production container:

- Container: `syson`
- Image: `syson-rbac:latest`
- Host port: `8080`
- Public URL: `https://syson.damuza-consulting.com`
- Frontend auth override: `/var/www/syson/auth.js`
- Flyway is disabled in the live env; additive SQL migrations have been applied manually.

For an `auth.js`-only change:

```bash
cd /root/syson-fork
cp frontend/syson/public/auth.js /var/www/syson/auth.js
systemctl reload nginx
bash scripts/check-syson-login-regression.sh
```

For backend changes:

```bash
cd /root/syson-fork
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -Dtest=EnterpriseAccountAccessAuditControllerRedTest,EnterpriseAccountAccessAuditRedTest test -o
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -DskipTests package -o
docker build --no-cache -t syson-rbac:latest backend/application/syson-application
# preserve current container env, then replace container
```

Do not use `-am`; it can rebuild the frontend stub and break the UI.

## Required verification before claiming done

Run all of these:

```bash
cd /root/syson-fork
node -c frontend/syson/public/auth.js
bash scripts/check-syson-login-regression.sh
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
bash scripts/check-syson-enterprise-access-regression.sh
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -Dtest=EnterpriseAccountAccessAuditControllerRedTest,EnterpriseAccountAccessAuditRedTest test -o
```

Expected outcomes:

- login overlay renders for fresh no-token browser
- admin can log in
- admin APIs return JSON for admin
- unauthenticated admin API does not leak JSON
- viewer/non-admin cannot access admin APIs; should receive API 403 JSON
- admin can create a user
- admin can reset that user's password
- audit events include create/login/reset activity

## Files added/changed for this feature

Primary files:

- `backend/application/syson-application/src/main/resources/db/migration/V6__enterprise_account_access_audit.sql`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/UserController.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/SecurityConfig.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/AuthController.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/TenantContext.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/entity/*`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/model/*`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/repository/*`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/service/*`
- `backend/application/syson-application/src/test/java/org/eclipse/syson/auth/EnterpriseAccountAccessAuditRedTest.java`
- `backend/application/syson-application/src/test/java/org/eclipse/syson/auth/EnterpriseAccountAccessAuditControllerRedTest.java`
- `frontend/syson/public/auth.js`
- `scripts/check-syson-enterprise-access-regression.sh`

## If something fails later

Do not revert the whole feature. First check:

1. Is the response HTML from SPA fallback instead of JSON?
2. Is `/var/www/syson/auth.js` stale compared with `frontend/syson/public/auth.js`?
3. Was Docker rebuilt with `--no-cache` after packaging the jar?
4. Did someone create a new controller instead of adding methods to `UserController`?
5. Is audit metadata valid JSONB?

Only then patch the smallest failing piece.
