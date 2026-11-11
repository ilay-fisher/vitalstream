import type { LatestVitals } from './types';

/** NEWS2 aggregate (RCP, SpO2 scale 1, alert patient). Mirrors the stream-processor rule. */
export function news2Score(v: {
  rr: number;
  spo2: number;
  sbp: number;
  hr: number;
  temp: number;
}): number {
  const rr = Math.round(v.rr);
  const spo2 = Math.round(v.spo2);
  const sbp = Math.round(v.sbp);
  const hr = Math.round(v.hr);
  const rrScore = rr <= 8 || rr >= 25 ? 3 : rr >= 21 ? 2 : rr <= 11 ? 1 : 0;
  const spo2Score = spo2 <= 91 ? 3 : spo2 <= 93 ? 2 : spo2 <= 95 ? 1 : 0;
  const sbpScore = sbp <= 90 || sbp >= 220 ? 3 : sbp <= 100 ? 2 : sbp <= 110 ? 1 : 0;
  const hrScore = hr <= 40 || hr >= 131 ? 3 : hr >= 111 ? 2 : hr >= 91 || hr <= 50 ? 1 : 0;
  const tempScore =
    v.temp <= 35.0 ? 3 : v.temp >= 39.1 ? 2 : v.temp <= 36.0 || v.temp >= 38.1 ? 1 : 0;
  return rrScore + spo2Score + sbpScore + hrScore + tempScore;
}

/** NEWS2 from the latest vitals, or null while any of the five inputs is missing. */
export function news2FromLatest(latest: LatestVitals): number | null {
  const rr = latest.RESP_RATE?.value;
  const spo2 = latest.SPO2?.value;
  const sbp = latest.SYSTOLIC_BP?.value;
  const hr = latest.HEART_RATE?.value;
  const temp = latest.TEMPERATURE?.value;
  if ([rr, spo2, sbp, hr, temp].some((x) => x === undefined)) return null;
  return news2Score({
    rr: rr as number,
    spo2: spo2 as number,
    sbp: sbp as number,
    hr: hr as number,
    temp: temp as number,
  });
}

export type News2Band = 'low' | 'low-medium' | 'medium' | 'high';

/** Clinical response bands: 0-4 low, any single 3 -> low-medium (not visible here), 5-6 medium, 7+ high. */
export function news2Band(score: number): News2Band {
  if (score >= 7) return 'high';
  if (score >= 5) return 'medium';
  if (score >= 1) return 'low-medium';
  return 'low';
}
