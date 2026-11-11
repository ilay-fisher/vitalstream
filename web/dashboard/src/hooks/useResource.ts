import { useCallback, useEffect, useRef, useState } from 'react';

export interface Resource<T> {
  data: T | undefined;
  loading: boolean;
  error: Error | undefined;
  reload: () => void;
}

/**
 * Loads data with `load` whenever `deps` change and exposes `reload`. A stale response (the deps
 * changed while it was in flight) is dropped, and a reload keeps the previous data visible.
 */
export function useResource<T>(load: () => Promise<T>, deps: unknown[]): Resource<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let current = true;
    setLoading(true);
    loadRef
      .current()
      .then((value) => {
        if (!current) return;
        setData(value);
        setError(undefined);
      })
      .catch((e: unknown) => {
        if (current) setError(e instanceof Error ? e : new Error(String(e)));
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, loading, error, reload };
}
