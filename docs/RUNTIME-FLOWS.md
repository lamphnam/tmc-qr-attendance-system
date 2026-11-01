# RUNTIME-FLOWS — runtime flows, error handling, sequence notes

Decisions behind these flows: [ARCHITECTURE](ARCHITECTURE.md) (ADR-01..03). Wire formats: [API-CONTRACTS](API-CONTRACTS.md). Tests that exercise them: [TESTING](TESTING.md).

## Flow A — Obtain dynamic QR and scan online [BASELINE-M1 + TEAM-PLAN]

```mermaid
sequenceDiagram
    participant E as Employee Web UI
    participant U as User QR Service
    participant K as Kiosk Agent
    participant L as Local SQLite
    participant T as Turnstile (LAN Mock)
    participant A as Attendance Processor
    participant P as PostgreSQL
    E->>U: GET /api/v1/qr/generate (JWT)
    U-->>E: QR payload (no shared secret)
    E->>K: scan QR via /api/local/scan
    K->>L: verify whitelist + TOTP, atomic used_token+PENDING event
    L-->>K: durable commit
    K->>T: openGate(gateId)
    K-->>E: allowed / command status
    par background (not gate fast path)
      K->>A: POST /api/v1/attendance/events
      A->>P: validate + idempotent insert
      P-->>A: commit
      A-->>K: acknowledgement
      K->>L: mark event SYNCED
    end
```

- QR expires on TOTP step boundary; reject stale/invalid/duplicate locally.
- When turnstile returns error after local commit, preserve event + gate command failure info; don't falsely report physical opening.
- Central ack delayed/lost AFTER commit → retry same `attendanceId`; no duplicate insert.

## Flow B — Lost WAN + recovery [BASELINE-M1]

```mermaid
sequenceDiagram
    participant E as Simulator
    participant K as Kiosk Agent
    participant L as SQLite
    participant T as Local Turnstile
    participant A as Central Ingestion
    E->>K: Set sync offline (demo)
    E->>K: Scan valid QR (already available)
    K->>L: Commit event PENDING
    K->>T: openGate over local network
    K-->>E: ALLOWED
    Note over K,A: Central connection unavailable
    E->>K: Restore sync online
    K->>L: Read PENDING batch
    K->>A: POST /api/v1/attendance/sync
    A-->>K: per-event durable ack IDs
    K->>L: Mark only acked IDs SYNCED
```

- The network toggle disables **only central API sync**, never the same-host mock turnstile.
- Offline scanning needs a QR already displayed/cached or a securely mobile-generated TOTP; the employee web cannot fetch a **new** QR from central during a WAN outage.
- The whitelist must have been synced before going offline; revocations reach an isolated kiosk only at the next sync.
- The 20-scan offline scenario and the lost-ack case are specified in [TESTING](TESTING.md) T02.

## Flow C — Monthly HR/Payroll Export [BASELINE-M1]

```mermaid
sequenceDiagram
    participant S as Reporting Scheduler
    participant P as PostgreSQL
    participant H as Mock HR/Payroll
    participant O as HR/Admin
    S->>P: At day 1 01:00 create/reuse batch UUID
    S->>P: Load attendance for previous period
    S->>H: POST batch (stable IDs)
    alt success
      H-->>S: accepted/upsert
      S->>P: mark SENT/ACKNOWLEDGED
    else HTTP 5xx/timeout
      H-->>S: retryable error
      S->>P: persist attempts/next retry
      S->>H: exponential retry (until 06:00)
      alt still failing after 06:00
        S-->>O: alert for manual action before 12:00
      end
    end
```

- `POST /api/v1/reports/hr-export/trigger` (protected HR/Admin) is a **demo bypass of the schedule**, not a different data contract. Persisted batch state must allow retry after a restart (tests: [TESTING](TESTING.md) T05).

## Flow D — Security Guard manual gate [BASELINE-M1 scope, DRAFT mechanism]

1. Guard authenticated + authorized role sends gate ID and reason.
2. Gate Station Service validates role, gate operational status, records request ID and audit event.
3. Gate command must reach kiosk over a defined authenticated channel; **not yet specified by M1**. Demo may use mock adapter and document this explicitly.
4. Log success/failure acknowledgement, do not just call gate open while ignoring unauthorized access.

## Error matrix

| Event | Local action | Central action | Expected user/admin effect |
|---|---|---|---|
| Bad TOTP | do not open; log rejected | optional reject event sync | clear denial |
| Duplicate local TOTP step | do not open; log rejected | optional reject event sync | replay blocked |
| WAN outage | PENDING persists; attempt local open | none | status PENDING visible |
| HTTP 5xx/timeout | retry later, same IDs | atomic idempotent persist | no duplication |
| Permanent 4xx schema issue | FAILED + alert; preserve record | return per-event failure | manual investigation |
| Central commits then response lost | retain PENDING until retry ack | return duplicate success on retry | eventual SYNCED |
| Turnstile adapter unavailable | retain event plus failure status | sync event eventually | no false 'gate opened' |
| Kiosk restart | SQLite queue survives | no change | background sync resumes |

**Definition:** `PENDING/SYNCED/FAILED` denotes *central synchronization status*, not physical gate open status. Use separate `gateCommandStatus` if needed.
