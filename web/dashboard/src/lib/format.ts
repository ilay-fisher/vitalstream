import { METRICS, type Metric, type Severity } from './types';

export function formatValue(metric: Metric, value: number): string {
  return value.toFixed(METRICS[metric].decimals);
}

export function isOutOfNormal(metric: Metric, value: number): boolean {
  const info = METRICS[metric];
  return value < info.normalMin || value > info.normalMax;
}

/** "12 s ago", "4 min ago", "3 h ago", otherwise the date. */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return '';
  const seconds = Math.max(0, Math.round((now - time) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return new Date(time).toLocaleDateString();
}

export function clockTime(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export const SEVERITY_RANK: Record<Severity, number> = { LOW: 0, MEDIUM: 1, HIGH: 2, CRITICAL: 3 };

export const SEVERITY_LABEL: Record<Severity, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  CRITICAL: 'Critical',
};

export const TYPE_LABEL = {
  THRESHOLD: 'Threshold',
  TREND_ZSCORE: 'Trend (z-score)',
  NEWS2: 'NEWS2',
  ML_ANOMALY: 'ML anomaly',
} as const;
