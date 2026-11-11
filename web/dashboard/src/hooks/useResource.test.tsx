import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useResource } from './useResource';
import { useTheme } from './useTheme';

describe('useResource', () => {
  it('loads, exposes errors and reloads', async () => {
    let calls = 0;
    const { result } = renderHook(() =>
      useResource(async () => {
        calls += 1;
        if (calls === 1) throw new Error('first failed');
        return calls;
      }, []),
    );
    await waitFor(() => expect(result.current.error?.message).toBe('first failed'));
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.data).toBe(2));
    expect(result.current.error).toBeUndefined();
    expect(result.current.loading).toBe(false);
  });

  it('drops a stale response when deps change', async () => {
    const { result, rerender } = renderHook(
      ({ id }) =>
        useResource(
          () =>
            new Promise<string>((resolve) => setTimeout(() => resolve(id), id === 'slow' ? 60 : 5)),
          [id],
        ),
      { initialProps: { id: 'slow' } },
    );
    rerender({ id: 'fast' });
    await waitFor(() => expect(result.current.data).toBe('fast'));
    await new Promise((r) => setTimeout(r, 90));
    expect(result.current.data).toBe('fast');
  });

  it('wraps non-Error rejections', async () => {
    const { result } = renderHook(() => useResource(() => Promise.reject('plain'), []));
    await waitFor(() => expect(result.current.error?.message).toBe('plain'));
  });
});

describe('useTheme', () => {
  it('toggles, applies data-theme and persists the choice', () => {
    localStorage.clear();
    const { result } = renderHook(() => useTheme());
    const start = result.current.theme;
    act(() => result.current.toggle());
    expect(result.current.theme).not.toBe(start);
    expect(document.documentElement.dataset.theme).toBe(result.current.theme);
    expect(localStorage.getItem('vitalstream.theme')).toBe(result.current.theme);
  });

  it('starts from the stored theme', () => {
    localStorage.setItem('vitalstream.theme', 'dark');
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('dark');
  });
});
