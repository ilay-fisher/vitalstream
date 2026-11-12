import { useId, useState, type FormEvent } from 'react';
import { useAuth } from '../hooks/useAuth';
import { relativeTime, TYPE_LABEL } from '../lib/format';
import type { AlertView } from '../lib/types';
import type { AckFailure } from '../hooks/useAckAlert';
import { SeverityBadge } from './badges';

interface Props {
  alert: AlertView;
  pending: boolean;
  failure: AckFailure | null;
  onAck: (alert: AlertView, note: string) => void;
  showPatient?: boolean;
  fresh?: boolean;
}

/**
 * One alert with an inline acknowledge form. The acknowledging user is the signed-in one (the
 * server takes it from the token), so the form only asks for an optional note. Viewers get a
 * disabled button that says why.
 */
export function AlertRow({
  alert,
  pending,
  failure,
  onAck,
  showPatient = true,
  fresh = false,
}: Props) {
  const [editing, setEditing] = useState(false);
  const [note, setNote] = useState('');
  const { session, canAcknowledge } = useAuth();
  const hintId = useId();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onAck(alert, note.trim());
    setEditing(false);
    setNote('');
  };

  return (
    <li
      className={`alert alert-${alert.severity.toLowerCase()}${alert.status !== 'OPEN' ? ' alert-done' : ''}${fresh ? ' alert-fresh' : ''}`}
      data-testid={`alert-${alert.alertId}`}
    >
      <div className="alert-main">
        <div className="alert-meta">
          <SeverityBadge severity={alert.severity} />
          <span className="type">{TYPE_LABEL[alert.type]}</span>
          {showPatient && (
            <a href={`#/patients/${alert.patientId}`} className="pid">
              {alert.patientId}
            </a>
          )}
          <time dateTime={alert.detectedAt} title={alert.detectedAt}>
            {relativeTime(alert.detectedAt)}
          </time>
        </div>
        <p className="alert-message">{alert.message}</p>
        {failure && failure.alertId === alert.alertId && (
          <p
            role="alert"
            className={`notice notice-${failure.kind === 'conflict' ? 'warn' : 'error'}`}
          >
            {failure.message}
          </p>
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
    </li>
  );
}
