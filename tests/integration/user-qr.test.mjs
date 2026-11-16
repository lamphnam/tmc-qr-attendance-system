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

test('USER QR: committed versions, scoped whitelist and independently verified audit chain', async () => {
  const id = `IT-${randomUUID().slice(0, 30)}`;
  psql(`INSERT INTO employees (employee_id,full_name,department,factory_id,shared_secret)
        VALUES ('${id}','Integration Demo','Assembly','BD','DUMMY_INTEGRATION_KEY')`);
  const path = `/api/v1/qr/${id}`;
  const first = await api(path);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get('x-tmc-dev-only'), 'true');
  assert.equal(first.headers.get('cache-control'), 'no-store');
  assert.equal(first.body.secretVersion, 1);
  assert.ok(/^[0-9]{6}$/.test(first.body.totpCode), 'six digit code');
  assert.ok(!Object.hasOwn(first.body, 'sharedSecret'), 'employee QR never contains a shared secret');
  const rotated = await api(`${path}/rotate`, { method: 'POST' });
  assert.equal(rotated.status, 200);
  assert.equal(rotated.body.secretVersion, 2);
  assert.equal(psql(`SELECT secret_version FROM employees WHERE employee_id='${id}'`), '2');
  assert.equal(psql(`SELECT shared_secret <> 'DUMMY_INTEGRATION_KEY' FROM employees WHERE employee_id='${id}'`), 't');
  const revoked = await api(`${path}/revoke`, { method: 'POST', body: { actorId: 'it-demo', actorRole: 'HR_STAFF', reason: 'synthetic departure' } });
  assert.equal(revoked.status, 200);
  assert.equal(revoked.body.secretVersion, 3);
  assert.equal((await api(path)).status, 403);
  assert.equal((await api('/api/v1/kiosk/whitelist')).status, 403);
  const whitelist = await api('/api/v1/kiosk/whitelist?factoryId=BD', { kiosk: true });
  assert.equal(whitelist.status, 200);
  assert.ok(whitelist.body.every((row) => row.factoryId === 'BD'));
  const row = whitelist.body.find((employee) => employee.employeeId === id);
  assert.ok(row?.qrStatus === 'REVOKED' && row?.secretVersion === 3, 'revocation is in the snapshot');
  const active = await api(`${path}/activate`, { method: 'POST' });
  assert.equal(active.status, 200);
  assert.equal(active.body.secretVersion, 4);
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
