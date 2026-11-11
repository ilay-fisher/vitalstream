import { SEVERITY_LABEL } from '../lib/format';
import { news2Band } from '../lib/news2';
import type { Severity } from '../lib/types';

const SHAPES: Record<Severity, string> = {
  LOW: 'M8 3a5 5 0 1 0 0 10A5 5 0 0 0 8 3Z',
  MEDIUM: 'M8 2 14.5 13h-13L8 2Z',
  HIGH: 'M8 1.5 14.5 8 8 14.5 1.5 8 8 1.5Z',
  CRITICAL: 'M5.2 1.5h5.6l3.7 3.7v5.6l-3.7 3.7H5.2L1.5 10.8V5.2l3.7-3.7Z',
};

/** Severity is always conveyed by label and shape as well as colour. */
export function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <span className={`sev sev-${severity.toLowerCase()}`} data-severity={severity}>
      <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true">
        <path d={SHAPES[severity]} fill="currentColor" />
      </svg>
      {SEVERITY_LABEL[severity]}
    </span>
  );
}

const BAND_TEXT = {
  low: 'Low',
  'low-medium': 'Low',
  medium: 'Medium',
  high: 'High',
} as const;

/** NEWS2 aggregate with its clinical risk band spelled out. */
export function News2Badge({ score }: { score: number | null }) {
  if (score === null) {
    return (
      <span className="news news-none" title="NEWS2 needs RR, SpO2, SBP, HR and temperature">
        NEWS2 –
      </span>
    );
  }
  const band = news2Band(score);
  return (
    <span className={`news news-${band}`} data-band={band} title={`NEWS2 ${score}`}>
      <b>NEWS2 {score}</b>
      <span>{BAND_TEXT[band]} risk</span>
    </span>
  );
}
