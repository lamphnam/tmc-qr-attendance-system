---
name: verify-all
description: Run the whole TMC verification (Maven on JDK 21, frontend, mocks, docs check, compose config, Docker stack, smoke, integration) and report the result honestly with exit codes and skipped counts. Use before saying work is done, before a PR, or when asked to verify, run all tests, "kiểm tra toàn bộ", "chạy test".
---

# verify-all

One command runs everything and prints a table; do not re-assemble the steps by hand.

```bash
node scripts/verify.mjs                  # full: java, frontend, mocks, docs, compose-config, stack-up, smoke, integration
node scripts/verify.mjs --fast           # no Docker stack: java, frontend, mocks, docs, compose-config
node scripts/verify.mjs --only docs      # any comma-separated subset of the stage names
node scripts/verify.mjs --no-integration # skip the test that stops/restarts containers
node scripts/verify.mjs --down           # stop the stack afterwards (volumes are kept)
```

## Pick the scope from what changed
| You changed | Run |
|---|---|
| only `.md` files | `--only docs` |
| Java / frontend / mock code, no runtime wiring | `--fast` |
| `docker-compose.yml`, `nginx.conf`, a Dockerfile, `init-db/`, anything the kiosk↔central slice uses, or you are about to say "done" | full run |

## Before running
- Docker stages and the Testcontainers test need Docker Desktop running. If `docker info` fails, tell the user; do not start Docker or free ports on your own initiative.
- The script finds a JDK 21 by itself (`JDK21_HOME` overrides). Never "fix" a Maven failure by lowering `java.version`.
- Never stop or remove containers that do not belong to this project (the owner's machine runs an unrelated `pmc-ticket-postgres` that holds host port 5432).

## Reading the result
`PASS` ran and passed · `FAIL` · `SKIPPED` did not run · `WARN` Maven passed **but some tests were skipped** (Testcontainers without Docker) — not green.
Report the table as it is, with counts. Say "all tests passed" only if no stage is `FAIL`/`WARN`/`SKIPPED` and the run was not partial (`--only`). A skipped stage is never a pass.

## When a stage fails
Read `.verify-logs/<stage>.log` (git-ignored) and fix the cause; do not just re-run. Usual causes: a host port busy (change `*_HOST_PORT` in `.env`); a service `unhealthy` (`docker compose logs --tail=80 <service>`); stale volume after a schema change (`docker compose down -v` destroys dev data — ask first); `integration` needs `stack-up` first.

Details of each command and what it proves: `README.md` §4 and `docs/STATUS.md` §3.
