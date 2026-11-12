import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { buildAlerts, CONFLICT_ALERT_ID, episodeId, NEWS2_PARENT_ID } from '../mocks/data';
import { emitEpisode, mockState, updateEpisode } from '../mocks/handlers';
import { signInAs } from '../test/auth';
import { server } from '../test/server';
import type { AlertView } from '../lib/types';
import { AlertFeed } from './AlertFeed';
import { AlertRow } from './AlertRow';
import { PatientDetail } from './PatientDetail';
import { WardOverview } from './WardOverview';

afterEach(() => server.events.removeAllListeners());

const row = (id: string) => screen.getByTestId(`alert-${id}`);
const rowCount = () => screen.getByTestId('alert-list').querySelectorAll(':scope > li').length;

/** Renders the feed and waits until episodes are listed and the live stream is connected. */
async function openFeed() {
  render(<AlertFeed />);
  await screen.findByTestId(`alert-${NEWS2_PARENT_ID}`);
  await screen.findByText('Live alerts connected');
}

describe('AlertFeed: one row per episode', () => {
  it('lists only top-level, non-resolved episodes, newest activity first', async () => {
    const urls: URL[] = [];
    server.events.on('request:start', ({ request }) => urls.push(new URL(request.url)));
    await openFeed();
    const listed = urls.find(
      (u) => u.pathname.endsWith('/alerts') && u.searchParams.has('topLevel'),
    );
    expect(listed?.searchParams.get('topLevel')).toBe('true');
    expect(listed?.searchParams.getAll('status')).toEqual(['OPEN', 'ACKNOWLEDGED']);
    const ids = [...screen.getByTestId('alert-list').querySelectorAll(':scope > li')].map((li) =>
      li.getAttribute('data-testid'),
    );
    expect(ids[0]).toBe(`alert-${NEWS2_PARENT_ID}`);
    expect(ids).not.toContain(`alert-${episodeId(2)}`);
    expect(ids).not.toContain(`alert-${episodeId(13)}`);
    expect(ids).toHaveLength(8);
  });

  it('shows severity, type and metric, message, occurrences, ongoing, last seen and ack state', async () => {
    await openFeed();
    const news2 = row(NEWS2_PARENT_ID);
    expect(within(news2).getByText('Critical')).toBeInTheDocument();
    expect(within(news2).getByText('NEWS2')).toBeInTheDocument();
    expect(within(news2).getByText(/NEWS2 score 12/)).toBeInTheDocument();
    expect(within(news2).getByLabelText('24 occurrences')).toHaveTextContent('×24');
    expect(within(news2).getByText('ongoing 23 min')).toBeInTheDocument();
    expect(within(news2).getByText(/seen \d+ s ago/)).toBeInTheDocument();
    const threshold = row(CONFLICT_ALERT_ID);
    expect(within(threshold).getByText('Threshold · Temperature')).toBeInTheDocument();
    expect(within(threshold).getByRole('button', { name: 'Acknowledge' })).toBeEnabled();
  });

  it('upserts an SSE update into the same row instead of appending a duplicate', async () => {
    await openFeed();
    const before = rowCount();
    act(() => {
      updateEpisode(NEWS2_PARENT_ID, {
        occurrences: 31,
        message: 'NEWS2 score 13 (RR 30, SpO2 90, SBP 82, HR 121, Temp 39.4)',
        lastSeenAt: new Date().toISOString(),
      });
    });
    await waitFor(() =>
      expect(within(row(NEWS2_PARENT_ID)).getByLabelText('31 occurrences')).toBeInTheDocument(),
    );
    expect(within(row(NEWS2_PARENT_ID)).getByText(/NEWS2 score 13/)).toBeInTheDocument();
    expect(rowCount()).toBe(before);
    expect(screen.getAllByTestId(`alert-${NEWS2_PARENT_ID}`)).toHaveLength(1);
  });

  it('adds a brand new episode once and then updates it in place', async () => {
    await openFeed();
    const before = rowCount();
    const base = buildAlerts()[8];
    const fresh: AlertView = {
      ...base,
      alertId: episodeId(500),
      patientId: 'P-0020',
      message: 'Heart rate 121 is above the normal range',
      occurrences: 1,
      version: 0,
    };
    act(() => void emitEpisode(fresh));
    await screen.findByTestId(`alert-${fresh.alertId}`);
    expect(rowCount()).toBe(before + 1);
    act(() => void updateEpisode(fresh.alertId, { occurrences: 2 }));
    await waitFor(() =>
      expect(within(row(fresh.alertId)).getByLabelText('2 occurrences')).toBeInTheDocument(),
    );
    expect(rowCount()).toBe(before + 1);
  });

  it('highlights an escalated row briefly', async () => {
    await openFeed();
    const medium = row(episodeId(8));
    expect(medium).not.toHaveClass('alert-escalated');
    act(() => void updateEpisode(episodeId(8), { severity: 'HIGH' }));
    await waitFor(() => expect(row(episodeId(8))).toHaveClass('alert-escalated'));
    expect(within(row(episodeId(8))).getByText('High')).toBeInTheDocument();
    await waitFor(() => expect(row(episodeId(8))).not.toHaveClass('alert-escalated'), {
      timeout: 5000,
    });
  });

  it('shows that an escalation reopened an acknowledged episode', async () => {
    await openFeed();
    act(() => void updateEpisode(episodeId(8), { acknowledgedBy: 'dr.cohen', severity: 'HIGH' }));
    await waitFor(() =>
      expect(within(row(episodeId(8))).getByText(/Escalated after acknowledgement by dr\.cohen/)),
    );
    expect(within(row(episodeId(8))).getByRole('button', { name: 'Acknowledge' })).toBeEnabled();
  });
});

describe('AlertFeed: contributing episodes', () => {
  it('fetches children only when a NEWS2 row is expanded, and lists them compactly', async () => {
    const requested: string[] = [];
    server.events.on('request:start', ({ request }) => {
      const url = new URL(request.url);
      if (url.searchParams.has('parentAlertId'))
        requested.push(url.searchParams.get('parentAlertId')!);
    });
    await openFeed();
    expect(requested).toEqual([]);
    const toggle = within(row(NEWS2_PARENT_ID)).getByRole('button', {
      name: 'Show 3 contributing alerts',
    });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const kids = await screen.findAllByTestId(/^child-/);
    expect(kids).toHaveLength(3);
    expect(requested).toEqual([NEWS2_PARENT_ID]);
    expect(within(kids[0]).getByText(/Systolic pressure 84/)).toBeInTheDocument();
    expect(within(kids[0]).getByText('Critical')).toBeInTheDocument();
    expect(within(kids[2]).getByText(/Acknowledged by dr\.cohen/)).toBeInTheDocument();
    await userEvent.click(toggle);
    expect(screen.queryAllByTestId(/^child-/)).toHaveLength(0);
    await userEvent.click(toggle);
    expect(await screen.findAllByTestId(/^child-/)).toHaveLength(3);
    expect(requested).toEqual([NEWS2_PARENT_ID]);
  });

  it('expands from the keyboard', async () => {
    await openFeed();
    const toggle = within(row(NEWS2_PARENT_ID)).getByRole('button', {
      name: 'Show 3 contributing alerts',
    });
    toggle.focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findAllByTestId(/^child-/)).toHaveLength(3);
    expect(toggle).toHaveAccessibleName('Hide 3 contributing alerts');
    await userEvent.keyboard(' ');
    expect(screen.queryAllByTestId(/^child-/)).toHaveLength(0);
  });

  it('offers no expander on an episode without children', async () => {
    await openFeed();
    expect(within(row(episodeId(6))).queryByRole('button', { name: /contributing/ })).toBeNull();
  });

  it('says so when the children cannot be loaded', async () => {
    server.use(
      http.get('*/clinical-api/api/v1/alerts', ({ request }) =>
        new URL(request.url).searchParams.has('parentAlertId')
          ? HttpResponse.json({ detail: 'down' }, { status: 503 })
          : undefined,
      ),
    );
    await openFeed();
    await userEvent.click(
      within(row(NEWS2_PARENT_ID)).getByRole('button', { name: 'Show 3 contributing alerts' }),
    );
    expect(await within(row(NEWS2_PARENT_ID)).findByRole('alert')).toHaveTextContent(
      /Could not load contributing alerts/,
    );
  });
});

describe('AlertFeed: resolved episodes', () => {
  it('keeps resolved episodes in a collapsed section', async () => {
    await openFeed();
    const toggle = screen.getByRole('button', { name: 'Resolved (2)' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId(`resolved-${episodeId(13)}`)).toBeNull();
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const done = screen.getByTestId(`resolved-${episodeId(13)}`);
    expect(within(done).getByText(/Heart rate 128/)).toBeInTheDocument();
    expect(within(done).getByText(/Resolved \d+ min ago/)).toBeInTheDocument();
    expect(within(done).getByText('lasted 12 min')).toBeInTheDocument();
  });

  it('fades a row out of the live list on an SSE resolve and moves it to Resolved', async () => {
    await openFeed();
    const before = rowCount();
    act(() => {
      updateEpisode(episodeId(9), { status: 'RESOLVED', resolvedAt: new Date().toISOString() });
    });
    await waitFor(() => expect(row(episodeId(9))).toHaveClass('alert-leaving'));
    expect(screen.getByRole('button', { name: 'Resolved (2)' })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId(`alert-${episodeId(9)}`)).toBeNull(), {
      timeout: 4000,
    });
    expect(rowCount()).toBe(before - 1);
    const toggle = screen.getByRole('button', { name: 'Resolved (3)' });
    await userEvent.click(toggle);
    expect(screen.getByTestId(`resolved-${episodeId(9)}`)).toBeInTheDocument();
  });

  it('drops a child from its parent when it resolves', async () => {
    await openFeed();
    await userEvent.click(
      within(row(NEWS2_PARENT_ID)).getByRole('button', { name: 'Show 3 contributing alerts' }),
    );
    await screen.findAllByTestId(/^child-/);
    act(() => {
      updateEpisode(episodeId(3), { status: 'RESOLVED', resolvedAt: new Date().toISOString() });
      updateEpisode(NEWS2_PARENT_ID, { childCount: 2 });
    });
    await waitFor(() => expect(screen.getAllByTestId(/^child-/)).toHaveLength(2));
    expect(
      within(row(NEWS2_PARENT_ID)).getByRole('button', { name: 'Hide 2 contributing alerts' }),
    ).toBeInTheDocument();
  });
});

describe('AlertFeed: acknowledge', () => {
  it('acknowledges in place and the SSE echo does not duplicate the row', async () => {
    await openFeed();
    const before = rowCount();
    const target = row(episodeId(9));
    await userEvent.click(within(target).getByRole('button', { name: 'Acknowledge' }));
    await userEvent.click(within(target).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(within(row(episodeId(9))).getByText('Acknowledged by Nurse Levi')).toBeInTheDocument(),
    );
    expect(rowCount()).toBe(before);
  });

  it('explains a 409 and shows the current state of the episode', async () => {
    await openFeed();
    const conflict = row(CONFLICT_ALERT_ID);
    await userEvent.click(within(conflict).getByRole('button', { name: 'Acknowledge' }));
    await userEvent.click(within(conflict).getByRole('button', { name: 'Confirm' }));
    expect(await within(conflict).findByRole('alert')).toHaveTextContent(
      /changed by someone else/i,
    );
    expect(within(row(CONFLICT_ALERT_ID)).getByText('Acknowledged by dr.levi')).toBeInTheDocument();
  });

  it('is disabled for a viewer, with the reason', async () => {
    signInAs('observer');
    await openFeed();
    const button = within(row(episodeId(9))).getByRole('button', { name: 'Acknowledge' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription('Viewers cannot acknowledge alerts');
  });
});

describe('AlertFeed: filters', () => {
  it('refetches with the severity and keeps status and topLevel', async () => {
    await openFeed();
    const urls: URL[] = [];
    server.events.on('request:start', ({ request }) => urls.push(new URL(request.url)));
    await userEvent.selectOptions(screen.getByLabelText('Severity'), 'CRITICAL');
    await waitFor(() =>
      expect(urls.some((u) => u.searchParams.get('severity') === 'CRITICAL')).toBe(true),
    );
    const list = urls.find((u) => u.searchParams.get('severity') === 'CRITICAL')!;
    expect(list.searchParams.get('topLevel')).toBe('true');
    await waitFor(() => expect(rowCount()).toBe(2));
  });

  it('shows only acknowledged episodes when asked', async () => {
    mockState.alerts = mockState.alerts.map((a) =>
      a.alertId === episodeId(7)
        ? { ...a, status: 'ACKNOWLEDGED', acknowledgedBy: 'dr.cohen', version: 1 }
        : a,
    );
    await openFeed();
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'ACKNOWLEDGED');
    await waitFor(() => expect(rowCount()).toBe(1));
    expect(row(episodeId(7))).toBeInTheDocument();
  });
});

describe('AlertRow ongoing timer', () => {
  afterEach(() => vi.useRealTimers());

  it('ticks the ongoing and last-seen labels without new data', () => {
    vi.useFakeTimers({
      toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date'],
    });
    vi.setSystemTime(Date.parse('2026-11-11T12:00:00Z'));
    const alert: AlertView = {
      ...buildAlerts()[0],
      firstSeenAt: '2026-11-11T11:48:00Z',
      lastSeenAt: '2026-11-11T11:59:50Z',
    };
    render(<AlertRow alert={alert} pending={false} failure={null} onAck={() => undefined} />);
    expect(screen.getByText('ongoing 12 min')).toBeInTheDocument();
    expect(screen.getByText('seen 10 s ago')).toBeInTheDocument();
    act(() => void vi.advanceTimersByTime(65_000));
    expect(screen.getByText('ongoing 13 min')).toBeInTheDocument();
    expect(screen.getByText('seen 1 min ago')).toBeInTheDocument();
  });

  it('stops counting when the component unmounts', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    const alert = buildAlerts()[0];
    const { unmount } = render(
      <AlertRow alert={alert} pending={false} failure={null} onAck={() => undefined} />,
    );
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('episode counts elsewhere', () => {
  it('ward tiles count top-level open episodes (openAlerts), not children', async () => {
    render(<WardOverview />);
    const tile = await screen.findByTestId('tile-P-0003');
    await waitFor(() => expect(tile).toHaveAccessibleName(/1 open alert$/));
    expect(within(tile).getByText('1 open alert')).toBeInTheDocument();
    expect(
      within(await screen.findByTestId('tile-P-0001')).getByText('No open alerts'),
    ).toBeInTheDocument();
  });

  it("patient detail lists the patient's episodes including children and resolved ones", async () => {
    render(<PatientDetail id="P-0003" />);
    const list = await screen.findByTestId('alert-list');
    await waitFor(() => expect(list.querySelectorAll(':scope > li')).toHaveLength(5));
    expect(within(list).getByText(/Heart rate 128/)).toBeInTheDocument();
    expect(within(list).getByText('Resolved')).toBeInTheDocument();
    expect(within(list).getAllByLabelText(/occurrences/)).toHaveLength(5);
    act(() => void updateEpisode(NEWS2_PARENT_ID, { occurrences: 40 }));
    await waitFor(() => expect(within(list).getByLabelText('40 occurrences')).toBeInTheDocument());
    expect(list.querySelectorAll(':scope > li')).toHaveLength(5);
  });
});
