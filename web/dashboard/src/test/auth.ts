import { authStore, DEMO_USERS } from '../lib/auth';
import { issueMockToken } from '../mocks/auth';

/** Puts the app in the signed-in state without going through the sign-in screen. */
export function signInAs(sub: 'nurse.levi' | 'dr.cohen' | 'observer' = 'nurse.levi'): void {
  const user = DEMO_USERS.find((u) => u.sub === sub);
  if (!user) throw new Error(`unknown demo user ${sub}`);
  authStore.adopt({
    token: issueMockToken(user),
    sub: user.sub,
    name: user.name,
    roles: user.roles,
    expiresAt: Date.now() + 8 * 3_600_000,
  });
}
