# kiosk-agent — DEV_SLICE

Owner: **Khánh** · package `vn.edu.hcmut.tmc.kiosk` · port 8080 (host 8085) · gateway `/api/local/**`, `/api/dev/kiosk/**`. Status context: [docs/STATUS.md](../docs/STATUS.md) · remaining work: [docs/TODO.md](../docs/TODO.md#2-khánh--kiosk-agent-mock-turnstile-api-sqlite-syncmanager) · class status: [docs/TRACEABILITY.md](../docs/TRACEABILITY.md#kiosk-agent-khánh).

Offline-first edge agent. The **durable SQLite queue and the store-and-forward sync are real and tested**; the **actual QR scan (TOTP, whitelist, anti-replay) is not implemented**, so `POST /api/local/scan` answers `501`.

The local whitelist domain and SQLite snapshot store are implemented, including AES-256-GCM protection of non-null verification material (D08). HTTP → use case → SQLite and periodic whitelist sync are implemented behind a flag defaulting to false, tested with mock Central. Real Central revision and scan/TOTP integration remain unverified/unimplemented respectively. The label remains DEV_SLICE.

`SyncWhitelistUseCase`, the HTTP source and its lifecycle scheduler are singleton beans only when `KIOSK_WHITELIST_SYNC_ENABLED=true`. The default is false in every profile; the first scheduled fetch waits a complete interval. See [whitelist scheduling](#whitelist-scheduling).

`WhitelistHttpTransport` implements HTTP fetching/validation of Central's employee array. It is an Infrastructure-only component, not an `IWhitelistSnapshotSource` implementation, and creates no trigger itself. After opt-in, scheduled or explicit use-case calls can persist valid snapshots. See [HTTP transport](#whitelist-http-transport-only).

| Route | Status |
|---|---|
| `POST /api/local/scan` | **NOT_IMPLEMENTED** (501). Never returns ALLOWED. Implementation brief: [handoff](#handoff-qr-scan-for-khánh-2026-11-16) |
| `POST /api/dev/kiosk/synthetic-event` | **DEV-ONLY**. Synthetic event with **no QR/TOTP check**: commit PENDING to SQLite, *then* call the local turnstile |
| `GET /api/dev/kiosk/queue` | **DEV-ONLY** counts + recent rows read from SQLite |
| `POST /api/dev/kiosk/sync/run` | **DEV-ONLY** one sync pass now (a scheduler also runs every 5 s) |
| `PUT /api/dev/kiosk/sync/offline` `{"disabled":true}` | **DEV-ONLY** stops **only** kiosk → central sync (D05); the turnstile keeps working |
| `GET /actuator/health` | UP only if the SQLite file opens, the queue table is readable and `journal_mode = wal` (`sqliteQueue` component) |

## What the slice guarantees
- **Order (ADR-01):** the event is committed to SQLite as `PENDING` *before* the open command is sent; a test proves the row is visible from a fresh connection at the moment the turnstile is called.
- **Sync status ≠ gate status.** `sync_status` (`PENDING|SYNCED|FAILED`) is central synchronisation only; `gate_command_status` (`SENT|FAILED|…`) is whether the command was accepted. `SENT` against the mock proves nothing physical.
- **SYNCED only after an ack naming the id.** Unacknowledged ids stay `PENDING` with exponential backoff; a whole-request failure (refused, timeout, 4xx, 5xx) is never a rejection — only a per-event `REJECTED_PERMANENT` makes a row `FAILED` (DECISIONS B07).
- **Durability scope:** WAL + `synchronous=FULL` cover a process crash or restart, not a disk failure ([limitations](../docs/DECISIONS.md#known-limitations)). Use the named volume, not a Windows bind mount (B17).

## Configuration (env, see `src/main/resources/application.yml`)
`KIOSK_ID`, `KIOSK_FACTORY_ID`, `KIOSK_DEFAULT_GATE_ID`, `SQLITE_PATH` (`/data/kiosk.db` in Compose, `./data/kiosk.db` otherwise), `CENTRAL_BASE_URL`, `CENTRAL_SYNC_PATH`, `CENTRAL_SYNC_DISABLED`, `TURNSTILE_BASE_URL`, `KIOSK_SYNC_ENABLED`, `KIOSK_SYNC_INTERVAL_MS`, `KIOSK_WHITELIST_SYNC_ENABLED` (false by default) and `KIOSK_WHITELIST_SYNC_INTERVAL_MS` (60000 by default). Whitelist settings are independent of Attendance Sync.

## Tests
`mvn test` uses real SQLite files (the production `SqliteConfig`), real loopback HTTP, ArchUnit layering and a full in-JVM offline → PENDING → reconnect → SYNCED walk-through. No mocking framework is used for the stores or HTTP.

## Local whitelist key (D08)

Provision `KIOSK_WHITELIST_KEY_BASE64` externally: standard Base64 encoding of **32 cryptographically random bytes** (AES-256). It maps to `kiosk.whitelist.secret-key-base64`; Compose forwards it only to the kiosk. There is no default, generated fallback, hardcoded key or key stored in SQLite. Keep the provisioned value out of source control, command-line arguments, logs and the SQLite volume; `.env.example` deliberately leaves it blank. Environment delivery is not a key vault: the kiosk process and privileged host/container administrators can access it.

An absent/blank key permits queue-only startup and snapshots with no secret material; encrypting material or reading any encrypted row raises the fixed domain error `UNAVAILABLE`, including for an unrelated employee lookup. Invalid Base64/key size fails bean creation with that same safe error. A wrong 32-byte key, altered non-null envelope or unsupported format raises `INVALID_CACHE`. None of these failures becomes a cache miss, success or plaintext fallback. Preserve the same external key across restarts; restore a matching key if configuration is lost. Changing the environment key cannot rotate existing ciphertext automatically.

`shared_secret_enc` remains the existing BLOB column. Its v1 envelope is `TMCW` (4 bytes), version `1` (1 byte), a new random nonce (12 bytes), and ciphertext with a 128-bit GCM tag. The header and length-prefixed UTF-8 context are authenticated: kiosk ID, factory ID, employee ID, QR status, secret version, snapshot version, millisecond timestamp and employee count. Moving a BLOB to another employee or changing this context therefore fails authentication. Java JCE performs encryption/authentication; no custom cryptographic primitive is implemented.

Encryption occurs inside the existing `BEGIN IMMEDIATE` snapshot replacement; employees and metadata commit together, and failure rolls back the replacement. Reads decrypt inside the existing consistent read transaction. Temporary plaintext copies obtained from the domain accessor and from decryption are cleared after use; the domain retains its defensive copy. This does not guarantee erasure of all JVM/provider memory copies.

The envelope is a **local storage format**, not a Central HTTP encoding. Material stays `byte[]`; Central encoding, provisioning/key custody, multi-key rotation/migration and offline revocation policy still need owner agreement. Existing opaque/plaintext BLOBs are never interpreted as legacy secrets. GCM authenticates present encrypted material and its context, not the whole database: it cannot detect a coordinated database rollback or distinguish a removed BLOB (SQL NULL) from legitimately absent material. This also does not protect against a compromised kiosk process holding the key.

## Whitelist sync application boundary

The framework-free `SyncWhitelistUseCase` receives `IWhitelistSnapshotSource`, `IWhitelistSnapshotStore`, `CentralSyncSwitch`, `KioskIdentity` and `Clock` through its constructor. `IKioskSyncAPI` and Attendance Sync are unchanged. Conditional composition-root wiring is now prepared for **one shared use-case instance per kiosk** using the existing store, switch, identity and Clock beans. No unavailable/no-op Central bean is supplied merely to make wiring succeed; the current application continues booting without the new source.

One `run()` fetches a full snapshot outside the store, validates kiosk/factory scope, and assigns local `cachedAt` from the injected Clock after retrieval. The source timestamp is provisional, not a Central freshness claim. The immutable domain models validate version/count/employee invariants; the store compares versions atomically under its existing write lock. An equal/older version does not refresh metadata. A valid empty snapshot initializes/clears the cache, including version zero when uninitialized. No TTL, network retry schedule or rotation policy is added.

An atomic non-reentrant guard covers download through persistence completion; a concurrent or recursive call returns immediately. It protects callers sharing that use-case instance, not separate instances/processes. Offline is checked before fetching and again before storage. Switching offline during a fetch discards a valid candidate; an already-started fetch/transaction is not forcibly cancelled. A future adapter must enforce its own bounded network waits.

| `WhitelistSyncReport.status` | Meaning |
|---|---|
| `UPDATED` | Store returned true after durable commit; report includes committed version |
| `NOT_NEWER` | Store rejected an equal/older candidate; report includes candidate version, which may differ from the current stored version |
| `OFFLINE` | Central synchronization disabled; no new persistence call |
| `ALREADY_RUNNING` | Another pass is active on this use-case instance |
| `SOURCE_UNAVAILABLE` | Source port reported unavailable Central/timeout |
| `INVALID_SNAPSHOT` | Source data rejected, null result, scope mismatch or invalid store input |
| `CACHE_UNAVAILABLE` | Persistence/material recovery/encryption/commit unavailable |
| `INVALID_CACHE` | Existing cache integrity failure; no automatic reset |

Reports carry only status and an optional version, with no employee, key, ciphertext or upstream exception text. Expected source errors use the existing `CentralUnavailableException` or `IllegalArgumentException`; model constructors also reject malformed data. Unexpected programming failures propagate, with the running guard still released. Central is scripted in `SyncWhitelistUseCaseTest`; real SQLite and AES verify cache preservation, encrypted commit failure and fetch/commit races. This is not an HTTP or end-to-end whitelist test.

The agreed Central mapping is now implemented by the inactive adapter below: source revision comes from a header and material is UTF-8. Central's actual revision implementation, authenticated factory/kiosk scope and production transport still require integration verification. This local port does not prescribe a JSON field or change the HTTP contract.

## Whitelist HTTP transport only

`WhitelistHttpTransport.fetchEmployees()` sends a single GET to the existing `/api/v1/kiosk/whitelist` with `factoryId` taken only from trusted `KioskIdentity`. It reuses `centralRestClient` and its existing connect/read timeouts (`1000`/`3000` ms by default). The credential is attached only to the whitelist request, not the shared client's default headers; redirects and retries are not added. There is no scheduler, startup fetch, use-case invocation or storage dependency.

Configure `KIOSK_API_KEY`, mapped to `kiosk.whitelist.api-key`. Compose forwards the environment value to the kiosk with an empty default; outside Compose provide it directly to the process. The existing `.env.example` contains a clearly synthetic dev credential for Central. No credential is generated or borrowed from `KIOSK_WHITELIST_KEY_BASE64`, which remains exclusively the local AES encryption key. Missing/blank HTTP credentials permit queue-only startup but fail before sending any request. Credential configuration is kept out of records with generated `toString()` methods.

Only HTTP 200 with JSON array data succeeds, including `[]`. Every row must have all eight current DTO fields with correct JSON types, ACTIVE/REVOKED status, positive integer `secretVersion`, valid Instant `updatedAt`, nonblank ID/material and the configured factory. Duplicate IDs (including IDs that would collide after existing domain whitespace normalization), duplicate JSON fields, trailing JSON or invalid rows reject the entire response; invalid rows are never silently dropped. Extra unknown fields are ignored. Names/department remain strings; no new business validation or secret encoding is invented.

The result is an immutable list of redacted `WhitelistEmployeeWireDto` records. `sharedSecret` remains the exact String supplied by Central, with no Base32/Base64 decoding, trimming or domain conversion. No snapshot/global version, expiry or TTL is calculated. These DTOs contain sensitive material in memory and must never be used as employee/browser response DTOs.

Failures use `WhitelistHttpException` with fixed messages, a reason and an optional non-200 HTTP status: `MISSING_CREDENTIAL`, `INVALID_CONFIGURATION`, `HTTP_ERROR`, `UNAVAILABLE`, `INVALID_RESPONSE`. No upstream body, credential or HTTP/Jackson exception cause is logged or attached. The new code does not log requests/payloads; header and body timeout tests use the production JDK client over loopback. The transport itself has no dependency on Application's `CentralSyncSwitch`; offline orchestration remains with the existing use case when a real source adapter can be integrated.

`fetchSnapshotResponse()` additionally captures all `X-Whitelist-Version` values, the configured request factory and
the validated array in one redacted, immutable response object. `fetchEmployees()` remains compatible with an older
Central response without that header; only the snapshot-source path requires it. HTTP tests use loopback/mock servers,
not a running Central; a raw transport result is not proof of authenticated scope or a newer revision.

## Employee mapping and conditional wiring

`WhitelistEmployeeMapper.mapEmployees()` is an independent Infrastructure field mapping from the wire DTO list to immutable `WhitelistEmployee` values. It maps ID/status/employee secret version and the exact UTF-8 bytes of the secret String, without Base32/Base64 decoding or trimming material. ACTIVE and REVOKED are the current wire statuses; the DTO rejects null/blank secrets or unsupported statuses even though the domain permits absent material for other sources. The mapper rejects null rows, duplicate normalized IDs and malformed Unicode, clears temporary byte buffers after the domain has copied them, and never logs input. JVM-wide erasure of all material copies is not claimed.

The mapper creates no `WhitelistMetadata`/`WhitelistSnapshot`, source scope or global version. It does not infer authority from factory fields and has no HTTP/storage dependency; the snapshot source below handles request-scope checks. `WhitelistHttpConfig` exposes the stateless mapper alongside the existing transport without connecting or invoking them.

`KIOSK_WHITELIST_SYNC_ENABLED` maps to `kiosk.whitelist.sync-enabled`; it is a startup flag, not a runtime toggle.
When false/missing, the application provides neither the source nor the whitelist use-case bean; existing transport,
mapper, local cache and Attendance Sync remain available. When true, Infrastructure's `WhitelistHttpConfig` supplies
`RestWhitelistSnapshotSource` using the existing transport/mapper/identity/Clock, and the composition root supplies
one shared `SyncWhitelistUseCase` using the existing store/switch/identity/Clock. Both definitions back off when an
explicit replacement bean exists. The composition root imports the HTTP configuration; use-case construction resolves
the source normally, without a registration-order-dependent `@ConditionalOnBean` check.

Opt-in now starts a delayed periodic trigger, described below. There is **no immediate startup fetch or new public
endpoint**. Explicit test/Java callers may still obtain the shared use-case bean and call `run()`; do not construct
another instance. `/api/dev/kiosk/sync/run` remains Attendance-only. Missing API keys or a headerless Central do not
prevent startup: scheduled/explicit runs return the existing safe failure reports and keep the old cache. Offline remains
controlled by `CentralSyncSwitch`; enabling the feature does not override it. `.env.example` and Compose forward
the flag with a false default; `KIOSK_API_KEY` and `KIOSK_WHITELIST_KEY_BASE64` retain separate roles.

`WhitelistEmployeeMapperTest` checks UTF-8, supported statuses, invalid data, defensive copies and safe diagnostics. `WhitelistSyncWiringConfigTest` supplies explicit test-only revision-bearing fixtures to verify singleton sharing/backoff, existing Clock/identity/store reuse and the shared offline switch. Fixture commits use a temporary SQLite database; no HTTP array is assigned a fabricated revision or written to a real kiosk cache.

## HTTP snapshot source (explicit only)

`RestWhitelistSnapshotSource` implements the existing domain port using the transport, mapper, configured
`KioskIdentity` and an injected `Clock`. It has no Spring stereotype, scheduler, use-case call or storage dependency;
Infrastructure configuration registers it only after opt-in. It never writes SQLite directly.

The agreed response is the existing JSON array plus `X-Whitelist-Version`: a positive decimal `long`, monotonically
increasing per factory and covering membership changes, deletion/factory moves and rotate/revoke/activate.
The adapter reads body and header from the same GET, never from separate requests. Missing/blank, nonnumeric,
nonpositive or overflowing revision is invalid; repeated or comma-combined values must all represent the same
positive long, otherwise the whole response is rejected. No employee version, timestamp, hash or local counter is used.

Requested kiosk/factory must match local configuration, and the transport's configured factory must match the
source's factory even for `[]`. Existing row validation rejects foreign-factory/duplicate employees; mapping preserves
each `secretVersion` and exact UTF-8 material. A versioned empty array is a valid complete snapshot under the agreed
factory-filter contract. These checks bind local configuration only: the dev API key is not factory-authorized,
and no response kiosk identity or factory authorization is invented. Central must supply a complete factory view
and its revision consistently; the client cannot prove that a well-formed response omitted no rows or is fresh.

Clock time is provisional `cachedAt`, not a revision. The unchanged use case/store will own offline checks,
serialization and atomic `replaceIfNewer` comparison. Invalid response/revision/scope/configuration
becomes a fixed `IllegalArgumentException`; missing credentials, HTTP failures (including 403/501/503), timeout and
connection failures become a fixed `CentralUnavailableException`. Neither includes upstream causes or payloads.

Central's revision code is not assumed merged or verified locally. Until it is available, the adapter rejects the
old headerless response; legacy employee-only transport remains callable. A real Central revision integration test
remains separate work. Adapter-only and explicit wiring evidence remain in STATUS §§14–15; latest scheduler evidence
is in [STATUS §16](../docs/STATUS.md#16-whitelist-scheduler-2026-11-14).

## Whitelist scheduling

Enable deliberately with `KIOSK_WHITELIST_SYNC_ENABLED=true`. False/missing creates no whitelist scheduler,
source or use-case bean and makes no scheduled whitelist request. `KIOSK_WHITELIST_SYNC_INTERVAL_MS` maps to
`kiosk.whitelist.sync-interval-ms`, defaults to **60000 ms**, and must be an integer in **1000..86400000 ms**.
An invalid interval fails enabled startup; the disabled feature ignores its unused interval. Restart to change these
deployment settings. This interval is a scheduling delay, not a cache TTL or a rotation policy.

`WhitelistSyncScheduler` starts through Spring `SmartLifecycle` after bean initialization. The first pass waits
one full interval; each later pass waits the same delay after the previous pass finishes. Its single daemon worker,
`tmc-whitelist-sync`, is private, not a Spring TaskScheduler/ScheduledExecutorService bean, so it cannot become
Attendance's default scheduler or occupy that scheduler's thread. No new endpoint or eager startup request is added.

Every pass calls only the existing singleton use case. Offline skips HTTP; its existing non-reentrant guard also
arbitrates explicit concurrent calls. Scheduler lifecycle synchronization protects start/stop only, not the sync
operation. HTTP remains outside SQLite transactions; source revision and atomic commit rules are unchanged.
Stopping cancels future ticks, does not interrupt an in-flight commit, and waits up to 10 seconds for the worker;
an unusually long in-flight pass can finish after that wait. No disk-loss or cross-process locking guarantee is added.

UPDATED is logged at INFO with the numeric revision; idle/offline/already-running outcomes at DEBUG; expected
source/cache failures at WARN using only the safe status enum. Unexpected RuntimeExceptions produce a fixed WARN,
without exception text/cause, and do not cancel the next scheduled execution. Old cache is preserved according to
the use-case/store contract. There is no extra retry loop or second sync lock.

Tests cover lifecycle/timing, invalid/default configuration, offline and singleton concurrency, plus a real scheduled
HTTP-mock 503 → next-cycle encrypted SQLite commit. Central's actual monotonic revision/consistent factory snapshot
and production factory authorization still need verification; enabling the flag does not establish either.

## Handoff: QR scan for Khánh (2026-11-16)

Written by Nam from the merged `develop` (`d486668`) so the scan can be built tonight without re-reading every branch. It describes what exists and what the other modules expect; the design choices marked **proposal** are yours to change (tell Khiêm if the wire shape changes, and record contract changes with the `record-decision` skill).

### What you can reuse as is

| Need | Already in the code |
|---|---|
| Whitelist lookup | `IWhitelistReader.lookup(employeeId)` (implemented by `SQLiteWhitelistRepository`, always a bean). Returns `WhitelistLookup`: empty `metadata` = cache never initialised; metadata present + empty `employee` = unknown employee. Throws `WhitelistCacheException` on storage/key/integrity failure — never treat that as a miss |
| Secret for TOTP | `WhitelistEmployee.secretMaterial()`: the **exact UTF-8 bytes** of Central's `sharedSecret` string (no Base32/Base64 decoding). This is the HMAC key User/QR uses, so codes match. It may be `null` (material unavailable) |
| Revocation / rotation | `WhitelistEmployee.qrStatus()` (`ACTIVE`/`REVOKED`) and `secretVersion()` |
| Durable queue + gate order | `IAttendanceLocalRepository.insertPending(...)`, then `ITurnstileAdapter.openGate(gateId)`, then `updateGateCommandStatus(...)`. Copy the order from `QueueSyntheticEventUseCase` |
| Anti-replay table | `used_tokens(employee_id, matched_time_step, used_at)`, PK `(employee_id, matched_time_step)`, present in `sqlite/schema.sql`, unused |
| Clock, identity | `Clock` and `KioskIdentity` beans (`KioskWiringConfig`) |
| Request DTO | `QrScanRequestDto(gateId, direction, qrPayload{empId, totp, ts, ver})`, TOTP redacted in `toString` |

### What to build

1. **TOTP verification**, identical to User/QR's `TotpCodeGenerator`: RFC 6238, HMAC-SHA256, key = `secretMaterial()`, counter = `epochSecond / 30` as an 8-byte big-endian long, dynamic truncation, 6 zero-padded digits. Accept the current step and the previous one only (ADR-02), compare with `MessageDigest.isEqual`, and return the **matched step**. Use the kiosk `Clock`, never the client's `ts`.
   **Watch the ArchUnit rule:** `KioskArchitectureTest.domainIsFrameworkFree` forbids `javax..` in `domain`, so `javax.crypto.Mac` cannot live there. Proposal: keep the window/truncation policy in `domain/service` behind a small port (e.g. an HMAC-SHA256 function) implemented in `infrastructure`. The alternative is a narrow `javax.crypto` exception like User/QR's B23, which needs a recorded decision.
2. **One atomic write for ALLOWED**: a new port method (proposal: `boolean insertPendingWithToken(LocalAttendanceEvent event, long matchedTimeStep)`) that inserts the `used_tokens` row **and** the PENDING event in one SQLite transaction and returns `false` if the token already exists (→ `REPLAYED`, no event, no gate command). `insertPending` alone is not enough.
3. **`ProcessQrScanUseCase`** in `application/usecase`: lookup → status/version checks → TOTP → atomic write → `openGate` → record the gate result. Measure `processingTimeMs` with `System.nanoTime()` inside the use case.
4. **`KioskScanController`** stays transport-only: validate the body, call the use case, map the result.

### Wire contract the frontend already uses

`/demo` (Khiêm) sends `{"gateId":"BD-G01","direction":"IN","qrPayload":{"empId":"EMP-001","totp":"482910","ts":1763280000,"ver":1}}` and reads (`web-frontend/src/demo/scan.ts`):

```json
{"result":"ALLOWED","attendanceId":"<uuid>","gateCommand":"SENT","processingTimeMs":25,"reason":null}
```

- Answer **HTTP 200 for both ALLOWED and REJECTED**; the page only parses a 2xx body as a scan result. Use `{code, message, correlationId}` errors for malformed input (400) and an unavailable/uninitialised cache (proposal: 503, never ALLOWED).
- `gateCommand` is a `GateCommandStatus` name. The page treats `SENT` as success and `FAILED`/`GATE_ERROR` as a problem; on REJECTED send `null` or `NOT_ATTEMPTED`.
- `reason` on REJECTED, with the mapping the page expects:

| Situation | `reason` |
|---|---|
| employee not in the cached snapshot | `UNKNOWN_EMPLOYEE` |
| `qrStatus = REVOKED` | `REVOKED` |
| `ver` ≠ cached `secretVersion` (code from before a rotate), or TOTP matches neither step | `INVALID_TOTP` (the page shows the same text for `EXPIRED`) |
| `(employee, matched step)` already in `used_tokens` | `REPLAYED` |
| `secretMaterial()` is null | not in the page's list yet — proposal `NO_SECRET`, agree it with Khiêm; never ALLOWED |

Rejected scans (D06, open, decide with Khôi): `local_attendance_events.scan_result` already allows `REJECTED` and `attendance-processor` accepts it; reporting and the HR export count `ALLOWED` only. If you store rejected rows, do not call the turnstile and keep `gate_command_status = NOT_ATTEMPTED`.

### Dependency status (updated 2026-11-17, after Khoa's commit `94e450f`)

- **Ready on `develop`: User/QR side (DECISIONS B27).** `GET /api/v1/kiosk/whitelist?factoryId=BD` sends `X-Whitelist-Version` (per-factory revision, starts at 1), and `GET /api/v1/qr/{id}` returns `periodSeconds: 30` and `qrPayload {empId, totp, ts, ver}`. `ts` is `issuedAt` in epoch **seconds**, not the time step: find the matching step from the TOTP (current + previous) and never trust `ts` alone. `ver` is the employee's `secretVersion`, so compare it with the cached `secretVersion` before checking the TOTP. A revoked QR answers `409 QR_REVOKED`. The schema changed, so run `docker compose down -v` once before `up`.
- **Not ready yet: whitelist in the running stack (Khôi, TODO §3).** The kiosk only fetches the whitelist when `KIOSK_WHITELIST_SYNC_ENABLED=true` (default `false` in Compose) and `KIOSK_WHITELIST_KEY_BASE64` is set (empty by default). The first fetch also waits one full interval. `KIOSK_API_KEY` is now the same in both services. To try it locally, export those two variables with a throwaway 32-byte key. For unit tests, seed the cache through `IWhitelistSnapshotStore.replaceIfNewer(...)` with a generated AES key, as `SyncWhitelistUseCaseTest` and `SQLiteWhitelistRepositoryTest` do.

### Things that must change together when the route stops answering 501

- `scripts/smoke.sh` and `scripts/smoke.ps1` expect `POST /api/local/scan` with `{}` to answer **501**; change both to the new response (e.g. 400 for `{}`).
- `KioskAgentApplicationTest` asserts the 501 (around line 139).
- `docs/API-CONTRACTS.md` §3, and status in `docs/STATUS.md`, `docs/TRACEABILITY.md` (`ProcessQrScanUseCase`, `TotpValidatorDomainService`, `ScanResultResponseDto` rows) and `web-frontend/src/catalog.ts`: use the `new-endpoint` and `update-status` skills.
- Add tests: TOTP boundaries (step edge, previous step, two steps back rejected, T03), replay of the same code, revoked/unknown/rotated employee, event + token committed together (and neither on failure), PENDING committed before `openGate`, and `KioskArchitectureTest` still green.
- Before pushing: `node scripts/verify.mjs` with Docker running (JDK 21), and report skipped counts honestly.

Not claimed by this handoff: any latency number (NFR-1 is `NOT_RUN` until measured) or "zero data loss" ([limitations](../docs/DECISIONS.md#known-limitations)).
