import { describe, expect, it } from 'vitest';
import type { ApiError, ApiResult } from '../api/client';
import { needsRefresh, onFetched, onTick, parseQrResponse, secondsLeft, type QrCode, type QrState } from './qr';

const PAYLOAD = { empId: 'EMP001', totp: '482910', ts: 1761152000, ver: 1 };
const T0 = Date.parse('2026-11-01T12:00:00Z');

const ok = (data: unknown): ApiResult<unknown> => ({ ok: true, status: 200, data });
const fail = (error: ApiError): ApiResult<unknown> => ({ ok: false, error });
const http = (status: number, code: string | null = null): ApiError => ({ kind: 'HTTP', status, code, message: null, correlationId: null });
const codeUntil = (expiresAtMs: number): QrCode => ({ payload: PAYLOAD, expiresAtMs, periodSeconds: 30 });
const contract = (expiresAtMs: number) => ({ qrPayload: PAYLOAD, expiresAt: new Date(expiresAtMs).toISOString(), periodSeconds: 30 });

describe('parseQrResponse', () => {
  it('reads the API-CONTRACTS §1 shape and trusts its expiresAt', () => {
    expect(parseQrResponse(contract(T0 + 30_000))).toEqual({ payload: PAYLOAD, expiresAtMs: T0 + 30_000, periodSeconds: 30 });
  });

  it('reads the bare docx payload and ends the code at its 30 s step boundary', () => {
    expect(parseQrResponse(PAYLOAD)?.expiresAtMs).toBe(1761152010000);
  });

  it('uses a declared periodSeconds for the boundary, and falls back to 30 when it is not positive', () => {
    expect(parseQrResponse({ qrPayload: PAYLOAD, periodSeconds: 60 })?.expiresAtMs).toBe(1761152040000);
    expect(parseQrResponse({ qrPayload: PAYLOAD, periodSeconds: 0 })?.periodSeconds).toBe(30);
    expect(parseQrResponse({ qrPayload: PAYLOAD, periodSeconds: -5 })?.periodSeconds).toBe(30);
  });

  it('rejects a totp that is not exactly 6 digits', () => {
    for (const totp of ['48291', '4829100', 'abcdef', 482910]) expect(parseQrResponse({ ...PAYLOAD, totp })).toBeNull();
  });

  it('rejects a payload without an integer ver or a usable ts', () => {
    const { ver: _ver, ...withoutVer } = PAYLOAD;
    expect(parseQrResponse(withoutVer)).toBeNull();
    expect(parseQrResponse({ ...PAYLOAD, ver: 1.5 })).toBeNull();
    expect(parseQrResponse({ ...PAYLOAD, ts: '1761152000' })).toBeNull();
  });

  it('rejects anything that is not an object', () => {
    for (const body of [null, undefined, 'qr', 42, []]) expect(parseQrResponse(body)).toBeNull();
  });
});

describe('secondsLeft', () => {
  it('rounds up and never goes below zero', () => {
    const code = codeUntil(T0 + 17_200);
    expect(secondsLeft(code, T0)).toBe(18);
    expect(secondsLeft(code, T0 + 17_200)).toBe(0);
    expect(secondsLeft(code, T0 + 60_000)).toBe(0);
  });
});

describe('onFetched', () => {
  it('shows a fresh code as ready', () => {
    expect(onFetched({ phase: 'loading' }, ok(contract(T0 + 30_000)), T0)).toEqual({ phase: 'ready', code: codeUntil(T0 + 30_000) });
  });

  it('never shows a code that is already expired on arrival, and says why', () => {
    const next = onFetched({ phase: 'loading' }, ok(contract(T0 - 1_000)), T0);
    expect(next).toMatchObject({ phase: 'error', error: { code: 'QR_ALREADY_EXPIRED' } });
  });

  it('keeps the current code as stale when a refresh fails inside its window', () => {
    const error = http(502, 'UPSTREAM_UNAVAILABLE');
    const prev: QrState = { phase: 'ready', code: codeUntil(T0 + 10_000) };
    expect(onFetched(prev, fail(error), T0)).toEqual({ phase: 'stale', code: codeUntil(T0 + 10_000), error });
  });

  it('drops the code when a refresh fails after it ran out', () => {
    const error = http(502);
    const prev: QrState = { phase: 'ready', code: codeUntil(T0 - 1) };
    expect(onFetched(prev, fail(error), T0)).toEqual({ phase: 'expired', error });
    expect(onFetched({ phase: 'expired', error: null }, fail(error), T0)).toEqual({ phase: 'expired', error });
  });

  it('is an error when the very first fetch fails (route not built yet)', () => {
    expect(onFetched({ phase: 'loading' }, fail(http(404)), T0)).toEqual({ phase: 'error', error: http(404) });
  });

  it('locks the column on 409 QR_REVOKED, even while a code is still on screen', () => {
    const revoked = http(409, 'QR_REVOKED');
    const prev: QrState = { phase: 'ready', code: codeUntil(T0 + 20_000) };
    expect(onFetched(prev, fail(revoked), T0)).toEqual({ phase: 'locked', error: revoked });
  });

  it('reports a 2xx body that does not match §1 as INVALID_QR_PAYLOAD', () => {
    const next = onFetched({ phase: 'loading' }, ok({ hello: 'world' }), T0);
    expect(next).toMatchObject({ phase: 'error', error: { kind: 'HTTP', status: 200, code: 'INVALID_QR_PAYLOAD' } });
  });
});

describe('onTick', () => {
  it('expires a ready code when its time is up', () => {
    expect(onTick({ phase: 'ready', code: codeUntil(T0) }, T0)).toEqual({ phase: 'expired', error: null });
  });

  it('expires a stale code and keeps the refresh error', () => {
    const error = http(502);
    expect(onTick({ phase: 'stale', code: codeUntil(T0), error }, T0)).toEqual({ phase: 'expired', error });
  });

  it('returns the same object while nothing changes', () => {
    const ready: QrState = { phase: 'ready', code: codeUntil(T0 + 5_000) };
    expect(onTick(ready, T0)).toBe(ready);
    const loading: QrState = { phase: 'loading' };
    expect(onTick(loading, T0)).toBe(loading);
  });
});

describe('needsRefresh', () => {
  it('asks for a new code only when expired and at least 3 s after the last attempt', () => {
    const expired: QrState = { phase: 'expired', error: null };
    expect(needsRefresh(expired, T0 + 3_000, T0)).toBe(true);
    expect(needsRefresh(expired, T0 + 2_999, T0)).toBe(false);
  });

  it('never auto-retries ready, error or locked', () => {
    const states: QrState[] = [
      { phase: 'ready', code: codeUntil(T0) },
      { phase: 'error', error: http(404) },
      { phase: 'locked', error: http(409, 'QR_REVOKED') },
    ];
    for (const state of states) expect(needsRefresh(state, T0 + 60_000, T0)).toBe(false);
  });
});
