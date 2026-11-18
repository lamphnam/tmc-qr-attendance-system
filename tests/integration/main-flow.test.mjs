// Real QR scan through the dev gateway, local SQLite, mock turnstile and central PostgreSQL.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHmac, randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const GATEWAY = process.env.GATEWAY_URL ?? `http://127.0.0.1:${process.env.GATEWAY_HOST_PORT ?? 8080}`;
const TURNSTILE = process.env.MOCK_TURNSTILE_URL ?? `http://127.0.0.1:${process.env.MOCK_TURNSTILE_HOST_PORT ?? 8090}`;
const GATE = process.env.KIOSK_DEFAULT_GATE_ID ?? 'BD-G01';
const KIOSK = process.env.KIOSK_ID ?? 'BD-K01';
const SECRET = 'MAIN_FLOW_SYNTHETIC_KEY';

function compose(args, options = {}) {
  return execFileSync('docker', ['compose', ...args], { cwd: ROOT, encoding: 'utf8', timeout: 120_000, ...options });
}

function psql(sql) {
  return compose(['exec', '-T', 'central-db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
    '-U', process.env.POSTGRES_USER ?? 'tmc_app', '-d', process.env.POSTGRES_DB ?? 'tmc', '-At'], { input: `${sql}\n` }).trim();
}

async function request(path, { method = 'GET', body, expected = 200, base = GATEWAY } = {}) {
  const response = await fetch(`${base}${path}`, {
    method, headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10_000),
  });
  if (response.status !== expected) throw new Error(`${method} ${path} returned ${response.status}, expected ${expected}`);
  return response.json();
}

async function waitFor(label, probe, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  let failure;
  do {
    try {
      if (await probe()) return;
    } catch (error) {
      failure = error;
    }
    await sleep(500);
  } while (Date.now() < deadline);
  throw new Error(`Timed out waiting for ${label}`, { cause: failure });
}

async function refreshWhitelist() {
  await waitFor('manual whitelist refresh', async () => {
    const result = await request('/api/dev/kiosk/whitelist/sync', { method: 'POST' });
    return ['UPDATED', 'NOT_NEWER'].includes(result.status);
  }, 20_000);
}

async function seedEmployees(count = 1) {
  const employees = Array.from({ length: count }, () => `MF-${randomUUID().replaceAll('-', '').slice(0, 28)}`);
  const values = employees.map((id) => `('${id}','Main Flow Test','${id}','BD','${SECRET}')`).join(',');
  psql(`BEGIN; UPDATE factories SET whitelist_version=whitelist_version+1 WHERE factory_id='BD';
        INSERT INTO employees (employee_id,full_name,department,factory_id,shared_secret)
        VALUES ${values}; COMMIT;`);
  await refreshWhitelist();
  return employees;
}

async function qrFor(employee) {
  const { qrPayload } = await request(`/api/v1/qr/${employee}`);
  assert.equal(qrPayload.empId, employee);
  assert.ok(typeof qrPayload.totp === 'string' && /^[0-9]{6}$/.test(qrPayload.totp));
  return qrPayload;
}

function scan(qrPayload) {
  return request('/api/local/scan', { method: 'POST', body: { gateId: GATE, direction: 'IN', qrPayload } });
}

async function scanAllowed(qrPayload) {
  const result = await scan(qrPayload);
  assert.equal(result.result, 'ALLOWED');
  assert.equal(result.gateCommand, 'SENT');
  assert.match(result.attendanceId ?? '', /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/);
  assert.ok(Number.isInteger(result.processingTimeMs) && result.processingTimeMs >= 0);
  return result.attendanceId;
}

async function setOffline(disabled) {
  const result = await request('/api/dev/kiosk/sync/offline', { method: 'PUT', body: { disabled } });
  assert.equal(result.centralSyncDisabled, disabled);
}

async function commands() {
  const state = await request(`/gates/${GATE}/status`, { base: TURNSTILE });
  return state.commandsReceived;
}

async function queue() {
  const { events } = await request('/api/dev/kiosk/queue?limit=200');
  return events;
}

async function localEvent(id) {
  const event = (await queue()).find((entry) => entry.attendanceId === id);
  assert.ok(event, `SQLite queue is missing ${id}`);
  return event;
}

function centralIds(employees) {
  const ids = psql(`SELECT attendance_id FROM attendance_events
    WHERE employee_id IN (${employees.map((id) => `'${id}'`).join(',')}) ORDER BY attendance_id;`);
  return ids ? ids.split(/\r?\n/) : [];
}

function resend(event) {
  const { attendanceId, employeeId, factoryId, gateId, direction, occurredAt, scanResult } = event;
  return request('/api/dev/attendance/sync', { method: 'POST', body: {
    kioskId: KIOSK, events: [{ attendanceId, employeeId, factoryId, gateId, direction, occurredAt, scanResult }],
  } });
}

test('QR -> kiosk -> gate command -> SQLite SYNCED -> PostgreSQL -> demo events and report', async () => {
  const [employee] = await seedEmployees();
  const before = await commands();
  const id = await scanAllowed(await qrFor(employee));
  assert.equal(await commands(), before + 1);
  const local = await localEvent(id);
  assert.equal(local.devFixture, false);
  await waitFor('SQLite ack', async () => (await localEvent(id)).syncStatus === 'SYNCED');
  assert.deepEqual(centralIds([employee]), [id]);
  const { events } = await request(`/api/v1/attendance/events?employeeId=${employee}`);
  assert.deepEqual(events.map((event) => event.attendanceId), [id]);
  const day = new Date(Date.parse(local.occurredAt) + 7 * 3600_000).toISOString().slice(0, 10);
  const report = await request(`/api/dev/reports/attendance?factoryId=BD&from=${day}&to=${day}&department=${employee}`);
  assert.deepEqual(report.rows.map((row) => row.employeeId), [employee]);
});

test('an exact replay is rejected with one gate command and one event total', async () => {
  const [employee] = await seedEmployees();
  const payload = await qrFor(employee);
  const before = await commands();
  const id = await scanAllowed(payload);
  const replay = await scan(payload);
  assert.deepEqual([replay.result, replay.reason, replay.gateCommand], ['REJECTED', 'REPLAYED', 'NOT_ATTEMPTED']);
  assert.equal(await commands(), before + 1);
  assert.deepEqual((await queue()).filter((row) => row.employeeId === employee).map((row) => row.attendanceId), [id]);
  await waitFor('original scan acknowledged', async () => (await localEvent(id)).syncStatus === 'SYNCED');
  assert.deepEqual(centralIds([employee]), [id]);
});

test('offline scan sends the mock gate command, remains PENDING and syncs once after reconnect', async () => {
  const [employee] = await seedEmployees();
  const payload = await qrFor(employee);
  let id;
  try {
    await setOffline(true);
    const before = await commands();
    id = await scanAllowed(payload);
    assert.equal(await commands(), before + 1);
    assert.equal((await localEvent(id)).syncStatus, 'PENDING');
    assert.deepEqual(centralIds([employee]), []);
  } finally {
    await setOffline(false);
  }
  await waitFor('reconnected sync', async () => (await localEvent(id)).syncStatus === 'SYNCED');
  assert.deepEqual(centralIds([employee]), [id]);
});

test('resending the same event returns ALREADY_EXISTS after commit', async () => {
  const [employee] = await seedEmployees();
  const id = await scanAllowed(await qrFor(employee));
  await waitFor('first commit', async () => (await localEvent(id)).syncStatus === 'SYNCED');
  const { results } = await resend(await localEvent(id));
  assert.deepEqual(results.map(({ attendanceId, status }) => [attendanceId, status]), [[id, 'ALREADY_EXISTS']]);
  assert.deepEqual(centralIds([employee]), [id]);
});

function codeAt(step) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac('sha256', SECRET).update(counter).digest();
  const offset = hash.at(-1) & 0xf;
  return String((hash.readUInt32BE(offset) & 0x7fff_ffff) % 1_000_000).padStart(6, '0');
}

test('invalid, expired, unknown, revoked and rotated QR never reach the gate or attendance queue', async () => {
  const [employee, revoked, rotated] = await seedEmployees(3);
  const [valid, oldRevoked, oldRotated] = await Promise.all([employee, revoked, rotated].map(qrFor));
  const before = await commands();
  const step = Math.floor(Date.now() / 30_000);
  // Rule out accidental six-digit collisions with any counter the kiosk may accept near this boundary.
  const liveCodes = [step - 1, step, step + 1].map(codeAt);
  const wrong = ['000000', '000001', '000002', '000003'].find((code) => !liveCodes.includes(code));
  let expiredStep = step - 2;
  while (liveCodes.includes(codeAt(expiredStep))) expiredStep--;
  const unknown = `MF-${randomUUID().replaceAll('-', '').slice(0, 28)}`;
  try {
    await request(`/api/v1/qr/${revoked}/revoke`, { method: 'POST', body: { reason: 'Main flow synthetic fixture' } });
    await request(`/api/v1/qr/${rotated}/rotate`, { method: 'POST' });
    await refreshWhitelist();
    for (const [payload, reason] of [
      [{ ...valid, totp: wrong }, 'INVALID_TOTP'],
      [{ ...valid, totp: codeAt(expiredStep), ts: expiredStep * 30 }, 'INVALID_TOTP'],
      [{ ...valid, empId: unknown }, 'UNKNOWN_EMPLOYEE'],
      [oldRevoked, 'REVOKED'],
      [oldRotated, 'INVALID_TOTP'],
    ]) {
      const result = await scan(payload);
      assert.deepEqual([result.result, result.reason, result.gateCommand], ['REJECTED', reason, 'NOT_ATTEMPTED']);
    }
    assert.equal(await commands(), before);
    const employees = [employee, revoked, rotated, unknown];
    assert.ok(!(await queue()).some((row) => employees.includes(row.employeeId)));
    assert.deepEqual(centralIds(employees), []);
  } finally {
    await request(`/api/v1/qr/${revoked}/activate`, { method: 'POST' });
    await refreshWhitelist();
  }
});

test('T02: 20 real scans survive an offline restart and sync each ID once including a prior central commit',
  { skip: process.env.SKIP_DISRUPTIVE === '1', timeout: 300_000 }, async () => {
    const employees = await seedEmployees(20);
    const payloads = await Promise.all(employees.map(qrFor));
    const before = await commands();
    const ids = [];
    const assertPending = async () => {
      const rows = (await queue()).filter((row) => ids.includes(row.attendanceId));
      assert.deepEqual(rows.map((row) => row.attendanceId).sort(), [...ids].sort());
      assert.ok(rows.every((row) => row.syncStatus === 'PENDING'));
      return true;
    };

    let centralStopped = false;
    try {
      await setOffline(true);
      for (const payload of payloads) ids.push(await scanAllowed(payload));
      assert.equal(new Set(ids).size, 20);
      assert.equal(await commands(), before + 20);
      assert.deepEqual(centralIds(employees), []);
      await assertPending();

      // The demo toggle is in memory. Keep central genuinely unavailable across the process restart.
      compose(['stop', 'attendance-processor']);
      centralStopped = true;
      compose(['restart', 'kiosk-agent']);
      await waitFor('all 20 events still PENDING after restart', assertPending);
      await setOffline(true);
      assert.deepEqual(centralIds(employees), []);
      const replay = await scan(payloads.at(-1));
      // Restart can outlast the TOTP window. Persistence of the used token is verified in Java.
      assert.equal(replay.result, 'REJECTED');
      assert.equal(await commands(), before + 20);

      compose(['start', 'attendance-processor']);
      centralStopped = false;
      await waitFor('central health', async () => (await request('/services/attendance/health')).status === 'UP');
      // Reproduce commit-without-ack state: central commits, but kiosk never receives this separate request's reply.
      const { results } = await resend(await localEvent(ids[0]));
      assert.equal(results[0].status, 'STORED');
      assert.deepEqual(centralIds(employees), [ids[0]]);
      await assertPending();
    } finally {
      if (centralStopped) compose(['start', 'attendance-processor']);
      await waitFor('kiosk offline toggle to be reset', async () => {
        await setOffline(false);
        return true;
      });
    }

    await waitFor('all 20 SQLite acks', async () => {
      const rows = (await queue()).filter((row) => ids.includes(row.attendanceId));
      return rows.length === 20 && rows.every((row) => row.syncStatus === 'SYNCED');
    });
    assert.deepEqual(centralIds(employees), [...ids].sort());
    assert.equal(await commands(), before + 20);
  });
