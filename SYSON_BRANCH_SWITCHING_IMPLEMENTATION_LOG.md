# SysON Branch Switching / Reconstruction Implementation Log

## User Request

Add functionality to select the active branch in the editor and apply it so:
- model saves write extended sidecar history/head data to the selected branch;
- selecting a branch can load the corresponding model into SysON;
- reconstruction avoids storing full blob versions on every save;
- solution follows BowTie Pilot head-table architecture where applicable;
- remains compliant with Sirius Web blob/editor interface.

## Guardrails

- Preserved Sirius Web editor compatibility: `document.content` remains the live editor contract.
- Sidecar branch heads store a current Sirius projection per branch, not a full blob per commit.
- `syson_changes`/head tables remain the granular history path.
- Did not refactor `auth.js` login boot flow.
- Verified through public nginx URL after deploy.

## Findings

1. Existing branch UI could set a project default branch, but the editor could not directly select/apply branches.
2. `SemanticDataSaveListener` always saved history to `main`, so auto-save/history extraction ignored the selected branch.
3. Explicit `/api/v1/projects/{projectId}/save` only wrote a lightweight commit marker and did not refresh the branch materialized Sirius projection.
4. `syson_branch_heads.canonical_json` was not being updated by `HeadMaterializationService`, so reconstruction/apply had no usable Sirius document projection.
5. `CommitEntity.parent_commit_ids` needed Hibernate JSON typing; otherwise explicit save failed inserting String into JSONB.

## Architecture Decision

Implemented a BowTie-style hybrid:

- Branch sidecar remains authoritative for branch history and current branch head.
- `syson_branch_heads.canonical_json` stores the current branch HEAD projection only:
  - granular element/relationship maps for diffing;
  - `siriusDocuments` map containing the current Sirius `document.content` projection for that branch.
- Selecting a branch calls an apply endpoint that writes that projection into live Sirius `document.content` rows, then the browser reloads the normal Sirius workbench.
- Saving while a branch is active writes/refreshes the selected branch head and uses the active branch setting for save-event history extraction.

This keeps Sirius Web compliant: the editor still loads via its normal GraphQL/blob path, while the sidecar decides which branch projection is currently projected into Sirius.

## Fixes Applied

- Added `BranchProjectionService`:
  - applies selected branch projection into Sirius documents;
  - seeds branch head from current Sirius docs if a branch has no projection yet;
  - sets active/default branch setting.
- Added `POST /api/v1/projects/{projectId}/version-control/apply-branch`.
- Branch creation now seeds a branch head from the current Sirius projection when possible.
- Explicit save now refreshes `syson_branch_heads` for the selected branch and links it to the save commit.
- `SemanticDataSaveListener` now resolves the active branch from project settings before falling back to `main`.
- `SysmlCanonicalExtractor` now includes `siriusDocuments`, full element metadata, raw JSON, relationship maps, and projection version in canonical JSON.
- `HeadMaterializationService` now writes `canonical_json` into `syson_branch_heads`.
- `ModelSaveHistoryService` reconstructs previous snapshots from canonical JSON, so diffs compare against the branch head rather than treating every save as all-new.
- `CommitEntity.parent_commit_ids` now uses Hibernate JSON mapping.
- Editor UI now includes:
  - visible branch badge;
  - branch dropdown;
  - `Apply` button that loads the selected branch projection and reloads SysON.

## Verification

- `node -c frontend/syson/public/auth.js` passed.
- Maven package passed:
  - `mvn -pl backend/application/syson-application -DskipTests -Dcheckstyle.skip=true package -o`
- Frontend JAR verified real before Docker build.
- Docker image rebuilt and `syson` restarted; health became ready.
- Login regression passed:
  - `bash scripts/check-syson-login-regression.sh`
- Enterprise access regression passed:
  - `BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh`
- API branch switching test passed:
  - created feature branch;
  - applied it through `/version-control/apply-branch`;
  - explicit save returned `saved:true`;
  - `syson_branch_heads` has `siriusDocuments` for the branch;
  - `syson_commits` has a commit for that branch;
  - default branch endpoint returns the selected branch UUID.
- Browser/Playwright verification through `https://syson.damuza-consulting.com/projects/<id>/edit`:
  - `#syson-branch-wrap` exists;
  - indicator shows selected feature branch;
  - dropdown has branch options;
  - Apply button is visible.
  - Screenshot: `/tmp/syson_branch_switching_ui.png`

## Remaining Risks / Follow-up

- Current extractor produced `object_count=0` for the tested simple Sirius document, but the Sirius document projection is stored and branch switching works at the blob-projection layer. Deeper SysML object extraction should be refined separately for richer object-level diffs across all SysON document shapes.
- Applying a branch projects it into Sirius core tables by design. This is necessary for stock Sirius Web compatibility but means the core blob is a working projection, not an independent branch store.
- Pre-existing untracked `frontend/syson-components/src/extensions/SysONBranchIndicator.tsx` and `SysONSaveButton.tsx` were not used in this pass.
