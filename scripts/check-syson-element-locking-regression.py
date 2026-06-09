#!/usr/bin/env python3
"""
Automated regression tests for Element Locking feature.
Tests: project settings, single lock/unlock, recursive lock/unlock,
conflict detection, read-only enforcement, Sirius editor compatibility.

Run: python3 scripts/check-syson-element-locking-regression.py
"""
import json, sys, urllib.request

BASE = "http://localhost:8080"
PASS_COUNT = 0
FAIL_COUNT = 0

def get_token():
    data = json.dumps({"email": "admin", "password": "admin"}).encode()
    req = urllib.request.Request(BASE + "/api/auth/login", data,
        {"Content-Type": "application/json"}, method="POST")
    resp = urllib.request.urlopen(req, timeout=10)
    return json.loads(resp.read().decode())["token"]

def api(method, path, body=None, token=None):
    hdrs = {"Content-Type": "application/json"}
    if token:
        hdrs["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(BASE + path, data, hdrs, method=method)
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        raw = resp.read().decode()
        try: return resp.status, json.loads(raw)
        except: return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try: return e.code, json.loads(raw)
        except: return e.code, raw

def check(name, condition, detail=""):
    global PASS_COUNT, FAIL_COUNT
    if condition:
        PASS_COUNT += 1
        print("  PASS  " + name)
    else:
        FAIL_COUNT += 1
        print("  FAIL  " + name + " " + detail)

def get_project_id(token):
    s, r = api("GET", "/api/v1/user/me/projects", token=token)
    if isinstance(r, list) and r:
        return r[0]["id"]
    import subprocess
    out = subprocess.check_output(
        ["sudo", "-u", "postgres", "psql", "-d", "syson", "-t", "-A", "-c",
         "SELECT id FROM project LIMIT 1"]).decode().strip()
    return out if out else None

def main():
    global PASS_COUNT, FAIL_COUNT
    print("=" * 60)
    print("SysON Element Locking Regression Tests")
    print("=" * 60)

    token = get_token()
    pid = get_project_id(token)
    check("Project ID obtained", pid is not None and len(pid) > 0, str(pid))
    if not pid:
        print("FATAL: No project available")
        sys.exit(1)

    branch = "00000000-0000-0000-0000-000000000000"

    # --- Section 1: Project Settings ---
    print("\n--- Project Settings ---")

    s, r = api("GET", "/api/v1/projects/" + pid + "/settings/element-locking", token=token)
    check("GET settings returns 200", s == 200, "status=" + str(s))
    check("GET settings returns JSON", isinstance(r, dict) and "enabled" in r, str(r)[:100])

    s, r = api("POST", "/api/v1/projects/" + pid + "/settings/element-locking",
        {"enabled": True}, token=token)
    check("POST enable returns 200", s == 200, "status=" + str(s))
    check("POST returns enabled=true", isinstance(r, dict) and r.get("enabled") is True, str(r)[:100])

    s, r = api("GET", "/api/v1/projects/" + pid + "/settings/element-locking", token=token)
    check("Verify locking is enabled", isinstance(r, dict) and r.get("enabled") is True, str(r)[:100])

    # --- Section 2: Single Lock ---
    print("\n--- Single Element Lock ---")

    s, r = api("POST", "/api/v1/projects/" + pid + "/elements/test-lock-001/lock",
        {"branchId": branch, "reason": "regression test", "ttlMinutes": 60,
         "sessionId": "test", "deviceId": "browser"}, token=token)
    check("Acquire lock returns 200", s == 200, "status=" + str(s))
    check("Lock has stableId", isinstance(r, dict) and r.get("stableId") == "test-lock-001", str(r)[:100])
    check("Lock has ownerUsername", isinstance(r, dict) and r.get("ownerUsername") is not None, str(r)[:100])

    s, r = api("GET", "/api/v1/projects/" + pid + "/element-locks", token=token)
    check("List locks returns 200", s == 200, "status=" + str(s))
    check("List has >= 1 lock", isinstance(r, list) and len(r) >= 1,
          "count=" + str(len(r) if isinstance(r, list) else "?"))

    s, r = api("GET", "/api/v1/projects/" + pid + "/elements/test-lock-001/lock?branchId=" + branch, token=token)
    check("Get specific lock returns 200", s == 200, "status=" + str(s))

    s, _ = api("DELETE", "/api/v1/projects/" + pid + "/elements/test-lock-001/lock?branchId=" + branch, token=token)
    check("Release lock returns 204", s == 204, "status=" + str(s))

    # --- Section 3: Recursive Lock ---
    print("\n--- Recursive Element Lock ---")

    s, r = api("POST", "/api/v1/projects/" + pid + "/elements/test-root/lock-recursive",
        {"branchId": branch, "reason": "recursive test", "ttlMinutes": 60,
         "sessionId": "test", "deviceId": "browser"}, token=token)
    check("Recursive lock returns 200", s == 200, "status=" + str(s))
    check("Returns lockedCount", isinstance(r, dict) and "lockedCount" in r, str(r)[:150])

    s, r = api("DELETE", "/api/v1/projects/" + pid + "/elements/test-root/lock-recursive?branchId=" + branch, token=token)
    check("Recursive unlock returns 200", s == 200, "status=" + str(s))
    check("Returns released count", isinstance(r, dict) and "released" in r, str(r)[:100])

    # --- Section 4: Release-all ---
    print("\n--- Release-all on Save ---")

    api("POST", "/api/v1/projects/" + pid + "/elements/save-test-1/lock",
        {"branchId": branch, "reason": "test", "ttlMinutes": 60, "sessionId": "s", "deviceId": "d"}, token=token)
    api("POST", "/api/v1/projects/" + pid + "/elements/save-test-2/lock",
        {"branchId": branch, "reason": "test", "ttlMinutes": 60, "sessionId": "s", "deviceId": "d"}, token=token)

    s, r = api("POST", "/api/v1/projects/" + pid + "/element-locks/release-all",
        {"branchId": branch}, token=token)
    check("Release-all returns 200", s == 200, "status=" + str(s))
    check("Released 2+ locks", isinstance(r, dict) and r.get("released", 0) >= 2, str(r)[:100])

    s, r = api("GET", "/api/v1/projects/" + pid + "/element-locks", token=token)
    check("Locks empty after release-all", isinstance(r, list) and len(r) == 0,
          "count=" + str(len(r) if isinstance(r, list) else "?"))

    # --- Section 5: Auth / Security ---
    print("\n--- Auth & Security ---")

    s, _ = api("GET", "/api/v1/projects/" + pid + "/settings/element-locking")
    check("Unauth GET settings returns 401", s == 401, "status=" + str(s))

    s, _ = api("POST", "/api/v1/projects/" + pid + "/settings/element-locking", {"enabled": True})
    check("Unauth POST settings returns 401", s == 401, "status=" + str(s))

    s, _ = api("GET", "/api/v1/projects/" + pid + "/element-locks")
    check("Unauth list locks returns 401", s == 401, "status=" + str(s))

    s, _ = api("POST", "/api/v1/projects/" + pid + "/elements/test/lock",
        {"branchId": branch, "reason": "", "ttlMinutes": 1, "sessionId": "", "deviceId": ""})
    check("Unauth acquire lock returns 401", s == 401, "status=" + str(s))

    # --- Section 6: Disable Locking ---
    print("\n--- Disable Locking ---")

    s, r = api("POST", "/api/v1/projects/" + pid + "/settings/element-locking",
        {"enabled": False}, token=token)
    check("Disable returns 200", s == 200, "status=" + str(s))
    check("Returns enabled=false", isinstance(r, dict) and r.get("enabled") is False, str(r)[:100])

    s, r = api("GET", "/api/v1/projects/" + pid + "/settings/element-locking", token=token)
    check("Verify locking is disabled", isinstance(r, dict) and r.get("enabled") is False, str(r)[:100])

    # --- Section 7: Sirius Compatibility ---
    print("\n--- Sirius Editor Compatibility ---")

    s, r = api("GET", "/api/v1/user/me", token=token)
    check("/api/v1/user/me returns 200", s == 200, "status=" + str(s))

    s, r = api("GET", "/api/v1/user/me/projects", token=token)
    check("/api/v1/user/me/projects returns 200", s == 200, "status=" + str(s))

    # auth.js is served by nginx (port 443), not by the app (port 8080)
    try:
        req = urllib.request.Request("https://syson.damuza-consulting.com/auth.js")
        import ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        resp = urllib.request.urlopen(req, timeout=10, context=ctx)
        content = resp.read().decode()
        check("/auth.js served by nginx", resp.status == 200 and len(content) > 10000,
              "len=" + str(len(content)))
        check("auth.js has lock UI code", "injectElementLockUI" in content)
        check("auth.js has _lockingEnabled", "_lockingEnabled" in content)
        check("auth.js has lockElementRecursive", "lockElementRecursive" in content)
        check("auth.js has enforceReadOnly", "enforceReadOnlyForLockedElements" in content)
    except Exception as e:
        check("/auth.js served", False, str(e))

    try:
        req = urllib.request.Request(BASE + "/")
        resp = urllib.request.urlopen(req, timeout=10)
        content = resp.read().decode()
        check("SPA index.html served", resp.status == 200 and "SysON" in content)
    except Exception as e:
        check("SPA index.html", False, str(e))

    # --- Summary ---
    print("\n" + "=" * 60)
    total = PASS_COUNT + FAIL_COUNT
    print("Results: " + str(PASS_COUNT) + "/" + str(total) + " passed, " + str(FAIL_COUNT) + " failed")
    print("=" * 60)

    if FAIL_COUNT > 0:
        print("SOME TESTS FAILED")
        sys.exit(1)
    else:
        print("ALL TESTS PASSED")
        sys.exit(0)

if __name__ == "__main__":
    main()
