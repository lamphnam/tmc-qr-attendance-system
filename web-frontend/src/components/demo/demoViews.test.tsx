import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ApiError } from '../../api/client';
import type { CentralEvent, QueuedEvent } from '../../api/types';
import type { CentralRow } from '../../demo/central';
import { DEMO_EMPLOYEES, DEMO_GATES } from '../../demo/config';
import type { QrState } from '../../demo/qr';
import { describeScan, describeScanError } from '../../demo/scan';
import CentralColumn, { type CentralColumnProps } from './CentralColumn';
import EmployeeColumn, { type EmployeeColumnProps } from './EmployeeColumn';
import KioskColumn, { type KioskColumnProps } from './KioskColumn';

const noop = () => {};
const T0 = Date.parse('2026-11-01T12:00:00Z');
const PAYLOAD = { empId: 'EMP001', totp: '482910', ts: 1761152000, ver: 1 };
const BANNED = /cổng đã mở|đã mở cổng/i;
const http = (status: number, code: string | null = null): ApiError => ({ kind: 'HTTP', status, code, message: null, correlationId: null });
const ready = (expiresAtMs: number): QrState => ({ phase: 'ready', code: { payload: PAYLOAD, expiresAtMs, periodSeconds: 30 } });

const employee = (overrides: Partial<EmployeeColumnProps> = {}) =>
  renderToString(
    <EmployeeColumn
      employees={DEMO_EMPLOYEES}
      selectedId="EMP001"
      onSelect={noop}
      qr={{ phase: 'loading' }}
      nowMs={T0}
      fetching={false}
      onRetry={noop}
      action={{ busy: null, message: null, error: null }}
      onRotate={noop}
      onRevoke={noop}
      {...overrides}
    />,
  );

const kioskHtml: string[] = [];
const kiosk = (overrides: Partial<KioskColumnProps> = {}) => {
  const html = renderToString(
    <KioskColumn
      gates={DEMO_GATES}
      gateId="BD-G01"
      onGate={noop}
      canScan
      scanning={false}
      onScan={noop}
      scanView={null}
      offline={false}
      onToggleOffline={noop}
      busy={null}
      onSyncNow={noop}
      onSyncWhitelist={noop}
      onSynthetic={noop}
      message={{ text: null, error: null }}
      queue={{ counts: { pending: 0, synced: 0, failed: 0 }, events: [] }}
      queueError={null}
      {...overrides}
    />,
  );
  kioskHtml.push(html);
  return html;
};

const central = (overrides: Partial<CentralColumnProps> = {}) =>
  renderToString(
    <CentralColumn
      source="lookup"
      rows={[]}
      loaded
      error={null}
      resendBusy={false}
      canResend={false}
      onResend={noop}
      resendMessage={{ text: null, error: null }}
      {...overrides}
    />,
  );

const event = (attendanceId: string): CentralEvent => ({
  attendanceId,
  employeeId: 'EMP001',
  factoryId: 'BD',
  gateId: 'BD-G01',
  direction: 'IN',
  occurredAt: '2026-11-01T01:20:00Z',
  scanResult: 'ALLOWED',
});

const queued = (attendanceId: string, syncStatus: string): QueuedEvent => ({
  ...event(attendanceId),
  syncStatus,
  gateCommandStatus: 'SENT',
  gateCommandDetail: null,
  retryCount: 0,
  nextRetryAt: null,
  lastError: null,
  devFixture: true,
});

const row = (attendanceId: string, presence: CentralRow['presence'], kioskSyncStatus: string): CentralRow => ({
  attendanceId,
  presence,
  event: presence === 'PRESENT' ? event(attendanceId) : null,
  kioskSyncStatus,
  error: null,
});

describe('EmployeeColumn', () => {
  it('loading: says it is fetching a code', () => {
    expect(employee()).toContain('Đang lấy mã…');
  });

  it('ready: draws the code as a QR image with the countdown, never as digits or JSON text', () => {
    const html = employee({ qr: ready(T0 + 17_500) });
    expect(html).toMatch(/<div class="tmc-qrplate" role="img" aria-label="Mã QR vào cổng hiện tại"><svg[^>]*aria-hidden="true"/);
    expect(html).not.toContain('482910');
    expect(html).not.toContain('&quot;totp&quot;');
    expect(html).toContain('18 giây');
    expect(html).toContain('aria-valuenow="18"');
  });

  it('ready: a new code draws a different QR image', () => {
    const qrOf = (totp: string) =>
      employee({ qr: { phase: 'ready', code: { payload: { ...PAYLOAD, totp }, expiresAtMs: T0 + 17_500, periodSeconds: 30 } } }).match(
        /<svg[\s\S]*?<\/svg>/,
      )?.[0];
    expect(qrOf('482910')).toBeDefined();
    expect(qrOf('482910')).toBe(qrOf('482910'));
    expect(qrOf('482910')).not.toBe(qrOf('118204'));
  });

  it('stale: keeps the still-valid QR and warns that the refresh failed', () => {
    const html = employee({ qr: { phase: 'stale', code: { payload: PAYLOAD, expiresAtMs: T0 + 9_000, periodSeconds: 30 }, error: http(502) } });
    expect(html).toContain('class="tmc-qrplate"');
    expect(html).toContain('Không lấy được mã mới. Mã hiện tại hết hạn sau 9 giây.');
  });

  it('expired: the old QR is gone from the screen', () => {
    const html = employee({ qr: { phase: 'expired', error: null }, fetching: true });
    expect(html).toContain('Không có mã hợp lệ');
    expect(html).toContain('Đang lấy mã mới…');
    expect(html).not.toContain('tmc-qrplate');
  });

  it('locked: tells the employee to contact HR', () => {
    expect(employee({ qr: { phase: 'locked', error: http(409, 'QR_REVOKED') } })).toContain('đã bị khóa');
  });

  it('error: a missing QR route is named as such, with a retry button', () => {
    const html = employee({ qr: { phase: 'error', error: http(404) } });
    expect(html).toContain('Backend chưa có route này (HTTP 404)');
    expect(html).toContain('Thử lại');
  });

  it('busy rotate: labels the button and disables both actions', () => {
    const html = employee({ action: { busy: 'rotate', message: null, error: null } });
    expect(html).toContain('Đang đổi khóa…');
    expect(html.match(/disabled=""/g)?.length).toBe(2);
  });
});

describe('KioskColumn', () => {
  it('no scan yet: invites a first scan', () => {
    expect(kiosk()).toContain('Chưa có lượt quét');
  });

  it('ALLOWED + SENT: both outcomes as badges, and opening is not confirmed', () => {
    const html = kiosk({ scanView: describeScan({ result: 'ALLOWED', attendanceId: 'c5b0ff1a-2c59', gateCommand: 'SENT', processingTimeMs: 25, reason: null }, 42) });
    for (const text of ['Đã chấp nhận', 'ALLOWED', 'SENT', 'Chưa xác nhận cổng mở thật', 'Mã lượt c5b0ff1a', 'Kiosk xử lý 25 ms']) {
      expect(html).toContain(text);
    }
  });

  it('ALLOWED + GATE_ERROR: says the gate did not respond', () => {
    const html = kiosk({ scanView: describeScan({ result: 'ALLOWED', attendanceId: null, gateCommand: 'GATE_ERROR', processingTimeMs: null, reason: null }, 5) });
    expect(html).toContain('cổng không phản hồi');
  });

  it('HTTP 501: says the kiosk does not support scanning yet', () => {
    const html = kiosk({ scanView: describeScanError({ kind: 'NOT_IMPLEMENTED', status: 501, message: null, correlationId: null }, 5) });
    expect(html).toContain('Kiosk chưa hỗ trợ quét mã (HTTP 501)');
  });

  it('offline: the switch is off and the warning is shown', () => {
    const html = kiosk({ offline: true });
    expect(html).toContain('Ngoại tuyến');
    expect(html).toContain('aria-checked="false"');
    expect(html).toContain('Mất kết nối – lượt quét được lưu và gửi sau');
  });

  it('unknown sync state: the switch is disabled', () => {
    const html = kiosk({ offline: null });
    expect(html).toContain('Không rõ');
    expect(html).toMatch(/class="k-sw"[^>]*disabled=""/);
  });

  it('a PENDING row is yellow, labelled for the mobile card layout, and counted', () => {
    const html = kiosk({ queue: { counts: { pending: 1, synced: 0, failed: 0 }, events: [queued('a-1', 'PENDING')] } });
    expect(html).toContain('tmc-badge-warn');
    expect(html).toContain('data-label="Đồng bộ"');
    expect(html).toContain('PENDING 1 · SYNCED 0 · FAILED 0');
  });

  it('a queue row merges employee with gate and retry count with the last error', () => {
    const failing = { ...queued('a-2', 'PENDING'), retryCount: 2, lastError: 'CENTRAL_UNREACHABLE' };
    const html = kiosk({ queue: { counts: { pending: 1, synced: 0, failed: 0 }, events: [failing] } });
    expect(html).toContain('<span>EMP001</span><span class="tmc-small">BD-G01 IN</span>');
    expect(html).toContain('<span>2</span><span class="tmc-small">CENTRAL_UNREACHABLE</span>');
  });

  it('no valid code: the scan button is disabled', () => {
    expect(kiosk({ canScan: false })).toMatch(/<button[^>]*disabled=""[^>]*>Quét mã hiện tại vào cổng<\/button>/);
    expect(kiosk({ canScan: true })).not.toMatch(/<button[^>]*disabled=""[^>]*>Quét mã hiện tại vào cổng<\/button>/);
  });

  it('never claims that a gate opened, in any state rendered above', () => {
    expect(kioskHtml.length).toBeGreaterThanOrEqual(8);
    for (const html of kioskHtml) expect(html).not.toMatch(BANNED);
  });
});

describe('CentralColumn', () => {
  it('lookup: counts what is and is not in PostgreSQL', () => {
    const html = central({ rows: [row('a', 'PRESENT', 'SYNCED'), row('b', 'ABSENT', 'PENDING')] });
    expect(html).toContain('Có trong PostgreSQL: 1');
    expect(html).toContain('Chưa có: 1');
  });

  it('raises an alert when the kiosk says SYNCED but central does not have the row', () => {
    expect(central({ rows: [row('c5b0ff1a-x', 'ABSENT', 'SYNCED')] })).toContain('Kiosk ghi SYNCED nhưng trung tâm không có: c5b0ff1a');
  });

  it('an empty table names what is empty for each source', () => {
    expect(central({ source: 'list' })).toContain('PostgreSQL chưa có bản ghi nào.');
    expect(central({ source: 'lookup' })).toContain('Chưa có bản ghi nào ở kiosk để đối chiếu.');
  });

  it('shows no counts before the first load', () => {
    expect(central({ loaded: false })).not.toContain('Có trong PostgreSQL');
  });

  it('an error with no rows shows only the error, never "nothing to compare"', () => {
    const html = central({ error: http(502, 'UPSTREAM_UNAVAILABLE') });
    expect(html).toContain('Yêu cầu thất bại (HTTP 502 UPSTREAM_UNAVAILABLE)');
    expect(html).not.toContain('Chưa có bản ghi nào ở kiosk để đối chiếu.');
  });

  it('employee and gate come from PostgreSQL only: an absent row shows a dash, not the kiosk copy', () => {
    const html = central({ rows: [row('a', 'PRESENT', 'SYNCED'), row('b', 'ABSENT', 'PENDING')] });
    expect(html).toContain('<span>EMP001</span><span class="tmc-small">BD-G01 IN</span>');
    expect(html).toContain('<td data-label="Nhân viên · cổng">—</td>');
    expect(html.match(/BD-G01 IN/g)).toHaveLength(1);
  });

  it('counts unknown rows separately', () => {
    expect(central({ rows: [row('a', 'UNKNOWN', 'SYNCED')] })).toContain('Không rõ: 1');
  });
});
