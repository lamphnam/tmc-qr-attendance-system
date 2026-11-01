---
name: update-status
description: Keep module and requirement status consistent across docs/STATUS.md, docs/TRACEABILITY.md, web-frontend/src/catalog.ts and docs/TODO.md when a module is implemented, a 501 stub becomes real, or a feature is verified. Use whenever a status label changes, "đổi trạng thái", "module xong", "mark as implemented".
---

# update-status

A status label may only go **up** with evidence (a command that was run and its real result). No evidence → leave it, or mark `NOT_TESTED`. Labels and meanings: `docs/STATUS.md` ("Status labels"). The same fact lives in three places that must change together:

1. **`docs/STATUS.md`** — the module's row in the matrix (status, "what really works", "not there"). If you ran new commands, add them to the commands table with exit codes. Update the dev-only inventory (§5) if you added or removed a dev fixture.
2. **`docs/TRACEABILITY.md`** — every FR/NFR/ADR row the change touches: `Status` and `Actual evidence` as `class → test name`. A `DEV_SLICE` row never satisfies the requirement; say what is still missing. Update the class map status too.
3. **`web-frontend/src/catalog.ts`** — `status` (`READY | DEV_SLICE | SCAFFOLD | MOCK`) and `note` for the service. A new service also needs a `healthPath` that exists as a `location =` in `nginx-gateway/nginx.conf` (a render test fails otherwise).

Then:
- `docs/TODO.md`: remove or reword the tasks that are now done; do not leave finished work listed.
- If a design choice was made on the way, use the `record-decision` skill.
- `docs/TESTING.md` section C boxes are ticked only when the matching test exists and passed.
- Verify: `node scripts/verify.mjs --only docs,frontend` (and the stages that exercise your change — see skill `verify-all`).

Never write "implemented" for something reachable only through `/api/dev/**`, never raise a label because the code compiles, and keep `NOT_MEASURED` for NFR-1/NFR-7 until a real measurement exists (claims policy: `docs/PROJECT.md` §5).
