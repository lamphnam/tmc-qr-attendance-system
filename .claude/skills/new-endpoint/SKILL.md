---
name: new-endpoint
description: Add a REST endpoint, or promote a 501 stub to a real route, in a TMC service while keeping every file that must stay in sync (contract doc, layering, gateway route, tests, smoke checks, status). Use for "thêm endpoint", "implement route", any change under /api/v1, /api/local or /api/dev.
---

# new-endpoint

Work through this in order; every step maps to a file that silently drifts if skipped.

0. **Ownership.** Check `docs/OWNERSHIP.md`. Do not implement another member's module unasked. A contract change across services needs reviewers from both sides and an entry in `docs/DECISIONS.md` (skill `record-decision`).
1. **Contract first.** Find the route in `docs/API-CONTRACTS.md` (§1–§7). New or changed shape → edit that doc, keep the DRAFT label until both owners agree.
2. **Layers** (`docs/ARCHITECTURE.md` "Dependency direction", enforced by the module's `*ArchitectureTest`):
   - `presentation`: controller = transport only; DTOs with validation; errors as `{code, message, correlationId}` via the service's `ErrorResponseDto` (copy, no shared library).
   - `application`: the use case and the transaction boundary.
   - `domain`: ports and rules, **no** Spring/`jakarta`/JDBC/HTTP types. `infrastructure`: the adapter. Constructor injection only.
   - Example to copy: `attendance-processor` — `AttendanceController` (production route, 501) vs `DevAttendanceController` + `AttendanceIngestionService` + `PostgresAttendanceRepository` (real logic).
3. **Not ready or not authenticated?** Answer `501 {"code":"NOT_IMPLEMENTED"}` using the service's existing stub pattern; never a fake success. A production route needs authentication (D04); anything unauthenticated is a dev fixture: `@Profile("dev")`, path `/api/dev/...`, flagged `dev_fixture`/`X-TMC-Dev-Only`.
4. **Gateway.** The `/api/v1/<area>/` prefix must exist in `nginx-gateway/nginx.conf`; a new prefix needs a `location` using the variable `proxy_pass` pattern. Keep the catch-all JSON 404s for `/api/` and `/services/` (they stop a proxy loop).
5. **Tests.** Use case and adapter against real stores (SQLite temp file in the kiosk; Testcontainers PostgreSQL in `attendance-processor`), a WebMvc contract test including the error shape, and a `tests/integration/` case that asserts **state** (rows, queue), not just a status code. Update section 4 of **both** `scripts/smoke.sh` and `scripts/smoke.ps1` (e.g. a 501 expectation becomes 200).
6. **Security.** No secret/token/TOTP/JWT in logs, responses or `toString`; the QR shared secret never reaches the browser; no stack traces or echoed request bodies.
7. **Docs and status.** Skill `update-status`; then `node scripts/check-docs.mjs`.
8. **Verify.** Skill `verify-all` (full run for anything that crosses the gateway).
