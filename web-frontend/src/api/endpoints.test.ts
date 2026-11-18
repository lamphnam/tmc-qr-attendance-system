import { describe, expect, it } from 'vitest';
import { activateQr, getCentralEvent, getQr, getQueue, revokeQr, rotateQr, runSync, scan, setOffline } from './endpoints';

interface Call {
  url: string;
  init: RequestInit | undefined;
}

function recorder() {
  const calls: Call[] = [];
  const impl = ((url: string, init?: RequestInit) => {
    calls.push({ url, init });
    return Promise.resolve(new Response('{}', { status: 200 }));
  }) as unknown as typeof fetch;
  return { impl, calls };
}

describe('endpoints', () => {
  it('encodes the employee id into the QR path and reads it with GET', async () => {
    const { impl, calls } = recorder();
    await getQr('EMP 1', impl);
    expect(calls[0].url).toBe('/api/v1/qr/EMP%201');
    expect(calls[0].init?.method).toBe('GET');
  });

  it('rotate, revoke and activate use the QR lifecycle POST routes', async () => {
    const { impl, calls } = recorder();
    await rotateQr('EMP001', impl);
    await revokeQr('EMP001', 'left the company', impl);
    await activateQr('EMP001', impl);
    expect(calls.map((c) => [c.init?.method, c.url])).toEqual([
      ['POST', '/api/v1/qr/EMP001/rotate'],
      ['POST', '/api/v1/qr/EMP001/revoke'],
      ['POST', '/api/v1/qr/EMP001/activate'],
    ]);
    expect(JSON.parse(String(calls[1].init?.body))).toEqual({ reason: 'left the company' });
  });

  it('setOffline is a PUT with {"disabled": true}', async () => {
    const { impl, calls } = recorder();
    await setOffline(true, impl);
    expect(calls[0].url).toBe('/api/dev/kiosk/sync/offline');
    expect(calls[0].init?.method).toBe('PUT');
    expect(calls[0].init?.body).toBe('{"disabled":true}');
  });

  it('scan POSTs the whole request to /api/local/scan', async () => {
    const { impl, calls } = recorder();
    const body = { gateId: 'BD-G01', direction: 'IN' as const, qrPayload: { empId: 'EMP001', totp: '482910', ts: 1761152000, ver: 1 } };
    await scan(body, impl);
    expect(calls[0].url).toBe('/api/local/scan');
    expect(calls[0].init?.method).toBe('POST');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual(body);
  });

  it('builds the kiosk queue and central lookup paths, encoding the attendance id', async () => {
    const { impl, calls } = recorder();
    await getQueue(15, impl);
    await getCentralEvent('a/b', impl);
    expect(calls.map((c) => c.url)).toEqual(['/api/dev/kiosk/queue?limit=15', '/api/dev/attendance/events/a%2Fb']);
  });

  it('runs a sync pass with a POST that has no body', async () => {
    const { impl, calls } = recorder();
    await runSync(impl);
    expect(calls[0]).toMatchObject({ url: '/api/dev/kiosk/sync/run', init: { method: 'POST', body: undefined } });
  });
});
