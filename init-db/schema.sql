-- =====================================================================
-- TMC central PostgreSQL schema  (BOOTSTRAP / DRAFT - docs/DATA-MODEL.md)
--
-- Migration strategy (decision D02, see docs/DECISIONS.md):
--   * ONE file, applied by the official postgres image on a CLEAN volume only
--     (/docker-entrypoint-initdb.d). No service creates or alters tables, and
--     Hibernate ddl-auto is never used.
--   * To reset a dev database:  docker compose down -v   (destroys the volume!)
--   * Change flow until the team adopts Flyway/Liquibase: edit this file in a PR
--     reviewed by the table's owner, then every dev resets their local volume.
--
-- Table ownership (who finalises columns): see "owner" comments below.
-- Everything here is a PROPOSAL derived from M1 + docs/DATA-MODEL.md, not an approved ERD.
-- Keep this file free of $$ blocks / functions: tests load it with a plain
-- ';'-splitting script runner.
-- =====================================================================

-- ---- shared reference ------------------------------------------------
CREATE TABLE IF NOT EXISTS factories (
    factory_id  VARCHAR(16)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL
);

-- ---- user-qr-service (owner: Khoa) -----------------------------------
CREATE TABLE IF NOT EXISTS employees (
    employee_id         VARCHAR(32)  PRIMARY KEY,
    full_name           VARCHAR(150) NOT NULL,
    department          VARCHAR(100),
    factory_id          VARCHAR(16)  NOT NULL REFERENCES factories (factory_id),
    qr_status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (qr_status IN ('ACTIVE', 'REVOKED', 'DISABLED')),
    secret_version      INTEGER      NOT NULL DEFAULT 1,
    -- TODO(Khoa, D08): protected per-employee TOTP secret. Deliberately NULL in
    -- the bootstrap: no secret material is generated or committed here.
    shared_secret_enc   TEXT,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS roles (
    role_name VARCHAR(32) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS users (
    user_id       BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    employee_id   VARCHAR(32)  REFERENCES employees (employee_id),
    password_hash TEXT         NOT NULL,
    mfa_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id   BIGINT      NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    role_name VARCHAR(32) NOT NULL REFERENCES roles (role_name),
    PRIMARY KEY (user_id, role_name)
);

-- Tamper-evident audit log (NFR-8). Only the shape exists; the hash chain,
-- append-only DB privileges and integrity tests are Khoa's work.
CREATE TABLE IF NOT EXISTS audit_logs (
    audit_id      BIGSERIAL    PRIMARY KEY,
    actor_id      VARCHAR(64)  NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    resource      VARCHAR(128),
    detail        TEXT,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    previous_hash CHAR(64),
    checksum      CHAR(64)
);

-- ---- gate-station-service (owner: Khoi) ------------------------------
CREATE TABLE IF NOT EXISTS kiosks (
    kiosk_id        VARCHAR(32) PRIMARY KEY,
    factory_id      VARCHAR(16) NOT NULL REFERENCES factories (factory_id),
    status          VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
                    CHECK (status IN ('UNKNOWN', 'ONLINE', 'OFFLINE', 'DISABLED')),
    last_heartbeat  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS gates (
    gate_id     VARCHAR(32) PRIMARY KEY,
    factory_id  VARCHAR(16) NOT NULL REFERENCES factories (factory_id),
    kiosk_id    VARCHAR(32) REFERENCES kiosks (kiosk_id),
    direction   VARCHAR(8)  NOT NULL CHECK (direction IN ('IN', 'OUT', 'BOTH')),
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'DISABLED', 'MAINTENANCE'))
);

-- ---- attendance-processor (owner: Khoi) ------------------------------
-- attendance_id PRIMARY KEY is the ultimate, concurrency-safe idempotency
-- guard (ADR-01). No FK to employees/gates on purpose: rows are immutable
-- facts produced at the edge, possibly while the whitelist was stale.
-- Whether to validate references at ingestion is Khoi's call (open: D01).
CREATE TABLE IF NOT EXISTS attendance_events (
    attendance_id    UUID         PRIMARY KEY,
    employee_id      VARCHAR(32)  NOT NULL,
    factory_id       VARCHAR(16)  NOT NULL,
    gate_id          VARCHAR(32)  NOT NULL,
    direction        VARCHAR(8)   NOT NULL CHECK (direction IN ('IN', 'OUT')),
    occurred_at      TIMESTAMPTZ  NOT NULL,
    scan_result      VARCHAR(16)  NOT NULL CHECK (scan_result IN ('ALLOWED', 'REJECTED')),
    source_kiosk_id  VARCHAR(32),
    -- bootstrap addition: TRUE for rows created through the dev-only
    -- /api/dev/attendance/* fixture endpoints, so reports can exclude them.
    dev_fixture      BOOLEAN      NOT NULL DEFAULT FALSE,
    recorded_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_attendance_factory_time  ON attendance_events (factory_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_attendance_employee_time ON attendance_events (employee_id, occurred_at);

-- ---- reporting-service (owner: Nam) ----------------------------------
CREATE TABLE IF NOT EXISTS hr_export_batches (
    batch_uuid       UUID         PRIMARY KEY,
    period           CHAR(7)      NOT NULL,            -- 'YYYY-MM' (open: D10)
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'SENT', 'ACKNOWLEDGED', 'FAILED')),
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    last_attempt_at  TIMESTAMPTZ,
    next_retry_at    TIMESTAMPTZ,
    last_error       TEXT
);

CREATE TABLE IF NOT EXISTS hr_export_batch_items (
    batch_uuid     UUID NOT NULL REFERENCES hr_export_batches (batch_uuid) ON DELETE CASCADE,
    attendance_id  UUID NOT NULL REFERENCES attendance_events (attendance_id),
    PRIMARY KEY (batch_uuid, attendance_id)
);
