#!/usr/bin/env bash
# Smoke test for a RUNNING stack (docker compose up --build -d --wait).
# Exit status 0 ONLY when every check passes; any failure makes it non-zero.
#
# Checks: health through the gateway, REAL database connectivity (PostgreSQL + SQLite, read from the services' own health),
# routing of every /api/v1 area to its owning service, direct mock endpoints, and that unimplemented features answer an
# honest 501 instead of a fake success.
#
# Needs only bash + curl. Override targets with GATEWAY_URL, FRONTEND_URL, KIOSK_URL, MOCK_TURNSTILE_URL, MOCK_HR_URL.
set -u

GATEWAY="${GATEWAY_URL:-http://127.0.0.1:${GATEWAY_HOST_PORT:-8080}}"
FRONTEND="${FRONTEND_URL:-http://127.0.0.1:${FRONTEND_HOST_PORT:-3000}}"
KIOSK="${KIOSK_URL:-http://127.0.0.1:${KIOSK_HOST_PORT:-8085}}"
TURNSTILE="${MOCK_TURNSTILE_URL:-http://127.0.0.1:${MOCK_TURNSTILE_HOST_PORT:-8090}}"
HR="${MOCK_HR_URL:-http://127.0.0.1:${MOCK_HR_HOST_PORT:-8091}}"

PASSES=0
FAILS=0
CODE=000
BODY=""

pass() { printf '  PASS  %s\n' "$1"; PASSES=$((PASSES + 1)); }
fail() { printf '  FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }

# request METHOD URL [JSON_BODY]  -> sets CODE and BODY (CODE=000 when nothing answered)
request() {
  local method=$1 url=$2 data=${3-} out
  if [ -n "$data" ]; then
    out=$(curl -sS --max-time 10 -X "$method" -H 'Content-Type: application/json' -d "$data" -w $'\n%{http_code}' "$url" 2>/dev/null)
  else
    out=$(curl -sS --max-time 10 -X "$method" -w $'\n%{http_code}' "$url" 2>/dev/null)
  fi
  CODE=${out##*$'\n'}
  BODY=${out%$'\n'*}
  [ -n "$CODE" ] || CODE=000
}

# expect LABEL METHOD URL EXPECTED_CODE [MUST_CONTAIN] [JSON_BODY]
expect() {
  local label=$1 method=$2 url=$3 want=$4 needle=${5-} data=${6-}
  request "$method" "$url" "$data"
  if [ "$CODE" != "$want" ]; then
    fail "$label  (expected HTTP $want, got $CODE: $url)"
  elif [ -n "$needle" ] && [[ "$BODY" != *"$needle"* ]]; then
    fail "$label  (HTTP $CODE but body lacks: $needle)"
  else
    pass "$label"
  fi
}

echo "Smoke test -> gateway $GATEWAY"

# The gateway may still be starting if `up -d` was run without --wait.
for _ in $(seq 1 30); do
  request GET "$GATEWAY/gateway/health"
  [ "$CODE" = "200" ] && break
  sleep 1
done

echo "[1] gateway and frontend"
expect "gateway health"                    GET "$GATEWAY/gateway/health" 200 '"status":"UP"'
expect "frontend served through gateway"   GET "$GATEWAY/"               200 'id="root"'
expect "frontend served on its own port"   GET "$FRONTEND/"              200 'id="root"'
expect "frontend proxies /services to gateway" GET "$FRONTEND/services/attendance/health" 200 '"status":"UP"'

echo "[2] service health + real database connectivity (through the gateway)"
for svc in user-qr gate-station attendance reporting; do
  expect "$svc: UP with PostgreSQL reachable" GET "$GATEWAY/services/$svc/health" 200 '"db":{"status":"UP"'
done
expect "kiosk: UP"                         GET "$GATEWAY/services/kiosk/health" 200 '"status":"UP"'
expect "kiosk: SQLite file opened and read" GET "$GATEWAY/services/kiosk/health" 200 '"sqliteQueue":{"status":"UP"'
expect "kiosk: SQLite journal_mode is WAL"  GET "$GATEWAY/services/kiosk/health" 200 '"journalMode":"wal"'
expect "kiosk: SQLite synchronous is FULL"  GET "$GATEWAY/services/kiosk/health" 200 '"synchronous":"FULL"'
expect "mock-turnstile health (via gateway)" GET "$GATEWAY/services/mock-turnstile/health" 200 '"status":"UP"'
expect "mock-hr health (via gateway)"        GET "$GATEWAY/services/mock-hr/health"        200 '"status":"UP"'

echo "[3] direct host ports"
expect "kiosk-agent on host port"          GET "$KIOSK/actuator/health" 200 '"status":"UP"'
expect "mock-turnstile on host port"       GET "$TURNSTILE/health"      200 '"mock":true'
expect "mock-hr on host port"              GET "$HR/health"             200 '"mock":true'
expect "mock-hr serves synthetic employees" GET "$HR/mock/hr/employees" 200 '"EMP001"'

echo "[4] every /api/v1 area is routed to its owning service (501 JSON = reached Spring, not an nginx 404)"
expect "user-qr    GET  /api/v1/qr/generate"                 GET  "$GATEWAY/api/v1/qr/generate"               501 '"code":"NOT_IMPLEMENTED"'
expect "user-qr    GET  /api/v1/kiosk/whitelist"             GET  "$GATEWAY/api/v1/kiosk/whitelist"           501 '"code":"NOT_IMPLEMENTED"'
expect "gate       POST /api/v1/gates"                       POST "$GATEWAY/api/v1/gates"                     501 '"code":"NOT_IMPLEMENTED"' '{}'
expect "gate       GET  /api/v1/gates/BD-G01/status"         GET  "$GATEWAY/api/v1/gates/BD-G01/status"       501 '"code":"NOT_IMPLEMENTED"'
expect "attendance POST /api/v1/attendance/events"           POST "$GATEWAY/api/v1/attendance/events"         501 '"code":"NOT_IMPLEMENTED"' '{}'
expect "reporting  GET  /api/v1/reports/dashboard"           GET  "$GATEWAY/api/v1/reports/dashboard"         501 '"code":"NOT_IMPLEMENTED"'
expect "kiosk      POST /api/local/scan (via gateway)"       POST "$GATEWAY/api/local/scan"                   501 '"code":"NOT_IMPLEMENTED"' '{}'

echo "[5] gateway hygiene"
expect "unknown /api path is a JSON 404 (and cannot loop)"   GET  "$GATEWAY/api/v1/nothing-here"             404 '"code":"NOT_FOUND"'
expect "unknown service probe is a JSON 404"                 GET  "$GATEWAY/services/nothing/health"         404 '"code":"NOT_FOUND"'

echo "[6] dev-only fixtures exist ONLY because the dev profile is on"
expect "central dev ingestion rejects an empty event"        POST "$GATEWAY/api/dev/attendance/events"       400 '"code":"VALIDATION_ERROR"' '{}'
expect "kiosk dev queue readable"                            GET  "$GATEWAY/api/dev/kiosk/queue"             200 '"counts"'

echo
if [ "$FAILS" -eq 0 ]; then
  echo "SMOKE PASSED: $PASSES checks, 0 failed."
  exit 0
fi
echo "SMOKE FAILED: $FAILS failed, $PASSES passed."
echo "Hint: docker compose ps ; docker compose logs --tail=80 <service>"
exit 1
