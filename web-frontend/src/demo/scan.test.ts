import { describe, expect, it } from 'vitest';
import type { ScanResponse } from '../api/types';
import { describeScan, describeScanError, parseScanResponse, type ScanView } from './scan';

const BANNED = /cổng đã mở|đã mở cổng|gate opened/i;

const response = (overrides: Partial<ScanResponse>): ScanResponse => ({
  result: 'ALLOWED',
  attendanceId: 'c5b0ff1a-2c59-4333-ac72-1b41b84e84a1',
  gateCommand: 'SENT',
  processingTimeMs: 25,
  reason: null,
  ...overrides,
});

const views: ScanView[] = [];
const remember = (view: ScanView) => {
  views.push(view);
  return view;
};

describe('parseScanResponse', () => {
  it('reads the §3 shape', () => {
    expect(parseScanResponse({ result: 'ALLOWED', attendanceId: 'a-1', gateCommand: 'SENT', processingTimeMs: 25 })).toEqual({
      result: 'ALLOWED',
      attendanceId: 'a-1',
      gateCommand: 'SENT',
      processingTimeMs: 25,
      reason: null,
    });
  });

  it('is null without a result, and drops a non-numeric processing time', () => {
    expect(parseScanResponse({ attendanceId: 'a-1' })).toBeNull();
    expect(parseScanResponse('ALLOWED')).toBeNull();
    expect(parseScanResponse({ result: 'ALLOWED', processingTimeMs: '25' })?.processingTimeMs).toBeNull();
  });
});

describe('describeScan', () => {
  it('ALLOWED + SENT: accepted, command sent, physical opening not confirmed', () => {
    const view = remember(describeScan(response({}), 42));
    expect(view).toMatchObject({ tone: 'ok', title: 'Đã chấp nhận', authorization: 'ALLOWED', gateCommand: 'SENT', kioskMs: 25, roundTripMs: 42 });
    expect(view.detail).toContain('Chưa xác nhận cổng mở thật');
  });

  it('ALLOWED + GATE_ERROR (§3) and ALLOWED + FAILED (kiosk enum) both say the gate did not respond', () => {
    for (const gateCommand of ['GATE_ERROR', 'FAILED']) {
      const view = remember(describeScan(response({ gateCommand }), 10));
      expect(view.tone).toBe('warn');
      expect(view.title).toBe('Đã chấp nhận, nhưng cổng không phản hồi');
    }
  });

  it('ALLOWED without a gate command says the gate state is unknown', () => {
    const view = remember(describeScan(response({ gateCommand: null }), 10));
    expect(view.title).toBe('Đã chấp nhận, chưa rõ trạng thái cổng');
    expect(view.detail).toContain('(không có)');
  });

  it('REJECTED + REPLAYED explains anti-replay', () => {
    const view = remember(describeScan(response({ result: 'REJECTED', gateCommand: null, reason: 'REPLAYED' }), 10));
    expect(view).toMatchObject({ tone: 'bad', title: 'Không chấp nhận', detail: 'Mã QR này đã được sử dụng (chống quét lại).' });
  });

  it('REJECTED with an unknown reason shows it verbatim, even one named like an Object.prototype key', () => {
    expect(remember(describeScan(response({ result: 'REJECTED', reason: 'GATE_CLOSED_FOR_MAINTENANCE' }), 1)).detail).toBe(
      'GATE_CLOSED_FOR_MAINTENANCE',
    );
    expect(remember(describeScan(response({ result: 'REJECTED', reason: 'toString' }), 1)).detail).toBe('toString');
  });

  it('REJECTED without a reason asks to retry', () => {
    expect(remember(describeScan(response({ result: 'REJECTED', reason: null }), 1)).detail).toContain('thử lại');
  });

  it('an unknown result is shown verbatim in warn', () => {
    const view = remember(describeScan(response({ result: 'MAYBE' }), 1));
    expect(view).toMatchObject({ tone: 'warn', title: 'Kết quả không nhận ra', authorization: 'MAYBE' });
    expect(view.detail).toContain('"MAYBE"');
  });

  it('an unparsable response says so', () => {
    const view = remember(describeScan(null, 1));
    expect(view).toMatchObject({ tone: 'warn', title: 'Phản hồi không nhận ra', authorization: null, gateCommand: null });
  });
});

describe('describeScanError', () => {
  it('names HTTP 501 as "the kiosk does not support scanning yet" and keeps the correlation id', () => {
    const view = remember(
      describeScanError({ kind: 'NOT_IMPLEMENTED', status: 501, message: 'owner: Khanh', correlationId: 'c-1' }, 7),
    );
    expect(view).toMatchObject({ tone: 'neutral', title: 'Kiosk chưa hỗ trợ quét mã (HTTP 501)', detail: 'owner: Khanh', correlationId: 'c-1' });
  });

  it('falls back to "nothing was recorded" when the error has no detail', () => {
    const view = remember(describeScanError({ kind: 'HTTP', status: 502, code: null, message: null, correlationId: null }, 7));
    expect(view.title).toBe('Yêu cầu thất bại (HTTP 502)');
    expect(view.detail).toBe('Chưa có lượt quét nào được ghi.');
  });
});

describe('wording', () => {
  it('no scan view ever claims that a gate opened', () => {
    expect(views.length).toBeGreaterThanOrEqual(10);
    for (const view of views) expect(JSON.stringify(view)).not.toMatch(BANNED);
  });
});
