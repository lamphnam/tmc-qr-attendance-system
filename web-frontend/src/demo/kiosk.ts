import type { SyncRunReport } from '../api/types';

export function formatSyncReport(r: SyncRunReport): string {
  if (r.skippedReason) return `Không đồng bộ: ${r.skippedReason}.`;
  if (r.attempted === 0) return 'Không có bản ghi PENDING nào cần gửi.';
  return `Đã gửi ${r.attempted}: ${r.synced} SYNCED, ${r.failed} FAILED, ${r.retryScheduled} hẹn thử lại.`;
}
