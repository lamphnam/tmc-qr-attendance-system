import { describe, expect, it } from 'vitest';
import { formatSyncReport } from './kiosk';

const report = { attempted: 0, synced: 0, failed: 0, retryScheduled: 0, skippedReason: null };

describe('formatSyncReport', () => {
  it('says why a pass was skipped (e.g. simulated offline)', () => {
    expect(formatSyncReport({ ...report, skippedReason: 'central sync disabled (simulated offline)' })).toBe(
      'Không đồng bộ: central sync disabled (simulated offline).',
    );
  });

  it('says there was nothing to send', () => {
    expect(formatSyncReport(report)).toBe('Không có bản ghi PENDING nào cần gửi.');
  });

  it('repeats the kiosk counts exactly', () => {
    expect(formatSyncReport({ ...report, attempted: 3, synced: 2, failed: 0, retryScheduled: 1 })).toBe(
      'Đã gửi 3: 2 SYNCED, 0 FAILED, 1 hẹn thử lại.',
    );
  });
});
