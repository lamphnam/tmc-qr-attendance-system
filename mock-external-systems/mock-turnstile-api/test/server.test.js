'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createServer } = require('../server.js');

async function withServer(fn, options) {
  const { server } = createServer(options);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const json = (base, path, init = {}) =>
  fetch(base + path, { ...init, headers: { 'Content-Type': 'application/json' }, body: init.body && JSON.stringify(init.body) });

test('health answers over real HTTP and says it is a mock', async () => {
  await withServer(async (base) => {
    const res = await fetch(`${base}/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: 'UP', service: 'mock-turnstile-api', mock: true });
  });
});

test('open accepts the command, records it, and never claims the gate physically opened', async () => {
  await withServer(async (base) => {
    const res = await json(base, '/gates/BD-G01/open', { method: 'POST', body: { kioskId: 'BD-K01' } });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.result, 'COMMAND_ACCEPTED');
    assert.equal(body.mock, true);
    assert.equal(body.physicalOpenConfirmed, false);

    const commands = await (await fetch(`${base}/gates/BD-G01/commands`)).json();
    assert.equal(commands.commands.length, 1);
    assert.equal(commands.commands[0].kioskId, 'BD-K01');

    const status = await (await fetch(`${base}/gates/BD-G01/status`)).json();
    assert.equal(status.commandsReceived, 1);
    assert.equal(status.state, 'UNKNOWN');
  });
});

test('commands are tracked per gate', async () => {
  await withServer(async (base) => {
    await json(base, '/gates/BD-G01/open', { method: 'POST', body: {} });
    await json(base, '/gates/BD-G02/open', { method: 'POST', body: {} });
    assert.equal((await (await fetch(`${base}/gates/BD-G02/status`)).json()).commandsReceived, 1);
    assert.equal((await (await fetch(`${base}/gates/LA-G01/status`)).json()).commandsReceived, 0);
  });
});

test('injected failure makes /open fail with that status, records nothing, and can be healed', async () => {
  await withServer(async (base) => {
    assert.equal((await json(base, '/mock/control/failure', { method: 'PUT', body: { status: 503 } })).status, 200);
    const failed = await json(base, '/gates/BD-G01/open', { method: 'POST', body: {} });
    assert.equal(failed.status, 503);
    assert.equal((await (await fetch(`${base}/gates/BD-G01/commands`)).json()).commands.length, 0);

    await json(base, '/mock/control/failure', { method: 'PUT', body: { status: 0 } });
    assert.equal((await json(base, '/gates/BD-G01/open', { method: 'POST', body: {} })).status, 200);
  });
});

test('failure injection can be preset from the environment-style option', async () => {
  await withServer(async (base) => {
    assert.equal((await json(base, '/gates/BD-G01/open', { method: 'POST', body: {} })).status, 500);
  }, { failureStatus: 500 });
});

test('bad input yields an error shape, not a crash', async () => {
  await withServer(async (base) => {
    const bad = await fetch(`${base}/gates/BD-G01/open`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{nope' });
    assert.equal(bad.status, 400);
    assert.equal((await bad.json()).code, 'INVALID_REQUEST');

    const invalid = await json(base, '/mock/control/failure', { method: 'PUT', body: { status: 200 } });
    assert.equal(invalid.status, 400);

    const unknown = await fetch(`${base}/nope`);
    assert.equal(unknown.status, 404);
  });
});
