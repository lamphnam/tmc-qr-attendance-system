# API-CONTRACTS — M1-backed routes + [DRAFT] wire schemas

> **Critical:** route names `/api/v1/attendance/events`, `/api/v1/attendance/sync`, `/api/v1/kiosk/whitelist`, `/api/v1/qr/generate`, `/api/local/scan`, `/api/v1/reports/hr-export/trigger` appear in M1/plan. Field names below beyond `empId/totp/ts/ver`, `attendanceId`, `Idempotency-Key`, `Batch_UUID` are **[DRAFT] until owner review**. Do not treat payloads as professor-approved schemas.
>
> **Implementation status** of each route (which answers 501, which has a dev-only fixture) is in [STATUS](STATUS.md). The choices the bootstrap made for sections 4–5 are DECISIONS B04–B07. Sign-off and review rules: [OWNERSHIP](OWNERSHIP.md#interface-gates--agree-before-merging-business-features-team-plan--draft).

## Conventions [DRAFT]

- JSON UTF-8, timestamps ISO-8601 UTC for event times, UUID as string for stable IDs.
- Central endpoints via `nginx-gateway`; kiosk local endpoint accessible to simulator within trusted dev environment.
- HR employee JWT for `/qr/generate` and reporting routes; Kiosk-to-Central auth via service credential/mTLS in production, **never publicly unauthenticated in actual deployment**.
- Error shape: `{ "code": "...", "message": "...", "correlationId": "..." }`; no stack traces/secrets in API logs.
- Avoid reusing `Idempotency-Key` for a different payload. Central dedupe primary by per-event unique `attendanceId`; batch key tracks duplicate requests where desired.
- CORS allowlist; dev-only mock endpoints/credentials cannot be production defaults.

## 1. GET /api/v1/qr/generate [TEAM-PLAN]

Request: employee Bearer JWT. Browser never receives `sharedSecret`. Response [DRAFT]:

```json
{
  "qrPayload": {"empId":"EMP001", "totp":"482910", "ts":1761152000, "ver":1},
  "expiresAt":"2026-11-01T12:00:30Z",
  "periodSeconds":30
}
```

- Backend TOTP HMAC-SHA256 per-employee secret and RFC 6238. Reject revoked users/QR.
- `{ts}` is contextual; kiosk must recompute trusted time step, not blindly trust timestamp from payload.
- Refresh on client at/after step boundary. Version change blocks old secret at kiosk **after whitelist refresh**; cannot claim instant offline revocation.

## 2. GET /api/v1/kiosk/whitelist [TEAM-PLAN]

- Kiosk-authenticated call; scope by `factoryId`/`kioskId` [DRAFT]. Return active/disabled employees' verification data and monotonically increasing version.
- Sensitive shared secrets can be delivered only over trusted authenticated channel and protected at rest; never appear in web/frontend response or logs.
- Example **structure only** (do not commit real secrets):

```json
{"version":3, "factoryId":"BD", "employees":[
  {"employeeId":"EMP001", "qrStatus":"ACTIVE", "secretVersion":1, "sharedSecret":"<SENSITIVE-REDACTED>"}
]}
```

## 3. POST /api/local/scan [TEAM-PLAN]

Kiosk simulator → kiosk-agent, [DRAFT] input:

```json
{"gateId":"BD-G01", "direction":"IN", "qrPayload":{"empId":"EMP001", "totp":"482910", "ts":1761152000, "ver":1}}
```

Response [DRAFT] for accepted:

```json
{"result":"ALLOWED", "attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1", "gateCommand":"SENT", "processingTimeMs":25}
```

Rejected attempts should be persistently auditable, with no OPEN command. A locally recorded `PENDING` attendance event does **not** mean central commit. For turnstile errors: preserve durable record and expose explicit `GATE_ERROR`/result; do not report gate physically opened if only a mock accepted command.

## 4. POST /api/v1/attendance/events [BASELINE-M1 route]

Kiosk sends one stable event. Request [DRAFT]:

```json
{
  "attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1",
  "employeeId":"EMP001", "factoryId":"BD", "gateId":"BD-G01",
  "direction":"IN", "occurredAt":"2026-11-01T01:20:00Z", "scanResult":"ALLOWED"
}
```

Header: `Idempotency-Key: <stable-key>`. Response only after database commit [DRAFT]:

```json
{"attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1", "status":"STORED", "duplicate":false}
```

If same ID retried after response loss, return successful ack + `duplicate:true` (if payload matches). Same ID with contradictory content → conflict policy to be agreed; do not silently mutate already stored events.

## 5. POST /api/v1/attendance/sync [BASELINE-M1 route]

Header: `Idempotency-Key: <stable-batch-request-key>`. Request [DRAFT]:

```json
{"kioskId":"BD-K01", "events":[
  {"attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1", "employeeId":"EMP001", "factoryId":"BD", "gateId":"BD-G01", "direction":"IN", "occurredAt":"2026-11-01T01:20:00Z", "scanResult":"ALLOWED"}
]}
```

Response must acknowledge **per event** due to mixed success/failure [BASELINE-M1 concept; [DRAFT] format]:

```json
{
 "results":[
   {"attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1", "status":"STORED"}
 ],
 "retryableIds":[], "rejectedIds":[]
}
```

`STORED` or `ALREADY_EXISTS` → mark SYNCED; `RETRYABLE` → retain PENDING and retry; `REJECTED_PERMANENT` → FAILED and notify. Repeated calls must not create duplicate rows. Rejected scan event schema vs attendance event must be clarified in owner sync.

## 6. Gate station endpoints [DRAFT; owner Khôi]

- `POST /api/v1/gates` — register/config gate.
- `GET /api/v1/gates/{gateId}/status` — latest heartbeat/status.
- `POST /api/v1/gates/{gateId}/manual-open` — Security Guard auth + mandatory `reason`, employeeId optional; durable audit.
- Kiosk-to-turnstile adapter open command is a separate **local network** interaction, not normal WAN roundtrip.
- Real manual-open remote delivery is not defined in M1; define failure/authorization states before implementing.

## 7. Reporting and HR export [TEAM-PLAN route + DRAFT]

- `GET /api/v1/reports/attendance?factoryId=BD&from=...&to=...` [DRAFT].
- `GET /api/v1/reports/dashboard?factoryId=BD` [DRAFT].
- `POST /api/v1/reports/hr-export/trigger` [TEAM-PLAN] with `yearMonth`, protected HR/Admin role and idempotency key [DRAFT].
- Mock HR `POST /mock/hr/attendance-batches` [DRAFT] with batch UUID and attendanceIds; implement receiver-side upsert and failure flag `SIMULATE_HR_DOWN` (team notes disagree HTTP 500 vs 503: both transient; test both).
- Scheduled job @ day 1 01:00 / retry until 06:00 / alert / 12:00 reconciliation deadline from M1. Manual trigger cannot accidentally duplicate real scheduled batch.

## API docs deliverable

Each owner eventually publishes OpenAPI JSON/YAML (or Actuator + Swagger). Until then this Markdown is the contract; keep an OpenAPI skeleton only if it is complete enough to validate. Unimplemented routes answer 501, never a dummy success.
