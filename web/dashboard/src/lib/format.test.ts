import { describe, expect, it } from 'vitest';
import { clockTime, formatValue, isOutOfNormal, ongoingFor, relativeTime } from './format';

describe('format', () => {
  it('formats by metric precision', () => {
    expect(formatValue('HEART_RATE', 71.6)).toBe('72');
    expect(formatValue('TEMPERATURE', 37)).toBe('37.0');
  });
  it('flags values outside the normal range', () => {
    expect(isOutOfNormal('SPO2', 91)).toBe(true);
    expect(isOutOfNormal('SPO2', 98)).toBe(false);
  });
  it('describes elapsed time', () => {
    const now = Date.parse('2026-11-11T12:00:00Z');
    expect(relativeTime('2026-11-11T11:59:30Z', now)).toBe('30 s ago');
    expect(relativeTime('2026-11-11T11:55:00Z', now)).toBe('5 min ago');
    expect(relativeTime('2026-11-11T09:00:00Z', now)).toBe('3 h ago');
    expect(relativeTime('bad', now)).toBe('');
    expect(relativeTime('2026-11-01T09:00:00Z', now)).not.toBe('');
  });
  it('renders a clock time', () => {
    expect(clockTime(Date.parse('2026-11-11T12:34:00Z'))).toMatch(/\d/);
  });
  it('describes how long an episode has been ongoing', () => {
    const now = Date.parse('2026-11-11T12:00:00Z');
    expect(ongoingFor('2026-11-11T11:59:40Z', now)).toBe('20 s');
    expect(ongoingFor('2026-11-11T11:48:00Z', now)).toBe('12 min');
    expect(ongoingFor('2026-11-11T09:55:00Z', now)).toBe('2 h 5 min');
    expect(ongoingFor('2026-11-11T09:00:00Z', now)).toBe('3 h');
    expect(ongoingFor('2026-11-11T12:00:30Z', now)).toBe('0 s');
    expect(ongoingFor('bad', now)).toBe('');
  });
});
