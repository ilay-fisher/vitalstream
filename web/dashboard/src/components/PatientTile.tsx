import { formatValue, isOutOfNormal } from '../lib/format';
import { news2FromLatest } from '../lib/news2';
import { METRIC_ORDER, METRICS, type PatientSummary } from '../lib/types';
import { News2Badge } from './badges';

/** One bed on the ward: identity, NEWS2, open alerts and the six latest values. */
export function PatientTile({
  patient,
  highlight = false,
}: {
  patient: PatientSummary;
  highlight?: boolean;
}) {
  const score = news2FromLatest(patient.latest);
  const attention = (score ?? 0) >= 5;
  return (
    <a
      className={`tile${attention ? ' tile-attention' : ''}${highlight ? ' tile-fresh' : ''}`}
      href={`#/patients/${patient.id}`}
      data-testid={`tile-${patient.id}`}
      aria-label={`${patient.name}, bed ${patient.bed}, ${patient.openAlertCount} open alerts`}
    >
      <header>
        <div>
          <h3>{patient.name}</h3>
          <p>
            {patient.id} · bed {patient.bed} · {patient.ward}
          </p>
        </div>
        <News2Badge score={score} />
      </header>
      <dl className="vitals">
        {METRIC_ORDER.map((metric) => {
          const latest = patient.latest[metric];
          const info = METRICS[metric];
          const out = latest ? isOutOfNormal(metric, latest.value) : false;
          const low = latest ? latest.value < info.normalMin : false;
          return (
            <div key={metric} className={out ? 'out' : undefined}>
              <dt>{info.short}</dt>
              <dd>
                {latest ? formatValue(metric, latest.value) : '–'}
                {out && (
                  <span className="dir" title="Outside normal range">
                    <span aria-hidden="true">{low ? '▼' : '▲'}</span>
                    <span className="sr-only">{low ? ' low' : ' high'}</span>
                  </span>
                )}
              </dd>
            </div>
          );
        })}
      </dl>
      <footer className={patient.openAlertCount > 0 ? 'has-alerts' : undefined}>
        {patient.openAlertCount > 0
          ? `${patient.openAlertCount} open ${patient.openAlertCount === 1 ? 'alert' : 'alerts'}`
          : 'No open alerts'}
      </footer>
    </a>
  );
}
