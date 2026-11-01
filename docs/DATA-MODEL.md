# DATA-MODEL — data models and persistence rules (provisional)

> [DRAFT] Table names/columns below are implementation proposals based on M1 text + team sheet, **not authoritative DB ERD**. Owner Khôi will finalize ERD; Khoa security schema; Nam reporting/export.
> The executable schema is `init-db/schema.sql` (single source of truth; bootstrap additions and decisions: DECISIONS B01, B11–B13). Do not duplicate DDL anywhere else.

## Central PostgreSQL — shared DB [BASELINE-M1]

Recommend explicit tables and unique keys, with separate ownership and migrations:

| Table | Important fields [DRAFT] | Purpose/owner |
|---|---|---|
| `employees` | `employee_id PK`, `full_name`, `department`, `factory_id`, `qr_status`, `secret_version`, protected `shared_secret`, `updated_at` | HR-sync and QR; Khoa |
| `factories` | `factory_id PK`, `name` | shared reference |
| `gates` | `gate_id PK`, `factory_id`, `direction`, `status`, `kiosk_id` | Khôi |
| `kiosks` | `kiosk_id PK`, `factory_id`, `last_heartbeat`, `status` | Khôi |
| `attendance_events` | `attendance_id UUID PK`, `employee_id`, `factory_id`, `gate_id`, `direction`, `occurred_at`, `scan_result`, `recorded_at` | Khôi; unique ID idempotency |
| `audit_logs` | `audit_id`, `actor_id`, `action`, `resource`, `occurred_at`, `previous_hash`, `checksum` | Khoa; append-only/tamper evidence |
| `hr_export_batches` | `batch_uuid PK`, `period`, `status`, `attempt_count`, `last_attempt_at`, `next_retry_at`, `last_error` | Nam; durable retry |
| `hr_export_batch_items` | `batch_uuid`, `attendance_id`, unique pair | Nam; dedupe/traceability |
| `users/roles/...` | subject, role/permission mappings | Khoa; six roles |

**Important:** PostgreSQL schema is shared by services; don't have two services own same migrations. Choose a single `init-db/schema.sql` for demo or migrations (Flyway) with coordinated ownership. Avoid Hibernate `ddl-auto=create-drop` deleting teammates' data; never use this in a persistent demo DB.

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

## Seed data [DRAFT]

- 2 factories `BD`, `LA`; representative kiosk/gate IDs, 2–4 fictional employees; six demo roles if auth implemented.
- All fixtures must be synthetic and clearly demo-only. No persistent real credential in repo.
- Use independent deterministic test IDs and reset instructions so demo is reproducible.
