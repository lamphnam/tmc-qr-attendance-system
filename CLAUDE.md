# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read [AGENTS.md](AGENTS.md) first: it holds every binding rule. [docs/STATUS.md](docs/STATUS.md) says which module is real vs `SCAFFOLD`/`DEV_SLICE`/`MOCK`; [docs/README.md](docs/README.md) maps the other docs. [docs/API-CONTRACTS.md](docs/API-CONTRACTS.md) and [docs/DATA-MODEL.md](docs/DATA-MODEL.md) are **DRAFT**, not approved contracts. The full run/test command list, ports and troubleshooting are in [README.md](README.md); this file adds only what that one lacks.

## Commands beyond the README

Maven **must run on JDK 21**. On the owner's machine `JAVA_HOME` points to JDK 17 although `java` is 21, so set it per command; `make check-java` verifies it.

```bash
mvn -B -ntp -pl backend-services/attendance-processor test                                              # one module
mvn -B -ntp -pl kiosk-agent test -Dtest=SyncPendingEventsUseCaseTest#backoffGrowsWithEachFailedAttempt   # one method
(cd web-frontend && npx vitest run src/health.test.ts -t "is UP only")                                   # one frontend test
(cd mock-external-systems/mock-hr-payroll-api && node --test test/server.test.js)                        # mocks are zero-dependency Node
node --test --test-name-pattern="RESTART" tests/integration/slice.test.mjs                               # stack must be up; SKIP_DISRUPTIVE=1 skips tests that stop containers
```

There is no linter: `npm run typecheck` and the ArchUnit tests are the static checks. `attendance-processor` has a Testcontainers test (real PostgreSQL 16) that is **skipped, not faked, without Docker** — read the surefire "Skipped" count before calling a build green. Image builds use `-DskipTests`, so a built image proves nothing about tests.

## Project skills (`.claude/skills/`, committed)

`verify-all` (runs `node scripts/verify.mjs`, reports honestly) · `check-docs` (runs `node scripts/check-docs.mjs`) · `update-status` (STATUS + TRACEABILITY + `catalog.ts` together) · `new-endpoint` (every file an endpoint touches) · `record-decision` (DECISIONS numbering and DRAFT propagation). Use them instead of improvising these procedures.

## Architecture (things that span several files)

**Modules are independent builds.** Each Java module declares `spring-boot-starter-parent` itself; the root `pom.xml` is only an aggregator and each Dockerfile builds from its own directory. There is deliberately no shared Java library: `ErrorResponseDto` (`{code, message, correlationId}`) is copied into every service. In `kiosk-agent` the composition root `vn.edu.hcmut.tmc.kiosk.config` sits outside the four layers on purpose; domain objects are wired as beans there or in an infrastructure `@Configuration`, never annotated inside `domain`.

**One central schema, no service owns DDL.** `init-db/schema.sql` + `seed.sql` are mounted into the postgres image and run only on an empty volume. Services use `JdbcTemplate` (no `ddl-auto`), and the Testcontainers test loads the same file through the relative path `../../init-db/schema.sql` — so never duplicate DDL.

**The vertical slice (kiosk → central) crosses five places:** `kiosk-agent` commits the event to SQLite as `PENDING` *before* calling the turnstile (`QueueSyntheticEventUseCase`) → `SyncPendingEventsUseCase` posts a batch through `nginx-gateway` (`CENTRAL_BASE_URL` + `CENTRAL_SYNC_PATH`, currently the dev path `/api/dev/attendance/sync`) → `attendance-processor` inserts with `ON CONFLICT (attendance_id) DO NOTHING`, one transaction per event, and acks only after commit → the kiosk marks `SYNCED` only for ids named in the ack. A whole-request failure (refused, timeout, any HTTP error) is never a rejection; only a per-event `REJECTED_PERMANENT` yields `FAILED`. Decisions behind this: DECISIONS B04–B07.

**Unimplemented features answer HTTP 501 `NOT_IMPLEMENTED`.** Production routes (`/api/v1/*`, `POST /api/local/scan`) are 501 stubs; the working logic is reachable only through unauthenticated `/api/dev/**` controllers annotated `@Profile("dev")` (Compose sets `SPRING_PROFILES_ACTIVE=dev`). `scripts/smoke.sh` and `KioskWithoutDevProfileTest` guard this — keep them green when promoting a stub.

**Gateway quirks** (`nginx-gateway/nginx.conf`): `proxy_pass` uses variables plus Docker's resolver so the gateway boots before its upstreams. `/api/` and `/services/` have catch-all JSON 404s because `web-frontend`'s own nginx proxies those prefixes back to the gateway; removing them creates a request loop. Every health path in `web-frontend/src/catalog.ts` must exist in `nginx.conf` (a render test checks it).

**Health is real.** Java services expose `/actuator/health`, DOWN (503) while PostgreSQL is unreachable; the kiosk adds a `sqliteQueue` component that opens the SQLite file and requires WAL mode. Compose `healthy` therefore means "can serve".

**Ports and SOLID claims.** The binding rules are AGENTS.md "Pragmatic SOLID"; the reasoning, defence questions and the evidence table are in [docs/SOLID-GUIDELINES.md](docs/SOLID-GUIDELINES.md). What spans several files: each port (`ITurnstileAdapter`, `IKioskSyncAPI`, `IAttendanceLocalRepository` in the kiosk; `IAttendanceRepository` in `attendance-processor`) has exactly one production implementation, so OCP/LSP are `DESIGNED`, not demonstrated. Tests substitute them differently on purpose: lambdas for `ITurnstileAdapter` and `IKioskSyncAPI`, a `FakeRepository` for `IAttendanceRepository`, the real SQLite file (no fake) for `IAttendanceLocalRepository`. Dependency direction is checked by `KioskArchitectureTest` and `AttendanceArchitectureTest`, e.g. `mvn -B -ntp -pl kiosk-agent test -Dtest=KioskArchitectureTest`. Before writing a SOLID claim into code comments, docs or a task summary, read the class and run the test behind it. `GateCommandStatus.UNSUPPORTED` has no producer or consumer yet: do not add an adapter that only returns it.
