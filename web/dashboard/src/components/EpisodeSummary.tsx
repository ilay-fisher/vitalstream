import { useNow } from '../hooks/useNow';
import { episodeLabel, ongoingFor, relativeTime } from '../lib/format';
import type { AlertView } from '../lib/types';
import { SeverityBadge } from './badges';

/** "×14" with a spoken equivalent, so the multiplier is not a bare symbol. */
export function Occurrences({ count }: { count: number }) {
  return (
    <span className="occurrences" aria-label={`${count} occurrences`} title={`${count} detections`}>
      ×{count}
    </span>
  );
}

/**
 * Compact one-line-ish episode for the nested "contributing alerts" list and the resolved
 * section. Read-only: acknowledging happens on the full row.
 */
export function EpisodeSummary({
  alert,
  variant,
  showPatient = false,
}: {
  alert: AlertView;
  variant: 'child' | 'resolved';
  showPatient?: boolean;
}) {
  const now = useNow();
  const resolved = variant === 'resolved';
  return (
    <li
      className={`episode-summary alert-${alert.severity.toLowerCase()}`}
      data-testid={`${resolved ? 'resolved' : 'child'}-${alert.alertId}`}
    >
      <div className="alert-meta">
        <SeverityBadge severity={alert.severity} />
        <span className="type">{episodeLabel(alert)}</span>
        {showPatient && (
          <a href={`#/patients/${alert.patientId}`} className="pid">
            {alert.patientId}
          </a>
        )}
        <Occurrences count={alert.occurrences} />
        {resolved ? (
          <>
            <time dateTime={alert.resolvedAt ?? alert.lastSeenAt}>
              {`Resolved ${relativeTime(alert.resolvedAt ?? alert.lastSeenAt, now)}`}
            </time>
            <span>{`lasted ${ongoingFor(alert.firstSeenAt, Date.parse(alert.lastSeenAt))}`}</span>
          </>
        ) : (
          <>
            <time dateTime={alert.lastSeenAt}>{`seen ${relativeTime(alert.lastSeenAt, now)}`}</time>
            <span className="status" data-status={alert.status}>
              {alert.status === 'ACKNOWLEDGED'
                ? `Acknowledged by ${alert.acknowledgedBy ?? 'unknown'}`
                : 'Open'}
            </span>
          </>
        )}
      </div>
      <p className="alert-message">{alert.message}</p>
    </li>
  );
}
