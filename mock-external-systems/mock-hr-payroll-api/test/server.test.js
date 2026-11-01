'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { createServer } = require('../server.js');

async function withServer(options, fn) {
  const { server } = createServer(options);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const call = (base, route, method = 'GET', body) =>
  fetch(base + route, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
const count = async (base) => (await (await call(base, '/mock/hr/attendance-records/count')).json());

test('health answers over real HTTP', async () => {
  await withServer({}, async (base) => {
    const res = await call(base, '/health');
    assert.equal(res.status, 200);
    assert.equal((await res.json()).service, 'mock-hr-payroll-api');
  });
});

test('a batch is stored once; resending the same batch adds no records (NFR-3)', async () => {
  await withServer({}, async (base) => {
    const batchUuid = randomUUID();
    const ids = [randomUUID(), randomUUID(), randomUUID()];

    const first = await (await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid, period: '2026-10', attendanceIds: ids })).json();
    assert.deepEqual([first.inserted, first.duplicatesIgnored, first.duplicateBatch], [3, 0, false]);
    assert.deepEqual(await count(base), { records: 3, batches: 1 });

    const again = await (await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid, period: '2026-10', attendanceIds: ids })).json();
    assert.deepEqual([again.inserted, again.duplicatesIgnored, again.duplicateBatch], [0, 3, true]);
    assert.deepEqual(await count(base), { records: 3, batches: 1 });

    const info = await (await call(base, `/mock/hr/attendance-batches/${batchUuid}`)).json();
    assert.equal(info.timesReceived, 2);
  });
});

test('an attendanceId already received in another batch is not stored twice', async () => {
  await withServer({}, async (base) => {
    const shared = randomUUID();
    await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid: randomUUID(), attendanceIds: [shared] });
    const second = await (await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid: randomUUID(), attendanceIds: [shared, randomUUID()] })).json();
    assert.deepEqual([second.inserted, second.duplicatesIgnored], [1, 1]);
    assert.equal((await count(base)).records, 2);
  });
});

test('"records" objects are accepted as well as bare ids', async () => {
  await withServer({}, async (base) => {
    const res = await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid: randomUUID(), records: [{ attendanceId: randomUUID(), employeeId: 'EMP001' }] });
    assert.equal((await res.json()).inserted, 1);
  });
});

for (const status of [500, 503]) {
  test(`injected HTTP ${status} is retryable, stores nothing, and recovery then succeeds`, async () => {
    await withServer({}, async (base) => {
      assert.equal((await call(base, '/mock/hr/control/failure', 'PUT', { status })).status, 200);
      const batch = { batchUuid: randomUUID(), attendanceIds: [randomUUID()] };

      const failed = await call(base, '/mock/hr/attendance-batches', 'POST', batch);
      assert.equal(failed.status, status);
      assert.deepEqual(await count(base), { records: 0, batches: 0 });

      await call(base, '/mock/hr/control/failure', 'PUT', { status: 0 });
      const ok = await call(base, '/mock/hr/attendance-batches', 'POST', batch);
      assert.equal(ok.status, 200);
      assert.deepEqual(await count(base), { records: 1, batches: 1 });
    });
  });
}

test('failure can be preset like SIMULATE_HR_DOWN does', async () => {
  await withServer({ failureStatus: 503 }, async (base) => {
    assert.equal((await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid: randomUUID(), attendanceIds: [] })).status, 503);
  });
});

test('malformed requests are rejected with 400 and nothing is stored', async () => {
  await withServer({}, async (base) => {
    const cases = [
      { batchUuid: 'nope', attendanceIds: [] },
      { batchUuid: randomUUID() },
      { batchUuid: randomUUID(), attendanceIds: ['not-a-uuid'] },
    ];
    for (const body of cases) {
      assert.equal((await call(base, '/mock/hr/attendance-batches', 'POST', body)).status, 400);
    }
    const garbage = await fetch(`${base}/mock/hr/attendance-batches`, { method: 'POST', body: '{nope' });
    assert.equal(garbage.status, 400);
    assert.deepEqual(await count(base), { records: 0, batches: 0 });
  });
});

test('employee master data is served for the HR sync (FR-1.1) and contains no secrets', async () => {
  await withServer({}, async (base) => {
    const { employees } = await (await call(base, '/mock/hr/employees')).json();
    assert.equal(employees.length, 4);
    assert.ok(employees.every((e) => !('sharedSecret' in e) && !('password' in e)));
  });
});

test('received data survives a restart when a data file is configured', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mock-hr-'));
  const dataFile = path.join(dir, 'state.json');
  const batchUuid = randomUUID();
  try {
    await withServer({ dataFile }, async (base) => {
      await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid, attendanceIds: [randomUUID(), randomUUID()] });
    });
    await withServer({ dataFile }, async (base) => {
      assert.deepEqual(await count(base), { records: 2, batches: 1 });
      const again = await (await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid, attendanceIds: [] })).json();
      assert.equal(again.duplicateBatch, true);
    });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('reset wipes received data', async () => {
  await withServer({}, async (base) => {
    await call(base, '/mock/hr/attendance-batches', 'POST', { batchUuid: randomUUID(), attendanceIds: [randomUUID()] });
    await call(base, '/mock/hr/control/reset', 'POST');
    assert.deepEqual(await count(base), { records: 0, batches: 0 });
  });
});
