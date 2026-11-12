import { useState } from 'react';
import { useAuth } from '../hooks/useAuth';
import { authStore, DEMO_USERS, ROLE_LABEL, SignInError, type DemoUser } from '../lib/auth';

/**
 * Local demo login: pick one of the demo users and the dev endpoint of clinical-api issues a token
 * for it. There is no password because this only exists when the server runs with APP_ENV=dev.
 */
export function SignIn() {
  const { notice } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  const choose = async (user: DemoUser) => {
    setError(null);
    setPending(user.sub);
    try {
      await authStore.signIn(user);
    } catch (failure) {
      setError(failure instanceof SignInError ? failure.message : 'Sign-in failed.');
      setPending(null);
    }
  };

  return (
    <main id="main" className="signin" tabIndex={-1}>
      <section className="signin-card" aria-labelledby="signin-title">
        <div className="signin-brand" aria-hidden="true">
          <svg viewBox="0 0 28 16" width="28" height="16">
            <path
              d="M1 8h6l3-6 4 12 3-6h10"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>
        <h1 id="signin-title">Sign in to VitalStream</h1>
        <p className="muted">
          Local demo login. Choose a demo user; their role decides what the dashboard lets them do.
        </p>
        {notice && (
          <p role="status" className="notice notice-warn">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="notice notice-error">
            {error}
          </p>
        )}
        <ul className="signin-users">
          {DEMO_USERS.map((user) => (
            <li key={user.sub}>
              <button onClick={() => void choose(user)} disabled={pending !== null}>
                <span className="who">{user.name}</span>
                <span className="role-badge">{ROLE_LABEL[user.roles[0]]}</span>
                <span className="sub">{user.sub}</span>
              </button>
            </li>
          ))}
        </ul>
        <p className="muted small">
          Observer can read everything but cannot acknowledge alerts. Tokens last 8 hours and are
          kept for this browser tab only.
        </p>
      </section>
    </main>
  );
}
