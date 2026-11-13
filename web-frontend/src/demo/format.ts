export type Tone = 'ok' | 'bad' | 'warn' | 'info' | 'neutral';

const TONES: Readonly<Record<string, Tone>> = {
  ALLOWED: 'ok',
  REJECTED: 'bad',
  SENT: 'ok',
  GATE_ERROR: 'bad',
  FAILED: 'bad',
  NOT_ATTEMPTED: 'neutral',
  UNSUPPORTED: 'neutral',
  PENDING: 'warn',
  SYNCED: 'ok',
  STORED: 'ok',
  ALREADY_EXISTS: 'info',
  RETRYABLE: 'warn',
  REJECTED_PERMANENT: 'bad',
};

export function toneFor(value: string): Tone {
  return Object.hasOwn(TONES, value) ? TONES[value] : 'warn';
}

export function formatClock(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

export const shortId = (id: string): string => id.slice(0, 8);
