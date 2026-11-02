# SOLID-GUIDELINES — Pragmatic SOLID for TMC M2

How to decide whether SOLID helps, how to apply each principle in *this* codebase, and how to prove it when the lecturer opens the source. The short binding rules are in [../AGENTS.md](../AGENTS.md#pragmatic-solid); this file holds the reasoning, the TMC examples, the anti-patterns, the defence questions and the evidence table.

Why it matters [OFFICIAL-M2]: Task 1 asks how SOLID was applied plus a Reflection Report, Task 2 asks for a main module implemented per the design and SOLID, and the team plan says the reflection must cite real code and trade-offs, not definitions ([PROJECT §4](PROJECT.md#4-m2-deliverables)). A principle that cannot be pointed at in code and defended under questioning is worth nothing in the defence.

## 1. Philosophy

> When designing, implementing, or refactoring production code, consider SOLID principles where they provide tangible improvements in maintainability, extensibility, testability, substitutability, or separation of responsibilities.
> Do not introduce unnecessary abstractions, interfaces, inheritance hierarchies, design patterns, or additional classes solely to demonstrate SOLID.
> Prefer the simplest correct design that satisfies current requirements while preserving reasonable extension points.

**Consider SOLID when**

- one class holds several independent responsibilities that change for different reasons;
- business logic calls the database, an HTTP client or an external provider directly;
- there are, or the requirements demand, several implementations that must be swapped;
- a business rule is expected to grow through a strategy, policy or adapter;
- the code is hard to unit-test because it drags infrastructure in;
- one change forces edits in several unrelated places.

**Do not force it when**

- the class or function has one simple, clear job;
- an abstraction would only add a layer of indirection;
- nobody has a grounded need to replace or extend the implementation;
- the refactor only increases the number of files;
- the simple design is already easy to test and meets the requirement.

The number of interfaces, classes, patterns or principles named is **not** a quality metric. Every abstraction costs a file, a name, an indirection and a contract that must be kept true, so it needs a reason that can be said in one sentence. In this repository the acceptable reasons are:

1. it keeps a volatile detail (PostgreSQL, SQLite, HTTP, a turnstile device) out of `domain`/`application`;
2. two or more implementations exist or are required;
3. it lets a test replace something slow or non-deterministic (network, clock). Stores are the exception: the repository rule is to test against real SQLite and real PostgreSQL ([TESTING](TESTING.md#test-layers-that-exist)).

Otherwise write a concrete class. `RetryBackoffPolicy`, `IdempotencyChecker` and `AttendanceSyncResponseMapper` are deliberately concrete: one deterministic rule each, no foreseen second implementation, tested directly.

## 2. The principles in TMC

Each principle: the rule, where it lives in TMC, what must be true before you claim it, and the usual mistakes. Class names are real unless marked *planned*; what exists and what is tested is in the [evidence table](#5-solid-evidence).

### Single Responsibility (SRP)

**Rule.** A class has one main reason to change, tied to one business or technical responsibility. It does not mean one method per class.

**In TMC.** The ingestion path of `attendance-processor` is split by *why it would change*:

| Class | Changes when… |
|---|---|
| `DevAttendanceController` | the HTTP shape, validation or status codes change |
| `AttendanceIngestionService` | the transaction boundary or the stored / rejected / retryable policy changes |
| `IdempotencyChecker` | the meaning of a repeated `attendanceId` changes |
| `AttendanceSyncResponseMapper` | the wire ack format changes |
| `PostgresAttendanceRepository` | the SQL or the unique-key mechanics change |

Changing the ack JSON touches the mapper and the DTO, not the transaction or SQL. In `kiosk-agent`, `SyncPendingEventsUseCase` (the PENDING → SYNCED / FAILED state machine) is separate from `RetryBackoffPolicy` (how long to wait), `CentralSyncSwitch` (the offline switch), `SyncManagerScheduler` (when to run) and `RestAttendanceSyncClient` (the wire).

**Before you claim it.** Name the two different reasons to change that you separated. If you cannot, you split for show.

**Mistakes.** One-method classes; `*Manager`/`*Helper`/`*Util` buckets; splitting along technical nouns that never change independently; the opposite, a controller that opens JDBC connections.

### Open/Closed (OCP) — high priority

**Rule.** Add behaviour by extension where several reasonable variants exist, instead of repeatedly editing stable logic.

**In TMC.** `ITurnstileAdapter.openGate(gateId)` in `kiosk-agent`. When an agent applies OCP it must answer five questions; this is the worked answer for the turnstile:

| Question | Answer |
|---|---|
| What is stable? | The ordering "commit PENDING to SQLite, then command the gate" in `QueueSyntheticEventUseCase` (and the future `ProcessQrScanUseCase`). |
| Where is the extension point? | The port `ITurnstileAdapter` in `domain/port`. |
| What must change to add a device? | One new class under `infrastructure/turnstile/` with its tests, plus the line that *selects* it in the composition root. The use cases stay untouched. |
| Is core logic really untouched? | Yes for the use cases. Not for the composition root. Today `EthernetTurnstileAdapter` is the only `@Component` implementing the port; a second one makes injection ambiguous until a property-driven selection exists, and that selection is **not built**. |
| What does it buy over the direct call? | The use case imports no HTTP client, so it is tested with a lambda (`QueueSyntheticEventUseCaseTest`); mock and real device differ only in wiring; `KioskArchitectureTest` fails if the domain or application imports infrastructure. |

Limits to state honestly: there is one production implementation, so OCP here is `DESIGNED`, not demonstrated. And `openGate` returns only a command result: a device that reports door state, or needs a handshake, would force a change. The fix is a separate capability port (see ISP), not a wider `ITurnstileAdapter`.

**Before you claim it.** Be able to list the files a new variant touches. If the list contains the use case, or an `if (type == …)` was added to it, the design is not open.

**Mistakes.** An interface for every class; `if/switch` on a device or provider type inside a use case; registries, reflection or plugin frameworks for two variants; a strategy hierarchy for a **closed** set. The sync outcomes (`STORED`, `ALREADY_EXISTS`, `RETRYABLE`, `REJECTED_PERMANENT`) come from the wire contract ([DECISIONS B05](DECISIONS.md#bootstrap-decisions)); a `switch` is the right tool, and adding an outcome is a contract change that belongs in DECISIONS. Prefer a `switch` *expression* over an enum (as `AttendanceSyncResponseMapper.toBatchResponse` does): the compiler then rejects a new constant. The `switch` *statement* in `SyncPendingEventsUseCase` is not checked for exhaustiveness, so convert it if the enum grows.

### Liskov Substitution (LSP) — high priority

**Rule.** Every implementation of an abstraction must be replaceable without breaking the behavioural contract its clients rely on: preconditions, postconditions, invariants, error behaviour, side effects. Two classes declaring `implements` the same interface prove nothing.

**In TMC.** The contract of `ITurnstileAdapter` as it stands, and where it is thin:

| Aspect | Stated today | Gap |
|---|---|---|
| Precondition | — | which `gateId` values are valid is not stated; `EthernetTurnstileAdapter` puts it straight into the URL |
| Postcondition | `SENT` means the peer accepted the command, never that the gate physically opened (`GateCommandStatus`) | — |
| Errors | "never throw; failures come back as FAILED / UNSUPPORTED" (port Javadoc) | `UNSUPPORTED` has **no defined meaning, no producer and no consumer** in the code |
| Side effects | must not touch central, must work with sync off (`EthernetTurnstileAdapter` Javadoc) | not written on the port, so a new implementer may not see it |
| Latency | — | no bound stated, yet NFR-1 depends on the fast path |

**Rules.**

- An adapter must not return `SENT` without sending, `SUCCESS` as a placeholder, or throw where the contract says to return a status.
- `UNSUPPORTED` (or `UnsupportedOperationException`) is legitimate only if the port contract defines it — what it means, whether the caller may retry, what the operator sees — and a test pins how the client reacts. Returning it casually pushes the client into a wrong branch.
- An adapter that can only ever return `UNSUPPORTED` is a labelled stub, not LSP or OCP evidence. Do not create one to fill a design tree ([TRACEABILITY](TRACEABILITY.md#java-class-map)).
- If a new implementation cannot honour the contract, the abstraction is wrong: re-cut the contract, add a capability port, or split the interface.
- A test double (a lambda, `FakeRepository`) is substitutable only for the clauses the test relies on. It is not proof of LSP.

**Contract tests.** When a port has a second implementation or a faithful fake, run one shared test class against all of them (pattern, not existing code):

```java
abstract class TurnstileAdapterContract {
    abstract ITurnstileAdapter peerAcceptsTheCommand();
    abstract ITurnstileAdapter peerIsUnreachable();

    @Test void acceptedCommandIsSentNeverPhysicallyConfirmed() { /* SENT, detail does not claim an open */ }
    @Test void unreachablePeerIsFailedAndDoesNotThrow()        { /* FAILED, no exception */ }
}
```

The three Ethernet cases in `HttpAdaptersTest` (`turnstileAcceptedCommandIsSentButNeverClaimsAPhysicalOpen`, `turnstileErrorStatusBecomesFailedWithoutThrowing`, `unreachableTurnstileBecomesFailedWithoutThrowing`) are the clauses to lift. The best current candidate for a contract test is `IAttendanceRepository`: it has a real implementation and an in-memory fake, and the fake cannot prove the atomic unique-key guarantee the port promises.

**Mistakes.** Fake success; stricter preconditions in one implementation; swallowing an error the contract says to report; an extra side effect (an adapter that calls central); claiming LSP from `implements`.

### Interface Segregation (ISP)

**Rule.** Shape interfaces around what each client needs; do not force clients to depend on methods they do not use. Do not shatter an interface into tiny pieces without a benefit.

**In TMC.** The kiosk keeps three ports apart by consumer and reason to change: `IKioskSyncAPI` (kiosk → central), `ITurnstileAdapter` (local device), `IAttendanceLocalRepository` (durable queue). The kiosk sync API and the monthly HR/Payroll export are separate responsibilities with different consumers and contracts (per-event ack versus a `Batch_UUID` batch); the HR export port is *planned* in `reporting-service`, which is still a scaffold.

**An open observation.** `IAttendanceLocalRepository` has nine methods and its three clients use disjoint subsets: `QueueSyntheticEventUseCase` (`insertPending`, `updateGateCommandStatus`, `findById`), `SyncPendingEventsUseCase` (`findDueForSync`, `markSynced`, `markFailed`, `scheduleRetry`), `QueueQueryService` (`countByStatus`, `findRecent`). Splitting into writer / sync / query ports is defensible, but today there is one cohesive concept, one implementation, and tests run against the real SQLite file, so a split adds two interfaces and no benefit. Revisit when a client needs a narrower fake.

**Mistakes.** A one-method interface per class; implementations that throw `UnsupportedOperationException` because the interface was too wide (an ISP and LSP smell at once); a god `Service` interface.

### Dependency Inversion (DIP)

**Rule.** Important business logic depends on abstractions that *it* owns, not on infrastructure details. Constructor injection alone is dependency injection, not dependency inversion.

**In TMC.** The port lives in `domain` (`IAttendanceLocalRepository`, `ITurnstileAdapter`, `IKioskSyncAPI`, `IAttendanceRepository`); the implementation lives in `infrastructure` (`SQLiteAttendanceRepository`, `EthernetTurnstileAdapter`, `RestAttendanceSyncClient`, `PostgresAttendanceRepository`); the composition root (`KioskWiringConfig`, `DomainBeansConfig`) wires them. The direction is enforced, not just intended: `KioskArchitectureTest` has three rules (layers, framework-free domain, application knows no JDBC/SQLite) and `AttendanceArchitectureTest` has two (layers, framework-free domain).

**Before you claim it.** Check three things: the interface is declared in the layer of its *client*; its signature leaks no infrastructure type (`RestClient`, `ResponseEntity`, `ResultSet`); an ArchUnit rule or a compile-level boundary fails if someone breaks it.

**Accepted trade-off.** The application layer uses Spring (`@Service`, `TransactionOperations` in `AttendanceIngestionService`) because transaction boundaries belong there ([ARCHITECTURE](ARCHITECTURE.md#dependency-direction-per-service)). The rules forbid infrastructure types (JDBC, SQLite) in `application`, not the Spring framework.

**Mistakes.** `@Autowired` on a concrete infrastructure class; the interface placed in `infrastructure` next to its only implementation; domain code importing a Spring or HTTP type; a shared Java library "for reuse" across services, which [ARCHITECTURE](ARCHITECTURE.md#dependency-direction-per-service) forbids.

## 3. Making it defensible

When a component with architectural meaning (a port, an adapter, a split or merge of a large class) is added or changed, be able to answer all of these. The PR description or the closing message of the task answers them in a few lines; a trivial change needs no ceremony.

1. Which principle is applied?
2. Which class, interface or method shows it?
3. What was the design risk before, or without it?
4. Why does this design remove that risk?
5. What is the practical benefit for TMC?
6. What is the trade-off or abstraction cost?
7. Which test or extension scenario shows it works?

Short form:

```text
SOLID note: OCP + DIP — ITurnstileAdapter (domain/port) with EthernetTurnstileAdapter.
Why: the use case must not change when the device changes; checked by KioskArchitectureTest.
Limit: one implementation today; selection of a second adapter is not built.
```

**What counts as evidence:** an ArchUnit rule that fails when the direction is broken; a test that runs the same code with a second implementation or a faithful double; a contract test over all implementations; an extension scenario that names the exact files a change touches. **What does not:** a class name, an interface name, `@Autowired`, a comment. No `// SOLID`, `// OCP`, `// LSP` comments: clear code and this document are the record.

## 4. Anti-patterns

| Smell | Why it is wrong here | Instead |
|---|---|---|
| `IFooService` + `FooServiceImpl`, one implementation, no boundary | indirection with no variation or isolation | a concrete class |
| Refactoring a working use case to "show" a principle | risk and churn with no requirement behind it | leave it; note the limit in the table |
| An empty class for every name in a design tree | not evidence ([TRACEABILITY](TRACEABILITY.md#java-class-map)) | a labelled 501 stub or a [TODO](TODO.md) |
| A stub adapter claimed as LSP/OCP proof | it cannot be substituted for a working one | build the second adapter, or claim nothing |
| Fake success from an adapter | clients act on a lie (breaks "no false success") | return `FAILED`, or `501` at the HTTP edge |
| Factory/registry/plugin machinery for two variants | more code than the variation | a property and one `@Bean` choice in the composition root |
| Strategy objects for a closed contract enum | four classes, no variation | an exhaustive `switch` expression |
| Port in `infrastructure`, or leaking a framework type | inverts nothing | the port in the client's layer, in domain terms |
| Widening a port for one new device | every implementer pays | a separate capability port |
| Counting interfaces or principles as quality | not a metric | explain the benefit and the cost |

## 5. SOLID evidence

Rules for this table:

- A row exists only for code that exists. Do not add a row for a planned class, and do not edit it for a small change.
- Status uses the claims vocabulary of [PROJECT §5](PROJECT.md#5-claims-policy-canonical--other-docs-link-here): `DESIGNED`, `IMPLEMENTED` (the code was read and does it), `TESTED` (a named test exists and its last run passed), `LIMITATION`. Never set `IMPLEMENTED` or `TESTED` without checking the code or running the test.
- The modules are `DEV_SLICE` or `SCAFFOLD` ([STATUS](STATUS.md#2-module-matrix)): a row proves the structure of real code, not a finished feature.
- Whoever changes the related implementation updates the row in the same PR. [OWNERSHIP](OWNERSHIP.md) makes Khánh the owner of the SOLID evidence documentation; each owner adds rows for their module. Then run `node scripts/check-docs.mjs`, which also validates the links below.

Checked against source on **2026-11-02**. Test run the same day: `mvn -B -ntp -pl kiosk-agent,backend-services/attendance-processor test` on JDK 21.0.11, exit 0. `kiosk-agent`: 37 run, 0 failed, 0 skipped. `attendance-processor`: 27 run, 0 failed, **6 skipped**: `AttendanceIdempotencyPostgresIntegrationTest` needs Docker, which was not running; it last ran on 2026-11-01 ([STATUS](STATUS.md#3-commands-run-and-results)).

| Principle | Module | Class / interface | Design rationale | Practical benefit | Verification | Status |
|---|---|---|---|---|---|---|
| DIP | `kiosk-agent` | [IAttendanceLocalRepository](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/IAttendanceLocalRepository.java) ← [SQLiteAttendanceRepository](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/infrastructure/persistence/SQLiteAttendanceRepository.java) | The ADR-01 ordering is written against a port the domain owns; SQLite is a detail | Application imports no SQLite/JDBC. A different store is a design intent only: none exists | [KioskArchitectureTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/KioskArchitectureTest.java), 3 rules, passed. Use-case tests use the real SQLite repository on purpose, so no fake backs this row | TESTED (direction only) |
| DIP | `kiosk-agent` | [IKioskSyncAPI](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/IKioskSyncAPI.java) ← [RestAttendanceSyncClient](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/infrastructure/sync/RestAttendanceSyncClient.java) | The sync state machine must not know HTTP; whole-request failure is one domain exception | The PENDING → SYNCED/FAILED logic is tested with a scripted lambda and no server; HTTP mapping is tested apart | [SyncPendingEventsUseCaseTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/application/SyncPendingEventsUseCaseTest.java), [HttpAdaptersTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/infrastructure/HttpAdaptersTest.java) | TESTED |
| DIP | `attendance-processor` | [IAttendanceRepository](../backend-services/attendance-processor/src/main/java/vn/edu/hcmut/tmc/attendance/domain/repository/IAttendanceRepository.java) ← [PostgresAttendanceRepository](../backend-services/attendance-processor/src/main/java/vn/edu/hcmut/tmc/attendance/infrastructure/persistence/PostgresAttendanceRepository.java) | Ingestion policy is independent of SQL; the database unique key is the concurrency guard | Ingestion rules run against an in-memory fake with no database; SQL is covered by the real-PostgreSQL test | [AttendanceArchitectureTest](../backend-services/attendance-processor/src/test/java/vn/edu/hcmut/tmc/attendance/AttendanceArchitectureTest.java) (2 rules), [AttendanceIngestionServiceTest](../backend-services/attendance-processor/src/test/java/vn/edu/hcmut/tmc/attendance/application/AttendanceIngestionServiceTest.java) passed. [AttendanceIdempotencyPostgresIntegrationTest](../backend-services/attendance-processor/src/test/java/vn/edu/hcmut/tmc/attendance/AttendanceIdempotencyPostgresIntegrationTest.java) skipped today (no Docker), last ran 2026-11-01 | TESTED (SQL part: last run 2026-11-01) |
| SRP | `attendance-processor` | [AttendanceIngestionService](../backend-services/attendance-processor/src/main/java/vn/edu/hcmut/tmc/attendance/application/service/AttendanceIngestionService.java), [IdempotencyChecker](../backend-services/attendance-processor/src/main/java/vn/edu/hcmut/tmc/attendance/domain/service/IdempotencyChecker.java), [AttendanceSyncResponseMapper](../backend-services/attendance-processor/src/main/java/vn/edu/hcmut/tmc/attendance/presentation/mapper/AttendanceSyncResponseMapper.java), [DevAttendanceController](../backend-services/attendance-processor/src/main/java/vn/edu/hcmut/tmc/attendance/presentation/controller/DevAttendanceController.java) | Transport, transaction and outcome policy, retry-vs-conflict rule, ack format and SQL change for different reasons (§2 SRP) | An ack-format change leaves the transaction and SQL alone; ingestion is tested without HTTP. `IdempotencyChecker` is thin (it delegates to `AttendanceRecord.samePayloadAs`): its value is naming the policy, do not copy the pattern for its own sake | [AttendanceIngestionServiceTest](../backend-services/attendance-processor/src/test/java/vn/edu/hcmut/tmc/attendance/application/AttendanceIngestionServiceTest.java), [AttendanceControllersWebTest](../backend-services/attendance-processor/src/test/java/vn/edu/hcmut/tmc/attendance/presentation/AttendanceControllersWebTest.java), [AttendanceDomainTest](../backend-services/attendance-processor/src/test/java/vn/edu/hcmut/tmc/attendance/domain/AttendanceDomainTest.java) passed | TESTED |
| SRP | `kiosk-agent` | [SyncPendingEventsUseCase](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/application/usecase/SyncPendingEventsUseCase.java), [RetryBackoffPolicy](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/service/RetryBackoffPolicy.java), [SyncManagerScheduler](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/application/usecase/SyncManagerScheduler.java) | The sync state machine, the wait rule and the trigger change independently | Backoff is tested as a pure rule; the state machine is tested with a fixed `Clock` | [RetryBackoffPolicyTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/domain/RetryBackoffPolicyTest.java), [SyncPendingEventsUseCaseTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/application/SyncPendingEventsUseCaseTest.java) passed | TESTED |
| OCP | `kiosk-agent` | [ITurnstileAdapter](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/ITurnstileAdapter.java) ← [EthernetTurnstileAdapter](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/infrastructure/turnstile/EthernetTurnstileAdapter.java); stable side: [QueueSyntheticEventUseCase](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/application/usecase/QueueSyntheticEventUseCase.java) | A device change must not edit the commit-then-open ordering (§2 OCP) | The use case imports no HTTP client and is exercised with lambda doubles | [QueueSyntheticEventUseCaseTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/application/QueueSyntheticEventUseCaseTest.java) passed. Port and one adapter exist; **no second production adapter, and the selection mechanism is not built**, so the OCP claim is not demonstrated | DESIGNED |
| LSP | `kiosk-agent` | [ITurnstileAdapter](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/ITurnstileAdapter.java) contract | One contract clients can rely on: `SENT` never means a physical open, failures do not throw | Only the one implementation can be checked today: three `HttpAdaptersTest` cases. `UNSUPPORTED` is undefined with no producer or consumer, and there is no contract test | [HttpAdaptersTest](../kiosk-agent/src/test/java/vn/edu/hcmut/tmc/kiosk/infrastructure/HttpAdaptersTest.java) | LIMITATION |
| ISP | `kiosk-agent` | [IKioskSyncAPI](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/IKioskSyncAPI.java), [ITurnstileAdapter](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/ITurnstileAdapter.java), [IAttendanceLocalRepository](../kiosk-agent/src/main/java/vn/edu/hcmut/tmc/kiosk/domain/port/IAttendanceLocalRepository.java) | Three ports by consumer and reason to change (central, local device, queue) | A sync change cannot ripple into the gate command; `IAttendanceLocalRepository` is wide (9 methods, three clients with disjoint subsets), kept on purpose, see §2 ISP | Source inspection 2026-11-02; no test targets this | IMPLEMENTED |

## 6. Questions a lecturer may ask

Answers point at real code and concede real gaps. A confident, honest "not yet, and here is what it would take" beats an overstated claim.

**Open/Closed**

- *Show me OCP in your code.* `ITurnstileAdapter`. Walk the five questions in §2: stable use case, port as extension point, one new class plus a composition-root selection, benefit over a direct call. Say that a second adapter and its selection are not built.
- *What do you edit to add a ZKTeco turnstile?* A new class in `infrastructure/turnstile`, its tests, and the selection line in the wiring. Not the use cases. If the SDK reports door state, that is a new capability port, not a wider one.
- *What happens without the port?* The use case would import the HTTP client, change per device, and need a running HTTP stub to test; `KioskArchitectureTest` would fail.
- *Is `switch (result.outcome())` an OCP violation?* No. The set is closed by the wire contract; adding an outcome is a contract change. Strategy objects would be four classes with no variation. Concede that the `switch` statement is not exhaustiveness-checked.

**Liskov Substitution**

- *Does `implements ITurnstileAdapter` prove LSP?* No. LSP is about the contract (§2 LSP). With one production implementation it can be argued, not demonstrated.
- *Mock turnstile versus real hardware?* The mock is an HTTP service behind the *same* adapter, so swapping it changes the peer, not the class. A real device with another protocol is another adapter and must pass the same contract. The mock cannot prove a physical open; the contract says `SENT` never claims one, so no client treats it as open.
- *May an adapter return `UNSUPPORTED`?* Only if the port defines what it means and what the caller does. Today it is undefined and nothing produces or consumes it: define it first.
- *Is the in-memory `FakeRepository` substitutable for `PostgresAttendanceRepository`?* For ingestion rules, yes. It cannot prove the atomic unique-key guarantee; that is covered only by the PostgreSQL test (8-thread race). A shared contract test would cover both.

**Other principles**

- *Why is `IAttendanceLocalRepository` one nine-method interface?* §2 ISP: disjoint client subsets, one cohesive concept, one implementation, real-SQLite tests; splitting now adds interfaces without a benefit.
- *Why not one API for kiosk sync and HR export?* Different consumers, lifecycle and contracts (per-event ack versus a monthly `Batch_UUID` batch). The HR side is planned in `reporting-service`, still a scaffold.
- *Isn't this just Spring DI?* DI wires objects. DIP is about who owns the abstraction: the port is in `domain`, the implementation in `infrastructure`, and ArchUnit fails if that is reversed.
- *The application layer imports Spring.* A stated trade-off: transaction boundaries live there; JDBC and SQLite types may not.
- *Why a separate `IdempotencyChecker` that delegates to one method?* It names the retry-versus-conflict policy and its `Verdict`. It is near the line of a class for its own sake: concede that, and do not extend the pattern.

**General**

- *Where did you choose not to apply SOLID?* `RetryBackoffPolicy`, `IdempotencyChecker` and the mapper are concrete classes; `IAttendanceLocalRepository` is not split; there is no registry or factory for turnstiles.
- *What do your abstractions cost?* A port is one interface and one contract to keep true. For `ITurnstileAdapter` the cost is paid before its OCP benefit exists, which is why the table says `DESIGNED`.

## 7. Keeping this document honest

- Change a rule here, then change the matching short rule in [../AGENTS.md](../AGENTS.md#pragmatic-solid); state a fact once.
- A new or changed port contract that another owner's module relies on is a contract change: [DECISIONS](DECISIONS.md) and the `record-decision` skill apply, not only a row here.
- Do not copy code into this file; link to it. Do not mark a principle `IMPLEMENTED` or `TESTED` on the strength of a name.
