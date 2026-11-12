# dashboard

Clinician web UI for VitalStream: ward overview, patient detail with charts, episode-based alert feed with acknowledge, and a copilot chat panel. React 18, TypeScript (strict), Vite, Vitest + Testing Library, Playwright, MSW.

| Ward overview                                 | Patient detail                                      | Alert feed                                        | Copilot (mid-stream)                                |
| --------------------------------------------- | --------------------------------------------------- | ------------------------------------------------- | --------------------------------------------------- |
| ![ward](screenshots/dashboard-ward-light.png) | ![patient](screenshots/dashboard-patient-light.png) | ![alerts](screenshots/dashboard-alerts-light.png) | ![copilot](screenshots/dashboard-copilot-light.png) |

## Sign in and roles

clinical-api requires a bearer token (CONTRACTS 4.2), so the dashboard opens on a dev sign-in screen with the three demo users:

| User       | `sub`        | Role        | Can acknowledge      |
| ---------- | ------------ | ----------- | -------------------- |
| Nurse Levi | `nurse.levi` | `CLINICIAN` | yes                  |
| Dr. Cohen  | `dr.cohen`   | `CLINICIAN` | yes                  |
| Observer   | `observer`   | `VIEWER`    | no (button disabled) |

![sign-in](screenshots/dashboard-signin.png)

Choosing a user calls `POST /clinical-api/api/v1/dev/token`, which only exists when clinical-api runs with `CLINICAL_DEV_TOKENS_ENABLED=true` (the compose default), `APP_ENV=dev` and no JWKS (otherwise sign-in explains that it is unavailable). The token is held in memory and `sessionStorage` (gone when the tab closes), never in `localStorage`, and is sent as `Authorization: Bearer` on every clinical-api call, including the alert SSE stream (`fetch` + `ReadableStream`, so it never appears in a URL). A 401 from the API, or the token's 8 h expiry, returns to the sign-in screen with a notice. The user shown after sign-in is what `GET /clinical-api/api/v1/me` reports for the token. The acknowledging user is the token owner: the request body carries only the optional `note`. Copilot chat calls carry the same user token (the copilot checks it with clinical-api and calls it as that user); a 401 there also returns to sign-in.

## Run

```bash
cd web/dashboard && npm ci
npm run dev:mock        # whole UI on MSW mocks (incl. the dev sign-in and 401/403 rules), no backend needed (http://localhost:5173)
npm run dev             # against real services (proxy targets below)
npm test                # Vitest, coverage gate on src/lib + src/hooks
npm run lint && npm run typecheck
npx playwright install chromium && npm run e2e      # e2e against dev:mock (port 25173)
npm run screenshots     # writes screenshots/dashboard-*.png (1440 light/dark, 390 mobile); docs/images is copied from there by the manager
```

Stack: `make up` builds this folder's `Dockerfile` (nginx-unprivileged, port 3000, healthcheck `/healthz`) and serves http://127.0.0.1:3000.

Mock tuning via the page URL: `?streamMs=1500` (interval of the scripted live episode: open, update, update, escalation, update, resolve, then the next one, always on the same alertId), `?tokenMs=100` (copilot token delay), `?liveCopilot` (do not mock the copilot, use the real one).

## Alert feed (episodes)

An alert is an **episode** (CONTRACTS 3.4): one ongoing condition per patient, type and metric, updated in place by clinical-api and auto-resolved after 5 minutes of silence. The UI follows that model:

- **Feed** = `GET /alerts?topLevel=true&status=OPEN&status=ACKNOWLEDGED` (plus severity, type, `q`), one row per episode, newest `lastSeenAt` first. A row shows severity, type and metric, the latest message, `×N` occurrences, `ongoing 12 min` (from `firstSeenAt`, refreshed every 5 s by one shared timer), `seen 30 s ago`, and the acknowledge state. An acknowledged episode that escalates returns to Open and says who had acknowledged it.
- **Live updates** = SSE `event: alert`, upserted by `alertId` into one client store (`src/lib/episodes.ts`), never appended; a stale `version` is ignored, so a refetch cannot overwrite a newer event. A severity increase highlights the row for 3 s. After a stream gap (no replay on reconnect) the feed refetches and drops episodes that resolved unseen.
- **Contributing episodes**: a NEWS2 row with `childCount > 0` has a disclosure button (`aria-expanded`, Enter/Space) that lazily fetches `parentAlertId=<id>` and shows its non-resolved children compactly; children update live and drop out when they resolve.
- **Resolved**: a collapsed "Resolved (N)" section (`status=RESOLVED`, 20 most recent). On a live resolve the row fades out of the feed (1.2 s) and then appears there.
- **Counts**: tiles show clinical-api's `openAlerts` (top-level OPEN/ACKNOWLEDGED), so a NEWS2 episode and the findings behind it count once. Patient detail lists all of that patient's episodes (ongoing, contributing and resolved), live.
- **Acknowledge** is unchanged: CLINICIAN only, viewers get a disabled button with the reason. 409 (already acknowledged, stale, or resolved meanwhile) shows "changed by someone else" and the current server state.
- Fields missing from an older server default sensibly (`firstSeenAt` = `detectedAt`, `occurrences` = 1), so the dashboard also runs against a pre-episode clinical-api.

## Configuration

| Variable                                       | Default                                            | Meaning                                       |
| ---------------------------------------------- | -------------------------------------------------- | --------------------------------------------- |
| `VITE_CLINICAL_API_URL`                        | `http://localhost:8082`                            | dev-server proxy target for `/clinical-api/*` |
| `VITE_COPILOT_URL`                             | `http://localhost:8083`                            | dev-server proxy target for `/copilot-api/*`  |
| `CLINICAL_API_URL` / `COPILOT_URL` (container) | `http://clinical-api:8082` / `http://copilot:8083` | nginx upstreams for the same prefixes         |
| `PORT`                                         | 5173                                               | dev server port                               |

The browser only calls same-origin prefixes (`/clinical-api`, `/copilot-api`), which the dev server and nginx forward, so no CORS is needed. Prefixes exist because both backends serve `/api/v1/...`.

## Design decisions

- **Severity is never colour alone.** Every badge carries a text label and a distinct shape (circle, triangle, diamond, octagon); out-of-range vitals get a visible arrow plus a screen-reader "high/low"; NEWS2 spells out its risk band; tool-call chips say running/done/failed. Light and dark themes (stored choice, else system), visible focus rings, skip link, reduced-motion respected.
- **fetch-based SSE** (`src/lib/sse.ts`) instead of `EventSource`, because `EventSource` cannot send `X-Correlation-Id` and cannot POST (the copilot chat is a POST). Alert stream reconnects with capped backoff and shows its state.
- **Copilot sessions.** The session id comes only from the server's `session` event and is echoed on follow-ups; the client never invents one. Model text is parsed into React elements by a small markdown subset (`src/lib/markdown.tsx`), so markup in an answer is shown as text; ESLint forbids `dangerouslySetInnerHTML`.
- **Optimistic acknowledge.** The row flips to Acknowledged immediately, then takes the server's answer; on 409 it fetches the alert and shows who got there first; other failures roll back with an error message.
- **NEWS2 on the tile** is computed client side from the latest vitals (the list endpoint has no score); it mirrors the stream-processor rule.
- **Reducers** (`chatReducer`, `liveAlertsReducer`, `episodesReducer`) hold all streaming state transitions and are unit tested per event type.
- **API shapes** follow CONTRACTS section 4 "As built"; the client normalises tolerant variants (see `src/lib/api.ts`).
