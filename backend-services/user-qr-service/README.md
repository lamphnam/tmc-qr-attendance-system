# user-qr-service — SCAFFOLD

Owner: **Khoa** · package `vn.edu.hcmut.tmc.userqr` · port 8080. Status context: [docs/STATUS.md](../../docs/STATUS.md) · tasks: [docs/TODO.md](../../docs/TODO.md#1-khoa--user-qr-service-authrbactotpaudit-gateway-integration).

Boots with a real PostgreSQL health check. No business feature exists; both routes answer `501`.

| Route (contract: [API-CONTRACTS](../../docs/API-CONTRACTS.md), DRAFT) | Status |
|---|---|
| `GET /api/v1/qr/generate` (§1, FR-1.3, ADR-02) | NOT_IMPLEMENTED |
| `GET /api/v1/kiosk/whitelist` (§2, FR-1.2, ADR-02) | NOT_IMPLEMENTED |

Config placeholder: `tmc.hr-api.base-url` (the mock HR in Compose).
