# reporting-service — DEV_SLICE

Owner: **Nam** · package `vn.edu.hcmut.tmc.reporting` · port 8080. Status context: [docs/STATUS.md](../../docs/STATUS.md) · tasks: [docs/TODO.md](../../docs/TODO.md#5-nam--reporting-service-mock-hr-payroll-api-testing-report-assembly) · decisions: [DECISIONS B24–B26](../../docs/DECISIONS.md#bootstrap-decisions).

Factory reports (FR-4.1), the factory dashboard (FR-4.2) and the monthly HR/Payroll export (ADR-03, NFR-3, NFR-6) work for real against PostgreSQL and the mock HR, but **only through the unauthenticated dev routes**. The production routes keep answering `501` until JWT/RBAC exists.

| Route | Status |
|---|---|
| `GET /api/v1/reports/attendance` · `GET /api/v1/reports/dashboard` · `POST /api/v1/reports/hr-export/trigger` | **501** `NOT_IMPLEMENTED` (needs authentication, D04) |
| `GET /api/dev/reports/attendance?factoryId=BD&from=2026-10-01&to=2026-10-31[&department=&offset=&limit=]` | works, `dev` profile only |
| `GET /api/dev/reports/dashboard?factoryId=BD[&date=2026-11-15]` | works, `dev` profile only |
| `POST /api/dev/reports/hr-export/trigger` `{"period":"2026-10"}` | works, `dev` profile only |
| `GET /api/dev/reports/hr-export/batches/2026-10` | works, `dev` profile only |

Shapes and status codes: [API-CONTRACTS §7](../../docs/API-CONTRACTS.md#7-reporting-and-hr-export-team-plan-route--draft).

## How the export behaves (B24)

- A month is a calendar month in **Asia/Ho_Chi_Minh**. On day 1 at 01:00 local the previous month is due. A background poll (`HrExportScheduler`, every 30 s) creates the batch if it is missing, so a run missed while the service was down is caught up.
- `batch_uuid = UUID.nameUUIDFromBytes("tmc-hr-export:YYYY-MM")`: one batch per month, whoever triggers it. Its items (accepted scans, no dev fixtures) are frozen at creation; retries resend exactly those.
- HR down (5xx, 408, 429, timeout): retry after 1, 2, 4, 8, 16, then every 30 min; nothing is scheduled after `created_at + 5 h` (06:00 for the 01:00 run). Then the batch is `FAILED` and an `HR_EXPORT_ALERT` ERROR line is logged; the dashboard shows it.
- Manual trigger: a delivered month → 409, a FAILED month → same batch re-armed, a pending month → attempted at once, a month not over yet → 400.
- No DB transaction spans the HTTP call: an attempt claims the batch (`SENDING` + 2 min lease) with a compare-and-set, sends, then records the result with a second compare-and-set.

## Configuration

| Env | Default | Meaning |
|---|---|---|
| `HR_EXPORT_CHANNEL` | `rest` | `rest` = mock HR API at `HR_API_BASE_URL`; `file` = one CSV per batch in `HR_EXPORT_FILE_DIR` |
| `REPORTING_INCLUDE_DEV_FIXTURES` | `false` (Compose: `true`) | count rows written by the dev fixtures; dev stacks only |
| `HR_EXPORT_SCHEDULER_ENABLED` | `true` | background poll |
| `HR_EXPORT_RUN_AT` · `HR_EXPORT_POLL_INTERVAL_MS` | `01:00` · `30000` | schedule |
| `HR_EXPORT_BACKOFF_BASE_MS` · `_MAX_MS` · `HR_EXPORT_RETRY_WINDOW_MS` · `HR_EXPORT_ATTEMPT_LEASE_MS` | 1 min · 30 min · 5 h · 2 min | retry policy |

## Where each SOLID principle is, and the test behind it

Full table with rationale and limits: [SOLID-GUIDELINES §5](../../docs/SOLID-GUIDELINES.md#5-solid-evidence). Paths below are under `src/main/java/vn/edu/hcmut/tmc/reporting/`.

| Principle | Where to point | What it buys | Proof |
|---|---|---|---|
| **S** | `application/service/HrExportService.java` (what happens to a batch) · `domain/service/ExportSchedule.java` (when) · `domain/service/ExportRetryPolicy.java` (how long to wait) · `domain/model/HrExportBatch.java` (legal transitions) · `infrastructure/hr/RestHrPayrollGateway.java` (wire) · `infrastructure/persistence/PostgresHrExportBatchRepository.java` (SQL) | changing the backoff, the 01:00 rule, the HR JSON or the SQL touches one class | `ExportScheduleAndRetryPolicyTest`, `HrExportBatchTest`, `HrExportServiceTest` |
| **O** | `domain/port/IHrPayrollGateway.java` ← `RestHrPayrollGateway` and `CsvFileHrPayrollGateway` (each `@ConditionalOnProperty("tmc.hr-export.channel")`); stable: the `switch` in `HrExportService.attempt` | a new channel (SFTP, another HR API) = one new class, **no existing file edited** | both channels run in tests; `file` end to end in `ReportingPostgresIntegrationTest`, `rest` against the real mock in `tests/integration/reporting.test.mjs` |
| **L** | the contract in the Javadoc of `IHrPayrollGateway` and `src/test/.../infrastructure/hr/HrPayrollGatewayContract.java`, extended unchanged by both channel tests | `HrExportService` can take any channel: no false `ACKNOWLEDGED`, resend never duplicates, failures are `RETRYABLE` and never thrown | 5 shared clauses pass for both; returning `ACKNOWLEDGED` from the file channel fails 3 of them |
| **I** | `IHrExportBatchStore` (writer, used by `HrExportService`) vs `IHrExportStatusReader` (read-only, used by `ReportQueryService`), both implemented by `PostgresHrExportBatchRepository` | the dashboard code cannot create, claim or change a batch (the compiler refuses) | constructor of `ReportQueryService` |
| **D** | every port in `domain/port`, every adapter in `infrastructure`, wiring in `config/ReportingWiringConfig.java` | the use case is unit-tested with a lambda channel and an in-memory store; PostgreSQL/HTTP/files are swappable details | `ReportingArchitectureTest` (4 rules); a `JdbcTemplate` field in `HrExportService` fails it |

Deliberately **not** abstracted: `ExportSchedule`, `ExportRetryPolicy` and `ReportQueryService` are concrete classes (one rule each, no second implementation foreseen). `IExportAlertNotifier` has one implementation (a log line); its reason is that M1 names no alert channel, so it is a DIP boundary, not an OCP claim.

## Tests

```bash
mvn -B -ntp -pl backend-services/reporting-service test            # JDK 21; PostgreSQL tests need Docker (skipped, not faked, without it)
node --test tests/integration/reporting.test.mjs                    # against a running Compose stack
```

## Known limitations

Canonical list: [DECISIONS — Known limitations](../../docs/DECISIONS.md#known-limitations). In short: unauthenticated dev routes; late events are not in an already frozen batch; the alert is a log line; no shift dimension; `rejectedScans` stays 0 until rejected scans sync (D06); NFR-7 not measured.
