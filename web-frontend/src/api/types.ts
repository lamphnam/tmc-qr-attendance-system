export type Direction = 'IN' | 'OUT';

export interface QrPayload {
  empId: string;
  totp: string;
  ts: number;
  ver: number;
}

export interface ScanRequest {
  gateId: string;
  direction: Direction;
  qrPayload: QrPayload;
}

export interface ScanResponse {
  result: string;
  attendanceId: string | null;
  gateCommand: string | null;
  processingTimeMs: number | null;
  reason: string | null;
}

export interface QueuedEvent {
  attendanceId: string;
  employeeId: string;
  factoryId: string;
  gateId: string;
  direction: string;
  occurredAt: string;
  scanResult: string;
  syncStatus: string;
  gateCommandStatus: string;
  gateCommandDetail: string | null;
  retryCount: number;
  nextRetryAt: string | null;
  lastError: string | null;
  devFixture: boolean;
}

export interface QueueSnapshot {
  counts: { pending: number; synced: number; failed: number };
  events: QueuedEvent[];
}

export interface SyncState {
  centralSyncDisabled: boolean;
}

export interface SyncRunReport {
  attempted: number;
  synced: number;
  failed: number;
  retryScheduled: number;
  skippedReason: string | null;
}

export interface SyntheticEventRequest {
  employeeId: string;
  gateId: string;
  direction: Direction;
}

export interface CentralEvent {
  attendanceId: string;
  employeeId: string;
  factoryId: string;
  gateId: string;
  direction: string;
  occurredAt: string;
  scanResult: string;
}

export interface BatchSyncRequest {
  kioskId: string;
  events: CentralEvent[];
}

export interface BatchSyncResponse {
  results: { attendanceId: string; status: string; reason: string | null }[];
  retryableIds: string[];
  rejectedIds: string[];
}
