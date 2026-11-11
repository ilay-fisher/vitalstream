import { describe, expect, it } from 'vitest';
import { news2Band, news2FromLatest, news2Score } from './news2';
import type { LatestVitals } from './types';

const normal = { rr: 16, spo2: 98, sbp: 120, hr: 70, temp: 36.8 };

describe('news2Score', () => {
  it('is zero for normal vitals', () => {
    expect(news2Score(normal)).toBe(0);
  });

  it.each([
    [{ rr: 8 }, 3],
    [{ rr: 10 }, 1],
    [{ rr: 22 }, 2],
    [{ rr: 25 }, 3],
    [{ spo2: 91 }, 3],
    [{ spo2: 93 }, 2],
    [{ spo2: 95 }, 1],
    [{ sbp: 90 }, 3],
    [{ sbp: 100 }, 2],
    [{ sbp: 110 }, 1],
    [{ sbp: 220 }, 3],
    [{ hr: 40 }, 3],
    [{ hr: 50 }, 1],
    [{ hr: 100 }, 1],
    [{ hr: 120 }, 2],
    [{ hr: 131 }, 3],
    [{ temp: 35 }, 3],
    [{ temp: 35.5 }, 1],
    [{ temp: 38.5 }, 1],
    [{ temp: 39.1 }, 2],
  ])('scores %j as %i', (override, expected) => {
    expect(news2Score({ ...normal, ...override })).toBe(expected);
  });
});

describe('news2FromLatest', () => {
  const at = (value: number) => ({ value, unit: '', observedAt: '' });
  const full: LatestVitals = {
    RESP_RATE: at(16),
    SPO2: at(98),
    SYSTOLIC_BP: at(120),
    HEART_RATE: at(70),
    TEMPERATURE: at(36.8),
  };
  it('computes from the latest values', () => {
    expect(news2FromLatest(full)).toBe(0);
  });
  it('is null while an input is missing', () => {
    expect(news2FromLatest({ ...full, SPO2: undefined })).toBeNull();
  });
});

describe('news2Band', () => {
  it.each([
    [0, 'low'],
    [3, 'low-medium'],
    [5, 'medium'],
    [6, 'medium'],
    [7, 'high'],
    [12, 'high'],
  ])('%i is %s', (score, band) => {
    expect(news2Band(score)).toBe(band);
  });
});
