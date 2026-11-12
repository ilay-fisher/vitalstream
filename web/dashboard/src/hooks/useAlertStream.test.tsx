import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { createApi } from '../lib/api';
import { mockState } from '../mocks/handlers';
import { server } from '../test/server';
import { useAlertStream } from './useAlertStream';

const api = createApi({ getToken: () => 'tok-stream' });

describe('useAlertStream', () => {
  it('connects, receives alert events and notifies the caller', async () => {
    mockState.streamIntervalMs = 20;
    const onAlert = vi.fn();
    const { result, unmount } = renderHook(() => useAlertStream(api, onAlert));
    await waitFor(() => expect(result.current.alerts.length).toBeGreaterThanOrEqual(2));
    expect(result.current.connection).toBe('open');
    expect(result.current.fresh.length).toBeGreaterThanOrEqual(2);
    expect(onAlert).toHaveBeenCalled();
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
    expect(seen[0].authorization).toBe('Bearer tok-stream');
    expect(seen[0].url).not.toContain('tok-stream');
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
