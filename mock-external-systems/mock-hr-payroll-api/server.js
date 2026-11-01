'use strict';
/*
 * mock-hr-payroll-api  -  DEV-ONLY stand-in for the external HR/Payroll system.   Owner: Nam
 *
 * It is a receiver with the semantics ADR-03 demands (idempotent upsert) plus failure injection, so the reporting
 * service can be tested against retries. It is NOT a model of any real HR product.
 *
 * [DRAFT] contract (docs/API-CONTRACTS.md section 7 - field names are Nam's to finalise with reporting-service):
 *   GET  /health
 *   GET  /mock/hr/employees                         synthetic employee master data (FR-1.1 source for user-qr-service)
 *   POST /mock/hr/attendance-batches                {"batchUuid","period"?, "attendanceIds":[...] | "records":[{attendanceId,...}]}
 *   GET  /mock/hr/attendance-batches/:batchUuid
 *   GET  /mock/hr/attendance-records/count          {"records": n, "batches": m}   (assertion aid)
 *   GET|PUT /mock/hr/control/failure                {"status": 500|503|...|0}      status 0 = healthy
 *   POST /mock/hr/control/reset                     wipe received data (dev only)
 *
 * Idempotency:  records are upserted by attendanceId (a repeated id is counted as duplicate, never stored twice) and a
 * repeated batchUuid is reported as duplicateBatch.  While failure injection is on, NOTHING is stored.
 * Any 5xx is a retryable error by contract (D09: do not depend on one literal code).
 *
 * State survives a container restart when HR_DATA_FILE points at a writable volume; otherwise it is memory only.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const MAX_BODY_BYTES = 5 * 1024 * 1024;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Synthetic, mirrors init-db/seed.sql. Fictional people.
const EMPLOYEES = [
  { employeeId: 'EMP001', fullName: 'Demo Employee One', department: 'Assembly', factoryId: 'BD', status: 'ACTIVE' },
  { employeeId: 'EMP002', fullName: 'Demo Employee Two', department: 'Packaging', factoryId: 'BD', status: 'ACTIVE' },
  { employeeId: 'EMP003', fullName: 'Demo Employee Three', department: 'Assembly', factoryId: 'LA', status: 'ACTIVE' },
  { employeeId: 'EMP004', fullName: 'Demo Revoked Person', department: 'Packaging', factoryId: 'BD', status: 'INACTIVE' },
];

function loadState(file) {
  const empty = { records: {}, batches: {} };
  if (!file) return empty;
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    return { records: parsed.records || {}, batches: parsed.batches || {} };
  } catch {
    return empty;
  }
}

function createServer(options = {}) {
  const dataFile = options.dataFile || null;
  const state = { failureStatus: Number(options.failureStatus || 0), data: loadState(dataFile), persisted: Boolean(dataFile) };

  function persist() {
    if (!dataFile) return;
    try {
      fs.mkdirSync(path.dirname(dataFile), { recursive: true });
      const tmp = `${dataFile}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(state.data));
      fs.renameSync(tmp, dataFile); // atomic replace: a crash never leaves a half-written state file
    } catch (err) {
      state.persisted = false; // keep serving from memory; report it instead of crashing the mock
      console.error(`mock-hr: cannot persist state to ${dataFile}: ${err.code || err.message}`);
    }
  }

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

  function receiveBatch(body) {
    if (typeof body.batchUuid !== 'string' || !UUID_RE.test(body.batchUuid)) {
      return { status: 400, body: { code: 'INVALID_REQUEST', message: 'batchUuid must be a UUID' } };
    }
    let records;
    if (Array.isArray(body.records)) records = body.records;
    else if (Array.isArray(body.attendanceIds)) records = body.attendanceIds.map((attendanceId) => ({ attendanceId }));
    else return { status: 400, body: { code: 'INVALID_REQUEST', message: 'send "attendanceIds" (array) or "records" (array of objects)' } };

    if (!records.every((r) => r && typeof r.attendanceId === 'string' && UUID_RE.test(r.attendanceId))) {
      return { status: 400, body: { code: 'INVALID_REQUEST', message: 'every attendanceId must be a UUID' } };
    }

    const now = new Date().toISOString();
    const duplicateBatch = Object.prototype.hasOwnProperty.call(state.data.batches, body.batchUuid);
    let inserted = 0;
    let duplicatesIgnored = 0;
    for (const record of records) {
      if (Object.prototype.hasOwnProperty.call(state.data.records, record.attendanceId)) {
        duplicatesIgnored += 1;
      } else {
        state.data.records[record.attendanceId] = { firstBatchUuid: body.batchUuid, receivedAt: now };
        inserted += 1;
      }
    }
    const previous = state.data.batches[body.batchUuid];
    state.data.batches[body.batchUuid] = {
      period: body.period || (previous && previous.period) || null,
      receivedAt: previous ? previous.receivedAt : now,
      lastReceivedAt: now,
      timesReceived: (previous ? previous.timesReceived : 0) + 1,
      itemCount: records.length,
    };
    persist();
    return {
      status: 200,
      body: { batchUuid: body.batchUuid, status: 'ACCEPTED', duplicateBatch, received: records.length, inserted, duplicatesIgnored },
    };
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const route = url.pathname.replace(/\/+$/, '') || '/';
    try {
      if (req.method === 'GET' && route === '/health') {
        return send(res, 200, { status: 'UP', service: 'mock-hr-payroll-api', mock: true, persistent: state.persisted, failureInjected: state.failureStatus || null });
      }
      if (req.method === 'GET' && route === '/mock/hr/employees') {
        return send(res, 200, { employees: EMPLOYEES });
      }
      if (req.method === 'POST' && route === '/mock/hr/attendance-batches') {
        const body = await readJson(req); // read first so a failing mock still drains the request
        if (state.failureStatus) {
          return send(res, state.failureStatus, { code: 'HR_UNAVAILABLE', message: 'failure injected by /mock/hr/control/failure; nothing was stored' });
        }
        const outcome = receiveBatch(body);
        return send(res, outcome.status, outcome.body);
      }
      let match = route.match(/^\/mock\/hr\/attendance-batches\/([^/]+)$/);
      if (req.method === 'GET' && match) {
        const batch = state.data.batches[decodeURIComponent(match[1])];
        return batch ? send(res, 200, { batchUuid: decodeURIComponent(match[1]), ...batch }) : send(res, 404, { code: 'NOT_FOUND', message: 'unknown batchUuid' });
      }
      if (req.method === 'GET' && route === '/mock/hr/attendance-records/count') {
        return send(res, 200, { records: Object.keys(state.data.records).length, batches: Object.keys(state.data.batches).length });
      }
      if (route === '/mock/hr/control/failure') {
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
      if (req.method === 'POST' && route === '/mock/hr/control/reset') {
        state.data = { records: {}, batches: {} };
        persist();
        return send(res, 200, { records: 0, batches: 0 });
      }
      return send(res, 404, { code: 'NOT_FOUND', message: `${req.method} ${route} is not a mock HR route` });
    } catch (err) {
      const status = err.status || 500;
      return send(res, status, { code: status === 500 ? 'INTERNAL_ERROR' : 'INVALID_REQUEST', message: status === 500 ? 'unexpected error' : err.message });
    }
  });

  return { server, state };
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8091);
  const { server } = createServer({
    // SIMULATE_HR_DOWN: 0/false/empty = healthy, otherwise the HTTP status to return (e.g. 500, 503). `true` means 503.
    failureStatus: ['true', '1'].includes(String(process.env.SIMULATE_HR_DOWN).toLowerCase()) ? 503 : Number(process.env.SIMULATE_HR_DOWN) || 0,
    dataFile: process.env.HR_DATA_FILE || null,
  });
  server.listen(port, '0.0.0.0', () => {
    console.log(`mock-hr-payroll-api (DEV-ONLY mock) listening on :${port}`);
  });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}

module.exports = { createServer };
