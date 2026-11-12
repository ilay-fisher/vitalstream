import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { createApi } from '../lib/api';
import { issueMockToken } from '../mocks/auth';
import { mockState } from '../mocks/handlers';
import { server } from '../test/server';
import { useAlertStream } from './useAlertStream';

const TOKEN = issueMockToken({ sub: 'nurse.levi', roles: ['CLINICIAN'] });
const api = createApi({ getToken: () => TOKEN });

describe('useAlertStream', () => {
  it('connects, upserts the repeated events of one episode and notifies with each alert', async () => {
    mockState.streamIntervalMs = 20;
    const onAlert = vi.fn();
    const { result, unmount } = renderHook(() => useAlertStream(api, onAlert));
    await waitFor(() => expect(onAlert.mock.calls.length).toBeGreaterThanOrEqual(4));
    expect(result.current.connection).toBe('open');
    const ids = result.current.alerts.map((a) => a.alertId);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.length).toBeLessThan(onAlert.mock.calls.length);
    expect(onAlert.mock.calls[0][0]).toMatchObject({ occurrences: 1, status: 'OPEN' });
    unmount();
  });

  it('sends a correlation id and ignores non-alert and malformed events', async () => {
    const ids: string[] = [];
    const enc = new TextEncoder();
    server.use(
      http.get('*/clinical-api/api/v1/alerts/stream', ({ request }) => {
        ids.push(request.headers.get('x-correlation-id') ?? '');
        const body = new ReadableStream({
          start(c) {
            c.enqueue(enc.encode('event: heartbeat\ndata: {}\n\n'));
            c.enqueue(enc.encode('event: alert\ndata: {not json\n\n'));
            c.enqueue(
              enc.encode(
                'event: alert\ndata: {"alertId":"a9","patientId":"P-0001","severity":"HIGH"}\n\n',
              ),
            );
          },
        });
        return new HttpResponse(body, { headers: { 'Content-Type': 'text/event-stream' } });
      }),
    );
    const { result, unmount } = renderHook(() => useAlertStream(api));
    await waitFor(() => expect(result.current.alerts).toHaveLength(1));
    expect(result.current.alerts[0]).toMatchObject({ alertId: 'a9', severity: 'HIGH' });
    expect(ids[0]).not.toBe('');
    unmount();
  });

  it('reports reconnecting when the stream fails', async () => {
    server.use(
      http.get(
        '*/clinical-api/api/v1/alerts/stream',
        () => new HttpResponse(null, { status: 503 }),
      ),
    );
    const { result, unmount } = renderHook(() => useAlertStream(api));
    await waitFor(() => expect(result.current.connection).toBe('reconnecting'));
    unmount();
  });
});

describe('useAlertStream authorization', () => {
  it('sends the bearer header (no EventSource, no token in the URL)', async () => {
    const seen: Array<{ url: string; authorization: string | null }> = [];
    server.use(
      http.get('*/clinical-api/api/v1/alerts/stream', ({ request }) => {
        seen.push({ url: request.url, authorization: request.headers.get('authorization') });
        return new HttpResponse(new ReadableStream({ start() {} }), {
          headers: { 'Content-Type': 'text/event-stream' },
        });
      }),
    );
    const { unmount } = renderHook(() => useAlertStream(api));
    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].authorization).toBe(`Bearer ${TOKEN}`);
    expect(seen[0].url).not.toContain(TOKEN);
    unmount();
  });

  it('goes back to sign-in on a 401 and stops reconnecting', async () => {
    let calls = 0;
    server.use(
      http.get('*/clinical-api/api/v1/alerts/stream', () => {
        calls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const onUnauthorized = vi.fn();
    const expiring = createApi({ getToken: () => 't', onUnauthorized });
    const { unmount } = renderHook(() => useAlertStream(expiring));
    await waitFor(() => expect(onUnauthorized).toHaveBeenCalledTimes(1));
    await new Promise((resolve) => setTimeout(resolve, 2500));
    expect(calls).toBe(1);
    unmount();
  });
});
