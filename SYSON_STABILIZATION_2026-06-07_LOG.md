# SysON Stabilization Log — 2026-06-07

## Scope
User reports live production still lacks visible RBAC/admin dashboard/account/access management UI, and opening a project goes blank.

## Current status
- Started live reproduce + root-cause pass.
- Production container `syson` is up and serving HTTP 200 at `https://syson.damuza-consulting.com/` and localhost:8080.
- Git working tree has uncommitted GraphQL/i18n compatibility files that appear not deployed or incomplete.

## Evidence so far
- Docker logs show GraphQL validation errors:
  - `Unknown type 'ProjectTemplateContext'`
  - `Unknown field argument 'context'` on `viewer.projectTemplates`
  - `Field 'workbenchConfiguration' in type 'EditingContext' is undefined`
  - `Unknown type 'DefaultViewConfiguration'`
- These match the user's symptoms: dashboard/list startup and project workbench blank screens.

## Guardrails
- Do not refactor `auth.js` login boot path.
- Keep admin endpoints in `UserController` unless live mappings prove otherwise.
- Verify API responses by content type/body, not status only.
- Preserve `/api/**` JSON 401/403 handlers.

## Next steps
1. Capture browser console/network after admin login and project open.
2. Inspect current GraphQL compat schema/patchers and live packaged JAR.
3. Patch missing Sirius Web frontend/backend GraphQL compatibility.
4. Ensure admin/account/access UI is actually reachable/visible in `auth.js`.
5. Build/deploy and run regression scripts + browser screenshots.
