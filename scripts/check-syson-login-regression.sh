#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-https://syson.damuza-consulting.com}"
REPO_DIR="${REPO_DIR:-/root/syson-fork}"
SRC_AUTH="$REPO_DIR/frontend/syson/public/auth.js"
LIVE_AUTH="/var/www/syson/auth.js"
USER_NAME="${SYSON_TEST_USER:-admin}"
PASSWORD="${SYSON_TEST_PASSWORD:-admin}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "missing file: $1"
}

require_file "$SRC_AUTH"
require_file "$LIVE_AUTH"

node -c "$SRC_AUTH" >/dev/null || fail "auth.js syntax check failed"

if ! cmp -s "$SRC_AUTH" "$LIVE_AUTH"; then
  fail "source auth.js and live /var/www/syson/auth.js differ; copy source to live and reload nginx"
fi

AUTH_STATUS=$(curl -ksS -o /tmp/syson-auth-live.js -w '%{http_code}' "$BASE_URL/auth.js")
[[ "$AUTH_STATUS" == "200" ]] || fail "$BASE_URL/auth.js returned HTTP $AUTH_STATUS"

TMP_PROFILE=$(mktemp -d)
DOM_FILE=$(mktemp)
ERR_FILE=$(mktemp)
cleanup() {
  rm -rf "$TMP_PROFILE" "$DOM_FILE" "$ERR_FILE" /tmp/syson-auth-live.js /tmp/syson-login-token.json
}
trap cleanup EXIT

# Fresh user-data-dir guarantees no localStorage token. The login overlay must render in this state.
timeout 45 chromium-browser \
  --headless \
  --no-sandbox \
  --disable-gpu \
  --user-data-dir="$TMP_PROFILE" \
  --virtual-time-budget=7000 \
  --dump-dom \
  "$BASE_URL/?login_regression_check=$(date +%s)" >"$DOM_FILE" 2>"$ERR_FILE" || {
    cat "$ERR_FILE" >&2 || true
    fail "Chromium DOM dump failed"
  }

if ! grep -q 'id="syson-auth-overlay"' "$DOM_FILE"; then
  echo "--- DOM head ---" >&2
  head -120 "$DOM_FILE" >&2 || true
  fail "login overlay was not rendered for a fresh unauthenticated browser session"
fi

if ! grep -q 'id="syson-root-blocker"' "$DOM_FILE"; then
  fail "root blocker style was not injected"
fi

if ! grep -q '#root { display: none !important; }' "$DOM_FILE"; then
  fail "root blocker does not use display:none !important"
fi

if ! grep -q '>Username<' "$DOM_FILE"; then
  fail "login form did not render Username label"
fi

LOGIN_STATUS=$(curl -ksS -o /tmp/syson-login-token.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" \
  "$BASE_URL/api/auth/login")
[[ "$LOGIN_STATUS" == "200" ]] || fail "login API returned HTTP $LOGIN_STATUS for $USER_NAME"

TOKEN=$(python3 - <<'PY'
import json
with open('/tmp/syson-login-token.json') as f:
    data=json.load(f)
print(data.get('token',''))
PY
)
[[ -n "$TOKEN" ]] || fail "login response did not include token"

ME_STATUS=$(curl -ksS -o /tmp/syson-me.json -w '%{http_code}' \
  --oauth2-bearer "$TOKEN" \
  "$BASE_URL/api/v1/user/me")
[[ "$ME_STATUS" == "200" ]] || fail "authenticated /api/v1/user/me returned HTTP $ME_STATUS"

printf 'OK: login overlay rendered, root blocker active, auth.js live, login API works, /me works.\n'
