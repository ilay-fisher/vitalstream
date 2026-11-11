import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { createApi } from '../lib/api';
import type { AlertView } from '../lib/types';
import { buildAlerts, CONFLICT_ALERT_ID } from '../mocks/data';
import { mockState } from '../mocks/handlers';
import { server } from '../test/server';
import { useAckAlert } from './useAckAlert';

const api = createApi();

function setup(alert: AlertView) {
  const shown: AlertView[] = [];
  const hook = renderHook(() => useAckAlert(api, (a) => shown.push(a)));
  return { shown, hook, alert };
}

describe('useAckAlert', () => {
  it('shows the acknowledgement optimistically, then the server state', async () => {
    const alert = mockState.alerts[0];
    const { shown, hook } = setup(alert);
    await act(async () => {
      await hook.result.current.ack(alert, 'dr.cohen', 'on it');
    });
    expect(shown[0]).toMatchObject({ status: 'ACKNOWLEDGED', acknowledgedBy: 'dr.cohen' });
    expect(shown[shown.length - 1]).toMatchObject({ status: 'ACKNOWLEDGED', version: 1 });
    expect(hook.result.current.failure).toBeNull();
    expect(hook.result.current.pending).toBeNull();
  });

  it('on 409 fetches the current state and reports a conflict', async () => {
    const alert = buildAlerts().find((a) => a.alertId === CONFLICT_ALERT_ID) as AlertView;
    const { shown, hook } = setup(alert);
    await act(async () => {
      await hook.result.current.ack(alert, 'dr.cohen', '');
    });
    await waitFor(() => expect(hook.result.current.failure?.kind).toBe('conflict'));
    const final = shown[shown.length - 1];
    expect(final.acknowledgedBy).toBe('dr.levi');
    expect(hook.result.current.failure?.message).toMatch(/changed by someone else/);
  });

  it('on 409 falls back to the original alert when the refetch fails', async () => {
    server.use(
      http.post('*/alerts/:id/ack', () => HttpResponse.json({}, { status: 409 })),
      http.get('*/alerts/:id', () => HttpResponse.json({}, { status: 500 })),
    );
    const alert = mockState.alerts[0];
    const { shown, hook } = setup(alert);
    await act(async () => {
      await hook.result.current.ack(alert, 'dr.cohen', '');
    });
    expect(shown[shown.length - 1]).toEqual(alert);
    expect(hook.result.current.failure?.kind).toBe('conflict');
  });

  it('rolls back on any other failure', async () => {
    server.use(
      http.post('*/alerts/:id/ack', () =>
        HttpResponse.json({ detail: 'database down' }, { status: 500 }),
      ),
    );
    const alert = mockState.alerts[0];
    const { shown, hook } = setup(alert);
    await act(async () => {
      await hook.result.current.ack(alert, 'dr.cohen', '');
    });
    expect(shown[shown.length - 1]).toEqual(alert);
    expect(hook.result.current.failure).toMatchObject({ kind: 'failed', message: 'database down' });
    act(() => hook.result.current.clearFailure());
    expect(hook.result.current.failure).toBeNull();
  });

  it('sends exactly the contract body: by and note', async () => {
    let body: unknown;
    server.use(
      http.post('*/alerts/:id/ack', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ alertId: 'x', status: 'ACKNOWLEDGED' });
      }),
    );
    const alert = mockState.alerts[0];
    const { hook } = setup(alert);
    await act(async () => {
      await hook.result.current.ack(alert, 'dr.cohen', 'seen');
    });
    expect(body).toEqual({ by: 'dr.cohen', note: 'seen' });
  });
});
