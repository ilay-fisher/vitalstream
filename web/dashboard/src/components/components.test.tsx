import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App, parseRoute } from '../App';
import { buildAlerts, buildPatients, CONFLICT_ALERT_ID } from '../mocks/data';
import { mockState } from '../mocks/handlers';
import { AlertList } from './AlertList';
import { News2Badge, SeverityBadge } from './badges';
import { CitationCard, ToolCallChip } from './CopilotPanel';
import { PatientTile } from './PatientTile';
import { WardOverview } from './WardOverview';

describe('SeverityBadge', () => {
  it.each(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const)(
    'labels %s with text, not colour alone',
    (severity) => {
      render(<SeverityBadge severity={severity} />);
      expect(screen.getByText(new RegExp(`^${severity}$`, 'i'))).toBeInTheDocument();
    },
  );
});

describe('News2Badge', () => {
  it('spells out the risk band', () => {
    render(<News2Badge score={12} />);
    expect(screen.getByText('NEWS2 12')).toBeInTheDocument();
    expect(screen.getByText('High risk')).toBeInTheDocument();
  });
  it('explains a missing score', () => {
    render(<News2Badge score={null} />);
    expect(screen.getByText('NEWS2 –')).toBeInTheDocument();
  });
});

describe('PatientTile', () => {
  const sepsis = {
    ...buildPatients().find((p) => p.id === 'P-0003')!,
    openAlertCount: 3,
  };

  it('shows identity, NEWS2, open alerts and flags out-of-range values in words', () => {
    render(<PatientTile patient={sepsis} />);
    const tile = screen.getByTestId('tile-P-0003');
    expect(tile).toHaveAttribute('href', '#/patients/P-0003');
    expect(within(tile).getByText('Yossi Mizrahi')).toBeInTheDocument();
    expect(within(tile).getByText(/NEWS2 \d+/)).toBeInTheDocument();
    expect(within(tile).getByText('3 open alerts')).toBeInTheDocument();
    expect(within(tile).getAllByText(/^(high|low)$/).length).toBeGreaterThan(0);
  });

  it('says so when there are no alerts', () => {
    render(<PatientTile patient={{ ...buildPatients()[0], openAlertCount: 0 }} />);
    expect(screen.getByText('No open alerts')).toBeInTheDocument();
  });
});

describe('WardOverview', () => {
  it('renders every patient, highest NEWS2 first, and filters by ward', async () => {
    render(<WardOverview />);
    const grid = await screen.findByTestId('ward-grid');
    await waitFor(() => expect(within(grid).getAllByRole('link')).toHaveLength(20));
    expect(within(grid).getAllByRole('link')[0]).toHaveAttribute('href', '#/patients/P-0003');
    await userEvent.click(screen.getByRole('button', { name: 'Cardiology' }));
    expect(within(grid).getAllByRole('link')).toHaveLength(10);
    expect(screen.getByRole('button', { name: 'Cardiology' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('shows a new live alert on the tile after the stream delivers it', async () => {
    mockState.streamIntervalMs = 30;
    render(<WardOverview />);
    expect(await screen.findByText('Live alerts connected')).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector('.tile-fresh')).not.toBeNull());
  });

  it('reports a load failure with a retry', async () => {
    const { server } = await import('../test/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('*/clinical-api/api/v1/patients', () =>
        HttpResponse.json({ detail: 'clinical-api is down' }, { status: 503 }),
      ),
    );
    render(<WardOverview />);
    expect(await screen.findByRole('alert')).toHaveTextContent('clinical-api is down');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('AlertList acknowledge flow', () => {
  const open = () => buildAlerts().filter((a) => a.status === 'OPEN');

  it('acknowledges optimistically and ends in the server state', async () => {
    const alerts = open();
    render(<AlertList alerts={alerts} />);
    const row = screen.getByTestId(`alert-${alerts[0].alertId}`);
    await userEvent.click(within(row).getByRole('button', { name: 'Acknowledge' }));
    const by = within(row).getByLabelText('Acknowledged by');
    await userEvent.clear(by);
    await userEvent.type(by, 'dr.cohen');
    await userEvent.click(within(row).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(within(row).getByText('Acknowledged by dr.cohen')).toBeInTheDocument(),
    );
    expect(mockState.alerts.find((a) => a.alertId === alerts[0].alertId)?.status).toBe(
      'ACKNOWLEDGED',
    );
  });

  it('handles a 409: shows the conflict and the other clinician as the current state', async () => {
    const alerts = open();
    render(<AlertList alerts={alerts} />);
    const row = screen.getByTestId(`alert-${CONFLICT_ALERT_ID}`);
    await userEvent.click(within(row).getByRole('button', { name: 'Acknowledge' }));
    const by = within(row).getByLabelText('Acknowledged by');
    await userEvent.clear(by);
    await userEvent.type(by, 'dr.cohen');
    await userEvent.click(within(row).getByRole('button', { name: 'Confirm' }));
    expect(await within(row).findByRole('alert')).toHaveTextContent(/changed by someone else/i);
    expect(within(row).getByText('Acknowledged by dr.levi')).toBeInTheDocument();
  });

  it('requires a name before confirming', async () => {
    localStorage.clear();
    const alerts = open();
    render(<AlertList alerts={alerts} />);
    const row = screen.getByTestId(`alert-${alerts[1].alertId}`);
    await userEvent.click(within(row).getByRole('button', { name: 'Acknowledge' }));
    await userEvent.click(within(row).getByRole('button', { name: 'Confirm' }));
    expect(within(row).queryByText(/Acknowledged by (?!$)/)).toBeNull();
    expect(within(row).getByLabelText('Acknowledged by')).toBeInvalid();
  });

  it('shows an empty state', () => {
    render(<AlertList alerts={[]} empty="Nothing here." />);
    expect(screen.getByText('Nothing here.')).toBeInTheDocument();
  });
});

describe('copilot parts', () => {
  it('tool call chip states are spelled out', () => {
    const base = { id: '1', name: 'list_alerts', arguments: { patient_id: 'P-0003' } };
    const { rerender } = render(<ToolCallChip call={{ ...base, status: 'running' }} />);
    expect(screen.getByText('running')).toBeInTheDocument();
    rerender(<ToolCallChip call={{ ...base, status: 'ok', durationMs: 42 }} />);
    expect(screen.getByText('done in 42 ms')).toBeInTheDocument();
    rerender(<ToolCallChip call={{ ...base, status: 'error', error: 'timeout' }} />);
    expect(screen.getByText('failed: timeout')).toBeInTheDocument();
    expect(screen.getByText('patient_id=P-0003')).toBeInTheDocument();
  });

  it('citation shows provenance', () => {
    render(
      <CitationCard
        citation={{ index: 2, title: 'Fluids', chunkId: 'c', provenance: 'api-upload' }}
      />,
    );
    expect(screen.getByText('Source: api-upload')).toBeInTheDocument();
  });
});

describe('App', () => {
  it('parses routes', () => {
    expect(parseRoute('')).toEqual({ name: 'ward' });
    expect(parseRoute('#/alerts')).toEqual({ name: 'alerts' });
    expect(parseRoute('#/patients/P-0003')).toEqual({ name: 'patient', id: 'P-0003' });
  });

  it('navigates between views and opens the copilot with patient context', async () => {
    mockState.tokenDelayMs = 0;
    window.location.hash = '#/patients/P-0003';
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Yossi Mizrahi' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('img').length).toBe(6));
    await userEvent.click(screen.getByRole('button', { name: 'Copilot' }));
    expect(screen.getByText('Context: P-0003')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Message to the copilot'), 'How is the patient?');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText(/meets sepsis screening criteria/)).toBeInTheDocument();
    expect(screen.getAllByTestId('tool-call')).toHaveLength(2);
    expect(screen.getAllByTestId('citation')).toHaveLength(2);
    window.location.hash = '#/alerts';
    expect(await screen.findByRole('heading', { name: 'Alert feed' })).toBeInTheDocument();
  });

  it('filters and searches the alert feed', async () => {
    window.location.hash = '#/alerts';
    render(<App />);
    await screen.findByTestId('alert-list');
    await userEvent.selectOptions(screen.getByLabelText('Severity'), 'CRITICAL');
    await waitFor(() => {
      const items = screen.getAllByTestId(/^alert-0/);
      expect(items.length).toBeGreaterThan(0);
      items.forEach((i) => expect(within(i).getByText('Critical')).toBeInTheDocument());
    });
    await userEvent.type(screen.getByLabelText('Search alerts'), 'zzz-nothing');
    expect(await screen.findByText('No alerts match these filters.')).toBeInTheDocument();
  });

  it('toggles the theme', async () => {
    window.location.hash = '#/';
    render(<App />);
    const toggle = screen.getByRole('button', { name: /Switch to/ });
    const before = document.documentElement.dataset.theme;
    await userEvent.click(toggle);
    expect(document.documentElement.dataset.theme).not.toBe(before);
  });
});
