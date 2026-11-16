# DATA-MODEL — data models and persistence rules (provisional)

> [DRAFT] Table names/columns below are implementation proposals based on M1 text + team sheet, **not authoritative DB ERD**. Owner Khôi will finalize ERD; Khoa security schema; Nam reporting/export.
> The executable schema is `init-db/schema.sql` (single source of truth; bootstrap additions and decisions: DECISIONS B01, B11–B13). Do not duplicate DDL anywhere else.

## Central PostgreSQL — shared DB [BASELINE-M1]

Recommend explicit tables and unique keys, with separate ownership and migrations:

| Table | Important fields [DRAFT] | Purpose/owner |
|---|---|---|
| `employees` | `employee_id VARCHAR(36) PK`, `full_name(120)`, `department(64) NOT NULL`, `factory_id(32) FK`, `role(32)`, `shared_secret(128) NOT NULL`, `qr_status ACTIVE/REVOKED`, positive `secret_version`, `updated_at` | QR; Khoa; plaintext **synthetic dev secrets only** (B22), protection at rest remains D08 |
| `factories` | `factory_id VARCHAR(16) PK`, `name`, `whitelist_version BIGINT NOT NULL DEFAULT 1 CHECK (whitelist_version >= 1)` | shared reference; User/QR snapshot revision (B27) |
| `gates` | `gate_id PK`, `factory_id`, `direction`, `status`, `kiosk_id` | Khôi |
| `kiosks` | `kiosk_id PK`, `factory_id`, `last_heartbeat`, `status` | Khôi |
| `attendance_events` | `attendance_id UUID PK`, `employee_id`, `factory_id`, `gate_id`, `direction`, `occurred_at`, `scan_result`, `recorded_at` | Khôi; unique ID idempotency |
| `audit_logs` | `log_id VARCHAR(36) PK`, unique `audit_sequence BIGSERIAL`, `timestamp`, `actor_id`, `actor_role`, `action_type`, `target_resource`, nullable `payload_summary`, `status`, `previous_hash`, `checksum` | Khoa; append-only application writer and hash chain (B22) |
| `hr_export_batches` | `batch_uuid PK` (= `nameUUIDFromBytes("tmc-hr-export:"+period)`), `period` UNIQUE, `status` (`PENDING`, `SENDING`, `SENT`, `ACKNOWLEDGED`, `FAILED`), `trigger_source`, `created_at`, `retry_deadline`, `attempt_count`, `last_attempt_at`, `next_retry_at`, `last_error` | Nam; one batch per Vietnamese calendar month, durable retry, `attempt_count` is the optimistic-lock version ([DECISIONS B24](DECISIONS.md#bootstrap-decisions)) |
| `hr_export_batch_items` | `batch_uuid`, `attendance_id`, unique pair | Nam; frozen at batch creation (ALLOWED, non-fixture events of the period); retries resend exactly these |
| `users/roles/...` | subject, role/permission mappings | Khoa; six roles |

**Important:** PostgreSQL schema is shared by services; don't have two services own same migrations. Choose a single `init-db/schema.sql` for demo or migrations (Flyway) with coordinated ownership. Avoid Hibernate `ddl-auto=create-drop` deleting teammates' data; never use this in a persistent demo DB.

### Factory whitelist revision [DRAFT, B27]

`BD` and `LA` seed at revision 1. Each User/QR lifecycle or membership mutation increments the affected
factory revision in the same transaction as the employee write and audit append. A transfer locks and increments
the distinct old/new factories in lexical ID order; same-factory transfer changes nothing. Audit/storage failure
rolls back employee, revisions and audit. No trigger or duplicated service DDL is used; future HR writers must
use the same application/repository transaction path (direct SQL outside it is not automatically tracked).

The whitelist query reads the factory revision first and employees second under one read-only `REPEATABLE_READ`
transaction. Its HTTP header represents that complete scoped array, including `[]`; it is independent of
per-employee secret versions and is not a global cursor. Deleting the last employee keeps the factory and
increments its revision. Fresh-volume/reset behavior: [Known limitations](DECISIONS.md#known-limitations).

## Kiosk SQLite — source of queued events [BASELINE-M1]

| Table | Key columns [DRAFT] | Rule |
|---|---|---|
| `whitelist_cache` | `employee_id PK`, encrypted/local secret, qrStatus, secretVersion, cache_version | Sync snapshot and credentials isolation |
| `used_tokens` | `(employee_id, matched_time_step)` unique, `used_at` | Prevent same-code replay **at this kiosk** |
| `local_attendance_events` | `attendance_id UUID PK`, employee/gate/factory/direction/time, `scan_result`, `sync_status` (`PENDING`, `SYNCED`, `FAILED`), retry metadata | Durable queue; pending must survive app restart |
| `kiosk_metadata` | kioskId, factoryId, lastWhitelistSync, clock_health | optional and config-driven |

SQLite operational choices from team plan: WAL + `synchronous=FULL` (verify actual guarantees for target filesystem). Process must check transaction return before opening gate. Keep disk size, queue retention, corruption detection, backup policies explicit. `used_tokens` and event insert in **same transaction**.

### Open: rejected scans (D06)

M1: **all scanned attempts including REJECTED should be logged**, while the team snippet for `local_attendance_events` only queues valid scans. Choose a separate `scan_audit_events` table or a universal event schema with `scan_result`; never lose rejected scans; a rejection must never trigger a gate command; decide which rejected records sync to central before calling the feature complete.

## Idempotency design [BASELINE-M1 + DRAFT implementation]

(The decision itself is ADR-01 in [ARCHITECTURE](ARCHITECTURE.md#adr-01--offline-first-sync-baseline-m1); these are the persistence rules that implement it.)

1. Kiosk creates the attendance UUID **once at scan**, not per retry.
2. Central `attendance_id` has unique constraint; insertion atomic. In Postgres use `INSERT ... ON CONFLICT ... DO NOTHING` + fetch existing status/data, or appropriate transactional JPA/JDBC method.
3. Ack only once underlying commit succeeds. For partial batches, ack the IDs actually committed, not all requested IDs.
4. For same event ID with conflicting payload, report collision/conflict and do not overwrite silently.
5. HR exports use **stable Batch_UUID** for repeat of the *same logical export*. Mock receiver upserts/dedupes by Batch_UUID and Attendance_ID.

## Audit integrity [BASELINE-M1 NFR-8 + GUARDRAIL]

- Checksum SHA-256 supports tamper **detection**; hash alone does **not** prevent an attacker with full DB write privileges from rewriting both data and checksums.
- Combine append-only API, database privileges (no UPDATE/DELETE for app role), hash chaining and external root/checkpoint protection when possible; document demo limitation.
- Never log `shared_secret`, bearer token, QR TOTP, passwords or personally identifying content beyond necessary audit metadata.

Implemented dev chain (B22): within PostgreSQL's default READ_COMMITTED transaction, append takes
a transaction-scoped advisory lock `730202601`,
reads the last checksum by `audit_sequence`, calculates the next hash, then inserts. The lock is held
until the employee change and audit insert commit/roll back together. It also serializes the empty-chain case.
The genesis previous hash is 64 zeroes. Sequence gaps from rolled-back inserts are valid.

Canonical checksum encoding `TMC-AUDIT-V1`: concatenate, in this order, the marker, log ID,
UTC `Instant.toString()` timestamp truncated to milliseconds, actor ID, actor role, action type,
target resource, payload summary, status, previous hash. Each field is a signed 4-byte big-endian
UTF-8 **byte** length followed by its bytes; null is length -1 with no bytes. Hash the resulting bytes
with SHA-256 and encode as 64 lowercase hex characters. Length prefixes prevent delimiter ambiguity.
`audit_sequence` orders the chain and is not part of the checksum.

`IAuditLogRepository` only appends; `IAuditLogQuery` is a separate read capability. SQL revokes
UPDATE/DELETE/TRUNCATE from PUBLIC, but the current shared **owner/superuser** account can still
rewrite data and checksums or bypass the writer lock. This is tamper detection, not full NFR-8
immutability; restricted application roles and an external checkpoint remain required (B14).

## Seed data [DRAFT]

- 2 factories `BD`, `LA`; original kiosk/gate IDs and legacy `EMP001`–`EMP004` preserved.
  Added `EMP-001`–`EMP-005` (9 total synthetic employees); `EMP-005` and legacy `EMP004` are REVOKED.
  Six role catalog entries, no users/passwords. Public dummy QR secrets are explicitly dev-only.
- All fixtures must be synthetic and clearly demo-only. No persistent real credential in repo.
- Use independent deterministic test IDs and reset instructions so demo is reproducible.
