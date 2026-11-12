import type { AlertView } from './types';

export type Connection = 'connecting' | 'open' | 'reconnecting';

export interface LiveAlertsState {
  /** Most recently changed episode first, de-duplicated by alertId, capped. */
  alerts: AlertView[];
  connection: Connection;
  /** Episode ids first seen live since the last `clearFresh`, for highlighting. */
  fresh: string[];
}

export type LiveAlertsAction =
  | { type: 'alert'; alert: AlertView }
  | { type: 'connection'; connection: Connection }
  | { type: 'clearFresh' };

export const MAX_LIVE_ALERTS = 200;

export const initialLiveAlerts: LiveAlertsState = {
  alerts: [],
  connection: 'connecting',
  fresh: [],
};

/**
 * Upserts SSE `alert` events by alertId: the stream repeats an episode on every change (update,
 * escalation, ack, resolve), so a repeat replaces the entry and moves it to the front.
 */
export function liveAlertsReducer(
  state: LiveAlertsState,
  action: LiveAlertsAction,
): LiveAlertsState {
  switch (action.type) {
    case 'alert': {
      const existing = state.alerts.findIndex((a) => a.alertId === action.alert.alertId);
      if (existing === -1) {
        return {
          ...state,
          alerts: [action.alert, ...state.alerts].slice(0, MAX_LIVE_ALERTS),
          fresh: [...state.fresh, action.alert.alertId],
        };
      }
      const alerts = state.alerts.filter((_, i) => i !== existing);
      return { ...state, alerts: [action.alert, ...alerts] };
    }
    case 'connection':
      return { ...state, connection: action.connection };
    case 'clearFresh':
      return { ...state, fresh: [] };
  }
}
