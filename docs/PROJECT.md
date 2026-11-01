# PROJECT — brief, requirements, deliverables

Single source for: provenance labels, scope, actors, FR/NFR, M2 deliverables, claims policy.
Architecture → [ARCHITECTURE](ARCHITECTURE.md) · owners → [OWNERSHIP](OWNERSHIP.md) · what exists today → [STATUS](STATUS.md).

## 1. Provenance labels and priority

Every important statement in `docs/` carries a label. **Never promote a [DRAFT] to a requirement.**

| Label | Meaning |
|---|---|
| **[OFFICIAL-M2]** | Direct requirement from `SA_CC_hk261_Assignment_M2.pdf` |
| **[BASELINE-M1]** | Choice / FR / NFR / ADR in the team's M1 report `main (17).pdf` |
| **[TEAM-PLAN]** | Schedule, assignment, checklist from the team's Google Doc / Sheet and group announcements |
| **[DRAFT]** | Detail added so a codebase could be built; **not approved** by the team as final API/schema |
| **[ENGINEERING-GUARDRAIL]** | Advice to avoid fake builds, vulnerabilities or false claims; not a quote of the assignment |

Priority when sources disagree: 1. official M2 → 2. M1 architecture and ADRs → 3. team scope/assignments → 4. drafts in this docs set.
**Exception:** if a draft conflicts technically with M1, do not implement the draft; record the conflict in [DECISIONS](DECISIONS.md). A sample Compose snippet is not an API contract. Do not silently change the M1 architecture.

## 2. Business context [BASELINE-M1][OFFICIAL-M2]

TMC Manufacturing has two factories: Bình Dương (~800 employees) and Long An (~600). Employees show a personal QR (app/web/email) and scan it at the gate. The kiosk authenticates, commands the turnstile and stores every entry/exit as an attendance event; central aggregates and sends attendance data to HR/Payroll through an API for reconciliation **before 12:00 on the 1st of the next month**. The system does not calculate payroll.

**In scope:** HR stays the source of truth for employees (the system syncs the list, issues/revokes QR, does not manage HR records); gate registration, status monitoring, manual open with a reason; QR scan/validate with both accepted **and rejected** attempts recorded; offline kiosk authentication with local store and later sync; personal history, shift/team/factory/month reports, dashboards, batch export to HR/Payroll; authentication, six-role RBAC, configuration, audit log; web portal and kiosk app. A native mobile app is conceptual only and **not required** in the bootstrap [DRAFT].

**Out of scope:** visitors; payroll calculation (OT/allowance/deduction); CRUD of master HR records and labour contracts; hardware-driver level turnstile control (mock or adapter API only).

**Actors:** Employee (get QR, scan, view history, request adjustment) · Production Supervisor (team/factory reports) · HR Staff (view/export attendance, revoke QR) · Security Guard (exceptions, manual open with reason) · System Administrator (users/roles, configuration, audit/log) · Executive Management (aggregate dashboard).

## 3. Requirements

### Functional [BASELINE-M1]

| ID | Requirement | Lead module |
|---|---|---|
| FR-1.1 | Sync employees from HR/Payroll | user-qr-service |
| FR-1.2 | Issue / activate QR | user-qr-service |
| FR-1.3 | Show dynamic QR in app/web | user-qr-service + web-frontend |
| FR-1.4 | Revoke / lock QR | user-qr-service |
| FR-2.1 | Register and configure gate stations | gate-station-service |
| FR-2.2 | Monitor status; manual open with recorded reason | gate-station-service + kiosk |
| FR-3.1 | Scan QR, authenticate, record, open gate | kiosk-agent + attendance-processor |
| FR-3.2 | Personal in/out history | reporting + frontend |
| FR-4.1 | Reports by shift/team/factory | reporting-service |
| FR-4.2 | Factory-wide dashboard | reporting-service |
| FR-5.1 | Account management and RBAC | user-qr-service |
| FR-5.2 | System configuration | whichever service fits |

### Non-functional [BASELINE-M1]

| ID | Criterion | How it must be measured |
|---|---|---|
| NFR-1 | QR scan → kiosk response / gate command **≤ 2 s** at **800 scans / 30 min / factory** | p95/p99 and pass ratio; separate load from stress |
| NFR-2 | No lost scan records in normal operation | local durable write, retry, recovery; compare counts + IDs |
| NFR-3 | Idempotent export, no duplicate writes | resend same Batch_UUID / Attendance_ID |
| NFR-4 | Encrypt personal data in transit/at rest; MFA for Admin/HR | security audit; a demo must state what is missing |
| NFR-5 | Add a factory/gate through configuration | factoryId / gateId / kiosk config model |
| NFR-6 | Export job 01:00 on day 1, retry until 06:00, alert before the 12:00 deadline | fake clock / manual trigger; retry tests |
| NFR-7 | Factory report **≤ 30 s** | measured query/report API |
| NFR-8 | RBAC for 6 roles; append-only audit with SHA-256 tamper evidence | role matrix, integrity test |

**Two incidents the design must resolve [OFFICIAL-M2]:** (i) connectivity loss while processing attendance; (ii) a leaked static QR enabling fraud. Hence Offline-First + Dynamic QR.

A technology choice alone does not satisfy an NFR: each needs a concrete assertion/metric/test (see [TESTING](TESTING.md)); until then it is `NOT_IMPLEMENTED` / `NOT_MEASURED`.

## 4. M2 deliverables

### Official tasks [OFFICIAL-M2]
**Task 1 — Software Architecture Design:** context FR/NFR and architecture characteristics; compare and choose styles; logical/physical architecture; the **module**, **component-and-connector** and **allocation** views; architecture decisions; design principles; how SOLID was applied; a **Reflection Report** on SOLID.
**Task 2 — Code Implementation:** at least **one main module** implemented per the design and SOLID.
**Submission:** detailed report with the **GitHub URL**, on LMS.

### Team plan [TEAM-PLAN]
- **Internal deadline: end of 2026-11-09** (group announcement, not from the PDF).
- Documentation before implementation and testing.
- Module views: package/directory map, class diagram, ERD, Dependency Structure Matrix. C&C views: runtime connectors; sequences for QR online/offline, batch resync, monthly HR export. Allocation views: Compose mapping and Work Assignment (person ↔ module).
- Testing & Evaluation: 800/30 min load with p95/p99; offline 20 PENDING → SYNCED without duplicates; HR retry and idempotency ([TESTING](TESTING.md)).
- SOLID reflection must cite real code and trade-offs, not definitions.

### Coordination order [ENGINEERING-GUARDRAIL]
1. Agree FR/NFR, ADRs and minimal API contracts ([OWNERSHIP](OWNERSHIP.md) interface gates).
2. Nam pushes the buildable scaffold, PR review. *(Done — [STATUS](STATUS.md).)*
3. Members write their documentation in parallel with their module; the report must not contradict the code.
4. Integrate the happy path QR → kiosk → central → dashboard first; then HR export / RBAC / audit.
5. Run tests, record measurements, check [TRACEABILITY](TRACEABILITY.md), assemble the PDF.

Do not wait for a perfect report before building; agree contracts first and update diagrams close to the real code.

### Report structure [DRAFT]
1 Context and FR/NFR changes since M1 · 2 Architecture summary, trade-offs, ADR status · 3 Module View · 4 C&C View · 5 Allocation View · 6 SOLID in real code · 7 Testing & Evaluation (datasets, logs, p95/p99, failures) · 8 SOLID Reflection · 9 Conclusion, limitations, GitHub URL, reproducibility.

## 5. Claims policy (canonical — other docs link here)

Never state these as results unless a real test proves them, with the failure envelope: **"< 2 s"**, **"zero data loss"**, **"audit immutable / SHA-256"**, **"MFA implemented"**, **"NFR-4 fully compliant"**, **"all tests passed"**.
Label every claim in the report `DESIGNED`, `IMPLEMENTED`, `TESTED` or `LIMITATION`. System limitations to quote honestly: [DECISIONS — Known limitations](DECISIONS.md#known-limitations). Module status vocabulary (`READY`, `DEV_SLICE`, ...): [STATUS](STATUS.md#status-labels).

## 6. Not confirmed (as of 2026-11-01)

- Spring Boot version is not mandated; the bootstrap chose 3.5.16 (DECISIONS B02).
- Wire schemas, API-key/auth, HTTP statuses, migration strategy, CI and DB credentials are **DRAFT** ([API-CONTRACTS](API-CONTRACTS.md), [DATA-MODEL](DATA-MODEL.md)); the bootstrap's proposals are DECISIONS B01, B04–B07, B14.
- No measured performance (NFR-1, NFR-7) and no end-to-end demo evidence yet — see [STATUS](STATUS.md).
- No GitHub repository URL was filled in the `Source Github - NAM` tab when these docs were written.
- NFR-2 "no data loss" must be presented with its failure envelope (a kiosk disk failing before sync can still lose events — M1 says so too).

## 7. Sources

M1 report sections: 2.5 FR, 2.6 NFR, 4 styles, 5.3–5.5 container/component/layers, 6.1–6.3 deployment and integrations, 7.1–7.3 ADRs, 8.1 SOLID, 9.2 M2 plan. M2 official: pages 2–3 (scope, issues, Task 1, Task 2). Google Doc tabs: `Tóm tắt M1`, `PHÂN CÔNG`, `Source Github - NAM`, `Checklist DEMO`, `NAM`. Sheet `SA_BTL_M2`, tab `Trang tính1`, assignment rows 4–8.
The PDFs are not packaged in the repo. If the Drive sources changed after these docs were written, reconcile before changing architecture or API.

- Drive folder: https://drive.google.com/drive/folders/1_YbLR6OYusUgclxqKalkzL0HEoxsD1G5
- Google Doc "Software Architect M2": https://docs.google.com/document/d/1SC74pja_WIQFVhsD2vcdo-PMNv6k95TwtUn20lH-s-g/edit?usp=drivesdk
- Sheet "SA_BTL_M2": https://docs.google.com/spreadsheets/d/1v1V84HVTn6JNo1kYxsaR_1W1tYkdlKbgrgMTLWbZiX4/edit?usp=drivesdk
- File: https://drive.google.com/file/d/1jb7F8lwxO_yjxS0PpnajdwZEN-TmJyaM/view?usp=drivesdk
