# SysON Stabilization Guide — Build, Deploy, and Extension Rules

> **Purpose:** Prevent future agents from repeating the stabilization failures of June 2026. This document codifies the correct build/deploy path, the sidecar extension architecture, and the recovery procedure when the SysON fork drifts from its upstream baseline.
>
> **Upstream-aligned commit:** `540ea78c86ab9b3af3acc9413a6e51dd7e795a19`
>
> **Live system:** `https://syson.damuza-consulting.com`

---

## 1. What went wrong (June 2026)

The SysON fork was extended with RBAC, element persistence, version control, and enterprise history/warehouse features. These extensions were intended as a **sidecar layer** — additive tables, services, and REST APIs that run alongside the upstream Sirius Web editor without modifying its persistence path.

In practice, the extensions drifted:

1. **Flyway was enabled in `application.properties`** (`spring.flyway.enabled=true`), conflicting with the live deployment requirement of `SPRING_FLYWAY_ENABLED=false` and manual SQL application.
2. **GraphQL compatibility patches were applied reactively** without verifying the bundled frontend's actual query contract against the backend schema.
3. **The `auth.js` login overlay was fragile** — multiple agents refactored it, breaking the boot sequence (`loadState → blockApp → showLogin`).
4. **The `document.is_read_only` column** lacked a DEFAULT in the live DB, blocking Sirius document writes after migration.
5. **Build commands used `-am`**, which rebuilt the frontend stub JAR (2 KB) instead of preserving the prebuilt 1.75 MB frontend JAR.
6. **GraphQL schema conflicts** — `.graphqls` files redefined types already defined in upstream Sirius Web JARs, causing `ValidationError` on startup.

The result: the live system appeared to work (login, REST APIs) but the Sirius editor path (project open → Explorer → Details → diagrams) was broken.

---

## 2. The sidecar architecture principle

**Rule: Enterprise features must be additive sidecars, not replacements.**

```
┌─────────────────────────────────────────────────────────┐
│                    Sirius Web Editor                     │
│  document.content ──► Explorer ──► Details ──► Diagrams  │
│  representation_content ──► Diagram canvas               │
│  Liquibase core tables: project, semantic_data, document │
└──────────────────────────┬──────────────────────────────┘
                           │ read only (never modify)
                           ▼
┌─────────────────────────────────────────────────────────┐
│              Enterprise Sidecar Layer                     │
│  syson_users, syson_tenants, syson_sessions              │
│  syson_elements, syson_relationships (V3)                │
│  syson_branches, syson_commits, syson_changes (V4)       │
│  syson_project_members (V5)                              │
│  syson_head_elements, syson_branch_heads (V7)            │
│  syson_tags, syson_merge_requests, syson_element_locks (V8)│
│  JWT auth, RBAC, audit, admin APIs                       │
│  Save event extraction (shadow mode)                     │
└─────────────────────────────────────────────────────────┘
```

### Rules

1. **Never modify upstream core tables** (`project`, `semantic_data`, `document`, `representation_metadata`, `representation_content`). The only exception is `document.is_read_only` DEFAULT fix (V9 migration).
2. **Enterprise tables live in the `syson_*` schema prefix.** They reference upstream `project(id)` via TEXT foreign keys.
3. **Save event extraction is shadow-mode.** `SemanticDataSaveListener` catches `SemanticDataUpdatedEvent` and extracts to V7 head tables. Failures are logged and audited but **never block the editor save**.
4. **Enterprise sidecar services must use `Propagation.REQUIRES_NEW`** so their failures don't roll back Sirius save transactions.
5. **New JPA entities use explicit getters/setters** (no Lombok). Snake_case column names with `@Column(name = "...")`.

---

## 3. Correct build and deploy path

### 3.1 Build from source

```bash
cd /root/syson-fork

# CORRECT: backend only, offline, no -am
mvn -pl backend/application/syson-application \
    -DskipTests -Dcheckstyle.skip=true \
    package -o
```

**Never use `-am`** (also-make). It rebuilds `syson-frontend` from source, producing an empty 2 KB stub JAR with no `static/index.html`. The resulting container returns 404 on the UI.

### 3.2 Verify the frontend JAR is real

```bash
python3 - <<'PY'
from pathlib import Path
import zipfile, io
jar = Path('backend/application/syson-application/target/syson-application-2025.6.1.jar')
with zipfile.ZipFile(jar) as z:
    front = [n for n in z.namelist() if n.endswith('syson-frontend-2025.6.1.jar')]
    assert front, 'No frontend JAR found!'
    data = z.read(front[0])
    print(f'frontend jar: {len(data)} bytes')
    assert len(data) > 100_000, f'Frontend JAR is a stub ({len(data)} bytes)!'
    with zipfile.ZipFile(io.BytesIO(data)) as f:
        assert any(n == 'static/index.html' for n in f.namelist()), 'No index.html!'
    print('OK: real frontend JAR with index.html')
PY
```

### 3.3 Docker build and deploy

```bash
# Build image
docker build -t syson-rbac:latest backend/application/syson-application

# Restart container preserving existing environment
python3 - <<'PY'
import json, subprocess, time, sys
inspect = json.loads(subprocess.check_output(['docker', 'inspect', 'syson'], text=True))[0]
envs = inspect['Config']['Env']
cmd = ['docker', 'run', '-d', '--name', 'syson', '--restart', 'unless-stopped', '-p', '8080:8080']
for e in envs:
    if e.startswith(('PATH=', 'JAVA_HOME=', 'LANG=', 'LANGUAGE=', 'LC_ALL=')):
        continue
    cmd += ['-e', e]
cmd.append('syson-rbac:latest')
subprocess.run(['docker', 'stop', 'syson'], check=False, stdout=subprocess.DEVNULL)
subprocess.run(['docker', 'rm', 'syson'], check=False, stdout=subprocess.DEVNULL)
out = subprocess.check_output(cmd, text=True).strip()
print('started', out[:12])
for i in range(1, 100):
    try:
        subprocess.check_output(['curl', '-sf', 'http://localhost:8080/'], timeout=3)
        print('READY', i)
        sys.exit(0)
    except Exception:
        time.sleep(2)
print('NOT_READY')
subprocess.run(['docker', 'logs', '--tail', '120', 'syson'])
sys.exit(1)
PY
```

### 3.4 Nginx auth.js iteration (no rebuild needed)

For `auth.js`-only changes:

```bash
cp frontend/syson/public/auth.js /var/www/syson/auth.js
systemctl reload nginx
bash scripts/check-syson-login-regression.sh
```

The nginx config has `location = /auth.js { alias /var/www/syson/auth.js; }` which takes priority over the proxy.

### 3.5 Frontend JAR repackaging (when auth.js must be baked in)

When you need the `auth.js` injection baked into the deployed JAR:

1. Extract the prebuilt frontend JAR from Maven cache
2. Add `<script src="/auth.js"></script>` to `static/index.html` (after `<title>`)
3. Copy `auth.js` into `static/`
4. Repackage with `jar cfM`
5. Install to Maven cache with `mvn install:install-file`
6. Delete `_remote.repositories` to prevent Maven from re-downloading the stub
7. Rebuild the fat JAR and Docker image

---

## 4. Upstream alignment and recovery

### 4.1 The upstream-aligned commit

Commit `540ea78c86ab9b3af3acc9413a6e51dd7e795a19` is the known-good Sirius Web/SysON baseline where the fork was aligned with upstream `eclipse-syson/syson`. All enterprise extensions were added on top of this commit.

### 4.2 When to use the aligned commit

If the live system is broken beyond repair (e.g., schema conflicts, corrupted migrations, build artifacts), use the aligned commit as a recovery anchor:

```bash
# Create stabilization branch from aligned commit
git checkout -b stabilization/<date> 540ea78c

# Cherry-pick extension commits in controlled layers:
# Layer 1: Clean upstream + live DB connection (already at aligned commit)
# Layer 2: Auth overlay only (auth.js + SecurityConfig + JWT)
# Layer 3: Backend auth/RBAC REST APIs (UserController, AuthController)
# Layer 4: Project membership/admin/audit
# Layer 5: Sidecar warehouse tables (V3-V8 migrations)
# Layer 6: Save-event extraction in shadow mode
# Layer 7: Locks/version graph APIs
```

**Test after each layer:**
- Create project
- Create SysMLv2 model
- Explorer tree appears
- Details panel works
- Diagram/representation creation works
- Save/reload works

### 4.3 What to preserve from the extensions

The enterprise extension commits (after `540ea78c`) add these features that should be preserved:

| Feature | Migration | Key Files |
|---------|-----------|-----------|
| JWT auth + login overlay | V2 | `SecurityConfig.java`, `JwtService.java`, `auth.js` |
| Element persistence | V3 | `ElementEntity`, `ElementRestController` |
| Version control | V4 | `VersionControlService`, `VersionControlController` |
| Project RBAC | V5 | `ProjectMembership`, `ProjectAccessService` |
| Enterprise account/access/audit | V6 | `AdminController`, `AuditLogService` |
| Model history/warehouse | V7 | `ModelSaveHistoryService`, `HeadMaterializationService` |
| Locks/tags/merge/integrity | V8 | `BranchLockService`, `IntegrityCheckService` |
| GraphQL compat | — | `syson-auth-compat.graphqls`, `TypeDefinitionConfigurer` classes |
| i18n translations | — | `i18n/{en,fr}/*.json`, `LocaleController.java` |
| `document.is_read_only DEFAULT` | V9 | `V9__document_read_only_default.sql` |
| `representation_metadata_id DEFAULT` | V10 | `V10__representation_metadata_id_default.sql` |
| UUID→TEXT implicit cast | V11 | `V11__uuid_text_cast.sql` |
| `semantic_data_id` nullable | V12 | `V12__semantic_data_id_nullable.sql` |

---

## 5. GraphQL compatibility patterns

### 5.1 Rule: never redefine upstream types

The upstream `sirius-web-application` JAR defines types like `ProjectTemplate`, `ViewerProjectTemplatesConnection`, etc. in its `siriusweb.graphqls`. The compat `.graphqls` **must not** redefine these — GraphQL Java rejects duplicates.

### 5.2 Rule: use TypeDefinitionConfigurer for field arguments

To add an argument (like `context: ProjectTemplateContext`) to an existing upstream field, use a `TypeDefinitionConfigurer` Spring component:

```java
@Component
public class ProjectTemplateContextSchemaPatcher implements TypeDefinitionConfigurer {
    @Override
    public void contribute(TypeDefinitionRegistry registry) {
        // 1. Add ProjectTemplateContext enum
        // 2. Find Viewer ObjectTypeDefinition
        // 3. Locate projectTemplates field
        // 4. Add optional context argument
    }
}
```

### 5.3 Rule: compat .graphqls is for NEW types only

`syson-auth-compat.graphqls` should only contain types that don't exist upstream:
- `ViewerCapabilities`, `ProjectCapabilities`
- `extend type Viewer` for NEW fields (language, namespaces, capabilities)
- `extend type Project` for NEW fields (capabilities)

### 5.4 Known required compat fields

| Field | Type | Required by |
|-------|------|-------------|
| `viewer.language` | `String` | Frontend startup |
| `viewer.namespaces` | `[String!]!` | Frontend startup |
| `viewer.capabilities` | `ViewerCapabilities!` | Frontend startup |
| `project.capabilities` | `ProjectCapabilities!` | Project list |
| `editingContext.workbenchConfiguration` | `WorkbenchConfiguration` | Editor open |
| `viewer.projectTemplates(context)` | arg on existing field | Template modal |

---

## 6. Required regression checks

After ANY change to the SysON fork, run these checks in order:

### 6.1 Login overlay regression

```bash
cd /root/syson-fork
bash scripts/check-syson-login-regression.sh
```

Verifies: `/auth.js` served, source matches live, fresh browser renders login overlay, root blocker active, login API works, `/me` works.

### 6.2 Enterprise access regression

```bash
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
```

Verifies: unauthenticated blocked, admin APIs work, viewer denied with JSON 403, password reset works, audit logs populated.

### 6.3 Browser workbench verification

```bash
python3 - <<'PY'
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    ctx = browser.new_context(viewport={'width':1600,'height':1000}, ignore_https_errors=True)
    page = ctx.new_page()
    page.goto('https://syson.damuza-consulting.com', wait_until='domcontentloaded')
    page.wait_for_selector('#syson-auth-overlay', timeout=30000)
    page.fill('#syson-email', 'admin')
    page.fill('#syson-password', 'admin')
    page.click('#syson-login-btn')
    page.wait_for_load_state('domcontentloaded')
    page.wait_for_timeout(7000)
    # Open a real SysML project (not empty test project)
    page.locator('a', has_text='Hermes Screenshot Project').first.click()
    page.wait_for_timeout(12000)
    page.wait_for_selector('[data-testid="site-left"]', timeout=60000)
    body = page.locator('body').inner_text()
    assert 'Explorer' in body, 'Explorer missing'
    assert 'Details' in body, 'Details missing'
    print('WORKBENCH OK: Explorer + Details visible')
    browser.close()
PY
```

### 6.4 Docker log check

```bash
docker logs --since 5m syson 2>&1 | grep -Ei 'ValidationError|Exception while fetching|ERROR' | tail -20
```

Should return empty (no GraphQL validation errors).

---

## 7. Flyway / Migration rules

1. **Live container uses `SPRING_FLYWAY_ENABLED=false`.** Migrations are applied manually.
2. **All V2-V12 migrations are applied to the live `syson` database.** New migrations get a V13+ number.
3. **New migrations must be idempotent** — use `CREATE TABLE IF NOT EXISTS`, `DO $$ BEGIN ... EXCEPTION WHEN duplicate_object ... END $$`.
4. **New migrations must only touch `syson_*` tables** except for carefully-audited FK references to upstream `project(id)`.
5. **New project_id columns must be TEXT**, not UUID. This matches upstream `project(id)`.
6. **V9 and V10 are migrations that touch upstream tables** (`document.is_read_only DEFAULT`, `representation_metadata_id DEFAULT`). These were one-time fixes. Do not add more upstream table modifications without auditing the full upstream Liquibase changelog.

---

## 8. auth.js rules

See `AGENTS.md` for the complete `auth.js` hard rules. Summary:

1. Never refactor the login boot path (`loadState → blockApp → showLogin`).
2. `blockApp()` must inject `#root { display: none !important; }` as a `<style>` in `<head>`.
3. After successful login, call `window.location.reload()`.
4. Add optional UI features as self-contained functions at the end of the file.
5. Use `_origFetch` for login/dashboard API calls.
6. Never trust `node -c` alone — run `scripts/check-syson-login-regression.sh`.

---

## 9. Controller registration constraint

New `@RestController` classes **silently fail** to register request mappings in SysON because the Sirius Web servlet filter scope prevents some beans from registering handlers.

**Rule:** Add new REST endpoints as methods on existing working controllers (`UserController`, `AuthController`, `ElementRestController`, `VersionControlController`).

**Exception:** Simple standalone controllers serving paths NOT overlapping with Sirius Web DO work. `LocaleController` (`/api/locales/...`) works because it doesn't conflict.

---

## 10. Version and build info

| Item | Value |
|------|-------|
| SysON version | 2025.6.1 |
| Upstream-aligned commit | `540ea78c` |
| Java | 17 (eclipse-temurin:17-jre-alpine) |
| PostgreSQL | 16 (host) |
| Docker image | `syson-rbac:latest` |
| Maven build | `mvn -pl backend/application/syson-application -DskipTests -Dcheckstyle.skip=true package -o` |
| Flyway | Disabled in container, manual application |
| Flyway migrations | V2-V12 applied |
