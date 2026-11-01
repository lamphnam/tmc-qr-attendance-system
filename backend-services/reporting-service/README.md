# reporting-service — SCAFFOLD

Owner: **Nam** · package `vn.edu.hcmut.tmc.reporting` · port 8080. Status context: [docs/STATUS.md](../../docs/STATUS.md) · tasks: [docs/TODO.md](../../docs/TODO.md#5-nam--reporting-service-mock-hr-payroll-api-testing-report-assembly).

Boots with a real PostgreSQL health check. No reporting feature exists yet: the three routes below all answer `501`.

| Route (contract: [API-CONTRACTS](../../docs/API-CONTRACTS.md) §7, DRAFT) | Status |
|---|---|
| `GET /api/v1/reports/attendance` (FR-4.1, NFR-7) | NOT_IMPLEMENTED |
| `GET /api/v1/reports/dashboard` (FR-4.2) | NOT_IMPLEMENTED |
| `POST /api/v1/reports/hr-export/trigger` (ADR-03, NFR-3/6) | NOT_IMPLEMENTED |

Config placeholder: `tmc.hr-api.base-url` (the mock HR in Compose). Read reports must exclude rows with `dev_fixture = true`.
