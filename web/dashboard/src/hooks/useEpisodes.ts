import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import type { Api } from '../lib/api';
import { episodesReducer, initialEpisodes } from '../lib/episodes';
import type { AlertView } from '../lib/types';
import { useAlertStream } from './useAlertStream';

/** How long a new or escalated row stays highlighted. */
export const HIGHLIGHT_MS = 3000;
/** How long a resolved row fades out of the live list before it moves to "Resolved". */
export const LEAVE_MS = 1200;

export type ChildStatus = 'loading' | 'done' | 'error';

/**
 * Holds the episodes of a view. SSE `alert` events are upserted by alertId (never appended),
 * `load` merges fetched pages, and `apply` takes locally known changes such as acknowledgements.
 * Highlights and the fade-out of resolved rows expire on timers owned here.
 */
export function useEpisodes(api: Api) {
  const [state, dispatch] = useReducer(episodesReducer, initialEpisodes);
  const [childStatus, setChildStatus] = useState<Record<string, ChildStatus>>({});
  const stream = useAlertStream(api, (alert) => dispatch({ type: 'upsert', alert }));

  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  useEffect(() => {
    const schedule = (key: string, ms: number, run: () => void) => {
      if (timers.current.has(key)) return;
      timers.current.set(
        key,
        setTimeout(() => {
          timers.current.delete(key);
          run();
        }, ms),
      );
    };
    for (const id of new Set([...state.fresh, ...state.escalated])) {
      schedule(`highlight-${id}`, HIGHLIGHT_MS, () => dispatch({ type: 'expire', id }));
    }
    for (const id of state.leaving) {
      schedule(`leave-${id}`, LEAVE_MS, () => dispatch({ type: 'finishLeaving', id }));
    }
  }, [state.fresh, state.escalated, state.leaving]);
  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach(clearTimeout);
      pending.clear();
    };
  }, []);

  const load = useCallback(
    (items: AlertView[], options: { searched?: boolean; prune?: boolean } = {}) =>
      dispatch({ type: 'load', items, ...options }),
    [],
  );
  const apply = useCallback((alert: AlertView) => dispatch({ type: 'upsert', alert }), []);

  /** Fetches the non-resolved children of `parentId`; called the first time a row is expanded. */
  const loadChildren = useCallback(
    async (parentId: string) => {
      setChildStatus((c) => ({ ...c, [parentId]: 'loading' }));
      try {
        const page = await api.listAlerts({
          parentAlertId: parentId,
          status: ['OPEN', 'ACKNOWLEDGED'],
          size: 50,
        });
        dispatch({ type: 'load', items: page.items });
        setChildStatus((c) => ({ ...c, [parentId]: 'done' }));
      } catch {
        setChildStatus((c) => ({ ...c, [parentId]: 'error' }));
      }
    },
    [api],
  );

  return { state, load, apply, loadChildren, childStatus, connection: stream.connection };
}
