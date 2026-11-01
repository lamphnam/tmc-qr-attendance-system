# TESTING — test plan, demo scenes, acceptance

What must be proven and what counts as done. Commands to run things: root [README](../README.md#4-test). What passed so far: [STATUS](STATUS.md). Claims rules: [PROJECT §5](PROJECT.md#5-claims-policy-canonical--other-docs-link-here).

## Philosophy

- "Docker healthy" is not business acceptance.
- Record the environment (machine/CPU/RAM/OS, Java version, Compose mode, dev vs production topology).
- Every result carries date, command, fixture, pass/fail counts, p50/p95/p99, max latency and screenshot/log link. Anything not run is `NOT_RUN`.

## Test layers that exist

| Layer | Tool | Where |
|---|---|---|
| Unit, Spring context, HTTP contract, layering | JUnit 5, Spring Test, ArchUnit | every Java module |
| Real PostgreSQL 16 | Testcontainers (**skipped, not faked,** without Docker) | `attendance-processor` |
| Real SQLite file, real loopback HTTP | production `SqliteConfig`, JDK `HttpServer` | `kiosk-agent` |
| Frontend | Vitest (SSR render of every route, health logic), `tsc`, Vite build | `web-frontend` |
| Mocks | `node:test` | `mock-external-systems/*` |
| Running stack | `scripts/smoke.sh` / `smoke.ps1` (health, DB/SQLite connectivity, routing, honest 501s) | `scripts/` |
| Black-box slice | `tests/integration/slice.test.mjs` — asserts on PostgreSQL rows, SQLite queue, turnstile command count | `tests/integration/` |
| Load | placeholder only | `tests/load/` |
| All of the above + docs check, one honest report | `scripts/verify.mjs` (`--fast` without Docker); markdown links/anchors: `scripts/check-docs.mjs` | `scripts/` |

## Must-run by M2 [TEAM-PLAN]

### T01 — Kiosk performance (NFR-1)
- Workload: **800 scans within 30 min per factory** (mean ≈ 0.44 req/s), plus an optional burst of 800 in 1–2 min.
- Measure scan request → kiosk response + gate-command initiation; **exclude** the background sync to central.
- Report p95/p99, error rate, max and throughput. Use distinct valid TOTP steps/employees so anti-replay does not skew results.
- Assert the **≤ 2 s** threshold under the stated load; do not assert it from arithmetic.

### T02 — Offline and durable sync (NFR-2 / ADR-01)
1. Preload the trusted whitelist and valid QR material.
2. Disable only kiosk ↔ central sync, not the local mock turnstile.
3. Generate **20 unique valid events** (distinct employees/time steps); inspect SQLite PENDING count and the local gate commands.
4. Restart the kiosk while offline; PENDING must survive.
5. Restore sync; every accepted ID is present **exactly once** in central, none unacked or duplicated, local state SYNCED; compare counts and ID sets.
6. Re-run the same batch; the row count must not grow.
7. Simulate central commit followed by a lost response; retry the same IDs; verify dedupe.

### T03 — Static QR exposure mitigation (ADR-02)
QR changes with the TOTP step; an immediate same-token replay at the same kiosk is rejected; expired / forged / disabled / wrong-secret tokens are rejected; after an offline revoke the kiosk may keep a stale whitelist (document it) and the revoke must propagate once connected; test clock-skew boundaries; claim no 100 % fraud prevention.

### T04 — Gate security (NFR-8)
An authorized Guard can issue a manual open with a non-empty reason; an Employee cannot. The audit event records actor/action/time/reason/target with hash continuity; update/delete attempts fail under the application DB role. Validate the real chain, not merely that a SHA-256 string exists.

### T05 — HR export (ADR-03 / NFR-3, NFR-6)
The manual trigger sends the previous period with a stable Batch_UUID and Attendance_ID; repeating the exact batch creates no duplicates. A mock HTTP 500/503 produces persisted retries with backoff and never blocks the scan path. Retry after a restart; the 06:00 warning cutoff is tested with an injectable clock. The 12:00 deadline is an operational requirement that one happy-path request does not prove.

### T06 — Reporting (NFR-7)
Factory/date/shift queries on a representative dataset; record latency and permissions; target ≤ 30 s.

## Five agreed demo scenes [TEAM-PLAN]

1. Dynamic QR → kiosk scan → local mock turnstile → replay denied.
2. Offline toggle → scan a unique QR → SQLite PENDING → restore → central SYNCED / dashboard increments.
3. Guard manual open with reason → audit evidence including the SHA-256 chain.
4. HR revokes a QR → whitelist refresh → kiosk denies (show the offline limitation).
5. Export batch → mock HR failure/retry → recovery and idempotent dedupe.

A checklist is not proof: show logs/data for what is implemented and disclose what is missing.

## Acceptance (definition of done)

Checked boxes are verified on 2026-11-01 (commands and exit codes in [STATUS](STATUS.md)); an unchecked box is open.

### A. Bootstrap repository
- [x] Root `README.md`, `AGENTS.md`, `docs/`, `.gitignore`, `.env.example`, `docker-compose.yml` exist.
- [x] Java 21 and Spring Boot 3.5.16 are explicit and resolve; every backend and the kiosk have a valid POM and tests.
- [x] The four services build and start independently with a health endpoint; `kiosk-agent` builds/starts and its SQLite file persists on a volume, with the table created and read.
- [x] `mock-turnstile-api` and `mock-hr-payroll-api` answer **real HTTP**.
- [x] Nginx routes to every domain API and the frontend with correct hostnames inside Compose.
- [x] The frontend builds, loads and reads a real health endpoint, with placeholders clearly labelled *(rendering in a real browser is `NOT_TESTED`)*.
- [x] PostgreSQL schema boots on a clean volume; fixtures are synthetic.
- [x] `.env`, secrets, `*.db`, `target/`, `node_modules/` are not tracked.
- [x] `docker compose config` passes, `up --build` works and services are healthy; the smoke script exits 0 only when every required endpoint passes.
- [x] 4-layer separation with no domain → infrastructure dependency and no cross-service imports (ArchUnit). Skeleton services contain only `presentation` so far.
- [x] `docs/STATUS.md` states each component's status truthfully; no auto-commit, force-push, repo creation or destructive change.

### B. Thin vertical slice (dev-only — a real but limited slice, **not** complete ADR-01/02/03)
- [x] Central stores one synthetic dev event in PostgreSQL; the same `attendanceId` again returns an ack without another row.
- [x] The kiosk queue stores PENDING in SQLite, survives a restart, syncs to central and becomes SYNCED only after the ack.
- [x] The kiosk calls the local mock turnstile independently of central.
- [x] Tests assert real state changes, not just status 200.

### C. Full M2 (owners, later)
- [ ] FR/NFR traceability covers every promised feature; every unimplemented one is explicit ([TRACEABILITY](TRACEABILITY.md)).
- [ ] Dynamic QR (RFC 6238, HMAC-SHA256, step window, anti-replay) with audit tests.
- [ ] Offline 20 unique events, none lost or duplicated, restart recovery (T02).
- [ ] Guarded manual open with an audit record (T04).
- [ ] QR revoked after a whitelist sync; offline limitations disclosed (T03).
- [ ] HR export: schedule + retry + stable idempotency + manual trigger + alert (T05).
- [ ] Load test with p95/p99 measured under the official workload (T01, T06).
- [ ] M2 views (Module / C&C / Allocation), SOLID evidence in real code, reflection, GitHub URL.
