# Baseline, Diff, and Selective Merge Specification

## Overview

Implements three interconnected capabilities for SysON's version control system,
inspired by bowtie-pilot's element-level approach but improving on its limitations
(no selective merge, dead conflict resolution).

## 1. Auto-Baseline on Branch Creation

### Rule
When a branch B is created from parent branch P:
- Capture P's current canonical snapshot (from `syson_branch_heads.canonical_json`)
- Store it as a baseline with:
  - `name`: "Branch point: {B.name}"
  - `status`: "draft"
  - `commit_id`: P's head commit at creation time
  - `branch_id`: B's branch_id (NEW column on syson_baselines)
  - `baseline_code`: "BL-{branchName}-{YYYY-MM-DD}"
- Also freeze the canonical JSON into `syson_model_snapshots` for fast reconstruction

### Schema Change
```sql
ALTER TABLE syson_baselines ADD COLUMN IF NOT EXISTS branch_id UUID;
ALTER TABLE syson_baselines ADD COLUMN IF NOT EXISTS canonical_snapshot JSONB;
```

### API
No new endpoint — fires automatically inside `VersionControlService.createBranch()`.
Baselines are queryable via existing `GET /branches/{branchId}/baselines`.

## 2. Element-Level Diff Engine (`BranchDiffService`)

### Design
Inspired by bowtie-pilot's `canonical-diff.ts` but operating on SysON's canonical JSON
shape (elements keyed by stableId, relationships keyed by stableId).

### Diff Algorithm
```
diffCanonicalJson(baseJson, targetJson) → DiffResult
```

1. **Index both snapshots** into typed maps:
   - `elements`: Map<stableId, CanonicalElement>
   - `relationships`: Map<stableId, CanonicalRelationship>

2. **For each object type**, produce `DiffEntry` records:
   - **added**: id in target but not base → `{kind:'added', afterObject}`
   - **removed**: id in base but not target → `{kind:'removed', beforeObject}`
   - **modified**: id in both → `deepDiff(before, after)` → `{kind:'modified', patch:{field:newVal}}`
     - Only emit if deepDiff is non-empty (field-level comparison using JSON canonicalization)

3. **Sort**: by objectType (element→relationship), then kind (added→modified→removed), then name

### DiffResult Shape
```json
{
  "summary": { "added": N, "modified": N, "removed": N, "unchanged": N },
  "entries": [
    {
      "objectType": "element|relationship",
      "objectId": "stableId",
      "objectName": "Package1::PartA",
      "kind": "added|modified|removed",
      "patch": { "field": "newValue" },
      "beforeObject": { ... },
      "afterObject": { ... }
    }
  ]
}
```

### Diff Comparison Modes
The diff engine supports two comparison contexts via the API:

| Mode | base | target | Purpose |
|------|------|--------|---------|
| **vs-branch-point** | baseline snapshot at branch creation | current branch HEAD | "What have I changed since branching?" |
| **vs-parent-latest** | parent branch HEAD | current branch HEAD | "What's the merge delta?" |

## 3. Selective Merge

### Design
Improves on bowtie-pilot (which does wholesale cache copy with no element selection).

### Merge Request Lifecycle
```
open → approved → merged
open → conflicted → (resolve conflicts) → approved → merged
open/approved → closed
```

### Create Merge Request
`POST /api/v1/projects/{projectId}/merge-requests`
```json
{
  "sourceBranchId": "uuid",
  "targetBranchId": "uuid",
  "title": "Merge feature-x into main"
}
```
**Side effects:**
1. Compute diff of source vs target HEAD
2. Auto-detect conflicts (objects modified on both branches since branch point)
3. Insert `syson_merge_conflicts` rows for conflicting objects
4. Set status to `open` or `conflicted`

### Preview Merge Diff
`GET /api/v1/projects/{projectId}/merge-requests/{mrId}/diff`
Returns `DiffResult` showing what the merge would change on the target.

### Selective Merge
`POST /api/v1/projects/{projectId}/merge-requests/{mrId}/merge`
```json
{
  "selectedObjectIds": ["stableId1", "stableId2", ...],
  "message": "Merged selected elements from feature-x"
}
```
**Process:**
1. Load source HEAD canonical JSON
2. Load target HEAD canonical JSON
3. For each selected object ID:
   - If `added` in source: insert into target canonical
   - If `modified` in source: apply patch to target
   - If `removed` in source: mark as deleted in target
4. Write updated canonical JSON to target's `syson_branch_heads`
5. Project target's updated `siriusDocuments` into `document.content`
6. Dispose Sirius editing context for the target project
7. Create merge commit (with `parent_commit_ids = [source_head, target_head]`)
8. Update merge request status to `merged`

### Conflict Resolution
`POST /api/v1/projects/{projectId}/merge-requests/{mrId}/conflicts/{conflictId}/resolve`
```json
{
  "resolution": "use_source|use_target|manual",
  "resolvedValue": { ... }
}
```

## 4. API Endpoints Summary

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/projects/{pid}/branches/{bid}/diff?mode=vs-branch-point` | Diff branch vs its creation baseline |
| GET | `/projects/{pid}/branches/{bid}/diff?mode=vs-parent-latest` | Diff branch vs parent's latest HEAD |
| GET | `/projects/{pid}/branches/{bid}/diff?baseBranchId={uuid}` | Diff branch vs arbitrary branch |
| POST | `/projects/{pid}/merge-requests` | Create merge request |
| GET | `/projects/{pid}/merge-requests/{mrId}/diff` | Preview merge diff |
| POST | `/projects/{pid}/merge-requests/{mrId}/merge` | Execute selective merge |
| POST | `/projects/{pid}/merge-requests/{mrId}/conflicts/{cid}/resolve` | Resolve conflict |
| GET | `/projects/{pid}/branches/{bid}/baselines` | List baselines (existing) |
| GET | `/projects/{pid}/baselines/{baselineId}/model` | Reconstruct model at baseline |

## 5. Frontend UI (auth.js)

### Diff Viewer
- Modal overlay triggered from branch control panel
- Two-column side-by-side: before (left) vs after (right)
- Color-coded: green=added, yellow=modified, red=removed
- Filterable by object type and kind
- Summary header: "5 added, 3 modified, 2 removed"

### Merge Wizard
- Step 1: Select source → target branches
- Step 2: Review diff (reuse DiffViewer)
- Step 3: Select elements to merge (checkboxes per diff entry)
- Step 4: Resolve conflicts (if any) — radio: use source / use target
- Step 5: Confirm and merge

## 6. Testing Strategy

### Unit Tests
- `BranchDiffServiceTest`: diff engine correctness
  - Empty diff (identical snapshots)
  - Added elements
  - Removed elements
  - Modified elements (field-level patch)
  - Mixed scenarios
- `BaselineCreationTest`: auto-baseline on branch creation
- `SelectiveMergeTest`: selective merge with subset selection

### Integration Tests
- Full create-branch → make-changes → diff → merge cycle
- Conflict detection and resolution
- Sirius editing context disposal after merge
