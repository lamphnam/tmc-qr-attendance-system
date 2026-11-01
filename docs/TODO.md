# TODO — remaining work per member

Everything here is **not done** unless [STATUS](STATUS.md) says otherwise. Priority: **P0** blocks others · **P1** · **P2**. The bootstrap provided only the foundation and a dev-only slice; it did not replace anyone's module.
Cross-cutting references (do not restate them in a PR): who depends on whom and the interface gates → [OWNERSHIP](OWNERSHIP.md); open decisions D01–D16 and the bootstrap proposals B01–B20 → [DECISIONS](DECISIONS.md); requirement status → [TRACEABILITY](TRACEABILITY.md); what to test → [TESTING](TESTING.md); what may be claimed → [PROJECT §5](PROJECT.md#5-claims-policy-canonical--other-docs-link-here).

## 0. Everyone, now (P0, before merging features)

1. Clone, `cp .env.example .env`, `docker compose up --build -d --wait`, run `scripts/smoke.sh` (or `smoke.ps1`); report failures with logs. Nam reviews the scaffold PR first.
2. Hold a 30-minute meeting to settle the blocking contracts **D01, D02, D04** ([DECISIONS](DECISIONS.md)). When settled, edit [API-CONTRACTS](API-CONTRACTS.md) / [DATA-MODEL](DATA-MODEL.md), drop the matching DRAFT label and record it in DECISIONS.
3. Review and accept or change the bootstrap decisions B01–B20 that concern your module.
4. Each module has an `ArchitectureTest`: do not disable it to go faster; change a rule only with a stated reason in the PR.
5. Order of work: happy path first (Khoa → Khánh → Khôi → Nam → Khiêm), then revoke + whitelist refresh → manual open + audit → HR export + retry → outage/restart (T02) → load test. When a step finishes, add a real test under `tests/integration/`, then update STATUS, TRACEABILITY and `web-frontend/src/catalog.ts`.

## 1. Khoa — `user-qr-service`, auth/RBAC/TOTP/audit, gateway integration

| P | Task | Depends on / blocks |
|---|---|---|
| P0 | **Auth/token contract**: JWT claims, role names matching the seeded `roles`, lifetime, how other services verify it — agree with Khiêm, Khôi, Nam | blocks every role-protected route |
| P0 | `GET /api/v1/kiosk/whitelist`: kiosk authentication (service key / mTLS, D04), scope by `factoryId`/`kioskId`, monotonically increasing `version`, `secretVersion`; agree the schema with Khánh | Khánh needs it for the whitelist cache and offline TOTP |
| P0 | `GET /api/v1/qr/generate`: RFC 6238 TOTP, HMAC-SHA256, 30 s, per-employee secret **never sent to the browser**; reject revoked QR | Khiêm renders it |
| P1 | Secret protection at rest and rotation (D08): `employees.shared_secret_enc` is NULL today | Khánh |
| P1 | HR employee sync (FR-1.1) from `GET /mock/hr/employees` (`tmc.hr-api.base-url` is already configured); QR revocation (FR-1.4) | Nam (mock), Khiêm (HR UI) |
| P1 | Audit log with a SHA-256 chain, an INSERT-only DB role and integrity tests (NFR-8, T04). All services currently share one dummy DB account (B14) | Khôi (manual open writes audit) |
| P1 | MFA for Admin/HR (NFR-4). If it will not be built, the report must say so | — |
| P2 | Remove the two `/api/dev/*` gateway locations and the `dev` profile from any non-developer environment; add rate limiting and a CORS allowlist | — |

## 2. Khánh — `kiosk-agent`, `mock-turnstile-api`, SQLite, SyncManager

| P | Task | Depends on |
|---|---|---|
| P0 | `ProcessQrScanUseCase` and a real `KioskScanController` (501 today): validate whitelist + TOTP, write the `used_tokens` row **and** the event in **one** SQLite transaction, only then call `ITurnstileAdapter` | Khoa (whitelist, secret) |
| P0 | Pure `TotpValidatorDomainService` (inject a `Clock`), current + previous step, clock-skew boundary tests (T03) | — |
| P0 | Whitelist pull, cache and refresh. State plainly in the report that revocation is not instant while offline | Khoa |
| P1 | Decide D06 (rejected scans: separate table vs `scan_result=REJECTED`) and which ones sync, with Khôi | Khôi |
| P1 | Switch `CENTRAL_SYNC_PATH` to the authenticated route when Khôi opens it; add kiosk → central credentials (D04) | Khôi, Khoa |
| P1 | Heartbeat to gate-station; receive manual-open commands over the agreed channel (D11) | Khôi |
| P1 | Official T02: **20 unique events** (different employees/time steps), restart while offline, lost ack after a central commit; compare counts and ID sets on both sides (the slice only proves 1 event) | Nam (harness) |
| P2 | `ZKTecoTurnstileAdapter` returning `UNSUPPORTED` (never fake a protocol); replace or adopt the mock turnstile contract (B18) | — |

## 3. Khôi — `attendance-processor`, `gate-station-service`

| P | Task | Depends on |
|---|---|---|
| P0 | **Settle the event/sync contract with Khánh** (API-CONTRACTS §3–5, B05–B07), then open `POST /api/v1/attendance/events` and `/sync` **with authentication** (501 today; the slice uses `/api/dev/attendance/*`) | Khoa (auth), Khánh |
| P0 | Own the final ERD in `init-db/schema.sql`: confirm B01 or switch to Flyway **in one place** (D02) | everyone |
| P1 | Reference-validation policy (does the employee/gate exist?), rejection and retention rules; accepted/rejected semantics and timezone for Nam | Nam |
| P1 | `gate-station-service`: gate/kiosk registration, heartbeat, `GET /api/v1/gates/{id}/status` (501 today) | Khánh |
| P1 | `POST /api/v1/gates/{id}/manual-open`: Guard role, mandatory `reason`, audit record. M1 does not define the delivery channel to the kiosk (D11): define states and authorization first, do not fake end-to-end | Khoa, Khánh, Khiêm |
| P2 | Module View, DSM, ERD and C&C sequences for the report, drawn from the real classes | — |

## 4. Khiêm — `web-frontend`

| P | Task | Depends on |
|---|---|---|
| P0 | Agree with Khoa the login/JWT flow, token storage and the `{code, message, correlationId}` error shape | Khoa |
| P1 | Employee page: log in, call `GET /api/v1/qr/generate`, render **only the server's payload**, refresh at the 30 s boundary; never generate a TOTP in the browser | Khoa |
| P1 | Kiosk simulator: call `POST /api/local/scan`, show *authorization result* separately from *gate-command status*, offline toggle (use `PUT /api/dev/kiosk/sync/offline` until an official mechanism exists) | Khánh |
| P1 | Guard (manual open + reason), HR (revoke, export), Admin pages; a dashboard with real data | Khôi, Nam, Khoa |
| P2 | Keep `src/catalog.ts` in sync with STATUS; FR/NFR → code → test traceability with screenshots | — |

## 5. Nam — `reporting-service`, `mock-hr-payroll-api`, testing, report assembly

| P | Task | Depends on |
|---|---|---|
| P0 | Review and merge the scaffold PR, publish the repo URL, enable minimal CI: `mvn test` on a runner **with Docker**, frontend typecheck/build, `docker compose config` | — |
| P1 | Data semantics with Khôi: exclude rows with `dev_fixture = true`, timezone, reporting period (D10: previous calendar month) | Khôi |
| P1 | `GET /api/v1/reports/attendance` and `/dashboard` (501 today); measure NFR-7 ≤ 30 s on a representative dataset (T06) | Khoa (roles) |
| P1 | HR export: `hr_export_batches`/`_items`, stable `batchUuid`, backoff retry, **injectable clock**, 01:00 scheduler, retry to 06:00, alert; a manual trigger must not create a batch that duplicates a scheduled one (D10, T05). Treat any 5xx/timeout as retryable (D09) | mock HR (exists) |
| P1 | NFR-1 load test (T01): pick **one** tool (k6 / JMeter / Locust), use distinct employees/time steps, time the fast path separately from background sync, report p50/p95/p99 or `NOT_RUN` | Khánh (real scan path) |
| P2 | Extend `tests/integration/` per finished feature; assemble Testing & Evaluation, Allocation View and the final report format | — |
