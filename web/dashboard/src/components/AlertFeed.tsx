import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useEpisodes } from '../hooks/useEpisodes';
import { useResource } from '../hooks/useResource';
import { selectChildren, selectLive, selectResolved, type FeedFilters } from '../lib/episodes';
import { SEVERITY_LABEL, TYPE_LABEL } from '../lib/format';
import type { AlertType, Severity } from '../lib/types';
import { useApi } from './ApiContext';
import { AlertList, type ChildTree } from './AlertList';
import { EpisodeSummary } from './EpisodeSummary';
import { StreamStatus } from './StreamStatus';

type LiveStatus = NonNullable<FeedFilters['status']> | '';

/**
 * The ward's alert feed: one row per ongoing episode (top-level, OPEN or ACKNOWLEDGED), kept live
 * by upserting SSE events by alertId. NEWS2 rows expand to their contributing episodes, and
 * recently resolved episodes sit in a collapsed section. Filters and search (`q`) go to the server.
 */
export function AlertFeed() {
  const api = useApi();
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [type, setType] = useState<AlertType | ''>('');
  const [status, setStatus] = useState<LiveStatus>('');
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [showResolved, setShowResolved] = useState(false);
  const resolvedId = useId();

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(timer);
  }, [text]);

  const episodes = useEpisodes(api);
  const { load, apply, loadChildren, state } = episodes;

  const liveList = useResource(
    () =>
      api.listAlerts({
        topLevel: true,
        status: status ? [status] : ['OPEN', 'ACKNOWLEDGED'],
        severity: severity || undefined,
        type: type || undefined,
        q: q || undefined,
        size: 100,
      }),
    [api, severity, type, status, q],
  );
  const resolvedList = useResource(
    () =>
      api.listAlerts({
        status: ['RESOLVED'],
        severity: severity || undefined,
        type: type || undefined,
        size: 20,
      }),
    [api, severity, type],
  );

  // The stream has no replay (CONTRACTS 4): after a gap, refetch and drop what resolved unseen.
  const wasReconnecting = useRef(false);
  const pruneNext = useRef(false);
  useEffect(() => {
    if (episodes.connection === 'reconnecting') wasReconnecting.current = true;
    if (episodes.connection === 'open' && wasReconnecting.current) {
      wasReconnecting.current = false;
      pruneNext.current = true;
      liveList.reload();
      resolvedList.reload();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [episodes.connection]);

  useEffect(() => {
    if (!liveList.data) return;
    load(liveList.data.items, { searched: true, prune: pruneNext.current });
    pruneNext.current = false;
  }, [liveList.data, load]);
  useEffect(() => {
    if (resolvedList.data) load(resolvedList.data.items);
  }, [resolvedList.data, load]);

  const filters: FeedFilters = useMemo(
    () => ({
      severity: severity || undefined,
      type: type || undefined,
      status: status || undefined,
      q: q || undefined,
    }),
    [severity, type, status, q],
  );
  const live = useMemo(() => selectLive(state, filters), [state, filters]);
  const resolved = useMemo(() => selectResolved(state, filters), [state, filters]);
  const tree: ChildTree = useMemo(
    () => ({
      childrenOf: (parentId) => selectChildren(state, parentId),
      status: (parentId) => episodes.childStatus[parentId],
      load: (parentId) => void loadChildren(parentId),
    }),
    [state, episodes.childStatus, loadChildren],
  );

  const filtered = Boolean(q || severity || type || status);
  const ongoing = live.filter((a) => !state.leaving.includes(a.alertId)).length;
  return (
    <section aria-labelledby="feed-title">
      <div className="page-head">
        <div>
          <h1 id="feed-title">Alert feed</h1>
          <p className="muted">
            {liveList.data
              ? `${ongoing} ongoing ${ongoing === 1 ? 'alert' : 'alerts'}`
              : 'Loading alerts…'}
            {q ? ` matching “${q}”` : ''}
          </p>
        </div>
        <StreamStatus connection={episodes.connection} />
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
          <select value={status} onChange={(e) => setStatus(e.target.value as LiveStatus)}>
            <option value="">Open and acknowledged</option>
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
          </select>
        </label>
      </form>

      {liveList.error && (
        <div role="alert" className="notice notice-error">
          Could not load alerts: {liveList.error.message}{' '}
          <button onClick={liveList.reload}>Try again</button>
        </div>
      )}
      {(liveList.data || liveList.error) && (
        <AlertList
          alerts={live}
          freshIds={state.fresh}
          escalatedIds={state.escalated}
          leavingIds={state.leaving}
          onChange={apply}
          tree={tree}
          empty={filtered ? 'No alerts match these filters.' : 'No ongoing alerts.'}
        />
      )}

      <div className="resolved">
        <h2>
          <button
            type="button"
            className="disclosure"
            aria-expanded={showResolved}
            aria-controls={resolvedId}
            onClick={() => setShowResolved(!showResolved)}
          >
            <span aria-hidden="true">{showResolved ? '▾' : '▸'}</span>
            {`Resolved (${resolved.length})`}
          </button>
        </h2>
        <div id={resolvedId}>
          {showResolved &&
            (resolved.length === 0 ? (
              <p className="muted">Nothing resolved recently.</p>
            ) : (
              <ul className="children" aria-label="Recently resolved alerts">
                {resolved.map((a) => (
                  <EpisodeSummary key={a.alertId} alert={a} variant="resolved" showPatient />
                ))}
              </ul>
            ))}
        </div>
      </div>
    </section>
  );
}
