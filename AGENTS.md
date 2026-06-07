# Notes for AI coding agents working on this SysON fork

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
