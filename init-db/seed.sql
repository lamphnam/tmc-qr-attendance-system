-- =====================================================================
-- DEV-ONLY synthetic seed data. Fictional people, no credentials, no secrets.
-- Applied once on a clean volume, right after schema.sql (alphabetical order).
-- =====================================================================

INSERT INTO factories (factory_id, name) VALUES
    ('BD', 'Binh Duong (demo)'),
    ('LA', 'Long An (demo)')
ON CONFLICT DO NOTHING;

INSERT INTO kiosks (kiosk_id, factory_id) VALUES
    ('BD-K01', 'BD'),
    ('LA-K01', 'LA')
ON CONFLICT DO NOTHING;

INSERT INTO gates (gate_id, factory_id, kiosk_id, direction) VALUES
    ('BD-G01', 'BD', 'BD-K01', 'IN'),
    ('BD-G02', 'BD', 'BD-K01', 'OUT'),
    ('LA-G01', 'LA', 'LA-K01', 'BOTH')
ON CONFLICT DO NOTHING;

-- shared_secret_enc intentionally left NULL (see schema.sql, TODO Khoa / D08)
INSERT INTO employees (employee_id, full_name, department, factory_id, qr_status) VALUES
    ('EMP001', 'Demo Employee One',   'Assembly', 'BD', 'ACTIVE'),
    ('EMP002', 'Demo Employee Two',   'Packaging', 'BD', 'ACTIVE'),
    ('EMP003', 'Demo Employee Three', 'Assembly', 'LA', 'ACTIVE'),
    ('EMP004', 'Demo Revoked Person', 'Packaging', 'BD', 'REVOKED')
ON CONFLICT DO NOTHING;

-- Six M1 roles (catalog only; no users / passwords are seeded).
INSERT INTO roles (role_name) VALUES
    ('EMPLOYEE'),
    ('PRODUCTION_SUPERVISOR'),
    ('HR_STAFF'),
    ('SECURITY_GUARD'),
    ('SYSTEM_ADMIN'),
    ('EXECUTIVE_MANAGEMENT')
ON CONFLICT DO NOTHING;
