import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '../test/server';
import { canAcknowledge, createAuthStore, DEMO_USERS, SESSION_KEY, SignInError } from './auth';

const HOUR = 3_600_000;

function newStore(now = Date.now()) {
  return createAuthStore({ storage: window.sessionStorage, now: () => now });
}

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
});

describe('DEMO_USERS', () => {
  it('are the three demo users of CONTRACTS 4.2', () => {
    expect(DEMO_USERS.map(({ sub, name, roles }) => ({ sub, name, roles }))).toEqual([
      { sub: 'nurse.levi', name: 'Nurse Levi', roles: ['CLINICIAN'] },
      { sub: 'dr.cohen', name: 'Dr. Cohen', roles: ['CLINICIAN'] },
      { sub: 'observer', name: 'Observer', roles: ['VIEWER'] },
    ]);
  });
});

describe('signIn', () => {
  it('requests a token for the chosen demo user from the dev endpoint', async () => {
    let body: unknown;
    server.use(
      http.post('*/clinical-api/api/v1/dev/token', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          token: 'tok-1',
          expiresAt: new Date(Date.now() + 8 * HOUR).toISOString(),
        });
      }),
    );
    const store = newStore();

    const session = await store.signIn(DEMO_USERS[0]);

    expect(body).toEqual({ sub: 'nurse.levi', name: 'Nurse Levi', roles: ['CLINICIAN'] });
    expect(session).toMatchObject({ token: 'tok-1', sub: 'nurse.levi', name: 'Nurse Levi' });
    expect(store.getToken()).toBe('tok-1');
    expect(store.getSnapshot().session?.name).toBe('Nurse Levi');
  });

  it('keeps the token in sessionStorage and never in localStorage', async () => {
    const store = newStore();

    await store.signIn(DEMO_USERS[1]);

    expect(window.sessionStorage.getItem(SESSION_KEY)).toContain('dr.cohen');
    expect(window.localStorage.length).toBe(0);
  });

  it('works without any storage (private mode)', async () => {
    const store = createAuthStore({ storage: null });

    await store.signIn(DEMO_USERS[2]);

    expect(store.getSnapshot().session?.sub).toBe('observer');
  });

  it('explains a 404 from a server that is not in dev mode', async () => {
    server.use(
      http.post('*/clinical-api/api/v1/dev/token', () =>
        HttpResponse.json({ status: 404 }, { status: 404 }),
      ),
    );

    await expect(newStore().signIn(DEMO_USERS[0])).rejects.toThrow(/not available/i);
  });

  it('reports an unreachable server', async () => {
    server.use(http.post('*/clinical-api/api/v1/dev/token', () => HttpResponse.error()));

    await expect(newStore().signIn(DEMO_USERS[0])).rejects.toBeInstanceOf(SignInError);
  });

  it('rejects a malformed answer', async () => {
    server.use(http.post('*/clinical-api/api/v1/dev/token', () => HttpResponse.json({ nope: 1 })));

    await expect(newStore().signIn(DEMO_USERS[0])).rejects.toBeInstanceOf(SignInError);
  });

  it('notifies subscribers and clears the notice of an earlier expiry', async () => {
    const store = newStore();
    const listener = vi.fn();
    store.subscribe(listener);
    store.expire();

    await store.signIn(DEMO_USERS[0]);

    expect(listener).toHaveBeenCalled();
    expect(store.getSnapshot().notice).toBeNull();
  });
});

describe('session lifetime', () => {
  const stored = (expiresAt: number) =>
    JSON.stringify({
      token: 't',
      sub: 'nurse.levi',
      name: 'Nurse Levi',
      roles: ['CLINICIAN'],
      expiresAt,
    });

  it('is restored from sessionStorage after a reload', () => {
    window.sessionStorage.setItem(SESSION_KEY, stored(Date.now() + HOUR));

    expect(newStore().getSnapshot().session?.name).toBe('Nurse Levi');
  });

  it('drops an expired stored session', () => {
    window.sessionStorage.setItem(SESSION_KEY, stored(Date.now() - 1));

    const store = newStore();

    expect(store.getSnapshot().session).toBeNull();
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('ignores corrupt stored data', () => {
    window.sessionStorage.setItem(SESSION_KEY, '{not json');
    expect(newStore().getSnapshot().session).toBeNull();
    window.sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 1 }));
    expect(newStore().getSnapshot().session).toBeNull();
  });

  it('stops handing out a token once it has expired', () => {
    let now = Date.now();
    const store = createAuthStore({ storage: window.sessionStorage, now: () => now });
    store.adopt({
      token: 't',
      sub: 'a',
      name: 'A',
      roles: ['VIEWER'],
      expiresAt: now + 1000,
    });
    expect(store.getToken()).toBe('t');

    now += 2000;

    expect(store.getToken()).toBeNull();
    expect(store.getSnapshot().notice).toMatch(/expired/i);
  });

  it('expire() signs out with a notice, signOut() without one', () => {
    const store = newStore();
    store.adopt({
      token: 't',
      sub: 'a',
      name: 'A',
      roles: ['VIEWER'],
      expiresAt: Date.now() + HOUR,
    });

    store.expire();
    expect(store.getSnapshot()).toEqual({
      session: null,
      notice: 'Your session expired. Sign in again.',
    });
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBeNull();

    store.adopt({
      token: 't',
      sub: 'a',
      name: 'A',
      roles: ['VIEWER'],
      expiresAt: Date.now() + HOUR,
    });
    store.signOut();
    expect(store.getSnapshot()).toEqual({ session: null, notice: null });
  });

  it('stops notifying a listener after unsubscribe', () => {
    const store = newStore();
    const listener = vi.fn();
    const off = store.subscribe(listener);
    off();

    store.signOut();

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('canAcknowledge', () => {
  it('is true for CLINICIAN only', () => {
    const base = { token: 't', sub: 'a', name: 'A', expiresAt: 1 };
    expect(canAcknowledge({ ...base, roles: ['CLINICIAN'] })).toBe(true);
    expect(canAcknowledge({ ...base, roles: ['VIEWER'] })).toBe(false);
    expect(canAcknowledge({ ...base, roles: ['SERVICE'] })).toBe(false);
    expect(canAcknowledge(null)).toBe(false);
  });
});
