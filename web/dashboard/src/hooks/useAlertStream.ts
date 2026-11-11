import { useEffect, useReducer, useRef } from 'react';
import { newCorrelationId, normalizeAlert, type Api } from '../lib/api';
import { initialLiveAlerts, liveAlertsReducer } from '../lib/alertsReducer';
import { streamSse } from '../lib/sse';

const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 15000;

/**
 * Subscribes to `/api/v1/alerts/stream` (SSE event `alert`) and keeps reconnecting with capped
 * backoff. Uses fetch rather than EventSource so the request carries X-Correlation-Id.
 * `onAlert` is called for every received alert (after the state update is queued).
 */
export function useAlertStream(api: Api, onAlert?: () => void) {
  const [state, dispatch] = useReducer(liveAlertsReducer, initialLiveAlerts);
  const onAlertRef = useRef(onAlert);
  onAlertRef.current = onAlert;

  useEffect(() => {
    const controller = new AbortController();
    let attempt = 0;

    async function run() {
      while (!controller.signal.aborted) {
        try {
          dispatch({
            type: 'connection',
            connection: attempt === 0 ? 'connecting' : 'reconnecting',
          });
          await streamSse(
            {
              url: api.alertStreamUrl,
              headers: { 'X-Correlation-Id': newCorrelationId() },
              signal: controller.signal,
              onOpen: () => {
                attempt = 0;
                dispatch({ type: 'connection', connection: 'open' });
              },
            },
            (message) => {
              attempt = 0;
              dispatch({ type: 'connection', connection: 'open' });
              if (message.event !== 'alert') return;
              try {
                dispatch({ type: 'alert', alert: normalizeAlert(JSON.parse(message.data)) });
                onAlertRef.current?.();
              } catch {
                /* ignore a malformed event, keep the stream */
              }
            },
          );
        } catch {
          if (controller.signal.aborted) return;
        }
        if (controller.signal.aborted) return;
        attempt += 1;
        dispatch({ type: 'connection', connection: 'reconnecting' });
        const delay = Math.min(RECONNECT_BASE_MS * 2 ** attempt, RECONNECT_MAX_MS);
        await new Promise((resolve) => setTimeout(resolve, delay));
      }
    }
    void run();
    return () => controller.abort();
  }, [api]);

  return { ...state, clearFresh: () => dispatch({ type: 'clearFresh' }) };
}
