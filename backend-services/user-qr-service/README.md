# user-qr-service — DEV_SLICE

Owner: **Khoa** · package `vn.edu.hcmut.tmc.userqr` · port 8080. Status context: [docs/STATUS.md](../../docs/STATUS.md) · tasks: [docs/TODO.md](../../docs/TODO.md#1-khoa--user-qr-service-authrbactotpaudit-gateway-integration).

Implements RFC 6238 HMAC-SHA256 TOTP (six digits, 30 seconds, current/previous window),
QR issue/rotate/revoke/activate, kiosk whitelist and a transactional SHA-256 audit chain.
Wire shapes, HTTP statuses and validation: [API-CONTRACTS](../../docs/API-CONTRACTS.md#employee-id-demo-routes-draft-b21).

Use `SPRING_PROFILES_ACTIVE=dev` with **synthetic data only**. The requested `/api/v1/qr/{employeeId}`
lifecycle/audit routes and `/api/v1/kiosk/whitelist` are enabled only in that profile.
Default profile returns 501. `/api/v1/qr/generate` stays 501 until JWT identity exists.
Health remains real and reports 503/DOWN if PostgreSQL is unreachable; startup does not require a DB.
No production in-memory fallback or service-owned DDL is used.

Compose supplies `KIOSK_API_KEY=dev-only-kiosk-change-me`. Outside Compose explicitly configure
the server key and send it in `X-Kiosk-Api-Key` from trusted kiosk tooling. Without a server key,
whitelist returns 503; missing/wrong credentials return 403. Do not put this key in React.

Whitelist requires `factoryId` (`[A-Za-z0-9_-]{1,16}`): missing/blank/malformed → 400 `VALIDATION_ERROR`,
unknown factory → 404 `NOT_FOUND`. The JSON body stays an array (also `[]`); `X-Whitelist-Version` is the
positive long factory revision, read with the body in one read-only `REPEATABLE_READ` transaction.
Lifecycle and application-level add/delete/transfer operations commit employee, factory revision and audit together.
Transfer increments both factory revisions in lexical lock order; same-factory transfer is a no-op.
Membership methods do not expose an HR endpoint; direct SQL writers must maintain the revision/audit invariant.

GET/rotate/activate QR responses retain every legacy field and add `periodSeconds: 30` plus
`qrPayload: {empId, totp, ts, ver}` (issue epoch seconds, six-digit string code, int secret version).
A revoked employee GET returns **409 `QR_REVOKED`**. DTO string representations redact TOTP/payload values.

```bash
curl http://localhost:8080/api/v1/qr/EMP-001
curl -X POST http://localhost:8080/api/v1/qr/EMP-001/rotate
curl -X POST http://localhost:8080/api/v1/qr/EMP-001/revoke -H 'Content-Type: application/json' -d '{"actorId":"hr-demo","actorRole":"HR_STAFF","reason":"synthetic departure"}'
curl -X POST http://localhost:8080/api/v1/qr/EMP-001/activate
curl -H 'X-Kiosk-Api-Key: dev-only-kiosk-change-me' 'http://localhost:8080/api/v1/kiosk/whitelist?factoryId=BD'
curl http://localhost:8080/api/v1/qr/audit-logs
mvn -B -ntp -pl backend-services/user-qr-service test
node --test tests/integration/user-qr.test.mjs
```

Run the test commands from the repository root on JDK 21. PostgreSQL tests use Testcontainers
and the canonical `init-db/schema.sql`/`seed.sql`; without Docker they are **skipped**.
The Mockito subclass mock maker is configured for Alpine/WSL2 compatibility.

`init-db` applies only to an empty volume. Use a separate Compose project/ports for verification
or review and back up existing dev data before a reset; the service never migrates tables on startup.
Fixtures: `EMP-001`–`EMP-005`, factories `BD`/`LA`; `EMP-005` is revoked. Original fixtures remain.

The application owns the transaction; repositories own SQL and locking; domain services own
cryptographic calculations. Separate append/query audit ports keep the writer append-only.
The extra port contracts and PostgreSQL lock are a maintenance cost; one production store exists,
so store substitution is a design option rather than demonstrated OCP/LSP.
Security, offline revocation and audit limitations: [DECISIONS](../../docs/DECISIONS.md#known-limitations).

Config placeholder retained: `tmc.hr-api.base-url`; HR synchronization is still unimplemented.
