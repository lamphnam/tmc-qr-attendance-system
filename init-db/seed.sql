-- =====================================================================
-- DEV-ONLY synthetic seed data. Fictional people and PUBLIC DUMMY QR secrets.
-- Never import these keys into a deployment with real employees.
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

-- Preserve the original IDs used by the existing attendance/mock fixtures.
INSERT INTO employees (employee_id, full_name, department, factory_id, shared_secret, qr_status) VALUES
    ('EMP001', 'Demo Employee One',   'Assembly', 'BD', 'TMC_DEMO_LEGACY_EMP001_V1', 'ACTIVE'),
    ('EMP002', 'Demo Employee Two',   'Packaging', 'BD', 'TMC_DEMO_LEGACY_EMP002_V1', 'ACTIVE'),
    ('EMP003', 'Demo Employee Three', 'Assembly', 'LA', 'TMC_DEMO_LEGACY_EMP003_V1', 'ACTIVE'),
    ('EMP004', 'Demo Revoked Person', 'Packaging', 'BD', 'TMC_DEMO_LEGACY_EMP004_V1', 'REVOKED'),
    ('EMP-001', 'Demo Nguyen Van An', 'Assembly', 'BD', 'TMC_SECRET_EMP001_V1', 'ACTIVE'),
    ('EMP-002', 'Demo Tran Thi Binh', 'Packaging', 'BD', 'TMC_SECRET_EMP002_V1', 'ACTIVE'),
    ('EMP-003', 'Demo Le Van Cuong', 'Assembly', 'LA', 'TMC_SECRET_EMP003_V1', 'ACTIVE'),
    ('EMP-004', 'Demo Pham Thi Dung', 'Quality', 'LA', 'TMC_SECRET_EMP004_V1', 'ACTIVE'),
    ('EMP-005', 'Demo Vo Van Em', 'Packaging', 'BD', 'TMC_SECRET_EMP005_V1', 'REVOKED')
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
