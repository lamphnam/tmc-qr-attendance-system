# kiosk-agent — DEV_SLICE

Owner: **Khánh** · package `vn.edu.hcmut.tmc.kiosk` · port 8080 (host 8085) · gateway `/api/local/**`, `/api/dev/kiosk/**`. Status context: [docs/STATUS.md](../docs/STATUS.md) · remaining work: [docs/TODO.md](../docs/TODO.md#2-khánh--kiosk-agent-mock-turnstile-api-sqlite-syncmanager) · class status: [docs/TRACEABILITY.md](../docs/TRACEABILITY.md#kiosk-agent-khánh).

Offline-first edge agent. The **durable SQLite queue and the store-and-forward sync are real and tested**; the **actual QR scan (TOTP, whitelist, anti-replay) is not implemented**, so `POST /api/local/scan` answers `501`.

The local whitelist domain and SQLite snapshot store are implemented, including AES-256-GCM protection of non-null verification material (D08). HTTP whitelist synchronization, TOTP and scan integration remain unimplemented; this does not change the DEV_SLICE label.

| Route | Status |
|---|---|
| `POST /api/local/scan` | **NOT_IMPLEMENTED** (501). Never returns ALLOWED |
| `POST /api/dev/kiosk/synthetic-event` | **DEV-ONLY**. Synthetic event with **no QR/TOTP check**: commit PENDING to SQLite, *then* call the local turnstile |
| `GET /api/dev/kiosk/queue` | **DEV-ONLY** counts + recent rows read from SQLite |
| `POST /api/dev/kiosk/sync/run` | **DEV-ONLY** one sync pass now (a scheduler also runs every 5 s) |
| `PUT /api/dev/kiosk/sync/offline` `{"disabled":true}` | **DEV-ONLY** stops **only** kiosk → central sync (D05); the turnstile keeps working |
| `GET /actuator/health` | UP only if the SQLite file opens, the queue table is readable and `journal_mode = wal` (`sqliteQueue` component) |

## What the slice guarantees
- **Order (ADR-01):** the event is committed to SQLite as `PENDING` *before* the open command is sent; a test proves the row is visible from a fresh connection at the moment the turnstile is called.
- **Sync status ≠ gate status.** `sync_status` (`PENDING|SYNCED|FAILED`) is central synchronisation only; `gate_command_status` (`SENT|FAILED|…`) is whether the command was accepted. `SENT` against the mock proves nothing physical.
- **SYNCED only after an ack naming the id.** Unacknowledged ids stay `PENDING` with exponential backoff; a whole-request failure (refused, timeout, 4xx, 5xx) is never a rejection — only a per-event `REJECTED_PERMANENT` makes a row `FAILED` (DECISIONS B07).
- **Durability scope:** WAL + `synchronous=FULL` cover a process crash or restart, not a disk failure ([limitations](../docs/DECISIONS.md#known-limitations)). Use the named volume, not a Windows bind mount (B17).

## Configuration (env, see `src/main/resources/application.yml`)
`KIOSK_ID`, `KIOSK_FACTORY_ID`, `KIOSK_DEFAULT_GATE_ID`, `SQLITE_PATH` (`/data/kiosk.db` in Compose, `./data/kiosk.db` otherwise), `CENTRAL_BASE_URL`, `CENTRAL_SYNC_PATH`, `CENTRAL_SYNC_DISABLED`, `TURNSTILE_BASE_URL`, `KIOSK_SYNC_ENABLED`, `KIOSK_SYNC_INTERVAL_MS`.

## Tests
`mvn test` uses real SQLite files (the production `SqliteConfig`), real loopback HTTP, ArchUnit layering and a full in-JVM offline → PENDING → reconnect → SYNCED walk-through. No mocking framework is used for the stores or HTTP.

## Local whitelist key (D08)

Provision `KIOSK_WHITELIST_KEY_BASE64` externally: standard Base64 encoding of **32 cryptographically random bytes** (AES-256). It maps to `kiosk.whitelist.secret-key-base64`; Compose forwards it only to the kiosk. There is no default, generated fallback, hardcoded key or key stored in SQLite. Keep the provisioned value out of source control, command-line arguments, logs and the SQLite volume; `.env.example` deliberately leaves it blank. Environment delivery is not a key vault: the kiosk process and privileged host/container administrators can access it.

An absent/blank key permits queue-only startup and snapshots with no secret material; encrypting material or reading any encrypted row raises the fixed domain error `UNAVAILABLE`, including for an unrelated employee lookup. Invalid Base64/key size fails bean creation with that same safe error. A wrong 32-byte key, altered non-null envelope or unsupported format raises `INVALID_CACHE`. None of these failures becomes a cache miss, success or plaintext fallback. Preserve the same external key across restarts; restore a matching key if configuration is lost. Changing the environment key cannot rotate existing ciphertext automatically.

`shared_secret_enc` remains the existing BLOB column. Its v1 envelope is `TMCW` (4 bytes), version `1` (1 byte), a new random nonce (12 bytes), and ciphertext with a 128-bit GCM tag. The header and length-prefixed UTF-8 context are authenticated: kiosk ID, factory ID, employee ID, QR status, secret version, snapshot version, millisecond timestamp and employee count. Moving a BLOB to another employee or changing this context therefore fails authentication. Java JCE performs encryption/authentication; no custom cryptographic primitive is implemented.

Encryption occurs inside the existing `BEGIN IMMEDIATE` snapshot replacement; employees and metadata commit together, and failure rolls back the replacement. Reads decrypt inside the existing consistent read transaction. Temporary plaintext copies obtained from the domain accessor and from decryption are cleared after use; the domain retains its defensive copy. This does not guarantee erasure of all JVM/provider memory copies.

The envelope is a **local storage format**, not a Central HTTP encoding. Material stays `byte[]`; Central encoding, provisioning/key custody, multi-key rotation/migration and offline revocation policy still need owner agreement. Existing opaque/plaintext BLOBs are never interpreted as legacy secrets. GCM authenticates present encrypted material and its context, not the whole database: it cannot detect a coordinated database rollback or distinguish a removed BLOB (SQL NULL) from legitimately absent material. This also does not protect against a compromised kiosk process holding the key.
