import { useEffect, useRef, useState } from 'react';
import { useAlertStream } from '../hooks/useAlertStream';
import { useResource } from '../hooks/useResource';
import { SEVERITY_LABEL, TYPE_LABEL } from '../lib/format';
import type { AlertStatus, AlertView, AlertType, Severity } from '../lib/types';
import { useApi } from './ApiContext';
import { AlertList } from './AlertList';
import { StreamStatus } from './StreamStatus';

const NO_ALERTS: AlertView[] = [];

/** Alert feed with severity/type/status filters and full-text search (`q` on /alerts). */
export function AlertFeed() {
  const api = useApi();
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [type, setType] = useState<AlertType | ''>('');
  const [status, setStatus] = useState<AlertStatus | ''>('');
  const [text, setText] = useState('');
  const [q, setQ] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(timer);
  }, [text]);

  const list = useResource(
    () =>
      api.listAlerts({
        severity: severity || undefined,
        type: type || undefined,
        status: status || undefined,
        q: q || undefined,
        size: 100,
      }),
    [api, severity, type, status, q],
  );
  const reloadRef = useRef(list.reload);
  reloadRef.current = list.reload;
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const stream = useAlertStream(api, () => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => reloadRef.current(), 400);
  });
  useEffect(() => () => clearTimeout(timer.current), []);

  const items = list.data?.items ?? NO_ALERTS;
  return (
    <section aria-labelledby="feed-title">
      <div className="page-head">
        <div>
          <h1 id="feed-title">Alert feed</h1>
          <p className="muted">
            {list.data ? `${list.data.total} alerts` : 'Loading alerts…'}
            {q ? ` matching “${q}”` : ''}
          </p>
        </div>
        <StreamStatus connection={stream.connection} />
      </div>

      <form className="filters" role="search" onSubmit={(e) => e.preventDefault()}>
        <label>
          Search alerts
          <input
            type="search"
            value={text}
            placeholder="Message text, e.g. hypotension"
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <label>
          Severity
          <select value={severity} onChange={(e) => setSeverity(e.target.value as Severity | '')}>
            <option value="">All</option>
            {(Object.keys(SEVERITY_LABEL) as Severity[]).reverse().map((s) => (
              <option key={s} value={s}>
                {SEVERITY_LABEL[s]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Type
          <select value={type} onChange={(e) => setType(e.target.value as AlertType | '')}>
            <option value="">All</option>
            {(Object.keys(TYPE_LABEL) as AlertType[]).map((t) => (
              <option key={t} value={t}>
                {TYPE_LABEL[t]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value as AlertStatus | '')}>
            <option value="">All</option>
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </label>
      </form>

      {list.error && (
        <div role="alert" className="notice notice-error">
          Could not load alerts: {list.error.message}{' '}
          <button onClick={list.reload}>Try again</button>
        </div>
      )}
      <AlertList
        alerts={items}
        freshIds={stream.fresh}
        empty={
          q || severity || type || status ? 'No alerts match these filters.' : 'No alerts yet.'
        }
      />
    </section>
  );
}
