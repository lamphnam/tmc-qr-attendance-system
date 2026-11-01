# STATUS — what exists, what was verified

Snapshot of **2026-11-01** right after the bootstrap. Every result below comes from a command that was actually run on the bootstrap machine; what was not run is `NOT_TESTED`. Re-run the commands on your machine before trusting this. Requirement-level status is in [TRACEABILITY](TRACEABILITY.md); remaining work in [TODO](TODO.md); product limitations in [DECISIONS](DECISIONS.md#known-limitations).

## Status labels

Canonical vocabulary (used by the module matrix, TRACEABILITY and `web-frontend/src/catalog.ts`).

| Label | Meaning |
|---|---|
| `READY` | runs for real and is tested |
| `DEV_SLICE` | real logic, reachable only through unauthenticated dev-only endpoints with synthetic data |
| `SCAFFOLD` | boots, real health, routes answer HTTP 501 |
| `MOCK` | stand-in for an external system |
| `NOT_IMPLEMENTED` | no code yet |
| `NOT_TESTED` | code exists but was never verified |

## 1. Environment used to verify

| Item | Value |
|---|---|
| Machine | Windows 11 Pro 10.0.26300; Docker Desktop (Engine **29.4.0**, Compose **v5.1.1**) |
| Java | `java` is 21.0.11, but the machine's **`JAVA_HOME` is JDK 17**, so Maven was run with it overridden to `C:\Program Files\Java\jdk-21.0.11` (`make check-java` guards this) |
| Maven / libraries | Maven 3.9.14 · Spring Boot **3.5.16** · sqlite-jdbc 3.49.1.0 · ArchUnit 1.4.1 · Testcontainers 1.21.4 (managed by the Spring Boot BOM) |
| Node (host) | 24.13.1, npm 11.21.0 · React 19.2.9, Vite 7.3.7, TypeScript 5.9.3, Vitest 3.2.7 |
| Runtime images | `eclipse-temurin:21-jre`, `node:22-alpine`, `postgres:16-alpine`, `nginx:1.27-alpine` (tags only, **not pinned by digest**) |
| Not tried | Linux, macOS, plain WSL, CI |

## 2. Module matrix

| Module | Owner | Status | Entry / health | What really works and is tested | Not there |
|---|---|---|---|---|---|
| `central-db` (PostgreSQL 16) | shared | **READY** | `pg_isready -h 127.0.0.1`; host `127.0.0.1:5433` | `init-db/schema.sql` + `seed.sql` load on an empty volume: 11 tables, 2 factories, 2 kiosks, 3 gates, 4 synthetic employees, 6 roles, 0 users | versioned migrations (D02/B01), restricted DB roles (B14) |
| `nginx-gateway` | Nam / Khoa | **READY** (routing only) | `/gateway/health` | `/services/*/health`, every `/api/v1/*` area reaches its service, JSON 404 for unknown paths (prevents a proxy loop), `X-Request-Id` | TLS/mTLS, authentication, rate limit, CORS allowlist |
| `user-qr-service` | Khoa | **SCAFFOLD** | `/services/user-qr/health` | boots; health reflects real PostgreSQL; `GET /api/v1/qr/generate` and `/api/v1/kiosk/whitelist` answer **501** | JWT/RBAC, TOTP, whitelist, HR sync, revocation, audit chain, MFA |
| `gate-station-service` | Khôi | **SCAFFOLD** | `/services/gate-station/health` | boots + real health; `POST /api/v1/gates`, `GET …/status`, `POST …/manual-open` answer **501** | registration, heartbeat, manual open + audit |
| `attendance-processor` | Khôi | **DEV_SLICE** | `/services/attendance/health` | real idempotent ingestion: single event + batch, per-event ack after commit, SAME vs CONFLICT, 503 when the DB is down, safe under 8 threads on one id. Only via `/api/dev/attendance/*`; `/api/v1/attendance/*` answer **501** | authenticated production routes, rejected scans (D06), reference validation |
| `reporting-service` | Nam | **SCAFFOLD** | `/services/reporting/health` | boots + real health; 3 report/dashboard/hr-export routes answer **501**; `tmc.hr-api.base-url` configured | reports, dashboard, HR export scheduler/retry |
| `kiosk-agent` | Khánh | **DEV_SLICE** | `/services/kiosk/health`; host `8085` | SQLite WAL + `synchronous=FULL`; PENDING committed before the turnstile call; sync by per-id ack with backoff; FAILED only on a per-event rejection; the offline switch cuts only kiosk → central; health reads the real SQLite file. `POST /api/local/scan` answers **501** | QR scan, TOTP, whitelist cache, anti-replay (`used_tokens` table unused), rejected scans, heartbeat, manual open, auth to central |
| `mock-turnstile-api` | Khánh | **MOCK** | `/health`; host `8090` | `POST /gates/{id}/open`, command counting, failure injection; always `physicalOpenConfirmed:false` | contract is invented by the bootstrap (B18) |
| `mock-hr-payroll-api` | Nam | **MOCK** | `/health`; host `8091` | idempotent upsert by `attendanceId`, `duplicateBatch`, 4xx/5xx injection, state survives a container restart, `GET /mock/hr/employees` | HR contract is DRAFT (D09/D10) |
| `web-frontend` | Khiêm | **SCAFFOLD** | `/` (gateway 8080 or host 3000) | *Status* page (real health every 10 s), *Dev slice* page, five placeholders labelled **Not implemented**; typecheck + build + 17 tests incl. SSR render of every route | login, QR, simulator, every business UI. **Never opened in a real browser** |
| `tests/integration` | Nam | **READY** (dev slice) | see README §4 | 8 tests asserting PostgreSQL rows, SQLite queue state and turnstile commands | tests for the real features (owners add) |
| `tests/load` | Nam | **NOT_IMPLEMENTED** | — | README + `health-baseline.js` (**NOT_TESTED**, k6 not installed) | NFR-1 / NFR-7 |
| `scripts/smoke.sh`, `smoke.ps1` | Nam | **READY** | — | 29 checks each; non-zero exit on any failure | — |
| `Makefile` | Nam | **READY** (partly) | — | run through `make`: `help`, `config`, `smoke`, `check-java` (refuses JDK 17, accepts 21). `test`, `up`, `integration`, `reset` not run through `make` (the commands behind them were run directly) | — |

## 3. Commands run and results

| # | Command | Exit | Result |
|---|---|---|---|
| 1 | `mvn -B -ntp test` (root, JDK 21) | **0** | BUILD SUCCESS, **76 tests, 0 failed, 0 skipped**: user-qr 4 · gate-station 4 · attendance-processor 27 · reporting 4 · kiosk-agent 37 · 1 m 25 s. `Skipped: 0` ⇒ the Testcontainers test **did run** against real PostgreSQL 16 |
| 2 | `npm run typecheck` / `npm test` / `npm run build` (web-frontend) | 0 / 0 / 0 | 17 tests pass (7 health + 10 render/catalog); bundle 241 kB (gzip 77 kB) |
| 3 | `npm test` in both mocks | 0 / 0 | 6/6 (turnstile) and 11/11 (HR) pass |
| 4 | `docker compose config --quiet` (also `--env-file .env.example`) | 0 | 10 valid services |
| 5 | `docker compose up --build -d --wait`, first try | **1** | all images built (3 m 31 s) but `central-db` could not start: host port 5432 was held by an unrelated container `pmc-ticket-postgres` (left untouched). Default host port changed to 5433 (B16) |
| 6 | `docker compose up -d --wait` after that change | **0** | all 10 containers `healthy` (53 s, images cached) |
| 7 | `docker compose down -v`, then `up --build -d --wait` from empty volumes | 0 / 0 | 38.8 s; `information_schema` shows 11 tables and `employees=4` ⇒ init-db works on an empty volume |
| 8 | `bash scripts/smoke.sh` | **0** | `SMOKE PASSED: 29 checks, 0 failed` (run 3 times, including after the clean start) |
| 9 | `powershell -File scripts\smoke.ps1` | 1 → **0** | first run 12 failures from two Windows PowerShell 5.1 quirks (below); after the fix 29/29 |
| 10 | `node --test tests/integration/slice.test.mjs` | 1 → **0** | first run 7/8 (the test's own assertion was wrong); after the fix **8/8** in 36.7 s, including a really stopped `attendance-processor` and a restarted `kiosk-agent` |
| 11 | DB inspection (run **before** step 7, on the stack holding data from the integration tests) | 0 | `psql \dt` → 11 tables; seed counts; `kiosk.db` read **independently** with Python `sqlite3` in a separate container: `journal_mode=wal`, 4 tables, 4 `SYNCED` events, `max retry_count=2`, `integrity_check=ok` |
| 12 | `docker compose logs` of all 10 containers | — | 0 lines matching `ERROR`/`exception`/`emerg`/`FATAL`; the only `WARN` is the intentional DEV banner |
| 13 | `git add -A` preview (git-dir kept outside the repo; **no `.git` created**) | 0 | 180 files would be tracked; no `.env`, `*.db`, `target/`, `node_modules/`, `dist/`, `*.jar`; secret scan found only `dev-only-change-me` |
| 14 | `make help` / `config` / `smoke` / `check-java` | 0 / 0 / 0 / **2** on JDK 17, 0 on JDK 21 | the guard works both ways |
| 15 | `node scripts/verify.mjs --down` (after the docs consolidation of the same day; shell `JAVA_HOME` was JDK 17, the script found JDK 21 itself) | **0** | all 8 stages PASS: java 76 tests / 0 skipped · frontend 17 · mocks 6 + 11 · docs check · compose config · stack up (95 s) · smoke 29/29 · integration 8/8 |

### Findings fixed during verification
1. **Maven ran on JDK 17** because `JAVA_HOME` pointed there → documented in README and guarded by `make check-java`.
2. **Real bug in `AttendanceIngestionService`:** with PostgreSQL unreachable, the transaction template threw `CannotCreateTransactionException` before the repository ran, so it was never translated to a retryable error → HTTP 500 instead of 503. Caught by `AttendanceProcessorApplicationTest`; fixed with a unit test.
3. **Port 5432 collision** → DB host port 5433; all published ports bind `127.0.0.1`.
4. **`smoke.ps1`** on PowerShell 5.1: `.Content` is `byte[]` for `application/vnd.*+json`, and error bodies live in `ErrorDetails.Message`.
5. **Frontend typecheck** needed `@types/node` for `vite.config.ts`.
6. **Integration test:** the test's expected `'t'` was wrong (`'true'` when a boolean is concatenated in SQL); the product behaved correctly.

## 4. Not verified / caveats of this verification

- `NOT_TESTED`: the UI in a real browser (only SSR render tests, served HTML and a build exist); Linux/macOS; CI; k6; `make test|up|integration|reset`.
- Mock and Java HTTP tests use JDK `HttpServer` / Node over loopback, **not** a real turnstile. Mock latency says nothing about hardware.
- Docker image builds run with `-DskipTests` (tests run on the host/CI via `mvn test`); a successful image build is not evidence of passing tests.
- Harmless warnings: Mockito "self-attaching" agent (a future JDK will forbid it); npm `install-scripts` notice for esbuild.

## 5. Dev-only / mock items (must not reach a "production" build)

`/api/dev/attendance/*` and `/api/dev/kiosk/*` (profile `dev`, unauthenticated) · the two matching `location` blocks in `nginx-gateway/nginx.conf` · `QueueSyntheticEventUseCase` (synthetic events, no QR/TOTP) · column `attendance_events.dev_fixture` · `mock-turnstile-api`, `mock-hr-payroll-api` · password `dev-only-change-me` · `HEALTH_SHOW_DETAILS=always` · `kiosk.central.sync-path=/api/dev/attendance/sync`.
