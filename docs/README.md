# docs — index

Handoff documentation for **TMC QR Attendance & Access Control** (CO3017 Software Architecture, Assignment M2), plus the status of the codebase that was bootstrapped from it. The codebase exists and builds; what is real and what is not: [STATUS](STATUS.md). Rules for agents and contributors: [../AGENTS.md](../AGENTS.md). Quick start and ports: [../README.md](../README.md).

## What to read for what

| If you are about to… | Read |
|---|---|
| change anything | [STATUS](STATUS.md) (is that module real?) → the doc for your task below |
| understand the project, FR/NFR, what is official vs draft | [PROJECT](PROJECT.md) |
| touch structure, layering, ADRs, build/Compose rules | [ARCHITECTURE](ARCHITECTURE.md) |
| add or change an endpoint | [API-CONTRACTS](API-CONTRACTS.md) (DRAFT) + [OWNERSHIP](OWNERSHIP.md) interface gates |
| add or change a table | [DATA-MODEL](DATA-MODEL.md) (DRAFT) + `init-db/schema.sql` |
| implement a scan / sync / export / manual-open flow | [RUNTIME-FLOWS](RUNTIME-FLOWS.md) |
| write or run tests, check "done" | [TESTING](TESTING.md) |
| pick up work | [TODO](TODO.md) |
| open a PR, know who owns a module | [OWNERSHIP](OWNERSHIP.md) |
| face an open question or a limitation | [DECISIONS](DECISIONS.md) |
| link code to requirements, find a planned class name | [TRACEABILITY](TRACEABILITY.md) |
| add a port/abstraction, split or merge a class, prepare the SOLID defence | [SOLID-GUIDELINES](SOLID-GUIDELINES.md) |

## Document map — each fact lives in exactly one place

| File | Single source for |
|---|---|
| [PROJECT](PROJECT.md) | provenance labels and priority, scope, actors, FR/NFR, M2 deliverables, **claims policy** |
| [ARCHITECTURE](ARCHITECTURE.md) | style, runtime boundaries, layering rule, ADR-01..03, deployment, stack, per-service layout, Compose/build rules |
| [OWNERSHIP](OWNERSHIP.md) | owners, integration dependencies, interface gates, PR workflow and checklist |
| [API-CONTRACTS](API-CONTRACTS.md) | endpoint contracts and wire shapes — **DRAFT** |
| [DATA-MODEL](DATA-MODEL.md) | table proposals and idempotency rules — **DRAFT** (executable schema: `init-db/schema.sql`) |
| [RUNTIME-FLOWS](RUNTIME-FLOWS.md) | sequences A–D and the error matrix |
| [TESTING](TESTING.md) | test plan T01–T06, demo scenes, definition of done |
| [DECISIONS](DECISIONS.md) | decision register D01–D16, bootstrap decisions B01–B20, **known limitations** |
| [TRACEABILITY](TRACEABILITY.md) | FR/NFR/ADR ↔ class ↔ test with status, and the Java class map |
| [SOLID-GUIDELINES](SOLID-GUIDELINES.md) | Pragmatic SOLID: when to apply it, per-principle guidance, anti-patterns, defence questions, the SOLID evidence table |
| [STATUS](STATUS.md) | module status labels, per-module status, commands run and results, dev-only inventory |
| [TODO](TODO.md) | remaining work per member |

Editing rules: state a fact once and link to it elsewhere; never promote a [DRAFT] silently (record the decision in DECISIONS); when a module's real status changes update STATUS, TRACEABILITY and `web-frontend/src/catalog.ts` together. Language: reference docs are English, team-operational docs (README, AGENTS, STATUS, TODO) are Vietnamese; code identifiers and comments are English.

## Old names

Earlier handoff packages and prompts use the numbered names. They were merged on 2026-11-01:

| Old | Now |
|---|---|
| 00 SOURCE-OF-TRUTH, 01 PROJECT-CONTEXT, 02 M2-DELIVERABLES | [PROJECT](PROJECT.md) |
| 03 ARCHITECTURE, 04 REPOSITORY-STRUCTURE | [ARCHITECTURE](ARCHITECTURE.md) |
| 05 OWNERSHIP-AND-COLLAB, 15 TEAM-INTEGRATION-GATES | [OWNERSHIP](OWNERSHIP.md) |
| 06 API-CONTRACTS-DRAFT / 07 DATA-MODEL-DRAFT / 08 RUNTIME-FLOWS | [API-CONTRACTS](API-CONTRACTS.md) / [DATA-MODEL](DATA-MODEL.md) / [RUNTIME-FLOWS](RUNTIME-FLOWS.md) (section numbers unchanged) |
| 09 BOOTSTRAP-PLAN, 10 TESTING-AND-DEMO, 12 ACCEPTANCE-CHECKLIST | [TESTING](TESTING.md) |
| 11 RISKS-DECISIONS | [DECISIONS](DECISIONS.md) |
| 13 TRACEABILITY-STARTER, 14 JAVA-CLASS-MAP | [TRACEABILITY](TRACEABILITY.md) |
| AGENT-RULES | [../AGENTS.md](../AGENTS.md) |
| BOOTSTRAP-STATUS / TEAM-TODO | [STATUS](STATUS.md) / [TODO](TODO.md) |
