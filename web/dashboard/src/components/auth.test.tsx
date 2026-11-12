import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { App } from '../App';
import { authStore } from '../lib/auth';
import { buildAlerts } from '../mocks/data';
import { mockState } from '../mocks/handlers';
import { signInAs } from '../test/auth';
import { server } from '../test/server';
import { AlertList } from './AlertList';
import { SignIn } from './SignIn';

const open = () => buildAlerts().filter((a) => a.status === 'OPEN');

describe('SignIn', () => {
  it('offers the three demo users with their roles', () => {
    authStore.signOut();
    render(<SignIn />);
    expect(screen.getByRole('heading', { name: 'Sign in to VitalStream' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Nurse Levi/ })).toHaveTextContent('Clinician');
    expect(screen.getByRole('button', { name: /Dr\. Cohen/ })).toHaveTextContent('Clinician');
    expect(screen.getByRole('button', { name: /Observer/ })).toHaveTextContent('Viewer');
  });

  it('signs in the chosen user', async () => {
    authStore.signOut();
    render(<SignIn />);
    await userEvent.click(screen.getByRole('button', { name: /Dr\. Cohen/ }));
    await waitFor(() => expect(authStore.getSnapshot().session?.sub).toBe('dr.cohen'));
  });

  it('shows why sign-in failed and why the user was sent back', async () => {
    authStore.signOut();
    server.use(
      http.post('*/clinical-api/api/v1/dev/token', () =>
        HttpResponse.json({ status: 404 }, { status: 404 }),
      ),
    );
    authStore.expire();
    render(<SignIn />);
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired');
    await userEvent.click(screen.getByRole('button', { name: /Nurse Levi/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/not available/i);
    expect(authStore.getSnapshot().session).toBeNull();
  });
});

describe('App authentication', () => {
  it('shows only the sign-in screen when signed out and calls no API', async () => {
    authStore.signOut();
    let calls = 0;
    server.use(
      http.get('*/clinical-api/*', () => {
        calls += 1;
        return HttpResponse.json([]);
      }),
    );
    window.location.hash = '#/';
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Sign in to VitalStream' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Alerts' })).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(calls).toBe(0);
  });

  it('signs in from the screen, shows who is signed in, and signs out', async () => {
    authStore.signOut();
    window.location.hash = '#/';
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: /Nurse Levi/ }));
    const bar = await screen.findByTestId('current-user');
    expect(bar).toHaveTextContent('Nurse Levi');
    expect(bar).toHaveTextContent('Clinician');
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(screen.getByRole('heading', { name: 'Sign in to VitalStream' })).toBeInTheDocument();
    expect(authStore.getSnapshot().session).toBeNull();
  });

  it('returns to sign-in when the API answers 401', async () => {
    server.use(
      http.get('*/clinical-api/api/v1/patients', () =>
        HttpResponse.json({ title: 'Unauthorized', status: 401 }, { status: 401 }),
      ),
    );
    window.location.hash = '#/';
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: 'Sign in to VitalStream' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired');
  });
});

describe('Acknowledge by role', () => {
  it('acknowledges as the signed-in clinician without asking who they are', async () => {
    signInAs('dr.cohen');
    const alerts = open();
    render(<AlertList alerts={alerts} />);
    const row = screen.getByTestId(`alert-${alerts[0].alertId}`);
    await userEvent.click(within(row).getByRole('button', { name: 'Acknowledge' }));
    expect(within(row).queryByLabelText('Acknowledged by')).toBeNull();
    expect(within(row).getByText('Acknowledging as Dr. Cohen')).toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(within(row).getByText('Acknowledged by Dr. Cohen')).toBeInTheDocument(),
    );
    expect(mockState.alerts.find((a) => a.alertId === alerts[0].alertId)?.acknowledgedBy).toBe(
      'Dr. Cohen',
    );
  });

  it('disables Acknowledge for a viewer and says why', () => {
    signInAs('observer');
    const alerts = open();
    render(<AlertList alerts={alerts} />);
    const row = screen.getByTestId(`alert-${alerts[0].alertId}`);
    const button = within(row).getByRole('button', { name: 'Acknowledge' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/viewers cannot acknowledge/i);
  });
});
