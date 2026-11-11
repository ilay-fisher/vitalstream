import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { ApiError, createApi, newCorrelationId, normalizePoint } from './api';

const api = createApi();

describe('createApi', () => {
  it('lists patients from a bare array', async () => {
    const patients = await api.listPatients();
    expect(patients).toHaveLength(20);
    expect(patients[0]).toMatchObject({ id: 'P-0001', name: 'Avi Cohen', ward: 'ICU' });
    expect(patients[0].latest.HEART_RATE?.value).toBeTypeOf('number');
  });

  it('reads the as-built patient shape (latestVitals, openAlerts)', async () => {
    server.use(
      http.get('*/clinical-api/api/v1/patients', () =>
        HttpResponse.json([{ id: 'P-0009', latestVitals: {}, openAlerts: 2 }]),
      ),
    );
    const patients = await api.listPatients();
    expect(patients).toEqual([
      { id: 'P-0009', name: '', bed: '', ward: '', latest: {}, openAlertCount: 2 },
    ]);
  });

  it('sends a fresh X-Correlation-Id on every request', async () => {
    const seen: string[] = [];
    server.use(
      http.get('*/clinical-api/api/v1/patients', ({ request }) => {
        seen.push(request.headers.get('x-correlation-id') ?? '');
        return HttpResponse.json([]);
      }),
    );
    await api.listPatients();
    await api.listPatients();
    expect(seen).toHaveLength(2);
    expect(seen[0]).not.toBe('');
    expect(seen[0]).not.toBe(seen[1]);
  });

  it('builds the vitals query and sorts points by time', async () => {
    let url: URL | undefined;
    server.use(
      http.get('*/clinical-api/api/v1/patients/:id/vitals', ({ request }) => {
        url = new URL(request.url);
        return HttpResponse.json({
          patientId: 'P-0001',
          resolution: 'MINUTE',
          points: [
            {
              observedAt: '2026-11-11T10:02:00Z',
              metric: 'HEART_RATE',
              avg: 80,
              min: 78,
              max: 82,
              count: 6,
            },
            {
              observedAt: '2026-11-11T10:01:00Z',
              metric: 'HEART_RATE',
              avg: 79,
              min: 77,
              max: 81,
              count: 6,
            },
            { observedAt: 'garbage', avg: 1 },
          ],
        });
      }),
    );
    const points = await api.getVitals('P-0001', {
      metric: 'HEART_RATE',
      from: new Date('2026-11-11T10:00:00Z'),
      to: new Date('2026-11-11T11:00:00Z'),
      resolution: 'minute',
    });
    expect(url?.searchParams.get('metric')).toBe('HEART_RATE');
    expect(url?.searchParams.get('resolution')).toBe('minute');
    expect(url?.searchParams.get('from')).toBe('2026-11-11T10:00:00.000Z');
    expect(points.map((p) => p.value)).toEqual([79, 80]);
    expect(points[0]).toMatchObject({ min: 77, max: 81 });
  });

  it('lists alerts with filters and normalises the page', async () => {
    let url: URL | undefined;
    server.use(
      http.get('*/clinical-api/api/v1/alerts', ({ request }) => {
        url = new URL(request.url);
        return HttpResponse.json({
          items: [{ alertId: 'a1', severity: 'HIGH', message: 'm', acknowledgementNote: 'n' }],
          page: 0,
          size: 50,
          totalElements: 9,
          totalPages: 1,
        });
      }),
    );
    const page = await api.listAlerts({ severity: 'HIGH', q: 'lactate', patientId: '' });
    expect(url?.searchParams.get('severity')).toBe('HIGH');
    expect(url?.searchParams.get('q')).toBe('lactate');
    expect(url?.searchParams.has('patientId')).toBe(false);
    expect(page.total).toBe(9);
    expect(page.items[0]).toMatchObject({
      alertId: 'a1',
      severity: 'HIGH',
      status: 'OPEN',
      acknowledgementNote: 'n',
    });
  });

  it('acknowledges an alert with the body the contract defines', async () => {
    let body: unknown;
    server.use(
      http.post('*/clinical-api/api/v1/alerts/:id/ack', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          alertId: 'a1',
          status: 'ACKNOWLEDGED',
          acknowledgedBy: 'dr.cohen',
          version: 3,
        });
      }),
    );
    const alert = await api.ackAlert('a1', { by: 'dr.cohen', note: 'seen' });
    expect(body).toEqual({ by: 'dr.cohen', note: 'seen' });
    expect(alert).toMatchObject({ status: 'ACKNOWLEDGED', version: 3 });
  });

  it('reports 409 as a conflict ApiError carrying the problem detail', async () => {
    server.use(
      http.post('*/clinical-api/api/v1/alerts/:id/ack', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'already acknowledged' }, { status: 409 }),
      ),
    );
    const error = await api.ackAlert('a1', { by: 'x' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, isConflict: true, message: 'already acknowledged' });
  });

  it('copes with a non-JSON error body', async () => {
    server.use(
      http.get(
        '*/clinical-api/api/v1/patients/:id',
        () => new HttpResponse('oops', { status: 502 }),
      ),
    );
    await expect(api.getPatient('P-1')).rejects.toMatchObject({ status: 502 });
  });

  it('exposes the proxied stream and chat urls', () => {
    expect(api.alertStreamUrl).toBe('/clinical-api/api/v1/alerts/stream');
    expect(api.chatUrl).toBe('/copilot-api/api/v1/chat');
  });
});

describe('helpers', () => {
  it('normalizePoint rejects rows without a time or value', () => {
    expect(normalizePoint({ value: 1 })).toBeNull();
    expect(normalizePoint({ observedAt: '2026-11-11T10:00:00Z', value: 5 })).toMatchObject({
      value: 5,
    });
  });
  it('newCorrelationId is unique', () => {
    expect(newCorrelationId()).not.toBe(newCorrelationId());
  });
});
