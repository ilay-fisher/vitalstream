/** Wire types mirroring docs/CONTRACTS.md sections 3 and 4 (and the copilot "as built" SSE contract). */

export type Metric =
  'HEART_RATE' | 'SPO2' | 'RESP_RATE' | 'SYSTOLIC_BP' | 'DIASTOLIC_BP' | 'TEMPERATURE';

export interface MetricInfo {
  label: string;
  short: string;
  unit: string;
  validMin: number;
  validMax: number;
  normalMin: number;
  normalMax: number;
  decimals: number;
}

/** Units and ranges from the metric table in CONTRACTS 3.1. */
export const METRICS: Record<Metric, MetricInfo> = {
  HEART_RATE: {
    label: 'Heart rate',
    short: 'HR',
    unit: '/min',
    validMin: 20,
    validMax: 250,
    normalMin: 51,
    normalMax: 90,
    decimals: 0,
  },
  SPO2: {
    label: 'Oxygen saturation',
    short: 'SpO2',
    unit: '%',
    validMin: 50,
    validMax: 100,
    normalMin: 96,
    normalMax: 100,
    decimals: 0,
  },
  RESP_RATE: {
    label: 'Respiratory rate',
    short: 'RR',
    unit: '/min',
    validMin: 4,
    validMax: 60,
    normalMin: 12,
    normalMax: 20,
    decimals: 0,
  },
  SYSTOLIC_BP: {
    label: 'Systolic pressure',
    short: 'SBP',
    unit: 'mmHg',
    validMin: 50,
    validMax: 260,
    normalMin: 111,
    normalMax: 219,
    decimals: 0,
  },
  DIASTOLIC_BP: {
    label: 'Diastolic pressure',
    short: 'DBP',
    unit: 'mmHg',
    validMin: 20,
    validMax: 160,
    normalMin: 60,
    normalMax: 90,
    decimals: 0,
  },
  TEMPERATURE: {
    label: 'Temperature',
    short: 'Temp',
    unit: '°C',
    validMin: 30,
    validMax: 43,
    normalMin: 36.1,
    normalMax: 38.0,
    decimals: 1,
  },
};

export const METRIC_ORDER: Metric[] = [
  'HEART_RATE',
  'SPO2',
  'RESP_RATE',
  'SYSTOLIC_BP',
  'DIASTOLIC_BP',
  'TEMPERATURE',
];

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type AlertType = 'THRESHOLD' | 'TREND_ZSCORE' | 'NEWS2' | 'ML_ANOMALY';
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

/**
 * One alert **episode** (CONTRACTS 3.4, 4 "as built"): an ongoing condition per patient, type and
 * metric that the server updates in place. `alertId` is the episode id, `severity` the peak seen
 * while open, and the detection fields (`value`, `score`, `message`, `window*`) are the latest.
 */
export interface AlertView {
  alertId: string;
  patientId: string;
  type: AlertType;
  severity: Severity;
  metric: Metric | null;
  value: number | null;
  score: number | null;
  message: string;
  windowStart: string;
  windowEnd: string;
  detectedAt: string;
  status: AlertStatus;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  acknowledgementNote?: string | null;
  version?: number;
  /** When the episode opened. Drives the "ongoing 12 min" label. */
  firstSeenAt: string;
  /** End of the latest detection window; feeds are sorted by it, newest first. */
  lastSeenAt: string;
  /** Number of detection windows folded into the episode. */
  occurrences: number;
  /** Set once the episode auto-resolved after silence; null while ongoing. */
  resolvedAt: string | null;
  /** The NEWS2 episode this one contributes to, if any. */
  parentAlertId: string | null;
  /** Non-resolved child episodes (NEWS2 parents only). */
  childCount: number;
  lastDetectionId?: string | null;
}

export interface LatestValue {
  value: number;
  unit: string;
  observedAt: string;
}

export type LatestVitals = Partial<Record<Metric, LatestValue>>;

export interface PatientSummary {
  id: string;
  name: string;
  bed: string;
  ward: string;
  latest: LatestVitals;
  openAlertCount: number;
}

/** One chart point. Minute resolution also carries min/max. */
export interface VitalPoint {
  t: number;
  value: number;
  min?: number;
  max?: number;
}

export type Resolution = 'raw' | 'minute';

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface AlertQuery {
  patientId?: string;
  severity?: Severity;
  type?: AlertType;
  /** Repeated on the wire: `status=OPEN&status=ACKNOWLEDGED`. */
  status?: AlertStatus[];
  /** `true` hides episodes that have a parent (they show under it). */
  topLevel?: boolean;
  /** Only the children of this episode. */
  parentAlertId?: string;
  q?: string;
  page?: number;
  size?: number;
}

/** The acknowledging user is the token's owner; the body carries only the optional note. */
export interface AckRequest {
  note?: string;
}

/* ---------------------------------------------------------------- copilot SSE */

export interface ToolCall {
  id: string;
  name: string;
  arguments: unknown;
  status: 'running' | 'ok' | 'error';
  durationMs?: number;
  error?: string;
}

export interface Citation {
  index: number;
  title: string;
  chunkId: string;
  documentId?: string;
  score?: number;
  provenance: string;
  snippet?: string;
}

export interface Usage {
  inputTokens?: number;
  outputTokens?: number;
  [key: string]: unknown;
}

export type CopilotEvent =
  | { type: 'session'; data: { sessionId: string } }
  | { type: 'token'; data: { text: string } }
  | { type: 'tool_call'; data: ToolCall }
  | { type: 'citation'; data: Citation }
  | {
      type: 'done';
      data: {
        text?: string;
        toolCalls?: number;
        usage?: Usage;
        sessionId?: string;
        correlationId?: string;
        error?: boolean;
      };
    };
