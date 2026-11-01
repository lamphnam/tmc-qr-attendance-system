-- Kiosk-local SQLite schema (BOOTSTRAP / DRAFT - docs/DATA-MODEL.md). Idempotent: safe to run on every start.
-- Journal mode WAL and synchronous=FULL are set per connection by SqliteConfig, not here.
--
-- Status of each table:
--   local_attendance_events  IMPLEMENTED by the dev slice (durable PENDING queue)
--   whitelist_cache          SCAFFOLD - owner Khanh/Khoa, secret-at-rest design open (D08)
--   used_tokens              SCAFFOLD - owner Khanh, anti-replay (ADR-02), must be written in the
--                            SAME transaction as the event row
--   kiosk_metadata           SCAFFOLD

CREATE TABLE IF NOT EXISTS local_attendance_events (
    attendance_id        TEXT    PRIMARY KEY,
    employee_id          TEXT    NOT NULL,
    factory_id           TEXT    NOT NULL,
    gate_id              TEXT    NOT NULL,
    direction            TEXT    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    occurred_at          TEXT    NOT NULL,
    -- 'REJECTED' is allowed so the universal-schema option for D06 stays open.
    scan_result          TEXT    NOT NULL CHECK (scan_result IN ('ALLOWED', 'REJECTED')),
    -- CENTRAL synchronisation state only. NOT whether the gate physically opened (docs/RUNTIME-FLOWS.md).
    sync_status          TEXT    NOT NULL DEFAULT 'PENDING' CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED')),
    gate_command_status  TEXT    NOT NULL DEFAULT 'NOT_ATTEMPTED'
                         CHECK (gate_command_status IN ('NOT_ATTEMPTED', 'SENT', 'FAILED', 'UNSUPPORTED')),
    gate_command_detail  TEXT,
    retry_count          INTEGER NOT NULL DEFAULT 0,
    next_retry_at        TEXT,
    last_error           TEXT,
    dev_fixture          INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT    NOT NULL,
    synced_at            TEXT
);

CREATE INDEX IF NOT EXISTS idx_local_events_sync ON local_attendance_events (sync_status, next_retry_at);

CREATE TABLE IF NOT EXISTS whitelist_cache (
    employee_id        TEXT    PRIMARY KEY,
    qr_status          TEXT    NOT NULL,
    secret_version     INTEGER NOT NULL,
    -- TODO(Khanh/Khoa, D08): protected at rest. Intentionally unused (NULL) in the bootstrap.
    shared_secret_enc  BLOB,
    cache_version      INTEGER NOT NULL,
    cached_at          TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS used_tokens (
    employee_id        TEXT    NOT NULL,
    matched_time_step  INTEGER NOT NULL,
    used_at            TEXT    NOT NULL,
    PRIMARY KEY (employee_id, matched_time_step)
);

CREATE TABLE IF NOT EXISTS kiosk_metadata (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);
