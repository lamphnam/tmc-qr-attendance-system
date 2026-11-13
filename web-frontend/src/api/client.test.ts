import { describe, expect, it } from 'vitest';
import { apiFetch, describeApiError, isMissingRoute, type ApiError } from './client';

interface Call {
  url: string;
  init: RequestInit | undefined;
}

function fakeFetch(answer: () => Response) {
  const calls: Call[] = [];
  const impl = ((url: string, init?: RequestInit) => {
    calls.push({ url, init });
    return Promise.resolve(answer());
  }) as unknown as typeof fetch;
  return { impl, calls };
}

const json =
  (status: number, body: unknown, headers: Record<string, string> = {}) =>
  () =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

const headersOf = (call: Call) => call.init?.headers as Record<string, string>;

async function errorOf(answer: () => Response): Promise<ApiError> {
  const result = await apiFetch('/x', {}, fakeFetch(answer).impl);
  if (result.ok) throw new Error('expected a failed ApiResult');
  return result.error;
}

describe('apiFetch', () => {
  it('returns the parsed JSON of a 2xx and sends Content-Type only when there is a body', async () => {
    const { impl, calls } = fakeFetch(json(200, { a: 1 }));
    expect(await apiFetch<{ a: number }>('/x', {}, impl)).toEqual({ ok: true, status: 200, data: { a: 1 } });
    expect(calls[0].init?.method).toBe('GET');
    expect(headersOf(calls[0])['Content-Type']).toBeUndefined();
    expect(headersOf(calls[0]).Accept).toBe('application/json');

    await apiFetch('/y', { method: 'POST', body: { b: 2 } }, impl);
    expect(headersOf(calls[1])['Content-Type']).toBe('application/json');
    expect(calls[1].init?.body).toBe('{"b":2}');
  });

  it('treats an empty 2xx body (204) as data null', async () => {
    const { impl } = fakeFetch(() => new Response(null, { status: 204 }));
    expect(await apiFetch('/x', {}, impl)).toEqual({ ok: true, status: 204, data: null });
  });

  it('never passes a non-JSON 2xx off as data', async () => {
    const error = await errorOf(() => new Response('<html>ok</html>', { status: 200 }));
    expect(error).toMatchObject({ kind: 'HTTP', status: 200, code: 'INVALID_RESPONSE_BODY' });
  });

  it('maps 501 to NOT_IMPLEMENTED and keeps message and correlationId', async () => {
    const error = await errorOf(json(501, { code: 'NOT_IMPLEMENTED', message: 'owner: Khanh', correlationId: 'c-1' }));
    expect(error).toEqual({ kind: 'NOT_IMPLEMENTED', status: 501, message: 'owner: Khanh', correlationId: 'c-1' });
    expect(isMissingRoute(error)).toBe(true);
  });

  it('maps a 404 without body to HTTP 404 with code null, which counts as a missing route', async () => {
    const error = await errorOf(() => new Response('', { status: 404 }));
    expect(error).toEqual({ kind: 'HTTP', status: 404, code: null, message: null, correlationId: null });
    expect(isMissingRoute(error)).toBe(true);
  });

  it('keeps the code of a JSON error body', async () => {
    const error = await errorOf(json(404, { code: 'NOT_FOUND', message: 'no such attendanceId' }));
    expect(error).toMatchObject({ kind: 'HTTP', status: 404, code: 'NOT_FOUND', message: 'no such attendanceId' });
  });

  it('treats 405 as a missing route, but not a gateway 502', async () => {
    expect(isMissingRoute(await errorOf(() => new Response('', { status: 405 })))).toBe(true);
    const gateway = await errorOf(json(502, { code: 'UPSTREAM_UNAVAILABLE', message: 'upstream down' }));
    expect(isMissingRoute(gateway)).toBe(false);
    expect(describeApiError(gateway).title).toBe('Yêu cầu thất bại (HTTP 502 UPSTREAM_UNAVAILABLE)');
  });

  it('takes correlationId from the body, else from the X-Request-Id header', async () => {
    const fromBody = await errorOf(json(500, { code: 'X', correlationId: 'body-id' }, { 'X-Request-Id': 'header-id' }));
    expect(fromBody).toMatchObject({ correlationId: 'body-id' });
    const fromHeader = await errorOf(json(500, { code: 'X' }, { 'X-Request-Id': 'header-id' }));
    expect(fromHeader).toMatchObject({ correlationId: 'header-id' });
  });

  it('turns a rejected fetch into a NETWORK error instead of throwing', async () => {
    const failing = (() => Promise.reject(new TypeError('network down'))) as unknown as typeof fetch;
    expect(await apiFetch('/x', {}, failing)).toEqual({ ok: false, error: { kind: 'NETWORK', message: 'network down' } });
  });

  it('aborts after timeoutMs and says it timed out', async () => {
    const hanging = ((_url: string, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      })) as unknown as typeof fetch;
    const result = await apiFetch('/x', { timeoutMs: 5 }, hanging);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.kind).toBe('NETWORK');
      expect(result.error.kind === 'NETWORK' && result.error.message).toContain('timeout');
    }
  });
});

describe('describeApiError', () => {
  it('says the server is unreachable for NETWORK, without a correlation id', () => {
    expect(describeApiError({ kind: 'NETWORK', message: 'Failed to fetch' })).toEqual({
      title: 'Không kết nối được máy chủ',
      detail: 'Failed to fetch',
      correlationId: null,
    });
  });

  it('says HTTP 501 for NOT_IMPLEMENTED', () => {
    const text = describeApiError({ kind: 'NOT_IMPLEMENTED', status: 501, message: 'later', correlationId: 'c-9' });
    expect(text).toEqual({ title: 'Chưa triển khai ở backend (HTTP 501)', detail: 'later', correlationId: 'c-9' });
  });

  it('says the route does not exist yet for 404 and 405', () => {
    const base = { kind: 'HTTP' as const, code: null, message: null, correlationId: null };
    expect(describeApiError({ ...base, status: 404 }).title).toBe('Backend chưa có route này (HTTP 404)');
    expect(describeApiError({ ...base, status: 405 }).title).toBe('Backend chưa có route này (HTTP 405)');
  });

  it('names the status and the code of any other failure, and omits a missing code', () => {
    const base = { kind: 'HTTP' as const, message: 'm', correlationId: 'c' };
    expect(describeApiError({ ...base, status: 409, code: 'ATTENDANCE_ID_CONFLICT' }).title).toBe(
      'Yêu cầu thất bại (HTTP 409 ATTENDANCE_ID_CONFLICT)',
    );
    expect(describeApiError({ ...base, status: 500, code: null })).toEqual({
      title: 'Yêu cầu thất bại (HTTP 500)',
      detail: 'm',
      correlationId: 'c',
    });
  });
});
