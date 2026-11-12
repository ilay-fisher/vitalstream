import { useSyncExternalStore } from 'react';

/** How often "ongoing 12 min" and "seen 5 s ago" labels refresh. */
export const NOW_TICK_MS = 5000;

const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;
const second = () => Math.floor(Date.now() / 1000) * 1000;
let snapshot = second();

function subscribe(listener: () => void): () => void {
  if (listeners.size === 0) {
    snapshot = second();
    timer = setInterval(() => {
      snapshot = second();
      listeners.forEach((notify) => notify());
    }, NOW_TICK_MS);
  }
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) clearInterval(timer);
  };
}

/**
 * Current time that re-renders subscribers every few seconds. All rows share one interval,
 * which runs only while at least one component is mounted.
 */
export function useNow(): number {
  // Before the first subscriber the stored snapshot is stale, so read the clock (whole seconds keep
  // the value stable across the two reads React makes per render).
  return useSyncExternalStore(subscribe, () => (listeners.size === 0 ? second() : snapshot));
}
