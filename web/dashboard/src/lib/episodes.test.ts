import { describe, expect, it } from 'vitest';
import { buildAlerts, episodeId, NEWS2_PARENT_ID } from '../mocks/data';
import {
  episodesReducer,
  initialEpisodes,
  selectChildren,
  selectLive,
  selectResolved,
  type EpisodesState,
} from './episodes';
import type { AlertView } from './types';

const all = buildAlerts();
const fixture = (n: number): AlertView => all.find((a) => a.alertId === episodeId(n))!;
const loaded = (items: AlertView[] = all): EpisodesState =>
  episodesReducer(initialEpisodes, { type: 'load', items });

describe('episodesReducer', () => {
  it('upserts by alertId: an update replaces the row and never adds one', () => {
    const news2 = fixture(1);
    let s = loaded();
    const before = Object.keys(s.byId).length;
    s = episodesReducer(s, { type: 'upsert', alert: { ...news2, occurrences: 25, version: 1 } });
    expect(Object.keys(s.byId)).toHaveLength(before);
    expect(s.byId[news2.alertId].occurrences).toBe(25);
    expect(s.fresh).toEqual([]);
  });

  it('marks a brand new episode fresh', () => {
    const s = episodesReducer(initialEpisodes, { type: 'upsert', alert: fixture(1) });
    expect(s.fresh).toEqual([fixture(1).alertId]);
    expect(episodesReducer(s, { type: 'expire', id: fixture(1).alertId }).fresh).toEqual([]);
  });

  it('ignores a stale version', () => {
    let s = loaded();
    s = episodesReducer(s, {
      type: 'upsert',
      alert: { ...fixture(1), occurrences: 99, version: 5 },
    });
    s = episodesReducer(s, {
      type: 'upsert',
      alert: { ...fixture(1), occurrences: 30, version: 2 },
    });
    expect(s.byId[fixture(1).alertId].occurrences).toBe(99);
  });

  it('a reload never overwrites a newer live update', () => {
    let s = loaded();
    s = episodesReducer(s, {
      type: 'upsert',
      alert: { ...fixture(1), occurrences: 99, version: 5 },
    });
    s = episodesReducer(s, { type: 'load', items: [fixture(1)] });
    expect(s.byId[fixture(1).alertId].occurrences).toBe(99);
  });

  it('prunes ongoing top-level episodes missing from an authoritative reload', () => {
    let s = loaded();
    const keep = all.filter((a) => a.alertId !== fixture(9).alertId);
    s = episodesReducer(s, { type: 'load', items: keep, prune: true });
    expect(s.byId[fixture(9).alertId]).toBeUndefined();
    expect(s.byId[fixture(2).alertId]).toBeDefined();
    expect(s.byId[fixture(13).alertId]).toBeDefined();
  });

  it('flags an escalation (severity went up) and not a plain update', () => {
    const medium = fixture(8);
    let s = loaded();
    s = episodesReducer(s, { type: 'upsert', alert: { ...medium, occurrences: 9, version: 1 } });
    expect(s.escalated).toEqual([]);
    s = episodesReducer(s, { type: 'upsert', alert: { ...medium, severity: 'HIGH', version: 2 } });
    expect(s.escalated).toEqual([medium.alertId]);
    s = episodesReducer(s, { type: 'expire', id: medium.alertId });
    expect(s.escalated).toEqual([]);
  });

  it('keeps a resolved top-level row in the live list while it leaves', () => {
    const open = fixture(9);
    let s = loaded();
    s = episodesReducer(s, {
      type: 'upsert',
      alert: { ...open, status: 'RESOLVED', resolvedAt: new Date().toISOString(), version: 1 },
    });
    expect(s.leaving).toEqual([open.alertId]);
    expect(selectLive(s, {}).map((a) => a.alertId)).toContain(open.alertId);
    expect(selectResolved(s, {}).map((a) => a.alertId)).not.toContain(open.alertId);
    s = episodesReducer(s, { type: 'finishLeaving', id: open.alertId });
    expect(selectLive(s, {}).map((a) => a.alertId)).not.toContain(open.alertId);
    expect(selectResolved(s, {})[0].alertId).toBe(open.alertId);
  });
});

describe('episode selectors', () => {
  it('live = top-level, non-resolved, newest lastSeenAt first', () => {
    const live = selectLive(loaded(), {});
    expect(live.every((a) => a.parentAlertId === null && a.status !== 'RESOLVED')).toBe(true);
    const times = live.map((a) => Date.parse(a.lastSeenAt));
    expect(times).toEqual([...times].sort((x, y) => y - x));
    expect(live.map((a) => a.alertId)).not.toContain(fixture(2).alertId);
  });

  it('filters by severity, type and status, and by text', () => {
    const s = loaded();
    expect(selectLive(s, { severity: 'CRITICAL' }).every((a) => a.severity === 'CRITICAL')).toBe(
      true,
    );
    expect(selectLive(s, { type: 'NEWS2' }).every((a) => a.type === 'NEWS2')).toBe(true);
    expect(selectLive(s, { status: 'ACKNOWLEDGED' })).toEqual([]);
    expect(selectLive(s, { q: 'respiratory' }).map((a) => a.patientId)).toEqual(['P-0009']);
  });

  it('children of a parent exclude resolved ones; resolved list is newest first', () => {
    const s = loaded();
    expect(selectChildren(s, NEWS2_PARENT_ID).map((a) => a.alertId)).toEqual([
      fixture(2).alertId,
      fixture(3).alertId,
      fixture(10).alertId,
    ]);
    expect(selectResolved(s, {}).map((a) => a.alertId)).toEqual([
      fixture(13).alertId,
      fixture(14).alertId,
    ]);
  });
});
