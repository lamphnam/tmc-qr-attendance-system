import { describeApiError, type ApiResult } from '../api/client';
import type { BatchSyncRequest, BatchSyncResponse, CentralEvent, QueuedEvent } from '../api/types';

export type Presence = 'PRESENT' | 'ABSENT' | 'UNKNOWN';

export interface Lookup {
  presence: Presence;
  event: CentralEvent | null;
  error: string | null;
}

export interface CentralRow {
  attendanceId: string;
  presence: Presence;
  event: CentralEvent | null;
  kioskSyncStatus: string | null;
  error: string | null;
}

export function presenceFrom(result: ApiResult<CentralEvent>): Lookup {
  if (result.ok) return { presence: 'PRESENT', event: result.data, error: null };
  const e = result.error;
  if (e.kind === 'HTTP' && e.status === 404 && e.code === 'NOT_FOUND') return { presence: 'ABSENT', event: null, error: null };
  return { presence: 'UNKNOWN', event: null, error: describeApiError(e).title };
}

export function joinWithQueue(queue: readonly QueuedEvent[], lookups: ReadonlyMap<string, Lookup>): CentralRow[] {
  return queue.map((q) => {
    const l = lookups.get(q.attendanceId) ?? { presence: 'UNKNOWN' as const, event: null, error: null };
    return { attendanceId: q.attendanceId, presence: l.presence, event: l.event, kioskSyncStatus: q.syncStatus, error: l.error };
  });
}

export function parseCentralList(body: unknown): CentralEvent[] | null {
  const list = Array.isArray(body) ? body : (body as { events?: unknown } | null)?.events;
  if (!Array.isArray(list)) return null;
  return list.filter(
    (e): e is CentralEvent => typeof e === 'object' && e !== null && typeof (e as CentralEvent).attendanceId === 'string',
  );
}

export function rowsFromList(list: readonly CentralEvent[], queue: readonly QueuedEvent[]): CentralRow[] {
  const status = new Map(queue.map((q) => [q.attendanceId, q.syncStatus]));
  return list.map((e) => ({
    attendanceId: e.attendanceId,
    presence: 'PRESENT',
    event: e,
    kioskSyncStatus: status.get(e.attendanceId) ?? null,
    error: null,
  }));
}

export interface CentralSummary {
  present: number;
  absent: number;
  unknown: number;
  syncedButAbsent: string[];
}

export function summarize(rows: readonly CentralRow[]): CentralSummary {
  return {
    present: rows.filter((r) => r.presence === 'PRESENT').length,
    absent: rows.filter((r) => r.presence === 'ABSENT').length,
    unknown: rows.filter((r) => r.presence === 'UNKNOWN').length,
    syncedButAbsent: rows.filter((r) => r.kioskSyncStatus === 'SYNCED' && r.presence === 'ABSENT').map((r) => r.attendanceId),
  };
}

export function buildResendBody(queue: readonly QueuedEvent[], kioskId: string): BatchSyncRequest | null {
  const events = queue
    .filter((q) => q.syncStatus === 'SYNCED')
    .map(({ attendanceId, employeeId, factoryId, gateId, direction, occurredAt, scanResult }) => ({
      attendanceId,
      employeeId,
      factoryId,
      gateId,
      direction,
      occurredAt,
      scanResult,
    }));
  return events.length === 0 ? null : { kioskId, events };
}

export function summarizeResend(r: BatchSyncResponse): string {
  const count = (s: string) => r.results.filter((x) => x.status === s).length;
  const stored = count('STORED');
  const already = count('ALREADY_EXISTS');
  const other = r.results.length - stored - already;
  const tail = stored === 0 && other === 0 ? ' Không tạo dòng mới.' : '';
  return `Gửi lại ${r.results.length}: ${already} ALREADY_EXISTS, ${stored} STORED${other > 0 ? `, ${other} trạng thái khác` : ''}.${tail}`;
}
