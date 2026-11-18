-- =====================================================================
-- LOAD-TEST FIXTURE (dev only). Synthetic employees for tests/load/k6/peak-scan-load.js.
--
-- Why: T01 (docs/TESTING.md) sends 800 valid scans per factory, and the kiosk accepts each
-- (employee, 30 s step) only once. The seed has 4 ACTIVE employees in BD, which caps a 30-minute
-- run at about 240 valid scans. This file adds LOAD-BD-0001 .. LOAD-BD-1000 so every scan in a
-- run uses a different employee.
--
-- Not part of init-db/seed.sql on purpose: run it only on a load-test stack, ideally a separate
-- Compose project (see tests/load/README.md). The authorized scans sync via dev ingress;
-- reports and exports can include those fixture rows when the Compose flag is enabled.
--
-- Follows the B27 rule for writers outside the application: for every inserted employee, in one
-- transaction, bump factories.whitelist_version and append an audit row to the SHA-256 chain
-- (encoding TMC-AUDIT-V1, docs/DATA-MODEL.md "Audit integrity"), under the same advisory lock
-- as PostgresAuditLogRepository. Without the revision bump the kiosk keeps its old whitelist.
--
-- Secrets are PUBLIC DUMMY values derived from the ID (TMC_LOAD_BD_0001_V1 ...); the k6 script
-- derives them the same way. Never load this into a database with real employees.
--
-- Idempotent: existing IDs are skipped, and nothing is bumped or audited for them.
-- Run (default Compose names; add -p <project> for a separate stack):
--   docker compose exec -T central-db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < tests/load/fixtures/load-employees.sql
-- =====================================================================

BEGIN;

DO $$
DECLARE
    c_factory CONSTANT text := 'BD';
    c_count   CONSTANT int  := 1000;
    c_actor   CONSTANT text := 'LOAD_FIXTURE_SQL';
    c_role    CONSTANT text := 'UNAUTHENTICATED_DEV';
    c_reason  CONSTANT text := 'k6 load-test fixture';
    v_num      text;
    v_id       text;
    v_ts       timestamptz;
    v_ms       int;
    v_ts_text  text;
    v_log_id   text;
    v_summary  text;
    v_prev     text;
    v_buf      bytea;
    v_field    text;
    v_bytes    bytea;
    v_inserted int := 0;
BEGIN
    -- Lock order matches User/QR (factory row first, then the audit chain lock), so a concurrent
    -- application write waits instead of deadlocking.
    PERFORM 1 FROM factories WHERE factory_id = c_factory FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'factory % does not exist', c_factory;
    END IF;
    -- Same transaction-scoped lock as the application's audit writer (DATA-MODEL, B22).
    PERFORM pg_advisory_xact_lock(730202601);

    FOR n IN 1..c_count LOOP
        v_num := lpad(n::text, 4, '0');
        v_id  := 'LOAD-' || c_factory || '-' || v_num;
        v_ts  := date_trunc('milliseconds', clock_timestamp());

        INSERT INTO employees (employee_id, full_name, department, factory_id, role,
                               shared_secret, qr_status, secret_version, updated_at)
        VALUES (v_id, 'Load Test ' || c_factory || ' ' || v_num, 'LoadTest', c_factory, 'EMPLOYEE',
                'TMC_LOAD_' || c_factory || '_' || v_num || '_V1', 'ACTIVE', 1, v_ts)
        ON CONFLICT (employee_id) DO NOTHING;
        CONTINUE WHEN NOT FOUND;

        UPDATE factories SET whitelist_version = whitelist_version + 1 WHERE factory_id = c_factory;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'factory % does not exist', c_factory;
        END IF;

        -- Java Instant.toString() of a millisecond instant: no fraction when ms = 0, else 3 digits.
        v_ms := (floor(extract(milliseconds FROM v_ts))::int) % 1000;
        v_ts_text := to_char(v_ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS')
                     || CASE WHEN v_ms = 0 THEN '' ELSE '.' || lpad(v_ms::text, 3, '0') END
                     || 'Z';
        v_log_id  := gen_random_uuid()::text;
        -- Same summary format as UserQrApplicationService.addEmployee + recordChange.
        v_summary := 'factoryId=' || c_factory || '; reason=' || c_reason;

        SELECT checksum INTO v_prev FROM audit_logs ORDER BY audit_sequence DESC LIMIT 1;
        v_prev := coalesce(v_prev, repeat('0', 64));

        -- TMC-AUDIT-V1: each field = signed 4-byte big-endian UTF-8 byte length + bytes.
        v_buf := ''::bytea;
        FOREACH v_field IN ARRAY ARRAY['TMC-AUDIT-V1', v_log_id, v_ts_text, c_actor, c_role,
                                       'ADD_EMPLOYEE', v_id, v_summary, 'SUCCESS', v_prev] LOOP
            v_bytes := convert_to(v_field, 'UTF8');
            v_buf := v_buf || int4send(octet_length(v_bytes)) || v_bytes;
        END LOOP;

        INSERT INTO audit_logs (log_id, timestamp, actor_id, actor_role, action_type,
                                target_resource, payload_summary, status, previous_hash, checksum)
        VALUES (v_log_id, v_ts, c_actor, c_role, 'ADD_EMPLOYEE', v_id, v_summary, 'SUCCESS',
                v_prev, encode(sha256(v_buf), 'hex'));

        v_inserted := v_inserted + 1;
    END LOOP;

    RAISE NOTICE 'load fixture: % employee(s) inserted for factory %', v_inserted, c_factory;
END
$$;

COMMIT;

SELECT f.factory_id,
       f.whitelist_version,
       count(e.employee_id) FILTER (WHERE e.employee_id LIKE 'LOAD-%')                        AS load_employees,
       count(e.employee_id) FILTER (WHERE e.qr_status = 'ACTIVE')                               AS active_employees
FROM factories f
LEFT JOIN employees e ON e.factory_id = f.factory_id
GROUP BY f.factory_id, f.whitelist_version
ORDER BY f.factory_id;
