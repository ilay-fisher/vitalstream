import { act, renderHook, waitFor } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { createApi } from '../lib/api';
import { authStore } from '../lib/auth';
import { mockState } from '../mocks/handlers';
import { server } from '../test/server';
import { useChat } from './useChat';

const api = createApi();

describe('useChat', () => {
  it('streams a full answer: tool calls, citations, tokens, usage', async () => {
    mockState.tokenDelayMs = 0;
    const { result } = renderHook(() => useChat(api, 'P-0003'));
    await act(async () => {
      await result.current.send('How is P-0003?');
    });
    await waitFor(() => expect(result.current.state.status).toBe('idle'));
    const answer = result.current.state.messages[1];
    expect(answer.toolCalls.map((t) => [t.name, t.status])).toEqual([
      ['get_patient_vitals', 'ok'],
      ['search_guidelines', 'ok'],
    ]);
    expect(answer.citations).toHaveLength(2);
    expect(answer.text).toContain('sepsis');
    expect(answer.usage).toMatchObject({ outputTokens: 96 });
    expect(result.current.state.sessionId).toBeTruthy();
  });

  it('sends the server-issued session id on the follow-up and the patient context always', async () => {
    mockState.tokenDelayMs = 0;
    const bodies: Array<Record<string, unknown>> = [];
    const correlation: string[] = [];
    server.use(
      http.post('*/copilot-api/api/v1/chat', async ({ request }) => {
        bodies.push((await request.clone().json()) as Record<string, unknown>);
        correlation.push(request.headers.get('x-correlation-id') ?? '');
        return undefined; // fall through to the default streaming handler
      }),
    );
    const { result } = renderHook(() => useChat(api, 'P-0003'));
    await act(async () => {
      await result.current.send('first');
    });
    await waitFor(() => expect(result.current.state.status).toBe('idle'));
    await act(async () => {
      await result.current.send('second');
    });
    await waitFor(() => expect(result.current.state.status).toBe('idle'));
    expect(bodies[0]).toEqual({ message: 'first', patientId: 'P-0003' });
    expect(bodies[1]).toMatchObject({ message: 'second', patientId: 'P-0003' });
    expect(bodies[1].sessionId).toBe(result.current.state.sessionId);
    expect(correlation.every(Boolean)).toBe(true);
  });

  it('sends the signed-in user token on every chat call, never in the URL', async () => {
    mockState.tokenDelayMs = 0;
    const seen: Array<{ auth: string | null; url: string }> = [];
    server.use(
      http.post('*/copilot-api/api/v1/chat', ({ request }) => {
        seen.push({ auth: request.headers.get('authorization'), url: request.url });
        return undefined;
      }),
    );
    const { result } = renderHook(() => useChat(api));
    await act(async () => {
      await result.current.send('hi');
    });
    await waitFor(() => expect(result.current.state.status).toBe('idle'));
    expect(seen[0].auth).toBe(`Bearer ${authStore.getToken()}`);
    expect(seen[0].url).not.toContain(authStore.getToken() ?? 'x');
  });

  it('a 401 from the copilot sends the user back to sign-in without an error bubble', async () => {
    server.use(http.post('*/copilot-api/api/v1/chat', () => new Response('{}', { status: 401 })));
    const { result } = renderHook(() => useChat(api));
    await act(async () => {
      await result.current.send('hi');
    });
    expect(authStore.getSnapshot().session).toBeNull();
    expect(authStore.getSnapshot().notice).toMatch(/expired/i);
    expect(result.current.state.messages[1]?.error).toBeUndefined();
  });

  it('ignores blank messages', async () => {
    const { result } = renderHook(() => useChat(api));
    await act(async () => {
      await result.current.send('   ');
    });
    expect(result.current.state.messages).toEqual([]);
  });

  it('shows an error state when the request fails, and reset clears it', async () => {
    server.use(http.post('*/copilot-api/api/v1/chat', () => new Response('down', { status: 503 })));
    const { result } = renderHook(() => useChat(api));
    await act(async () => {
      await result.current.send('hello');
    });
    expect(result.current.state.status).toBe('error');
    expect(result.current.state.messages[1].error).toMatch(/503/);
    act(() => result.current.reset());
    expect(result.current.state.messages).toEqual([]);
  });

  it('stop aborts a running stream without an error state', async () => {
    mockState.tokenDelayMs = 50;
    const { result } = renderHook(() => useChat(api));
    act(() => {
      void result.current.send('long one');
    });
    await waitFor(() =>
      expect(result.current.state.messages[1]?.toolCalls.length).toBeGreaterThan(0),
    );
    act(() => result.current.stop());
    await new Promise((r) => setTimeout(r, 120));
    expect(result.current.state.status).toBe('streaming');
    expect(result.current.state.messages[1].error).toBeUndefined();
  });
});
