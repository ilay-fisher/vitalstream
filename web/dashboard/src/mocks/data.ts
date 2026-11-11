import { news2FromLatest } from '../lib/news2';
import {
  METRIC_ORDER,
  METRICS,
  type AlertView,
  type LatestVitals,
  type Metric,
  type PatientSummary,
  type VitalPoint,
} from '../lib/types';

/** Same 20 patients as infra/postgres/init.sql. */
const NAMES = [
  'Avi Cohen',
  'Dana Levi',
  'Yossi Mizrahi',
  'Noa Peretz',
  'Eli Biton',
  'Maya Friedman',
  'Omer Katz',
  'Tamar Azulay',
  'Ron Dahan',
  'Shira Amar',
  'Lior Golan',
  'Noam Segal',
  'Yael Hadad',
  'Itai Shalom',
  'Michal Ben-David',
  'Gil Navon',
  'Rina Ohana',
  'Amit Stern',
  'Hila Malka',
  'Daniel Weiss',
];

type Profile = 'stable' | 'sepsis' | 'hypoxia' | 'watch';

const PROFILE: Record<string, Profile> = {
  'P-0003': 'sepsis',
  'P-0007': 'hypoxia',
  'P-0012': 'watch',
  'P-0016': 'watch',
};

const BASE: Record<Metric, number> = {
  HEART_RATE: 72,
  SPO2: 98,
  RESP_RATE: 15,
  SYSTOLIC_BP: 122,
  DIASTOLIC_BP: 76,
  TEMPERATURE: 36.8,
};

const DELTA: Record<Profile, Partial<Record<Metric, number>>> = {
  stable: {},
  watch: { HEART_RATE: 28, RESP_RATE: 6, TEMPERATURE: 1.4 },
  sepsis: {
    HEART_RATE: 48,
    RESP_RATE: 14,
    TEMPERATURE: 2.4,
    SYSTOLIC_BP: -38,
    DIASTOLIC_BP: -20,
    SPO2: -6,
  },
  hypoxia: { SPO2: -11, RESP_RATE: 12, HEART_RATE: 38 },
};

function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return ((h >>> 0) % 10000) / 10000;
}

export function profileOf(patientId: string): Profile {
  return PROFILE[patientId] ?? 'stable';
}

function metricValue(patientId: string, metric: Metric, progress: number, salt: string): number {
  const info = METRICS[metric];
  const personal = (hash(`${patientId}${metric}`) - 0.5) * (info.normalMax - info.normalMin) * 0.12;
  const wave =
    Math.sin(hash(`${patientId}w`) * 6 + progress * 9) * (info.normalMax - info.normalMin) * 0.02;
  const noise =
    (hash(`${patientId}${metric}${salt}`) - 0.5) * (info.normalMax - info.normalMin) * 0.03;
  const drift = (DELTA[profileOf(patientId)][metric] ?? 0) * progress * progress;
  const value = BASE[metric] + personal + wave + noise + drift;
  return Number(value.toFixed(info.decimals));
}

export function buildPatients(now: number = Date.now()): PatientSummary[] {
  return NAMES.map((name, i) => {
    const id = `P-${String(i + 1).padStart(4, '0')}`;
    const latest: LatestVitals = {};
    for (const metric of METRIC_ORDER) {
      latest[metric] = {
        value: metricValue(id, metric, 1, 'now'),
        unit: METRICS[metric].unit,
        observedAt: new Date(now - 4000).toISOString(),
      };
    }
    return {
      id,
      name,
      bed: `B-${String((i % 10) + 1).padStart(2, '0')}`,
      ward: i < 10 ? 'ICU' : 'CARDIOLOGY',
      latest,
      openAlertCount: 0,
    };
  });
}

export const CONFLICT_ALERT_ID = '00000000-0000-4000-8000-0000000000c1';

export function buildAlerts(now: number = Date.now()): AlertView[] {
  const minute = 60_000;
  const make = (
    n: number,
    patientId: string,
    type: AlertView['type'],
    severity: AlertView['severity'],
    message: string,
    agoMin: number,
    extra: Partial<AlertView> = {},
  ): AlertView => {
    const detected = now - agoMin * minute;
    return {
      alertId: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
      patientId,
      type,
      severity,
      metric: null,
      value: null,
      score: null,
      message,
      windowStart: new Date(detected - 62_000).toISOString(),
      windowEnd: new Date(detected - 2_000).toISOString(),
      detectedAt: new Date(detected).toISOString(),
      status: 'OPEN',
      acknowledgedBy: null,
      acknowledgedAt: null,
      version: 0,
      ...extra,
    };
  };
  return [
    make(
      1,
      'P-0003',
      'NEWS2',
      'CRITICAL',
      'NEWS2 score 12 (RR 29, SpO2 91, SBP 84, HR 119, Temp 39.2)',
      2,
      { score: 12 },
    ),
    make(
      2,
      'P-0003',
      'THRESHOLD',
      'CRITICAL',
      'Systolic pressure 84 mmHg is below the critical limit (90)',
      3,
      { metric: 'SYSTOLIC_BP', value: 84 },
    ),
    make(3, 'P-0003', 'THRESHOLD', 'MEDIUM', 'Temperature 38.9 °C is above the normal range', 11, {
      metric: 'TEMPERATURE',
      value: 38.9,
    }),
    make(4, 'P-0007', 'NEWS2', 'CRITICAL', 'NEWS2 score 8 (RR 27, SpO2 87, HR 110)', 4, {
      score: 8,
    }),
    make(5, 'P-0007', 'THRESHOLD', 'CRITICAL', 'SpO2 87 % is below the critical limit (90)', 5, {
      metric: 'SPO2',
      value: 87,
    }),
    make(6, 'P-0012', 'NEWS2', 'HIGH', 'NEWS2 score 5 (RR 22, HR 104, Temp 38.2)', 8, { score: 5 }),
    make(
      7,
      'P-0016',
      'TREND_ZSCORE',
      'HIGH',
      'Heart rate 131 deviates from the recent trend (z = 3.8)',
      14,
      { metric: 'HEART_RATE', value: 131, score: 3.8 },
    ),
    make(
      8,
      'P-0005',
      'ML_ANOMALY',
      'MEDIUM',
      'Vital-sign pattern is unusual for this patient (anomaly score 0.91)',
      26,
      { score: 0.91 },
    ),
    make(9, 'P-0009', 'THRESHOLD', 'MEDIUM', 'Respiratory rate 23 is above the normal range', 41, {
      metric: 'RESP_RATE',
      value: 23,
    }),
    make(
      10,
      'P-0003',
      'TREND_ZSCORE',
      'HIGH',
      'Heart rate 112 deviates from the recent trend (z = 3.2)',
      52,
      {
        metric: 'HEART_RATE',
        value: 112,
        score: 3.2,
        status: 'ACKNOWLEDGED',
        acknowledgedBy: 'dr.cohen',
        acknowledgedAt: new Date(now - 47 * minute).toISOString(),
        version: 1,
      },
    ),
    make(
      11,
      'P-0014',
      'THRESHOLD',
      'LOW',
      'Diastolic pressure 58 mmHg is just under the normal range',
      75,
      { metric: 'DIASTOLIC_BP', value: 58 },
    ),
    {
      ...make(
        12,
        'P-0012',
        'THRESHOLD',
        'MEDIUM',
        'Temperature 38.4 °C is above the normal range',
        18,
        { metric: 'TEMPERATURE', value: 38.4 },
      ),
      alertId: CONFLICT_ALERT_ID,
    },
  ];
}

export function withOpenCounts(patients: PatientSummary[], alerts: AlertView[]): PatientSummary[] {
  return patients.map((p) => ({
    ...p,
    openAlertCount: alerts.filter((a) => a.patientId === p.id && a.status === 'OPEN').length,
  }));
}

/** Time series ending now: stable patients wander, deteriorating ones ramp over the last 40 %. */
export function buildVitals(
  patientId: string,
  metric: Metric,
  from: number,
  to: number,
  resolution: 'raw' | 'minute',
): VitalPoint[] {
  const step =
    resolution === 'minute'
      ? 60_000
      : Math.max(10_000, Math.round((to - from) / 360 / 1000) * 1000);
  const points: VitalPoint[] = [];
  const start = Math.ceil(from / step) * step;
  for (let t = start; t <= to; t += step) {
    const progress = Math.max(0, ((t - from) / (to - from) - 0.6) / 0.4);
    const value = metricValue(patientId, metric, progress, String(t));
    points.push(
      resolution === 'minute'
        ? {
            t,
            value,
            min: Number((value - 0.02 * Math.abs(value)).toFixed(1)),
            max: Number((value + 0.02 * Math.abs(value)).toFixed(1)),
          }
        : { t, value },
    );
  }
  return points;
}

export function newsFor(patient: PatientSummary): number | null {
  return news2FromLatest(patient.latest);
}
