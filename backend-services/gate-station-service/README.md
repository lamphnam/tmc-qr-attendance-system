# gate-station-service — SCAFFOLD

Owner: **Khôi** · package `vn.edu.hcmut.tmc.gate` · port 8080. Status context: [docs/STATUS.md](../../docs/STATUS.md) · tasks: [docs/TODO.md](../../docs/TODO.md#3-khôi--attendance-processor-gate-station-service).

Boots with a real PostgreSQL health check. No gate feature exists yet: the three routes below all answer `501`.

| Route (contract: [API-CONTRACTS](../../docs/API-CONTRACTS.md) §6, DRAFT) | Status |
|---|---|
| `POST /api/v1/gates` (FR-2.1) | NOT_IMPLEMENTED |
| `GET /api/v1/gates/{gateId}/status` (FR-2.2) | NOT_IMPLEMENTED |
| `POST /api/v1/gates/{gateId}/manual-open` (FR-2.2) | NOT_IMPLEMENTED |
