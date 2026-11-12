import { useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useAuth } from '../hooks/useAuth';
import { useNow } from '../hooks/useNow';
import { episodeLabel, ongoingFor, relativeTime } from '../lib/format';
import type { AlertView } from '../lib/types';
import type { AckFailure } from '../hooks/useAckAlert';
import { SeverityBadge } from './badges';
import { Occurrences } from './EpisodeSummary';

interface Props {
  alert: AlertView;
  pending: boolean;
  failure: AckFailure | null;
  onAck: (alert: AlertView, note: string) => void;
  showPatient?: boolean;
  /** Episode appeared live (new row). */
  fresh?: boolean;
  /** Severity just went up. */
  escalated?: boolean;
  /** Resolved live: fading out of the ongoing list. */
  leaving?: boolean;
  /** Called the first time the contributing episodes are expanded (lazy fetch). */
  onExpand?: (alertId: string) => void;
  /** The expanded contributing episodes; rendered only while expanded. */
  renderChildren?: (alert: AlertView) => ReactNode;
}

/**
 * One alert episode: severity, type and metric, latest message, "×N" occurrences, how long it has
 * been ongoing and when it was last seen, plus the acknowledge form. The acknowledging user is the
 * signed-in one (the server takes it from the token), so the form only asks for an optional note.
 * Viewers get a disabled button that says why. A NEWS2 episode with `childCount > 0` has a
 * keyboard-operable disclosure for its contributing episodes.
 */
export function AlertRow({
  alert,
  pending,
  failure,
  onAck,
  showPatient = true,
  fresh = false,
  escalated = false,
  leaving = false,
  onExpand,
  renderChildren,
}: Props) {
  const [editing, setEditing] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [note, setNote] = useState('');
  const requested = useRef(false);
  const { session, canAcknowledge } = useAuth();
  const now = useNow();
  const hintId = useId();
  const childrenId = useId();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onAck(alert, note.trim());
    setEditing(false);
    setNote('');
  };

  const toggle = () => {
    if (!expanded && !requested.current) {
      requested.current = true;
      onExpand?.(alert.alertId);
    }
    setExpanded(!expanded);
  };

  const resolved = alert.status === 'RESOLVED';
  const canExpand = alert.childCount > 0 && onExpand !== undefined;
  const kids = `${alert.childCount} contributing ${alert.childCount === 1 ? 'alert' : 'alerts'}`;
  const classes = [
    'alert',
    `alert-${alert.severity.toLowerCase()}`,
    alert.status !== 'OPEN' ? 'alert-done' : '',
    fresh ? 'alert-fresh' : '',
    escalated ? 'alert-escalated' : '',
    leaving ? 'alert-leaving' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <li className={classes} data-testid={`alert-${alert.alertId}`}>
      <div className="alert-main">
        <div className="alert-meta">
          <SeverityBadge severity={alert.severity} />
          <span className="type">{episodeLabel(alert)}</span>
          {showPatient && (
            <a href={`#/patients/${alert.patientId}`} className="pid">
              {alert.patientId}
            </a>
          )}
          <Occurrences count={alert.occurrences} />
          <span>
            {resolved
              ? `lasted ${ongoingFor(alert.firstSeenAt, Date.parse(alert.lastSeenAt))}`
              : `ongoing ${ongoingFor(alert.firstSeenAt, now)}`}
          </span>
          <time dateTime={alert.lastSeenAt} title={alert.lastSeenAt}>
            {`seen ${relativeTime(alert.lastSeenAt, now)}`}
          </time>
        </div>
        <p className="alert-message">{alert.message}</p>
        {alert.status === 'OPEN' && alert.acknowledgedBy && (
          <p className="hint">{`Escalated after acknowledgement by ${alert.acknowledgedBy}`}</p>
        )}
        {failure && failure.alertId === alert.alertId && (
          <p
            role="alert"
            className={`notice notice-${failure.kind === 'conflict' ? 'warn' : 'error'}`}
          >
            {failure.message}
          </p>
        )}
        {canExpand && (
          <button
            type="button"
            className="disclosure"
            aria-expanded={expanded}
            aria-controls={childrenId}
            onClick={toggle}
          >
            <span aria-hidden="true">{expanded ? '▾' : '▸'}</span>
            {`${expanded ? 'Hide' : 'Show'} ${kids}`}
          </button>
        )}
      </div>
      <div className="alert-state">
        {alert.status === 'OPEN' ? (
          editing ? (
            <form
              onSubmit={submit}
              className="ack-form"
              aria-label={`Acknowledge alert ${alert.alertId}`}
            >
              <p className="acting-as">Acknowledging as {session?.name}</p>
              <label>
                Note (optional)
                <input value={note} onChange={(e) => setNote(e.target.value)} autoFocus />
              </label>
              <div className="row">
                <button type="submit" className="primary">
                  Confirm
                </button>
                <button type="button" onClick={() => setEditing(false)}>
                  Cancel
                </button>
              </div>
            </form>
          ) : (
            <>
              <button
                onClick={() => setEditing(true)}
                disabled={pending || !canAcknowledge}
                aria-describedby={canAcknowledge ? undefined : hintId}
              >
                Acknowledge
              </button>
              {!canAcknowledge && (
                <span id={hintId} className="hint muted">
                  Viewers cannot acknowledge alerts
                </span>
              )}
            </>
          )
        ) : (
          <span className="status" data-status={alert.status}>
            {alert.status === 'ACKNOWLEDGED'
              ? `Acknowledged by ${alert.acknowledgedBy ?? 'unknown'}${pending ? ' (saving…)' : ''}`
              : 'Resolved'}
          </span>
        )}
      </div>
      {canExpand && (
        <div id={childrenId} className="alert-children">
          {expanded && renderChildren?.(alert)}
        </div>
      )}
    </li>
  );
}
