# Notes for AI coding agents working on this SysON fork

## Critical: stabilization guide

Before making any architectural, build, or deployment decision, read:

- `SYSON_STABILIZATION_GUIDE.md` — the correct build/deploy path, sidecar extension architecture, upstream-aligned recovery procedure, and known pitfalls from the June 2026 stabilization.

This document defines the non-negotiable rules for extending SysON without breaking the Sirius editor path.

## Critical: enterprise account/access/audit work is live — do not overwrite

Read `SYSON_ENTERPRISE_ACCESS_AUDIT_HANDOFF.md` before modifying auth, admin APIs, security config, audit logging, or `auth.js`.

Hard rules:

1. Do not move admin endpoints out of `UserController` into a new controller unless you first prove the new controller mappings are hit live. New SysON/Sirius `@RestController` classes have previously compiled but fallen through to SPA `index.html`.
2. Do not remove the `/api/**` `AuthenticationEntryPoint` and `AccessDeniedHandler` in `SecurityConfig`; they prevent protected API failures from being swallowed by SPA fallback as HTTP 200 HTML.
3. Do not treat HTTP 200 as API success unless `Content-Type` and JSON body shape are correct.
4. Audit metadata is JSONB. Do not write raw strings into `syson_audit_events.metadata`.
5. Before claiming success, run both regression scripts:

```bash
cd /root/syson-fork
bash scripts/check-syson-login-regression.sh
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
```

## Critical: element history / warehouse / version-control planning

Before implementing granular SysML element history, data warehouse queries, branch/merge/baseline/tag workflows, locks, or GitGraph UI, read:

- `doc/plans/2026-06-07-syson-enterprise-element-history-vc-plan.md`

This plan intentionally adapts BowTie Pilot's append-only `model_changes` + materialized `head_*` pattern for SysON. Do not replace it with naive full-blob history. Do not use random UUIDs for extracted elements. Preserve Sirius editor compatibility and keep `document.content` / `representation_content.content` as compatibility storage while adding stable object-level history.

## Critical: do not break the login overlay

The production site is `https://syson.damuza-consulting.com`. Authentication is injected by `frontend/syson/public/auth.js` and is also direct-served by nginx at `/var/www/syson/auth.js`.

Less-capable agents have repeatedly introduced the same regression: after changing `auth.js`, the login screen disappears or the React app renders over/behind it. Avoid large rewrites.

### Hard rules for `frontend/syson/public/auth.js`

1. **Do not refactor the login boot path.** Leave these functions and their call order intact unless you are specifically fixing login:
   - `loadState()`
   - `blockApp()`
   - `showLogin()`
   - `login()`
   - `mountUserBar()`
   - `logout()`
   - `refreshToken()`
2. **If there is no token, the boot path must be exactly:**
   - `loadState()`
   - `blockApp()`
   - `showLogin('')`
3. **`blockApp()` must inject:** `#root { display: none !important; }` as a `<style id="syson-root-blocker">` in `<head>`.
   - Do not replace this with `root.style.display = 'none'`; Vite/React can override that.
4. **`showLogin()` must tolerate being called while `document.body` is still null.**
   - `auth.js` loads in `<head>` before body exists.
   - If body does not exist, register a one-time `DOMContentLoaded` callback and return.
5. **After successful login, call `window.location.reload()`.**
   - Do not simply remove the overlay. The React app may have already booted with failed unauthenticated GraphQL requests and can stay blank.
6. **Add optional UI features as self-contained functions.**
   - Do not restructure existing login/interceptor code.
   - Prefer inline styles for feature overlays to avoid interfering with login CSS.
7. **Use `_origFetch` for login/dashboard API calls.**
   - The global `window.fetch` is monkey-patched to add JWT headers.
8. **Do not trust syntax checks alone.** `node -c auth.js` can pass while the login overlay is broken.

### Required verification after any `auth.js` change

Run the regression check before claiming success:

```bash
cd /root/syson-fork
bash scripts/check-syson-login-regression.sh
```

This verifies:
- `/auth.js` is served by nginx.
- the source `auth.js` and live `/var/www/syson/auth.js` match.
- a fresh no-localStorage Chromium session renders `#syson-auth-overlay`.
- `#syson-root-blocker` is present.
- `#root` is hidden while unauthenticated.
- login API accepts the install-default `admin` / `admin` credentials.
- protected user endpoint works with the returned token.

### Sirius Web interface control + integration KB

Before debugging any Sirius Web UI snackbar, project open/create failure, raw i18n key, blank workbench, GraphQL validation error, or bundled frontend/backend mismatch, read both documents in this order:

- `SIRIUS_WEB_INTERFACE_CONTROL_DOCUMENT.md` — authoritative Interface Control Document for the compatible frontend/backend protocol: GraphQL fields/types/arguments/input drift, i18n endpoint/namespaces, auth.js request normalization, verification rules, and error-to-contract mapping.
- `SIRIUS_WEB_INTEGRATION_KB.md` — symptom/fix knowledge base and operational debugging notes.

Do not second-guess or rediscover known Sirius Web messages before checking the ICD. It documents the required compatibility surface (`ProjectTemplateContext`, `allProjectTemplates`, `CreateProjectInput.templateId/libraryIds/natures`, `workbenchConfiguration`, viewer/project capabilities, i18n locale fallback), the correct GraphQL compatibility patterns, and the verification commands.

### Authenticated blank-screen regression

The login overlay can pass while an already-authenticated browser still shows a blank app. If a user reports a blank page after login, inspect the browser network/console with a real token. The known root cause here was a frontend/backend GraphQL contract mismatch:

- frontend startup queries required `viewer.language`, `viewer.namespaces`, and `viewer.capabilities`;
- project list queries required `project.capabilities`;
- without those fields, GraphQL validation errors occurred before/while the React app mounted, leaving `#root` effectively blank.

Do **not** try to fix that by refactoring `auth.js`. The compatibility fields live in:

- `backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/*CapabilitiesDataFetcher.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/ViewerLanguageDataFetcher.java`
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/ViewerNamespacesDataFetcher.java`

Deploying this fix requires rebuilding and restarting the backend Docker container, not just copying `/var/www/syson/auth.js`.

### Deployment note for fast auth.js iteration

For an `auth.js`-only fix, do this instead of rebuilding the full Docker image:

```bash
cp frontend/syson/public/auth.js /var/www/syson/auth.js
systemctl reload nginx
bash scripts/check-syson-login-regression.sh
```

Only rebuild/repackage the JAR when you need the change baked into the application image.

### Known-good login details for smoke tests

- Username: `admin`
- Password: `admin`

Do not put stronger passwords back into source-controlled docs.

## Critical: build command rules

See `SYSON_STABILIZATION_GUIDE.md` §3 for the full build/deploy path. Summary:

1. **Never use `-am`** in Maven builds. It rebuilds the frontend stub JAR (2 KB, no index.html).
2. **Always use `-o`** (offline) to avoid GitHub Packages auth failures.
3. **Verify the frontend JAR is 1.75 MB+** with `static/index.html` before Docker build.
4. **Preserve existing Docker environment** when restarting — never lose DB credentials.

## Critical: sidecar architecture

Enterprise features (auth, RBAC, element persistence, version control, history/warehouse, locks) are **additive sidecars** that run alongside the upstream Sirius Web editor. They must never:

- Modify upstream core tables (`project`, `semantic_data`, `document`, `representation_*`)
- Replace or bypass `document.content` persistence
- Block editor saves when sidecar extraction fails
- Redefine upstream GraphQL types

See `SYSON_STABILIZATION_GUIDE.md` §2 for the full sidecar architecture rules.
