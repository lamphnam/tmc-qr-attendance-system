# tests/load — T01 script written, NOT_RUN

Owner: Nam (with Khánh for the kiosk side). Nothing here has measured NFR-1 or NFR-7 yet. Workloads, thresholds and rules: [docs/TESTING.md](../../docs/TESTING.md) T01 and T06.

Tool: **k6** (checked with k6 v1.7.1).

| File | What it is | Status |
|---|---|---|
| `k6/peak-scan-load.js` | T01 / NFR-1: `POST /api/local/scan` on the kiosk, one fresh valid QR per scan | **NOT_RUN**: the scan answers 501 until Khánh's work is merged. `selftest` passed; `quick` on the live stack passed the fixture and TOTP checks, then aborted on the 501 as designed |
| `fixtures/load-employees.sql` | 1000 synthetic ACTIVE employees `LOAD-BD-0001..1000` for factory BD | Loaded on 2026-11-17 (1000 rows in 1.5 s, rerun inserts 0); whole audit chain re-verified ([STATUS §19](../../docs/STATUS.md#19-full-run-on-develop-with-docker-2026-11-17)) |
| `k6/health-baseline.js` | Layout placeholder that only calls a health endpoint; says nothing about NFR-1 | NOT_TESTED |

## Why a fixture

The kiosk accepts each (employee, 30 s step) only once. The seed has 4 ACTIVE employees in BD, so a 30-minute run can produce at most about 240 valid scans, not the 800 that T01 asks for. With 1000 fixture employees, every scan in a run uses a different employee and anti-replay never rejects one.

The fixture is **not** in `init-db/seed.sql`. It follows the B27 rule for writers outside the application: each insert bumps `factories.whitelist_version` and appends an audit row to the SHA-256 chain in the same transaction. Its secrets are public dummy values derived from the ID (`TMC_LOAD_BD_0001_V1`), and the k6 script derives them the same way.

## Scenarios

| `SCENARIO` | Load | Use |
|---|---|---|
| `steady` | 800 scans spread over 30 min | The NFR-1 workload |
| `burst` | 800 scans in 2 min | Stress; report it separately from `steady` |
| `quick` | 20 scans in 1 min | Check the setup before a long run; not evidence |
| `selftest` | none | TOTP vectors only, no network |

Thresholds (k6 exits non-zero if one fails):
- scan p95 and p99 ≤ 2000 ms;
- HTTP errors below 1 %;
- every scan `ALLOWED`;
- no dropped iterations, meaning k6 delivered the whole stated load.

The summary also reports:
- `scan_within_2s` (pass ratio);
- `kiosk_processing_ms` (the kiosk's own `processingTimeMs`);
- `gate_command_sent`. This only means the **mock** turnstile accepted the command. It does not mean a gate opened.

`setup()` refuses to measure an unprepared stack. It aborts in four cases:
- the fixture is missing;
- user-qr's TOTP differs from the script's;
- the scan answers 501;
- a probe scan is not `ALLOWED` after waiting up to `WHITELIST_WAIT_S` seconds for the kiosk's whitelist sync.

## Run (Git Bash / WSL)

The scans create real attendance rows (not `dev_fixture`) that reports and the HR export will count. Reset the database before and after.

```bash
docker compose down -v
export KIOSK_WHITELIST_SYNC_ENABLED=true KIOSK_WHITELIST_SYNC_INTERVAL_MS=5000
export KIOSK_WHITELIST_KEY_BASE64="$(node -e "console.log(require('crypto').randomBytes(32).toString('base64'))")"
docker compose up --build -d --wait
docker compose exec -T central-db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < tests/load/fixtures/load-employees.sql
node --test tests/integration/user-qr.test.mjs     # optional: re-checks the audit chain, now including fixture rows

SCENARIO=quick  k6 run tests/load/k6/peak-scan-load.js
SCENARIO=steady k6 run --summary-export tests/load/results/steady.json tests/load/k6/peak-scan-load.js
SCENARIO=burst  k6 run --summary-export tests/load/results/burst.json  tests/load/k6/peak-scan-load.js
docker compose down -v
```

`tests/load/results/` is git-ignored. Copy the numbers into `docs/STATUS.md` together with:
- the exact command;
- the k6 exit code;
- the machine (one-machine Compose, see Known limitations in DECISIONS).

Other environment variables:
- `KIOSK_URL` (default `http://127.0.0.1:8085`) and `GATEWAY_URL` (default `http://127.0.0.1:8080`);
- `FACTORY_ID` (default `BD`);
- `GATES` (default `BD-G01:IN,BD-G02:OUT`);
- `LOAD_EMPLOYEES` (default 1000; must match the fixture);
- `WHITELIST_WAIT_S` (default 120).
