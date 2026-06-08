# Sirius Web Integration Knowledge Base for SysON Agents

**Purpose:** help future agents stabilize `syson.damuza-consulting.com` and the `/root/syson-fork` codebase when the packaged Sirius Web React frontend and the Java/GraphQL backend drift out of sync.

**Authoritative ICD:** read `SIRIUS_WEB_INTERFACE_CONTROL_DOCUMENT.md` first for the controlled frontend/backend protocol/interface/message contract. That ICD defines the required GraphQL fields/types/arguments/input compatibility, i18n endpoint/namespaces, auth.js request normalization contract, error-to-interface mapping, and verification checklist. This KB is the companion symptom/fix playbook.

**Audience:** Hermes/Codex/Claude agents working on SysON. Read this before changing `auth.js`, GraphQL schema, Sirius Web dependencies, project creation/opening, or the SysON Docker build.

**Current live stack shape:**
- Local repo: `/root/syson-fork`
- Live container: `syson`, port `8080`
- Live URL: `https://syson.damuza-consulting.com`
- Auth injection: `frontend/syson/public/auth.js`, served fast from nginx at `/var/www/syson/auth.js`
- GraphQL endpoint: `/api/graphql`
- Primary compatibility files:
  - `backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls`
  - `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/*DataFetcher.java`
  - `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/*SchemaPatcher.java`
  - `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/LocaleController.java`
  - `backend/application/syson-application/src/main/resources/i18n/{en,fr}/*.json`

---

## 1. Core integration rule

SysON is not a conventional single-version app here. It is a custom SysON/Sirius Web build with:

1. pre-built Sirius Web frontend assets packaged inside a frontend JAR,
2. Sirius Web backend GraphQL schemas and data fetchers from Maven JARs,
3. custom Damuza auth/RBAC/history layers added alongside Sirius Web,
4. nginx direct-serve overrides for `auth.js`.

Most visible UI failures are **contract drift** between the already-built React frontend and the backend GraphQL/i18n/API surface. Do not assume an HTTP 200 or a successful login means the Sirius workbench is healthy.

**Rule:** when the browser shows a snackbar, blank page, raw i18n key, or project open/create error, capture the exact GraphQL validation error and patch the backend compatibility layer. Do not start by refactoring `auth.js` or rebuilding the GitHub-Packages-auth frontend.

---

## 2. Symptom map

### A. Project browser/list loads but shows GraphQL snackbar

Typical messages:

```text
Validation error (UnknownType) : Unknown type 'ProjectTemplateContext'
Validation error (UnknownArgument@[viewer/projectTemplates]) : Unknown field argument 'context'
Validation error (FieldUndefined@[viewer/allProjectTemplates]) : Field 'allProjectTemplates' in type 'Viewer' is undefined
```

Meaning:
- The frontend bundle is querying a newer/alternate Sirius Web schema than the backend exposes.
- This can happen even if the currently served JS bundle looks newer/older than the backend because browser tabs may cache old chunks, and different create/open paths issue different queries.

Known fixes/patterns:
- `ProjectTemplateContext` and `projectTemplates(..., context)` are handled by `ProjectTemplateContextSchemaPatcher.java`.
- **New known unresolved drift from 2026-06-07 screenshot:** create/open path queries `viewer.allProjectTemplates`. If it is undefined, add compatibility for `Viewer.allProjectTemplates: [ProjectTemplate!]!` and either:
  - wire it to the same underlying project template provider/data fetcher as `projectTemplates`, or
  - return the same three known templates as a safe fallback (`batmobile-template`, `sysmlv2-template`, `sysmlv2-library-template`) if no provider is easily injectable.

### B. Authenticated app is blank after login

Typical messages:

```text
Field 'language' in type 'Viewer' is undefined
Field 'namespaces' in type 'Viewer' is undefined
Field 'capabilities' in type 'Viewer' is undefined
Field 'capabilities' in type 'Project' is undefined
```

Meaning:
- Login overlay and `/me` may work, but Sirius React startup fails during GraphQL validation.
- Fix belongs in GraphQL compatibility schema + data fetchers, not in the auth overlay.

Existing files:
- `syson-auth-compat.graphqls` extends `Viewer` and `Project`.
- `ViewerLanguageDataFetcher.java`
- `ViewerNamespacesDataFetcher.java`
- `ViewerCapabilitiesDataFetcher.java`
- `ProjectCapabilitiesDataFetcher.java`

### C. Opening a project/workbench fails or goes blank

Typical messages:

```text
Field 'workbenchConfiguration' in type 'EditingContext' is undefined
Unknown type 'DefaultViewConfiguration'
```

Meaning:
- The project editor/workbench bootstrap query expects newer Sirius workbench configuration types.

Existing files:
- `syson-auth-compat.graphqls` extends `EditingContext` with `workbenchConfiguration` and defines `WorkbenchConfiguration`, `WorkbenchMainPanelConfiguration`, `WorkbenchSidePanelConfiguration`, `ViewConfiguration`, `DefaultViewConfiguration`.
- `EditingContextWorkbenchConfigurationDataFetcher.java` supplies a safe empty/default workbench configuration.

### D. Project create page opens but Create Project throws unexpected errors

Likely causes:
- The create form path issues `viewer.allProjectTemplates` or library/template queries not used on the project browser landing page.
- The backend accepts project browser queries but not modal/create-specific GraphQL operations.
- The 2026 frontend sends `CreateProjectInput.templateId` and `libraryIds`, while the 2025 backend only defines `natures` plus the separate `createProjectFromTemplate` mutation.
- The 2025 backend still requires `CreateProjectInput.natures`; if the frontend payload omits it, GraphQL validation fails with `Field 'natures' has coerced Null value for NonNull type '[String!]!'`.
- The GraphQL HTTP response may be 200 with `errors`, so status-only checks miss it.

Known compatibility fixes:
- `syson-auth-compat.graphqls` extends `Viewer` with `allProjectTemplates: [ProjectTemplate!]!` and extends `CreateProjectInput` with optional `templateId`/`libraryIds`.
- `ViewerAllProjectTemplatesDataFetcher.java` returns the same template list as the paginated `projectTemplates` provider.
- `auth.js` has a tiny `normalizeGraphQLRequestBody()` fetch normalizer that injects `natures: []` into `createProject` GraphQL requests when the newer frontend sends `templateId` but no `natures`. Keep this minimal; do not refactor the login boot path.

Investigation:
1. Capture network GraphQL JSON from the browser or reproduce with a tokenless/tokened curl.
2. Search the Vite bundle for the field/query name:
   ```bash
   curl -sk https://syson.damuza-consulting.com/assets/index-DNlyxqpy.js -o /tmp/live-index.js
   python3 - <<'PY'
   t=open('/tmp/live-index.js').read()
   for s in ['allProjectTemplates','projectTemplates','workbenchConfiguration']:
       print(s, t.find(s))
   PY
   ```
3. Compare backend packaged schema:
   ```bash
   python3 - <<'PY'
   import zipfile, glob
   for jar in glob.glob('/root/.m2/repository/org/eclipse/sirius/**/*.jar', recursive=True):
       try:
           with zipfile.ZipFile(jar) as z:
               for n in z.namelist():
                   if n.endswith('.graphqls'):
                       txt=z.read(n).decode('utf-8','ignore')
                       if 'allProjectTemplates' in txt or 'projectTemplates' in txt:
                           print(jar, n)
                           i=max(txt.find('allProjectTemplates'), txt.find('projectTemplates'))
                           print(txt[max(0,i-250):i+450])
       except Exception:
           pass
   PY
   ```

### E. Raw translation keys show in the UI

Typical visible keys:

```text
createProjectArea.createNewProject
listProjectsArea.existingProjects
useProjectsTableColumns.name
projectsTable.actions
```

Meaning:
- The Sirius frontend uses i18next HTTP backend at `/api/locales/{language}/{namespace}.json`.
- Missing language/namespace JSON or locale fallback returns 404/HTML.
- Some builds treat dotted keys literally.

Existing fixes:
- `LocaleController.java` serves classpath resources under `i18n/{language}/{namespace}.json`.
- It falls back from `en-US` → `en`, `fr-CA` → `fr`, unknown → `en`.
- Add both nested and flat key aliases when needed, for example:
  ```json
  "useProjectsTableColumns": { "name": "Name" },
  "useProjectsTableColumns.name": "Name"
  ```
- `auth.js` includes a small MutationObserver cleanup for known visible fallback keys. Keep this tiny; do not generalize it into a frontend rewrite.

### F. Login overlay works but user bar/dashboard/admin styles are broken

Meaning:
- `auth.js` style injection was originally tied to unauthenticated `showLogin()`, so authenticated reloads did not always receive user-bar styles.

Existing fix:
- `ensureAuthStyles()` injects `STYLES` with id `syson-auth-styles`.
- `mountUserBar()` calls `ensureAuthStyles()` before rendering.
- For fast iteration:
  ```bash
  cp frontend/syson/public/auth.js /var/www/syson/auth.js
  systemctl reload nginx
  bash scripts/check-syson-login-regression.sh
  ```

### G. Protected API checks appear to pass but return HTML

Meaning:
- Sirius/Spring SPA fallback can return `index.html` for paths that look like API routes if no JSON handler is registered.

Verification rule:
- Always assert status **and** `Content-Type` **and** JSON body shape.
- A `200 text/html` from `/api/v1/...` is a routing failure, not a success.

---

## 3. GraphQL compatibility implementation rules

### 3.1 First inspect upstream schemas

Before adding anything to `.graphqls`, inspect the packaged Sirius/SysON schemas. Duplicating an existing GraphQL type crashes schema creation.

```bash
python3 - <<'PY'
import zipfile, glob
terms=['ProjectTemplate','allProjectTemplates','projectTemplates','WorkbenchConfiguration']
for jar in glob.glob('/root/.m2/repository/org/eclipse/**/*.jar', recursive=True):
    try:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if n.endswith('.graphqls'):
                    txt=z.read(n).decode('utf-8','ignore')
                    if any(t in txt for t in terms):
                        print('\nJAR', jar)
                        print('FILE', n)
                        for t in terms:
                            i=txt.find(t)
                            if i >= 0:
                                print('TERM', t)
                                print(txt[max(0,i-180):i+260])
    except Exception:
        pass
PY
```

### 3.2 Use `.graphqls` only for genuinely new fields/types

Safe in `syson-auth-compat.graphqls`:
- `extend type Viewer { language namespaces capabilities }`
- new capability object types if absent upstream
- `extend type Project { capabilities }`
- `extend type EditingContext { workbenchConfiguration }` when absent
- workbench configuration types when absent

Dangerous in `.graphqls`:
- redefining `ProjectTemplate`, `ViewerProjectTemplatesConnection`, etc. if Sirius already defines them.
- redefining `Viewer.projectTemplates` to add arguments. GraphQL Java treats field redefinition as conflict.

### 3.3 Use `TypeDefinitionConfigurer` to patch existing fields

If a type/field exists but the frontend expects an extra argument, use `TypeDefinitionConfigurer` and edit `TypeDefinitionRegistry` programmatically.

Existing example:
- `ProjectTemplateContextSchemaPatcher.java`
  - adds enum `ProjectTemplateContext` if absent
  - finds `Viewer.projectTemplates`
  - adds optional `context: ProjectTemplateContext`
  - patches both `ObjectTypeDefinition` and `ObjectTypeExtensionDefinition`

Use the same pattern for future field-argument drift.

### 3.4 Add data fetchers only when backend has no existing fetcher

If Sirius already has a data fetcher for a field, prefer schema patching only. If the field is new to the backend, add a small data fetcher.

Existing examples:
- `ViewerCapabilitiesDataFetcher.java`
- `ProjectCapabilitiesDataFetcher.java`
- `EditingContextWorkbenchConfigurationDataFetcher.java`

For `viewer.allProjectTemplates`, likely add:
- schema: `extend type Viewer { allProjectTemplates: [ProjectTemplate!]! }` **only if absent**
- data fetcher: `@QueryDataFetcher(type = "Viewer", field = "allProjectTemplates")`
- return objects whose shape matches `ProjectTemplate` fields (`id`, `label`, `imageURL`) or use Sirius provider if available.

---

## 4. Reproduction and verification commands

### 4.1 Check container health

```bash
docker ps --filter name=syson --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
curl -sk -o /dev/null -w 'https:%{http_code}\n' https://syson.damuza-consulting.com/
curl -s -o /dev/null -w 'local:%{http_code}\n' http://127.0.0.1:8080/
docker logs --tail=200 syson 2>&1 | egrep -i 'ValidationError|Unknown field|Unknown type|Exception|ERROR' || true
```

### 4.2 Reproduce a GraphQL validation error directly

GraphQL returns HTTP 200 even when validation fails. Always parse the JSON.

```bash
python3 - <<'PY'
import json, urllib.request
BASE='https://syson.damuza-consulting.com'
q='query { viewer { allProjectTemplates { id label imageURL } } }'
req=urllib.request.Request(BASE+'/api/graphql', data=json.dumps({'query': q}).encode(), headers={'Content-Type':'application/json'})
with urllib.request.urlopen(req, timeout=20) as r:
    data=json.loads(r.read())
print(json.dumps(data, indent=2)[:2000])
PY
```

### 4.3 Verify known project template compat

```bash
python3 - <<'PY'
import json, urllib.request
BASE='https://syson.damuza-consulting.com'
q='query getProjectTemplates($page: Int!, $limit: Int!, $context: ProjectTemplateContext!) { viewer { projectTemplates(page: $page, limit: $limit, context: $context) { edges { node { id label imageURL } } pageInfo { hasNextPage count } } } }'
req=urllib.request.Request(BASE+'/api/graphql', data=json.dumps({'query': q, 'variables': {'page': 0, 'limit': 20, 'context': 'PROJECT_BROWSER'}}).encode(), headers={'Content-Type':'application/json'})
with urllib.request.urlopen(req, timeout=20) as r:
    data=json.loads(r.read())
print('errors:', data.get('errors'))
print('count:', data.get('data',{}).get('viewer',{}).get('projectTemplates',{}).get('pageInfo',{}).get('count'))
PY
```

### 4.4 Verify i18n fallback

```bash
python3 - <<'PY'
import json, urllib.request
BASE='https://syson.damuza-consulting.com'
for lang in ['en', 'en-US', 'fr-CA', 'zz']:
    with urllib.request.urlopen(f'{BASE}/api/locales/{lang}/sirius-web-application.json', timeout=20) as r:
        d=json.loads(r.read())
    print(lang, d.get('useProjectsTableColumns.name'), d['useProjectsTableColumns']['name'])
PY
```

### 4.5 Browser-level capture

Use a fresh headless Chromium profile. Do not rely on your existing logged-in browser tab because stale chunks/localStorage can hide or create issues.

Current helper from the stabilization session:
- `/tmp/syson_verify_capture.py`

If missing, recreate a small DevTools/Playwright probe that:
1. loads unauthenticated page and verifies `#syson-auth-overlay`,
2. injects `localStorage.syson_auth` from `/api/auth/login`,
3. loads the project browser and asserts no visible `Unknown type`/`Unknown field argument`,
4. opens/create project path and captures body text + snackbar messages,
5. saves screenshots to `/tmp/syson-screenshots/`.

### 4.6 Required regression scripts

After `auth.js` changes:

```bash
cd /root/syson-fork
node -c frontend/syson/public/auth.js
cp frontend/syson/public/auth.js /var/www/syson/auth.js
systemctl reload nginx
bash scripts/check-syson-login-regression.sh
```

After backend/security/GraphQL changes:

```bash
cd /root/syson-fork
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -DskipTests package -o
docker build -t syson-rbac:latest backend/application/syson-application
# redeploy using existing /tmp/syson.env
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
```

---

## 5. Build/deploy rules specific to Sirius Web

### 5.1 Prefer offline Maven

Sirius Web Maven artifacts come from GitHub Packages and can fail metadata resolution online. The needed JARs are usually cached in `~/.m2/repository`.

Use:

```bash
mvn -pl backend/application/syson-application -Dcheckstyle.skip=true -DskipTests package -o
```

### 5.2 Avoid rebuilding frontend from source unless absolutely required

The Sirius frontend uses GitHub Packages npm dependencies. `npm install` may fail without auth. Prefer:
- backend compatibility patches,
- nginx direct-served `auth.js`,
- frontend JAR repack only when needed.

### 5.3 Verify the fat JAR contains the patched nested dependency/schema

If you patch a cached Maven JAR or schema, verify it got copied into the Spring Boot fat JAR:

```bash
python3 - <<'PY'
import zipfile, tempfile, os
app='backend/application/syson-application/target/syson-application-2025.6.1.jar'
with zipfile.ZipFile(app) as z:
    nested=z.read('BOOT-INF/lib/sirius-web-application-2025.6.1.jar')
fd,tmp=tempfile.mkstemp(suffix='.jar'); os.write(fd,nested); os.close(fd)
with zipfile.ZipFile(tmp) as z:
    txt=z.read('schema/siriusweb.graphqls').decode('utf-8')
    for s in ['ProjectTemplateContext','context: ProjectTemplateContext','allProjectTemplates']:
        print(s, s in txt)
os.remove(tmp)
PY
```

---

## 6. Known current compatibility inventory

### Already addressed

- `viewer.language`
- `viewer.namespaces`
- `viewer.capabilities`
- `project.capabilities`
- `viewer.projectTemplates(page, limit, context)`
- `ProjectTemplateContext`
- `editingContext.workbenchConfiguration`
- `DefaultViewConfiguration`
- `/api/locales/{language}/{namespace}.json` serving and language fallback
- raw visible `useProjectsTableColumns.name` via flat alias + small DOM cleanup
- authenticated `auth.js` style injection for the user/admin bar

### Newly observed and should be fixed next

- `viewer.allProjectTemplates` undefined when opening/creating a project.

Screenshot evidence from user: project creation page shows snackbar:

```text
Validation error (FieldUndefined@[viewer/allProjectTemplates]) : Field 'allProjectTemplates' in type 'Viewer' is undefined
```

Recommended first implementation:
1. inspect packaged schema to confirm `ProjectTemplate` type exists,
2. add `allProjectTemplates: [ProjectTemplate!]!` to `extend type Viewer` if absent,
3. add `ViewerAllProjectTemplatesDataFetcher.java`,
4. return the same template set used by the project browser if provider injection is not obvious,
5. run direct GraphQL reproduction for `viewer { allProjectTemplates { id label imageURL } }`,
6. open/create project in headless browser and verify no snackbar.

---

## 7. Representation table schema mismatches

The live database's `representation_metadata` and `representation_content` tables have columns that upstream Sirius Web 2025.6.1 doesn't populate on INSERT:

1. `representation_metadata_id UUID NOT NULL` — added DEFAULT `gen_random_uuid()` via V10
2. `semantic_data_id UUID NOT NULL` — made nullable via V12
3. `id` column is TEXT but upstream JDBC binds UUID parameters — fixed via UUID→TEXT implicit cast (V11, requires superuser)

**Symptoms:**

- "Exception while fetching data (/detailsEvent)" with "bad SQL grammar" on SELECT COUNT
- "Exception while fetching data (/createProjectFromTemplate)" with "null value in column" on INSERT
- "An unexpected error has occurred, please refresh the page" toast on template creation

**Root cause:** The live DB tables were created manually or by older Flyway migrations that don't match the upstream Liquibase schema. The upstream code assumes specific column types and nullability.

**Fix migrations:**

- V10: `ALTER TABLE representation_metadata ALTER COLUMN representation_metadata_id SET DEFAULT gen_random_uuid();`
- V11: `CREATE CAST (uuid AS text) WITH INOUT AS IMPLICIT;` (requires superuser)
- V12: `ALTER TABLE representation_metadata ALTER COLUMN semantic_data_id DROP NOT NULL;`

**Verification:** After deploying, create a project from the SysMLv2 template. The workbench should open with Explorer tree, model documents, and Details panel. No error snackbars.

---

## 8. Editor UI interaction surface

These are the tested interaction paths through the Sirius Web editor UI, verified by `scripts/check-syson-editor-ui-regression.py`:

1. **Login overlay** (unauthenticated) — `#syson-auth-overlay` renders with email/password fields and login button. `#syson-root-blocker` hides `#root`.
2. **Project browser** — list existing projects, create blank project, create from template (SysMLv2, Batmobile), upload, delete.
3. **Workbench** — Explorer (left), Details (right), Representations panel, toolbar with user menu and omnibox.
4. **Explorer tree** — expand/collapse document nodes (toggles), select model elements (Packages, Parts, etc.), create new model documents, filter/search.
5. **Details panel** — shows element properties when a model element is selected. Documents (.sysml) do NOT populate Details.
6. **Representations panel** — create new representations (diagrams), open existing representations, delete representations.
7. **Diagram canvas** — SVG/React Flow rendering, zoom, pan, tool palette.
8. **Sidebar tabs** — left: Explorer, Validation; right: Details, Query, Representations, Related Elements. All switchable via `[data-testid="viewselector-{name}"]`.
9. **User menu** (auth.js bar) — injected into top nav: "admin | SUPERUSER | Dashboard | Admin | Sign out". NOT the same as `[data-testid="user-menu"]` which is the Sirius hamburger menu (Projects/Libraries/Help).
10. **Search/omnibox** — opens with `Ctrl+K`, shows command palette.

**Key gotchas for test automation:**

- The `[data-testid="user-menu"]` is the Sirius hamburger menu, not the auth.js user bar.
- Selecting a `.sysml` document node in the Explorer does NOT populate the Details panel — only model elements (Package, Part, etc.) do.
- Modal backdrops from previous interactions can intercept clicks on "more" buttons. Always close menus/modals with Escape before new interactions.
- The `#syson-root-blocker` style element is injected into `<head>` before `<body>` exists. Use `document.querySelector()` fallback if `wait_for_selector` times out.

---

## 9. Anti-patterns that wasted time

- Refactoring `auth.js` to fix backend GraphQL validation errors.
- Trusting `/api/graphql` HTTP 200 without parsing JSON `errors`.
- Trusting `node -c auth.js` without running the login browser regression.
- Creating new REST controller classes for admin APIs instead of adding methods to a known-working controller; some new controllers compile but route to SPA HTML.
- Redefining GraphQL types already present in Sirius JARs.
- Building with `-am` or rebuilding the frontend stub JAR, producing missing UI assets.
- Assuming a fresh browser and the user's browser see the same chunks/localStorage/cache state.

---

## 10. Quick triage checklist for future agents

When the user sends a screenshot with a Sirius snackbar:

1. Read the snackbar text exactly.
2. Search Docker logs:
   ```bash
   docker logs --tail=300 syson 2>&1 | egrep -i 'ValidationError|Unknown field|Unknown type|FieldUndefined'
   ```
3. Reproduce with a direct GraphQL query if possible.
4. Search the live bundle for the query/field name.
5. Inspect packaged `.graphqls` inside Maven JARs.
6. Decide:
   - missing new field/type → add `syson-auth-compat.graphqls` + data fetcher,
   - existing field missing an argument → `TypeDefinitionConfigurer`,
   - missing translation → `LocaleController`/i18n JSON,
   - pure auth UI issue → minimal `auth.js` patch + nginx direct-serve.
7. Build/deploy the smallest layer needed.
8. Verify with direct GraphQL JSON and a fresh browser screenshot.
9. Update this KB and `syson-development` skill if a new Sirius drift pattern is discovered.
