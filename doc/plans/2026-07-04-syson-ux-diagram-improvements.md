# SysON UX Diagram Improvements — Merge Conflict Resolution Guide

> **Branch:** `ux-diagram-improvements`
> **Date:** July 2026
> **Baseline:** Commit `168d9351a` on `rbac` branch (upstream-aligned: `540ea78c`)

## Changes Summary

### 1. Color-Coded Edges by Relationship Type

**Files affected:**
- `backend/services/syson-services/src/main/java/org/eclipse/syson/util/EdgeColorPalette.java` (**NEW**)
- `backend/services/syson-services/src/main/java/org/eclipse/syson/util/ViewConstants.java`
- All `*EdgeDescriptionProvider.java` in `backend/views/`

**What changed:**
- Each SysMLv2 relationship type now has a distinct color (Subsetting=blue, Dependency=brown, FeatureTyping=purple, etc.)
- Edge width increased from 1px to 2px globally
- Added stereotype labels («subset», «type», «allocate», etc.) to edges that didn't have labels

**KerML/SysMLv2 compliance:** Visual-only change. No metamodel modification. All colors and labels are purely presentational.

**Merge conflict risk: LOW**
- `EdgeColorPalette.java` is a NEW file — no upstream conflicts possible
- Edge provider changes are in SysON-specific `createEdgeStyle()` methods
- If upstream adds new edge providers, they will still use `ViewConstants.DEFAULT_EDGE_COLOR` (not broken, just not color-coded — can be updated later)

**Resolution if conflicts:**
1. If upstream modifies an edge provider's `create()` or `link()` method: merge normally, our `createEdgeStyle()` change stays
2. If upstream adds a new edge type: add its color to `EdgeColorPalette.java` and update its `createEdgeStyle()`

### 2. Tabbed Property Pages (Details View)

**File affected:**
- `backend/application/syson-application-configuration/src/main/java/org/eclipse/syson/application/configuration/SysMLv2PropertiesConfigurer.java`

**What changed:**
- Reorganized from 2 pages (Core + Advanced) to 3 semantic tabs:
  - **Identity** — core properties, visibility, feature values, comments
  - **Relationships** — redefinition, subsetting, typing, subclassification, transitions
  - **Advanced** — all other structural features

**KerML/SysMLv2 compliance:** No change. Same EMF structural features displayed, just reorganized.

**Merge conflict risk: MEDIUM**
- `SysMLv2PropertiesConfigurer.java` references `CoreFeaturesSwitch` which is upstream
- If upstream changes the metamodel (adds/removes EStructuralFeatures), the switch may need updates

**Resolution if conflicts:**
- If upstream changes `createDetailsView()` layout: keep our 3-page layout, incorporate new groups
- If upstream adds new feature groups: add them to the appropriate page (Identity/Relationships/Advanced)
- If `CoreFeaturesSwitch` changes: verify all referenced features still exist

### 3. Edge Hover Tooltips (Frontend — Prepared)

**File affected:**
- `frontend/syson-components/src/edges/SysMLEdgeTooltipWrapper.tsx` (**NEW** — not yet integrated)

**What changed:**
- Created a ReactFlow custom edge component that shows relationship type tooltips on hover
- Needs integration into the Sirius Web `DiagramRepresentationConfiguration` (requires node_modules)

**Merge conflict risk: NONE** (new file, not yet wired)

---

## Conflict Resolution Order (if merging upstream)

1. Merge upstream changes first
2. Run `mvn -pl backend/application/syson-application -DskipTests compile -o`
3. Fix any compilation errors in this order:
   a. Check `EdgeColorPalette.java` still compiles (no conflicts expected)
   b. Check edge providers — if new providers exist, add palette colors
   c. Check `SysMLv2PropertiesConfigurer.java` — if layout changed, preserve 3-tab layout
4. Run full build: `mvn -pl backend/application/syson-application -DskipTests package -o`
5. Build Docker and deploy per `SYSON_STABILIZATION_GUIDE.md`

## Known Limitations / Future Work

1. **Edge tooltips** — component created but not yet integrated (needs node_modules and build integration)
2. **Inline type creation** — requires changes to `@eclipse-sirius/sirius-components-widget-reference` (upstream), not implemented
3. **Type hierarchy visualizer** — new representation type needed, not implemented
4. **Quick-add tools** — requires AQL service support in `DetailsViewService`, not implemented
5. **`packages/reqif/package.json`** — non-critical `"type": "module"` warning in RM repo, unrelated

### 4. Edge Routing Improvements (Connector Drawing)

**Files affected:**
- `frontend/syson/src/extensions/SysONEdgeRouting.ts` (**NEW**)
- `frontend/syson/src/index.tsx` (import added)

**What changed:**
- Ported connector drawing algorithms from SysMLDiagramTool:
  - `getEdgePoint()` — edge clipping to symbol boundaries (stops at box edge, not center)
  - `computeBezierControlPoint()` — perpendicular-offset bezier for smooth curves
    (cpX = (sx+ex)/2 - dy*0.2, cpY = (sy+ey)/2 + dx*0.2)
  - `RELATIONSHIP_ROUTING` map — assigns ReactFlow routing per relationship type:
    - Transitions/Succession → `bezier` (smooth curves like SysMLDiagramTool)
    - Structural (Subsetting, Dependency, etc.) → `smoothstep` (orthogonal with curved corners)
    - Flow/Value → `straight`
- Runtime ReactFlow injection: patches `defaultEdgeOptions.type = 'smoothstep'` and
  increases `interactionWidth` to 20px for easier clicking
- All algorithms are visual-only — no KerML/SysMLv2 metamodel change

**Merge conflict risk: LOW**
- `SysONEdgeRouting.ts` is a NEW file — no upstream conflict possible
- `index.tsx` import is additive (side-effect import)
- Runtime injection uses `setInterval` with timeout to avoid blocking if ReactFlow not yet loaded

## Verification

```bash
cd /root/syson-fork
mvn -pl backend/application/syson-application -DskipTests -Dcheckstyle.skip=true package -o
# Expected: BUILD SUCCESS
```
