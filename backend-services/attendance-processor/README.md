# attendance-processor — DEV_SLICE

Owner: **Khôi** · package `vn.edu.hcmut.tmc.attendance` · port 8080 · gateway `/api/v1/attendance/**`. Status context: [docs/STATUS.md](../../docs/STATUS.md) · remaining work: [docs/TODO.md](../../docs/TODO.md#3-khôi--attendance-processor-gate-station-service) · class status: [docs/TRACEABILITY.md](../../docs/TRACEABILITY.md#attendance-processor-khôi).

Central, idempotent, ACID ingestion of attendance events (ADR-01). The **ingestion logic is real and tested**; the **production HTTP routes are not** (no kiosk-to-central authentication yet, D04; the wire contract is DRAFT, D01).

| Route | Status |
|---|---|
| `POST /api/v1/attendance/events`, `POST /api/v1/attendance/sync` | **NOT_IMPLEMENTED** — answer `501`. Wire `AttendanceIngestionService` here behind auth |
| `POST /api/dev/attendance/events` | **DEV-ONLY**, unauthenticated, `dev` profile only. One event, ack after commit |
| `POST /api/dev/attendance/sync` | **DEV-ONLY**. Batch with a per-event ack: `STORED` / `ALREADY_EXISTS` / `RETRYABLE` / `REJECTED_PERMANENT` |
| `GET /api/dev/attendance/events/{id}` | **DEV-ONLY** read-back |
| `GET /api/dev/attendance/history` | **DEV-ONLY**, unauthenticated history query; see below |
| `GET /actuator/health` | ready only while PostgreSQL answers (`db` component) |

Rows written through the dev routes have `dev_fixture = true` so reports can exclude them.

## Dev attendance history

Only profile `dev` registers `GET /api/dev/attendance/history`. Example with synthetic data:

```text
/api/dev/attendance/history?employeeId=EMP001&factoryId=BD&fromInclusive=2026-11-14T01:00:00Z&toExclusive=2026-11-14T02:00:00Z&page=0&size=20
```

The mandatory employee/factory filters and half-open time range are passed to the existing query service.
Times require an ISO-8601 instant with a timezone and millisecond precision or coarser.
Pagination is zero-based, defaults to page 0 / size 20, and accepts sizes 1..100.
The response contains `events` (the existing attendance event DTO), `page`, `size`, and `hasNext`.
Events sort by `occurredAt ASC, attendanceId ASC`; no total count or consistent snapshot across separate pages is promised.
Both ALLOWED and REJECTED facts are included; `dev_fixture=true` rows are excluded even on this dev route.
Consequently, events created through the existing dev ingestion endpoints do not populate this history.
Use synthetic non-fixture rows in an isolated dev database to exercise it; no fixture-inclusion bypass is provided.

Invalid/missing parameters return 400 `VALIDATION_ERROR`; storage failures return 503 `STORAGE_UNAVAILABLE`,
never an empty successful page. Responses carry `X-TMC-Dev-Only: true` and `Cache-Control: no-store`.
The wire details are recorded as a [dev-only DRAFT](../../docs/API-CONTRACTS.md#dev-only-attendance-history-draft).

**Security limit:** there is no authentication or factory authorization. Any caller reaching this dev endpoint
can select any factory; `factoryId` is only a SQL filter. Enable it only on a trusted developer machine
with synthetic records. Do not enable the dev profile against real employee data.
No production History GET route is registered. Production identity, roles and authorized factory scope require team agreement.

## How idempotency works
1. The kiosk creates `attendanceId` **once**, at scan time.
2. `PostgresAttendanceRepository.insertIfAbsent` runs `INSERT … ON CONFLICT (attendance_id) DO NOTHING`; the primary key, not a prior `SELECT`, decides, so concurrent retries cannot create two rows (covered by an 8-thread test).
3. If the id exists, `IdempotencyChecker` compares payloads: same fact → `ALREADY_EXISTS` (ack, `duplicate: true`); different fact → `CONFLICT` (HTTP 409 / `REJECTED_PERMANENT`) and the stored row is never overwritten.
4. Every event is its own transaction. An ACK is returned only **after commit**; expected per-event validation/storage failures do not roll back siblings. A DB outage — including failing to open the transaction — becomes `RETRYABLE` in a batch / HTTP 503 for a single event. Invalid JSON or an invalid batch envelope can reject the whole request before event processing.

`occurredAt` is compared at millisecond precision (DECISIONS B06). `Idempotency-Key` is not used for dedupe in this slice; `attendanceId` is.

## Dev handoff acceptance

Run these PowerShell examples against an already running dev-profile stack with synthetic data only.
`$attendanceBase` is the gateway URL (or the directly exposed processor URL); these commands do not start/reset the stack.

```powershell
$attendanceBase = 'http://localhost:8080'
$demoEvent = @{
    attendanceId = [guid]::NewGuid().ToString()
    employeeId = 'EMP001'; factoryId = 'BD'; gateId = 'BD-G01'
    direction = 'IN'; occurredAt = '2026-11-14T01:20:00Z'; scanResult = 'ALLOWED'
}
$demoJson = $demoEvent | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$attendanceBase/api/dev/attendance/events" -ContentType 'application/json' -Body $demoJson
# Retry the identical fact and ID: ALREADY_EXISTS, duplicate=true; no second row.
Invoke-RestMethod -Method Post -Uri "$attendanceBase/api/dev/attendance/events" -ContentType 'application/json' -Body $demoJson

$demoBatch = @{ kioskId = 'BD-K01'; events = @($demoEvent, @{ attendanceId = 'invalid-uuid' }) } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri "$attendanceBase/api/dev/attendance/sync" -ContentType 'application/json' -Body $demoBatch

$historyUri = "$attendanceBase/api/dev/attendance/history?employeeId=EMP001&factoryId=BD&fromInclusive=2026-11-14T01:00:00Z&toExclusive=2026-11-14T02:00:00Z&page=0&size=20"
Invoke-RestMethod -Uri $historyUri
```

The first POST should return `STORED`, `duplicate=false`. The example batch then returns `ALREADY_EXISTS`
for that ID and `REJECTED_PERMANENT` for `invalid-uuid`. Inspect **each `results` item by attendanceId**:

| Per-event status | Kiosk action |
|---|---|
| `STORED` / `ALREADY_EXISTS` | Mark only that acknowledged ID SYNCED |
| `RETRYABLE` | Keep PENDING and retry the same ID/payload with backoff |
| `REJECTED_PERMANENT` | Mark that ID FAILED for inspection; do not silently create a replacement ID |

HTTP 200 for a batch does not acknowledge every event. An absent/unreadable per-ID result is not an ACK;
`retryableIds` / `rejectedIds` are supplementary lists. Kiosk treats a whole-request failure as retryable,
including misrouted/auth-error requests; changing its sync URL to the current production stub will not drain the queue.
Changing a business field while reusing the ID returns 409 `ATTENDANCE_ID_CONFLICT` for a single POST,
or `REJECTED_PERMANENT` in a batch, preserving the first stored fact.

**History expectation:** every row created above is `dev_fixture=true` and is deliberately absent from History.
If the database contains only these fixture rows, the GET returns `events: []` and `hasNext: false`.
For positive History acceptance, use the existing PostgreSQL history tests, which prepare synthetic non-fixture
rows in an isolated Testcontainers database. Do not relabel shared dev fixtures just to make the demo appear successful.

Nam still needs to verify the deployed Kiosk/gateway/queue flow and agree accepted/rejected facts, fixture policy
and reporting timezone with Khôi. Production POST routes remain 501 in every profile; production History GET
is absent. Khoa owns auth integration and Khánh reviews event/ACK compatibility; factory input grants no access rights.

## Tests
`mvn test` runs unit tests (fake repository), WebMvc validation/profile tests, ArchUnit layering and the history/idempotency PostgreSQL integration suites. Testcontainers starts **real PostgreSQL 16** and loads the canonical `init-db/schema.sql`; the history suite also exercises HTTP → service → PostgreSQL. These suites are **skipped, not faked, without Docker** — check the surefire "Skipped" count.

From the repository root: `mvn -B -ntp -pl backend-services/attendance-processor test`.
Latest measured module result, PostgreSQL details and earlier skipped runs: [STATUS §13](../../docs/STATUS.md#13-attendance-processor-postgresql-recheck-2026-11-14).
This handoff adds documentation only; the examples above were not rerun through a deployed gateway in this documentation pass.
