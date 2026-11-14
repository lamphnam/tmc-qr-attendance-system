# DECISIONS — open decisions, bootstrap decisions, known limitations

1. [Decision register D01–D16](#decision-register) — what the team still has to settle, with its current status.
2. [Bootstrap and implementation decisions](#bootstrap-decisions) — concrete choices, still **proposals** pending owner sign-off.
3. [Known limitations](#known-limitations) — the canonical list to quote in the report and the demo.

Status values: **OPEN** nothing chosen · **PROPOSED** the scaffold chose something (see the B-id), awaiting sign-off · **RESOLVED** settled as policy.

## Decision register

| ID | P | Topic | Guidance | Status |
|---|---|---|---|---|
| D01 | P0 | Contract fields for QR / event / sync ack | DRAFT in [API-CONTRACTS](API-CONTRACTS.md); Khoa–Khánh–Khôi must agree before parallel implementation | **PROPOSED** B04–B07, B21 |
| D02 | P0 | DB migration owner / strategy | Shared PostgreSQL is M1; pick one migration strategy and avoid duplicate DDL | **PROPOSED** B01 |
| D03 | P0 | Scope of Nam's bootstrap | Runnable scaffold + optional thin dev slice; never silently implement the five members' modules | **RESOLVED** (scaffold + dev slice only) |
| D04 | P0 | Auth boundary kiosk → central | M1 expects HTTPS/mTLS; local Compose may be HTTP-only **explicitly dev-only**; kiosk endpoints must not ship open as production defaults | **OPEN** (B04, B14, B15 are dev stopgaps) |
| D05 | P0 | Offline simulation | Stopping central ≠ WAN isolation; toggle sync or the network boundary without cutting the local turnstile | **PROPOSED** B08 |
| D06 | P1 | Rejected-scan persistence | M1 says rejected scans are logged; define schema, retention and which events sync | **OPEN** (B11 keeps both options) |
| D07 | P1 | Multi-kiosk anti-replay | Local `(employeeId, step)` only stops same-kiosk replay; no global anti-replay while offline | **OPEN** (limitation) |
| D08 | P1 | Whitelist / secret protection and revocation | Kiosk at-rest protection implemented; key lifecycle, Central at-rest protection, cross-service secret mapping and offline revocation remain open | **OPEN** (B12; [kiosk implementation](#d08-kiosk-secret-protection-2026-11-14)) |
| D09 | P1 | HR mock failure code | Examples say 500 and 503; handle any retryable 5xx, never one literal code | **PROPOSED** B09 |
| D10 | P1 | HR export period and stable batch id | Define previous calendar month, duplicate manual trigger, storage and dedupe semantics | **OPEN** |
| D11 | P1 | Remote manual-gate command | M1 wants a dedicated authenticated channel but gives no protocol; do not fake end-to-end | **OPEN** |
| D12 | P1 | Clock dependencies | NTP / skew checks for RFC 6238; testable with an injectable clock | **OPEN** (kiosk code already injects a `Clock`) |
| D13 | P1 | "Zero data loss" claim | A disk failure before sync can lose events; scope the claim | **RESOLVED** (policy: [Known limitations](#known-limitations)) |
| D14 | P2 | Message broker / Redis | The baseline rejects a broker; Redis is optional; never added automatically | **RESOLVED** (not added) |
| D15 | P2 | RAM claims | `-Xmx192m` is a heap cap, not a container limit or a measured metric | **RESOLVED** (policy) |
| D16 | P2 | Frontend language | Sheet says ReactJS, draft proposes TypeScript/Vite; may change | **PROPOSED** B10 |

## Bootstrap decisions

Added 2026-11-01 by the scaffold. Nothing here changes [BASELINE-M1] (4 services + kiosk, shared PostgreSQL, SQLite WAL, direct REST, no broker). Nothing is professor-approved. "Confirm with" names who must say yes before it counts as final; until then the DRAFT labels in the other docs stay.

| ID | Resolves | Decision taken by the scaffold | Why | Reversal / effect | Confirm with |
|---|---|---|---|---|---|
| B01 | D02 | One `init-db/schema.sql` (+ `seed.sql`) mounted into the official postgres image, applied **once on an empty volume**. No Flyway/Liquibase, no Hibernate `ddl-auto`, no service creates tables. Reset = `docker compose down -v` | One owner of DDL, no duplicates, simplest thing that works for a shared DB | For versioned migrations add Flyway in **one** place (not per service) and drop the initdb mount | Khôi, Khoa, Nam |
| B02 | - | Spring Boot **3.5.16**, Java 21, Maven. Spring Boot 4.x also exists on Maven Central; not chosen because 3.5 is the last, widely documented 3.x line and the 4.x modular starters / Jackson 3 changes are unfamiliar to the team | Lower risk for five people | Moving to 4.x is a deliberate team task; check Spring's support calendar before the final report if support status matters | All |
| B03 | - | Both mocks are zero-dependency **Node.js** servers, not Spring Boot | Tiny images, no JVM for a mock | Rewrite in Java only if the team insists | Khánh, Nam |
| B04 | D01, D04 | Kiosk → central currently posts to the **dev-only** `POST /api/dev/attendance/sync` through the gateway (`CENTRAL_SYNC_PATH`). Production `/api/v1/attendance/events\|sync` answer **501** until auth (D04) and the wire contract (D01) are agreed | No unprotected public ingestion endpoint | Khôi publishes the authenticated route, Khánh flips `CENTRAL_SYNC_PATH` | Khôi + Khánh |
| B05 | D01 | Wire format = the API-CONTRACTS DRAFT unchanged: single event `{attendanceId, employeeId, factoryId, gateId, direction, occurredAt, scanResult}`; batch `{kioskId, events[]}` → `{results[{attendanceId,status,reason?}], retryableIds, rejectedIds}`; statuses `STORED`, `ALREADY_EXISTS`, `RETRYABLE`, `REJECTED_PERMANENT`. Same id + different payload → **409 / REJECTED_PERMANENT, never overwrite** | Implements API-CONTRACTS + DATA-MODEL "Idempotency design" | A field rename means editing API-CONTRACTS, `AttendanceEventDto` and `RestAttendanceSyncClient` | Khánh + Khôi |
| B06 | D01 | Timestamps are **ISO-8601 UTC, millisecond precision** on the wire and in both stores (`occurredAt` truncated to ms) | A retried event must compare equal after a PostgreSQL round trip (microseconds) | Changing precision touches `AttendanceRecord` and `LocalAttendanceEvent` together | Khôi + Khánh + Nam |
| B07 | D01 | The kiosk treats **any failure of the whole sync request** (refused, timeout, 4xx, 5xx, unreadable body) as "assume nothing committed, retry the same ids". Only a per-event `REJECTED_PERMANENT` makes a row `FAILED` | A misconfigured route must not mark a whole queue FAILED | API-CONTRACTS said "4xx → FAILED"; this narrows it to per-event rejections | Khánh |
| B08 | D05 | Offline simulation = runtime switch `CentralSyncSwitch` (env `CENTRAL_SYNC_DISABLED`, `PUT /api/dev/kiosk/sync/offline`); stops **only** kiosk → central | Stopping central containers is a different failure (also tested) | - | Khánh |
| B09 | D09 | Mock HR failure injection accepts **any** 4xx/5xx (`SIMULATE_HR_DOWN=503` or `PUT /mock/hr/control/failure`); nothing is stored while failing; the receiver upserts by `attendanceId` and reports `duplicateBatch` | Do not rely on one literal code | Reporting treats every 5xx/timeout as retryable | Nam |
| B10 | D16 | Frontend = React 19 + TypeScript + Vite 7 | ARCHITECTURE default | - | Khiêm |
| B11 | D06 | `local_attendance_events.scan_result` allows `ALLOWED` and `REJECTED` (universal-schema option) but **nothing writes REJECTED yet**; D06 stays open | Keeps both options open | - | Khánh + Khôi |
| B12 | D07, D08 | Original bootstrap: cache/token tables unused and `employees.shared_secret_enc` NULL. Kiosk whitelist persistence and AES-256-GCM protection are now implemented; see [D08 update](#d08-kiosk-secret-protection-2026-11-14). Central employee-secret storage is superseded by B22: plaintext public synthetic dev `shared_secret` only. Production `used_tokens` integration and kiosk HTTP whitelist sync remain unimplemented | Preserve bootstrap history; record later crypto/storage choices explicitly | See B22 and D08 update | Khoa + Khánh |
| B13 | - | DATA-MODEL columns added: `attendance_events.source_kiosk_id` and `.dev_fixture` (marks rows written by dev fixtures so reports can exclude them). No FKs from `attendance_events` to employees/gates (immutable edge facts, possibly produced with a stale whitelist) | Traceability of the dev slice | Khôi may add reference validation at ingestion | Khôi |
| B14 | D04 | All central services share one **dummy** DB credential from `.env`; no least-privilege roles (e.g. an audit table the app can INSERT but not UPDATE/DELETE, NFR-8) | Scaffold only | Khoa must add restricted roles before claiming audit tamper evidence | Khoa |
| B15 | D04 | `SPRING_PROFILES_ACTIVE=dev` enables the unauthenticated `/api/dev/**` fixtures; Compose turns it on, every host port binds `127.0.0.1`, and the gateway forwards `/api/dev/attendance/` and `/api/dev/kiosk/` | Makes the slice demonstrable | Remove both gateway locations and the profile for any non-developer environment | Khoa |
| B16 | - | Database host port is **5433**, not 5432 | A local PostgreSQL or another project's container usually owns 5432 | `DB_HOST_PORT` | - |
| B17 | - | The kiosk's SQLite lives on a Docker **named volume**, not a bind mount | WAL needs a shared-memory file that Windows/macOS bind mounts can break | `kiosk-agent/data/` is only for non-Docker runs | Khánh |
| B18 | - | The mock turnstile contract (`POST /gates/{id}/open`, status, command log, failure injection) is invented here; API-CONTRACTS does not define it | Needed for the independent kiosk → turnstile test | Khánh may replace it | Khánh |
| B19 | - | Error body everywhere is `{code, message, correlationId}`; the gateway forwards or mints `X-Request-Id` and services echo it | API-CONTRACTS conventions | - | All |
| B20 | - | `attendance-processor` repository tests use real PostgreSQL 16 through Testcontainers and are **skipped (not faked)** without Docker | Real engines | CI needs a Docker-capable runner | Nam |
| B21 | D01, D04, D08 | 2026-11-14, requested M2 User/QR implementation: employee-ID lifecycle, audit viewer and kiosk whitelist at the requested `/api/v1` paths **only in dev**. Default profile returns 501; `/qr/generate` remains 501 pending JWT. Whitelist uses a configured kiosk key; employee endpoints/actor metadata remain unauthenticated demo data. Array whitelist with per-employee version, colon-delimited QR token | Delivers the requested demo without silently enabling unauthenticated production routes; explicitly requested v1 paths are an exception to the dev-path convention | Before production add JWT/RBAC, kiosk identity/factory authorization, TLS and approved token/snapshot contract. No kiosk implementation changed | Khoa + Khánh + Khiêm; Nam for integration |
| B22 | D02, D08 | 2026-11-14, requested schema/seed: plaintext **public synthetic** `shared_secret`, ACTIVE/REVOKED status, employee role and five additional IDs while retaining original fixtures. Supersedes B12 employee-secret placeholder. Audit UUID IDs, sequence ordering and SHA-256 previous-hash chain, serialized appends in the same transaction as QR mutations | Real lifecycle persistence, concurrent updates and rollback; canonical checksum encoding in DATA-MODEL; attendance tables unchanged | Empty-volume initialization only. No automatic migration/reset of existing data. Shared DB owner and no external anchor still preclude immutability claims | Khoa + Khôi + Nam; Khánh for secret format |
| B23 | - | 2026-11-14: narrow `UserqrArchitectureTest` exception for `javax.crypto..`, part of the JDK, to implement RFC 6238 via `Mac`. All other javax dependencies remain forbidden; framework/JDBC/layer rules retained, application JDBC prohibition added | Original blanket javax ban included standard Java cryptography explicitly required by ADR-02 | No Spring/Jakarta/JDBC exception; no rule disabled | Khoa; team architecture reviewer |

## Source contrasts (do not resolve silently)

1. **Official M2** requires *at least one main module*; the **team** plans many modules, UI and an end-to-end demo. State clearly in the report which is mandatory and which is aspirational.
2. The announcement says **Documentation before Implementation & Testing**; the Google Doc also says **Main Flow before the report**. Practical reading: contracts and boundaries first, report updated alongside the code, tests after a working vertical slice.
3. M1 uses **one central PostgreSQL shared by 4 services**. Do not turn it into database-per-service.
4. M1 gets the QR token from the server, not the shared secret. Never expose the secret to React to simplify things.
5. M1 stores the SQLite event before OPEN, so `ALLOWED` must distinguish authorization from a physical gate opening.
6. The sheet asks for source structure "including all features per the M1 design". For the bootstrap that meant boundaries, contracts and placeholders, **not** claiming features without owners and tests.

## Known limitations

Canonical list. Quote these instead of re-deriving them, and never contradict them in the report (policy: [PROJECT §5](PROJECT.md#5-claims-policy-canonical--other-docs-link-here)).

- **Security is incomplete.** Dev traffic is plain HTTP. Employee QR/lifecycle/audit endpoints lack JWT/RBAC, actor metadata is untrusted, and services share a dummy DB owner credential. The dev whitelist checks a shared kiosk API key without factory authorization. Kiosk whitelist material has local AES-256-GCM protection; Central `shared_secret` contains plaintext synthetic dev values only, with public dummy seed secrets. TLS 1.3, mTLS, Central encryption at rest and MFA remain unimplemented. This does not satisfy NFR-4 as a whole.
- **Dev attendance history is unauthenticated.** The profile-gated history route accepts factory as a filter only; it provides no access control. Use synthetic records on a trusted developer machine. There is no production History GET route or approved production history authorization contract.
- **Data durability.** SQLite WAL + `synchronous=FULL` protects against a process crash or restart, not against a physical disk failure, and depends on the filesystem. "Zero data loss" must not be claimed.
- **Offline revocation is not instant.** A kiosk keeps a stale whitelist until its next sync. A compromised kiosk exposes the secrets of the employees assigned to it.
- **Anti-replay is per kiosk.** `(employee_id, matched_time_step)` cannot stop reuse across kiosks while disconnected.
- **Audit.** User/QR now appends a SHA-256 hash chain atomically with lifecycle mutations. There is no restricted writer role or external anchor: the shared DB owner can rewrite rows/checksums or bypass the advisory lock. PUBLIC privilege revocation and the append-only port do not make the table immutable (B14/B22). The bounded viewer does not certify deletion of the tail or whole-chain completeness.
- **The mock turnstile proves nothing physical.** HTTP 200 is not "gate opened"; mock latency is not hardware latency.
- **A one-machine Compose stack simulates edge + central**; it is not evidence of a factory deployment, and `-Xmx192m` is not a memory claim (D15).
- **Not measured:** NFR-1 (≤ 2 s) and NFR-7 (≤ 30 s). Anything unmeasured is `NOT_RUN`, never a number from arithmetic.

## Change governance

Log open decisions in this file with date, owner, chosen answer and the effect on FR/NFR/ADR, API and diagrams. Keep [BASELINE-M1] intact unless a documented ADR supersedes it with the group's approval.

## D08 kiosk secret protection (2026-11-14)

Implemented at the assigned user's explicit request: AES-256-GCM in kiosk Infrastructure, with an external 32-byte Base64 key (`KIOSK_WHITELIST_KEY_BASE64`), a fresh random 96-bit nonce per encryption, a 128-bit tag, a versioned local envelope and authenticated row/snapshot context. The existing SQLite repository encrypts/decrypts without changing schema, HTTP contracts, domain ports or attendance transactions. Missing keys fail secret operations; malformed key configuration, wrong keys and altered envelopes expose only fixed safe domain errors. There is no fallback to plaintext or automatic reset.

Scope: the kiosk part of D08 / NFR-4 and the whitelist prerequisite for ADR-02. It does not complete FR-3.1, kiosk TOTP verification, anti-replay or NFR-4; User/QR TOTP is implemented separately (B21). Key custody/provisioning, key rotation/migration, the agreed Central-to-kiosk secret mapping and revocation policy remain for Khoa/Khánh and the team. The format version supports explicit future codecs; no keyring, key ID or legacy migration is claimed. Preserve the key externally for database recovery. Whole-database integrity/rollback detection and protection from a compromised kiosk process are outside this change. Detailed storage/error behavior: [kiosk README](../kiosk-agent/README.md#local-whitelist-key-d08).

## Whitelist application boundary (2026-11-14)

At the assigned user's request, `IWhitelistSnapshotSource` and `SyncWhitelistUseCase` define a local domain/application boundary before the HTTP agreement. They reuse immutable snapshots, the existing unavailable-Central/cache failures and atomic store contract. The application records its Clock time after retrieval, while only the store compares the source version against durable state under a write transaction. No separate pre-read/version check, HTTP transaction wrapper or extension to attendance sync is introduced.

This application-core step did not resolve D01/D04/D08 remote-policy questions or change API/schema/M1 diagrams. Central now has the separate dev whitelist endpoint from B21, but no kiosk-side Central source adapter, whitelist scheduler or automatic use-case wiring exists. Khoa/Khánh must still agree trusted response-to-domain mapping, scope/authentication, secret material mapping and version/unchanged-response semantics: Central currently returns an employee array with per-employee secretVersion, not a global snapshot version. That employee version cannot be substituted for the store's snapshot version. The later composition root must supply one shared use-case instance and the existing switch/Clock/identity. Behavior/results and instance-local concurrency limits are described in [kiosk README](../kiosk-agent/README.md#whitelist-sync-application-boundary).

## Whitelist HTTP transport boundary (2026-11-14)

At the user's explicit request, kiosk Infrastructure now has an independently callable transport for B21's existing JSON array, with factory from configured identity and request-local `X-Kiosk-Api-Key`. It preserves Central's secret String exactly and performs full response validation without converting it to a domain snapshot. The separate HTTP credential comes from `KIOSK_API_KEY`; it has no relationship to the AES key. Existing shared client timeout settings are reused; no retry/scheduler or HTTP transaction wrapper is added.

This implements transport, not `IWhitelistSnapshotSource` or HTTP whitelist synchronization into SQLite. A global revision is not synthesized from employee versions, timestamps, hashes or a local counter. D01/D04/D08 questions stay open. Missing credentials/HTTP/parser failures expose fixed technical reasons without body/cause diagnostics. Empty-array success cannot establish authorized factory/kiosk identity or freshness. See [transport behavior and limitations](../kiosk-agent/README.md#whitelist-http-transport-only).

## Independent mapping and conditional composition (2026-11-14)

The requested preparation adds wire-to-domain employee mapping using the exact UTF-8 encoding of the String; it does not decode the base64-looking text used by Central. Missing/blank HTTP secrets and unsupported wire statuses remain invalid, without changing the domain's separate nullable-material policy. No source metadata or version is created. Composition prepares a default singleton only when an existing source port bean is registered; without one, transport/mapper/cache still start independently, including with no HTTP key. No source placeholder, scheduler or HTTP write path is provided. A future source must supply valid authenticated scope/revision and be registered before conditional wiring is evaluated. D01/D04/D08 remain open; API/schema and version semantics are unchanged. Details: [mapping and wiring](../kiosk-agent/README.md#employee-mapping-and-conditional-wiring).

## Dev attendance history boundary (2026-11-14)

At the attendance owner's explicit request, a dev-only GET controller reuses the existing history service/read port
and PostgreSQL query without changing ingestion, idempotency or schema. Since no production history contract exists,
the minimal dev wire shape is recorded separately in [API-CONTRACTS](API-CONTRACTS.md#dev-only-attendance-history-draft).
It keeps the existing non-fixture policy and bounded, stable half-open-range query; no aggregation or reporting-service work is added.
The module remains DEV_SLICE. Factory filtering is not authorization; no mock authentication or production GET route is added.
Production history identity/roles/scope and the cross-module contract remain open for owner review (D01/D04).

## Kiosk HTTP snapshot adapter boundary (2026-11-14)

The user reports Khoa's agreement on a positive long `X-Whitelist-Version`, monotonically increasing per factory
and covering all whitelist membership/secret/status changes, with the existing array body and exact UTF-8 material.
Kiosk now implements that mapping in an explicitly constructed `RestWhitelistSnapshotSource`, without changing
the canonical API document, Central code or schema in this task. Earlier transport/core entries above record the
state before that agreement; they do not describe the latest adapter availability.

No real source bean, scheduler or HTTP-to-cache path is activated. The existing version guard, SQLite transactions
and AES-GCM remain unchanged. Configured identity and factory row checks are not authorization: the dev key is still
not factory-bound. Central must return a complete factory view and revision consistently, including an empty view;
its actual implementation and end-to-end integration remain unverified here. See [Kiosk source boundary](../kiosk-agent/README.md#http-snapshot-source-not-activated).
