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

### Employee-ID demo routes [DRAFT, B21]

Available **only with profile `dev`**, using synthetic employees. Successful responses carry
`X-TMC-Dev-Only: true` and `Cache-Control: no-store`. Without that profile these routes
return 501. `/qr/generate` remains 501 in every profile until employee JWT identity exists.
These paths are retained as explicitly requested for the M2 demo; they are not authenticated production routes.

- `GET /api/v1/qr/{employeeId}` returns 200 for ACTIVE, 409 `QR_REVOKED` for REVOKED,
  404 `NOT_FOUND` for missing employees, or 503 `STORAGE_UNAVAILABLE` on DB failure.
- `POST /api/v1/qr/{employeeId}/rotate` replaces the secret with 32 SecureRandom bytes
  encoded as unpadded base64url, sets ACTIVE, increments `secretVersion`, and appends `ROTATE_QR_SECRET`.
- `POST /api/v1/qr/{employeeId}/activate` does the same with audit action `ACTIVATE_QR`.
- `POST /api/v1/qr/{employeeId}/revoke` sets REVOKED, increments the version, and appends `REVOKE_QR`.
  Returns `{employeeId, qrStatus, secretVersion, updatedAt}`. Repeated lifecycle commands each increment
  the version and append another event; no idempotency key is defined for these commands.
- All three POST bodies are optional: `{ "actorId":"hr-demo", "actorRole":"HR_STAFF", "reason":"synthetic departure" }`.
  Actor ID: 1–64 characters `[A-Za-z0-9._@-]`; role: 1–32 `[A-Z_]`; reason: at most 256 characters.
  These are **untrusted demo metadata**, not authorization. Defaults are `DEV_OPERATOR` / `UNAUTHENTICATED_DEV`.
  Malformed JSON/invalid fields return 400 `VALIDATION_ERROR` with no echoed values.
- Mutations lock the employee row and commit the QR update, factory `whitelist_version + 1` and audit append in
  **one transaction**; a failed audit insert rolls back all three. Rotate/activate return the same QR response as GET:

```json
{
  "employeeId":"EMP-001", "fullName":"Demo Nguyen Van An", "factoryId":"BD",
  "qrStatus":"ACTIVE", "totpCode":"048291", "timeStep":59820480,
  "issuedAt":"2026-11-14T00:00:00Z", "expiresAt":"2026-11-14T00:00:30Z",
  "remainingSeconds":30, "secretVersion":1, "qrTokenPayload":"EMP-001:048291:1",
  "periodSeconds":30, "qrPayload":{"empId":"EMP-001", "totp":"048291", "ts":1794614400, "ver":1}
}
```

The example code is illustrative. `timeStep = floor(epochSecond/30)`;
`expiresAt` is the next step boundary and `remainingSeconds` is rounded up to whole seconds (1–30).
All existing fields, including the legacy `qrTokenPayload` string `employeeId:totpCode:secretVersion`, are retained.
`periodSeconds` is an int, always 30. The nested `qrPayload` has exactly four fields: `empId = employeeId`,
six-digit String `totp = totpCode` (preserves leading zeroes), long `ts = issuedAt.getEpochSecond()`,
int `ver = secretVersion`. The frontend draws this object as QR; `ts` is issue time, not the step counter.
The DRAFT scan/version policy still needs Khoa–Khánh review; adding payload fields does not implement scanning.
Six-digit codes can collide; version/status checks are required in addition to TOTP.
No shared secret is returned to the employee. Sensitive DTO `toString()` values are redacted.

`GET /api/v1/qr/audit-logs?limit=100` (dev only, range 1–200) returns newest first:
`[{sequence, logId, timestamp, actorId, actorRole, actionType, targetResource, payloadSummary, status, previousHash, checksum}]`.
Ordering is by sequence, not timestamp. Audit encoding and limitations: [DATA-MODEL](DATA-MODEL.md#audit-integrity-baseline-m1-nfr-8--guardrail).

## 2. GET /api/v1/kiosk/whitelist [TEAM-PLAN]

- Production kiosk authentication/factory authorization remain DRAFT. Dev snapshot decisions: B27.
- The dev implementation (B21) requires `X-Kiosk-Api-Key` matching server-side `KIOSK_API_KEY`.
  Missing/wrong key returns 403; unconfigured server key returns 503 `KIOSK_AUTH_NOT_CONFIGURED`.
  There is no default in the application; Compose supplies an obvious dummy key for synthetic data.
  This key is shared across demo factories; `factoryId` is a filter, **not an authorization scope**.
- Required query `?factoryId=BD`, matching `[A-Za-z0-9_-]{1,16}`. Missing, blank or malformed scope returns
  400 `VALIDATION_ERROR`; an unknown factory returns 404 `NOT_FOUND`. Errors use `ErrorResponseDto`.
- Success is the unchanged JSON **array**, including `[]`, containing **all statuses**, including REVOKED,
  ordered by employee ID within that factory. `X-Whitelist-Version: <positive long>` is always present on 200,
  including an empty snapshot. It is the persisted `factories.whitelist_version`, never sum/max of `secretVersion`.
- Version SELECT and employee SELECT run in one read-only `REPEATABLE_READ` transaction. A concurrent commit cannot
  pair a newer body with an older header. Rotate/revoke/activate/add/delete increment the affected factory once;
  transfer increments both old and new factories, locking IDs in lexical order before audit append, all in one transaction.
  Repeated lifecycle commands increment again; transfer to the same factory is a no-op. Membership application methods
  have no public HR API yet. Direct SQL writers must follow the same revision/audit rule; no DB trigger is installed.
- Compose passes the same dummy `KIOSK_API_KEY` default to User/QR and Kiosk. Application defaults remain empty.
  Factory revisions reset on a fresh database volume; retained kiosk metadata requires a coordinated dev reset
  ([limitations](DECISIONS.md#known-limitations)). Production epoch/reset policy remains open.
- Sensitive shared secrets can be delivered only over trusted authenticated channel and protected at rest; never appear in web/frontend response or logs.
- Example **structure only** (do not commit real secrets):

```json
[{"employeeId":"EMP-001", "fullName":"Demo Nguyen Van An", "factoryId":"BD", "department":"Assembly",
  "qrStatus":"ACTIVE", "secretVersion":1, "sharedSecret":"<SENSITIVE-REDACTED>", "updatedAt":"2026-11-14T00:00:00Z"}]
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

### Current dev ingestion behavior [DRAFT]

The v1 POST routes above still return 501 without invoking ingestion. Working equivalents are
`POST /api/dev/attendance/events` and `/api/dev/attendance/sync`, registered only with profile `dev`,
unauthenticated; use them only with synthetic data. They write `dev_fixture=true`; neither request factory
nor batch `kioskId` is an authenticated identity. In the current dev DTO, `kioskId` is optional metadata.

| Operation | Implemented result |
|---|---|
| Single event, new / same ID and payload | 200 `STORED` / `ALREADY_EXISTS`, with `duplicate: false` / `true` |
| Single event, same ID but different business payload | 409 `ATTENDANCE_ID_CONFLICT`; original row unchanged |
| Single event, validation / DB constraint / storage failure | 400 / 422 `EVENT_REJECTED` / 503 `STORAGE_UNAVAILABLE` |
| Valid batch envelope | 200 with per-event `results`, including failed events; never interpret 200 alone as an ACK |
| Invalid JSON or batch envelope | 400 before processing; a batch must contain 1..500 events |

Missing/invalid fields use `VALIDATION_ERROR` or `INVALID_EVENT`; unreadable JSON uses `MALFORMED_REQUEST`.
Once deserialized, null elements and invalid event values are individually rejected; transient storage/transaction
failures yield `RETRYABLE` for that event. Conflict/constraint failures map to `REJECTED_PERMANENT` in a batch.
Successful per-event ACKs are returned only after that event's transaction commits; this is not one batch transaction.
Failed items may include `reason`; read `results` by ID, not just the supplementary ID lists.

Dedupe uses `attendanceId`, not `Idempotency-Key` or batch identity. Business comparison uses normalized IDs,
direction/result and millisecond event time; `sourceKioskId` / `devFixture` metadata do not change a fact's identity.
Retry the same fact with the same ID after a lost ACK. Dev behavior does not finalize production auth/conflict policy.
Runnable examples and deployment limits: [processor acceptance guide](../backend-services/attendance-processor/README.md#dev-handoff-acceptance).

### Dev-only attendance history [DRAFT]

There is no agreed production Attendance History GET contract. The owner's requested M2 inspection route is
`GET /api/dev/attendance/history`, registered only in Spring profile `dev`. This does not define or enable
`GET /api/v1/attendance/history`, nor change the ingestion contracts above or reporting routes below.

Required query parameters: `employeeId`, `factoryId`, `fromInclusive`, `toExclusive`.
IDs must be nonblank. Times are ISO-8601 instants with a timezone, at millisecond precision or coarser,
with `fromInclusive < toExclusive`; matching is `[fromInclusive, toExclusive)`.
Optional `page` (zero-based, default 0, nonnegative int) and `size` (default 20, int 1..100).
Rows must match both filters, exclude `dev_fixture=true`, and sort by `occurredAt ASC, attendanceId ASC`.
Stored ALLOWED and REJECTED facts are both returned. Response 200:

```json
{
  "events": [
    {"attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1","employeeId":"EMP001","factoryId":"BD",
     "gateId":"BD-G01","direction":"IN","occurredAt":"2026-11-14T01:00:00Z","scanResult":"ALLOWED"}
  ],
  "page": 0, "size": 20, "hasNext": false
}
```

No matches produce `events: []`, `hasNext: false`, retaining the requested page/size.
`hasNext` describes the same SELECT as the returned rows; offset pages can shift between requests as data arrives.
No total count, aggregation, or payroll interpretation is exposed.
Missing/invalid filters, times or pagination return 400 `VALIDATION_ERROR`; database failure returns
503 `STORAGE_UNAVAILABLE`, using the shared `{code, message, correlationId}` shape without echoed inputs.
Success and handled query errors carry `X-TMC-Dev-Only: true` and `Cache-Control: no-store`.

**Unauthenticated dev access only:** the caller's factory parameter does not grant authorization.
Anyone who can reach this route can query any factory. Use only synthetic data in a trusted local dev environment;
production employee identity, RBAC and factory authorization remain unimplemented and require owner review.
The existing dev ingestion routes create fixtures and therefore do not seed these results.

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
- The three `/api/v1/reports/*` routes answer **501** until JWT/RBAC exists. The working logic is behind the unauthenticated dev routes below ([DECISIONS B24–B26](DECISIONS.md#bootstrap-decisions)); their shapes are the proposal for the v1 routes. Days are factory days in `Asia/Ho_Chi_Minh`, instants are UTC, absent fields are omitted (not `null`).

| Dev route (`dev` profile) | Request | Answer |
|---|---|---|
| `GET /api/dev/reports/attendance` | `factoryId`, `from`, `to` (ISO dates, inclusive, ≤ 31 days), optional `department`, `offset` (0), `limit` (100, ≤ 500) | 200 `{factoryId, from, to, timeZone, offset, limit, hasMore, rows[{workDate, employeeId, fullName?, department?, firstIn?, lastOut?, inCount, outCount}], departments[{department?, employees, scans}]}`; only `ALLOWED` scans |
| `GET /api/dev/reports/dashboard` | `factoryId`, optional `date` (today in factory time) | 200 `{factoryId, date, timeZone, employeesPresent, scansIn, scansOut, rejectedScans, stillInside, lastScanAt?, presentByDepartment[], latestHrExport?}` |
| `POST /api/dev/reports/hr-export/trigger` | `{"period":"YYYY-MM"}` | 200 `{outcome:"DELIVERED", duplicateBatch, batch}` · 202 `RETRY_SCHEDULED` (batch `PENDING` with `nextRetryAt`) · 409 `ALREADY_EXPORTED` / `EXPORT_IN_PROGRESS` · 502 `HR_EXPORT_FAILED` (alert raised) · 400 month not over / malformed |
| `GET /api/dev/reports/hr-export/batches/{period}` | — | 200 `{batchUuid, period, status, triggerSource, createdAt, retryDeadline, attemptCount, lastAttemptAt?, nextRetryAt?, lastError?, itemCount}` · 404 |

Errors use `{code, message, correlationId}`: 400 `INVALID_REQUEST` / `VALIDATION_ERROR` / `MALFORMED_REQUEST`, 503 `STORAGE_UNAVAILABLE`.

What reporting sends to HR (channel `rest`): `POST /mock/hr/attendance-batches` with header `Idempotency-Key: <batchUuid>` and `{batchUuid, period, records[{attendanceId, employeeId, factoryId, gateId, direction, occurredAt}]}`; a 200 whose body is `{status:"ACCEPTED", batchUuid}` for the same batch is the acknowledgement. Channel `file` writes `hr-export_<period>_<batchUuid>.csv` with header `batch_uuid,period,attendance_id,employee_id,factory_id,gate_id,direction,occurred_at`.
- Mock HR `POST /mock/hr/attendance-batches` [DRAFT] with batch UUID and attendanceIds; implement receiver-side upsert and failure flag `SIMULATE_HR_DOWN` (team notes disagree HTTP 500 vs 503: both transient; test both).
- Scheduled job @ day 1 01:00 / retry until 06:00 / alert / 12:00 reconciliation deadline from M1. Manual trigger cannot accidentally duplicate real scheduled batch.

## 8. Demo main-flow routes [PROPOSAL Khiêm → Khoa / Khôi / Khánh, not agreed]

Called by `web-frontend` `/demo` (`src/api/endpoints.ts`). QR routes now match §1 in the dev profile (B27);
the remaining proposals below still need implementation/owner agreement. The page reports unimplemented responses honestly.

| Route | Owner | Proposed shape |
|---|---|---|
| `GET /api/v1/qr/{empId}` | Khoa | same body as §1 (`qrPayload`, `expiresAt`, `periodSeconds`); the bare §1 `qrPayload` is also accepted. Revoked: `409 {"code":"QR_REVOKED"}`. The page draws `qrPayload` as a QR image and never shows the TOTP digits |
| `POST /api/v1/qr/{empId}/rotate` | Khoa | `{}` → 200; new `secret_version`, new secret, old code invalid at kiosks after their next whitelist refresh |
| `POST /api/v1/qr/{empId}/revoke` | Khoa | `{"reason":"…"}` → 200; `qr_status = REVOKED`; audit log entry |
| `GET /api/v1/attendance/events?limit=20` | Khôi | `{"events":[ §4 event … ]}` newest first. Until it exists the page looks up each queued id with the dev route `GET /api/dev/attendance/events/{id}` |
| `POST /api/dev/kiosk/whitelist/sync` | Khôi | runs `SyncWhitelistUseCase` once; 200 |
| `POST /api/local/scan` response `reason` | Khánh | on `REJECTED`: `EXPIRED`, `INVALID_TOTP`, `REPLAYED`, `REVOKED`, `UNKNOWN_EMPLOYEE` |

**Security:** the three QR routes are unauthenticated by request of the M2 brief ("demo"). Anyone who knows an
employee id could obtain that employee's current code, which defeats ADR-02. Before any non-demo use they must
move behind `@Profile("dev")` (as `/api/dev/**`) or require the employee's JWT like `/generate`. Keep the literal
`GET /api/v1/qr/generate` mapping: `scripts/smoke.sh` expects it to answer 501.

The requested User/QR alignment is recorded in [DECISIONS B27](DECISIONS.md#bootstrap-decisions), pending owner review.
The remaining proposals do not become approved contracts through that change.

**What the merged `develop` really answers (2026-11-16, code inspection):**

| Route | Today | Gap with the proposal |
|---|---|---|
| `GET /api/v1/qr/{empId}` (dev) | all legacy fields plus `periodSeconds:30` and `qrPayload:{empId,totp,ts,ver}` (§1); revoked: `409 {"code":"QR_REVOKED"}` | QR wire shape aligned; JWT/RBAC and real kiosk scan remain incomplete |
| rotate / revoke / activate (dev) | implemented; body `{actorId?, actorRole?, reason?}` | matches |
| `GET /api/v1/attendance/events?limit=` | not implemented; the only list is `GET /api/dev/attendance/history?employeeId&factoryId&fromInclusive&toExclusive&page&size`, oldest first, **fixtures excluded** | `/demo` column 3 falls back to per-id lookups; the history route cannot show synthetic events |
| `POST /api/dev/kiosk/whitelist/sync` | not implemented (no whitelist endpoint on the kiosk) | button answers 404 |
| `POST /api/local/scan` | 501 | Khánh: [handoff](../kiosk-agent/README.md#handoff-qr-scan-for-khánh-2026-11-16) |

## API docs deliverable

Each owner eventually publishes OpenAPI JSON/YAML (or Actuator + Swagger). Until then this Markdown is the contract; keep an OpenAPI skeleton only if it is complete enough to validate. Unimplemented routes answer 501, never a dummy success.
