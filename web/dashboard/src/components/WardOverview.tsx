import { useEffect, useMemo, useRef, useState } from 'react';
import { useAlertStream } from '../hooks/useAlertStream';
import { useResource } from '../hooks/useResource';
import { news2FromLatest } from '../lib/news2';
import { useApi } from './ApiContext';
import { PatientTile } from './PatientTile';
import { StreamStatus } from './StreamStatus';

/** Ward grid. Live alerts trigger a debounced refetch so tiles, NEWS2 and counts stay current. */
export function WardOverview() {
  const api = useApi();
  const patients = useResource(() => api.listPatients(), [api]);
  const [ward, setWard] = useState('ALL');
  const [fresh, setFresh] = useState<string[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const reloadRef = useRef(patients.reload);
  reloadRef.current = patients.reload;

  const stream = useAlertStream(api, () => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => reloadRef.current(), 600);
  });

  const newest = stream.alerts[0];
  useEffect(() => {
    if (!newest) return;
    setFresh([newest.patientId]);
    const clear = setTimeout(() => setFresh([]), 6000);
    return () => clearTimeout(clear);
  }, [newest]);

  useEffect(() => () => clearTimeout(timer.current), []);

  const wards = useMemo(
    () => ['ALL', ...new Set((patients.data ?? []).map((p) => p.ward))],
    [patients.data],
  );
  const sorted = useMemo(
    () =>
      (patients.data ?? [])
        .filter((p) => ward === 'ALL' || p.ward === ward)
        .sort(
          (a, b) =>
            (news2FromLatest(b.latest) ?? -1) - (news2FromLatest(a.latest) ?? -1) ||
            b.openAlertCount - a.openAlertCount ||
            a.id.localeCompare(b.id),
        ),
    [patients.data, ward],
  );
  const attention = sorted.filter((p) => (news2FromLatest(p.latest) ?? 0) >= 5).length;

  return (
    <section aria-labelledby="ward-title">
      <div className="page-head">
        <div>
          <h1 id="ward-title">Ward overview</h1>
          <p className="muted">
            {patients.data
              ? `${sorted.length} patients, ${attention} with NEWS2 of 5 or more. Sorted by risk.`
              : 'Loading patients…'}
          </p>
        </div>
        <div className="head-tools">
          <StreamStatus connection={stream.connection} />
          <div role="group" aria-label="Ward filter" className="seg">
            {wards.map((w) => (
              <button key={w} aria-pressed={ward === w} onClick={() => setWard(w)}>
                {w === 'ALL' ? 'All wards' : w.length <= 3 ? w : w[0] + w.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>
      </div>
      {patients.error && (
        <div role="alert" className="notice notice-error">
          Could not load patients: {patients.error.message}{' '}
          <button onClick={patients.reload}>Try again</button>
        </div>
      )}
      <div className="grid" data-testid="ward-grid">
        {sorted.map((p) => (
          <PatientTile key={p.id} patient={p} highlight={fresh.includes(p.id)} />
        ))}
      </div>
      {patients.loading && !patients.data && <p className="muted">Loading…</p>}
    </section>
  );
}
