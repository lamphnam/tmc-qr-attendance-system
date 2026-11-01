import { describe, expect, it } from 'vitest';
import { fetchHealth, interpretHealth } from './health';

describe('interpretHealth', () => {
  it('is UP only for HTTP 200 with status UP, and keeps actuator components', () => {
    const result = interpretHealth(200, { status: 'UP', components: { db: { status: 'UP' }, ping: { status: 'UP' } } });
    expect(result.state).toBe('UP');
    expect(result.components).toEqual({ db: 'UP', ping: 'UP' });
  });

  it('is DOWN when a reachable service reports DOWN (Spring answers 503 with JSON)', () => {
    const result = interpretHealth(503, { status: 'DOWN', components: { db: { status: 'DOWN' } } });
    expect(result.state).toBe('DOWN');
    expect(result.components.db).toBe('DOWN');
  });

  it('never trusts a JSON "UP" that came with a non-200 status', () => {
    expect(interpretHealth(500, { status: 'UP' }).state).toBe('DOWN');
  });

  it('is UNREACHABLE without a usable answer', () => {
    expect(interpretHealth(null, null).state).toBe('UNREACHABLE');
    expect(interpretHealth(502, '<html>bad gateway</html>').state).toBe('UNREACHABLE');
    expect(interpretHealth(200, { nope: true }).state).toBe('UNREACHABLE');
  });
});

describe('fetchHealth', () => {
  it('maps a network error to UNREACHABLE instead of throwing', async () => {
    const failing = (() => Promise.reject(new TypeError('network down'))) as unknown as typeof fetch;
    expect((await fetchHealth('/x', failing)).state).toBe('UNREACHABLE');
  });

  it('reads status and body from the response', async () => {
    const ok = (() => Promise.resolve(new Response(JSON.stringify({ status: 'UP' }), { status: 200 }))) as unknown as typeof fetch;
    expect((await fetchHealth('/x', ok)).state).toBe('UP');
  });

  it('survives an HTML error page', async () => {
    const html = (() => Promise.resolve(new Response('<html></html>', { status: 502 }))) as unknown as typeof fetch;
    expect((await fetchHealth('/x', html)).state).toBe('UNREACHABLE');
  });
});
