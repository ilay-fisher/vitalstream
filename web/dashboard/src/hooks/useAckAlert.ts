import { useCallback, useState } from 'react';
import { ApiError, type Api } from '../lib/api';
import type { AlertView } from '../lib/types';

export interface AckFailure {
  alertId: string;
  kind: 'conflict' | 'failed';
  message: string;
}

/**
 * Acknowledge with optimistic UI. `apply` receives the alert to show: first the optimistic
 * ACKNOWLEDGED copy, then the server's answer. On failure the original is restored; on 409
 * (someone changed the alert first) the current server state is fetched and shown instead.
 */
export function useAckAlert(api: Api, apply: (alert: AlertView) => void) {
  const [pending, setPending] = useState<string | null>(null);
  const [failure, setFailure] = useState<AckFailure | null>(null);

  const ack = useCallback(
    async (alert: AlertView, by: string, note: string) => {
      setFailure(null);
      setPending(alert.alertId);
      apply({
        ...alert,
        status: 'ACKNOWLEDGED',
        acknowledgedBy: by,
        acknowledgedAt: new Date().toISOString(),
      });
      try {
        const saved = await api.ackAlert(alert.alertId, { by, note: note || undefined });
        apply(saved);
      } catch (error) {
        if (error instanceof ApiError && error.isConflict) {
          try {
            apply(await api.getAlert(alert.alertId));
          } catch {
            apply(alert);
          }
          setFailure({
            alertId: alert.alertId,
            kind: 'conflict',
            message: 'This alert was changed by someone else. Showing its current state.',
          });
        } else {
          apply(alert);
          setFailure({
            alertId: alert.alertId,
            kind: 'failed',
            message: error instanceof Error ? error.message : 'Could not acknowledge the alert.',
          });
        }
      } finally {
        setPending(null);
      }
    },
    [api, apply],
  );

  return { ack, pending, failure, clearFailure: () => setFailure(null) };
}
