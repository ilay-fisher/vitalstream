import { useMemo, useState } from 'react';
import { useResource } from '../hooks/useResource';
import { news2FromLatest } from '../lib/news2';
import { METRIC_ORDER, type AlertView, type Resolution } from '../lib/types';
import { useApi } from './ApiContext';
import { AlertList } from './AlertList';
import { News2Badge } from './badges';
import { VitalChart } from './VitalChart';

const RANGES = [
  { key: '15m', label: '15 min', ms: 15 * 60_000 },
  { key: '1h', label: '1 hour', ms: 3_600_000 },
  { key: '6h', label: '6 hours', ms: 6 * 3_600_000 },
  { key: '24h', label: '24 hours', ms: 24 * 3_600_000 },
] as const;

const NO_ALERTS: AlertView[] = [];

/** One patient: six charts over a chosen range and resolution, plus the alert history. */
export function PatientDetail({ id }: { id: string }) {
  const api = useApi();
  const [range, setRange] = useState<(typeof RANGES)[number]['key']>('1h');
  const [resolution, setResolution] = useState<Resolution>('raw');
  const window = useMemo(() => {
    const to = Date.now();
    const ms = RANGES.find((r) => r.key === range)?.ms ?? 3_600_000;
    return { from: to - ms, to };
    // Recomputed on range change only: a stable window keeps charts from jittering on re-render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range, resolution, id]);

  const patient = useResource(() => api.getPatient(id), [api, id]);
  const alerts = useResource(() => api.listAlerts({ patientId: id, size: 50 }), [api, id]);
  const series = useResource(
    () =>
      Promise.all(
        METRIC_ORDER.map((metric) =>
          api.getVitals(id, {
            metric,
            from: new Date(window.from),
            to: new Date(window.to),
            resolution,
          }),
        ),
      ),
    [api, id, window, resolution],
  );

  const p = patient.data;
  return (
    <section aria-labelledby="patient-title">
      <p className="crumb">
        <a href="#/">Ward overview</a>
      </p>
      <div className="page-head">
        <div>
          <h1 id="patient-title">{p ? p.name : id}</h1>
          <p className="muted">{p ? `${p.id} · bed ${p.bed} · ${p.ward}` : 'Loading patient…'}</p>
        </div>
        <div className="head-tools">
          {p && <News2Badge score={news2FromLatest(p.latest)} />}
          <div role="group" aria-label="Time range" className="seg">
            {RANGES.map((r) => (
              <button key={r.key} aria-pressed={range === r.key} onClick={() => setRange(r.key)}>
                {r.label}
              </button>
            ))}
          </div>
          <div role="group" aria-label="Resolution" className="seg">
            {(['raw', 'minute'] as const).map((r) => (
              <button key={r} aria-pressed={resolution === r} onClick={() => setResolution(r)}>
                {r === 'raw' ? 'Raw' : 'Per minute'}
              </button>
            ))}
          </div>
        </div>
      </div>

      {(patient.error || series.error) && (
        <div role="alert" className="notice notice-error">
          Could not load vitals: {(patient.error ?? series.error)?.message}{' '}
          <button
            onClick={() => {
              patient.reload();
              series.reload();
            }}
          >
            Try again
          </button>
        </div>
      )}

      <div className="charts">
        {METRIC_ORDER.map((metric, i) => (
          <VitalChart
            key={metric}
            metric={metric}
            points={series.data?.[i] ?? []}
            from={window.from}
            to={window.to}
            loading={series.loading}
          />
        ))}
      </div>

      <h2>Alert history</h2>
      <AlertList
        alerts={alerts.data?.items ?? NO_ALERTS}
        showPatient={false}
        empty="No alerts for this patient."
      />
    </section>
  );
}
