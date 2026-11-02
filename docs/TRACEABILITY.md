# TRACEABILITY — requirements ↔ code ↔ tests, and the Java class map

The evidence column is filled only with things that exist and ran (2026-11-01). `TBD`/empty means nothing yet. Owners update their rows in the PR that changes the status; also update [STATUS](STATUS.md) and `web-frontend/src/catalog.ts`. Status vocabulary: [STATUS](STATUS.md#status-labels); `NOT_MEASURED` = a measurable NFR with no measurement.

## FR / NFR / ADR matrix

`DEV_SLICE` rows use synthetic events (no QR/TOTP) and **do not satisfy** the requirement; they show the plumbing.

| ID | Design element | Owner | Expected test | Status | Actual evidence (class → test) |
|---|---|---|---|---|---|
| FR-1.1 | User QR ↔ HR sync | Khoa/Nam | hr-sync integration | NOT_IMPLEMENTED | mock side only: `mock-hr-payroll-api` `GET /mock/hr/employees` (node:test) |
| FR-1.2 / 1.3 / 1.4 | Dynamic QR, revocation, web UI | Khoa/Khiêm | TOTP generation, revoked QR | NOT_IMPLEMENTED | `UserQrController` routes answer 501 (`UserQrServiceApplicationTest`) |
| FR-2.1 / 2.2 | Gate station, manual open, audit | Khôi/Khoa | guard role / manual gate | NOT_IMPLEMENTED | `GateStationController` routes answer 501 |
| FR-3.1 | Kiosk + SQLite + Processor | Khánh/Khôi | scan → ack → idempotency | DEV_SLICE | queue + sync + turnstile only; `POST /api/local/scan` answers 501 (`KioskAgentApplicationTest`). See ADR-01 rows |
| FR-3.2 | Reporting + UI | Nam/Khiêm | employee history authorization | NOT_IMPLEMENTED | — |
| FR-4.1 / 4.2 | Reporting service | Nam | filtered factory report & dashboard | NOT_IMPLEMENTED | `ReportingController` routes answer 501 |
| FR-5.1 | JWT / RBAC | Khoa | per-role endpoint authorization | NOT_IMPLEMENTED | tables `roles`, `users`, `user_roles` only |
| FR-5.2 | Configuration | Khoa/Khôi | reload / bounds | NOT_IMPLEMENTED | — |
| NFR-1 | Kiosk fast path | Khánh/Nam | 800/30 min + p95/p99 | NOT_MEASURED | no real scan path exists yet |
| NFR-2 | SQLite WAL + direct sync | Khánh/Khôi/Nam | 20 offline + restart + lost ack | DEV_SLICE | `slice.test.mjs` "central really DOWN and a kiosk RESTART" (1 event, process restart); `SQLiteAttendanceRepositoryTest.pendingEventSurvivesReopeningTheDatabaseFile`. **Not** the 20-event T02 scenario, **not** disk failure |
| NFR-3 | HR batch dedupe | Nam | same Batch_UUID / attendance ids | MOCK | receiver side only: mock HR `node:test` (11) + `slice.test.mjs`; `reporting-service` sends nothing yet |
| NFR-4 | Encryption, MFA, secret handling | Khoa | credential + transport controls | NOT_IMPLEMENTED | — |
| NFR-5 | Config-driven factory/gate | Khôi/Khánh | add a factory without code | partial | `KioskProperties` / `KioskIdentity` read kiosk id, factory, gate from env; `gates`, `kiosks` tables. No admin API/UI |
| NFR-6 | Monthly export + retry/alert | Nam | fake-clock schedule + failure | NOT_IMPLEMENTED | `hr_export_*` tables only |
| NFR-7 | Report query ≤ 30 s | Nam | report on a dataset | NOT_MEASURED | — |
| NFR-8 | RBAC, tamper-evident audit | Khoa | app permission + checksum integrity | NOT_IMPLEMENTED | `audit_logs` table only |
| ADR-01 | Offline + SQLite + direct sync | Khánh/Khôi | offline / restart / retry | DEV_SLICE | commit-before-open: `QueueSyntheticEventUseCaseTest.eventIsCommittedAsPendingBeforeTheGateCommandIsSent` · SYNCED only after ack: `SyncPendingEventsUseCaseTest` (6), `KioskAgentApplicationTest.scanWhileOfflineStaysPendingThenSyncsExactlyOnceAfterReconnect` · central idempotency: `AttendanceIdempotencyPostgresIntegrationTest` (6, real PostgreSQL, incl. 8-thread race), `slice.test.mjs` · offline switch (cuts only kiosk → central): `CentralSyncSwitch` |
| ADR-02 | TOTP, anti-replay | Khoa/Khánh | replay / revocation / skew | NOT_IMPLEMENTED | `used_tokens`, `whitelist_cache` tables exist, unused |
| ADR-03 | Monthly HR export | Nam | retry + idempotency + failure notification | NOT_IMPLEMENTED | mock receiver only |
| Layering | `presentation → application → domain ← infrastructure` | all | `mvn test` | READY | `*ArchitectureTest` in all 5 Java modules (skeleton services only have `presentation` so far) |

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
| `domain/model/WhitelistEmployee` | **not created** |
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
| *added by the bootstrap* | `DevAttendanceController`, `ApiExceptionHandler`, `IngestionResult`, `domain/exception/*`, `DomainBeansConfig` |

### user-qr-service (Khoa), gate-station-service (Khôi), reporting-service (Nam)
The owner supplies the names. The bootstrap provides only `*Application`, a `*Controller` of 501 stubs and `ErrorResponseDto`.

Rule: an empty class for every name in a design tree is **not** SOLID evidence. For an unimplemented feature prefer a clearly labelled stub (501) or a TODO in [TODO](TODO.md) over a class that pretends.
