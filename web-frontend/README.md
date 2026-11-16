# web-frontend — DEV_SLICE

Owner: **Khiêm** · React 19 + TypeScript + Vite 7 · nginx on port 80 (host 3000, or through the gateway on 8080). Status context: [docs/STATUS.md](../docs/STATUS.md) · tasks: [docs/TODO.md](../docs/TODO.md#4-khiêm--web-frontend).

Real: the **Status** page (live `/services/<name>/health` through the gateway, every 10 s) and the **Dev slice** page (drives the dev-only kiosk fixtures). Placeholders labelled **Not implemented**: `/employee`, `/kiosk`, `/guard`, `/hr`, `/admin`. There is no login. The employee page must only render a payload returned by `GET /api/v1/qr/generate` (the shared secret never reaches the browser).

## `/demo` — main flow on one page (M2)

A standalone page (outside the scaffold shell) with three columns so the whole flow is visible at once:

1. **Employee** — pick EMP001–EMP004, show the current code as a **QR image** (the TOTP digits are never printed), countdown to the next code, rotate secret, revoke QR.
2. **Kiosk BD-K01** — scan the current code into gate BD-G01/BD-G02, authorization result shown separately from the gate-command status, offline switch, sync now, whitelist sync, synthetic event, SQLite queue.
3. **Central** — which queued ids are in PostgreSQL, and a resend of SYNCED rows that must answer `ALREADY_EXISTS`.

| Works against the real stack today | Waits for an owner (page shows the real 404/405/501) |
|---|---|
| offline switch, sync, queue, synthetic event (`/api/dev/kiosk/*`), per-id lookup and resend (`/api/dev/attendance/*`) | QR, rotate, revoke, central list, whitelist sync: proposals in [API-CONTRACTS §8](../docs/API-CONTRACTS.md#8-demo-main-flow-routes-proposal-khiêm--khoa--khôi--khánh-not-agreed); `POST /api/local/scan` answers 501 |

It relies on `/api/dev/**`, so it only works with the `dev` profile (Compose default) and uses synthetic data.

```bash
npm ci
npm run dev          # http://localhost:5173, proxies /api and /services to the gateway (docker compose up -d first)
```
`GATEWAY_URL=http://localhost:9090 npm run dev` if you changed `GATEWAY_HOST_PORT`. Typecheck/test/build commands: root [README §4](../README.md#4-test).

Code map: `src/catalog.ts` (declared service/area catalogue — keep in sync with STATUS), `src/health.ts` (pure, unit-tested health interpretation), `src/pages/*`, `src/api/*` (never-throwing fetch client, one wrapper per route), `src/demo/*` (pure view logic for `/demo`, polling hook), `src/components/demo/*` (the three columns), `src/styles/demo.css` (design tokens and components, scoped to `.tmc-demo` so the other pages are unchanged).
