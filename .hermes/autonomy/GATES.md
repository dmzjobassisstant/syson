# GATES.md — Build/Test/Lint Gate Definitions

## Gate 1: Lint (Frontend)

```bash
cd /root/syson-fork/frontend/syson
npm run lint 2>&1  # if available; otherwise: npx oxlint .
```

**Pass:** Exit code 0, no errors. Warnings allowed but reported.

## Gate 2: Build (Backend)

```bash
cd /root/syson-fork
mvn -o compile -pl backend/application/syson-application -am 2>&1
```

**Pass:** Exit code 0, `BUILD SUCCESS`.  
**⚠ NEVER use `-am` for package/install.** Use `-pl backend/application/syson-application -am` for compile only.

## Gate 3: Build (Full — only for reviewer)

```bash
cd /root/syson-fork
mvn -o package -pl backend/application/syson-application 2>&1
# Verify frontend JAR > 1.75 MB and contains static/index.html
jar tf backend/application/syson-application/target/syson-application-*.jar | grep "static/index.html"
```

**Pass:** `static/index.html` found in JAR, JAR size > 1.75 MB.

## Gate 4: Login Regression

```bash
cd /root/syson-fork
bash scripts/check-syson-login-regression.sh 2>&1
```

**Pass:** All checks pass: auth.js served, overlay renders, root blocked, login API works, token works.

## Gate 5: Enterprise Access Regression

```bash
cd /root/syson-fork
BASE_URL=http://localhost:8080 bash scripts/check-syson-enterprise-access-regression.sh 2>&1
```

**Pass:** All checks pass. (Requires backend running on :8080.)

## Gate 6: Frontend Build

```bash
cd /root/syson-fork/frontend/syson
npm run build 2>&1
```

**Pass:** Exit code 0.

## Gate 7: RBAC / Auth Regression

```bash
cd /root/syson-fork
# Verify auth.js is intact
node -c frontend/syson/public/auth.js
# Verify no regressions in SecurityConfig
grep -c "AuthenticationEntryPoint" backend/application/syson-application/src/main/java/org/eclipse/syson/security/SecurityConfig.java
```

**Pass:** Auth.js syntax valid. At least 1 AuthenticationEntryPoint reference in SecurityConfig.

---

## Gate Pipeline (Builder Must Pass)

| Order | Gate | Auto? |
|-------|------|-------|
| 1 | Lint | Yes |
| 2 | Build (Backend compile) | Yes |
| 4 | Login Regression | Yes |
| 6 | Frontend Build | Yes |
| 7 | RBAC/Auth Regression | Yes |

## Gate Pipeline (Reviewer Must Pass)

All gates 1-7, plus:
| Order | Gate | Auto? |
|-------|------|-------|
| 3 | Build (Full package) | Yes |
| 5 | Enterprise Access Regression | Needs backend |

## Test Proof (Reviewer)

After builder commits, reviewer MUST:
1. Run the full gate pipeline → all pass
2. **Revert the fix** → at least one test MUST fail (proving the test catches the bug)
3. **Re-apply the fix** → all tests pass again
4. **Mutate the fix** (break it intentionally) → at least one test MUST fail
5. **Restore the fix** → all tests pass

If the test suite passes even with the fix reverted, the test is weak — REJECT.
