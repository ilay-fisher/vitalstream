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

/** AlertEvent fields plus workflow state (CONTRACTS 3.2 and 4). `version` is optional, see README. */
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
  status?: AlertStatus;
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
