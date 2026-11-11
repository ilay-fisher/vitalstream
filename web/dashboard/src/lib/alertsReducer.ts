import type { AlertView } from './types';

export type Connection = 'connecting' | 'open' | 'reconnecting';

export interface LiveAlertsState {
  /** Newest first, de-duplicated by alertId, capped. */
  alerts: AlertView[];
  connection: Connection;
  /** Alert ids that arrived live since the last `clearFresh`, for highlighting. */
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

/** Upserts SSE `alert` events: the stream repeats an alert when its status changes. */
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
      const alerts = state.alerts.slice();
      alerts[existing] = action.alert;
      return { ...state, alerts };
    }
    case 'connection':
      return { ...state, connection: action.connection };
    case 'clearFresh':
      return { ...state, fresh: [] };
  }
}
