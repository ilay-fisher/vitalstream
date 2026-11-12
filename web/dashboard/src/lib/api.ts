import { authStore } from './auth';
import type {
  AckRequest,
  AlertQuery,
  AlertView,
  LatestVitals,
  Metric,
  Page,
  PatientSummary,
  Resolution,
  VitalPoint,
} from './types';

/** Fresh id for the X-Correlation-Id header (CONTRIBUTING 4). */
export function newCorrelationId(): string {
  return (
    globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  );
}

/** RFC 9457 problem document, as far as the UI needs it. */
export interface Problem {
  title?: string;
  detail?: string;
  status?: number;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
    readonly correlationId: string,
  ) {
    super(problem.detail ?? problem.title ?? `Request failed with ${status}`);
    this.name = 'ApiError';
  }

  /** Optimistic-lock failure: somebody else changed the resource first. */
  get isConflict(): boolean {
    return this.status === 409;
  }

  /** Signed in, but the role does not allow the operation. */
  get isForbidden(): boolean {
    return this.status === 403;
  }
}

export interface ApiOptions {
  /** Proxied prefix for clinical-api (dev server and nginx both map it). */
  clinicalBase?: string;
  /** Proxied prefix for the copilot. */
  copilotBase?: string;
  fetchImpl?: typeof fetch;
  /** Bearer token of the signed-in user (default: the app-wide session). */
  getToken?: () => string | null;
  /** Called when clinical-api answers 401: the session is over (default: back to sign-in). */
  onUnauthorized?: () => void;
}

type Json = Record<string, unknown>;

function asRecord(value: unknown): Json {
  return typeof value === 'object' && value !== null ? (value as Json) : {};
}

/** clinical-api list responses are specified loosely: accept a bare array or a page object. */
function unwrapList(body: unknown): unknown[] {
  if (Array.isArray(body)) return body;
  const record = asRecord(body);
  for (const key of ['items', 'content', 'data', 'points', 'alerts', 'patients']) {
    if (Array.isArray(record[key])) return record[key] as unknown[];
  }
  return [];
}

export function normalizePatient(raw: unknown): PatientSummary {
  const r = asRecord(raw);
  const latest: LatestVitals = {};
  const source = asRecord(r.latest ?? r.latestVitals ?? r.vitals);
  for (const [metric, value] of Object.entries(source)) {
    const v = asRecord(value);
    if (typeof v.value === 'number') {
      latest[metric as Metric] = {
        value: v.value,
        unit: String(v.unit ?? ''),
        observedAt: String(v.observedAt ?? ''),
      };
    }
  }
  return {
    id: String(r.id ?? r.patientId ?? ''),
    name: String(r.name ?? ''),
    bed: String(r.bed ?? ''),
    ward: String(r.ward ?? ''),
    latest,
    openAlertCount: Number(r.openAlertCount ?? r.openAlerts ?? 0),
  };
}

export function normalizeAlert(raw: unknown): AlertView {
  const r = asRecord(raw);
  return {
    alertId: String(r.alertId ?? r.id ?? ''),
    patientId: String(r.patientId ?? ''),
    type: (r.type ?? 'THRESHOLD') as AlertView['type'],
    severity: (r.severity ?? 'LOW') as AlertView['severity'],
    metric: (r.metric ?? null) as AlertView['metric'],
    value: typeof r.value === 'number' ? r.value : null,
    score: typeof r.score === 'number' ? r.score : null,
    message: String(r.message ?? ''),
    windowStart: String(r.windowStart ?? ''),
    windowEnd: String(r.windowEnd ?? ''),
    detectedAt: String(r.detectedAt ?? ''),
    status: (r.status ?? 'OPEN') as AlertView['status'],
    acknowledgedBy: (r.acknowledgedBy ?? null) as string | null,
    acknowledgedAt: (r.acknowledgedAt ?? null) as string | null,
    acknowledgementNote: (r.acknowledgementNote ?? null) as string | null,
    ...(typeof r.version === 'number' ? { version: r.version } : {}),
    // Episode fields (CONTRACTS 3.4). Defaults keep a pre-episode payload renderable.
    firstSeenAt: String(r.firstSeenAt ?? r.detectedAt ?? ''),
    lastSeenAt: String(r.lastSeenAt ?? r.windowEnd ?? r.detectedAt ?? ''),
    occurrences: typeof r.occurrences === 'number' ? r.occurrences : 1,
    resolvedAt: (r.resolvedAt ?? null) as string | null,
    parentAlertId: (r.parentAlertId ?? null) as string | null,
    childCount: typeof r.childCount === 'number' ? r.childCount : 0,
    lastDetectionId: (r.lastDetectionId ?? null) as string | null,
  };
}

/** Raw points carry `observedAt`/`value`; minute buckets carry `windowStart` and avg/min/max. */
export function normalizePoint(raw: unknown): VitalPoint | null {
  const r = asRecord(raw);
  const time = Date.parse(String(r.observedAt ?? r.windowStart ?? r.t ?? ''));
  const value = r.value ?? r.avg;
  if (Number.isNaN(time) || typeof value !== 'number') return null;
  return {
    t: time,
    value,
    ...(typeof r.min === 'number' ? { min: r.min } : {}),
    ...(typeof r.max === 'number' ? { max: r.max } : {}),
  };
}

export interface VitalsQuery {
  metric: Metric;
  from: Date;
  to: Date;
  resolution: Resolution;
}

export function createApi(options: ApiOptions = {}) {
  const clinical = options.clinicalBase ?? '/clinical-api';
  const copilot = options.copilotBase ?? '/copilot-api';
  const doFetch = (input: string, init?: RequestInit) => (options.fetchImpl ?? fetch)(input, init);
  const getToken = options.getToken ?? (() => authStore.getToken());
  const onUnauthorized = options.onUnauthorized ?? (() => authStore.expire());

  /** Authorization header for clinical-api; fetch-based SSE reuses it, so no token is put in a URL. */
  function authHeaders(): Record<string, string> {
    const token = getToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  async function request(url: string, init: RequestInit = {}): Promise<unknown> {
    const correlationId = newCorrelationId();
    const response = await doFetch(url, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...authHeaders(),
        ...(init.headers as Record<string, string> | undefined),
        'X-Correlation-Id': correlationId,
      },
    });
    if (!response.ok) {
      if (response.status === 401) onUnauthorized();
      let problem: Problem = { status: response.status };
      try {
        problem = { ...problem, ...asRecord(await response.json()) };
      } catch {
        /* non-JSON error body: keep the status only */
      }
      throw new ApiError(response.status, problem, correlationId);
    }
    return response.status === 204 ? null : response.json();
  }

  /** Builds a query string; an array value is repeated (`status=OPEN&status=ACKNOWLEDGED`). */
  function query(
    params: Record<string, string | number | boolean | readonly string[] | undefined>,
  ): string {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (Array.isArray(value)) value.forEach((v) => search.append(key, v));
      else if (value !== undefined && value !== '') search.set(key, String(value));
    }
    const text = search.toString();
    return text ? `?${text}` : '';
  }

  return {
    clinicalBase: clinical,
    copilotBase: copilot,
    authHeaders,
    handleUnauthorized: onUnauthorized,

    async listPatients(): Promise<PatientSummary[]> {
      return unwrapList(await request(`${clinical}/api/v1/patients`)).map(normalizePatient);
    },

    async getPatient(id: string): Promise<PatientSummary> {
      return normalizePatient(
        await request(`${clinical}/api/v1/patients/${encodeURIComponent(id)}`),
      );
    },

    async getVitals(patientId: string, q: VitalsQuery): Promise<VitalPoint[]> {
      const qs = query({
        metric: q.metric,
        from: q.from.toISOString(),
        to: q.to.toISOString(),
        resolution: q.resolution,
      });
      const body = await request(
        `${clinical}/api/v1/patients/${encodeURIComponent(patientId)}/vitals${qs}`,
      );
      return unwrapList(body)
        .map(normalizePoint)
        .filter((p): p is VitalPoint => p !== null)
        .sort((a, b) => a.t - b.t);
    },

    async listAlerts(q: AlertQuery = {}): Promise<Page<AlertView>> {
      const qs = query({ ...q, page: q.page ?? 0, size: q.size ?? 50 });
      const body = await request(`${clinical}/api/v1/alerts${qs}`);
      const items = unwrapList(body).map(normalizeAlert);
      const record = asRecord(body);
      return {
        items,
        total: Number(record.totalElements ?? record.total ?? items.length),
        page: Number(record.page ?? record.number ?? q.page ?? 0),
        size: Number(record.size ?? q.size ?? 50),
      };
    },

    async getAlert(id: string): Promise<AlertView> {
      return normalizeAlert(await request(`${clinical}/api/v1/alerts/${encodeURIComponent(id)}`));
    },

    /** Acknowledge an alert; a 409 surfaces as `ApiError.isConflict`. */
    async ackAlert(id: string, body: AckRequest): Promise<AlertView> {
      return normalizeAlert(
        await request(`${clinical}/api/v1/alerts/${encodeURIComponent(id)}/ack`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      );
    },

    alertStreamUrl: `${clinical}/api/v1/alerts/stream`,
    chatUrl: `${copilot}/api/v1/chat`,
  };
}

export type Api = ReturnType<typeof createApi>;
