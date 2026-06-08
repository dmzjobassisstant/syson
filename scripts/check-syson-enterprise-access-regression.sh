#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-https://syson.damuza-consulting.com}"
ADMIN_USER="${SYSON_TEST_USER:-admin}"
ADMIN_PASSWORD="${SYSON_TEST_PASSWORD:-admin}"
TMP_DIR=$(mktemp -d)
RESPONSE_FILE="$TMP_DIR/response.body"
HEADER_FILE="$TMP_DIR/response.headers"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  if [[ -s "$HEADER_FILE" ]]; then
    echo "--- response headers ---" >&2
    sed -n '1,40p' "$HEADER_FILE" >&2 || true
  fi
  if [[ -s "$RESPONSE_FILE" ]]; then
    echo "--- response body prefix ---" >&2
    head -c 500 "$RESPONSE_FILE" >&2 || true
    echo >&2
  fi
  exit 1
}

http_json() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local code
  : >"$HEADER_FILE"
  : >"$RESPONSE_FILE"
  if [[ -n "$token" && -n "$body" ]]; then
    code=$(curl -ksS -D "$HEADER_FILE" -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method" --oauth2-bearer "$token" -H 'Accept: application/json' -H 'Content-Type: application/json' -d "$body" "$BASE_URL$path")
  elif [[ -n "$token" ]]; then
    code=$(curl -ksS -D "$HEADER_FILE" -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method" --oauth2-bearer "$token" -H 'Accept: application/json' "$BASE_URL$path")
  elif [[ -n "$body" ]]; then
    code=$(curl -ksS -D "$HEADER_FILE" -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method" -H 'Accept: application/json' -H 'Content-Type: application/json' -d "$body" "$BASE_URL$path")
  else
    code=$(curl -ksS -D "$HEADER_FILE" -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method" -H 'Accept: application/json' "$BASE_URL$path")
  fi
  printf '%s\n' "$code"
}

assert_json_body() {
  python3 - "$RESPONSE_FILE" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    json.load(f)
PY
}

json_get() {
  local expr="$1" file="${2:-$RESPONSE_FILE}"
  python3 - "$expr" "$file" <<'PY'
import json, sys
expr, path = sys.argv[1], sys.argv[2]
with open(path) as f:
    data = json.load(f)
value = eval(expr, {'data': data})
print('' if value is None else value)
PY
}

assert_json_status() {
  local actual="$1" expected="$2" label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label returned HTTP $actual, expected $expected"
  assert_json_body || fail "$label did not return parseable JSON"
}

# Unauthenticated protection: admin APIs must not leak JSON account data without JWT.
code=$(http_json GET '/api/v1/user/admin/users')
if [[ "$code" == "200" ]]; then
  if python3 - "$RESPONSE_FILE" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
    raise SystemExit(0 if isinstance(data, list) else 1)
except Exception:
    raise SystemExit(1)
PY
  then
    fail "unauthenticated admin users leaked JSON list"
  fi
elif [[ "$code" != "401" && "$code" != "403" ]]; then
  fail "unauthenticated admin users returned HTTP $code, expected 401/403 or non-JSON SPA fallback"
fi

# Admin login and basic authenticated profile.
code=$(http_json POST '/api/auth/login' '' "{\"email\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}")
assert_json_status "$code" "200" "admin login"
ADMIN_TOKEN=$(json_get "data.get('token')")
[[ -n "$ADMIN_TOKEN" ]] || fail "admin login did not return token"

code=$(http_json GET '/api/v1/user/me' "$ADMIN_TOKEN")
assert_json_status "$code" "200" "authenticated /me"
ADMIN_ID=$(json_get "data.get('id')")
[[ -n "$ADMIN_ID" ]] || fail "/me did not return admin id"

code=$(http_json GET '/api/v1/user/admin/users' "$ADMIN_TOKEN")
assert_json_status "$code" "200" "admin users list"
python3 - "$RESPONSE_FILE" <<'PY' || fail "admin users list was not a JSON array"
import json, sys
assert isinstance(json.load(open(sys.argv[1])), list)
PY

# Create a unique non-admin user and prove least-privilege admin denial.
stamp=$(date +%s%N)
NEW_EMAIL="regression+$stamp@example.test"
NEW_PASSWORD="TmpPass${stamp}!"
CREATE_BODY="{\"email\":\"$NEW_EMAIL\",\"name\":\"Regression User\",\"password\":\"$NEW_PASSWORD\",\"tenantRole\":\"viewer\"}"
code=$(http_json POST '/api/v1/user/admin/users' "$ADMIN_TOKEN" "$CREATE_BODY")
assert_json_status "$code" "201" "admin create user"
NEW_ID=$(json_get "data.get('id')")
[[ -n "$NEW_ID" ]] || fail "create user did not return id"

code=$(http_json POST '/api/auth/login' '' "{\"email\":\"$NEW_EMAIL\",\"password\":\"$NEW_PASSWORD\"}")
assert_json_status "$code" "200" "created user login"
USER_TOKEN=$(json_get "data.get('token')")
[[ -n "$USER_TOKEN" ]] || fail "created user login did not return token"

code=$(http_json GET '/api/v1/user/me' "$USER_TOKEN")
assert_json_status "$code" "200" "created user /me"

code=$(http_json GET '/api/v1/user/admin/users' "$USER_TOKEN")
assert_json_status "$code" "403" "viewer admin access"

# Admin password reset must work and the reset password must authenticate.
RESET_PASSWORD="ResetPass${stamp}!"
code=$(http_json PUT "/api/v1/user/admin/users/$NEW_ID/password" "$ADMIN_TOKEN" "{\"password\":\"$RESET_PASSWORD\"}")
assert_json_status "$code" "200" "admin password reset"

code=$(http_json POST '/api/auth/login' '' "{\"email\":\"$NEW_EMAIL\",\"password\":\"$RESET_PASSWORD\"}")
assert_json_status "$code" "200" "reset password login"

# Audit log should record login/create/reset events and be readable by admin only.
code=$(http_json GET '/api/v1/user/admin/audit/events?limit=50' "$ADMIN_TOKEN")
assert_json_status "$code" "200" "audit event list"
python3 - "$RESPONSE_FILE" "$NEW_ID" <<'PY' || fail "audit log missing expected account events"
import json, sys
with open(sys.argv[1]) as f:
    events = json.load(f)
new_id = sys.argv[2]
actions = [e.get('action') for e in events]
targets = [str(e.get('targetId')) for e in events]
assert isinstance(events, list), type(events)
assert 'admin.user.created' in actions, actions
assert 'auth.login.success' in actions, actions
assert new_id in targets, targets
PY

# RBAC Audit Trail endpoint — admin-only, returns paginated JSON.
code=$(http_json GET '/api/v1/user/admin/audit-trail?size=10&page=0&sort=createdAt,desc' "$ADMIN_TOKEN")
assert_json_status "$code" "200" "admin audit trail"

python3 - "$RESPONSE_FILE" <<'PY' || fail "audit trail response missing required fields"
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
assert isinstance(data, dict), f"expected dict, got {type(data)}"
assert "content" in data, f"missing 'content' key: {list(data.keys())}"
assert "totalElements" in data, f"missing 'totalElements' key: {list(data.keys())}"
assert isinstance(data["content"], list), f"'content' should be list"
assert data["totalElements"] >= 0, f"totalElements should be >= 0"
PY

# Audit trail — viewer must get 403.
code=$(http_json GET '/api/v1/user/admin/audit-trail?size=5' "$USER_TOKEN")
assert_json_status "$code" "403" "viewer audit trail denial"

# Audit trail — unauthenticated must get 401/403.
code=$(http_json GET '/api/v1/user/admin/audit-trail?size=5')
if [[ "$code" != "401" && "$code" != "403" ]]; then
  fail "unauthenticated audit trail returned HTTP $code, expected 401/403"
fi

# Audit trail — verify login_success events exist after our test logins.
code=$(http_json GET '/api/v1/user/admin/audit-trail?size=50&page=0&sort=createdAt,desc' "$ADMIN_TOKEN")
assert_json_status "$code" "200" "audit trail for event check"
python3 - "$RESPONSE_FILE" <<'PY' || fail "audit trail missing login_success events"
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
events = data.get("content", [])
event_types = [e.get("eventType") for e in events]
assert "login_success" in event_types, f"no login_success in: {event_types}"
PY

printf 'OK: enterprise regression passed — unauth blocked/no JSON leak, admin APIs work, viewer denied with JSON 403, password reset works, audit logs populated, RBAC audit trail verified.\n'
