# web-frontend — SCAFFOLD

Owner: **Khiêm** · React 19 + TypeScript + Vite 7 · nginx on port 80 (host 3000, or through the gateway on 8080). Status context: [docs/STATUS.md](../docs/STATUS.md) · tasks: [docs/TODO.md](../docs/TODO.md#4-khiêm--web-frontend).

Real: the **Status** page (live `/services/<name>/health` through the gateway, every 10 s) and the **Dev slice** page (drives the dev-only kiosk fixtures). Placeholders labelled **Not implemented**: `/employee`, `/kiosk`, `/guard`, `/hr`, `/admin`. There is no login, no QR rendering and no fake data. The employee page must only render a payload returned by `GET /api/v1/qr/generate` (the shared secret never reaches the browser).

```bash
npm ci
npm run dev          # http://localhost:5173, proxies /api and /services to the gateway (docker compose up -d first)
```
`GATEWAY_URL=http://localhost:9090 npm run dev` if you changed `GATEWAY_HOST_PORT`. Typecheck/test/build commands: root [README §4](../README.md#4-test).

Code map: `src/catalog.ts` (declared service/area catalogue — keep in sync with STATUS), `src/health.ts` (pure, unit-tested health interpretation), `src/pages/*`.
