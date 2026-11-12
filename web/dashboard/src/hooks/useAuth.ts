import { useEffect, useSyncExternalStore } from 'react';
import { authStore, canAcknowledge } from '../lib/auth';

/** The signed-in user and the sign-out action; re-renders when the session changes. */
export function useAuth() {
  const { session, notice } = useSyncExternalStore(authStore.subscribe, authStore.getSnapshot);
  return {
    session,
    notice,
    canAcknowledge: canAcknowledge(session),
    signOut: authStore.signOut,
  };
}

/** setTimeout cannot count beyond 2^31 - 1 ms (about 24.8 days). */
const MAX_TIMER_MS = 2 ** 31 - 1;

/** Sends the user back to sign-in at the moment the token runs out, even when the tab is idle. */
export function useSessionExpiry(): void {
  const { session } = useAuth();
  const expiresAt = session?.expiresAt;
  useEffect(() => {
    if (expiresAt === undefined) return;
    const timer = setTimeout(
      () => void authStore.getToken(),
      Math.min(Math.max(expiresAt - Date.now(), 0), MAX_TIMER_MS),
    );
    return () => clearTimeout(timer);
  }, [expiresAt]);
}
