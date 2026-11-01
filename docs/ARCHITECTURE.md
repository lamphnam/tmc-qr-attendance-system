# ARCHITECTURE — baseline (DO NOT DRIFT), ADRs, build standards

Part 1 is the M1 architecture; part 2 is how the repository is built. Flows: [RUNTIME-FLOWS](RUNTIME-FLOWS.md). Owners: [OWNERSHIP](OWNERSHIP.md). Class names: [TRACEABILITY](TRACEABILITY.md#java-class-map).

# Part 1 — Architecture

## Style [BASELINE-M1]

**Hybrid Service-based Architecture (SBA) + Offline-First Kiosk.** Four coarse-grained domain services, deployed separately, sharing **one central PostgreSQL**, talking REST (HTTPS in production). The kiosk is independent, with local SQLite (WAL) store-and-forward. **No** RabbitMQ/Kafka/event bus or service mesh in the baseline. Redis, read replica, PgBouncer and transactional outbox are optional only with a measured reason. Not a monolith; not database-per-service.

## Modules / runtime boundaries

| Runtime | Responsibility | Persists |
|---|---|---|
| web-frontend | employee QR, kiosk simulator, HR/Admin/Security/dashboard pages | browser/session |
| nginx-gateway | reverse proxy routing REST; production TLS is outside this demo | config |
| user-qr-service | HR sync, JWT/RBAC, TOTP, QR revocation, whitelist, audit access | PostgreSQL |
| gate-station-service | gate/kiosk registration, status, heartbeat, manual-open workflow | PostgreSQL |
| attendance-processor | receive single/batch events, validate, idempotency, ACID persist | PostgreSQL |
| reporting-service | dashboard/report, HR batch export, export audit/status | PostgreSQL |
| kiosk-agent | fast-path offline validation, SQLite, turnstile adapter, SyncManager | SQLite volume |
| mock-turnstile-api | simulate gate open/status over the local network | ephemeral |
| mock-hr-payroll-api | simulate HR import/export, failure and dedupe | persistent if a restart demo needs it |
| central-db | central data, ACID transactions, unique IDs | PostgreSQL volume |

`attendance-processor` is a standalone service: never move the main attendance logic into the gate service for speed. Owners: [OWNERSHIP](OWNERSHIP.md).

## Dependency direction (per service)

```text
presentation  ──>  application  ──> domain <── infrastructure
                         │                         ▲
                         └── uses domain ports ────┘
```

- `presentation`: controllers, request/response DTOs, validation; no business rules.
- `application`: use cases, orchestration, transaction boundaries, per-use-case retry policy.
- `domain`: entities, value objects, rules, and the repository/external-service **interfaces (ports)**. **No** Spring MVC, JPA, SQLite/JDBC or HTTP-client imports.
- `infrastructure`: JDBC/JPA, SQLite persistence, REST clients, configs/adapters implementing the ports.
- Spring Boot main/config classes live outside `domain`. Dependency inversion through constructor injection. No circular dependencies, no cross-service Java imports, no shared Java library.
- Enforced by an ArchUnit `*ArchitectureTest` in every Java module (it fails `mvn test`). Where a module needs a composition root (e.g. `kiosk-agent`'s `config` package) it sits outside the four layers on purpose.

## ADR-01 — Offline-first sync [BASELINE-M1]

- The kiosk authorizes scans from a **local** whitelist and verification material; a scan never calls central to authorize.
- **Durably write the SQLite event (PENDING) before sending the OPEN command.** Accepted and rejected scan attempts both get a record, per policy (open: D06).
- `SyncManager` sends one event to `/events` when online, a batch to `/sync` on recovery; each event has a stable `attendanceId` UUID, created once at scan time.
- Central validates and persists idempotently through a unique `attendance_id` and returns the ack **only after commit**; only acked IDs move `PENDING → SYNCED`.
- Deterministic 4xx validation errors → `FAILED` for inspection; transient 5xx / timeout / disconnect → retry with backoff. Mixed batches need a per-event ack.
- The queue survives a **kiosk process restart** through a SQLite volume. It does not survive disk failure ([DECISIONS — limitations](DECISIONS.md#known-limitations)).

## ADR-02 — Dynamic QR (TOTP) [BASELINE-M1]

- TOTP per RFC 6238, HMAC-SHA256, 30 s step, per-employee shared secret. The QR payload identifies the employee and carries the TOTP (the team proposal adds timestamp/version).
- The browser receives **only the generated QR payload** from the authenticated user service, never the shared secret. A mobile app could store a secret securely; the web must not.
- The kiosk accepts the current and the immediately previous step (~60 s window) when clocks are synchronized. Anti-replay: unique `(employee_id, matched_time_step)` stored atomically in SQLite together with the event.
- A cached secret is limited to the kiosk's permitted factory whitelist and protected at rest; rotate/disable and re-sync the whitelist. Offline revocation cannot be instant; local anti-replay cannot stop cross-kiosk reuse while disconnected (limitations apply).

## ADR-03 — Monthly HR export [BASELINE-M1]

- `reporting-service` starts the export at **01:00 on day 1** with a stable `batchUuid` + `attendanceId`; the receiver must upsert idempotently.
- Timeout/5xx → exponential backoff **until 06:00**; alert HR and Admin if still failing; reconciliation must complete **before 12:00**.
- A manual trigger for the demo is [TEAM-PLAN]. Persist batch status and attempts. An export failure must never affect the kiosk gate fast path.

## Deployment

- [BASELINE-M1] physical: a Linux kiosk on the factory LAN with a local turnstile; WAN to a central Linux VM running Nginx + Docker Compose services + PostgreSQL. TLS 1.3 / mTLS apply to the production design.
- [DRAFT demo] Docker Compose on one developer machine **simulates** edge + central; it is not proof of a physical factory deployment. The offline toggle must isolate **only kiosk → central sync** and leave kiosk → mock turnstile working.

## Reliability trade-off

The fast path is the time from "scan request accepted at the kiosk" to "response / turnstile command attempt". Central sync is asynchronous and outside this budget. Measure real hardware acknowledgement and network latency separately. An HTTP 200 from the turnstile mock is not proof a physical gate opened.

# Part 2 — Build and repository standards

## Stack [DRAFT unless stated]

| Concern | Choice |
|---|---|
| Backend | Java 21, Spring Boot **3.5.16**, Maven (team plan; the version is DECISIONS B02). Each module declares its own `spring-boot-starter-parent`; the root `pom.xml` is only an aggregator. Do not pick an unresolvable or made-up Spring Boot version |
| Frontend | React + TypeScript + Vite (D16); TypeScript/Vite are a safe default, the plan only says ReactJS |
| Central DB | PostgreSQL 16 (image **not yet pinned by digest**; pin before release) |
| Kiosk DB | SQLite via JDBC on a durable volume, `PRAGMA journal_mode=WAL`, `synchronous=FULL` |
| Gateway | Nginx reverse proxy for the central API and the frontend |
| Mocks | Lightweight and isolated; the five Java applications are the real services (the bootstrap wrote the mocks in Node, B03) |
| Not used | Kafka, RabbitMQ, Redis, Keycloak, service mesh (D14) — no addition without an approved ADR |

## Per-service layout

```text
<service>/
├── pom.xml  Dockerfile  .dockerignore  README.md
├── src/main/java/vn/edu/hcmut/tmc/<bounded-context>/
│   ├── <Something>Application.java
│   ├── presentation/{controller,dto,mapper}/
│   ├── application/{service,usecase}/
│   ├── domain/{model,service,port|repository,exception}/
│   └── infrastructure/{persistence,config,client|sync|turnstile}/
├── src/main/resources/application.yml
└── src/test/java/...
```

Layer names matter more than every optional subfolder. Create real minimal working classes and tests, not empty `package-info` trees. The actual repository tree is in the root [README](../README.md#5-cấu-trúc).

## Compose and build rules

- Services: `central-db`, `nginx-gateway`, `user-qr-service`, `gate-station-service`, `attendance-processor`, `reporting-service`, `kiosk-agent`, `mock-turnstile-api`, `mock-hr-payroll-api`, `web-frontend`.
- `central-db`: named volume and a healthcheck that really proves readiness; `depends_on` with health conditions is not a substitute for application readiness. The kiosk must not depend on central.
- Cross-container URLs use Compose service DNS, never `localhost`.
- Multi-stage Dockerfiles. `-DskipTests` in an image build is never evidence that tests pass.
- `JAVA_TOOL_OPTIONS=-Xms64m -Xmx192m` is a dev heap cap, **not** a RAM claim (D15).
- Secrets: `.env` is git-ignored; `.env.example` holds obviously dummy values. Never commit credentials, private keys, SQLite DBs, Postgres data, jars, `node_modules`.
- Git: `.gitattributes` (LF), `.editorconfig`, `.gitignore`; PRs to the shared branch; no force-push or history rewrite of collaborators' work. Review workflow: [OWNERSHIP](OWNERSHIP.md#pull-request-workflow).
