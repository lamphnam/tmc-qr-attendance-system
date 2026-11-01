# kiosk-agent — DEV_SLICE

Owner: **Khánh** · package `vn.edu.hcmut.tmc.kiosk` · port 8080 (host 8085) · gateway `/api/local/**`, `/api/dev/kiosk/**`. Status context: [docs/STATUS.md](../docs/STATUS.md) · remaining work: [docs/TODO.md](../docs/TODO.md#2-khánh--kiosk-agent-mock-turnstile-api-sqlite-syncmanager) · class status: [docs/TRACEABILITY.md](../docs/TRACEABILITY.md#kiosk-agent-khánh).

Offline-first edge agent. The **durable SQLite queue and the store-and-forward sync are real and tested**; the **actual QR scan (TOTP, whitelist, anti-replay) is not implemented**, so `POST /api/local/scan` answers `501`.

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
