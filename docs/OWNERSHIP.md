# OWNERSHIP — owners, integration gates, PR workflow

Who builds what, what must be agreed before parallel coding, and how work is merged. Remaining work per person: [TODO](TODO.md).

## Owners [TEAM-PLAN: `SA_BTL_M2` rows 4–8]

| Member | Owns (implementation) | Owns (documentation) |
|---|---|---|
| Trần Võ Đại Khánh | `kiosk-agent`, `mock-turnstile-api`, SQLite, offline verification, anti-replay, SyncManager, turnstile adapter | Kiosk classes/sequences, SOLID evidence |
| Biện Anh Khôi | `attendance-processor`, `gate-station-service`, idempotent central ingestion, gate heartbeat / manual-open | Module View, DSM, ERD, C&C and sync sequences |
| Trần Lâm Anh Khoa | `user-qr-service`, gateway/auth integration, JWT/RBAC/TOTP/whitelist, audit | ADRs, API contracts, security tactics |
| Văn Bá Trọng Khiêm | `web-frontend`, employee QR/refresh, simulator offline toggle, HR/Admin/Guard UI | FR/NFR → code → test traceability, screenshots |
| Phạm Hoàng Nam | `reporting-service`, `mock-hr-payroll-api`, reporting/export, k6/JMeter/Locust and integration testing, **monorepo bootstrap** | Testing & Evaluation, Allocation View, final report/format |

**Clarification [ENGINEERING-GUARDRAIL]:** Nam built the *overall scaffold* and did not take over the other owners' implementation. If anyone implements another owner's module for an integration demo, record the commit/ownership and ask that owner to review. The `gateway` is shared (Khoa integrates).

## Integration dependencies

```text
Khoa  --QR + whitelist contract-->   Khánh
Khánh --attendance event + ack--->   Khôi
Khôi  --committed event schema--->   Nam
Nam   --reporting endpoints------>   Khiêm
Khôi  --manual-gate endpoint----->   Khiêm
Khoa  --auth / RBAC token-------->   Khiêm + Gate/Report services
Khánh --mock turnstile API------->   Kiosk
Nam   --mock HR API-------------->   Reporting
```

For the first PR each owner approves the interface *shape*; implementation details belong to the module owner.

## Interface gates — agree before merging business features [TEAM-PLAN + DRAFT]

| Boundary | Pair | Must agree | Reference |
|---|---|---|---|
| React ↔ User/QR | Khiêm + Khoa | token/auth flow, QR payload / `expiresAt`, error schema | API-CONTRACTS §1 |
| User/QR ↔ Kiosk | Khoa + Khánh | whitelist auth, factory scope, version, rotation, cache freshness | API-CONTRACTS §2 |
| Kiosk ↔ Attendance | Khánh + Khôi | UUID, `scanResult`, retry error classes, per-event ack, conflict semantics | API-CONTRACTS §3–5 |
| Kiosk ↔ Turnstile mock | Khánh | local API, mock result vs physical open, timeout behaviour | API-CONTRACTS §6 |
| Gate Service ↔ Kiosk | Khôi + Khánh | heartbeat and manual-open delivery, auth, offline behaviour | API-CONTRACTS §6, D11 |
| Reporting ↔ Attendance | Nam + Khôi | query ownership, accepted/rejected semantics, timestamp timezone | DATA-MODEL |
| Reporting ↔ Mock HR | Nam | export period, stable batch key, status/retry, failure injection | API-CONTRACTS §7 |
| Nginx ↔ all backends | Nam + Khoa | route prefix, path stripping, container DNS, health | ARCHITECTURE Part 2 |

Rules for every cross-member interface:
1. Every endpoint another member uses has an OpenAPI/example JSON **and** either a real implementation with documented auth/errors, or an honest `NOT_IMPLEMENTED` (HTTP 501) stub. Never a fake success.
2. One timestamp convention for persisted events: ISO-8601 UTC (millisecond precision, DECISIONS B06); state the zone explicitly for monthly export boundaries.
3. The shared PostgreSQL is a design fact, not a licence to touch each other's tables: one owner for `init-db/schema.sql` changes (D02), read-only dependencies elsewhere.
4. Share PRs early; run the smoke test before calling a feature done.

The DRAFT contracts become final only after team agreement **and** a recorded decision — do not silently remove DRAFT labels.

## Pull request workflow

1. Branch `feat/<module>-<topic>` from the latest main. Keep config compatible.
2. A PR states: affected FR/NFR/ADR; changed API/DB contract and who approved it; the commands run with their **real** outcome (or which environment was unavailable); tests for business cases and explicit failures; updated diagrams/traceability when the approved design changed; demo screenshots/logs when meaningful. No credentials, personal employee data or large binaries.
3. The module owner reviews changes to their module. **Cross-service schema/API changes need reviewers from both sides**; they must also update [API-CONTRACTS](API-CONTRACTS.md) / [DATA-MODEL](DATA-MODEL.md) and the OpenAPI.
4. Merge only after backend Maven tests, frontend build, `docker compose config` and the smoke test pass where the environment supports them.
5. Never overwrite another branch's files or rewrite collaborators' commits.
