import { SEVERITY_RANK } from './format';
import type { AlertStatus, AlertType, AlertView, Severity } from './types';

/**
 * Client-side store of alert episodes (CONTRACTS 3.4), keyed by alertId. Fetched pages and SSE
 * events are merged into it, so an episode that changes never produces a second row.
 */
export interface EpisodesState {
  byId: Record<string, AlertView>;
  /** Ids returned by the latest server-side search; they stay visible even if text matching differs. */
  searchedIds: string[];
  /** Episodes first seen live since they were last expired (new-row highlight). */
  fresh: string[];
  /** Episodes whose severity went up live (brief highlight). */
  escalated: string[];
  /** Resolved top-level episodes that still fade out of the live list. */
  leaving: string[];
}

export type EpisodesAction =
  | { type: 'load'; items: AlertView[]; searched?: boolean; prune?: boolean }
  | { type: 'upsert'; alert: AlertView }
  | { type: 'expire'; id: string }
  | { type: 'finishLeaving'; id: string };

export const initialEpisodes: EpisodesState = {
  byId: {},
  searchedIds: [],
  fresh: [],
  escalated: [],
  leaving: [],
};

const versionOf = (alert: AlertView): number => alert.version ?? 0;

/** Newer wins; an equal version also wins so optimistic copies and server echoes can replace each other. */
function supersedes(incoming: AlertView, existing: AlertView | undefined): boolean {
  return !existing || versionOf(incoming) >= versionOf(existing);
}

const without = (ids: string[], id: string) => ids.filter((x) => x !== id);
const withId = (ids: string[], id: string) => (ids.includes(id) ? ids : [...ids, id]);

export function episodesReducer(state: EpisodesState, action: EpisodesAction): EpisodesState {
  switch (action.type) {
    case 'load': {
      const byId = { ...state.byId };
      if (action.prune) {
        // After a stream gap an ongoing episode may have resolved unseen: trust the fresh list.
        const listed = new Set(action.items.map((a) => a.alertId));
        for (const a of Object.values(byId)) {
          if (!a.parentAlertId && a.status !== 'RESOLVED' && !listed.has(a.alertId)) {
            delete byId[a.alertId];
          }
        }
      }
      for (const item of action.items) {
        if (supersedes(item, byId[item.alertId])) byId[item.alertId] = item;
      }
      return {
        ...state,
        byId,
        searchedIds: action.searched ? action.items.map((a) => a.alertId) : state.searchedIds,
      };
    }
    case 'upsert': {
      const { alert } = action;
      const existing = state.byId[alert.alertId];
      if (!supersedes(alert, existing)) return state;
      const escalated =
        existing && SEVERITY_RANK[alert.severity] > SEVERITY_RANK[existing.severity];
      const resolvedNow = existing && existing.status !== 'RESOLVED' && alert.status === 'RESOLVED';
      return {
        ...state,
        byId: { ...state.byId, [alert.alertId]: alert },
        fresh: existing ? state.fresh : withId(state.fresh, alert.alertId),
        escalated: escalated ? withId(state.escalated, alert.alertId) : state.escalated,
        leaving:
          resolvedNow && !alert.parentAlertId
            ? withId(state.leaving, alert.alertId)
            : state.leaving,
      };
    }
    case 'expire':
      return {
        ...state,
        fresh: without(state.fresh, action.id),
        escalated: without(state.escalated, action.id),
      };
    case 'finishLeaving':
      return { ...state, leaving: without(state.leaving, action.id) };
  }
}

/** What the feed's filter bar selects. `status` narrows the live list only (resolved has its own section). */
export interface FeedFilters {
  severity?: Severity;
  type?: AlertType;
  status?: Extract<AlertStatus, 'OPEN' | 'ACKNOWLEDGED'>;
  q?: string;
}

const byLastSeenDesc = (a: AlertView, b: AlertView) =>
  Date.parse(b.lastSeenAt) - Date.parse(a.lastSeenAt) || a.alertId.localeCompare(b.alertId);

function matchesText(state: EpisodesState, alert: AlertView, q: string | undefined): boolean {
  if (!q) return true;
  if (state.searchedIds.includes(alert.alertId)) return true;
  return `${alert.message} ${alert.patientId}`.toLowerCase().includes(q.toLowerCase());
}

function matches(
  state: EpisodesState,
  alert: AlertView,
  { severity, type, q }: FeedFilters,
): boolean {
  return (
    (!severity || alert.severity === severity) &&
    (!type || alert.type === type) &&
    matchesText(state, alert, q)
  );
}

/** Ongoing top-level episodes, newest activity first, plus rows still fading out after a resolve. */
export function selectLive(state: EpisodesState, filters: FeedFilters): AlertView[] {
  return Object.values(state.byId)
    .filter(
      (a) =>
        !a.parentAlertId &&
        (a.status !== 'RESOLVED' || state.leaving.includes(a.alertId)) &&
        (!filters.status || a.status === filters.status || state.leaving.includes(a.alertId)) &&
        matches(state, a, filters),
    )
    .sort(byLastSeenDesc);
}

/** Non-resolved children of one episode, newest activity first. */
export function selectChildren(state: EpisodesState, parentId: string): AlertView[] {
  return Object.values(state.byId)
    .filter((a) => a.parentAlertId === parentId && a.status !== 'RESOLVED')
    .sort(byLastSeenDesc);
}

/** Recently resolved episodes (children included), most recently resolved first. */
export function selectResolved(
  state: EpisodesState,
  filters: FeedFilters,
  limit = 20,
): AlertView[] {
  const resolvedAt = (a: AlertView) => Date.parse(a.resolvedAt ?? a.lastSeenAt);
  return Object.values(state.byId)
    .filter(
      (a) =>
        a.status === 'RESOLVED' &&
        !state.leaving.includes(a.alertId) &&
        matches(state, a, { ...filters, q: undefined }),
    )
    .sort((a, b) => resolvedAt(b) - resolvedAt(a))
    .slice(0, limit);
}

/** Every episode of one patient (ongoing, children and resolved), newest activity first. */
export function selectForPatient(state: EpisodesState, patientId: string): AlertView[] {
  return Object.values(state.byId)
    .filter((a) => a.patientId === patientId)
    .sort(byLastSeenDesc);
}
