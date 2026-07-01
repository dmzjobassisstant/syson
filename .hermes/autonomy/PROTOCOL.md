# Autonomy Loop Protocol

## Architecture

```
┌──────────────────────────────────────────────┐
│               LOOP-STATE.md                   │
│  Backlog → Current Task → State → Results     │
└────────────┬─────────────────────────────────┘
             │
   ┌─────────▼──────────┐      ┌──────────────┐
   │  BUILDER WORKTREE   │      │   GATES.md    │
   │  (autonomy/builder) │─────▶│  7 gate defs  │
   │  • reads STATE      │      └──────────────┘
   │  • bounded change   │
   │  • red→green test   │
   │  • commits          │
   │  • updates STATE    │
   └─────────┬──────────┘
             │
   ┌─────────▼──────────┐
   │ REVIEWER WORKTREE   │
   │ (autonomy/reviewer) │
   │  • runs all gates   │
   │  • revert→fail      │
   │  • mutate→fail      │
   │  • restore→pass     │
   │  • accept/reject    │
   │  • updates STATE    │
   └─────────┬──────────┘
             │
   ┌─────────▼──────────┐
   │   MERGE TO MAIN     │
   │   (if accepted)     │
   └────────────────────┘
```

## State Machine

```
BACKLOG → IN_PROGRESS → BUILDER_DONE → REVIEWER_PASS → MERGED
                │              │              │
                ▼              ▼              ▼
            FAILED        REVIEWER_FAIL    REJECTED
```

## Builder Workflow

1. Read `LOOP-STATE.md` → pick top item from Backlog
2. Set state to `IN_PROGRESS`
3. Create feature branch from `rbac`
4. Implement ONE bounded change
5. Write/update tests:
   - **Before fix:** test fails (RED) — capture output
   - **After fix:** test passes (GREEN) — capture output
6. Run Gate Pipeline (Gates 1,2,4,6,7)
7. Commit with format: `feat/fix: <description>\n\nRED: <test output>\nGREEN: <test output>\nGATES: <results>`
8. Push branch
9. Update LOOP-STATE.md → `BUILDER_DONE`

## Reviewer Workflow

1. Read `LOOP-STATE.md` → find `BUILDER_DONE` item
2. Checkout builder's branch
3. Run FULL gate pipeline (Gates 1-7)
4. Test proof:
   a. All gates pass ✓
   b. Revert fix → verify test fails ✗ (record output)
   c. Re-apply fix → all pass ✓
   d. Mutate fix (break it) → verify test fails ✗
   e. Restore fix → all pass ✓
5. If ALL steps pass → ACCEPT, merge to rbac, update STATE
6. If any step fails → REJECT with evidence, update STATE

## Trust Tiers

| Tier | Criteria |
|------|----------|
| **Platinum** | Full test proof + all gates + reviewer mutation pass |
| **Gold** | All gates pass + revert proof |
| **Silver** | All gates pass |
| **Bronze** | Builder gates pass only |
| **Parked** | Repeated failure, needs human |

## Protected Work

If a task fails 3+ builder or 2+ reviewer cycles:
- Mark as `PARKED` in LOOP-STATE.md
- Add risk assessment
- Do NOT retry autonomously
