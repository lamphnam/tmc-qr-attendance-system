# STATUS — what exists, what was verified

Bootstrap snapshot of **2026-11-01**, with separate Kiosk and User/QR verification records from **2026-11-14** (local UTC+7 date; see §§6–8). These results belong to their respective pre-merge branch snapshots, not the combined working tree; no combined Maven/integration result is claimed. Earlier bootstrap results are historical. Requirement-level status is in [TRACEABILITY](TRACEABILITY.md); remaining work in [TODO](TODO.md); product limitations in [DECISIONS](DECISIONS.md#known-limitations).

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
| `user-qr-service` | Khoa | **DEV_SLICE** | `/services/user-qr/health` | dev-only employee-ID QR lifecycle, RFC 6238 TOTP, key-protected whitelist, transactional audit chain; pre-merge 41 module tests including 7 real PostgreSQL tests, zero skipped, also verified on Alpine. Default profile and `/qr/generate` remain 501 | JWT/RBAC, production kiosk identity/factory authorization, Central encryption at rest, HR sync, restricted audit DB role/external anchor, MFA; kiosk HTTP consumer absent, application core exists |
| `gate-station-service` | Khôi | **SCAFFOLD** | `/services/gate-station/health` | boots + real health; `POST /api/v1/gates`, `GET …/status`, `POST …/manual-open` answer **501** | registration, heartbeat, manual open + audit |
| `attendance-processor` | Khôi | **DEV_SLICE** | `/services/attendance/health` | real idempotent ingestion: single event + batch, per-event ack after commit, SAME vs CONFLICT, 503 when the DB is down, safe under 8 threads on one id. Only via `/api/dev/attendance/*`; `/api/v1/attendance/*` answer **501** | authenticated production routes, rejected scans (D06), reference validation |
| `reporting-service` | Nam | **SCAFFOLD** | `/services/reporting/health` | boots + real health; 3 report/dashboard/hr-export routes answer **501**; `tmc.hr-api.base-url` configured | reports, dashboard, HR export scheduler/retry |
| `kiosk-agent` | Khánh | **DEV_SLICE** | `/services/kiosk/health`; host `8085` | SQLite WAL + `synchronous=FULL`; PENDING committed before the turnstile call; sync by per-id ack with backoff; FAILED only on a per-event rejection; the offline switch cuts only kiosk → central; health reads the real SQLite file. Whitelist domain, atomic SQLite snapshots and local AES-256-GCM secret protection implemented (D08 update below). `POST /api/local/scan` answers **501** | QR scan, TOTP, HTTP whitelist sync, key lifecycle, anti-replay (`used_tokens` table unused), rejected scans, heartbeat, manual open, auth to central |
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
