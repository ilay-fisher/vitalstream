import { useCallback, useEffect, useReducer, useRef } from 'react';
import { newCorrelationId, type Api } from '../lib/api';
import { chatReducer, initialChatState, parseCopilotEvent } from '../lib/chatReducer';
import { SseHttpError, streamSse } from '../lib/sse';

/**
 * Copilot conversation over SSE. The session id comes from the server's `session` event and is
 * sent back on follow-ups; it is never generated here (the server ignores ids it did not issue).
 */
export function useChat(api: Api, patientId?: string) {
  const [state, dispatch] = useReducer(chatReducer, initialChatState);
  const sessionRef = useRef<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  sessionRef.current = state.sessionId;

  useEffect(() => () => abortRef.current?.abort(), []);

  const send = useCallback(
    async (text: string) => {
      const message = text.trim();
      if (!message) return;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      dispatch({ type: 'send', text: message, id: newCorrelationId() });
      try {
        await streamSse(
          {
            url: api.chatUrl,
            method: 'POST',
            // The user's own token: the copilot checks it and acts on their behalf.
            headers: { ...api.authHeaders(), 'X-Correlation-Id': newCorrelationId() },
            body: {
              message,
              ...(sessionRef.current ? { sessionId: sessionRef.current } : {}),
              ...(patientId ? { patientId } : {}),
            },
            signal: controller.signal,
          },
          (raw) => {
            const event = parseCopilotEvent(raw);
            if (event) dispatch({ type: 'event', event });
          },
        );
      } catch (error) {
        if (controller.signal.aborted) return;
        if (error instanceof SseHttpError && error.status === 401) {
          api.handleUnauthorized();
          return;
        }
        dispatch({
          type: 'failed',
          message: error instanceof Error ? error.message : 'Connection to the copilot was lost.',
        });
      }
    },
    [api, patientId],
  );

  const stop = useCallback(() => abortRef.current?.abort(), []);
  const reset = useCallback(() => {
    abortRef.current?.abort();
    dispatch({ type: 'reset' });
  }, []);

  return { state, send, stop, reset };
}
