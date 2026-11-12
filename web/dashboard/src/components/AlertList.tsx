import { useCallback, useEffect, useState } from 'react';
import { useAckAlert } from '../hooks/useAckAlert';
import type { ChildStatus } from '../hooks/useEpisodes';
import type { AlertView } from '../lib/types';
import { useApi } from './ApiContext';
import { AlertRow } from './AlertRow';
import { EpisodeSummary } from './EpisodeSummary';

/** Lazy access to the contributing episodes of NEWS2 rows. */
export interface ChildTree {
  childrenOf: (parentId: string) => AlertView[];
  status: (parentId: string) => ChildStatus | undefined;
  load: (parentId: string) => void;
}

/**
 * Renders alert episodes and owns acknowledge handling: optimistic overrides live here, and are
 * dropped whenever the parent supplies a fresh list. `onChange` receives every acknowledged state
 * so the parent's store stays the single source of truth.
 */
export function AlertList({
  alerts,
  showPatient = true,
  freshIds = [],
  escalatedIds = [],
  leavingIds = [],
  empty = 'No alerts match.',
  onChange,
  tree,
}: {
  alerts: AlertView[];
  showPatient?: boolean;
  freshIds?: string[];
  escalatedIds?: string[];
  leavingIds?: string[];
  empty?: string;
  onChange?: (alert: AlertView) => void;
  tree?: ChildTree;
}) {
  const api = useApi();
  const [overrides, setOverrides] = useState<Record<string, AlertView>>({});
  useEffect(() => setOverrides((o) => (Object.keys(o).length ? {} : o)), [alerts]);
  const apply = useCallback(
    (alert: AlertView) => {
      setOverrides((o) => ({ ...o, [alert.alertId]: alert }));
      onChange?.(alert);
    },
    [onChange],
  );
  const { ack, pending, failure } = useAckAlert(api, apply);

  if (alerts.length === 0) return <p className="muted empty">{empty}</p>;
  return (
    <ul className="alerts" data-testid="alert-list">
      {alerts.map((a) => {
        const shown = overrides[a.alertId] ?? a;
        return (
          <AlertRow
            key={a.alertId}
            alert={shown}
            pending={pending === a.alertId}
            failure={failure}
            onAck={ack}
            showPatient={showPatient}
            fresh={freshIds.includes(a.alertId)}
            escalated={escalatedIds.includes(a.alertId)}
            leaving={leavingIds.includes(a.alertId)}
            onExpand={tree?.load}
            renderChildren={tree && ((parent) => <ChildEpisodes parent={parent} tree={tree} />)}
          />
        );
      })}
    </ul>
  );
}

/** The expanded contributing episodes of one NEWS2 row: loading, error with retry, or the list. */
function ChildEpisodes({ parent, tree }: { parent: AlertView; tree: ChildTree }) {
  const status = tree.status(parent.alertId);
  const kids = tree.childrenOf(parent.alertId);
  if (status === 'error') {
    return (
      <p role="alert" className="notice notice-error">
        Could not load contributing alerts.{' '}
        <button type="button" onClick={() => tree.load(parent.alertId)}>
          Try again
        </button>
      </p>
    );
  }
  if (kids.length === 0) {
    return status === 'loading' || status === undefined ? (
      <p role="status" className="muted">
        Loading contributing alerts…
      </p>
    ) : (
      <p className="muted">No ongoing contributing alerts.</p>
    );
  }
  return (
    <ul className="children" aria-label={`Contributing alerts of ${parent.patientId} NEWS2`}>
      {kids.map((child) => (
        <EpisodeSummary key={child.alertId} alert={child} variant="child" />
      ))}
    </ul>
  );
}
