import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { createApi } from '../lib/api';
import { mockState } from '../mocks/handlers';
import { server } from '../test/server';
import { useAlertStream } from './useAlertStream';

const api = createApi();

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
