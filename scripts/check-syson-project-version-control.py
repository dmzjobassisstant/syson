#!/usr/bin/env python3
"""
check-syson-project-version-control.py
Verifies the project version control management implementation:
- VC overview has all required fields (including openMRCount)
- VC tree has all required fields
- Tag list endpoint works
- Merge request list endpoint works
- Default branch GET/SET works
- Auth checks (unauthenticated blocked)
- Branches endpoint works
- Baselines endpoint works
Run: python3 scripts/check-syson-project-version-control.py
"""
import json
import os
import subprocess
import sys

BASE_URL = os.environ.get("BASE_URL", "https://syson.damuza-consulting.com")
ADMIN_USER = os.environ.get("SYSON_TEST_USER", "admin")
ADMIN_PASSWORD = os.environ.get("SYSON_TEST_PASSWORD", "admin")

PASS = 0
FAIL = 0


def log(msg):
    print(f"  {msg}")


def ok(msg):
    global PASS
    PASS += 1
    print(f"  PASS: {msg}")


def fail(msg):
    global FAIL
    FAIL += 1
    print(f"  FAIL: {msg}", file=sys.stderr)


def curl_get(path, token=None):
    """Returns (status_code, content_type, body_text)."""
    url = BASE_URL + path
    return curl_get_url(url, token)


def curl_get_url(url, token=None):
    """Returns (status_code, content_type, body_text) for a full URL."""
    cmd = [
        "curl", "-ksSL", "-w", "\n%{http_code}\n%{content_type}",
        "-o", "-", "-H", "Accept: application/json",
    ]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    cmd.append(url)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    lines = result.stdout.rsplit("\n", 2)
    if len(lines) >= 3:
        body, code_str, ct = lines[0], lines[1], lines[2]
    elif len(lines) == 2:
        body, code_str = lines
        ct = ""
    else:
        body, code_str, ct = result.stdout, "000", ""
    try:
        code = int(code_str.strip())
    except ValueError:
        code = 0
    return code, ct.strip(), body


def curl_post(path, data, token=None):
    """POST JSON, returns (status_code, body_text)."""
    url = BASE_URL + path
    cmd = [
        "curl", "-ksS", "-w", "\n%{http_code}",
        "-o", "-", "-H", "Content-Type: application/json",
        "-H", "Accept: application/json",
        "-d", json.dumps(data),
    ]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    cmd.append(url)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    lines = result.stdout.rsplit("\n", 1)
    if len(lines) >= 2:
        body, code_str = lines[0], lines[1]
    else:
        body, code_str = result.stdout, "000"
    try:
        code = int(code_str.strip())
    except ValueError:
        code = 0
    return code, body


def assert_json(path, expected_status=200, token=None, label=None):
    label = label or path
    code, ct, body = curl_get(path, token)
    if code != expected_status:
        fail(f"{label}: expected HTTP {expected_status}, got {code}")
        return None
    if expected_status == 200 and "application/json" not in ct:
        fail(f"{label}: expected JSON, got {ct}")
        return None
    ok(f"{label} (HTTP {code})")
    if expected_status == 200:
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            fail(f"{label}: response is not valid JSON")
            return None
    return None


def assert_post_json(path, data, expected_status=200, token=None, label=None):
    label = label or path
    code, body = curl_post(path, data, token)
    if code != expected_status:
        fail(f"{label}: expected HTTP {expected_status}, got {code}")
        return None
    ok(f"{label} (HTTP {code})")
    if expected_status == 200:
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            fail(f"{label}: response is not valid JSON")
            return None
    return None


# --- Login ---
print("\n=== Project Version Control Management Regression ===\n")
print("--- Authentication ---")
code, body = curl_post("/api/auth/login", {
    "email": ADMIN_USER,
    "password": ADMIN_PASSWORD,
})
if code != 200:
    fail(f"login failed: HTTP {code}")
    sys.exit(1)
try:
    TOKEN = json.loads(body)["token"]
except (json.JSONDecodeError, KeyError):
    fail("login response missing token")
    sys.exit(1)
ok("admin login")

# Use a test project ID (may not exist, endpoints should still return valid structure)
PID = "00000000-0000-0000-0000-000000000001"
BID = "00000000-0000-0000-0000-000000000001"

# --- VC Overview ---
print("\n--- Version Control Overview ---")
data = assert_json(
    f"/api/v1/projects/{PID}/version-control/overview",
    token=TOKEN, label="VC overview",
)
if data:
    for key in ["branchCount", "commitCount", "changeCount", "baselineCount", "tagCount", "openMRCount"]:
        if key not in data:
            fail(f"overview missing field: {key}")
        else:
            ok(f"overview.{key} present (value={data[key]})")

# --- VC Tree ---
print("\n--- Version Control Tree ---")
data = assert_json(
    f"/api/v1/projects/{PID}/version-control/tree",
    token=TOKEN, label="VC tree",
)
if data:
    for key in ["branches", "commits", "baselines", "tags"]:
        if key not in data:
            fail(f"tree missing field: {key}")
        else:
            ok(f"tree.{key} present (count={len(data[key]) if isinstance(data[key], list) else 'N/A'})")

# --- Branches ---
print("\n--- Branches ---")
data = assert_json(
    f"/api/v1/projects/{PID}/branches?tenantId={PID}",
    token=TOKEN, label="list branches",
)
if data is not None:
    ok(f"branches returned (count={len(data) if isinstance(data, list) else 'N/A'})")

# --- Tags ---
print("\n--- Tags ---")
data = assert_json(
    f"/api/v1/projects/{PID}/tags",
    token=TOKEN, label="list tags",
)
if data is not None:
    if isinstance(data, list):
        ok(f"tags returned list (count={len(data)})")
    else:
        fail(f"tags returned non-list: {type(data)}")

# --- Merge Requests ---
print("\n--- Merge Requests ---")
data = assert_json(
    f"/api/v1/projects/{PID}/merge-requests",
    token=TOKEN, label="list merge requests",
)
if data is not None:
    if isinstance(data, list):
        ok(f"merge requests returned list (count={len(data)})")
    else:
        fail(f"merge requests returned non-list: {type(data)}")

# --- Default Branch Settings ---
print("\n--- Default Branch Settings ---")
data = assert_json(
    f"/api/v1/projects/{PID}/settings/default-branch",
    token=TOKEN, label="GET default branch",
)
if data is not None:
    if "branchId" in data:
        ok(f"default branch has branchId field (value={data['branchId']})")
    else:
        fail("default branch response missing 'branchId' field")

# Set default branch
data = assert_post_json(
    f"/api/v1/projects/{PID}/settings/default-branch",
    {"branchId": BID},
    token=TOKEN, label="SET default branch",
)
if data is not None:
    if "branchId" in data:
        ok(f"set default branch returned branchId (value={data['branchId']})")
    else:
        fail("set default branch response missing 'branchId' field")

# Verify it was set
data = assert_json(
    f"/api/v1/projects/{PID}/settings/default-branch",
    token=TOKEN, label="verify default branch after SET",
)
if data is not None and data.get("branchId") == BID:
    ok("default branch value persisted correctly")
elif data is not None:
    fail(f"default branch expected {BID}, got {data.get('branchId')}")

# --- Auth Checks ---
print("\n--- Auth Checks ---")
code, _, _ = curl_get(f"/api/v1/projects/{PID}/version-control/overview")
if code in (401, 403):
    ok(f"unauth VC overview blocked ({code})")
else:
    fail(f"unauth VC overview returned HTTP {code} (expected 401/403)")

code, _, _ = curl_get(f"/api/v1/projects/{PID}/tags")
if code in (401, 403):
    ok(f"unauth tags blocked ({code})")
else:
    fail(f"unauth tags returned HTTP {code} (expected 401/403)")

code, _, _ = curl_get(f"/api/v1/projects/{PID}/merge-requests")
if code in (401, 403):
    ok(f"unauth merge-requests blocked ({code})")
else:
    fail(f"unauth merge-requests returned HTTP {code} (expected 401/403)")

code, _, _ = curl_get(f"/api/v1/projects/{PID}/settings/default-branch")
if code in (401, 403):
    ok(f"unauth default-branch blocked ({code})")
else:
    fail(f"unauth default-branch returned HTTP {code} (expected 401/403)")

code, _ = curl_post(f"/api/v1/projects/{PID}/settings/default-branch", {"branchId": BID})
if code in (401, 403):
    ok(f"unauth set default-branch blocked ({code})")
else:
    fail(f"unauth set default-branch returned HTTP {code} (expected 401/403)")

# --- Branch Lock (existing, verify no regression) ---
print("\n--- Existing Endpoints (No Regression) ---")
assert_json(
    f"/api/v1/projects/{PID}/branches/{BID}/lock",
    expected_status=404, token=TOKEN, label="branch lock (404)",
)
assert_json(
    f"/api/v1/projects/{PID}/branches/{BID}/integrity/latest",
    expected_status=404, token=TOKEN, label="integrity latest (404)",
)

# --- Element Locking Settings (no regression) ---
data = assert_json(
    f"/api/v1/projects/{PID}/settings/element-locking",
    token=TOKEN, label="element locking setting",
)
if data is not None and "enabled" in data:
    ok(f"element locking setting still works (enabled={data['enabled']})")

# --- Frontend Content Checks ---
print("\n--- Frontend Content Checks ---")
# Check nginx-served auth.js (port 80) since the container serves the bundled version
nginx_url = "http://localhost:80"
code, ct, body = curl_get_url(nginx_url + "/auth.js")
if code == 200 and "javascript" in ct.lower():
    ok("auth.js served by nginx")
    if "Version Control" in body:
        ok("auth.js contains 'Version Control' text")
    else:
        fail("auth.js missing 'Version Control' text")
    if "GitGraph" in body or "version-graph" in body or "VersionGraph" in body or "renderGitGraph" in body:
        ok("auth.js contains version graph visualization reference")
    else:
        fail("auth.js missing version graph visualization reference")
    if "default-branch" in body or "defaultBranch" in body or "default_branch" in body:
        ok("auth.js contains default branch management code")
    else:
        fail("auth.js missing default branch management code")
elif code == 200:
    ok("auth.js served by nginx (HTTP 200)")
    fail("auth.js content type not javascript: " + ct)
else:
    fail("auth.js not served correctly by nginx (HTTP " + str(code) + ")")

# --- Summary ---
print(f"\n=== Results: {PASS} passed, {FAIL} failed ===")
if FAIL == 0:
    print("OK: project version control management regression passed.")
else:
    print(f"FAIL: {FAIL} checks failed.")
    sys.exit(1)
