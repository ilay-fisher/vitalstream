import { useState, type FormEvent } from 'react';
import { relativeTime, TYPE_LABEL } from '../lib/format';
import type { AlertView } from '../lib/types';
import type { AckFailure } from '../hooks/useAckAlert';
import { SeverityBadge } from './badges';

const CLINICIAN_KEY = 'vitalstream.clinician';

function storedClinician(): string {
  try {
    return localStorage.getItem(CLINICIAN_KEY) ?? '';
  } catch {
    return '';
  }
}

interface Props {
  alert: AlertView;
  pending: boolean;
  failure: AckFailure | null;
  onAck: (alert: AlertView, by: string, note: string) => void;
  showPatient?: boolean;
  fresh?: boolean;
}

/** One alert with an inline acknowledge form (asks who acknowledges and an optional note). */
export function AlertRow({
  alert,
  pending,
  failure,
  onAck,
  showPatient = true,
  fresh = false,
}: Props) {
  const [editing, setEditing] = useState(false);
  const [by, setBy] = useState(storedClinician);
  const [note, setNote] = useState('');

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!by.trim()) return;
    try {
      localStorage.setItem(CLINICIAN_KEY, by.trim());
    } catch {
      /* ignore */
    }
    onAck(alert, by.trim(), note.trim());
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
              <label>
                Acknowledged by
                <input value={by} onChange={(e) => setBy(e.target.value)} required autoFocus />
              </label>
              <label>
                Note (optional)
                <input value={note} onChange={(e) => setNote(e.target.value)} />
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
            <button onClick={() => setEditing(true)} disabled={pending}>
              Acknowledge
            </button>
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
