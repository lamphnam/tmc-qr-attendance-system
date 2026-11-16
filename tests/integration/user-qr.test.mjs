// Real HTTP -> User/QR -> canonical PostgreSQL schema. Synthetic employee per run.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const GATEWAY = process.env.GATEWAY_URL ?? `http://127.0.0.1:${process.env.GATEWAY_HOST_PORT ?? 8080}`;
const KIOSK_KEY = process.env.KIOSK_API_KEY ?? 'dev-only-kiosk-change-me';
const psql = (sql) => execFileSync('docker', ['compose', 'exec', '-T', 'central-db', 'psql', '-U',
  process.env.POSTGRES_USER ?? 'tmc_app', '-d', process.env.POSTGRES_DB ?? 'tmc', '-At', '-c', sql],
{ cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

async function api(path, { method = 'GET', body, kiosk = false } = {}) {
  const response = await fetch(`${GATEWAY}${path}`, {
    method, headers: { 'Content-Type': 'application/json', ...(kiosk ? { 'X-Kiosk-Api-Key': KIOSK_KEY } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10_000),
  });
  return { status: response.status, headers: response.headers, body: await response.json() };
}

function checksum(log) {
  const fields = ['TMC-AUDIT-V1', log.logId, log.timestamp, log.actorId, log.actorRole, log.actionType,
    log.targetResource, log.payloadSummary, log.status, log.previousHash];
  const chunks = fields.flatMap((value) => {
    const bytes = value == null ? null : Buffer.from(value, 'utf8');
    const length = Buffer.alloc(4);
    length.writeInt32BE(bytes?.length ?? -1);
    return bytes == null ? [length] : [length, bytes];
  });
  return createHash('sha256').update(Buffer.concat(chunks)).digest('hex');
}

function assertQr(body) {
  assert.equal(body.periodSeconds, 30);
  assert.deepEqual(body.qrPayload, {
    empId: body.employeeId, totp: body.totpCode,
    ts: Math.floor(Date.parse(body.issuedAt) / 1000), ver: body.secretVersion,
  });
  assert.match(body.qrPayload.totp, /^[0-9]{6}$/);
  assert.ok(!Object.hasOwn(body, 'sharedSecret'));
}

async function snapshot(factoryId) {
  const result = await api(`/api/v1/kiosk/whitelist?factoryId=${factoryId}`, { kiosk: true });
  assert.equal(result.status, 200);
  const header = result.headers.get('x-whitelist-version');
  assert.match(header ?? '', /^[1-9][0-9]*$/);
  const version = BigInt(header);
  assert.ok(version <= 9223372036854775807n);
  assert.ok(Array.isArray(result.body));
  assert.ok(result.body.every((row) => row.factoryId === factoryId));
  return { ...result, version };
}

test('USER QR: committed versions, scoped whitelist and independently verified audit chain', async () => {
  const id = `IT-${randomUUID().slice(0, 30)}`;
  // Fixture setup uses SQL because no HR write API exists; invalidate the factory cache atomically.
  psql(`BEGIN; UPDATE factories SET whitelist_version=whitelist_version+1 WHERE factory_id='BD';
        INSERT INTO employees (employee_id,full_name,department,factory_id,shared_secret)
        VALUES ('${id}','Integration Demo','Assembly','BD','DUMMY_INTEGRATION_KEY'); COMMIT;`);
  const initial = await snapshot('BD');
  const otherFactory = await snapshot('LA');
  const path = `/api/v1/qr/${id}`;
  const first = await api(path);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get('x-tmc-dev-only'), 'true');
  assert.equal(first.headers.get('cache-control'), 'no-store');
  assert.equal(first.body.secretVersion, 1);
  assertQr(first.body);
  assert.ok(/^[0-9]{6}$/.test(first.body.totpCode), 'six digit code');
  assert.ok(!Object.hasOwn(first.body, 'sharedSecret'), 'employee QR never contains a shared secret');
  const rotated = await api(`${path}/rotate`, { method: 'POST' });
  assert.equal(rotated.status, 200);
  assert.equal(rotated.body.secretVersion, 2);
  assertQr(rotated.body);
  const afterRotate = await snapshot('BD');
  assert.equal(afterRotate.version, initial.version + 1n);
  assert.equal(afterRotate.body.find((row) => row.employeeId === id)?.secretVersion, 2);
  assert.equal(psql(`SELECT secret_version FROM employees WHERE employee_id='${id}'`), '2');
  assert.equal(psql(`SELECT shared_secret <> 'DUMMY_INTEGRATION_KEY' FROM employees WHERE employee_id='${id}'`), 't');
  const revoked = await api(`${path}/revoke`, { method: 'POST', body: { actorId: 'it-demo', actorRole: 'HR_STAFF', reason: 'synthetic departure' } });
  assert.equal(revoked.status, 200);
  assert.equal(revoked.body.secretVersion, 3);
  const denied = await api(path);
  assert.equal(denied.status, 409);
  assert.equal(denied.body.code, 'QR_REVOKED');
  assert.equal((await api('/api/v1/kiosk/whitelist?factoryId=BD')).status, 403);
  const whitelist = await snapshot('BD');
  assert.equal(whitelist.version, afterRotate.version + 1n);
  assert.equal(whitelist.status, 200);
  assert.ok(whitelist.body.every((row) => row.factoryId === 'BD'));
  const row = whitelist.body.find((employee) => employee.employeeId === id);
  assert.ok(row?.qrStatus === 'REVOKED' && row?.secretVersion === 3, 'revocation is in the snapshot');
  const active = await api(`${path}/activate`, { method: 'POST' });
  assert.equal(active.status, 200);
  assert.equal(active.body.secretVersion, 4);
  assertQr(active.body);
  const afterActivate = await snapshot('BD');
  assert.equal(afterActivate.version, whitelist.version + 1n);
  assert.equal(afterActivate.body.find((row) => row.employeeId === id)?.qrStatus, 'ACTIVE');
  assert.equal((await snapshot('LA')).version, otherFactory.version);
  assert.equal(psql(`SELECT qr_status FROM employees WHERE employee_id='${id}'`), 'ACTIVE');
  assert.equal(psql(`SELECT count(*) FROM audit_logs WHERE target_resource='${id}'`), '3');
  const logs = await api('/api/v1/qr/audit-logs?limit=200');
  assert.equal(logs.status, 200);
  const ordered = logs.body.toReversed();
  for (let i = 0; i < ordered.length; i++) {
    assert.equal(checksum(ordered[i]), ordered[i].checksum, 'independent Node checksum');
    if (i > 0) assert.equal(ordered[i].previousHash, ordered[i - 1].checksum, 'audit chain continuity');
  }
  assert.equal((await api('/api/v1/qr/NO_SUCH_EMPLOYEE')).status, 404);
  assert.equal((await api('/api/v1/qr/generate')).status, 501);
});

test('USER QR: scoped empty snapshot, required factory and safe errors through gateway', async () => {
  const factoryId = `F-${randomUUID().slice(0, 10)}`;
  psql(`INSERT INTO factories (factory_id,name) VALUES ('${factoryId}','Integration empty factory')`);
  try {
    const empty = await snapshot(factoryId);
    assert.equal(empty.version, 1n);
    assert.deepEqual(empty.body, []);
    for (const suffix of ['', '?factoryId=', '?factoryId=%20', '?factoryId=BD!', '?factoryId=NO_SUCH_FACTORY']) {
      const result = await api(`/api/v1/kiosk/whitelist${suffix}`, { kiosk: true });
      assert.equal(result.status, suffix.endsWith('NO_SUCH_FACTORY') ? 404 : 400);
      assert.equal(result.body.code, result.status === 404 ? 'NOT_FOUND' : 'VALIDATION_ERROR');
      assert.ok(result.body.correlationId);
      assert.equal(result.headers.get('x-whitelist-version'), null);
    }
  } finally {
    psql(`DELETE FROM factories WHERE factory_id='${factoryId}'`);
  }
});
