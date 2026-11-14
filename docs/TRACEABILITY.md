# TRACEABILITY — requirements ↔ code ↔ tests, and the Java class map

The evidence column records separate branch verification (bootstrap 2026-11-01; Kiosk and User/QR 2026-11-14). Preserved test results are pre-merge evidence, not a combined-tree test run; see [STATUS](STATUS.md). `TBD`/empty means nothing yet. Owners update their rows in the PR that changes the status; also update STATUS and `web-frontend/src/catalog.ts`. Status vocabulary: [STATUS](STATUS.md#status-labels); `NOT_MEASURED` = a measurable NFR with no measurement.

## FR / NFR / ADR matrix

`DEV_SLICE` rows describe partial, dev-only functionality using synthetic data and **do not satisfy the whole requirement**. Attendance/kiosk event fixtures bypass QR/TOTP; User/QR now has tested TOTP generation, while kiosk scan integration remains absent.

| ID | Design element | Owner | Expected test | Status | Actual evidence (class → test) |
|---|---|---|---|---|---|
| FR-1.1 | User QR ↔ HR sync | Khoa/Nam | hr-sync integration | NOT_IMPLEMENTED | mock side only: `mock-hr-payroll-api` `GET /mock/hr/employees` (node:test) |
| FR-1.2 / 1.3 / 1.4 | Dynamic QR, revocation, web UI | Khoa/Khiêm | TOTP generation, revoked QR | DEV_SLICE | `UserQrApplicationService` → domain/application/web tests; `UserQrPostgresIntegrationTest` (7 real PostgreSQL cases, passed 2026-11-14 before merge). Dev employee-ID issue/rotate/revoke/activate and whitelist only; JWT and browser UI absent; kiosk HTTP consumer absent, application core exists |
| FR-2.1 / 2.2 | Gate station, manual open, audit | Khôi/Khoa | guard role / manual gate | NOT_IMPLEMENTED | `GateStationController` routes answer 501 |
| FR-3.1 | Kiosk + SQLite + Processor | Khánh/Khôi | scan → ack → idempotency | DEV_SLICE | queue + sync + turnstile only; `POST /api/local/scan` answers 501 (`KioskAgentApplicationTest`). See ADR-01 rows |
| FR-3.2 | Reporting + UI | Nam/Khiêm | employee history authorization | NOT_IMPLEMENTED | Internal attendance history query core exists in attendance-processor (class map below); no history endpoint, authorization or UI. Reporting service remains unchanged |
| FR-4.1 / 4.2 | Reporting service | Nam | filtered factory report & dashboard | NOT_IMPLEMENTED | `ReportingController` routes answer 501 |
| FR-5.1 | JWT / RBAC | Khoa | per-role endpoint authorization | NOT_IMPLEMENTED | tables `roles`, `users`, `user_roles` only |
| FR-5.2 | Configuration | Khoa/Khôi | reload / bounds | NOT_IMPLEMENTED | — |
| NFR-1 | Kiosk fast path | Khánh/Nam | 800/30 min + p95/p99 | NOT_MEASURED | no real scan path exists yet |
| NFR-2 | SQLite WAL + direct sync | Khánh/Khôi/Nam | 20 offline + restart + lost ack | DEV_SLICE | `slice.test.mjs` "central really DOWN and a kiosk RESTART" (1 event, process restart); `SQLiteAttendanceRepositoryTest.pendingEventSurvivesReopeningTheDatabaseFile`. **Not** the 20-event T02 scenario, **not** disk failure |
| NFR-3 | HR batch dedupe | Nam | same Batch_UUID / attendance ids | MOCK | receiver side only: mock HR `node:test` (11) + `slice.test.mjs`; `reporting-service` sends nothing yet |
| NFR-4 | Encryption, MFA, secret handling | Khoa | credential + transport controls | NOT_IMPLEMENTED (overall) | Kiosk whitelist material protected with AES-256-GCM; local codec/persistence tests below. Central has plaintext synthetic dev secrets only (B22); transport protection, Central encryption at rest and MFA remain unimplemented |
| NFR-5 | Config-driven factory/gate | Khôi/Khánh | add a factory without code | partial | `KioskProperties` / `KioskIdentity` read kiosk id, factory, gate from env; `gates`, `kiosks` tables. No admin API/UI |
| NFR-6 | Monthly export + retry/alert | Nam | fake-clock schedule + failure | NOT_IMPLEMENTED | `hr_export_*` tables only |
| NFR-7 | Report query ≤ 30 s | Nam | report on a dataset | NOT_MEASURED | — |
| NFR-8 | RBAC, tamper-evident audit | Khoa | app permission + checksum integrity | DEV_SLICE | `AuditChecksumCalculatorTest` and `UserQrPostgresIntegrationTest`: per-field tampering, real row tampering, chain continuity under concurrent writers and transaction rollback passed 2026-11-14. No RBAC, restricted writer role or external anchor; not full immutability |
| ADR-01 | Offline + SQLite + direct sync | Khánh/Khôi | offline / restart / retry | DEV_SLICE | commit-before-open: `QueueSyntheticEventUseCaseTest.eventIsCommittedAsPendingBeforeTheGateCommandIsSent` · SYNCED only after ack: `SyncPendingEventsUseCaseTest` (6), `KioskAgentApplicationTest.scanWhileOfflineStaysPendingThenSyncsExactlyOnceAfterReconnect` · central idempotency: `AttendanceIdempotencyPostgresIntegrationTest` (6, real PostgreSQL, incl. 8-thread race), `slice.test.mjs` · offline switch (cuts only kiosk → central): `CentralSyncSwitch` |
| ADR-02 | TOTP, anti-replay | Khoa/Khánh | replay / revocation / skew | DEV_SLICE | User/QR TOTP implemented; `TotpCodeGeneratorTest` passed before merge: 6 RFC SHA256 vectors, boundaries, current/previous only, wrong secret/malformed tokens. Kiosk whitelist domain, atomic SQLite persistence, AES-256-GCM protection and SyncWhitelistUseCase application core are implemented/tested locally; see the class map below. Production `used_tokens` integration, kiosk scan/TOTP verification, kiosk HTTP whitelist adapter/scheduler and end-to-end scan remain unimplemented |
| ADR-03 | Monthly HR export | Nam | retry + idempotency + failure notification | NOT_IMPLEMENTED | mock receiver only |
| Layering | `presentation → application → domain ← infrastructure` | all | `mvn test` | READY | `*ArchitectureTest` in all 5 Java modules; User/QR now has all four layers and checks the JDK crypto exception explicitly (B23) |

Routes that must answer 501 instead of a fake success are asserted by `scripts/smoke.sh` section 4.

## Three views required [OFFICIAL-M2]

- **Module View:** package ownership / class dependencies, DSM, ERD — must reference the real source tree.
- **C&C View:** the three sequences and runtime connectors (edge ↔ central, kiosk ↔ turnstile, reporting ↔ HR) plus error paths ([RUNTIME-FLOWS](RUNTIME-FLOWS.md)).
- **Allocation View:** the real Compose / VM / container placement and the work-assignment table; a single-machine deployment is labelled a simulation.

Evidence format when a row is finished: `ID | class/path | method/endpoint | test command / test class | result | screenshot/log | limitations`.

## Java class map

Names the team proposed in its design trees (companion text files in the `NAM` Drive folder). Trees are design intent, **not proof a class exists** — the status column is. Package roots: `vn.edu.hcmut.tmc.kiosk`, `vn.edu.hcmut.tmc.attendance`, `.userqr`, `.gate`, `.reporting`. Owners may refactor, but if a class is renamed, record the mapping here so the M1 and M2 diagrams stay traceable.

### kiosk-agent (Khánh)

| Class (package under `kiosk`) | Status |
|---|---|
| `KioskAgentApplication` | done |
| `presentation/controller/KioskScanController` | exists; answers **501** (transport only when implemented) |
| `presentation/dto/QrScanRequestDto` | done (TOTP redacted in `toString`) |
| `presentation/dto/ScanResultResponseDto` | **not created** |
| `application/usecase/ProcessQrScanUseCase` | **not created** — verify offline, persist the durable event **and** the used token atomically, then command the gate via `ITurnstileAdapter`. `QueueSyntheticEventUseCase` shows the commit-then-open ordering |
| `application/usecase/SyncManagerScheduler` | done (trigger for `SyncPendingEventsUseCase`) |
| `domain/model/LocalAttendanceEvent` | done |
| `domain/model/WhitelistEmployee`, `WhitelistSnapshot`, `WhitelistMetadata`, `WhitelistLookup`, `QrStatus` | implemented; immutable local whitelist models, redacted/defensively copied material, initialized vs empty snapshot; `WhitelistModelsTest` |
| `domain/port/IWhitelistReader`, `IWhitelistSnapshotStore`; `domain/exception/WhitelistCacheException` | implemented; separate local read/update contracts, safe cache failure reasons |
| `domain/port/IWhitelistSnapshotSource`; `application/usecase/SyncWhitelistUseCase`, `WhitelistSyncReport` | implemented application core: dedicated remote capability, scoped snapshot validation, local Clock timestamp, offline checks, non-reentrant guard, durable store outcome. `SyncWhitelistUseCaseTest`: 23 passed, 0 failed/error/skipped on 2026-11-14 using real SQLite/AES and scripted Central. Existing store owns atomic version comparison. Conditional singleton configuration exists below; no source is registered in the present application, so no use-case bean, complete HTTP source adapter or scheduler is active |
| `infrastructure/sync/WhitelistEmployeeMapper`; `config/WhitelistSyncWiringConfig` | implemented independent UTF-8 employee mapping and conditional composition. WhitelistEmployeeMapperTest: 17; WhitelistSyncWiringConfigTest: 4 passed, 0 failed/error/skipped on 2026-11-14 (JDK 23 / release 21). Only test contexts supply explicit revision-bearing fixtures. Production creates no snapshot/scope/version, fallback source or HTTP-to-SQLite connection. [Mapping/wiring boundary](../kiosk-agent/README.md#employee-mapping-and-conditional-wiring) |
| `infrastructure/sync/WhitelistHttpTransport`, `WhitelistEmployeeWireDto`, `WhitelistHttpException`; `infrastructure/config/WhitelistHttpConfig` | implemented transport only: configured factory, request-local credential, existing client timeouts, complete array validation, safe errors/redaction. WhitelistHttpTransportTest: 69; WhitelistHttpConfigTest: 2 passed, 0 failed/error/skipped on 2026-11-14 (loopback HTTP/mock, JDK 23 / release 21). No domain source implementation, generated snapshot revision, use-case wiring, scheduler or persistence. [Transport boundary](../kiosk-agent/README.md#whitelist-http-transport-only) |
| `infrastructure/persistence/SQLiteWhitelistRepository` | implemented: existing DataSource/schema, scoped atomic replacement and consistent reads, empty/reopened snapshots, encrypted non-null material. `SQLiteWhitelistRepositoryTest`: 44 passed on 2026-11-14 (JDK 23, release 21), including authenticated context, missing/wrong keys, encrypted rollback and reader/writer race. No HTTP sync or scan integration |
| `infrastructure/security/WhitelistSecretCodec`; `infrastructure/config/WhitelistSecretConfig` | implemented: AES-256-GCM, versioned envelope, fresh nonce, authenticated row context, externally supplied key, safe failure without plaintext fallback. `WhitelistSecretCodecTest`: 27 passed; `WhitelistSecretConfigTest`: 3 passed, 0 skipped on 2026-11-14. Central encoding and key lifecycle remain open; see D08 and kiosk README |
| `domain/service/TotpValidatorDomainService` | **not created** — pure RFC 6238 HMAC-SHA256 with a `Clock` abstraction |
| `domain/port/IAttendanceLocalRepository`, `ITurnstileAdapter`, `IKioskSyncAPI` | done |
| `infrastructure/persistence/SqliteConfig`, `SQLiteAttendanceRepository` | done (not imported by the domain) |
| `infrastructure/turnstile/EthernetTurnstileAdapter` | done against the **mock** HTTP turnstile |
| `infrastructure/turnstile/ZKTecoTurnstileAdapter` | **not created** — would be a second implementation of the port, but an adapter that only returns `UNSUPPORTED` is not OCP/LSP evidence, and `UNSUPPORTED` must first be defined in the port contract ([SOLID-GUIDELINES](SOLID-GUIDELINES.md#liskov-substitution-lsp--high-priority)); never fake a proprietary protocol |
| `infrastructure/sync/RestAttendanceSyncClient` | done (posts to the dev sync path, B04) |
| *added by the bootstrap* | `QueueSyntheticEventUseCase`, `SyncPendingEventsUseCase`, `QueueQueryService`, `CentralSyncSwitch`, `SyncSettings`, `SyncRunReport`, `RetryBackoffPolicy`, `KioskIdentity`, `SqliteQueueHealthIndicator`, `DevQueueController`, `ApiExceptionHandler`, `config/` (composition root) |

### attendance-processor (Khôi)

| Class (package under `attendance`) | Status |
|---|---|
| `AttendanceProcessorApplication` | done |
| `presentation/controller/AttendanceController` | exists; answers **501** until auth + contract are agreed (B04) |
| `presentation/mapper/AttendanceSyncResponseMapper` | done (separate per-event ack mapping) |
| `presentation/dto/BatchSyncRequestDto` | done (+ `BatchSyncResponseDto`, `AttendanceEventDto`, `EventAckDto`, `ErrorResponseDto`) |
| `application/service/AttendanceIngestionService` | done (transaction orchestration, one transaction per event) |
| `domain/model/AttendanceRecord` | done |
| `domain/service/IdempotencyChecker` | done (classifies a repeated id; the PostgreSQL unique key is the concurrency-safe guard) |
| `domain/repository/IAttendanceRepository` | done (port in the domain) |
| `infrastructure/persistence/PostgresAttendanceRepository` | done |
| `domain/model/AttendanceHistoryQuery`, `AttendanceHistoryPage`; `domain/repository/IAttendanceHistoryQuery` | implemented internal read contract: employee + factory required, UTC millisecond [from, to), zero-based offset pages of 1..100 events, occurredAt/attendanceId ASC, fixtures excluded. Not an API/auth contract |
| `application/service/AttendanceHistoryService` | implemented with constructor-injected read port; `AttendanceHistoryModelsTest` (21) and `AttendanceHistoryServiceTest` (4) passed on 2026-11-14, JDK 23 / release 21 |
| `infrastructure/persistence/PostgresAttendanceHistoryQuery` | implemented parameterized JdbcTemplate SELECT, bounded page + hasNext, no write-repository changes. SQL NOT_TESTED: 8 AttendanceHistoryPostgresIntegrationTest cases skipped on 2026-11-14 (Docker unavailable). Application context test verifies real unreachable-DB failure propagation; no fake empty result |
| *added by the bootstrap* | `DevAttendanceController`, `ApiExceptionHandler`, `IngestionResult`, `domain/exception/*`, `DomainBeansConfig` |

### user-qr-service (Khoa)

All implemented classes below are dev-slice code; production authentication is not supplied.

- `presentation/controller/UserQrController`: employee-ID QR lifecycle and audit viewer; `KioskWhitelistController`: key-protected snapshot.
- `QrGenerateContractController`: M1 authenticated route remains 501; `UnimplementedQrController`: non-dev profile remains 501; `ApiExceptionHandler`: safe errors.
- `presentation/dto/`: `QrActionRequestDto`, `QrResponseDto`, `QrStatusResponseDto`, `WhitelistEmployeeDto`, `AuditLogResponseDto`, existing `ErrorResponseDto`.
- `application/service/UserQrApplicationService`: use cases/transactions; `QrIssue`: redacted result.
- `domain/model/`: `EmployeeQr`, `QrStatus`, `AuditEvent`, `AuditLog`; `domain/exception/`: missing/revoked/storage errors.
- `domain/service/TotpCodeGenerator`, `AuditChecksumCalculator`: deterministic JDK cryptography.
- `domain/repository/IEmployeeQrRepository`, `IAuditLogRepository` (append only), `IAuditLogQuery` (viewer only).
- `infrastructure/persistence/PostgresEmployeeQrRepository`, `PostgresAuditLogRepository`; `infrastructure/config/DomainBeansConfig`: clocks/random/domain/repository wiring.
- Evidence: 41 module tests passed with zero skipped on Alpine/JDK 21, 2026-11-14; `tests/integration/user-qr.test.mjs` passed through the real gateway/PostgreSQL stack as part of 9/9 integration cases.

### gate-station-service (Khôi), reporting-service (Nam)
The bootstrap provides only `*Application`, a `*Controller` of 501 stubs and `ErrorResponseDto`.

Rule: an empty class for every name in a design tree is **not** SOLID evidence. For an unimplemented feature prefer a clearly labelled stub (501) or a TODO in [TODO](TODO.md) over a class that pretends.
