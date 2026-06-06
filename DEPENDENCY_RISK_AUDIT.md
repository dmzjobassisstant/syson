# SysON Dependency Risk Audit
## Date: 2026-06-06 | Repository: /root/syson-fork | Version: 2026.5.0

---

## 1. SIRIUS WEB (Core Dependency) — RISK: **HIGH**

### Evidence Files
- `/root/syson-fork/pom.xml` (line 35, 50-52, 40-52): declares `sirius.web.version=2026.5.0` and repositories
- `/root/syson-fork/settings.xml` (lines 20-24, 39-44): GitHub Packages auth via env vars
- `/root/syson-fork/backend/application/syson-application/pom.xml` (line 49-51): depends on `sirius-web-starter`
- `/root/syson-fork/frontend/syson/package.json` (lines 16-36): 21 `@eclipse-sirius` packages

### Findings
| Factor | Status |
|--------|--------|
| **License** | EPL-2.0 (open source) ✓ |
| **Activity** | Active: 129 stars, 84 forks, 956 open issues, last commit 2026-06-04 |
| **Latest release** | v2026.5.0 (May 20, 2026) |
| **Sole maintainer** | **YES** — Obeo dominates: sbegaudeau (1167 commits), pcdavid (728), frouene (504), mcharfadi (343), gcoutable (322) — all Obeo employees |
| **On Maven Central** | **NO** — `sirius-web-starter`, `sirius-components-emf`, etc. return 404 on Maven Central |
| **On npmjs.com** | **NO** — all `@eclipse-sirius/*` packages return 404 on npmjs.com |
| **Actual registry** | GitHub Packages (`maven.pkg.github.com/eclipse-sirius/sirius-web` and `npm.pkg.github.com/eclipse-sirius`) |
| **Auth required** | **YES** — `settings.xml` needs `USERNAME`/`PASSWORD` env vars (GitHub PAT with `read:packages`) |

### Risk Assessment: **HIGH**
SysON cannot be built without Sirius Web, and Sirius Web is **only available via GitHub Packages** (not Maven Central or npmjs.com). This means:
- **Any third-party fork is dependent on Obeo continuing to publish** to GitHub Packages
- If Obeo stops publishing (project ends, moves to private hosting, license change), syson-fork **cannot be built from scratch**
- A GitHub Personal Access Token with `read:packages` scope is mandatory
- All 21 frontend packages and multiple backend JARs (~dozen+) are pinned to the exact same version as syson (2026.5.0) — tightly coupled release trains

---

## 2. CLOSED-SOURCE / PROPRIETARY DEPENDENCIES — RISK: **MEDIUM**

### Evidence Files
- `/root/syson-fork/.npmrc` (lines 1-2): scoped registries `@eclipse-sirius` and `@ObeoNetwork` → GitHub Packages
- `/root/syson-fork/package-lock.json` (lines 2507-2576): resolved URLs confirm GitHub Packages
- `/root/syson-fork/frontend/syson/package.json` (lines 43-44): `@ObeoNetwork/gantt-task-react`, `@ObeoNetwork/react-trello`

### Findings
No truly closed-source dependencies were found (no enterprise editions, obfuscated JARs, commercial license plugins). However:

| Package | Risk | Details |
|---------|------|---------|
| `@ObeoNetwork/gantt-task-react` 0.6.4 | **MEDIUM** | Obeo fork (1 star), NOT on npmjs.com. Hosted only on `npm.pkg.github.com/ObeoNetwork`. Last updated May 2026 — currently maintained but single-vendor. |
| `@ObeoNetwork/react-trello` 2.4.11 | **HIGH** | Obeo fork (**0 stars**), NOT on npmjs.com. Last updated **December 2023** (2.5 years stale). Only on GitHub Packages. |
| `sirius-emf-json` (transitive via Sirius Web) | **MEDIUM** | Separate GitHub Packages repo (`eclipse-sirius/sirius-emf-json`), also Obeo-maintained, also only on GitHub Packages. |

### Risk Assessment: **MEDIUM**
While no truly proprietary code exists, the `@ObeoNetwork` packages serve the same practical function: **single-vendor, not on public registries, no community governance**. `react-trello` (kanban board) has been effectively abandoned for 2.5 years.

---

## 3. DISCONTINUED / AT-RISK PROJECTS — RISK: **MEDIUM**

### Evidence Files
- `/root/syson-fork/frontend/syson/package.json` (line 69)
- `/root/syson-fork/package-lock.json` (resolved URLs for all packages)
- `/root/syson-fork/CHANGELOG.adoc` (dependency update history)

### Findings

| Dependency | Version | Status | Impact |
|------------|---------|--------|--------|
| `subscriptions-transport-ws` | 0.11.0 | **DEPRECATED** — npm says "no longer maintained, use graphql-ws" | Runtime dependency for GraphQL WebSocket subscriptions |
| `react-trello` (ObeoNetwork fork) | 2.4.11 | **Unmaintained** — last updated Dec 2023 | Kanban/Gantt UI widget |
| `fontsource-roboto` | 4.0.0 | **Renamed** — current package is `@fontsource/roboto` 5.2.10 | Font loading |
| `material-react-table` | 3.2.1 | **Stale major version** — ecosystem has moved on | Table UI component |

### Risk Assessment: **MEDIUM**
- `subscriptions-transport-ws` is a runtime dependency for a core feature (real-time collaboration via GraphQL subscriptions). Being deprecated means no security patches.
- `react-trello` being 2.5 years unmaintained is concerning but only affects the kanban board feature.
- The other issues are minor but indicate bitrot in the dependency tree.

---

## 4. BUILD FROM SOURCE VIABILITY — RISK: **HIGH**

### Evidence Files
- `/root/syson-fork/settings.xml` (full file): requires `USERNAME`/`PASSWORD` env vars
- `/root/syson-fork/.npmrc` (full file): points to GitHub Packages for scoped packages
- `/root/syson-fork/pom.xml` (lines 40-52): repositories section
- `/root/syson-fork/pom.xml` (lines 254-262): pluginRepository for `dash-licenses-snapshots`

### Findings
| Factor | Status |
|--------|--------|
| **Public Maven Central** | Only Spring Boot + standard libs (commons-io, commons-lang3, antlr4) are on Maven Central. **All Sirius Web artifacts are NOT.** |
| **Public npm registry** | Only generic npm packages are on npmjs.com. **All @eclipse-sirius and @ObeoNetwork packages are NOT.** |
| **Authentication required** | **YES** — GitHub PAT with `read:packages` scope in `USERNAME`/`PASSWORD` env vars |
| **Private repos needed** | 0 private repos, but 3 GitHub Packages repos that require auth: `eclipse-sirius/sirius-web`, `eclipse-sirius/sirius-emf-json`, `ObeoNetwork/*` |
| **Build tools** | Maven 3.6.3+, Java 21, Node 22.16.0, npm 10.9.2 — all standard |
| **Special access** | Eclipse `dash-licenses-snapshots` plugin repo (for IP log verification, not needed for normal builds) |

### Can you build from source today?
**YES, but only with a GitHub PAT.** Without the PAT:
- `mvn compile` fails (cannot resolve `org.eclipse.sirius:sirius-web-starter`)
- `npm install` fails (cannot resolve `@eclipse-sirius/sirius-web-application`)

### Risk Assessment: **HIGH**
If GitHub Packages goes down, GitHub changes its auth model, or Obeo stops publishing artifacts, the entire build pipeline breaks. This is a single point of failure.

---

## 5. FRONTEND DEPENDENCIES — RISK: **MEDIUM**

### Evidence Files
- `/root/syson-fork/frontend/syson/package.json` (lines 14-91): all dependencies
- `/root/syson-fork/frontend/syson-components/package.json` (lines 32-149): peer + dev deps
- `/root/syson-fork/package-lock.json` (resolved URLs confirm GitHub Packages for all @eclipse-sirius and @ObeoNetwork)

### Dependency Summary
| Category | Count | Registry | Risk |
|----------|-------|----------|------|
| `@eclipse-sirius/*` | 21 packages | GitHub Packages only | HIGH |
| `@ObeoNetwork/*` | 2 packages | GitHub Packages only | HIGH |
| Deprecated (`subscriptions-transport-ws`) | 1 | npmjs (deprecated) | MEDIUM |
| Standard npm packages (~30) | ~30 | npmjs.com | LOW |

### Key npm packages on public registry (healthy):
React 18.3.1, GraphQL 16.8.1, Apollo Client 3.10.4, MUI 7.3.10, @xyflow/react 12.6.0, d3 7.0.0, elkjs 0.11.0, lexical 0.42.0 — all healthy and actively maintained.

### Risk Assessment: **MEDIUM**
The heavy dependency on 23 GitHub-Packages-only packages means any new contributor needs both a GitHub PAT and knowledge of how to configure `.npmrc` for scoped registries. The deprecated `subscriptions-transport-ws` is a technical debt item.

---

## 6. STANDARD LIBRARIES (kerml.libraries, sysml.libraries) — RISK: **LOW**

### Evidence Files
- `/root/syson-fork/NOTICE` (lines 24-28): LGPL-3.0 license, path declaration
- `/root/syson-fork/LICENSE-LGPL`: full LGPL-3.0 text
- `/root/syson-fork/backend/application/syson-application-configuration/src/main/resources/kerml.libraries/` (30+ JSON files)
- `/root/syson-fork/backend/application/syson-application-configuration/src/main/resources/sysml.libraries/` (50+ JSON files)
- `/root/syson-fork/backend/application/syson-application-configuration/src/main/java/org/eclipse/syson/application/configuration/SysONDefaultLibrariesConfiguration.java`: loading code (EPL-2.0)

### Findings
| Factor | Status |
|--------|--------|
| **License** | LGPL-3.0 (NOTICE explicitly states this) — compatible with EPL-2.0 for distribution |
| **Included in repo** | YES — all JSON files are committed in source tree |
| **External download** | NO — loaded from classpath resources |
| **Loading code** | EPL-2.0, fully open source, can be customized via `getDefaultLibraries()` override |
| **Origin** | SysML v2 standard library specification |
| **Replaceability** | Can likely be regenerated from SysML v2 specification tools |
| **Risk if lost** | Libraries are completely self-contained in the repo — no external dependency |

### Risk Assessment: **LOW**
The libraries are fully self-contained in the repository as JSON files with clear LGPL-3.0 licensing. The loading mechanism is open source and extensible. If the upstream specification changes, new library versions could be regenerated.

---

## RISK SUMMARY

| # | Area | Risk | Critical Finding |
|---|------|------|------------------|
| 1 | **Sirius Web** | **HIGH** 🔴 | Sole Obeo vendor, NOT on Maven Central/npmjs, GitHub Packages only with PAT auth |
| 2 | **Closed-source** | MEDIUM 🟡 | No truly proprietary code, but ObeoNetwork packages are single-vendor, not on npmjs |
| 3 | **Discontinued projects** | MEDIUM 🟡 | `subscriptions-transport-ws` deprecated, `react-trello` 2.5 years stale |
| 4 | **Build from source** | **HIGH** 🔴 | Cannot build without GitHub PAT; all Sirius Web deps are GitHub-Packages-only |
| 5 | **Frontend** | MEDIUM 🟡 | 23 of ~55 packages require GitHub Packages auth; deprecated WS library |
| 6 | **Standard libraries** | LOW 🟢 | LGPL-3.0, fully self-contained in repo, no external download needed |

### OVERALL RISK FOR LONG-LIVED DERIVATIVE: **HIGH** 🔴

The single biggest risk by far is the **Sirius Web dependency being exclusively available via GitHub Packages with a required Personal Access Token**. If Obeo stops publishing there, or if a downstream fork needs to rebuild without access to GitHub Packages, the project cannot be built. A mitigation strategy would be to **mirror all Sirius Web artifacts to Maven Central and npmjs.com**, or to **vendor all dependencies locally** and set up a private artifact repository.
