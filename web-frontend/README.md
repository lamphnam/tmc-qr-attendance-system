# web-frontend — DEV_SLICE

Owner: **Khiêm** · React 19 + TypeScript + Vite 7 · nginx on port 80 (host 3000, or through the gateway on 8080). Status context: [docs/STATUS.md](../docs/STATUS.md) · tasks: [docs/TODO.md](../docs/TODO.md#4-khiêm--web-frontend).

Real: the **Status** page (live `/services/<name>/health` through the gateway, every 10 s) and the **Dev slice** page (drives the dev-only kiosk fixtures). Placeholders labelled **Not implemented**: `/employee`, `/kiosk`, `/guard`, `/hr`, `/admin`. There is no login. The employee page must only render a payload returned by `GET /api/v1/qr/generate` (the shared secret never reaches the browser).

## `/demo` — main flow on one page (M2)

A standalone page (outside the scaffold shell) with three columns so the whole flow is visible at once:

1. **Employee** — pick EMP001–EMP004, show the current code as a **QR image**, count down to the next code and rotate, revoke or activate. After revoke, refresh the kiosk whitelist and use the short-lived prior QR to check the kiosk denial.
2. **Kiosk BD-K01** — scan the current code into gate BD-G01/BD-G02, authorization result shown separately from the gate-command status, offline switch, sync now, whitelist sync, synthetic event, SQLite queue.
3. **Central** — which queued ids are in PostgreSQL, and a resend of SYNCED rows that must answer `ALREADY_EXISTS`.

| Implemented backend routes (verification in STATUS) | Remaining integration work |
|---|---|
| User/QR lifecycle, H1 dev Central list, H2 dev whitelist refresh, local QR scan, offline switch, queue and resend ([API-CONTRACTS](../docs/API-CONTRACTS.md)) | Dev Main Flow and browser revoke/activate evidence in [STATUS §23](../docs/STATUS.md#23-m2-follow-up-and-compose-acceptance-2026-11-18). Owner review and the production auth contract remain open. |

It relies on `/api/dev/**`, so it only works with the `dev` profile (Compose default) and uses synthetic data.

```bash
npm ci
npm run dev          # http://localhost:5173, proxies /api and /services to the gateway (docker compose up -d first)
```
`GATEWAY_URL=http://localhost:9090 npm run dev` if you changed `GATEWAY_HOST_PORT`. Typecheck/test/build commands: root [README §4](../README.md#4-test).

Code map: `src/catalog.ts` (declared service/area catalogue — keep in sync with STATUS), `src/health.ts` (pure, unit-tested health interpretation), `src/pages/*`, `src/api/*` (never-throwing fetch client, one wrapper per route), `src/demo/*` (pure view logic for `/demo`, polling hook), `src/components/demo/*` (the three columns), `src/styles/demo.css` (design tokens and components, scoped to `.tmc-demo` so the other pages are unchanged).
