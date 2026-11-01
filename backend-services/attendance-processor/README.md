# attendance-processor — DEV_SLICE

Owner: **Khôi** · package `vn.edu.hcmut.tmc.attendance` · port 8080 · gateway `/api/v1/attendance/**`. Status context: [docs/STATUS.md](../../docs/STATUS.md) · remaining work: [docs/TODO.md](../../docs/TODO.md#3-khôi--attendance-processor-gate-station-service) · class status: [docs/TRACEABILITY.md](../../docs/TRACEABILITY.md#attendance-processor-khôi).

Central, idempotent, ACID ingestion of attendance events (ADR-01). The **ingestion logic is real and tested**; the **production HTTP routes are not** (no kiosk-to-central authentication yet, D04; the wire contract is DRAFT, D01).

| Route | Status |
|---|---|
| `POST /api/v1/attendance/events`, `POST /api/v1/attendance/sync` | **NOT_IMPLEMENTED** — answer `501`. Wire `AttendanceIngestionService` here behind auth |
| `POST /api/dev/attendance/events` | **DEV-ONLY**, unauthenticated, `dev` profile only. One event, ack after commit |
| `POST /api/dev/attendance/sync` | **DEV-ONLY**. Batch with a per-event ack: `STORED` / `ALREADY_EXISTS` / `RETRYABLE` / `REJECTED_PERMANENT` |
| `GET /api/dev/attendance/events/{id}` | **DEV-ONLY** read-back |
| `GET /actuator/health` | ready only while PostgreSQL answers (`db` component) |

Rows written through the dev routes have `dev_fixture = true` so reports can exclude them.

## How idempotency works
1. The kiosk creates `attendanceId` **once**, at scan time.
2. `PostgresAttendanceRepository.insertIfAbsent` runs `INSERT … ON CONFLICT (attendance_id) DO NOTHING`; the primary key, not a prior `SELECT`, decides, so concurrent retries cannot create two rows (covered by an 8-thread test).
3. If the id exists, `IdempotencyChecker` compares payloads: same fact → `ALREADY_EXISTS` (ack, `duplicate: true`); different fact → `CONFLICT` (HTTP 409 / `REJECTED_PERMANENT`) and the stored row is never overwritten.
4. Every event is its own transaction. A result is returned only **after commit**, and one bad event in a batch cannot hide or roll back its siblings. A DB outage — including failing to open the transaction — becomes `RETRYABLE` / HTTP 503.

`occurredAt` is compared at millisecond precision (DECISIONS B06). `Idempotency-Key` is not used for dedupe in this slice; `attendanceId` is.

## Tests
`mvn test` runs unit tests (fake repository), the WebMvc contract, ArchUnit layering and `AttendanceIdempotencyPostgresIntegrationTest`, which starts **real PostgreSQL 16 through Testcontainers** and loads the real `init-db/schema.sql`. It is **skipped, not faked, without Docker** — check the surefire "Skipped" count.
