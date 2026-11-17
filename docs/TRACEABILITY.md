# TRACEABILITY — requirements ↔ code ↔ tests, and the Java class map

The evidence column retains historical bootstrap and pre-merge Kiosk/User-QR verification. Latest H1/H2 evidence and Docker limitations are in [STATUS §20](STATUS.md#20-h1h2-demo-endpoints-2026-11-17); historical full PostgreSQL runs remain in §§13/19, not proof that the new H1 SQL tests ran. `TBD`/empty means nothing yet. Owners update their rows in the PR that changes the status; also update STATUS and `web-frontend/src/catalog.ts` when changing a module label. Status vocabulary: [STATUS](STATUS.md#status-labels); `NOT_MEASURED` = a measurable NFR with no measurement.

## FR / NFR / ADR matrix

`DEV_SLICE` rows describe partial, dev-only functionality using synthetic data and **do not satisfy the whole requirement**. Attendance/kiosk event fixtures bypass QR/TOTP; User/QR now has tested TOTP generation, while kiosk scan integration remains absent.

| ID | Design element | Owner | Expected test | Status | Actual evidence (class → test) |
|---|---|---|---|---|---|
| FR-1.1 | User QR ↔ HR sync | Khoa/Nam | hr-sync integration | NOT_IMPLEMENTED | mock side only: `mock-hr-payroll-api` `GET /mock/hr/employees` (node:test) |
| FR-1.2 / 1.3 / 1.4 | Dynamic QR, revocation, web UI | Khoa/Khiêm | TOTP generation, revoked QR | DEV_SLICE | `UserQrApplicationService` → domain/application/web tests and `UserQrPostgresIntegrationTest`; latest [K1–K4 verification](STATUS.md#18-userqr-k1k4-contract-alignment-2026-11-16). Dev issue/rotate/revoke/activate returns legacy fields plus `qrPayload`/`periodSeconds`, revoked GET returns 409 `QR_REVOKED`. Required factory whitelist has transactional revision/header/body, membership/transfer rollback and concurrent snapshot tests; `tests/integration/user-qr.test.mjs` checks gateway wire shapes. JWT/RBAC, HR sync and real kiosk scanning remain absent; `/demo` renders QR from the aligned shape |
| FR-2.1 / 2.2 | Gate station, manual open, audit | Khôi/Khoa | guard role / manual gate | NOT_IMPLEMENTED | `GateStationController` routes answer 501 |
| FR-3.1 | Kiosk + SQLite + Processor | Khánh/Khôi | scan → ack → idempotency | DEV_SLICE | Processor ingestion/idempotency verified against PostgreSQL; [latest evidence](STATUS.md#13-attendance-processor-postgresql-recheck-2026-11-14). Kiosk synthetic queue/sync exists, but `POST /api/local/scan` still answers 501; no complete production scan flow or new whole-stack acceptance claim UI: `/demo` columns 2–3 show the queue and each id's presence in PostgreSQL (`demoViews.test.tsx`, `central.test.ts`; Chrome check 2026-11-14) |
| FR-3.2 | Reporting + UI | Nam/Khiêm | employee history authorization | NOT_IMPLEMENTED | Attendance query core now has a dev-only inspection endpoint (class map below); production history, authorization and UI remain unimplemented. Reporting service remains unchanged |
| FR-4.1 / 4.2 | Reporting service | Nam | filtered factory report & dashboard | DEV_SLICE | `ReportQueryService` + `PostgresAttendanceReportReader` via dev-only `/api/dev/reports/attendance` and `/dashboard`: factory day in Asia/Ho_Chi_Minh, department ("team") totals, ALLOWED-only, fixtures excluded → `ReportingPostgresIntegrationTest.theReportGroupsByVietnameseDayJoinsDepartmentsAndCountsOnlyAcceptedRealScans`, `theDashboardCountsOneFactoryDayAndShowsTheLatestExport`. Missing: authentication/roles, **shift** dimension (no shift data in M1), production `/api/v1/reports/*` (501) |
| FR-5.1 | JWT / RBAC | Khoa | per-role endpoint authorization | NOT_IMPLEMENTED | tables `roles`, `users`, `user_roles` only |
| FR-5.2 | Configuration | Khoa/Khôi | reload / bounds | NOT_IMPLEMENTED | — |
| NFR-1 | Kiosk fast path | Khánh/Nam | 800/30 min + p95/p99 | NOT_MEASURED | no real scan path exists yet |
| NFR-2 | SQLite WAL + direct sync | Khánh/Khôi/Nam | 20 offline + restart + lost ack | DEV_SLICE | `slice.test.mjs` "central really DOWN and a kiosk RESTART" (1 event, process restart); `SQLiteAttendanceRepositoryTest.pendingEventSurvivesReopeningTheDatabaseFile`. **Not** the 20-event T02 scenario, **not** disk failure |
| NFR-3 | HR batch dedupe | Nam | same Batch_UUID / attendance ids | DEV_SLICE | sender: one batch per period with `batch_uuid` derived from the period (B24) → `ExportPeriodTest.batchUuidIsDeterministicPerPeriodAndDiffersBetweenPeriods`, `ReportingPostgresIntegrationTest.concurrentTriggersForOneMonthCreateOneBatchAndSendItOnce`, `reporting.test.mjs` (UUID recomputed in Node, `timesReceived` = 1 at the real mock HR); receiver: mock HR `node:test` (11) |
| NFR-4 | Encryption, MFA, secret handling | Khoa | credential + transport controls | NOT_IMPLEMENTED (overall) | Kiosk whitelist material protected with AES-256-GCM; local codec/persistence tests below. Central has plaintext synthetic dev secrets only (B22); transport protection, Central encryption at rest and MFA remain unimplemented |
| NFR-5 | Config-driven factory/gate | Khôi/Khánh | add a factory without code | partial | `KioskProperties` / `KioskIdentity` read kiosk id, factory, gate from env; `gates`, `kiosks` tables. No admin API/UI |
| NFR-6 | Monthly export + retry/alert | Nam | fake-clock schedule + failure | DEV_SLICE | `ExportSchedule` (01:00 VN, catch-up), `ExportRetryPolicy` (1→30 min, stop at created + 5 h) → `ExportScheduleAndRetryPolicyTest`, `HrExportServiceTest.whenHrStaysDownNoAttemptHappensAfter0600ThenTheBatchFailsAndOneAlertIsRaised`; HR down at the real mock → `reporting.test.mjs` (202, nothing stored). Alert = ERROR log `HR_EXPORT_ALERT` + FAILED batch on the dashboard; no mail/SMS |
| NFR-7 | Report query ≤ 30 s | Nam | report on a dataset | NOT_MEASURED | queries are bounded (≤ 31 days, ≤ 500 rows, index range on `(factory_id, occurred_at)`) but no latency was measured on a representative dataset |
| NFR-8 | RBAC, tamper-evident audit | Khoa | app permission + checksum integrity | DEV_SLICE | `AuditChecksumCalculatorTest` and `UserQrPostgresIntegrationTest`: per-field tampering, real row tampering, chain continuity under concurrent writers and transaction rollback passed 2026-11-14. No RBAC, restricted writer role or external anchor; not full immutability |
| ADR-01 | Offline + SQLite + direct sync | Khánh/Khôi | offline / restart / retry | DEV_SLICE | commit-before-open: `QueueSyntheticEventUseCaseTest.eventIsCommittedAsPendingBeforeTheGateCommandIsSent` · SYNCED only after ack: `SyncPendingEventsUseCaseTest` (6), `KioskAgentApplicationTest.scanWhileOfflineStaysPendingThenSyncsExactlyOnceAfterReconnect` · central idempotency: `AttendanceIdempotencyPostgresIntegrationTest` (6, real PostgreSQL, incl. 8-thread race), `slice.test.mjs` · offline switch (cuts only kiosk → central): `CentralSyncSwitch` · visible in `web-frontend` `/demo` (offline → PENDING, reconnect → SYNCED once, resend → `ALREADY_EXISTS`), checked in Chrome against the real stack 2026-11-14 |
| ADR-02 | TOTP, anti-replay | Khoa/Khánh | replay / revocation / skew | DEV_SLICE | User/QR TOTP implemented; `TotpCodeGeneratorTest`: 6 RFC SHA256 vectors, boundaries, current/previous only, wrong secret/malformed tokens. Kiosk whitelist domain, atomic SQLite persistence, AES-256-GCM, HTTP source/use case/scheduler are implemented/tested; §18 verifies real Central → Kiosk cache metadata with the versioned snapshot. Production `used_tokens` integration, kiosk scan/TOTP verification and end-to-end scan remain unimplemented |
| ADR-03 | Monthly HR export | Nam | retry + idempotency + failure notification | DEV_SLICE | `HrExportService` (claim / send / record with compare-and-set, lease for crashed attempts) over `IHrPayrollGateway` (`rest`, `file`; shared `HrPayrollGatewayContract`) → `HrExportServiceTest` (15), `ReportingPostgresIntegrationTest` (8), `reporting.test.mjs`. Only via unauthenticated `/api/dev/reports/hr-export/*`; late events are not re-exported (DECISIONS known limitations) |
| Layering | `presentation → application → domain ← infrastructure` | all | `mvn test` | READY | `*ArchitectureTest` in all 5 Java modules; User/QR and reporting have all four layers, User/QR checks the JDK crypto exception explicitly (B23); gate-station only has `presentation` so far |

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
| `presentation/controller/DevWhitelistSyncController` | H2 dev-only POST delegates to the existing singleton, no direct HTTP/SQLite/AES logic. Non-2xx for disabled/unavailable/offline/busy/failed sync; HTTP-mock → real SQLite and shared-guard test in STATUS §20. Default flag still false; no startup fetch |
| `presentation/dto/QrScanRequestDto` | done (TOTP redacted in `toString`) |
| `presentation/dto/ScanResultResponseDto` | **not created** |
| `application/usecase/ProcessQrScanUseCase` | **not created** — verify offline, persist the durable event **and** the used token atomically, then command the gate via `ITurnstileAdapter`. `QueueSyntheticEventUseCase` shows the commit-then-open ordering |
| `application/usecase/SyncManagerScheduler` | done (trigger for `SyncPendingEventsUseCase`) |
| `application/usecase/WhitelistSyncScheduler` | implemented opt-in SmartLifecycle fixed-delay trigger, first delay equals interval; private executor independent of Attendance scheduling, safe status/error logs, no direct transport/store access or new sync lock. [Latest scheduler evidence](STATUS.md#16-whitelist-scheduler-2026-11-14) |
| `domain/model/LocalAttendanceEvent` | done |
| `domain/model/WhitelistEmployee`, `WhitelistSnapshot`, `WhitelistMetadata`, `WhitelistLookup`, `QrStatus` | implemented; immutable local whitelist models, redacted/defensively copied material, initialized vs empty snapshot; `WhitelistModelsTest` |
| `domain/port/IWhitelistReader`, `IWhitelistSnapshotStore`; `domain/exception/WhitelistCacheException` | implemented; separate local read/update contracts, safe cache failure reasons |
| `domain/port/IWhitelistSnapshotSource`; `application/usecase/SyncWhitelistUseCase`, `WhitelistSyncReport` | implemented application core: scope/Clock/offline/non-overlap checks and durable store outcome. Existing store owns atomic version comparison. HTTP source/SQLite and periodic trigger are opt-in, false by default; [latest verification](STATUS.md#16-whitelist-scheduler-2026-11-14) |
| `infrastructure/sync/WhitelistEmployeeMapper`; `config/WhitelistSyncWiringConfig` | implemented UTF-8 mapping and flag-gated singleton composition. With the flag true, the root imports Infrastructure configuration and injects the existing source/store/switch/Clock/identity, with explicit-bean backoff; no registration-order-dependent source condition. [Wiring boundary](../kiosk-agent/README.md#employee-mapping-and-conditional-wiring) and real SQLite/HTTP-mock tests in STATUS §15 |
| `infrastructure/sync/WhitelistHttpTransport`, `WhitelistEmployeeWireDto`, `WhitelistHttpException`; `infrastructure/config/WhitelistHttpConfig` | implemented transport: configured factory, request-local credential, timeouts, complete array validation and safe errors/redaction. New immutable/redacted SnapshotResponse captures all revision-header values and employees from one response; legacy fetchEmployees remains header-optional. No generated revision or persistence; [transport boundary](../kiosk-agent/README.md#whitelist-http-transport-only) |
| `infrastructure/sync/RestWhitelistSnapshotSource` | implemented/tested source adapter; Infrastructure registers it only when whitelist sync flag is true. Explicit and scheduled HTTP mock → source → shared use case → SQLite/AES verified in STATUS §§15–16. Real Central revision and factory authorization remain unverified/incomplete |
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
| `presentation/controller/DevAttendanceEventsController`; `presentation/dto/AttendanceEventsResponseDto`; `application/service/AttendanceEventsService`; `domain/repository/IAttendanceEventsQuery` | H1 dev-only bounded Central list including fixtures, optional employee filter, newest-first with stable ID tie-break. Reuses `AttendanceEventDto` and existing PostgreSQL read adapter, not History/write capabilities. Web/profile/validation/frontend shape verified; 2 new real-PostgreSQL cases written but skipped without Docker (STATUS §20). No production authorization |
| `presentation/mapper/AttendanceSyncResponseMapper` | done (separate per-event ack mapping) |
| `presentation/dto/BatchSyncRequestDto` | done (+ `BatchSyncResponseDto`, `AttendanceEventDto`, `EventAckDto`, `ErrorResponseDto`) |
| `application/service/AttendanceIngestionService` | implemented/tested: one transaction per event, ACK after commit, duplicate/conflict classification and retryable storage/commit failure; [verification](STATUS.md#13-attendance-processor-postgresql-recheck-2026-11-14) |
| `domain/model/AttendanceRecord` | done |
| `domain/service/IdempotencyChecker` | done (classifies a repeated id; the PostgreSQL unique key is the concurrency-safe guard) |
| `domain/repository/IAttendanceRepository` | done (port in the domain) |
| `infrastructure/persistence/PostgresAttendanceRepository` | implemented/tested: parameterized insert/read, primary-key dedupe and concurrent retry; real PostgreSQL verification linked above |
| `domain/model/AttendanceHistoryQuery`, `AttendanceHistoryPage`; `domain/repository/IAttendanceHistoryQuery` | implemented internal read contract: employee + factory required, UTC millisecond [from, to), zero-based offset pages of 1..100 events, occurredAt/attendanceId ASC, fixtures excluded. Not an API/auth contract |
| `application/service/AttendanceHistoryService` | implemented with constructor-injected read port; `AttendanceHistoryModelsTest` (21) and `AttendanceHistoryServiceTest` (4) passed on 2026-11-14, JDK 23 / release 21 |
| `infrastructure/persistence/PostgresAttendanceHistoryQuery` | implemented/tested parameterized JdbcTemplate SELECT, bounded page + hasNext, no write-repository changes. Original SQL cases and both new HTTP/service/PostgreSQL cases passed; [STATUS §13](STATUS.md#13-attendance-processor-postgresql-recheck-2026-11-14). Earlier skips remain in §12; storage failure never becomes a fake empty result |
| `presentation/controller/DevAttendanceHistoryController`; `presentation/dto/AttendanceHistoryResponseDto` | implemented/tested dev-only GET, constructor-injected existing service, validated scope/range/pages, safe 400/503, no-store; WebMvc/profile and real-DB coverage in §13. No production History route, authorization or deployed-gateway acceptance claim |
| *added by the bootstrap* | `DevAttendanceController`, `ApiExceptionHandler`, `IngestionResult`, `domain/exception/*`, `DomainBeansConfig` |

### user-qr-service (Khoa)

All implemented classes below are dev-slice code; production authentication is not supplied.

- `presentation/controller/UserQrController`: employee-ID QR lifecycle and audit viewer; `KioskWhitelistController`: key-protected, required-factory array snapshot with revision header.
- `QrGenerateContractController`: M1 authenticated route remains 501; `UnimplementedQrController`: non-dev profile remains 501; `ApiExceptionHandler`: safe errors.
- `presentation/dto/`: `QrActionRequestDto`, `QrResponseDto` (nested redacted `QrPayload`), `QrStatusResponseDto`, `WhitelistEmployeeDto`, `AuditLogResponseDto`, existing `ErrorResponseDto`.
- `application/service/UserQrApplicationService`: lifecycle/membership transactions and repeatable-read export; `QrIssue`: redacted result.
- `domain/model/`: `EmployeeQr`, `WhitelistSnapshot`, `QrStatus`, `AuditEvent`, `AuditLog`; `domain/exception/`: employee/factory missing, revoked and storage errors.
- `domain/service/TotpCodeGenerator`, `AuditChecksumCalculator`: deterministic JDK cryptography.
- `domain/repository/IEmployeeQrRepository`, `IAuditLogRepository` (append only), `IAuditLogQuery` (viewer only).
- `infrastructure/persistence/PostgresEmployeeQrRepository`, `PostgresAuditLogRepository`; `infrastructure/config/DomainBeansConfig`: clocks/random/domain/repository wiring.
- Historical evidence: 41 module tests on Alpine/JDK 21, 2026-11-14; current K1–K4 tests, commands and limits: [STATUS §18](STATUS.md#18-userqr-k1k4-contract-alignment-2026-11-16).

### reporting-service (Nam)

Package root `vn.edu.hcmut.tmc.reporting`. All classes below exist and are tested (2026-11-15); none is a placeholder.

| Layer | Classes |
|---|---|
| presentation | `ReportingController` (production routes, **501**), `DevReportingController` (`dev` profile), `ApiExceptionHandler`, DTOs `AttendanceReportDto`, `DashboardDto`, `HrExportBatchDto`, `HrExportTriggerRequestDto`, `HrExportTriggerResponseDto`, `ErrorResponseDto` |
| application | `HrExportService` (use case), `HrExportScheduler` (trigger), `ReportQueryService`, `HrExportResult`, `HrExportSettings`, `Dashboard` |
| domain | ports `IHrPayrollGateway`, `IHrExportBatchStore`, `IHrExportStatusReader`, `IAttendanceReportReader`, `IExportAlertNotifier`; services `ExportSchedule`, `ExportRetryPolicy`; model `HrExportBatch`, `ExportPeriod`, `FactoryCalendar`, `PayrollBatch`, `PayrollRecord`, `PayrollSelection`, `DeliveryResult`, `DeliveryOutcome`, `BatchStatus`, `TriggerSource`, `DataScope`, `ExportAlert`, report queries and rows |
| infrastructure | `RestHrPayrollGateway`, `CsvFileHrPayrollGateway`, `PostgresHrExportBatchRepository`, `PostgresAttendanceReportReader`, `LoggingExportAlertNotifier` |
| composition root | `config/ReportingWiringConfig` |

### gate-station-service (Khôi)
The bootstrap provides only `*Application`, a `*Controller` of 501 stubs and `ErrorResponseDto`.

Rule: an empty class for every name in a design tree is **not** SOLID evidence. For an unimplemented feature prefer a clearly labelled stub (501) or a TODO in [TODO](TODO.md) over a class that pretends.
