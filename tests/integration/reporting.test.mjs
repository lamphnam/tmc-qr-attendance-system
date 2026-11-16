// Integration test of reporting-service (D10 / B24, ADR-03) against a RUNNING Compose stack.
//
//   docker compose up --build -d --wait
//   node --test tests/integration/reporting.test.mjs
//
// Path under test: gateway -> /api/dev/reports (dev profile) -> PostgreSQL batch + frozen items -> REAL mock HR.
// Assertions are made on state: rows in PostgreSQL through `psql` and the batch as the mock HR stored it.
// Events come from the dev fixtures, so this needs REPORTING_INCLUDE_DEV_FIXTURES=true (the Compose default).
// It proves the dev slice only: no authentication, no real payroll system.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { after, before, test } from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const GATEWAY = process.env.GATEWAY_URL ?? `http://127.0.0.1:${process.env.GATEWAY_HOST_PORT ?? 8080}`;
const HR = process.env.MOCK_HR_URL ?? `http://127.0.0.1:${process.env.MOCK_HR_HOST_PORT ?? 8091}`;
const DB_USER = process.env.POSTGRES_USER ?? 'tmc_app';
const DB_NAME = process.env.POSTGRES_DB ?? 'tmc';

const psql = (sql) =>
  execFileSync('docker', ['compose', 'exec', '-T', 'central-db', 'psql', '-U', DB_USER, '-d', DB_NAME, '-At', '-c', sql], {
    cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();

async function api(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { json = null; }
  return { status: response.status, json, text };
}

/** Same algorithm as Java's UUID.nameUUIDFromBytes (MD5, version 3), computed independently of the service. */
function batchUuidFor(period) {
  const h = createHash('md5').update(`tmc-hr-export:${period}`, 'utf8').digest();
  h[6] = (h[6] & 0x0f) | 0x30;
  h[8] = (h[8] & 0x3f) | 0x80;
  const hex = h.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** A past month that has no batch and no events yet, so repeated runs on a kept volume do not collide. */
function freshPeriod() {
  for (let i = 0; i < 50; i++) {
    const year = 2001 + Math.floor(Math.random() * 19);
    const month = String(1 + Math.floor(Math.random() * 12)).padStart(2, '0');
    const period = `${year}-${month}`;
    const used = Number(psql(`SELECT (SELECT count(*) FROM hr_export_batches WHERE period = '${period}')
      + (SELECT count(*) FROM attendance_events WHERE to_char(occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM') = '${period}')`));
    if (used === 0) return period;
  }
  throw new Error('could not find an unused period');
}

async function devEvent(period, { scanResult = 'ALLOWED', day = '15' } = {}) {
  const attendanceId = randomUUID();
  const { status } = await api('POST', `${GATEWAY}/api/dev/attendance/events`, {
    attendanceId, employeeId: 'EMP001', factoryId: 'BD', gateId: 'BD-G01', direction: 'IN',
    occurredAt: `${period}-${day}T03:00:00.000Z`, scanResult,
  });
  assert.equal(status, 200, 'the dev ingestion fixture must store the event');
  return attendanceId;
}

const trigger = (period) => api('POST', `${GATEWAY}/api/dev/reports/hr-export/trigger`, { period });
const hrBatch = (uuid) => api('GET', `${HR}/mock/hr/attendance-batches/${uuid}`);
const setHrFailure = (status) => api('PUT', `${HR}/mock/hr/control/failure`, { status });

before(async () => {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const { status } = await api('GET', `${GATEWAY}/services/reporting/health`).catch(() => ({ status: 0 }));
    if (status === 200) return;
    await sleep(1000);
  }
  throw new Error('reporting-service is not healthy behind the gateway');
});

after(async () => {
  await setHrFailure(0).catch(() => {});
});

test('a manual export reaches the real mock HR once, with the deterministic batch id and frozen items', async () => {
  const period = freshPeriod();
  const a = await devEvent(period, { day: '10' });
  const b = await devEvent(period, { day: '20' });
  await devEvent(period, { scanResult: 'REJECTED' });
  const expectedUuid = batchUuidFor(period);

  const first = await trigger(period);
  assert.equal(first.status, 200, first.text);
  assert.equal(first.json.outcome, 'DELIVERED');
  assert.equal(first.json.batch.batchUuid, expectedUuid);
  assert.equal(first.json.batch.status, 'ACKNOWLEDGED');
  assert.equal(first.json.batch.itemCount, 2);

  const items = psql(`SELECT attendance_id FROM hr_export_batch_items WHERE batch_uuid = '${expectedUuid}' ORDER BY 1`).split('\n');
  assert.deepEqual(items, [a, b].sort(), 'only the two ALLOWED events are frozen into the batch');
  assert.equal(psql(`SELECT status || ',' || attempt_count FROM hr_export_batches WHERE period = '${period}'`), 'ACKNOWLEDGED,1');

  const received = await hrBatch(expectedUuid);
  assert.equal(received.status, 200, 'the mock HR stored the batch under the same id');
  assert.equal(received.json.itemCount, 2);
  assert.equal(received.json.timesReceived, 1);

  const again = await trigger(period);
  assert.equal(again.status, 409);
  assert.equal(again.json.code, 'ALREADY_EXPORTED');
  assert.equal((await hrBatch(expectedUuid)).json.timesReceived, 1, 'a delivered month is never sent twice');
});

test('HR down: 202 with a scheduled retry and nothing stored; HR back: the same batch is delivered', async () => {
  const period = freshPeriod();
  await devEvent(period);
  const expectedUuid = batchUuidFor(period);

  await setHrFailure(503);
  try {
    const down = await trigger(period);
    assert.equal(down.status, 202, down.text);
    assert.equal(down.json.outcome, 'RETRY_SCHEDULED');
    assert.equal(down.json.batch.status, 'PENDING');
    assert.equal(down.json.batch.attemptCount, 1);
    assert.equal(down.json.batch.lastError, 'HTTP 503');
    assert.ok(down.json.batch.nextRetryAt, 'a next retry time is persisted');
    assert.equal((await hrBatch(expectedUuid)).status, 404, 'a failed attempt stores nothing at the receiver');
  } finally {
    await setHrFailure(0);
  }

  const back = await trigger(period);
  assert.equal(back.status, 200, back.text);
  assert.equal(back.json.batch.batchUuid, expectedUuid);
  assert.equal(back.json.batch.attemptCount, 2);
  assert.equal(back.json.batch.status, 'ACKNOWLEDGED');
  assert.equal(psql(`SELECT count(*) FROM hr_export_batches WHERE period = '${period}'`), '1');
  assert.equal((await hrBatch(expectedUuid)).json.timesReceived, 1);
});

test('the dashboard reads PostgreSQL through the gateway and shows the latest export', async () => {
  const { status, json, text } = await api('GET', `${GATEWAY}/api/dev/reports/dashboard?factoryId=BD`);
  assert.equal(status, 200, text);
  assert.equal(json.timeZone, 'Asia/Ho_Chi_Minh');
  assert.equal(typeof json.employeesPresent, 'number');
  assert.ok(json.latestHrExport, 'at least one batch exists after the tests above');
});
