import { useCallback, useEffect, useState } from 'react';
import { useAckAlert } from '../hooks/useAckAlert';
import type { AlertView } from '../lib/types';
import { useApi } from './ApiContext';
import { AlertRow } from './AlertRow';

/**
 * Renders alerts and owns acknowledge handling: optimistic overrides live here, and are dropped
 * whenever the parent supplies a fresh list.
 */
export function AlertList({
  alerts,
  showPatient = true,
  freshIds = [],
  empty = 'No alerts match.',
}: {
  alerts: AlertView[];
  showPatient?: boolean;
  freshIds?: string[];
  empty?: string;
}) {
  const api = useApi();
  const [overrides, setOverrides] = useState<Record<string, AlertView>>({});
  useEffect(() => setOverrides((o) => (Object.keys(o).length ? {} : o)), [alerts]);
  const apply = useCallback(
    (alert: AlertView) => setOverrides((o) => ({ ...o, [alert.alertId]: alert })),
    [],
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
          />
        );
      })}
    </ul>
  );
}
