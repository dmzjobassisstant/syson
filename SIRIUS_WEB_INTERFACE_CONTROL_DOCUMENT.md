# Sirius Web / SysON Interface Control Document (ICD)

**Repository:** `/root/syson-fork`  
**Live system:** `https://syson.damuza-consulting.com`  
**Primary endpoint:** `/api/graphql`  
**Purpose:** define the compatibility interface between the bundled Sirius Web React frontend and the customized SysON Java/Spring/Sirius backend so future agents do not repeatedly rediscover the same message contracts by trial and error.

---

## 1. Executive summary

This SysON deployment is a version-skewed Sirius Web composition. The visible web application is a pre-built React/Vite Sirius Web frontend bundled inside `syson-frontend-*.jar`, while the backend is a custom SysON/Sirius Web Java application with Damuza auth/RBAC/history additions. The frontend and backend do not always expose the exact same GraphQL, i18n, and project-creation contracts.

Most recent UI failures were not ordinary business-logic bugs. They were **interface contract mismatches**:

- the frontend queried GraphQL fields/types/arguments the backend did not define;
- the frontend sent create-project mutation input fields the backend did not understand;
- the backend still required older input fields the newer frontend omitted;
- the frontend loaded i18n namespaces that the backend did not serve;
- HTTP 200 responses sometimes contained GraphQL `errors` or SPA HTML, so status-only checks were misleading.

The correct development pattern is therefore **interface compatibility control**, not speculative frontend rewrites.

When a future agent sees a Sirius Web snackbar, blank page, project create/open failure, raw translation key, or GraphQL validation error, it must first compare the frontend message contract against the backend schema and the compatibility layer documented here.

---

## 2. Scope

This ICD covers the interface between:

1. **Bundled Sirius Web frontend**
   - React/Vite assets inside the packaged frontend JAR.
   - Runtime JS served to browsers under `/assets/...`.
   - Custom injected `/auth.js` served by nginx and/or packaged into the frontend JAR.

2. **Customized SysON backend**
   - Spring Boot application.
   - Sirius Web GraphQL runtime at `/api/graphql`.
   - Compatibility schema at `backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls`.
   - Compatibility data fetchers and schema patchers in `org.eclipse.syson.auth`.
   - Locale files under `backend/application/syson-application/src/main/resources/i18n/{en,fr}`.

3. **Auxiliary REST/auth endpoints**
   - `/api/auth/login`, refresh/logout where applicable.
   - `/api/v1/user/**` custom Damuza user/project access APIs.
   - `/api/locales/{language}/{namespace}.json` i18n endpoint.

This ICD does **not** redefine the full Sirius Web protocol. It documents the compatibility contract known to be required by the bundled frontend currently used in this deployment and the safe patterns for adding more compatibility when new frontend messages appear.

---

## 3. Non-negotiable interface-control rules

1. **Do not guess from HTTP status.** GraphQL can return HTTP 200 with fatal `errors`. API-looking routes can return HTTP 200 with SPA `index.html`.
2. **Do not refactor `auth.js` to fix GraphQL validation errors.** Missing `Viewer`, `Project`, `EditingContext`, or input types belong in the backend GraphQL compatibility layer.
3. **Do not redefine upstream GraphQL types.** Inspect packaged `.graphqls` files first. GraphQL Java rejects duplicate type definitions.
4. **Use `TypeDefinitionConfigurer` to add arguments to existing upstream fields.** A `.graphqls` extension cannot safely override an existing field signature.
5. **Add data fetchers only for fields that have no upstream fetcher.** If upstream already implements the field, patch the field shape only.
6. **Prefer permissive/stub defaults for capability/configuration compatibility fields.** These fields are used to let the frontend mount; they are not the enterprise authorization source of truth.
7. **Keep request normalizers minimal.** `auth.js` may normalize one known GraphQL body shape, but it must not become a replacement Sirius frontend.
8. **Verify the exact UI path.** Login success does not prove project browser; project browser does not prove create modal; create success does not prove workbench open.
9. **Document every newly discovered frontend message in this ICD and `SIRIUS_WEB_INTEGRATION_KB.md`.** Avoid repeated reverse engineering.
10. **Enterprise features are sidecars.** Never modify upstream core tables (`project`, `semantic_data`, `document`, `representation_*`). See `SYSON_STABILIZATION_GUIDE.md` for the full sidecar architecture rules.
11. **Never use `-am` in Maven builds.** It rebuilds the frontend stub JAR (2 KB, no index.html). Always use `mvn -pl backend/application/syson-application -DskipTests -Dcheckstyle.skip=true package -o`.
12. **Verify the frontend JAR is real (1.75 MB+) before Docker build.** A 2 KB stub JAR produces a container that returns 404 on the UI.

---

## 4. Runtime topology

## 4. Runtime topology

### 4.1 Components

- Browser loads `https://syson.damuza-consulting.com/`.
- Nginx proxies application traffic to local container port `8080`.
- Nginx directly serves `/auth.js` from `/var/www/syson/auth.js` for fast auth-overlay iteration.
- React/Vite Sirius frontend calls `/api/graphql` for Sirius app operations.
- React/Vite frontend calls `/api/locales/{language}/{namespace}.json` for translations.
- Custom `auth.js` intercepts `fetch`/`XMLHttpRequest` to attach JWT bearer tokens.
- Custom backend auth/RBAC APIs live mainly under `/api/v1/user/**`.

### 4.2 Key files

- `SIRIUS_WEB_INTERFACE_CONTROL_DOCUMENT.md` — this ICD.
- `SIRIUS_WEB_INTEGRATION_KB.md` — symptom/fix knowledge base.
- `AGENTS.md` — mandatory future-agent guardrails.
- `backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls` — additive GraphQL compatibility definitions.
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/ProjectTemplateContextSchemaPatcher.java` — registry patcher for existing upstream types/fields.
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/*DataFetcher.java` — compatibility data fetchers.
- `backend/application/syson-application/src/main/java/org/eclipse/syson/auth/LocaleController.java` — i18n endpoint.
- `backend/application/syson-application/src/main/resources/i18n/{en,fr}/*.json` — frontend translation payloads.
- `frontend/syson/public/auth.js` — JWT injection, login overlay, tiny GraphQL request normalizer.
- `/var/www/syson/auth.js` — live direct-served copy.

---

## 5. GraphQL endpoint contract

### 5.1 Endpoint

- URL: `/api/graphql`
- Method: `POST`
- Content-Type: `application/json`
- Body shape:

```json
{
  "query": "query or mutation text",
  "variables": {},
  "operationName": "optional operation name"
}
```

### 5.2 Success and failure semantics

Do not treat HTTP 200 as proof of success. GraphQL validation/runtime errors are returned in the JSON body:

```json
{
  "errors": [
    {
      "message": "Validation error (FieldUndefined@[viewer/allProjectTemplates]) : Field 'allProjectTemplates' in type 'Viewer' is undefined",
      "locations": [{ "line": 1, "column": 18 }],
      "extensions": { "classification": "ValidationError" }
    }
  ]
}
```

Verification must assert:

- HTTP status is acceptable;
- body is JSON, not HTML;
- body does not contain `errors` for the exercised operation;
- body contains expected `data` shape.

---

## 6. Required compatibility GraphQL schema

The current compatibility schema file is:

```text
backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls
```

The currently required additive schema surface is below. Future agents must preserve these contracts unless the frontend JAR is proven to no longer need them.

### 6.1 Viewer compatibility fields

The bundled frontend requires these fields on `Viewer`:

```graphql
extend type Viewer {
  language: String!
  namespaces: [String!]!
  capabilities: ViewerCapabilities!
  allProjectTemplates: [ProjectTemplate!]!
}
```

#### `viewer.language`

- Type: `String!`
- Current data fetcher: `ViewerLanguageDataFetcher.java`
- Expected value: normally `"en"`
- Purpose: Sirius Web locale/bootstrap query.
- Failure if absent: authenticated app can blank before project browser renders.

#### `viewer.namespaces`

- Type: `[String!]!`
- Current data fetcher: `ViewerNamespacesDataFetcher.java`
- Expected value: list of i18n namespaces served by `LocaleController`.
- Purpose: tells frontend which i18n namespaces to request.
- Failure if absent: blank app or raw translation keys.

#### `viewer.capabilities`

- Type: `ViewerCapabilities!`
- Current data fetcher: `ViewerCapabilitiesDataFetcher.java`
- Current permissive response:

```json
{
  "projects": {
    "canList": true,
    "canCreate": true,
    "canUpload": true
  },
  "libraries": {
    "canList": true
  }
}
```

- Purpose: lets Sirius project browser decide which project/library actions are visible.
- Authorization note: this is not the final enterprise RBAC authority. Custom `/api/v1/**` endpoints and backend security still control privileged operations.

#### `viewer.allProjectTemplates`

- Type: `[ProjectTemplate!]!`
- Current data fetcher: `ViewerAllProjectTemplatesDataFetcher.java`
- Current implementation: delegates to `IProjectTemplateApplicationService.findAll(PageRequest.of(0, 100)).getContent()`.
- Purpose: newer create/open/project-template UI path queries all templates directly instead of only using paginated `viewer.projectTemplates`.
- Failure if absent:

```text
Validation error (FieldUndefined@[viewer/allProjectTemplates]) : Field 'allProjectTemplates' in type 'Viewer' is undefined
```

### 6.2 Viewer capabilities types

```graphql
type ViewerCapabilities {
  projects: ViewerProjectsCapabilities!
  libraries: ViewerLibrariesCapabilities!
}

type ViewerProjectsCapabilities {
  canList: Boolean!
  canCreate: Boolean!
  canUpload: Boolean!
}

type ViewerLibrariesCapabilities {
  canList: Boolean!
}
```

These are frontend-mount compatibility types. They should remain permissive unless a future frontend proves it requires different fields.

### 6.3 Project compatibility fields

The bundled project browser requires `Project.capabilities`:

```graphql
extend type Project {
  capabilities: ProjectCapabilities!
}

type ProjectCapabilities {
  canDownload: Boolean!
  canRename: Boolean!
  canDelete: Boolean!
  canEdit: Boolean!
  canDuplicate: Boolean!
  settings: ProjectSettingsCapabilities!
}

type ProjectSettingsCapabilities {
  canView: Boolean!
}
```

Current data fetcher: `ProjectCapabilitiesDataFetcher.java`.

Purpose: lets the frontend render action menus/buttons for listed projects without GraphQL validation failure.

Expected safe response shape:

```json
{
  "canDownload": true,
  "canRename": true,
  "canDelete": true,
  "canEdit": true,
  "canDuplicate": true,
  "settings": { "canView": true }
}
```

Authorization note: do not rely on this alone for enterprise permissions. It is a UI compatibility response.

### 6.4 Project template context compatibility

The upstream backend already defines `ProjectTemplate` and `viewer.projectTemplates(page, limit)`. The bundled frontend also sends a `context` argument using a `ProjectTemplateContext` enum.

Required frontend contract:

```graphql
enum ProjectTemplateContext {
  PROJECT_BROWSER
  PROJECT_TEMPLATE_MODAL
}
```

Required field shape after registry patching:

```graphql
viewer.projectTemplates(
  page: Int!,
  limit: Int,
  context: ProjectTemplateContext
): <upstream connection type>
```

Important: do **not** define `ProjectTemplate` or redefine `viewer.projectTemplates` in `.graphqls` if upstream already defines them.

Current implementation: `ProjectTemplateContextSchemaPatcher.java`:

- adds `ProjectTemplateContext` enum if absent;
- finds upstream `Viewer.projectTemplates`;
- appends optional `context: ProjectTemplateContext` argument;
- does not replace upstream data fetching.

Known failure modes:

```text
Validation error (UnknownType) : Unknown type 'ProjectTemplateContext'
Validation error (UnknownArgument@[viewer/projectTemplates]) : Unknown field argument 'context'
```

### 6.5 CreateProjectInput compatibility

The newer frontend create-project wizard sends fields that the older backend did not accept:

```graphql
extend input CreateProjectInput {
  templateId: ID
  libraryIds: [String!]
}
```

Known request-shape drift:

- newer frontend sends: `templateId`, `libraryIds`;
- older backend requires: `natures: [String!]!`;
- older backend also has separate template-based creation paths.

Current compatibility behavior:

1. `.graphqls` extends `CreateProjectInput` with optional `templateId` and `libraryIds`.
2. `ProjectTemplateContextSchemaPatcher.java` makes `CreateProjectInput.natures` optional at registry-build time if the upstream input type still marks it non-null.
3. `auth.js` has a minimal `normalizeGraphQLRequestBody()` that injects `natures: []` when a `createProject` GraphQL request contains `templateId` but omits `natures`.

Known failure without this:

```text
Field 'natures' has coerced Null value for NonNull type '[String!]!'
```

Interface rule: if future create-project failures occur, capture the exact mutation body first. Do not guess. Compare `variables.input` to the current `CreateProjectInput` contract.

### 6.6 EditingContext workbench compatibility

The newer workbench bootstrap query requires:

```graphql
extend type EditingContext {
  workbenchConfiguration: WorkbenchConfiguration!
}

type WorkbenchConfiguration {
  mainPanel: WorkbenchMainPanelConfiguration!
  workbenchPanels: [WorkbenchSidePanelConfiguration!]!
}

type WorkbenchMainPanelConfiguration {
  id: String!
  representationEditors: [WorkbenchRepresentationEditorConfiguration!]!
}

type WorkbenchRepresentationEditorConfiguration {
  representationId: String!
  isActive: Boolean!
}

type WorkbenchSidePanelConfiguration {
  id: String!
  isOpen: Boolean!
  views: [ViewConfiguration!]!
}

interface ViewConfiguration {
  id: String!
  isActive: Boolean!
}

type DefaultViewConfiguration implements ViewConfiguration {
  id: String!
  isActive: Boolean!
}
```

Current data fetcher: `EditingContextWorkbenchConfigurationDataFetcher.java`.

Current safe response:

```json
{
  "mainPanel": {
    "id": "main",
    "representationEditors": []
  },
  "workbenchPanels": []
}
```

Purpose: satisfy frontend validation and let the client use default panel/workbench behavior.

Known failure without this:

```text
Field 'workbenchConfiguration' in type 'EditingContext' is undefined
Unknown type 'DefaultViewConfiguration'
```

---

## 7. Current compatibility implementation matrix

### Backend schema/data fetcher files

- `syson-auth-compat.graphqls`
  - Adds Viewer fields: `language`, `namespaces`, `capabilities`, `allProjectTemplates`.
  - Adds `CreateProjectInput.templateId` and `CreateProjectInput.libraryIds`.
  - Adds `Project.capabilities` and capability types.
  - Adds `EditingContext.workbenchConfiguration` and workbench configuration types.

- `ProjectTemplateContextSchemaPatcher.java`
  - Adds `ProjectTemplateContext` enum.
  - Adds optional `context` argument to existing upstream `viewer.projectTemplates`.
  - Adds `viewer.allProjectTemplates` if not already present.
  - Makes `CreateProjectInput.natures` optional if the upstream input still has it as non-null.

- `ViewerLanguageDataFetcher.java`
  - Coordinates: `Viewer.language`.
  - Returns frontend bootstrap language.

- `ViewerNamespacesDataFetcher.java`
  - Coordinates: `Viewer.namespaces`.
  - Returns namespace list matching classpath i18n files.

- `ViewerCapabilitiesDataFetcher.java`
  - Coordinates: `Viewer.capabilities`.
  - Returns permissive project/library capability map.

- `ViewerAllProjectTemplatesDataFetcher.java`
  - Coordinates: `Viewer.allProjectTemplates`.
  - Returns first 100 templates from `IProjectTemplateApplicationService`.

- `ProjectCapabilitiesDataFetcher.java`
  - Coordinates: `Project.capabilities`.
  - Returns permissive project action capability map.

- `EditingContextWorkbenchConfigurationDataFetcher.java`
  - Coordinates: `EditingContext.workbenchConfiguration`.
  - Returns default/empty workbench configuration.

---

## 8. i18n interface contract

### 8.1 Endpoint

```text
GET /api/locales/{language}/{namespace}.json
```

Examples:

```text
/api/locales/en/sirius-web-application.json
/api/locales/fr/sirius-components-diagrams.json
/api/locales/en-US/sirius-components-core.json
```

### 8.2 Resolution behavior

Implemented by `LocaleController.java`:

1. Try exact language: `i18n/{language}/{namespace}.json`.
2. If language contains region, try base language: `en-US` -> `en`.
3. Fall back to English: `i18n/en/{namespace}.json`.
4. If missing, return 404.

Response must be JSON, not SPA HTML.

### 8.3 Required namespaces

Current classpath includes 14 namespaces in both English and French:

- `sirius-web-application`
- `sirius-components-core`
- `sirius-components-diagrams`
- `sirius-components-palette`
- `sirius-components-trees`
- `sirius-components-forms`
- `sirius-components-validation`
- `sirius-components-tables`
- `sirius-components-gantt`
- `sirius-components-portals`
- `sirius-components-selection`
- `sirius-components-deck`
- `sirius-components-formdescriptioneditors`
- `sirius-components-widget-reference`

Required file location:

```text
backend/application/syson-application/src/main/resources/i18n/{en,fr}/{namespace}.json
```

### 8.4 Known i18n failure signatures

Visible UI keys instead of labels:

```text
createProjectArea.createNewProject
listProjectsArea.existingProjects
useProjectsTableColumns.name
projectsTable.actions
```

Root causes:

- missing JSON namespace;
- wrong language fallback;
- endpoint returning HTML rather than JSON;
- dotted keys treated as literal keys by some frontend builds.

Compatibility rule: for dotted keys, add both nested and flat forms when needed:

```json
{
  "useProjectsTableColumns": {
    "name": "Name"
  },
  "useProjectsTableColumns.name": "Name"
}
```

---

## 9. Auth.js interface contract

`auth.js` is an interface shim between the unmodified Sirius Web frontend and the Damuza auth system. It must remain small and stable.

### 9.1 Live serving

- Source: `frontend/syson/public/auth.js`
- Live direct-served copy: `/var/www/syson/auth.js`
- Nginx route: exact-match `/auth.js`

For auth.js-only changes:

```bash
cd /root/syson-fork
cp frontend/syson/public/auth.js /var/www/syson/auth.js
systemctl reload nginx
bash scripts/check-syson-login-regression.sh
```

### 9.2 Required behavior

1. Load before React app.
2. Read JWT/user state from localStorage.
3. If no token:
   - inject `#root { display: none !important; }` as `<style id="syson-root-blocker">`;
   - render login overlay;
   - do not let React app show through.
4. On successful login:
   - store token/user state;
   - call `window.location.reload()`.
5. If token exists:
   - let React app boot normally;
   - attach bearer token to GraphQL/fetch/XHR requests;
   - mount user bar/dashboard controls.
6. Monkey-patch `window.fetch` and `XMLHttpRequest` enough to add the bearer-token `Authorization` header.
7. Normalize only known GraphQL request drift.

### 9.3 Current request normalizer contract

Current known normalizer:

- Applies only if body is a string containing `createProject` and `CreateProjectInput`.
- Parses JSON.
- If `variables.input.templateId` exists and `variables.input.natures` is absent, injects `natures: []`.
- Returns original body on parse errors or non-matching requests.

Required semantic:

```js
if (input has templateId && input lacks natures) {
  input.natures = [];
}
```

Do not broaden this into generic mutation rewriting unless a new captured message proves it is required.

### 9.4 Auth.js non-regression rules

- Do not refactor login boot sequence.
- Do not replace CSS blocker with `root.style.display = 'none'`.
- Do not remove reload-after-login.
- Do not change `_origFetch` semantics without running login regression.
- Do not use syntax check alone as verification.

---

## 10. Known frontend interaction paths and required contracts

### 10.1 Unauthenticated initial page load

Required:

- `/auth.js` loads before app is usable.
- No token -> login overlay visible.
- `#syson-root-blocker` present.
- `#root` hidden.

Verification:

```bash
cd /root/syson-fork
bash scripts/check-syson-login-regression.sh
```

### 10.2 Login

Required REST/auth path:

```text
POST /api/auth/login
Content-Type: application/json
```

The exact login response shape is consumed by `auth.js`; if changed, update `auth.js` and the regression script together.

After login, browser reloads and React app boots with token present.

### 10.3 Authenticated project browser startup

Required GraphQL fields:

- `viewer.language`
- `viewer.namespaces`
- `viewer.capabilities`
- `project.capabilities`
- `viewer.projectTemplates(page, limit, context?)`
- `ProjectTemplateContext` enum if query uses `context`

Required i18n:

- `/api/locales/{language}/{namespace}.json`
- all namespaces listed by `viewer.namespaces`

Failure signatures:

- blank root after login;
- raw i18n keys;
- snackbar with `UnknownType`, `UnknownArgument`, or `FieldUndefined`.

### 10.4 Create project modal/form

Required GraphQL fields/input:

- `viewer.allProjectTemplates`
- `viewer.projectTemplates(... context: PROJECT_TEMPLATE_MODAL)` if used by current frontend chunk;
- `CreateProjectInput.templateId` optional;
- `CreateProjectInput.libraryIds` optional;
- `CreateProjectInput.natures` must not be a blocker when omitted by newer frontend.

Required `auth.js` normalization:

- inject `natures: []` for createProject-with-templateId requests that omit `natures`.

Failure signatures:

```text
Field 'allProjectTemplates' in type 'Viewer' is undefined
Field 'natures' has coerced Null value for NonNull type '[String!]!'
```

### 10.5 Project open / workbench bootstrap

Required GraphQL fields/types:

- `EditingContext.workbenchConfiguration`
- `WorkbenchConfiguration`
- `WorkbenchMainPanelConfiguration`
- `WorkbenchRepresentationEditorConfiguration`
- `WorkbenchSidePanelConfiguration`
- `ViewConfiguration`
- `DefaultViewConfiguration`

Safe response may be empty/default as documented in section 6.6.

Failure signatures:

```text
Field 'workbenchConfiguration' in type 'EditingContext' is undefined
Unknown type 'DefaultViewConfiguration'
```

---

## 11. How to inspect the live frontend contract

Use this when a new message appears. Do not patch blindly.

### 11.1 Capture live frontend bundle

```bash
curl -sk https://syson.damuza-consulting.com/ -o /tmp/syson-index.html
python3 - <<'PY'
import re
html=open('/tmp/syson-index.html', encoding='utf-8', errors='ignore').read()
for src in re.findall(r'src="([^"]+\.js)"', html):
    print(src)
PY
```

Download the relevant bundle:

```bash
curl -sk https://syson.damuza-consulting.com/assets/<bundle>.js -o /tmp/live-index.js
```

Search for operation terms:

```bash
python3 - <<'PY'
text=open('/tmp/live-index.js', encoding='utf-8', errors='ignore').read()
terms = [
  'allProjectTemplates',
  'projectTemplates',
  'ProjectTemplateContext',
  'CreateProjectInput',
  'templateId',
  'libraryIds',
  'natures',
  'workbenchConfiguration',
  'viewer',
  'capabilities',
]
for term in terms:
    print(term, text.find(term))
PY
```

### 11.2 Inspect packaged backend GraphQL schemas

```bash
cd /root/syson-fork
python3 - <<'PY'
import zipfile, glob
terms = [
  'ProjectTemplate',
  'projectTemplates',
  'allProjectTemplates',
  'ProjectTemplateContext',
  'CreateProjectInput',
  'WorkbenchConfiguration',
  'workbenchConfiguration',
]
for jar in glob.glob('/root/.m2/repository/org/eclipse/**/*.jar', recursive=True):
    try:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if not name.endswith('.graphqls'):
                    continue
                text = z.read(name).decode('utf-8', 'ignore')
                if any(term in text for term in terms):
                    print('\nJAR', jar)
                    print('FILE', name)
                    for term in terms:
                        i = text.find(term)
                        if i >= 0:
                            print('TERM', term)
                            print(text[max(0, i-180):i+360])
    except Exception:
        pass
PY
```

### 11.3 Inspect effective compatibility files

```bash
cd /root/syson-fork
sed -n '1,220p' backend/application/syson-application/src/main/resources/schema/syson-auth-compat.graphqls
find backend/application/syson-application/src/main/java/org/eclipse/syson/auth -name '*DataFetcher.java' -o -name '*SchemaPatcher.java'
```

Note: use `read_file`/`search_files` tools when operating as Hermes. Shell commands above are for human/local reference.

---

## 12. How to add a new compatibility field safely

### 12.1 Decision tree

1. Capture exact GraphQL error.
2. Determine missing element type:
   - missing type -> maybe add type in `.graphqls` only if upstream does not already define it;
   - missing field on existing type -> `extend type` if field truly absent everywhere;
   - missing argument on existing field -> `TypeDefinitionConfigurer`, not `.graphqls` override;
   - missing input field -> `extend input` if GraphQL supports it and upstream has no duplicate;
   - required input field omitted -> registry patcher may need to loosen non-null, or frontend request normalizer may be needed.
3. Inspect upstream JAR `.graphqls` before editing.
4. Add data fetcher if and only if there is no upstream data fetcher for the new field.
5. Rebuild backend and deploy.
6. Verify the exact UI path that produced the operation.
7. Update this ICD and `SIRIUS_WEB_INTEGRATION_KB.md`.

### 12.2 `.graphqls` extension pattern

Use when field/type is absent upstream:

```graphql
extend type SomeExistingType {
  newField: NewFieldType!
}

type NewFieldType {
  id: String!
}
```

Then add a data fetcher:

```java
@QueryDataFetcher(type = "SomeExistingType", field = "newField")
public class SomeExistingTypeNewFieldDataFetcher implements IDataFetcherWithFieldCoordinates<Map<String, Object>> {
    @Override
    public Map<String, Object> get(DataFetchingEnvironment environment) {
        return Map.of("id", "default");
    }
}
```

### 12.3 Existing field argument pattern

Use `TypeDefinitionConfigurer` when upstream already defines the field and the frontend only needs an extra argument.

Do not write:

```graphql
extend type Viewer {
  projectTemplates(page: Int!, limit: Int, context: ProjectTemplateContext): SomeConnection!
}
```

if `Viewer.projectTemplates` already exists. That can conflict.

Instead patch the field in the registry, as `ProjectTemplateContextSchemaPatcher.java` does.

### 12.4 Input non-null relaxation pattern

If the frontend omits a backend-required field, and the backend can safely default it, patch the input type at registry-build time:

- locate `InputObjectTypeDefinition`;
- find the `InputValueDefinition`;
- if type is `NonNullType`, replace it with its wrapped type;
- ensure runtime code has a default/fallback path.

Current example: `CreateProjectInput.natures`.

### 12.5 Request normalizer pattern

Only use `auth.js` request normalization if GraphQL schema patching alone cannot satisfy the backend runtime path.

Constraints:

- match a very narrow query/mutation signature;
- parse JSON safely;
- modify only `variables.input` for known mutation;
- return original body on errors;
- add comments identifying the backend/frontend drift.

Current example: inject `natures: []` for createProject requests containing `templateId` but no `natures`.

---

## 13. Verification checklist

After any compatibility change, run the relevant checks.

### 13.1 Build/schema verification

```bash
cd /root/syson-fork
mvn clean package -DskipTests -Dcheckstyle.skip=true -pl backend/application/syson-application -o
```

If deploying live, wait for the container startup log:

```bash
docker logs syson 2>&1 | grep 'Started SysONApplication'
```

### 13.2 i18n endpoint verification

```bash
curl -sk -D- https://syson.damuza-consulting.com/api/locales/en/sirius-web-application.json -o /tmp/locale.json
python3 -m json.tool /tmp/locale.json >/dev/null
```

Assert content type/body are JSON, not HTML.

### 13.3 GraphQL validation verification

Use a real captured query where possible. Minimum smoke query pattern:

```bash
curl -sk https://syson.damuza-consulting.com/api/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"query { viewer { language namespaces capabilities { projects { canList canCreate canUpload } libraries { canList } } allProjectTemplates { id label } } }"}' \
  | python3 -m json.tool
```

Check there is no `errors` array.

### 13.4 Login overlay regression

```bash
cd /root/syson-fork
bash scripts/check-syson-login-regression.sh
```

### 13.5 Enterprise access regression

```bash
cd /root/syson-fork
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh
```

### 13.6 Browser path verification

Verify each path independently:

1. fresh unauthenticated browser -> login overlay;
2. login -> project browser;
3. create project dialog opens;
4. project creation succeeds;
5. newly created project opens;
6. workbench/editor page renders;
7. browser console/network has no GraphQL `ValidationError` for the exercised paths.

---

## 14. Known error-to-interface mapping

### Error: `Field 'language' in type 'Viewer' is undefined`

Missing contract: `Viewer.language`.  
Fix file: `syson-auth-compat.graphqls` + `ViewerLanguageDataFetcher.java`.

### Error: `Field 'namespaces' in type 'Viewer' is undefined`

Missing contract: `Viewer.namespaces`.  
Fix file: `syson-auth-compat.graphqls` + `ViewerNamespacesDataFetcher.java`.

### Error: `Field 'capabilities' in type 'Viewer' is undefined`

Missing contract: `Viewer.capabilities`.  
Fix file: `syson-auth-compat.graphqls` + `ViewerCapabilitiesDataFetcher.java`.

### Error: `Field 'capabilities' in type 'Project' is undefined`

Missing contract: `Project.capabilities`.  
Fix file: `syson-auth-compat.graphqls` + `ProjectCapabilitiesDataFetcher.java`.

### Error: `Unknown type 'ProjectTemplateContext'`

Missing contract: `ProjectTemplateContext` enum.  
Fix file: `ProjectTemplateContextSchemaPatcher.java`.

### Error: `Unknown field argument 'context' on viewer.projectTemplates`

Missing contract: optional `context` argument on existing upstream field.  
Fix file: `ProjectTemplateContextSchemaPatcher.java`.  
Do not redefine `projectTemplates` in `.graphqls`.

### Error: `Field 'allProjectTemplates' in type 'Viewer' is undefined`

Missing contract: `Viewer.allProjectTemplates`.  
Fix files: `syson-auth-compat.graphqls`/schema patcher + `ViewerAllProjectTemplatesDataFetcher.java`.

### Error: `Field 'natures' has coerced Null value for NonNull type '[String!]!'`

Missing request compatibility: newer frontend omitted older backend-required `natures`.  
Fix files: `ProjectTemplateContextSchemaPatcher.java` to relax non-null if needed; `auth.js` normalizer to inject `natures: []` for createProject requests.

### Error: `Field 'workbenchConfiguration' in type 'EditingContext' is undefined`

Missing contract: `EditingContext.workbenchConfiguration`.  
Fix files: `syson-auth-compat.graphqls` + `EditingContextWorkbenchConfigurationDataFetcher.java`.

### Error: raw translation keys visible

Missing contract: i18n namespace/key response.  
Fix files: `LocaleController.java` + `i18n/{en,fr}/{namespace}.json`; add flat dotted-key aliases where needed.

---

## 15. Version-skew root cause and long-term options

### 15.1 Why compatibility shims are currently preferred

A clean full-stack upgrade would mean aligning:

- Sirius Web backend Maven dependencies;
- SysON backend modules;
- prebuilt frontend JAR;
- frontend npm dependencies;
- custom Damuza auth/RBAC/history code;
- Docker build image and runtime.

This is non-trivial because Sirius Web artifacts are distributed through GitHub Packages and because the current system has live custom enterprise additions.

Therefore, the production-safe strategy is:

```text
preserve working Sirius editor/runtime
+ add narrow backend compatibility schema/fetchers
+ add minimal auth.js request normalization only where necessary
+ verify exact paths
```

This is a strangler/interceptor compatibility pattern, not a rewrite.

### 15.2 When to consider full realignment

A full dependency/frontend/backend realignment may be justified if:

- compatibility shims grow beyond a manageable surface;
- create/open/editor operations require complex semantic behavior, not just validation defaults;
- the frontend starts relying on data structures that cannot be safely stubbed;
- stable GitHub Packages access is available;
- a migration test suite exists for login, project browser, create project, editor open, diagram editing, save/history, and enterprise access.

Until then, preserve this ICD-driven compatibility layer.

---

## 16. Future-agent workflow

When assigned any SysON/Sirius UI issue:

1. Read `AGENTS.md`.
2. Read this ICD.
3. Read `SIRIUS_WEB_INTEGRATION_KB.md`.
4. Reproduce and capture exact frontend message:
   - GraphQL query/mutation body;
   - GraphQL JSON response including `errors`;
   - browser console if available;
   - docker logs if relevant.
5. Check if the error maps to section 14.
6. If known, apply documented fix pattern.
7. If unknown:
   - inspect frontend bundle for operation text;
   - inspect backend JAR `.graphqls` files;
   - add the minimum safe compatibility surface;
   - add data fetcher/defaults only if required;
   - verify.
8. Update this ICD and KB with the new message contract.
9. Do not claim success until the exact UI path works in browser.

---

## 17. Maintenance rules for this ICD

- Add every new frontend GraphQL field/type/argument/input drift discovered.
- Add exact error text and the file(s) that implement the fix.
- Add the expected response shape for every compatibility data fetcher.
- Keep stubs explicitly labeled as stubs/permissive compatibility values.
- Do not delete older contracts unless the frontend JAR has changed and regression tests prove they are obsolete.
- Keep this document referenced from `AGENTS.md`, `SIRIUS_WEB_INTEGRATION_KB.md`, and the Hermes `syson-development` skill.

---

## 18. Current authoritative compatibility surface snapshot

As of this ICD, the required compatibility surface is:

GraphQL Viewer fields:

- `language: String!`
- `namespaces: [String!]!`
- `capabilities: ViewerCapabilities!`
- `projectTemplates(page: Int!, limit: Int, context: ProjectTemplateContext)` via registry patch
- `allProjectTemplates: [ProjectTemplate!]!`

GraphQL Project fields:

- `capabilities: ProjectCapabilities!`

GraphQL input compatibility:

- `CreateProjectInput.templateId: ID`
- `CreateProjectInput.libraryIds: [String!]`
- `CreateProjectInput.natures` relaxed from non-null when required by upstream/backend drift

GraphQL EditingContext fields:

- `workbenchConfiguration: WorkbenchConfiguration!`

GraphQL enum/type compatibility:

- `ProjectTemplateContext`
- `ViewerCapabilities`
- `ViewerProjectsCapabilities`
- `ViewerLibrariesCapabilities`
- `ProjectCapabilities`
- `ProjectSettingsCapabilities`
- `WorkbenchConfiguration`
- `WorkbenchMainPanelConfiguration`
- `WorkbenchRepresentationEditorConfiguration`
- `WorkbenchSidePanelConfiguration`
- `ViewConfiguration`
- `DefaultViewConfiguration`

I18n endpoint:

- `GET /api/locales/{language}/{namespace}.json`
- 14 namespaces in `en` and `fr`
- region fallback and English fallback

Auth/interceptor:

- `/auth.js` direct-served by nginx
- fetch/XHR bearer-token injection
- login overlay with CSS root blocker
- reload after login
- createProject request normalizer for `natures: []`

This is the baseline contract future agents must preserve.

---

## Element Creation Mutations (added 2026-06-21)

### createChild — `CreateChildInput`

| Field | Required | Auto-discoverable | Description |
|---|---|---|---|
| `id` | ✅ | 🟢 Yes (UUID) | Client mutation ID |
| `editingContextId` | ✅ | 🟢 Yes | semantic_data UUID from `get_context()` |
| `objectId` | ✅ | 🔵 From catalog | Parent element's XMI id |
| `childCreationDescriptionId` | ✅ | 🔵 From tools query | Tool ID: `SysMLv2EditService-<Entity>` |

**Discovery query** for `childCreationDescriptionId`:
```graphql
childCreationDescriptions(kind: "siriusComponents://?domain=sysml&entity=Package") {
  id label
}
```
Returns 84 tools for Package, 55 for PartDefinition, etc.

**Payload**: `CreateChildSuccessPayload { object { id label kind } }` or `ErrorPayload { message }`

**Element kind** on created objects: `siriusComponents://semantic?domain=sysml&entity=<Entity>`

### createRootObject — `CreateRootObjectInput`

| Field | Required | Description |
|---|---|---|
| `id` | ✅ | Client mutation UUID |
| `editingContextId` | ✅ | semantic_data UUID |
| `documentId` | ✅ | Document UUID from `get_context()` |
| `domainId` | ✅ | Domain ID (e.g., `"sysmlv2"`) |
| `rootObjectCreationDescriptionId` | ✅ | From `rootObjectCreationDescriptions(domainId:, suggested:)` |
