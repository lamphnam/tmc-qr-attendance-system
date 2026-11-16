import { apiFetch, type ApiResult } from './client';
import type {
  BatchSyncRequest,
  BatchSyncResponse,
  CentralEvent,
  QueueSnapshot,
  ScanRequest,
  SyncRunReport,
  SyncState,
  SyntheticEventRequest,
} from './types';

const enc = encodeURIComponent;

export const PATHS = {
  qr: (empId: string) => `/api/v1/qr/${enc(empId)}`,
  qrRotate: (empId: string) => `/api/v1/qr/${enc(empId)}/rotate`,
  qrRevoke: (empId: string) => `/api/v1/qr/${enc(empId)}/revoke`,
  scan: '/api/local/scan',
  kioskQueue: (limit: number) => `/api/dev/kiosk/queue?limit=${limit}`,
  kioskSyncState: '/api/dev/kiosk/sync/state',
  kioskOffline: '/api/dev/kiosk/sync/offline',
  kioskSyncRun: '/api/dev/kiosk/sync/run',
  kioskWhitelistSync: '/api/dev/kiosk/whitelist/sync',
  kioskSyntheticEvent: '/api/dev/kiosk/synthetic-event',
  centralEvents: (limit: number) => `/api/v1/attendance/events?limit=${limit}`,
  centralEvent: (attendanceId: string) => `/api/dev/attendance/events/${enc(attendanceId)}`,
  centralResend: '/api/dev/attendance/sync',
} as const;

type F = typeof fetch | undefined;

export const getQr = (empId: string, f?: F) => apiFetch<unknown>(PATHS.qr(empId), {}, f);
export const rotateQr = (empId: string, f?: F) => apiFetch<unknown>(PATHS.qrRotate(empId), { method: 'POST', body: {} }, f);
export const revokeQr = (empId: string, reason: string, f?: F) =>
  apiFetch<unknown>(PATHS.qrRevoke(empId), { method: 'POST', body: { reason } }, f);
export const scan = (body: ScanRequest, f?: F) => apiFetch<unknown>(PATHS.scan, { method: 'POST', body }, f);
export const getQueue = (limit: number, f?: F): Promise<ApiResult<QueueSnapshot>> =>
  apiFetch<QueueSnapshot>(PATHS.kioskQueue(limit), {}, f);
export const getSyncState = (f?: F) => apiFetch<SyncState>(PATHS.kioskSyncState, {}, f);
export const setOffline = (disabled: boolean, f?: F) =>
  apiFetch<SyncState>(PATHS.kioskOffline, { method: 'PUT', body: { disabled } }, f);
export const runSync = (f?: F) => apiFetch<SyncRunReport>(PATHS.kioskSyncRun, { method: 'POST' }, f);
export const syncWhitelist = (f?: F) => apiFetch<unknown>(PATHS.kioskWhitelistSync, { method: 'POST' }, f);
export const queueSyntheticEvent = (body: SyntheticEventRequest, f?: F) =>
  apiFetch<unknown>(PATHS.kioskSyntheticEvent, { method: 'POST', body }, f);
export const listCentralEvents = (limit: number, f?: F) => apiFetch<unknown>(PATHS.centralEvents(limit), {}, f);
export const getCentralEvent = (attendanceId: string, f?: F) =>
  apiFetch<CentralEvent>(PATHS.centralEvent(attendanceId), {}, f);
export const resendToCentral = (body: BatchSyncRequest, f?: F) =>
  apiFetch<BatchSyncResponse>(PATHS.centralResend, { method: 'POST', body }, f);
