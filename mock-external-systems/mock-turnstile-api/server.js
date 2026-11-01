'use strict';
/*
 * mock-turnstile-api  -  DEV-ONLY stand-in for the factory-LAN turnstile controller.   Owner: Khanh
 *
 * What it proves:  the kiosk can reach a turnstile endpoint over real HTTP, and keeps working when central is off.
 * What it does NOT prove:  that a physical gate opened. It never reports an open gate: `physicalOpenConfirmed` is
 * always false, and its latency says nothing about real hardware (docs/ARCHITECTURE.md "Reliability trade-off").
 *
 * [DRAFT] contract - not in docs/API-CONTRACTS.md, so owned and finalised by Khanh:
 *   GET  /health
 *   POST /gates/:gateId/open            body {"kioskId": "..."}  -> 200 COMMAND_ACCEPTED   (or the injected failure)
 *   GET  /gates/:gateId/status
 *   GET  /gates/:gateId/commands        recent commands received (assertion aid for tests)
 *   GET  /mock/control/failure          current failure injection
 *   PUT  /mock/control/failure          body {"status": 503}  to make /open fail with that status, {"status": 0} to heal
 * No authentication: it is a mock on the factory LAN of the Compose network.
 */
const http = require('node:http');
const { randomUUID } = require('node:crypto');

const MAX_BODY_BYTES = 16 * 1024;
const MAX_COMMANDS_KEPT = 200;

function createServer(options = {}) {
  const state = {
    failureStatus: Number(options.failureStatus || 0),
    commands: [],
    countsByGate: new Map(),
  };

  function send(res, status, body) {
    const payload = JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) });
    res.end(payload);
  }

  function readJson(req) {
    return new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          reject(Object.assign(new Error('body too large'), { status: 413 }));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => {
        if (chunks.length === 0) return resolve({});
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
        } catch {
          reject(Object.assign(new Error('body is not valid JSON'), { status: 400 }));
        }
      });
      req.on('error', reject);
    });
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname.replace(/\/+$/, '') || '/';
    try {
      if (req.method === 'GET' && path === '/health') {
        return send(res, 200, { status: 'UP', service: 'mock-turnstile-api', mock: true });
      }

      let match = path.match(/^\/gates\/([^/]+)\/open$/);
      if (req.method === 'POST' && match) {
        const gateId = decodeURIComponent(match[1]);
        const body = await readJson(req);
        if (state.failureStatus) {
          return send(res, state.failureStatus, { code: 'TURNSTILE_UNAVAILABLE', message: 'failure injected by /mock/control/failure' });
        }
        const command = {
          commandId: randomUUID(),
          gateId,
          kioskId: typeof body.kioskId === 'string' ? body.kioskId : null,
          receivedAt: new Date().toISOString(),
        };
        state.commands.push(command);
        if (state.commands.length > MAX_COMMANDS_KEPT) state.commands.shift();
        state.countsByGate.set(gateId, (state.countsByGate.get(gateId) || 0) + 1);
        return send(res, 200, {
          result: 'COMMAND_ACCEPTED',
          commandId: command.commandId,
          gateId,
          mock: true,
          physicalOpenConfirmed: false,
        });
      }

      match = path.match(/^\/gates\/([^/]+)\/status$/);
      if (req.method === 'GET' && match) {
        const gateId = decodeURIComponent(match[1]);
        const last = [...state.commands].reverse().find((c) => c.gateId === gateId);
        return send(res, 200, {
          gateId,
          mock: true,
          state: 'UNKNOWN',
          note: 'a mock cannot know whether a physical gate is open',
          commandsReceived: state.countsByGate.get(gateId) || 0,
          lastCommandAt: last ? last.receivedAt : null,
        });
      }

      match = path.match(/^\/gates\/([^/]+)\/commands$/);
      if (req.method === 'GET' && match) {
        const gateId = decodeURIComponent(match[1]);
        return send(res, 200, { gateId, commands: state.commands.filter((c) => c.gateId === gateId) });
      }

      if (path === '/mock/control/failure') {
        if (req.method === 'GET') return send(res, 200, { status: state.failureStatus });
        if (req.method === 'PUT') {
          const body = await readJson(req);
          const status = Number(body.status);
          if (!Number.isInteger(status) || (status !== 0 && (status < 400 || status > 599))) {
            return send(res, 400, { code: 'INVALID_REQUEST', message: 'status must be 0 or an HTTP error code 400-599' });
          }
          state.failureStatus = status;
          return send(res, 200, { status: state.failureStatus });
        }
      }

      return send(res, 404, { code: 'NOT_FOUND', message: `${req.method} ${path} is not a mock turnstile route` });
    } catch (err) {
      const status = err.status || 500;
      return send(res, status, { code: status === 500 ? 'INTERNAL_ERROR' : 'INVALID_REQUEST', message: status === 500 ? 'unexpected error' : err.message });
    }
  });

  return { server, state };
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8090);
  const { server } = createServer({ failureStatus: process.env.SIMULATE_TURNSTILE_DOWN });
  server.listen(port, '0.0.0.0', () => {
    console.log(`mock-turnstile-api (DEV-ONLY mock) listening on :${port}`);
  });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}

module.exports = { createServer };
