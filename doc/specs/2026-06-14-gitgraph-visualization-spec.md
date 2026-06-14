# GitGraph Visualization Specification

**Date:** 2026-06-14
**Component:** SysMLv2 Architect — Project Version Control (`auth.js`)
**Author:** Hermes Agent (adapting BowTie Pilot `VersionGraph.tsx` to plain DOM/SVG)

## 1. Goal

Replace the minimal linear commit-dot rendering in `showProjectVC()` with a
feature-rich, SVG-based commit graph inspired by BowTie Pilot's
`VersionGraph.tsx`. The graph must visualize branches as lanes, render commit
nodes (plain / merge / baseline / head), draw routed parent→child edges with
curves, show baseline diamonds and tag markers, provide hover tooltips,
density filtering, a dark/light theme toggle, and a click-to-diff action — all
as a **self-contained** addition to `auth.js`, without touching the login boot
path.

## 2. Constraints (from AGENTS.md)

1. Do NOT refactor the login boot path (`loadState`, `blockApp`, `showLogin`,
   `login`, `mountUserBar`, `logout`, `refreshToken`).
2. Do NOT replace `blockApp()`.
3. Add the GitGraph as a **self-contained** function.
4. Use `_origFetch` for all API calls.
5. Use **inline styles** only (no external CSS) for feature overlays.
6. After any change, run
   `cd /root/syson-fork && bash scripts/check-syson-login-regression.sh`.

The login overlay, root blocker, and token interceptor remain byte-for-byte
intact.

## 3. Data Flow

```
auth.js (browser)
   │  showProjectVC(projectId)
   ▼
_origFetch( GET /api/v1/projects/{projectId}/version-control/tree,
            Authorization: Bearer <jwt> )
   │
   ▼
VersionControlController.getVersionTree()
   └─ VersionGraphService.getVersionGraph(projectId)
        └─ returns VersionGraphData{ branches, commits, baselines, tags }
   │  HTTP 200, JSON body
   ▼
renderVCContent() → renderGitGraph(branches, commits, baselines, tags)
   └─ builds SVG commit graph string + interactivity
```

The tree endpoint is **already fetched** by the existing `showProjectVC()`
alongside `/version-control/overview` and `/settings/default-branch`. The
enhanced `renderGitGraph()` consumes the already-fetched `tree` payload; it
does **not** re-fetch. (A click on a commit lazily fetches that commit's diff
via the per-commit diff endpoint — see §11.)

### 3.1 Backend DTO shapes (JSON keys)

Returned by `GET /version-control/tree` as `VersionGraphData`:

```
{
  "branches":  BranchDto[],
  "commits":   CommitDto[],
  "baselines": BaselineDto[],
  "tags":      TagDto[]
}
```

**BranchDto** (from `org.eclipse.syson.vc.dto.BranchDto`):
`branchId, projectId, tenantId, name, branchType, headCommitId, baseCommitId,
parentBranchId, isProtected, isDeleted, createdAt, updatedAt, createdBy`

**CommitDto**:
`commitId, projectId, branchId, commitNumber, message, authorUserId,
changeCount, commitHash, parentCommitIds (comma-separated String!),
committedAt, source, status`

**BaselineDto**:
`baselineId, projectId, tenantId, baselineCode, name, commitId, status,
approvedBy, approvedAt, description, createdAt, createdBy`

**TagDto** (inner record of `VersionGraphService`):
`tagId, projectId, branchId, commitId, name, description, createdBy, createdAt`

> **Note on `parentCommitIds`:** the DTO serializes it as a **single
> comma-separated String**, not an array. The renderer must parse it with
> `String(s).split(',').map(trim).filter(Boolean)`. A commit with ≥2 parsed
> parents is a **merge commit**.

## 4. Rendering Algorithm (adapted from BowTie `VersionGraph.tsx`)

The graph is a vertical timeline: newest commit at top, oldest at bottom. Each
branch owns a horizontal **lane** (column). Commit nodes are drawn at the
intersection of their branch lane and their time row.

### 4.1 Lane assignment

- Branches are sorted by `name` with `main`/`master` first, then alphabetical,
  so the trunk stays in lane 0.
- `laneIndex = sortPosition` (0-based). `laneX(lane) = GRAPH_LEFT + lane *
  LANE_GAP` where `GRAPH_LEFT = 24`, `LANE_GAP = 30`.
- The backend `VersionGraphService` does **not** pre-compute `laneIndex`, so the
  renderer assigns lanes client-side (mirrors BowTie's `safeBranches` sort).

### 4.2 Row ordering

- Commits are sorted newest-first by `committedAt` (fallback
  `commitNumber` desc), matching the existing behaviour.

### 4.3 Commit dot rendering (color by branch, merge doubled)

| Commit kind        | Shape     | Render                                                                 |
|--------------------|-----------|------------------------------------------------------------------------|
| Plain              | circle    | `r=5`, white fill, branch-color stroke `2.1`                           |
| Head (branch tip)  | circle    | `r=6.5`, white fill, branch-color stroke `2.7`, glow filter            |
| Baseline           | circle    | `r=7.5`, **branch-color fill**, white center dot, red ring + glow      |
| Merge (≥2 parents) | diamond   | rotated square `rx=2`, white fill, branch-color stroke `2.4`, glow     |

Color resolution: named branch-type palette (`main→#3b82f6`,
`release→#ef4444`, `feature→#22c55e`, …) falling back to a rotating
`PALETTE` by lane index.

### 4.4 Edge routing (parent → child curves)

For each commit, for each parsed parent id:
- If the parent is in the visible set, draw a curve from the child node to the
  parent node.
- If the parent is absent from the visible set (filtered out by density), draw
  a **ghost edge** to an inferred position below the child (dashed, low alpha)
  so lineage is still hinted.
- Merge edges (parent index > 0) use the **parent's** color and a thicker
  stroke; first-parent edges use the child's color.
- Path uses the BowTie `routePath` cubic Bezier:
  - same lane → straight line;
  - different lane → `C` curve with a vertical bend of
    `clamp(8, |Δy|/3, 18)`.

### 4.5 Lane rails

For each branch, a translucent vertical bar (stroke `4`, alpha `0.42`) spans
from its first visible commit to its last visible commit, giving each lane a
visible "rail" colour.

### 4.6 Baseline markers (diamonds with labels)

A baseline anchored to a commit is rendered as a small **diamond polygon** to
the right of the node plus a pill label `baselineCode || name`. The commit node
itself is also styled as a baseline (filled, red ring). A baseline pill is
rendered with red tint (`rgba(239,68,68,.14)` fill, `rgba(239,68,68,.62)`
stroke).

### 4.7 Tag markers

Tags anchored to a commit render as a green pill `🏷 name` after any baseline
pill, using `rgba(5,150,105,.14)` / `rgba(5,150,105,.6)`.

### 4.8 Hover tooltips

Two layers:
1. **Native `<title>`** element inside each commit `<g>` (zero-JS, always
   available): `branchName · #N · message · date`.
2. **Custom HTML tooltip** (richer): on `mouseover`/`focusin` of a commit
   node, a positioned `<div>` shows branch name, commit number, merge/baseline
   badges, full message, short hash, author (short), and formatted timestamp.
   Hidden on `mouseout`/`focusout`.

### 4.9 Dark/light theme

- Default: **dark** (matches the existing `#0f172a` VC overlay panel and the
  SysMLv2 Architect brand dark surface). The brand accent **#261e58**
  (daintree) is used for header gradients and primary controls.
- Light mode uses a white/slate palette for readability.
- A segmented Light/Dark toggle in the graph header re-renders the SVG.
- Theme tokens mirror BowTie's `GRAPH_THEMES` (bg, panel, text, textMuted,
  rowEven/Odd, guide, baselineText, …), ported to plain strings.

### 4.10 Density modes

A segmented control with three modes (mirrors BowTie `DensityMode`):

| Mode         | Visible commits                                              |
|--------------|--------------------------------------------------------------|
| `baselines`  | baselines, branch heads, and root commits only (sparsest)    |
| `standard`   | above + merge commits (default)                              |
| `full`       | every commit                                                 |

Switching re-renders the SVG (edges re-routed against the new visible set).

### 4.11 Click handler — show commit diff

Clicking a commit node fetches and displays its diff:
```
_origFetch( GET /api/v1/projects/{projectId}/branches/{branchId}/commits/{commitId}/diff )
   → List<ChangeDto>
```
A diff panel appears below the graph listing each change: `#seq · operation ·
objectType · objectId(short) · patch(truncated)`. The panel is collapsible.

## 5. Layout & Responsiveness

- The SVG sits in a horizontally-scrollable container (`overflow-x:auto`,
  `max-height: 60vh`, vertical scroll).
- SVG `width` = `GRAPH_LEFT + (maxLane+1)*LANE_GAP + MESSAGE_GAP +
  MESSAGE_WIDTH`; `height` = `PAD_TOP + visibleCount*rowH + PAD_BOTTOM`.
- `rowH` = 34 (standard/baselines) or 38 (full density).
- Message text, short hash, and date are rendered as SVG `<text>` in a fixed
  right-hand column, monospaced.
- The overlay itself is `width: min(1200px, 96vw); max-height: 90vh`.

## 6. Accessibility

- The SVG has `role="img"` and an `aria-label` summarising counts.
- Each commit `<g>` is `tabindex="0"` with `role="button"` and
  `aria-label="commit {shortHash}: {message}"`.
- Keyboard: `focusin`/`focusout` toggle the tooltip; `Enter`/`Space` triggers
  the diff (in addition to click).
- Theme/density toggles are real `<button>`s with `aria-pressed`.
- Colour is never the sole signal: shapes differ (circle vs diamond), and
  text labels accompany every marker.

## 7. Implementation Plan in `auth.js`

All additions are **inside the existing IIFE**, as self-contained functions:

1. `_vcParseParents(s)` — parse the comma-separated `parentCommitIds`.
2. Module-level `_gitGraphData` — holds the last-rendered graph payload so
   density/theme toggles can re-render without re-fetching.
3. **Enhance** `renderGitGraph(branches, commits, baselines, tags)` to emit
   the rich SVG string (rails, routed edges, node kinds, baseline diamonds,
   tag pills, message/hash/date columns, `<title>` tooltips, branch header
   labels). Density/theme are read from closure state.
4. `renderVCContent` is updated to (a) pass `tags`, (b) emit a graph header
   bar with density + theme controls and a diff panel mount point, and (c)
   call `initGitGraph(projectId, branches, commits, baselines, tags)` after
   `innerHTML` is set.
5. `initGitGraph(...)` — self-contained wiring: stores payload in
   `_gitGraphData`, attaches delegated `mouseover/mouseout/click` handlers on
   the SVG for tooltips + diff, and wires the density/theme buttons to
   re-render the SVG into its container.
6. `showGitGraphDiff(projectId, branchId, commitId)` — fetches the diff via
   `_origFetch` and renders the change list into the diff panel.

No existing login/interceptor code is modified. The only existing functions
touched are `renderGitGraph` (body enhanced, signature gains `tags`) and
`renderVCContent` (graph section + one init call).

## 8. Verification

1. `node -c frontend/syson/public/auth.js` — syntax.
2. `cp frontend/syson/public/auth.js /var/www/syson/auth.js && systemctl reload nginx`.
3. `bash scripts/check-syson-login-regression.sh` — login overlay, root
   blocker, auth API intact.
4. Functional: open Version Control for a project with history and confirm
   lanes, edges, baseline diamonds, tag pills, tooltips, density/theme
   toggles, and click-to-diff all work. With no data, the "No commits yet"
   empty state must still render.
