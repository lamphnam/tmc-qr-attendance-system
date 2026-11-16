import { describe, expect, it } from 'vitest';
import { formatClock, shortId, toneFor } from './format';

describe('toneFor', () => {
  it('colours queue states as the M2 brief asks: SYNCED green, PENDING yellow, FAILED red', () => {
    expect(toneFor('SYNCED')).toBe('ok');
    expect(toneFor('PENDING')).toBe('warn');
    expect(toneFor('FAILED')).toBe('bad');
  });

  it('knows the central ack and gate command values', () => {
    expect(toneFor('ALREADY_EXISTS')).toBe('info');
    expect(toneFor('REJECTED_PERMANENT')).toBe('bad');
    expect(toneFor('NOT_ATTEMPTED')).toBe('neutral');
  });

  it('shows an unknown value as warn instead of guessing, even a name that exists on Object.prototype', () => {
    expect(toneFor('WHATEVER')).toBe('warn');
    expect(toneFor('toString')).toBe('warn');
  });
});

describe('formatClock', () => {
  it('formats an ISO instant as a 24-hour clock', () => {
    expect(formatClock('2026-11-01T01:20:00Z')).toMatch(/^\d{2}:\d{2}:\d{2}$/);
  });

  it('returns an unparsable value unchanged', () => {
    expect(formatClock('nope')).toBe('nope');
  });
});

describe('shortId', () => {
  it('keeps the first 8 characters of an attendance id', () => {
    expect(shortId('c5b0ff1a-2c59-4333-ac72-1b41b84e84a1')).toBe('c5b0ff1a');
  });
});
