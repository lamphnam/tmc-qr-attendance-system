import { describe, expect, it } from 'vitest';
import type { ApiError, ApiResult } from '../api/client';
import type { CentralEvent, QueuedEvent } from '../api/types';
import {
  buildResendBody,
  joinWithQueue,
  parseCentralList,
  presenceFrom,
  rowsFromList,
  summarize,
  summarizeResend,
  type CentralRow,
  type Lookup,
} from './central';

const central = (attendanceId: string): CentralEvent => ({
  attendanceId,
  employeeId: 'EMP001',
  factoryId: 'BD',
  gateId: 'BD-G01',
  direction: 'IN',
  occurredAt: '2026-11-01T01:20:00.123456Z',
  scanResult: 'ALLOWED',
});

const queued = (attendanceId: string, syncStatus: string): QueuedEvent => ({
  ...central(attendanceId),
  syncStatus,
  gateCommandStatus: 'SENT',
  gateCommandDetail: null,
  retryCount: 0,
  nextRetryAt: null,
  lastError: null,
  devFixture: true,
});

const fail = (error: ApiError): ApiResult<CentralEvent> => ({ ok: false, error });
const http = (status: number, code: string | null): ApiError => ({ kind: 'HTTP', status, code, message: null, correlationId: null });

describe('presenceFrom', () => {
  it('200 means the row is in PostgreSQL', () => {
    expect(presenceFrom({ ok: true, status: 200, data: central('a') })).toEqual({ presence: 'PRESENT', event: central('a'), error: null });
  });

  it('404 with code NOT_FOUND means it is not there', () => {
    expect(presenceFrom(fail(http(404, 'NOT_FOUND')))).toEqual({ presence: 'ABSENT', event: null, error: null });
  });

  it('a 404 without that code is a missing route, so UNKNOWN and never ABSENT', () => {
    expect(presenceFrom(fail(http(404, null)))).toEqual({
      presence: 'UNKNOWN',
      event: null,
      error: 'Backend chưa có route này (HTTP 404)',
    });
  });

  it('a gateway 502 is UNKNOWN with the reason', () => {
    expect(presenceFrom(fail(http(502, 'UPSTREAM_UNAVAILABLE')))).toMatchObject({
      presence: 'UNKNOWN',
      error: 'Yêu cầu thất bại (HTTP 502 UPSTREAM_UNAVAILABLE)',
    });
  });
});

describe('joinWithQueue', () => {
  it('keeps the queue order and marks ids without a lookup as UNKNOWN', () => {
    const lookups = new Map<string, Lookup>([['b', { presence: 'PRESENT', event: central('b'), error: null }]]);
    const rows = joinWithQueue([queued('a', 'PENDING'), queued('b', 'SYNCED')], lookups);
    expect(rows.map((r) => [r.attendanceId, r.presence, r.kioskSyncStatus])).toEqual([
      ['a', 'UNKNOWN', 'PENDING'],
      ['b', 'PRESENT', 'SYNCED'],
    ]);
  });
});

describe('parseCentralList / rowsFromList', () => {
  it('accepts a bare array or {events: [...]}, and drops entries without an attendanceId', () => {
    expect(parseCentralList([central('a')])).toEqual([central('a')]);
    expect(parseCentralList({ events: [central('a'), { nope: 1 }, null] })).toEqual([central('a')]);
  });

  it('is null for anything else', () => {
    for (const body of [null, 'x', 42, { events: 'x' }, {}]) expect(parseCentralList(body)).toBeNull();
  });

  it('marks every listed row PRESENT and adds the kiosk state when the kiosk still has it', () => {
    const rows = rowsFromList([central('a'), central('z')], [queued('a', 'SYNCED')]);
    expect(rows.map((r) => [r.presence, r.kioskSyncStatus])).toEqual([
      ['PRESENT', 'SYNCED'],
      ['PRESENT', null],
    ]);
  });
});

describe('summarize', () => {
  it('counts presence and flags a SYNCED row that central does not have', () => {
    const row = (attendanceId: string, presence: CentralRow['presence'], kioskSyncStatus: string): CentralRow => ({
      attendanceId,
      presence,
      event: null,
      kioskSyncStatus,
      error: null,
    });
    expect(
      summarize([row('a', 'PRESENT', 'SYNCED'), row('b', 'ABSENT', 'PENDING'), row('c', 'ABSENT', 'SYNCED'), row('d', 'UNKNOWN', 'SYNCED')]),
    ).toEqual({ present: 1, absent: 2, unknown: 1, syncedButAbsent: ['c'] });
  });
});

describe('buildResendBody', () => {
  it('takes only SYNCED rows with exactly the 7 event fields, occurredAt unchanged', () => {
    const body = buildResendBody([queued('a', 'SYNCED'), queued('b', 'PENDING'), queued('c', 'FAILED')], 'BD-K01');
    expect(body).toEqual({ kioskId: 'BD-K01', events: [central('a')] });
    expect(Object.keys(body!.events[0]).sort()).toEqual(
      ['attendanceId', 'direction', 'employeeId', 'factoryId', 'gateId', 'occurredAt', 'scanResult'].sort(),
    );
  });

  it('is null when nothing is SYNCED', () => {
    expect(buildResendBody([queued('a', 'PENDING')], 'BD-K01')).toBeNull();
    expect(buildResendBody([], 'BD-K01')).toBeNull();
  });
});

describe('summarizeResend', () => {
  const result = (status: string) => ({ attendanceId: status, status, reason: null });

  it('says no row was created when everything already existed', () => {
    const r = { results: ['ALREADY_EXISTS', 'ALREADY_EXISTS', 'ALREADY_EXISTS', 'ALREADY_EXISTS'].map(result), retryableIds: [], rejectedIds: [] };
    expect(summarizeResend(r)).toBe('Gửi lại 4: 4 ALREADY_EXISTS, 0 STORED. Không tạo dòng mới.');
  });

  it('does not claim "no new row" when something was STORED or answered otherwise', () => {
    const r = { results: ['ALREADY_EXISTS', 'STORED', 'REJECTED_PERMANENT'].map(result), retryableIds: [], rejectedIds: [] };
    expect(summarizeResend(r)).toBe('Gửi lại 3: 1 ALREADY_EXISTS, 1 STORED, 1 trạng thái khác.');
  });
});
