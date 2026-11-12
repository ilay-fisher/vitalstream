import { delay, http, HttpResponse } from 'msw';
import type { RoleName } from '../lib/auth';
import { issueMockToken, readMockToken, type MockClaims } from './auth';
import type { AlertView, Metric } from '../lib/types';
import { buildAlerts, buildPatients, buildVitals, CONFLICT_ALERT_ID, withOpenCounts } from './data';

/** Mutable in-memory backend shared by all handlers; tests call `resetMockState`. */
interface MockState {
  alerts: AlertView[];
  streamIntervalMs: number;
  tokenDelayMs: number;
  nextAlert: number;
}

/** Tuning knobs for demos and e2e: `?streamMs=1500&tokenMs=100` on the page URL. */
function pageParam(name: string, fallback: number): number {
  if (typeof location === 'undefined') return fallback;
  const value = Number(new URLSearchParams(location.search).get(name));
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

export const mockState: MockState = {
  alerts: buildAlerts(),
  streamIntervalMs: pageParam('streamMs', 7000),
  tokenDelayMs: pageParam('tokenMs', 45),
  nextAlert: 100,
};

export function resetMockState(overrides: Partial<MockState> = {}): void {
  Object.assign(mockState, {
    alerts: buildAlerts(),
    streamIntervalMs: 7000,
    tokenDelayMs: 45,
    nextAlert: 100,
    ...overrides,
  });
}

const READ_ROLES: RoleName[] = ['CLINICIAN', 'VIEWER', 'SERVICE'];

/** Mirrors clinical-api CONTRACTS 4.2: 401 without a token, 403 for a role that may not do this. */
function authorize(request: Request, allowed: RoleName[] = READ_ROLES): MockClaims | Response {
  const claims = readMockToken(request.headers.get('authorization'));
  const problem = (status: number, title: string) =>
    HttpResponse.json(
      { title, status, detail: title },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    );
  if (!claims) return problem(401, 'Unauthorized');
  return claims.roles.some((r) => allowed.includes(r)) ? claims : problem(403, 'Forbidden');
}

const encoder = new TextEncoder();
const sse = (event: string, data: unknown) =>
  encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);

function sseResponse(start: (push: (e: string, d: unknown) => void, close: () => void) => void) {
  let closed = false;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(': connected\n\n'));
      start(
        (e, d) => {
          if (!closed) controller.enqueue(sse(e, d));
        },
        () => {
          closed = true;
          try {
            controller.close();
          } catch {
            /* already closed */
          }
        },
      );
    },
    cancel() {
      closed = true;
    },
  });
  return new HttpResponse(stream, {
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
  });
}

const LIVE_TEMPLATES: Array<
  Pick<AlertView, 'patientId' | 'type' | 'severity' | 'message' | 'metric' | 'value' | 'score'>
> = [
  {
    patientId: 'P-0007',
    type: 'THRESHOLD',
    severity: 'CRITICAL',
    message: 'SpO2 86 % is below the critical limit (90)',
    metric: 'SPO2',
    value: 86,
    score: null,
  },
  {
    patientId: 'P-0012',
    type: 'NEWS2',
    severity: 'CRITICAL',
    message: 'NEWS2 score 7 (RR 24, HR 118, Temp 38.7)',
    metric: null,
    value: null,
    score: 7,
  },
  {
    patientId: 'P-0009',
    type: 'TREND_ZSCORE',
    severity: 'HIGH',
    message: 'Heart rate 126 deviates from the recent trend (z = 3.4)',
    metric: 'HEART_RATE',
    value: 126,
    score: 3.4,
  },
];

function liveAlert(): AlertView {
  const template = LIVE_TEMPLATES[mockState.nextAlert % LIVE_TEMPLATES.length];
  const now = Date.now();
  const alert: AlertView = {
    ...template,
    alertId: `00000000-0000-4000-8000-${String(mockState.nextAlert++).padStart(12, '0')}`,
    windowStart: new Date(now - 62_000).toISOString(),
    windowEnd: new Date(now - 2_000).toISOString(),
    detectedAt: new Date(now).toISOString(),
    status: 'OPEN',
    acknowledgedBy: null,
    acknowledgedAt: null,
    version: 0,
  };
  mockState.alerts.unshift(alert);
  return alert;
}

const COPILOT_ANSWER =
  'Patient P-0003 meets sepsis screening criteria. NEWS2 is **12**, driven by a respiratory rate of 29, SpO2 91 % and a systolic pressure of 84 mmHg [1].\n\n' +
  '- Start the sepsis bundle: lactate, blood cultures, broad-spectrum antibiotics within the hour [1].\n' +
  '- Give a 30 mL/kg crystalloid bolus if hypotension persists [2].\n' +
  '- Reassess vitals every 15 minutes and escalate to the critical care team.\n\n' +
  'This is decision support only; confirm against local protocol.';

export const handlers = [
  http.post('*/clinical-api/api/v1/dev/token', async ({ request }) => {
    const body = (await request.json()) as MockClaims;
    return HttpResponse.json({
      token: issueMockToken(body),
      expiresAt: new Date(Date.now() + 8 * 3_600_000).toISOString(),
    });
  }),

  http.get('*/clinical-api/api/v1/patients', async ({ request }) => {
    const denied = authorize(request);
    if (denied instanceof Response) return denied;
    await delay(120);
    return HttpResponse.json(
      withOpenCounts(buildPatients(), mockState.alerts).map(({ latest, openAlertCount, ...p }) => ({
        ...p,
        latestVitals: latest,
        openAlerts: openAlertCount,
      })),
    );
  }),

  http.get('*/clinical-api/api/v1/patients/:id/vitals', async ({ params, request }) => {
    const denied = authorize(request);
    if (denied instanceof Response) return denied;
    const url = new URL(request.url);
    const metric = (url.searchParams.get('metric') ?? 'HEART_RATE') as Metric;
    const to = Date.parse(url.searchParams.get('to') ?? '') || Date.now();
    const from = Date.parse(url.searchParams.get('from') ?? '') || to - 3_600_000;
    const resolution = url.searchParams.get('resolution') === 'minute' ? 'minute' : 'raw';
    await delay(80);
    const points = buildVitals(String(params.id), metric, from, to, resolution).map((p) =>
      resolution === 'minute'
        ? {
            observedAt: new Date(p.t).toISOString(),
            metric,
            min: p.min,
            max: p.max,
            avg: p.value,
            count: 6,
          }
        : {
            observedAt: new Date(p.t).toISOString(),
            metric,
            value: p.value,
            unit: '',
            deviceId: 'DEV-ICU-0001',
          },
    );
    return HttpResponse.json({
      patientId: String(params.id),
      resolution: resolution.toUpperCase(),
      from: new Date(from).toISOString(),
      to: new Date(to).toISOString(),
      points,
    });
  }),

  http.get('*/clinical-api/api/v1/patients/:id', ({ params, request }) => {
    const denied = authorize(request);
    if (denied instanceof Response) return denied;
    const patient = withOpenCounts(buildPatients(), mockState.alerts).find(
      (p) => p.id === params.id,
    );
    return patient
      ? HttpResponse.json({
          id: patient.id,
          name: patient.name,
          bed: patient.bed,
          ward: patient.ward,
          latestVitals: patient.latest,
          openAlerts: patient.openAlertCount,
        })
      : HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 });
  }),

  http.get('*/clinical-api/api/v1/alerts/stream', ({ request }) => {
    const denied = authorize(request);
    if (denied instanceof Response) return denied;
    return sseResponse((push, close) => {
      const timer = setInterval(() => push('alert', liveAlert()), mockState.streamIntervalMs);
      // Stop producing once the client goes away (cancel flips `closed`; the next push is a no-op).
      setTimeout(() => {
        clearInterval(timer);
        close();
      }, 10 * 60_000);
    });
  }),

  http.get('*/clinical-api/api/v1/alerts/:id', ({ params, request }) => {
    const denied = authorize(request);
    if (denied instanceof Response) return denied;
    const alert = mockState.alerts.find((a) => a.alertId === params.id);
    return alert
      ? HttpResponse.json(alert)
      : HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 });
  }),

  http.get('*/clinical-api/api/v1/alerts', async ({ request }) => {
    const denied = authorize(request);
    if (denied instanceof Response) return denied;
    const url = new URL(request.url);
    const q = (url.searchParams.get('q') ?? '').toLowerCase();
    const filter = (key: keyof AlertView) => url.searchParams.get(key) ?? undefined;
    const items = mockState.alerts.filter(
      (a) =>
        (!filter('patientId') || a.patientId === filter('patientId')) &&
        (!filter('severity') || a.severity === filter('severity')) &&
        (!filter('type') || a.type === filter('type')) &&
        (!filter('status') || a.status === filter('status')) &&
        (!q || `${a.message} ${a.patientId}`.toLowerCase().includes(q)),
    );
    await delay(80);
    return HttpResponse.json({
      items,
      page: 0,
      size: 50,
      totalElements: items.length,
      totalPages: 1,
    });
  }),

  http.post('*/clinical-api/api/v1/alerts/:id/ack', async ({ params, request }) => {
    const actor = authorize(request, ['CLINICIAN']);
    if (actor instanceof Response) return actor;
    // The real API ignores any `by` in the body: the actor is the token.
    const body = (await request.json()) as { note?: string };
    const alert = mockState.alerts.find((a) => a.alertId === params.id);
    if (!alert) return HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 });
    await delay(150);
    if (alert.alertId === CONFLICT_ALERT_ID || alert.status !== 'OPEN') {
      if (alert.alertId === CONFLICT_ALERT_ID) {
        Object.assign(alert, {
          status: 'ACKNOWLEDGED',
          acknowledgedBy: 'dr.levi',
          acknowledgedAt: new Date().toISOString(),
          version: (alert.version ?? 0) + 1,
        });
      }
      return HttpResponse.json(
        { title: 'Conflict', detail: 'Alert was already acknowledged', status: 409 },
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    Object.assign(alert, {
      status: 'ACKNOWLEDGED',
      acknowledgedBy: actor.name ?? actor.sub,
      acknowledgementNote: body.note ?? null,
      acknowledgedAt: new Date().toISOString(),
      version: (alert.version ?? 0) + 1,
    });
    return HttpResponse.json(alert);
  }),

  http.post('*/copilot-api/api/v1/chat', async ({ request }) => {
    const body = (await request.json()) as { message: string; sessionId?: string };
    const sessionId = body.sessionId ?? 'a1b2c3d4e5f60718293a4b5c6d7e8f90';
    return sseResponse((push, close) => {
      void (async () => {
        const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
        push('session', { sessionId });
        push('tool_call', {
          id: 't1',
          name: 'get_patient_vitals',
          arguments: { patient_id: 'P-0003', minutes: 30 },
          status: 'running',
        });
        await wait(350);
        push('tool_call', {
          id: 't1',
          name: 'get_patient_vitals',
          arguments: { patient_id: 'P-0003', minutes: 30 },
          status: 'ok',
          durationMs: 212,
        });
        push('tool_call', {
          id: 't2',
          name: 'search_guidelines',
          arguments: { query: 'sepsis bundle hypotension', k: 3 },
          status: 'running',
        });
        await wait(300);
        push('tool_call', {
          id: 't2',
          name: 'search_guidelines',
          arguments: { query: 'sepsis bundle hypotension', k: 3 },
          status: 'ok',
          durationMs: 148,
        });
        push('citation', {
          index: 1,
          title: 'Sepsis: first-hour bundle',
          chunkId: 'c-7f3a',
          documentId: 'd-12',
          score: 0.83,
          provenance: 'curated',
          snippet:
            'Measure lactate, obtain blood cultures and give broad-spectrum antibiotics within one hour.',
        });
        push('citation', {
          index: 2,
          title: 'Fluid resuscitation in septic shock',
          chunkId: 'c-91b2',
          documentId: 'd-14',
          score: 0.71,
          provenance: 'curated',
          snippet: 'Give 30 mL/kg crystalloid for hypotension or lactate of 4 mmol/L or more.',
        });
        for (const token of COPILOT_ANSWER.match(/\S+\s*|\n+/g) ?? []) {
          push('token', { text: token });
          await wait(mockState.tokenDelayMs);
        }
        push('done', {
          text: COPILOT_ANSWER,
          sessionId,
          correlationId: 'mock-corr-1',
          toolCalls: 2,
          usage: { inputTokens: 1840, outputTokens: 96 },
        });
        close();
      })();
    });
  }),
];
