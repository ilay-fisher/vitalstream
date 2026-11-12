import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { authStore } from '../lib/auth';
import { signInAs } from '../test/auth';
import { useAuth, useSessionExpiry } from './useAuth';

describe('useAuth', () => {
  it('exposes the signed-in user and what their role allows', () => {
    signInAs('observer');
    const { result } = renderHook(() => useAuth());
    expect(result.current.session?.name).toBe('Observer');
    expect(result.current.canAcknowledge).toBe(false);
    act(() => signInAs('dr.cohen'));
    expect(result.current.canAcknowledge).toBe(true);
  });

  it('signs out through the hook', () => {
    const { result } = renderHook(() => useAuth());
    act(() => result.current.signOut());
    expect(result.current.session).toBeNull();
  });
});

describe('useSessionExpiry', () => {
  it('sends the user back to sign-in when the token runs out', async () => {
    authStore.adopt({
      token: 't',
      sub: 'a',
      name: 'A',
      roles: ['CLINICIAN'],
      expiresAt: Date.now() + 80,
    });
    const { result } = renderHook(() => {
      useSessionExpiry();
      return useAuth();
    });
    expect(result.current.session).not.toBeNull();
    await waitFor(() => expect(result.current.session).toBeNull());
    expect(result.current.notice).toMatch(/expired/i);
  });
});
