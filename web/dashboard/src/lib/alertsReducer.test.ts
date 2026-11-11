import { describe, expect, it } from 'vitest';
import { buildAlerts } from '../mocks/data';
import { initialLiveAlerts, liveAlertsReducer, MAX_LIVE_ALERTS } from './alertsReducer';

const [a, b] = buildAlerts();

describe('liveAlertsReducer', () => {
  it('prepends new alerts and marks them fresh', () => {
    let s = liveAlertsReducer(initialLiveAlerts, { type: 'alert', alert: a });
    s = liveAlertsReducer(s, { type: 'alert', alert: b });
    expect(s.alerts.map((x) => x.alertId)).toEqual([b.alertId, a.alertId]);
    expect(s.fresh).toEqual([a.alertId, b.alertId]);
  });

  it('upserts an alert that is repeated with a new status', () => {
    let s = liveAlertsReducer(initialLiveAlerts, { type: 'alert', alert: a });
    s = liveAlertsReducer(s, { type: 'alert', alert: { ...a, status: 'ACKNOWLEDGED' } });
    expect(s.alerts).toHaveLength(1);
    expect(s.alerts[0].status).toBe('ACKNOWLEDGED');
    expect(s.fresh).toEqual([a.alertId]);
  });

  it('tracks the connection and clears the fresh marks', () => {
    let s = liveAlertsReducer(initialLiveAlerts, { type: 'connection', connection: 'open' });
    expect(s.connection).toBe('open');
    s = liveAlertsReducer(s, { type: 'alert', alert: a });
    s = liveAlertsReducer(s, { type: 'clearFresh' });
    expect(s.fresh).toEqual([]);
  });

  it('caps the list', () => {
    let s = initialLiveAlerts;
    for (let i = 0; i < MAX_LIVE_ALERTS + 5; i++) {
      s = liveAlertsReducer(s, { type: 'alert', alert: { ...a, alertId: `id-${i}` } });
    }
    expect(s.alerts).toHaveLength(MAX_LIVE_ALERTS);
    expect(s.alerts[0].alertId).toBe(`id-${MAX_LIVE_ALERTS + 4}`);
  });
});
