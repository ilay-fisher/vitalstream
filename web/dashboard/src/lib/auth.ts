/**
 * Dev sign-in for the dashboard (CONTRACTS 4.2). The token lives in memory and in sessionStorage
 * (cleared when the tab closes), never in localStorage, and is only ever sent as a bearer header.
 */

export type RoleName = 'CLINICIAN' | 'VIEWER' | 'SERVICE';

export interface DemoUser {
  sub: string;
  name: string;
  roles: RoleName[];
}

/** The three demo users of CONTRACTS 4.2. */
export const DEMO_USERS: DemoUser[] = [
  { sub: 'nurse.levi', name: 'Nurse Levi', roles: ['CLINICIAN'] },
  { sub: 'dr.cohen', name: 'Dr. Cohen', roles: ['CLINICIAN'] },
  { sub: 'observer', name: 'Observer', roles: ['VIEWER'] },
];

export const ROLE_LABEL: Record<RoleName, string> = {
  CLINICIAN: 'Clinician',
  VIEWER: 'Viewer',
  SERVICE: 'Service',
};

export interface Session {
  token: string;
  sub: string;
  name: string;
  roles: RoleName[];
  /** Epoch milliseconds. */
  expiresAt: number;
}

export interface AuthSnapshot {
  session: Session | null;
  /** Why the user is looking at the sign-in screen, when it was not their choice. */
  notice: string | null;
}

export const SESSION_KEY = 'vitalstream.session';
const EXPIRED_NOTICE = 'Your session expired. Sign in again.';

export class SignInError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SignInError';
  }
}

/** Only a CLINICIAN may acknowledge alerts; the server enforces it, the UI mirrors it. */
export function canAcknowledge(session: Session | null): boolean {
  return session?.roles.includes('CLINICIAN') ?? false;
}

export interface AuthStoreOptions {
  /** Where to keep the session across reloads; `null` keeps it in memory only. */
  storage?: Storage | null;
  clinicalBase?: string;
  fetchImpl?: typeof fetch;
  now?: () => number;
}

function defaultStorage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.token === 'string' &&
    typeof v.sub === 'string' &&
    typeof v.name === 'string' &&
    Array.isArray(v.roles) &&
    typeof v.expiresAt === 'number'
  );
}

/** A small external store so React (useSyncExternalStore) and the fetch layer share one session. */
export function createAuthStore(options: AuthStoreOptions = {}) {
  const storage = 'storage' in options ? (options.storage ?? null) : defaultStorage();
  const clinical = options.clinicalBase ?? '/clinical-api';
  const now = options.now ?? Date.now;
  const listeners = new Set<() => void>();

  const persist = (session: Session | null) => {
    try {
      if (session) storage?.setItem(SESSION_KEY, JSON.stringify(session));
      else storage?.removeItem(SESSION_KEY);
    } catch {
      /* storage blocked: the session simply does not survive a reload */
    }
  };

  const restore = (): Session | null => {
    try {
      const raw = storage?.getItem(SESSION_KEY);
      if (!raw) return null;
      const parsed: unknown = JSON.parse(raw);
      if (isSession(parsed) && parsed.expiresAt > now()) return parsed;
    } catch {
      /* corrupt entry: fall through and discard it */
    }
    persist(null);
    return null;
  };

  let snapshot: AuthSnapshot = { session: restore(), notice: null };

  const set = (next: AuthSnapshot) => {
    snapshot = next;
    persist(next.session);
    listeners.forEach((listener) => listener());
  };

  const store = {
    getSnapshot: (): AuthSnapshot => snapshot,

    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    /** Takes over an already issued session (sign-in result). */
    adopt(session: Session): void {
      set({ session, notice: null });
    },

    /** The bearer token, or null when signed out or when it has run out. */
    getToken(): string | null {
      const { session } = snapshot;
      if (!session) return null;
      if (session.expiresAt <= now()) {
        store.expire();
        return null;
      }
      return session.token;
    },

    /** The user's own choice to leave. */
    signOut(): void {
      set({ session: null, notice: null });
    },

    /** The server (or the clock) ended the session: back to sign-in, with an explanation. */
    expire(): void {
      set({ session: null, notice: EXPIRED_NOTICE });
    },

    /** Asks the dev endpoint for a token for a demo user. */
    async signIn(user: DemoUser): Promise<Session> {
      let response: Response;
      try {
        response = await (options.fetchImpl ?? fetch)(`${clinical}/api/v1/dev/token`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ sub: user.sub, name: user.name, roles: user.roles }),
        });
      } catch {
        throw new SignInError('Could not reach the server. Is the stack running?');
      }
      if (response.status === 404) {
        throw new SignInError(
          'Dev sign-in is not available on this server (it needs APP_ENV=dev and no external identity provider).',
        );
      }
      if (!response.ok) throw new SignInError(`Sign-in failed (HTTP ${response.status}).`);
      const body: unknown = await response.json().catch(() => null);
      const record = (body ?? {}) as Record<string, unknown>;
      const expiresAt = Date.parse(String(record.expiresAt ?? ''));
      if (typeof record.token !== 'string' || Number.isNaN(expiresAt)) {
        throw new SignInError('The server answered with an unexpected sign-in response.');
      }
      const session: Session = {
        token: record.token,
        sub: user.sub,
        name: user.name,
        roles: user.roles,
        expiresAt,
      };
      store.adopt(session);
      return session;
    },
  };
  return store;
}

export type AuthStore = ReturnType<typeof createAuthStore>;

/** The app-wide session. */
export const authStore: AuthStore = createAuthStore();
