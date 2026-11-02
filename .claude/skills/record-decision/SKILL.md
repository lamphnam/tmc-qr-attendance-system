---
name: record-decision
description: Record a design decision, contract or schema change, or resolve an open question in docs/DECISIONS.md with correct numbering and status, and update the DRAFT docs it touches. Use when choosing between alternatives, changing an API or table, answering one of D01-D16, "ghi quyết định", "chốt contract".
---

# record-decision

`docs/DECISIONS.md` is the only place decisions are logged. Do not bury a decision in a PR description, a code comment or a status file.

1. **Does it answer an existing question?** Find the row in the *Decision register* (D01–D16). Update its **Status**: `OPEN` → `PROPOSED` (+ the B-id) when someone chose but the owner has not signed off; `RESOLVED` only after the owners named in "Confirm with" agreed.
2. **Add the decision** to the *Bootstrap decisions* table with the **next free `Bnn`** (check the last row; never reuse or renumber). Columns: `ID | Resolves (D-id) | Decision | Why | Reversal / effect | Confirm with`. Fill every one. For a team decision made after the bootstrap, start the Decision text with the date and who decided, e.g. `2026-11-12 Khoa + Khánh: …`.
3. **Never edit a past decision away.** To change one, add a new row that says it supersedes `Bnn` and update the old row's wording to point forward.
4. **Propagate** to what the decision changes: `docs/API-CONTRACTS.md` or `docs/DATA-MODEL.md` (and `init-db/schema.sql`, the DTOs, `RestAttendanceSyncClient`... whatever implements it). Drop a DRAFT label only when both owners of the boundary agreed, and say so in the PR.
   A port contract another owner's module relies on (e.g. `IKioskSyncAPI`, `ITurnstileAdapter`) counts as a contract: record it here, then update its row in `docs/SOLID-GUIDELINES.md` §5 if one exists.
5. **If it changes the M1 baseline** (4 services + kiosk, shared PostgreSQL, SQLite WAL, direct REST, no broker): stop. That needs a documented ADR with group approval, not a log entry.
6. New limitation or claim restriction? Add it to *Known limitations* in the same file (the single canonical list), not elsewhere.
7. Update `docs/TODO.md` if it unblocks or creates work, then run `node scripts/check-docs.mjs`.

Anchors other docs link to: `#decision-register`, `#bootstrap-decisions`, `#known-limitations` — keep those headings.
