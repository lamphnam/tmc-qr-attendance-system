# tests/integration

Black-box tests against a **running** Compose stack. They assert on state (PostgreSQL rows via `psql`, the kiosk's SQLite
queue, commands counted by the mock turnstile), never on "HTTP 200" alone.

```bash
docker compose up --build -d --wait
node --test tests/integration/slice.test.mjs          # Node >= 20, no npm install needed
SKIP_DISRUPTIVE=1 node --test tests/integration/slice.test.mjs
```

| Test | Disruptive | Proves |
|---|---|---|
| same `attendanceId` twice, batch repeat, conflict | no | central idempotency by **row count** (dev fixture endpoints) |
| production route answers 501 | no | no fake success on the unimplemented path |
| SQLite file on the kiosk volume | no | `kiosk.db` exists in `/data` |
| kiosk offline -> PENDING -> reconnect -> SYNCED | no | ADR-01 slice: local turnstile works while central sync is off |
| central **stopped** + kiosk **restarted** | **yes** (stops `attendance-processor`, restarts `kiosk-agent`) | PENDING survives a process restart, retries, lands once |
| mock HR re-send + container restart | **yes** (restarts `mock-hr-payroll-api`) | receiver-side dedupe and persistence |

Scope warning: events are **synthetic** (no QR, no TOTP, no authentication). Passing this suite does **not** demonstrate
NFR-1/2/3/6, ADR-02 or ADR-03. Owners add their real tests next to this file.

Environment overrides: `GATEWAY_URL`, `MOCK_TURNSTILE_URL`, `MOCK_HR_URL`, `POSTGRES_USER`, `POSTGRES_DB`.
