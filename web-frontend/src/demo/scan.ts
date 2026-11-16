import { describeApiError, type ApiError } from '../api/client';
import type { ScanResponse } from '../api/types';
import type { Tone } from './format';

export interface ScanView {
  tone: Tone;
  title: string;
  detail: string;
  authorization: string | null;
  gateCommand: string | null;
  attendanceId: string | null;
  kioskMs: number | null;
  roundTripMs: number;
  correlationId: string | null;
}

const str = (v: unknown): string | null => (typeof v === 'string' && v.length > 0 ? v : null);

export function parseScanResponse(body: unknown): ScanResponse | null {
  if (typeof body !== 'object' || body === null) return null;
  const r = body as Record<string, unknown>;
  const result = str(r.result);
  if (result === null) return null;
  return {
    result,
    attendanceId: str(r.attendanceId),
    gateCommand: str(r.gateCommand),
    processingTimeMs: typeof r.processingTimeMs === 'number' && Number.isFinite(r.processingTimeMs) ? r.processingTimeMs : null,
    reason: str(r.reason),
  };
}

const REJECT_REASONS: Readonly<Record<string, string>> = {
  EXPIRED: 'Mã đã hết hạn hoặc sai. Lấy mã mới rồi quét lại.',
  INVALID_TOTP: 'Mã đã hết hạn hoặc sai. Lấy mã mới rồi quét lại.',
  REPLAYED: 'Mã QR này đã được sử dụng (chống quét lại).',
  REVOKED: 'QR của nhân viên đã bị khóa.',
  UNKNOWN_EMPLOYEE: 'Nhân viên không có trong danh sách của kiosk.',
};

function rejectDetail(reason: string | null): string {
  if (reason === null) return 'Vui lòng thử lại với mã mới hoặc liên hệ bảo vệ.';
  return Object.hasOwn(REJECT_REASONS, reason) ? REJECT_REASONS[reason] : reason;
}

const GATE_OK = 'SENT';
const GATE_PROBLEM = new Set(['GATE_ERROR', 'FAILED']);

export function describeScan(response: ScanResponse | null, roundTripMs: number): ScanView {
  const base = {
    attendanceId: response?.attendanceId ?? null,
    kioskMs: response?.processingTimeMs ?? null,
    roundTripMs,
    correlationId: null,
    authorization: response?.result ?? null,
    gateCommand: response?.gateCommand ?? null,
  };
  if (response === null) {
    return { ...base, tone: 'warn', title: 'Phản hồi không nhận ra', detail: 'Kiosk trả về dữ liệu không khớp API-CONTRACTS §3.' };
  }
  if (response.result === 'REJECTED') {
    return { ...base, tone: 'bad', title: 'Không chấp nhận', detail: rejectDetail(response.reason) };
  }
  if (response.result === 'ALLOWED') {
    if (response.gateCommand === GATE_OK) {
      return { ...base, tone: 'ok', title: 'Đã chấp nhận', detail: 'Lệnh mở đã gửi tới cổng xoay. Chưa xác nhận cổng mở thật.' };
    }
    if (response.gateCommand !== null && GATE_PROBLEM.has(response.gateCommand)) {
      return {
        ...base,
        tone: 'warn',
        title: 'Đã chấp nhận, nhưng cổng không phản hồi',
        detail: 'Lượt vào đã được ghi nhận. Vui lòng nhờ bảo vệ mở cổng.',
      };
    }
    return {
      ...base,
      tone: 'warn',
      title: 'Đã chấp nhận, chưa rõ trạng thái cổng',
      detail: `Trạng thái lệnh cổng nhận được: ${response.gateCommand ?? '(không có)'}.`,
    };
  }
  return {
    ...base,
    tone: 'warn',
    title: 'Kết quả không nhận ra',
    detail: `Kiosk trả về "${response.result}" (API-CONTRACTS §3 chỉ có ALLOWED / REJECTED).`,
  };
}

export function describeScanError(error: ApiError, roundTripMs: number): ScanView {
  const text = describeApiError(error);
  const title = error.kind === 'NOT_IMPLEMENTED' ? 'Kiosk chưa hỗ trợ quét mã (HTTP 501)' : text.title;
  return {
    tone: 'neutral',
    title,
    detail: text.detail ?? 'Chưa có lượt quét nào được ghi.',
    authorization: null,
    gateCommand: null,
    attendanceId: null,
    kioskMs: null,
    roundTripMs,
    correlationId: text.correlationId,
  };
}
