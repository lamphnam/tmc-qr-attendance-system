// Integration test of the P1 dev-only vertical slice against a RUNNING Compose stack.
//
//   docker compose up --build -d --wait
//   node --test tests/integration/slice.test.mjs          (SKIP_DISRUPTIVE=1 skips the tests that stop/restart containers)
//
// Assertions are made on state: rows counted in PostgreSQL through `psql`, SQLite queue rows read back through the
// kiosk, commands counted by the mock turnstile. An HTTP 200 alone never makes a test pass.
//
// This proves a LIMITED slice (synthetic events, no QR/TOTP, no auth). It does not prove ADR-01/02/03 or any NFR.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { after, before, test } from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const GATEWAY = process.env.GATEWAY_URL ?? `http://127.0.0.1:${process.env.GATEWAY_HOST_PORT ?? 8080}`;
const TURNSTILE = process.env.MOCK_TURNSTILE_URL ?? `http://127.0.0.1:${process.env.MOCK_TURNSTILE_HOST_PORT ?? 8090}`;
const HR = process.env.MOCK_HR_URL ?? `http://127.0.0.1:${process.env.MOCK_HR_HOST_PORT ?? 8091}`;
const DB_USER = process.env.POSTGRES_USER ?? 'tmc_app';
const DB_NAME = process.env.POSTGRES_DB ?? 'tmc';
const SKIP_DISRUPTIVE = process.env.SKIP_DISRUPTIVE === '1';

const compose = (...args) => execFileSync('docker', ['compose', ...args], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const psql = (sql) => compose('exec', '-T', 'central-db', 'psql', '-U', DB_USER, '-d', DB_NAME, '-At', '-c', sql).trim();
const rowsFor = (id) => Number(psql(`SELECT count(*) FROM attendance_events WHERE attendance_id = '${id}'`));
const totalRows = () => Number(psql('SELECT count(*) FROM attendance_events'));

async function api(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status, json, text };
}

async function waitFor(what, probe, { timeoutMs = 30_000, intervalMs = 500 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await probe();
      if (last) return last;
    } catch (error) {
      last = error;
    }
    await sleep(intervalMs);
  }
  throw new Error(`timed out after ${timeoutMs} ms waiting for: ${what} (last: ${last instanceof Error ? last.message : String(last)})`);
}

const eventBody = (attendanceId, employeeId = 'EMP001') => ({
  attendanceId,
  employeeId,
  factoryId: 'BD',
  gateId: 'BD-G01',
  direction: 'IN',
  occurredAt: '2026-11-01T01:20:00.123Z',
  scanResult: 'ALLOWED',
});

const kioskApi = (method, path, body) => api(method, `${GATEWAY}/api/dev/kiosk${path}`, body);
const setOffline = (disabled) => kioskApi('PUT', '/sync/offline', { disabled });
const kioskEvent = async (id) => {
  const { json } = await kioskApi('GET', '/queue?limit=200');
  const found = json.events.find((e) => e.attendanceId === id);
  assert.ok(found, `event ${id} is not in the kiosk SQLite queue`);
  return found;
};
const turnstileCommands = async (gateId) => (await api('GET', `${TURNSTILE}/gates/${gateId}/status`)).json.commandsReceived;
const gatewayHealthy = async (path) => (await api('GET', `${GATEWAY}${path}`)).status === 200;

before(async () => {
  const health = await api('GET', `${GATEWAY}/gateway/health`).catch(() => ({ status: 0 }));
  assert.equal(health.status, 200, `the stack is not reachable at ${GATEWAY}. Start it: docker compose up --build -d --wait`);
});

after(async () => {
  // Leave the stack as we found it, even when a disruptive test failed half-way.
  try {
    compose('start', 'attendance-processor');
  } catch {
    /* already running */
  }
  await setOffline(false).catch(() => {});
});

// ---------------------------------------------------------------------------------------------------------------------
// Central: idempotent ingestion, judged by rows in PostgreSQL
// ---------------------------------------------------------------------------------------------------------------------

test('central: the same attendanceId sent twice creates exactly one PostgreSQL row', async () => {
  const id = randomUUID();
  const totalBefore = totalRows();

  const first = await api('POST', `${GATEWAY}/api/dev/attendance/events`, eventBody(id));
  assert.equal(first.status, 200);
  assert.deepEqual([first.json.status, first.json.duplicate], ['STORED', false]);
  assert.equal(rowsFor(id), 1);

  const retry = await api('POST', `${GATEWAY}/api/dev/attendance/events`, eventBody(id));
  assert.equal(retry.status, 200);
  assert.deepEqual([retry.json.status, retry.json.duplicate], ['ALREADY_EXISTS', true]);
  assert.equal(rowsFor(id), 1, 'the retry must not add a row');
  assert.equal(totalRows(), totalBefore + 1);
});

test('central: a reused id with a different payload is a 409 and never overwrites the stored row', async () => {
  const id = randomUUID();
  await api('POST', `${GATEWAY}/api/dev/attendance/events`, eventBody(id, 'EMP001'));

  const clash = await api('POST', `${GATEWAY}/api/dev/attendance/events`, eventBody(id, 'EMP002'));

  assert.equal(clash.status, 409);
  assert.equal(psql(`SELECT employee_id FROM attendance_events WHERE attendance_id = '${id}'`), 'EMP001');
  assert.equal(rowsFor(id), 1);
});

test('central: batch sync acks per event, rejects the malformed one alone, and repeating adds no rows', async () => {
  const [a, b, c] = [randomUUID(), randomUUID(), randomUUID()];
  const batch = { kioskId: 'BD-K01', events: [eventBody(a), eventBody(b), eventBody(c), { ...eventBody(randomUUID()), attendanceId: 'not-a-uuid' }] };

  const first = await api('POST', `${GATEWAY}/api/dev/attendance/sync`, batch);
  assert.equal(first.status, 200);
  assert.deepEqual(first.json.results.map((r) => r.status), ['STORED', 'STORED', 'STORED', 'REJECTED_PERMANENT']);
  assert.deepEqual(first.json.rejectedIds, ['not-a-uuid']);

  const again = await api('POST', `${GATEWAY}/api/dev/attendance/sync`, batch);
  assert.deepEqual(again.json.results.map((r) => r.status), ['ALREADY_EXISTS', 'ALREADY_EXISTS', 'ALREADY_EXISTS', 'REJECTED_PERMANENT']);
  assert.equal(Number(psql(`SELECT count(*) FROM attendance_events WHERE attendance_id IN ('${a}','${b}','${c}')`)), 3);
  assert.equal(psql(`SELECT DISTINCT source_kiosk_id FROM attendance_events WHERE attendance_id = '${a}'`), 'BD-K01');
});

test('central: the production route is an honest 501 and stores nothing', async () => {
  const id = randomUUID();
  const response = await api('POST', `${GATEWAY}/api/v1/attendance/events`, eventBody(id));

  assert.equal(response.status, 501);
  assert.equal(response.json.code, 'NOT_IMPLEMENTED');
  assert.equal(rowsFor(id), 0);
});

// ---------------------------------------------------------------------------------------------------------------------
// Kiosk: SQLite queue, local turnstile, sync
// ---------------------------------------------------------------------------------------------------------------------

test('kiosk: the SQLite database file really lives on the kiosk volume', () => {
  const listing = compose('exec', '-T', 'kiosk-agent', 'ls', '/data');
  assert.match(listing, /kiosk\.db/);
});

test('kiosk offline: event is durable PENDING, the local turnstile still gets the command, central has nothing; reconnect syncs it exactly once', async () => {
  assert.equal((await setOffline(true)).json.centralSyncDisabled, true);
  const commandsBefore = await turnstileCommands('BD-G01');

  const queued = await kioskApi('POST', '/synthetic-event', { employeeId: 'EMP002', gateId: 'BD-G01', direction: 'IN' });
  assert.equal(queued.status, 201);
  assert.equal(queued.json.syncStatus, 'PENDING');
  assert.equal(queued.json.gateCommandStatus, 'SENT');
  const id = queued.json.attendanceId;
  assert.equal(await turnstileCommands('BD-G01'), commandsBefore + 1, 'the kiosk must reach the local mock turnstile over HTTP while central sync is off');

  const skipped = await kioskApi('POST', '/sync/run');
  assert.equal(skipped.json.skippedReason, 'CENTRAL_SYNC_DISABLED');
  assert.equal(rowsFor(id), 0, 'central must not know the event yet');
  assert.equal((await kioskEvent(id)).syncStatus, 'PENDING');

  await setOffline(false);
  await kioskApi('POST', '/sync/run');
  await waitFor('kiosk marks the event SYNCED', async () => (await kioskEvent(id)).syncStatus === 'SYNCED');
  assert.equal(rowsFor(id), 1);
  // (string concatenation casts boolean to 'true'/'false'; a bare boolean column would print t/f)
  assert.equal(psql(`SELECT employee_id || ',' || source_kiosk_id || ',' || dev_fixture FROM attendance_events WHERE attendance_id = '${id}'`), 'EMP002,BD-K01,true');

  await kioskApi('POST', '/sync/run');
  assert.equal(rowsFor(id), 1, 'a further sync pass must not create a duplicate');
});

test('kiosk with central really DOWN and a kiosk RESTART: the event survives in SQLite, is retried, and lands exactly once',
  { skip: SKIP_DISRUPTIVE ? 'SKIP_DISRUPTIVE=1' : false, timeout: 300_000 }, async () => {
    await setOffline(false);
    compose('stop', 'attendance-processor');
    let id;
    try {
      const queued = await kioskApi('POST', '/synthetic-event', { employeeId: 'EMP003', gateId: 'BD-G01', direction: 'OUT' });
      assert.equal(queued.status, 201, 'the kiosk keeps working while central is down');
      assert.equal(queued.json.gateCommandStatus, 'SENT');
      id = queued.json.attendanceId;

      await waitFor('a real failed sync attempt is recorded', async () => (await kioskEvent(id)).retryCount >= 1, { timeoutMs: 60_000 });
      assert.equal((await kioskEvent(id)).syncStatus, 'PENDING');
      assert.equal(rowsFor(id), 0);

      compose('restart', 'kiosk-agent');
      await waitFor('kiosk healthy after restart', () => gatewayHealthy('/services/kiosk/health'), { timeoutMs: 120_000, intervalMs: 1000 });

      const survived = await kioskEvent(id);
      assert.equal(survived.syncStatus, 'PENDING', 'the PENDING row must survive a kiosk process restart');
      assert.ok(survived.retryCount >= 1, 'retry metadata survives too');
    } finally {
      compose('start', 'attendance-processor');
    }

    await waitFor('attendance-processor healthy again', () => gatewayHealthy('/services/attendance/health'), { timeoutMs: 120_000, intervalMs: 1000 });
    await waitFor('kiosk delivers the event after recovery', async () => (await kioskEvent(id)).syncStatus === 'SYNCED', { timeoutMs: 150_000, intervalMs: 1000 });
    assert.equal(rowsFor(id), 1, 'exactly one central row after the outage');
  });

// ---------------------------------------------------------------------------------------------------------------------
// Mock HR
// ---------------------------------------------------------------------------------------------------------------------

test('mock HR: a re-sent batch stores nothing twice and the state survives a container restart',
  { skip: SKIP_DISRUPTIVE ? 'SKIP_DISRUPTIVE=1' : false, timeout: 120_000 }, async () => {
    await api('POST', `${HR}/mock/hr/control/reset`);
    const batchUuid = randomUUID();
    const batch = { batchUuid, period: '2026-10', attendanceIds: [randomUUID(), randomUUID()] };
    try {
      const first = await api('POST', `${HR}/mock/hr/attendance-batches`, batch);
      assert.deepEqual([first.json.inserted, first.json.duplicateBatch], [2, false]);
      const second = await api('POST', `${HR}/mock/hr/attendance-batches`, batch);
      assert.deepEqual([second.json.inserted, second.json.duplicateBatch], [0, true]);
      assert.deepEqual((await api('GET', `${HR}/mock/hr/attendance-records/count`)).json, { records: 2, batches: 1 });

      compose('restart', 'mock-hr-payroll-api');
      await waitFor('mock HR healthy after restart', async () => (await api('GET', `${HR}/health`)).status === 200, { timeoutMs: 60_000 });

      assert.deepEqual((await api('GET', `${HR}/mock/hr/attendance-records/count`)).json, { records: 2, batches: 1 }, 'received data persists on the volume');
      const afterRestart = await api('POST', `${HR}/mock/hr/attendance-batches`, batch);
      assert.equal(afterRestart.json.duplicateBatch, true);
    } finally {
      await api('POST', `${HR}/mock/hr/control/reset`).catch(() => {});
    }
  });
