#!/usr/bin/env python3
"""
check-syson-history-warehouse-regression.py
Verifies the enterprise history/warehouse/version-control implementation.
Run: python3 scripts/check-syson-history-warehouse-regression.py
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
    cmd = [
        "curl", "-ksS", "-w", "\n%{http_code}\n%{content_type}",
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


# --- Login ---
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

# --- Test project (may be empty, that is fine) ---
PID = "00000000-0000-0000-0000-000000000001"
BID = "00000000-0000-0000-0000-000000000001"

# --- Version control endpoints ---
data = assert_json(
    f"/api/v1/projects/{PID}/version-control/overview",
    token=TOKEN, label="VC overview",
)
if data:
    for key in ["branchCount", "commitCount", "changeCount", "baselineCount", "tagCount"]:
        if key not in data:
            fail(f"overview missing field: {key}")
        else:
            ok(f"overview.{key} present")

data = assert_json(
    f"/api/v1/projects/{PID}/version-control/tree",
    token=TOKEN, label="VC tree",
)
if data:
    for key in ["branches", "commits", "baselines", "tags"]:
        if key not in data:
            fail(f"tree missing field: {key}")
        else:
            ok(f"tree.{key} present")

assert_json(
    f"/api/v1/projects/{PID}/branches?tenantId={PID}",
    token=TOKEN, label="list branches",
)

# Lock endpoint (404 for non-existent branch)
assert_json(
    f"/api/v1/projects/{PID}/branches/{BID}/lock",
    expected_status=404, token=TOKEN, label="branch lock (404)",
)

# Integrity endpoint (404 for non-existent branch)
assert_json(
    f"/api/v1/projects/{PID}/branches/{BID}/integrity/latest",
    expected_status=404, token=TOKEN, label="integrity latest (404)",
)

# --- Unauthenticated access blocked ---
code, _, _ = curl_get(f"/api/v1/projects/{PID}/version-control/overview")
if code in (401, 403):
    ok(f"unauthenticated VC overview blocked ({code})")
else:
    fail(f"unauthenticated VC overview returned HTTP {code} (expected 401/403)")

# --- Summary ---
print(f"\nResults: {PASS} passed, {FAIL} failed")
if FAIL == 0:
    print("OK: history/warehouse regression passed.")
else:
    print(f"FAIL: {FAIL} checks failed.")
    sys.exit(1)
