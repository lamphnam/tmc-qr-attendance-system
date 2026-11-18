# STATUS — what exists, what was verified

Bootstrap snapshot of **2026-11-01**, with later verification records dated **2026-11-14** (local UTC+7); the `reporting-service` row and §3 commands 16–18 were added on **2026-11-15** (branch `feat/reporting-service`, run before the merge with User/QR and Kiosk); the `web-frontend` row and §3 commands 19–23 (numbered 16–20 on branch `feat/frontend-main-flow`) on **2026-11-14**. All four feature branches were merged into `develop` on **2026-11-16**; the initial merged run is [§17](#17-merged-develop-2026-11-16), followed by K1–K4 alignment and verification in [§18](#18-userqr-k1k4-contract-alignment-2026-11-16). The Kiosk and User/QR results in §§6–8 belong to separate pre-merge snapshots and must not be added together. Latest Attendance Processor handoff evidence: [§13](#13-attendance-processor-postgresql-recheck-2026-11-14); this module run does not establish a new whole-system result. Earlier runs remain historical. Requirement-level status is in [TRACEABILITY](TRACEABILITY.md); remaining work in [TODO](TODO.md); product limitations in [DECISIONS](DECISIONS.md#known-limitations).

## Status labels

Canonical vocabulary (used by the module matrix, TRACEABILITY and `web-frontend/src/catalog.ts`).

| Label | Meaning |
|---|---|
| `READY` | runs for real and is tested |
| `DEV_SLICE` | real partial functionality exposed only in dev with synthetic data; production authentication/authorization is incomplete |
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
| `central-db` (PostgreSQL 16) | shared | **READY** | `pg_isready -h 127.0.0.1`; host `127.0.0.1:5433` | canonical schema/seed tested on PostgreSQL: 11 tables, 2 factories, 2 kiosks, 3 gates, 9 synthetic employees, 6 roles, 0 users; original attendance tables preserved | versioned migrations (D02/B01), restricted DB roles (B14) |
| `nginx-gateway` | Nam / Khoa | **READY** (routing only) | `/gateway/health` | `/services/*/health`, every `/api/v1/*` area reaches its service, JSON 404 for unknown paths (prevents a proxy loop), `X-Request-Id` | TLS/mTLS, authentication, rate limit, CORS allowlist |
| `user-qr-service` | Khoa | **DEV_SLICE** | `/services/user-qr/health` | dev-only employee-ID QR lifecycle, RFC 6238 TOTP, key-protected factory whitelist, transactional audit chain; K1–K4 implementation and current verification in §18. Required factory scope, consistent `X-Whitelist-Version` including empty arrays, `qrPayload`, `periodSeconds:30`, `409 QR_REVOKED`. Default profile and `/qr/generate` remain 501 | JWT/RBAC, production kiosk identity/factory authorization, Central encryption at rest, HR sync/public membership API, restricted audit DB role/external anchor, MFA |
| `gate-station-service` | Khôi | **SCAFFOLD** | `/services/gate-station/health` | boots + real health; `POST /api/v1/gates`, `GET …/status`, `POST …/manual-open` answer **501** | registration, heartbeat, manual open + audit |
| `attendance-processor` | Khôi | **DEV_SLICE** | `/services/attendance/health` | real idempotent ingestion and dev-only history GET via `/api/dev/attendance/*`; history excludes fixtures. H1 dev-only `GET /api/v1/attendance/events` supplies `/demo` with fixtures, newest first. Latest H1/H2 verification: §20; earlier PostgreSQL evidence: §§13/19. Production ingestion POST routes answer **501**; no production History GET route | authenticated production routes, authorized history access, end-to-end rejected scans (D06), reference-validation policy |
| `reporting-service` | Nam | **DEV_SLICE** | `/services/reporting/health` | (2026-11-15, §3 rows 16–18) factory report and dashboard in Asia/Ho_Chi_Minh days, ALLOWED-only, fixtures excluded unless opted in; monthly HR export with a period-derived `batch_uuid`, frozen items, 01:00 schedule with catch-up, backoff to the period cutoff, FAILED + log alert, compare-and-set claims; two payroll channels (`rest`, `file`) under one contract test. Only via `/api/dev/reports/*`; `/api/v1/reports/*` answer **501** | authentication/roles, shift dimension, mail alert, re-export of late events, NFR-7 measurement |
| `kiosk-agent` | Khánh | **DEV_SLICE** | `/services/kiosk/health`; host `8085` | Real dev QR scan using local whitelist/TOTP, atomic `used_tokens` + PENDING commit before gate command, per-ID ack/backoff and local rejected-scan audit. AES-GCM cache, H2 manual refresh and H3/H4 startup/key provisioning. Evidence: [§22](#22-dev-qr-scan-2026-11-18) | Audit retention/central replication (D06), production key lifecycle/auth, heartbeat, manual open |
| `mock-turnstile-api` | Khánh | **MOCK** | `/health`; host `8090` | `POST /gates/{id}/open`, command counting, failure injection; always `physicalOpenConfirmed:false` | contract is invented by the bootstrap (B18) |
| `mock-hr-payroll-api` | Nam | **MOCK** | `/health`; host `8091` | idempotent upsert by `attendanceId`, `duplicateBatch`, 4xx/5xx injection, state survives a container restart, `GET /mock/hr/employees` | HR contract is DRAFT (D09/D10) |
| `web-frontend` | Khiêm | **DEV_SLICE** | `/` (gateway 8080 or host 3000); `/demo` | *Status* page (real health every 10 s), *Dev slice* page, five placeholders labelled **Not implemented**. `/demo` has employee QR image, kiosk controls/queue and central inspection. User/QR's dev response matches QR expectations (§18). H1/H2 now supply the existing frontend's Central list/manual whitelist paths ([API-CONTRACTS §8](API-CONTRACTS.md#8-demo-main-flow-routes-draft)); backend contract tests in §20, no frontend source change. 118 Vitest tests re-run in §18; historical Chrome layout checks in §3 | login, Guard/HR/Admin pages and production auth; browser acceptance recorded in §23 |
| `tests/integration` | Nam | **READY** (dev slice) | see README §4 | 19 Compose cases passed with 0 skipped, including real scan, rejection, browser-compatible routes, 20-scan restart and reporting. [Evidence](#23-m2-follow-up-and-compose-acceptance-2026-11-18) | Authenticated production scenarios |
| `tests/load` | Nam | **DEV_SLICE** | k6 and BD/date report probe | T01 BD steady and T06 BD/date measured against fresh dev Compose data. [Results](#23-m2-follow-up-and-compose-acceptance-2026-11-18) | Physical gate, LA deployment, report shift and role checks |
| `scripts/smoke.sh`, `smoke.ps1` | Nam | **READY** | — | §18: 35 checks per script, exit 0; non-zero exit on any failure | — |
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
| 16 | `mvn -B -ntp -pl backend-services/reporting-service test` (2026-11-15, JDK 21.0.11, Docker Engine 29.4.0) | **0** | **89 tests, 0 failed, 0 skipped**: domain 26, use case 15, payroll channels 29 (incl. 5 shared contract clauses × 2), dev profile 4, PostgreSQL 16 Testcontainers 8, app 3, ArchUnit 4. A first run had 1 failure from a wrong test expectation (null fields are omitted, not `null`); fixed in the test. Mutation checks: `ACKNOWLEDGED` from the file channel fails 3 contract tests; a `JdbcTemplate` field in `HrExportService` fails ArchUnit |
| 17 | `node scripts/verify.mjs` (2026-11-15, full, after the reporting change) | **1** | java **161 tests / 0 skipped**, frontend, mocks 6 + 11, docs, compose config, stack up, smoke **31/31** PASS; integration **10/11**: `slice.test.mjs` "central really DOWN and a kiosk RESTART" read `/api/dev/kiosk/queue` right after the kiosk health turned UP and got no `events` (it passed in an earlier run the same day). An earlier full run that day failed 2 tests for two causes since fixed: the reporting env vars had been added to the wrong Compose service, and `node --test` ran both integration files in parallel (now `--test-concurrency=1`) |
| 18 | `node scripts/verify.mjs --only integration` (right after row 17, same stack) | **0** | **11/11**, 0 skipped: `slice.test.mjs` 8 + `reporting.test.mjs` 3 (manual export to the real mock HR with the Node-recomputed batch id, 503 then recovery on the same batch, dashboard through the gateway) |
| 19 | `npm run typecheck` / `npx vitest run` / `npm run build` (web-frontend, **2026-11-14**, Arch Linux, Node 26.13.0, npm 12.2.2; `/demo` work) | 0 / 0 / 0 | **118 tests, 10 files, 0 skipped**; bundle 286 kB (gzip 92 kB) |
| 20 | `docker compose build web-frontend` + `up -d web-frontend` (same machine, stack on `GATEWAY_HOST_PORT=9090`) | 0 / 0 | `npm ci` in the image succeeds with the new lockfile; container `healthy`; `GET /demo` through the gateway → 200 |
| 21 | `GATEWAY_URL=http://localhost:9090 bash scripts/smoke.sh` | **0** | 29/29 |
| 22 | `GATEWAY_URL=http://localhost:9090 node --test tests/integration/slice.test.mjs` | **0** | 8/8, 0 skipped. Without `GATEWAY_URL` the test targets 8080 and fails with 404 (environment, not code) |
| 23 | `/demo` opened in Chrome through Chrome DevTools, against the real stack | — | three columns side by side at 1440/1366 px, stacked at 1280/390 px, no horizontal overflow; offline → PENDING, reconnect → SYNCED once, resend → `ALREADY_EXISTS`; QR and scan show the real 404/501 (no successful scan is possible yet); console clean. **Maven was not run on this machine** (no JDK 21 / `mvn`); no Java file changed |

### Findings fixed during verification
1. **Maven ran on JDK 17** because `JAVA_HOME` pointed there → documented in README and guarded by `make check-java`.
2. **Real bug in `AttendanceIngestionService`:** with PostgreSQL unreachable, the transaction template threw `CannotCreateTransactionException` before the repository ran, so it was never translated to a retryable error → HTTP 500 instead of 503. Caught by `AttendanceProcessorApplicationTest`; fixed with a unit test.
3. **Port 5432 collision** → DB host port 5433; all published ports bind `127.0.0.1`.
4. **`smoke.ps1`** on PowerShell 5.1: `.Content` is `byte[]` for `application/vnd.*+json`, and error bodies live in `ErrorDetails.Message`.
5. **Frontend typecheck** needed `@types/node` for `vite.config.ts`.
6. **Integration test:** the test's expected `'t'` was wrong (`'true'` when a boolean is concatenated in SQL); the product behaved correctly.
7. **Flaky test (2026-11-15):** the kiosk restart case of `slice.test.mjs` can query the queue a moment after health reports UP and fail; seen once in row 17, passed in row 18. Not fixed (not this branch's module); a retry inside `kioskEvent` would remove the race.

## 4. Not verified / caveats of this verification

- `NOT_TESTED`: the *Status*, *Dev slice* and placeholder pages in a real browser (only `/demo` was exercised there, row 23); macOS; CI; k6; `make test|up|integration|reset`. Linux was used only for the web-frontend rows 19–23.
- Mock and Java HTTP tests use JDK `HttpServer` / Node over loopback, **not** a real turnstile. Mock latency says nothing about hardware.
- Docker image builds run with `-DskipTests` (tests run on the host/CI via `mvn test`); a successful image build is not evidence of passing tests.
- Harmless warnings: Mockito "self-attaching" agent (a future JDK will forbid it); npm `install-scripts` notice for esbuild.

## 5. Dev-only / mock items (must not reach a "production" build)

H1 adds `GET /api/v1/attendance/events` as an explicit **dev-profile-only** path exception; H2 adds
`POST /api/dev/kiosk/whitelist/sync`. Both are unauthenticated, synthetic-data-only (B29).

`/api/dev/attendance/*`, `/api/dev/kiosk/*`, `/api/dev/reports/*` and `POST /api/local/scan` (profile `dev`, unauthenticated) · the three matching `location` blocks in `nginx-gateway/nginx.conf` · `REPORTING_INCLUDE_DEV_FIXTURES=true` in Compose (reports and the HR export count fixture rows, B26) · `QueueSyntheticEventUseCase` (synthetic events, no QR/TOTP) · column `attendance_events.dev_fixture` · `mock-turnstile-api`, `mock-hr-payroll-api` · password `dev-only-change-me` · `HEALTH_SHOW_DETAILS=always` · `kiosk.central.sync-path=/api/dev/attendance/sync`. · `web-frontend` `/demo` (calls the dev routes above; uses the synthetic employees EMP001–EMP004)

User/QR dev-only inventory (B21/B22): `/api/v1/qr/{employeeId}` and lifecycle/audit routes,
`/api/v1/kiosk/whitelist` with a dummy kiosk key in Compose, public dummy seed QR secrets,
untrusted actor metadata. Requested v1 paths are an explicit demo exception; no profile enables production auth.

## 6. Kiểm chứng D08 tại kiosk (2026-11-14)

Bằng chứng trước merge trên branch kiosk; các lệnh/kết quả dưới đây thuộc snapshot triển khai D08, không phải lần chạy trên working tree hợp nhất.

Nhãn module vẫn là **DEV_SLICE**; không thay đổi nhãn dashboard. Phần bảo vệ material trong SQLite đã được triển khai theo [D08](DECISIONS.md#d08-kiosk-secret-protection-2026-11-14), không đồng nghĩa scan/TOTP hoặc HTTP whitelist sync đã hoàn thành. Cách cấp khóa và giới hạn khôi phục/rotation: [kiosk README](../kiosk-agent/README.md#local-whitelist-key-d08).

Môi trường thực chạy: JDK 23.0.1, compiler release 21, Maven 3.9.16. Khóa trong test được sinh ngẫu nhiên tạm thời; không dùng khóa production.

| Lệnh | Exit code | Kết quả |
|---|---|---|
| `mvn -B -ntp -pl kiosk-agent -Dtest=WhitelistSecretCodecTest,WhitelistSecretConfigTest,SQLiteWhitelistRepositoryTest test` | 0 | 74 PASS, 0 FAIL/ERROR/SKIP: codec 27, configuration 3, SQLite 44 |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 138 PASS, 0 FAIL/ERROR/SKIP; gồm 3 Kiosk ArchUnit tests và regression queue/sync |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 11 PASS, 0 FAIL/ERROR/SKIP trong 5 module; 3 Kiosk tests trùng tập trên, không cộng thành 149 test khác nhau |
| `node scripts/check-docs.mjs` | 0 | Các link tài liệu hợp lệ |
| `git diff --check` | 0 | Không lỗi whitespace |

Test thực dùng AES-GCM/JCE và SQLite file: round-trip/reopen, nonce mới, sai/thiếu khóa, sửa envelope/ngữ cảnh, rollback khi lỗi mã hóa hoặc transaction, đọc nhất quán khi writer commit, giữ nguyên attendance queue/used_tokens/metadata khác. Không chạy lại PostgreSQL integration trong lượt D08 này; các module backend không đổi. Cảnh báo khởi tạo bean trong test khóa không hợp lệ là nhánh lỗi chủ động kiểm tra, chỉ có thông báo cố định an toàn.

## 7. Whitelist Sync Application Core (2026-11-14)

Bằng chứng trước merge trên branch kiosk sau bước Application Core. Tổng 161 thay cho snapshot Kiosk 138 ở §6; không cộng hai lượt hoặc cộng với User/QR thành kết quả kiểm thử mới.

Đã thêm `IWhitelistSnapshotSource`, `SyncWhitelistUseCase` và `WhitelistSyncReport`; [class map](TRACEABILITY.md#kiosk-agent-khánh) và [ranh giới tích hợp](../kiosk-agent/README.md#whitelist-sync-application-boundary) mô tả phần thực có. Nhãn kiosk vẫn **DEV_SLICE**, không đổi dashboard. Chưa có HTTP adapter, scheduler hoặc bean Whitelist Sync tự chạy; không thay đổi Attendance Sync, schema hay API contract.

| Lệnh | Exit | Kết quả thực chạy |
|---|---|---|
| `mvn -B -ntp -pl kiosk-agent -Dtest=SyncWhitelistUseCaseTest test` | 0 | 23 PASS, 0 FAIL/ERROR/SKIP; Central được script, SQLite/AES thật |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 161 PASS, 0 FAIL/ERROR/SKIP; gồm 23 test mới và 3 Kiosk ArchUnit tests |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 11 PASS, 0 FAIL/ERROR/SKIP trong 5 module; không cộng lặp 3 Kiosk tests vào tổng trên |
| `git diff --check` | 0 | Không lỗi whitespace |
| `node scripts/check-docs.mjs` | 0 | Link tài liệu hợp lệ, không cảnh báo dòng trùng |

Môi trường vẫn JDK 23.0.1 / compiler release 21. Test bao phủ offline trước/trong fetch, scope sai, dữ liệu không hợp lệ, version cũ/bằng và version zero, snapshot rỗng, timeout/failure, chống sync chồng trong fetch/commit và gọi đệ quy, writer khác cập nhật trong lúc tải, reopen, thiếu khóa, cache hỏng và commit failure rollback. `UPDATED` chỉ xuất hiện sau durable commit; kết quả không chứa dữ liệu nhân viên hoặc exception text. Một lần chờ công cụ bị timeout dù log Maven đã BUILD SUCCESS; đã chạy lại để xác nhận exit 0, lưu log/mã thoát trong `kiosk-agent/target/whitelist-sync-tests.*`. Chưa kiểm chứng HTTP Whitelist của Central hoặc triển khai nhiều process; guard yêu cầu dùng chung một use-case instance trong kiosk.
## 8. User/QR verification — 2026-11-14

Incoming branch evidence recorded before this merge. The 40/41 module runs and root 112-test run below refer to that branch's own snapshots and environment. They are retained as historical evidence, not a new combined-tree PASS; the seven PostgreSQL cases are included in the module totals.

Windows host lacks JDK 21/Maven on PATH (JDK 23 installed; WSL has JDK 17).
Ran Maven in `maven:3.9-eclipse-temurin-21` (Java 21.0.12.1), mounting this workspace,
the Maven cache and Docker socket, with `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.

- `mvn -B -ntp -pl backend-services/user-qr-service test` inside that container: **exit 0**,
  40 tests, 0 failures/errors, **0 skipped**. Includes 7 real PostgreSQL 16 Testcontainers cases,
  concurrent same/different employee rotations, atomic rollback on audit failure and real row tampering.
- `node scripts/verify.mjs --down`: **exit 0**, all 8 stages passed. A temporary ignored Maven
  command shim delegated to the JDK 21 container (the host JDK was not substituted for Java 21).
  Used isolated `COMPOSE_PROJECT_NAME=tmc-userqr-m2`, host ports gateway 18080, frontend 13000,
  kiosk 18085, turnstile 18090, HR 18091, PostgreSQL 15433. Fresh project volumes initialized
  the changed schema/seed; existing `tmc-qr-attendance` volumes were not reset.
  Results: root Java **112 tests / 0 failed / 0 errors / 0 skipped**; frontend typecheck/build
  and **17 tests**; mock turnstile **6/6**, mock HR **11/11**; docs **0 errors / 0 warnings**;
  Compose config and all healthchecks passed; smoke **31/31**; integration **9/9, 0 skipped**,
  including the independent Node checksum implementation in `user-qr.test.mjs`.
  Node TAP output made the script display `?` counts for mocks/integration; actual totals above
  are from `.verify-logs/mocks.log` and `.verify-logs/integration.log`.
  `docker compose down` exited **0** for the isolated project, retaining its test volumes.
- Latest module run, including the additional safe HTTP error test:
  `mvn -B -ntp -pl backend-services/user-qr-service test` in
  **`maven:3.9-eclipse-temurin-21-alpine`** with the same workspace/cache/socket mounts:
  **exit 0**, **41 tests / 0 failures / 0 errors / 0 skipped**. All seven PostgreSQL cases,
  four architecture rules and both existing application tests ran. Mockito subclass configuration
  works on Alpine; log: `.verify-logs/user-qr-alpine.log`.

## 9. Kiểm tra tài liệu trong working tree đang merge (2026-11-14)

Lượt này chỉ hợp nhất nội dung tài liệu của hai branch và kiểm tra Markdown/link/anchor. Không chạy lại Maven, PostgreSQL integration hoặc Docker Compose; kiểm chứng chức năng trên working tree hợp nhất là **NOT_RUN**, không suy ra từ tổng PASS trước merge ở §§6–8.

| Kiểm tra tài liệu | Exit | Kết quả |
|---|---|---|
| Quét marker trong DECISIONS/STATUS/TRACEABILITY | 0 | Không còn marker conflict trong nội dung file |
| `git diff --check` | 0 | Không lỗi whitespace |
| `node scripts/check-docs.mjs` | 0 | Link và anchor hợp lệ, không cảnh báo dòng trùng |

Merge vẫn đang chờ người dùng review; chưa stage file hoặc tạo merge commit. Vì giữ nguyên index, Git vẫn báo ba file unmerged dù marker trong working tree đã được xử lý.

## 10. Whitelist HTTP Transport tại Kiosk (2026-11-14)

Snapshot làm việc bắt đầu từ merge commit `1dfc793`. Chỉ hoàn thành transport/DTO Infrastructure và cấu hình credential; không có snapshot-source implementation, scheduler, wiring vào use case hoặc ghi cache. Nhãn kiosk vẫn **DEV_SLICE**. Chi tiết sử dụng/giới hạn: [HTTP transport](../kiosk-agent/README.md#whitelist-http-transport-only).

| Lệnh | Exit | Kết quả đo trong lượt transport |
|---|---|---|
| `mvn -B -ntp -pl kiosk-agent -Dtest=WhitelistHttpTransportTest,WhitelistHttpConfigTest test` | 0 | 71 PASS: HTTP 69, configuration 2; 0 FAIL/ERROR/SKIP |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 232 PASS, 0 FAIL/ERROR/SKIP; gồm 71 test mới và regression queue/sync |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 13 PASS, 0 FAIL/ERROR/SKIP trong 5 module; 3 Kiosk tests đã nằm trong tổng trên |

Chạy trên JDK 23.0.1 / compiler release 21. HTTP dùng loopback server và client timeout thực; configuration test dùng Spring HTTP mock. Đã đo các nhánh 200/empty, non-200, timeout header/body, mất kết nối, malformed/missing/null/duplicate data, sai factory, credential chỉ trên request whitelist và thông báo lỗi không lộ fixture nhạy cảm. Không cần Central/Docker thực chạy; không gọi API khi khởi động. Không chạy lại PostgreSQL integration hoặc toàn bộ tests backend trong lượt này. Snapshot revision, authenticated scope và tích hợp với `SyncWhitelistUseCase` vẫn chờ contract Khoa/Khánh.

## 11. Whitelist employee mapping và conditional DI (2026-11-14)

Bắt đầu từ `35ef043`; nhãn kiosk vẫn **DEV_SLICE**. Mapper chỉ tạo employee domain, không tạo scope/snapshot/revision. Conditional wiring dùng các bean store, Clock, identity và switch có sẵn; chỉ tạo singleton use case khi source đã được đăng ký, không tạo source fallback. Ứng dụng hiện tại không có source nên không có use-case bean hoặc HTTP-to-cache flow. [Giới hạn và thứ tự cấu hình](../kiosk-agent/README.md#employee-mapping-and-conditional-wiring).

| Lệnh | Exit | Kết quả thực chạy |
|---|---|---|
| `mvn -B -ntp -pl kiosk-agent -Dtest=WhitelistEmployeeMapperTest,WhitelistSyncWiringConfigTest test` | 0 | 21 PASS: mapper 17, wiring 4; 0 FAIL/ERROR/SKIP |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 253 PASS, 0 FAIL/ERROR/SKIP, gồm 21 test mới và 3 Kiosk ArchUnit tests |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 13 PASS, 0 FAIL/ERROR/SKIP trong 5 module; không cộng lặp vào tổng module |

Môi trường JDK 23.0.1 / compiler release 21. Kiểm chứng UTF-8 chính xác, không decode Base64, defensive copy, dữ liệu không hợp lệ, redaction, singleton/backoff, guard chia sẻ và offline. Chỉ test context cung cấp source fixture có revision khai báo sẵn; persistence trong test dùng SQLite tạm và AES thật, HTTP mock không nhận request. Không chạy Central/PostgreSQL/Docker hoặc cập nhật schema/API contract. Đường ghi từ HTTP vẫn chờ revision/scope hợp lệ.

## 12. Dev attendance history GET (2026-11-14)

Lịch sử lượt triển khai đầu tiên khi Docker lỗi; kết quả chạy lại sau khi Docker phục hồi ở [§13](#13-attendance-processor-postgresql-recheck-2026-11-14).

Bắt đầu từ `98c3882`, thêm endpoint kiểm tra dữ liệu dev và DTO, tái sử dụng service/read port/SQL hiện có.
Module vẫn **DEV_SLICE**; không đổi nhãn dashboard, không thay đổi ingestion/idempotency/schema hoặc reporting-service.
Phạm vi HTTP và giới hạn dữ liệu giả: [attendance README](../backend-services/attendance-processor/README.md#dev-attendance-history).
Factory là bộ lọc do client nhập, không phải quyền truy cập; production History GET/authentication/authorization chưa được triển khai.
Test profile xác nhận không đăng ký controller khi thiếu `dev`; cả route dev lẫn production History GET trả 404 ngoài dev.

| Lệnh trong lượt này | Exit | Kết quả |
|---|---|---|
| `mvn -B -ntp -pl backend-services/attendance-processor test` trước sửa | 0 | 63 PASS, 0 FAIL, 0 ERROR, 14 SKIP; tổng 77, không coi SKIP là PASS |
| `mvn -B -ntp -pl backend-services/attendance-processor -Dtest=DevAttendanceHistoryControllerWebTest,AttendanceHistoryProfileWebTest,AttendanceProcessorApplicationTest test` | 0 | 35 PASS, 0 FAIL/ERROR/SKIP; gồm 29 WebMvc, 1 profile và 5 full-context DB-unavailable |
| `mvn -B -ntp -pl backend-services/attendance-processor test` sau sửa | 0 | 94 PASS, 0 FAIL, 0 ERROR, 16 SKIP; tổng 110. Có 31 test mới thực chạy so với baseline và 2 PostgreSQL cases mới bị SKIP |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 13 PASS, 0 FAIL/ERROR/SKIP trong 5 module; không cộng lặp vào tổng module |
| `node scripts/check-docs.mjs` | 0 | Link/anchor hợp lệ, không cảnh báo dòng trùng |
| `git diff --check` | 0 | Không lỗi whitespace |

Chạy trên JDK 23.0.1 / compiler release 21, Maven 3.9.16. Cả hai lượt full-module BUILD SUCCESS;
không cộng test focused hoặc ArchUnit vào tổng 110. Docker Desktop trả HTTP 500 cho cả `/version` và
`/containers/json?all=1`; không đổi cấu hình hệ thống để vượt qua lỗi.
Sau sửa, Testcontainers không tìm được Docker khả dụng: toàn bộ 10 history và 6 idempotency PostgreSQL cases bị SKIP,
không có PostgreSQL container thực chạy trong lượt này. Hai test mới HTTP → service → PostgreSQL đã biên dịch
nhưng chưa kiểm chứng runtime; dùng canonical schema, không thay PostgreSQL bằng H2/mock.
Kết quả PostgreSQL PASS ở các lượt trước không được dùng thay bằng chứng cho snapshot hiện tại.
Log và exit code lưu trong `backend-services/attendance-processor/target/history-api-*` (build artifacts, không stage).

## 13. Attendance Processor PostgreSQL recheck (2026-11-14)

Lượt chạy lại sau §12 trên code History GET, nay ở commit `5b1e5a2`. Module đủ bàn giao ở mức **DEV_SLICE**;
không đổi nhãn dashboard hoặc tuyên bố hoàn thành production. Môi trường: JDK 23.0.1 / compiler release 21,
Maven 3.9.16, Docker Engine 28.0.1, Testcontainers 1.21.4.

Lệnh `mvn -B -ntp -pl backend-services/attendance-processor test`: **exit 0**, BUILD SUCCESS, 1 phút 08 giây.

| Phạm vi | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|
| Toàn bộ module | **110** | **0** | **0** | **0** |
| History PostgreSQL integration | 10 | 0 | 0 | 0 |
| Idempotency PostgreSQL integration | 6 | 0 | 0 | 0 |
| Attendance ArchUnit | 2 | 0 | 0 | 0 |

Ba dòng chi tiết là tập con của tổng module. Testcontainers thực sự kết nối Docker và tạo hai PostgreSQL 16
container (`postgres:16-alpine`), nạp schema canonical; không dùng database giả và không có integration test bị SKIP.
Hai test mới đã PASS:

- `devHttpQueryUsesRealServiceAndPostgresFilteringBoundariesAndStablePages`: HTTP → service → PostgreSQL, scope/range/order/pagination.
- `devHttpQueryReturnsAnEmptyPageWhenOnlyFixturesExist`: HTTP trả trang rỗng khi chỉ có fixture.

Đối chiếu Surefire XML với log `backend-services/attendance-processor/target/history-api-postgres-retry.log`
và exit file cùng tên; đây là build artifacts cục bộ, không đưa vào Git. `git diff --check` ở lượt đó cũng exit 0.
Lượt cập nhật tài liệu hiện tại chỉ đọc lại bằng chứng này, không chạy lại Maven.
Các lượt SKIP ở §12 được giữ nguyên; không cộng số test giữa các lượt hoặc với kết quả ArchUnit toàn hệ thống.

Phạm vi nghiệm thu: HTTP/service/PostgreSQL của module đã kiểm chứng; chưa chạy lại toàn stack Kiosk/gateway
trên snapshot bàn giao. Nam cần xác nhận kịch bản tích hợp và dữ liệu reporting; Khoa/Khánh cùng Khôi cần chốt
auth, scope và event/ACK contract trước khi mở production. Hướng dẫn: [README nghiệm thu](../backend-services/attendance-processor/README.md#dev-handoff-acceptance).

## 14. Kiosk HTTP snapshot source (2026-11-14)

Bắt đầu từ `0a133e9`; Kiosk vẫn **DEV_SLICE**, không đổi nhãn dashboard. Theo contract người dùng xác nhận đã
thống nhất với Khoa, adapter đọc `X-Whitelist-Version` dương kiểu long từ cùng response với JSON array.
Chi tiết parsing, scope và lỗi: [Kiosk README](../kiosk-agent/README.md#http-snapshot-source-explicit-only).

Đã có `RestWhitelistSnapshotSource`, chưa đăng ký bean, chưa kích hoạt use case/scheduler hoặc ghi cache từ HTTP.
Central revision implementation không được giả định đã merge; chỉ mock/loopback được kiểm chứng, chưa có test với
Central thật. SQLite/AES, version guard, Attendance Sync và scan/TOTP/anti-replay/turnstile không thay đổi.

| Lệnh | Exit | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|---:|
| `mvn -B -ntp -pl kiosk-agent -Dtest=RestWhitelistSnapshotSourceTest,WhitelistHttpTransportTest,WhitelistHttpConfigTest,WhitelistSyncWiringConfigTest test` | 0 | 133 | 0 | 0 | 0 |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 311 | 0 | 0 | 0 |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 13 | 0 | 0 | 0 |
| `node scripts/check-docs.mjs` | 0 | — | — | — | — |
| `git diff --check` | 0 | — | — | — | — |

58 test mới của adapter kiểm tra revision, dữ liệu/scope/empty, UTF-8, lỗi HTTP/timeout/credential và redaction.
69 transport tests cũ giữ nguyên và PASS, gồm timeout header/body qua HTTP thật trên loopback. Wiring tests tiếp tục
xác nhận cấu hình hiện tại không cung cấp source bean. Các lượt focused là tập con, không cộng vào tổng Kiosk.
Môi trường JDK 23.0.1 / compiler release 21, Maven 3.9.16. Log/exit code: `kiosk-agent/target/whitelist-source-*`.
ArchUnit toàn hệ thống gồm 3 Kiosk tests đã nằm trong tổng 311; không cộng lặp. Link/anchor tài liệu hợp lệ,
không cảnh báo dòng trùng hoặc lỗi whitespace. Không sửa luật ArchUnit, API contract hay code Central.

## 15. Whitelist opt-in wiring (2026-11-14)

Bắt đầu từ `8a4b9f4`; Kiosk vẫn **DEV_SLICE**, không đổi nhãn dashboard. Bước adapter-only ở §14 giữ nguyên
làm lịch sử; nay Infrastructure đăng ký source và composition root đăng ký một use-case singleton khi
`KIOSK_WHITELIST_SYNC_ENABLED=true` (`kiosk.whitelist.sync-enabled`). Mặc định false ở mọi profile: không tạo
hai bean này. Bật flag chỉ chuẩn bị pipeline; không có whitelist scheduler, startup fetch, public endpoint hoặc
entrypoint vận hành mới. Attendance Sync và offline switch hiện có giữ nguyên.

`WhitelistHttpSyncWiringTest` dùng cấu hình Spring thật, HTTP mock và SQLite/AES thật: flag/profile/default/YAML
environment mapping, singleton và no-startup-work, HTTP ngoài SQLite transaction, version cũ/bằng giữ nguyên
metadata/ciphertext, empty snapshot, lỗi fetch/credential/header/scope, thiếu AES key và commit rollback, guard chung
qua fetch/commit, reopen và giữ nguyên attendance queue/used_tokens/metadata khác. Full component scan cũng được kiểm tra.

| Lệnh | Exit | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|---:|
| `mvn -B -ntp -pl kiosk-agent -Dtest=WhitelistHttpSyncWiringTest,WhitelistSyncWiringConfigTest,WhitelistHttpConfigTest test` | 0 | 30 | 0 | 0 | 0 |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 335 | 0 | 0 | 0 |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 13 | 0 | 0 | 0 |
| `node scripts/check-docs.mjs` | 0 | — | — | — | — |
| `git diff --check` | 0 | — | — | — | — |

Có 24 test mới; focused tests là tập con của tổng Kiosk. Log/exit code ở `kiosk-agent/target/whitelist-wiring-*`.
Central revision thực tế chưa được kiểm chứng; API key dev chưa ràng buộc quyền theo factory. Flag không xác thực
hay cấp quyền cho client. Không đổi Domain Contract, schema, AES codec/repository hoặc các thành phần scan/TOTP.
Hướng dẫn cấu hình/trigger: [Kiosk README](../kiosk-agent/README.md#employee-mapping-and-conditional-wiring).
ArchUnit giữ nguyên luật; 3 Kiosk architecture cases là tập con của tổng Kiosk, không cộng lặp. Docs links/anchors
và whitespace hợp lệ. Không chạy Docker Compose hoặc Central thật trong lượt này.

## 16. Whitelist scheduler (2026-11-14)

Bắt đầu từ `0d9f7d5`; Kiosk vẫn **DEV_SLICE**, không đổi dashboard. Lịch sử explicit-only wiring ở §15 giữ nguyên;
nay flag opt-in cũng tạo `WhitelistSyncScheduler`. Mặc định false, không tạo worker/fetch whitelist. Khi bật,
`KIOSK_WHITELIST_SYNC_INTERVAL_MS` mặc định 60000 ms, hợp lệ trong 1000..86400000 ms; thiếu dùng mặc định,
cấu hình sai làm startup enabled thất bại. Disabled bỏ qua interval không sử dụng.

SmartLifecycle bắt đầu fixed delay sau trọn một chu kỳ; executor riêng không đăng ký làm Spring scheduler bean,
không chiếm luồng Attendance. Scheduler chỉ gọi singleton use case; giữ nguyên offline/version/transaction guard,
không thêm sync lock, endpoint hoặc fetch tức thời. Chi tiết lifecycle/log/shutdown: [Kiosk README](../kiosk-agent/README.md#whitelist-scheduling).

| Lệnh | Exit | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|---:|
| `mvn -B -ntp -pl kiosk-agent -Dtest=WhitelistSyncSchedulerTest,WhitelistSchedulerConfigTest,WhitelistHttpSyncWiringTest,WhitelistSyncWiringConfigTest test` | 0 | 53 | 0 | 0 | 0 |
| `mvn -B -ntp -pl kiosk-agent test` | 0 | 360 | 0 | 0 | 0 |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` | 0 | 13 | 0 | 0 | 0 |
| `node scripts/check-docs.mjs` | 0 | — | — | — | — |
| `git diff --check` | 0 | — | — | — | — |

25 test mới: scheduler/lifecycle/timing 13, cấu hình 11 và 1 HTTP mock 503 → chu kỳ sau commit SQLite/AES thật.
Kiểm tra initial delay, fixed delay, long fetch không chồng, trigger explicit dùng chung guard, offline, lỗi không
làm mất lịch chạy và log không chứa diagnostic nhạy cảm. Tests wiring cũ giữ timer ở interval tối đa để tập trung
vào explicit calls. Warning khởi động trong các test interval sai là nhánh lỗi được mong đợi, không phải test ERROR.
Focused tests là tập con của tổng Kiosk. Log/exit code: `kiosk-agent/target/whitelist-scheduler-*`.
Central revision thật/factory authorization và end-to-end qua gateway chưa kiểm chứng. Không đổi Attendance,
Scan/TOTP/Anti-Replay/Turnstile, domain port, AES codec, SQLite repository/schema hoặc API contract.
ArchUnit không đổi luật; 3 Kiosk architecture cases đã nằm trong tổng 360, không cộng lặp. Link/anchor hợp lệ,
không cảnh báo dòng trùng hoặc lỗi whitespace. Không chạy Docker Compose hay Central thật trong lượt scheduler này.

## 17. Merged `develop` (2026-11-16)

`develop` = `--no-ff` merges of `feat/user-qr-service-totp` → `feat/kiosk-whitelist-sync` → `feat/reporting-service` → `feat/frontend-main-flow` (merge commit `d486668`). Only documentation and `scripts/verify.mjs` conflicted; code and `init-db/` merged without conflict. Windows 11, JDK 21.0.11, Maven 3.9.14, Node 24.13.1; **Docker Desktop was not running**.

| Command | Exit | Result |
|---|---|---|
| `mvn -B -ntp test` (root) | **0** | **604 tests, 0 failed, 31 skipped**: user-qr 41 (7 skipped), gate-station 4, attendance-processor 110 (16 skipped), reporting 89 (8 skipped), kiosk-agent 360. Every skip is a Testcontainers PostgreSQL test, so this is **not** a full PASS |
| `npm ci` / `npm run typecheck` / `npx vitest run` / `npm run build` (web-frontend) | 0 / 0 / 0 / 0 | 118 tests, 10 files |
| `node --test` in both mocks | 0 / 0 | 6/6 and 11/11 |
| `docker compose config --quiet` | 0 | valid |
| `node scripts/check-docs.mjs` | 0 | OK |

`NOT_RUN` on the merged tree: Testcontainers, `docker compose up`, smoke, integration (`slice`, `user-qr`, `reporting`). The schema of `employees`, `audit_logs` and `hr_export_*` changed, so an existing volume needs `docker compose down -v` before `node scripts/verify.mjs`.

Known cross-module gaps at that pre-K1–K4 snapshot (code inspection, not test failures): User/QR sent no `X-Whitelist-Version`; `/demo` expected a QR shape and revoked code that User/QR did not return. These User/QR gaps are addressed in §18. At that historical snapshot the Central list/manual whitelist endpoint were absent; H1/H2 now implement them in §20 ([API-CONTRACTS §8](API-CONTRACTS.md#8-demo-main-flow-routes-draft)). `POST /api/local/scan` still answers 501. Owners and tasks: [TODO](TODO.md).

## 18. User/QR K1–K4 contract alignment (2026-11-16)

Module vẫn **DEV_SLICE**, không đổi nhãn dashboard. Theo yêu cầu người dùng (B27), `factories.whitelist_version`
khởi tạo 1, tăng cùng transaction employee/audit cho lifecycle/thêm/xóa và cả hai phía chuyển factory.
Khóa factory theo thứ tự ID; chuyển về chính factory hiện tại là no-op. Snapshot bắt buộc `factoryId`,
đọc header/body cùng read-only `REPEATABLE_READ`, giữ JSON array kể cả `[]`. QR giữ trường cũ, thêm
`periodSeconds:30`, `qrPayload:{empId,totp,ts,ver}`; revoked GET trả `409 QR_REVOKED`.
Compose truyền cùng dummy kiosk key cho hai service. Production identity/factory authorization vẫn thiếu.

Kiểm thử lần này dùng Docker project riêng `tmc-k1-k4-review`, volume mới và cổng riêng; không reset volume cũ.
Các cổng gateway/frontend/kiosk/turnstile/HR/PostgreSQL lần lượt là 28080/23000/28085/28090/28091/25433.
`KIOSK_WHITELIST_SYNC_ENABLED=true`, interval 1000 ms và AES key giả (32 byte 1..32) chỉ truyền qua môi trường
kiểm thử; Compose mặc định vẫn tắt whitelist sync. `KIOSK_API_KEY` dùng giá trị giả của `.env.example`.
JUnit chạy trong `maven:3.9-eclipse-temurin-21` (Temurin 21.0.12.1), mount repo vào `/workspace`, cache Maven
vào `/root/.m2/repository`, socket `/var/run/docker.sock`, và `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.
Lượt User/QR cuối dùng thêm `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`. Log: `.verify-logs/k1-*`.

Lệnh và kết quả đã xác nhận:

- `mvn -B -ntp test` (root, trong container): **exit 0**, **611/611**, fail/error/skip **0**:
  User/QR 48, Gate 4, Attendance 110, Reporting 89, Kiosk 360. Bao gồm 13 architecture tests và 37 PostgreSQL tests thật.
  Lượt full reactor bắt đầu trước ba ca tham số bổ sung; toàn bộ User/QR cuối được chạy lại ngay bên dưới.
- `mvn -B -ntp -pl backend-services/user-qr-service test` (container mới): **exit 0**, **51/51**, fail/error/skip **0**,
  gồm **16 PostgreSQL thật**, **9 web**, **4 architecture**. Lượt này bổ sung kiểm tra rollback cả rotate/revoke/activate
  và snapshot ban đầu rỗng khi writer thêm nhân viên giữa hai SELECT. Focused run là tập con, không cộng lặp.
- `docker compose --env-file .env.example -p tmc-k1-k4-review build`, rồi `up --no-build -d --wait`
  (với project/cổng/key test ở trên): **exit 0 / 0**, đủ 10 container healthy.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1` và Git Bash `scripts/smoke.sh`:
  **exit 0 / 0**, **35/35 mỗi script**.
- `node --test tests/integration/user-qr.test.mjs`: **exit 0**, **2/2**; `slice.test.mjs`: **exit 0**, **8/8**;
  `reporting.test.mjs`: **exit 0**, **3/3**. Chạy tuần tự, không dùng `SKIP_DISRUPTIVE`; không skip.
- Helper `KioskSnapshotCheck.java` chạy bằng JDK 21 + sqlite-jdbc, cùng UID Kiosk, kết nối SQLite `mode=ro`:
  **exit 0**; revision và tổng số dòng cache có secret mã hóa khớp factory BD trong Central thật sau HTTP sync.
  Đây là kiểm tra metadata/cache, không phải scan/TOTP hay xác nhận cổng vật lý.
- Trong `web-frontend`: `npm ci`, `npm run typecheck`, `npm test`: **exit 0 / 0 / 0**, **118/118** (10 files).
  Build frontend cũng chạy thật trong Compose image build ở trên.
- `docker compose --env-file .env.example config --quiet`, `node scripts/check-docs.mjs`, `git diff --check`:
  **exit 0 / 0 / 0**. `node --check tests/integration/user-qr.test.mjs`: **exit 0**.
- `docker compose -p tmc-k1-k4-review down`: **exit 0**, gỡ container/network test, **giữ volume**; stack ban đầu không đổi.

Các lần thử trước khi sửa môi trường được giữ log: smoke ban đầu 33/35 (exit 1), reporting 0/3 (exit 1) do dùng
image reporting cũ; build lại toàn bộ source rồi chạy lại đạt kết quả trên. Vitest ban đầu bị sandbox `EPERM`,
sau đó thiếu `qrcode.react` trong `node_modules` cũ; chạy quyền phù hợp và `npm ci` theo lockfile đã khắc phục,
không sửa dependency/source frontend. Phép đọc volume SQLite mount read-only ban đầu không tạo được shared-memory
lock của WAL; helper cuối dùng kết nối DB chỉ đọc trên volume cho phép tạo lock, không ghi dữ liệu nghiệp vụ.

Contract vẫn DRAFT và cần reviewer Khoa–Khánh–Khiêm cùng owner schema/integration (B27). Schema chỉ áp dụng trên
volume rỗng; reset Central làm revision trở lại 1, cần phối hợp cache Kiosk ([Known limitations](DECISIONS.md#known-limitations)).
Các label module giữ nguyên; không tuyên bố production auth, HR sync, scan/TOTP Kiosk hay NFR hiệu năng đã hoàn tất.

## 19. Full run on `develop` with Docker (2026-11-17)

Tree: `develop` at `94e450f` plus the uncommitted B28 docs, the T01 load files and a `verify.mjs` fix. Windows 11, Docker Engine 29.4.0, Compose 5.1.1, JDK 21.0.11, Node 24.13.1, k6 1.7.1. Before the run, with the owner's approval, `docker compose down -v` removed the stale volumes (created before the B27 schema).

| Command | Exit | Result |
|---|---|---|
| `node scripts/verify.mjs` (full) | 0 | java 614 tests, 0 failed, 0 errors, **0 skipped**; frontend 118 (typecheck, test, build); mocks 6 + 11; docs OK; compose config; stack up, all healthy; smoke 35/35; integration 13/13 |
| `psql ... < tests/load/fixtures/load-employees.sql` | 0 | 1000 employees inserted in 1.5 s, BD revision 5 → 1005; rerun inserts 0 (idempotent) |
| `node --test tests/integration/user-qr.test.mjs` after the fixture | 0 | 2/2; its independent checksum check covers the newest 200 audit rows |
| Node recomputation of the whole audit chain (scratch script, `TMC-AUDIT-V1`) | 0 | 1009 rows from genesis, 1000 of them fixture rows, 0 breaks, 0 checksum mismatches |
| `SCENARIO=selftest k6 run tests/load/k6/peak-scan-load.js` | 0 | TOTP equals 12 vectors from the Java `TotpCodeGenerator` |
| `SCENARIO=quick k6 run tests/load/k6/peak-scan-load.js` | 108 | Expected abort: fixture found and TOTP equal to live user-qr, then `POST /api/local/scan` answered 501. **T01 stays NOT_RUN** |

`verify.mjs` printed "? tests passed" for the frontend because Vitest colours its summary; the count is now read after stripping ANSI escapes (re-run `--only frontend`: 118).

Still not exercised: the kiosk scan (501), whitelist sync in the default Compose (flag off, no dev AES key), NFR-1 and NFR-7.

## 20. H1/H2 demo endpoints (2026-11-17)

Git preparation: clean `feat/kiosk-whitelist-sync` at `6a0870c`; `git fetch origin` exit 0,
HEAD was an ancestor of `origin/develop` (0 ahead / 9 behind), then authorized
`git merge --ff-only origin/develop` exit 0 → `353cebc`. Same branch; no merge commit, staging or push.
H1/H2 changes remain uncommitted. Module labels stay DEV_SLICE.

H1 supplies a dev-only bounded fixture-inclusive Central list using a separate domain read capability
and the existing PostgreSQL query adapter. History's fixture exclusion/ascending range pages and ingestion
are unchanged. H2 supplies a dev-only manual trigger using the existing singleton and its guards;
only UPDATED/NOT_NEWER are 200. [Wire/errors/security](API-CONTRACTS.md#h1-and-h2-dev-demo-routes-2026-11-17), B29.

Environment here: Windows, JDK 23.0.1 compiling release 21. `docker version` and `docker ps -a` both exited 1:
Docker Client 28.0.1 was available, but `dockerDesktopLinuxEngine` named pipe was absent. No daemon configuration,
Compose, container or volume was changed. Testcontainers could not find a usable Docker environment.
This is a separate machine/run from §19's full Docker evidence; it does not revoke or extend that result.

| Command | Exit | PASS | FAIL | ERROR | SKIP |
|---|---|---|---|---|---|
| `mvn -B -ntp -pl backend-services/attendance-processor -Dtest=DevAttendanceEventsControllerWebTest,AttendanceEventsProfileWebTest,AttendanceHistoryPostgresIntegrationTest,AttendanceProcessorApplicationTest test` | 0 | 19 | 0 | 0 | 12 |
| `mvn -B -ntp -pl kiosk-agent -Dtest=DevWhitelistSyncControllerWebTest,DevWhitelistSyncAvailabilityTest,WhitelistSyncProfileWebTest,WhitelistHttpSyncWiringTest test` | 0 | 39 | 0 | 0 | 0 |
| `mvn -B -ntp -fae -pl backend-services/attendance-processor,kiosk-agent test` — Attendance | 0 | 108 | 0 | 0 | 18 |
| Same full run — Kiosk | 0 | 374 | 0 | 0 | 0 |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` — all five Java modules | 0 | 15 | 0 | 0 | 0 |

Final static checks: `node scripts/check-docs.mjs` exit 0 (30 Markdown files, 349 links, no warnings);
`git diff --check` exit 0. The docs check initially found one obsolete §8 anchor in the frontend README;
the reference and route-status row were updated, with no frontend application code changes.

Full Attendance total is 126: 12 History PostgreSQL + 6 Idempotency PostgreSQL cases are skipped,
including the two new H1 real-store tests (fixtures/newest-first/ties/limit/filter and unchanged History).
They are written against PostgreSQL Testcontainers/canonical schema, not replaced by H2 or fake SQL results.
Focused runs and ArchUnit reruns are not added to the module totals; five architecture cases are already
included in the Attendance/Kiosk full run. Root ArchUnit now includes four Reporting architecture cases.

H1 WebMvc tests verify seven frontend `CentralEvent` fields, validation, empty arrays, safe 503, dev-only
registration and unchanged production POST 501s. H2 tests verify every report status, safe unexpected failure,
missing flag/use case and non-dev 404. Production Spring composition + HTTP mock + real SQLite/AES additionally
verify manual POST commits, rejects a recursive trigger through the same singleton guard and respects offline.
Existing queue, encryption, rollback, scheduler and concurrency regressions also passed.

Gateway route verification is static: `/api/v1/attendance/` and `/api/dev/kiosk/` already forward the unchanged
paths/query strings. No nginx/config/frontend application/Scan/Central/schema changes. No H3/H4 startup fetch, flag enablement
or dev AES provisioning. New H1 PostgreSQL tests and deployed gateway/browser acceptance remain NOT_RUN here;
frontend dependencies are absent, so frontend Vitest was not rerun. Ask Nam to exercise both buttons/list on
the Docker-capable demo stack with Khiêm; do not claim an end-to-end scan (still 501).

Files in this handoff:

- New Attendance production files: `IAttendanceEventsQuery`, `AttendanceEventsService`,
  `DevAttendanceEventsController`, `AttendanceEventsResponseDto`. Existing `PostgresAttendanceHistoryQuery`
  gains only the separate recent-list capability.
- New Attendance tests: `DevAttendanceEventsControllerWebTest`, `AttendanceEventsProfileWebTest`.
  Existing `AttendanceHistoryPostgresIntegrationTest` and `AttendanceProcessorApplicationTest` are extended.
- New Kiosk production file: `DevWhitelistSyncController`. New tests: `DevWhitelistSyncControllerWebTest`,
  `DevWhitelistSyncAvailabilityTest`, `WhitelistSyncProfileWebTest`; existing `WhitelistHttpSyncWiringTest` is extended.
- Markdown: both module READMEs, `web-frontend/README.md` (reference/status only), `API-CONTRACTS`, `DECISIONS`,
  `STATUS`, `TRACEABILITY`, `SOLID-GUIDELINES`, `TODO` under `docs/`.

## 21. H3/H4 dev whitelist startup and key provisioning (2026-11-17)

Starting tree: clean `feat/kiosk-whitelist-sync` at `ee88e68` (H1/H2 committed by the user).
No fetch/merge/branch/index/commit/push operation in this task. The module stays DEV_SLICE.

H3: dev + enabled registers a Ready listener. The existing scheduler waits until ApplicationReady, then queues
one initial pass on its private worker; other profiles retain delayed SmartLifecycle startup. No HTTP in bean
constructors, refresh or the event thread. The same use case/guard covers initial, periodic and manual calls.
Failure/offline leaves old cache intact and periodic retry available. The default interval is still 60000 ms.

H4: `scripts/provision-kiosk-key.ps1` uses .NET CSPRNG for a missing 32-byte Base64 key in ignored local `.env`.
Valid keys/files are reused unchanged; invalid/duplicate keys fail safely. The script serializes invocations and
replaces the file atomically without printing values or retaining backups. A local key was provisioned here and
then reused successfully; `git check-ignore .env` exit 0. No real key was added to tracked files.
The dev-only Compose sets sync true and requires a nonempty key. `application.yml`/`.env.example` remain false;
the existing codec, envelope, AAD and queue-only standalone missing-key behavior are unchanged.

Environment: Windows PowerShell 5.1, JDK 23.0.1 compiling release 21; no JDK 21 installed here.
Docker CLI initially showed Client/Server 28.0.1 and existing containers (both commands exit 0).
Testcontainers 1.21.4 later received HTTP 500 from its default `docker_engine` pipe. CLI's context points to
`dockerDesktopLinuxEngine`; a process-only DOCKER_HOST retry also got 500, and CLI then reproduced 500 on both
pipes. No Docker settings, user Testcontainers config, containers, volumes or database data were changed.
Existing containers were not removed; no stack was started/reset. Compose configuration checks do not require
a working daemon and do not establish gateway acceptance.

| Command | Exit | PASS | FAIL | ERROR | SKIP |
|---|---|---|---|---|---|
| `mvn -B -ntp -pl kiosk-agent -Dtest=WhitelistSyncSchedulerTest,WhitelistSchedulerConfigTest,WhitelistHttpSyncWiringTest,WhitelistSecretConfigTest test` | 0 | 68 | 0 | 0 | 0 |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-provision-kiosk-key.ps1` (final run) | 0 | 9 | 0 | 0 | 0 |
| `mvn -B -ntp -fae -pl backend-services/attendance-processor,kiosk-agent test` — Attendance | 0 | 108 | 0 | 0 | 18 |
| Same full run — Kiosk | 0 | 389 | 0 | 0 | 0 |
| `mvn -B -ntp -pl backend-services/attendance-processor test` with process-only `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` | 0 | 108 | 0 | 0 | 18 |
| `mvn -B -ntp -Dtest=*ArchitectureTest test` — all five modules | 0 | 15 | 0 | 0 | 0 |

The 18 PostgreSQL cases (12 History including H1 + 6 Idempotency) remain **SKIP, not PASS** in both attempts.
Do not add focused runs or the repeated architecture cases to full module totals. No Attendance Java/tests changed.
Initial script execution was blocked by local Execution Policy; the per-process Bypass option was then used
without changing system policy. The first executable script test run had 7 PASS / 2 FAIL: PowerShell 5.1 coerced
a null backup path into an empty string in File.Replace. Using its explicit NullString fixed atomic replacement;
the complete script suite then passed 9/9. Keys were not printed in either run.

Verified locally: profile/flag/Ready gating, duplicate Ready suppression, initial 503 or missing header → old
cache retained → later periodic HTTP-mock response committed through real SQLite/AES, manual/periodic exclusion
during initial fetch, offline no fetch/write, key length/restart behavior. Existing Attendance Sync, queue,
scheduler lifecycle and persistence regressions passed. No new public endpoint or authentication is added.

`docker compose config --quiet` with local `.env`: exit 0. Missing-key negative check
`docker compose --env-file .env.example config --quiet`: expected exit 1 with a provisioning hint.
Only quiet/sanitized configuration checks are used; no rendered credentials enter the log.

Remaining for Nam: stable Docker/Testcontainers and JDK 21 rerun; rebuild dev stack with the preserved local
key, observe initial failure/retry while gateway starts, then manual sync/newer-or-unchanged revision and
restart/reopen through the real gateway `/demo`. Health does not imply that whitelist is populated.
Scan/TOTP/Anti-Replay/Turnstile remain unchanged (scan 501); no new whole-stack acceptance or NFR claim.
Setup and key custody limits: [Kiosk H3/H4](../kiosk-agent/README.md#dev-initial-sync-and-key-provisioning-h3h4).

Files: two existing Java files (`WhitelistSyncScheduler`, `WhitelistSyncWiringConfig`); three existing tests
(`WhitelistSchedulerConfigTest`, `WhitelistHttpSyncWiringTest`, `WhitelistSecretConfigTest`); two new PowerShell
scripts; `.env.example`, `docker-compose.yml`, `application.yml` (comment only); root/Kiosk READMEs and
`STATUS`, `TRACEABILITY`, `TODO`, `DECISIONS`, `SOLID-GUIDELINES`. No module labels change.

Final checks: `node scripts/check-docs.mjs` exit 0 (30 Markdown files, 355 links, zero warnings),
`git diff --check` exit 0. Branch/HEAD remain `feat/kiosk-whitelist-sync` / `ee88e68`;
`git diff --cached --quiet` exit 0 confirms no index changes. H3/H4 remain uncommitted.

## 22. Dev QR scan (2026-11-18)

Checkout local `codex/kiosk-qr-scan`, dựa trên `8a45d98` của `origin/feat/kiosk-whitelist-sync`.
Kiosk chạy scan thật trong profile `dev` và giữ nhãn **DEV_SLICE**. SQLite ghi token và event trong một transaction
trước khi gửi lệnh cổng. Sync vẫn dùng chính ID đó và cần ack theo từng ID.
[B30](DECISIONS.md#bootstrap-decisions) ghi hành vi từ chối tạm thời và các owner cần review.

Test Compose mới tại `tests/integration/main-flow.test.mjs` kiểm tra QR qua gateway, SQLite, mock turnstile,
PostgreSQL, Central Events, báo cáo, revoke/rotate, replay, offline và 20 scan qua restart.
Dev ingress phía central đánh dấu dữ liệu là fixture. Báo cáo dev trong Compose có thể đọc các fixture này.

| Lệnh | Exit | Kết quả |
|---|---:|---|
| `mvn -B -ntp -pl kiosk-agent test` (JDK 21.0.6) | 0 | 408 test, 0 lỗi, 0 skip. Có ArchUnit, SQLite thật, và luồng QR offline → PENDING → SYNCED qua HTTP loopback với central stub. |
| `node scripts/verify.mjs --fast` | 1 | Lượt Maven reactor ban đầu ghi nhận 13 skip, sau đó phải dừng Surefire đang chờ Docker. Frontend, mock, docs và Compose config pass. |
| `mvn -B -ntp '-Dtest=*,!*PostgresIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | 636 test chạy, 0 lỗi và 0 skip trong tập đã chọn; 4 lớp PostgreSQL Testcontainers bị loại khỏi lần chạy này. |
| `node scripts/verify.mjs --only frontend,mocks,docs` | 0 | Typecheck/build frontend và 118 Vitest; mock turnstile 6, mock HR 11; docs check PASS. |
| `docker compose config --quiet`, `node --check tests/integration/main-flow.test.mjs`, `git diff --check` | 0 / 0 / 0 | Kiểm tra cấu hình, cú pháp và diff. |
| `wsl -d Ubuntu -- timeout 15s docker info --format '{{.ServerVersion}}'` | 124 | Socket Docker Desktop không trả lời. |

Smoke qua gateway, integration Compose (gồm T02 20 scan và revoke sau refresh), browser acceptance và load
test vẫn **NOT_RUN**. Ca T02 dựng trạng thái đã commit tại Central nhưng kiosk chưa nhận ack bằng một request
độc lập. Chưa kiểm chứng việc làm rơi response thật trên đường truyền.
Log cục bộ ở `.verify-logs/` (Git bỏ qua).

## 23. M2 follow-up and Compose acceptance (2026-11-18)

Compose project `tmc-priority-review`, separate ports and volumes. Docker Desktop CLI restart reported exit 1
during shutdown. After the desktop backend restarted, Docker Engine 27.5.1 responded and the Compose project started.
Environment: Windows host with Intel Core i5-1135G7 (4 cores, 8 logical processors), Docker VM with 8 CPUs and
approximately 3.68 GiB RAM, JDK 21.0.6, PostgreSQL 16 and k6 2.4.0. Gate latency is for the mock.
Changes to the scanner, UI and Reporting preserve the `DEV_SLICE` designation. B31–B33 require owner review.

| Command / check | Exit | Observed result |
|---|---:|---|
| `docker compose config --quiet` and `docker compose up --build -d --wait --wait-timeout 240` | 0 / 0 | 10 healthy containers on the isolated project. |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1` and `bash scripts/smoke.sh` (Git Bash) | 0 / 0 | Both 35/35. Logs `priority-smoke.log` and `priority-smoke-bash.log`. |
| `node scripts/verify.mjs --only integration` | 0 | 19/19, 0 skipped; includes T02 and gateway → scan → PostgreSQL → report. See `priority-integration.log` and `integration.log`. |
| `mvn -B -ntp test`, then `mvn -B -ntp -fae test` on JDK 21.0.6 | 1 → 0 | Initial User/QR Testcontainers JDBC timeout. Retry after images were built: 680 passed, 0 skipped, including PostgreSQL Testcontainers. See `priority-java-full*.log`. |
| `node scripts/verify.mjs --only frontend,mocks,docs` | 0 | Frontend 118 tests/typecheck/build, mocks 6 turnstile and 11 HR. |
| Verifier prerequisite check with an intentionally missing Compose key | 1 expected | Compose config exit 15. Stack-up, smoke and integration report SKIPPED. See `priority-verifier-prerequisite.log`. |
| Chrome headless via `priority-browser.mjs` | 0 | Revoke → refresh → prior QR returns `REJECTED/REVOKED`. Activate → refresh → new QR `ALLOWED/SENT`. Evidence `priority-demo-revoked.png` and `priority-demo-activated.png` with the QR image hidden. |
| Read-only snapshot of Compose SQLite and scheduled-cutoff PostgreSQL query | 0 / 0 | All four rejection reasons persisted and SQLite integrity OK. A scheduled batch discovered after cutoff was FAILED, 0 sends, with deadline at 06:00 VN. See `priority-audit-sqlite-verified.log` and `priority-hr-cutoff.log`. |
| T06 `node .verify-logs/priority-report-probe.mjs` | 0 | BD/date report over 35,200 synthetic events for 800 employees across 22 days, max-size page of 500 rows. First request 1,471 ms; 25 subsequent requests p50 753 ms, p95 1,877 ms, p99/max 2,694 ms. Shift and role checks remain outside this measured API. |
| T01 k6 2.4.0 `quick`, first run and retry | 99 → 0 | First run overlapped Maven/Testcontainers and crossed latency and dropped-iteration thresholds. Isolated retry passed. Quick is a preflight; see the steady-run result for NFR-1. |
| T01 k6 2.4.0 `steady`, BD, configured 800 scans / 30 min | 0 | 801 measured calls, 801 ALLOWED/SENT and within 2 s, zero HTTP errors and dropped iterations. p50 38.72 ms, p95 135.68 ms, p99 269.74 ms, max 1.84 s; 0.447 iterations/s. See `priority-k6-quiet-steady.log` and corresponding JSON. |
| k6 2.4.0 `burst`, BD, configured 800 scans / 2 min | 0 | 801 measured calls, 801 ALLOWED/SENT, zero HTTP errors and drops. p50 35.92 ms, p95 60.89 ms, p99 152.07 ms, max 915.06 ms; 6.679 iterations/s. Stress result reported separately. |

Raw local logs are under `.verify-logs/`. Compose T02 precommits one event through separate HTTP before the
kiosk receives its acknowledgement. The earlier local kiosk run physically dropped the response against an in-memory
Central stub. Both are scoped to the recorded conditions.

The load runner used `docker run --rm --network tmc-priority-review_default -e SCENARIO=steady`, the recorded
k6 2.4.0 image, script mount `tests/load/k6`, `KIOSK_URL=http://kiosk-agent:8080`,
`GATEWAY_URL=http://nginx-gateway` and `k6 run --summary-export`. Build/test activity ended before the steady run.
The PostgreSQL count for the steady time range was 802 events, 802 distinct attendance IDs and 802 distinct
employees. This includes the setup probe. See `priority-steady-central-count.log`.
