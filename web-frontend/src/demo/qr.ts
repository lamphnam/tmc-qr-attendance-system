import type { ApiError, ApiResult } from '../api/client';
import type { QrPayload } from '../api/types';

export const DEFAULT_PERIOD_SECONDS = 30;
export const EXPIRED_RETRY_MS = 3000;

export interface QrCode {
  payload: QrPayload;
  expiresAtMs: number;
  periodSeconds: number;
}

export type QrState =
  | { phase: 'loading' }
  | { phase: 'ready'; code: QrCode }
  | { phase: 'stale'; code: QrCode; error: ApiError }
  | { phase: 'expired'; error: ApiError | null }
  | { phase: 'locked'; error: ApiError }
  | { phase: 'error'; error: ApiError };

function payloadFrom(value: unknown): QrPayload | null {
  if (typeof value !== 'object' || value === null) return null;
  const v = value as Record<string, unknown>;
  const ok =
    typeof v.empId === 'string' &&
    v.empId.length > 0 &&
    typeof v.totp === 'string' &&
    /^\d{6}$/.test(v.totp) &&
    typeof v.ts === 'number' &&
    Number.isFinite(v.ts) &&
    typeof v.ver === 'number' &&
    Number.isInteger(v.ver);
  return ok ? { empId: v.empId as string, totp: v.totp as string, ts: v.ts as number, ver: v.ver as number } : null;
}

export function parseQrResponse(body: unknown): QrCode | null {
  const record = (typeof body === 'object' && body !== null ? body : {}) as Record<string, unknown>;
  const payload = payloadFrom(record.qrPayload) ?? payloadFrom(body);
  if (payload === null) return null;
  const period =
    typeof record.periodSeconds === 'number' && record.periodSeconds > 0 ? record.periodSeconds : DEFAULT_PERIOD_SECONDS;
  const declared = typeof record.expiresAt === 'string' ? Date.parse(record.expiresAt) : Number.NaN;
  const expiresAtMs = Number.isFinite(declared) ? declared : (Math.floor(payload.ts / period) + 1) * period * 1000;
  return { payload, expiresAtMs, periodSeconds: period };
}

export function secondsLeft(code: QrCode, nowMs: number): number {
  return Math.max(0, Math.ceil((code.expiresAtMs - nowMs) / 1000));
}

export function isRevoked(error: ApiError): boolean {
  return error.kind === 'HTTP' && (error.code === 'QR_REVOKED' || error.code === 'QR_LOCKED');
}

const invalidBody = (status: number): ApiError => ({
  kind: 'HTTP',
  status,
  code: 'INVALID_QR_PAYLOAD',
  message: 'the QR response does not match API-CONTRACTS §1',
  correlationId: null,
});

const expiredOnArrival = (status: number): ApiError => ({
  kind: 'HTTP',
  status,
  code: 'QR_ALREADY_EXPIRED',
  message: 'the server sent a code whose expiresAt has already passed (check the clocks)',
  correlationId: null,
});

function liveCode(state: QrState, nowMs: number): QrCode | null {
  if (state.phase !== 'ready' && state.phase !== 'stale') return null;
  return secondsLeft(state.code, nowMs) > 0 ? state.code : null;
}

export function onFetched(prev: QrState, result: ApiResult<unknown>, nowMs: number): QrState {
  let error: ApiError;
  if (result.ok) {
    const code = parseQrResponse(result.data);
    if (code !== null && secondsLeft(code, nowMs) > 0) return { phase: 'ready', code };
    error = code === null ? invalidBody(result.status) : expiredOnArrival(result.status);
  } else {
    error = result.error;
  }
  if (isRevoked(error)) return { phase: 'locked', error };
  const still = liveCode(prev, nowMs);
  if (still !== null) return { phase: 'stale', code: still, error };
  if (prev.phase === 'expired' || prev.phase === 'ready' || prev.phase === 'stale') return { phase: 'expired', error };
  return { phase: 'error', error };
}

export function onTick(prev: QrState, nowMs: number): QrState {
  if ((prev.phase === 'ready' || prev.phase === 'stale') && secondsLeft(prev.code, nowMs) === 0) {
    return { phase: 'expired', error: prev.phase === 'stale' ? prev.error : null };
  }
  return prev;
}

export function needsRefresh(state: QrState, nowMs: number, lastAttemptMs: number): boolean {
  return state.phase === 'expired' && nowMs - lastAttemptMs >= EXPIRED_RETRY_MS;
}
