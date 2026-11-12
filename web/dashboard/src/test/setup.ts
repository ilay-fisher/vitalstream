import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';
import { authStore } from '../lib/auth';
import { resetMockState } from '../mocks/handlers';
import { signInAs } from './auth';
import { server } from './server';

/**
 * Node's fetch (undici) needs absolute URLs and rejects the jsdom AbortSignal (a different
 * class), so this wrapper resolves same-origin paths and implements abort itself: the request
 * is made without a signal, and an abort rejects the pending fetch or cancels the body stream.
 */
function installFetch(nativeFetch: typeof fetch): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input;
    const { signal, ...rest } = init ?? {};
    if (!signal) return nativeFetch(url, rest);
    const aborted = () => new DOMException('The operation was aborted.', 'AbortError');
    if (signal.aborted) throw aborted();
    let onAbort: () => void = () => undefined;
    const abortion = new Promise<never>((_, reject) => {
      onAbort = () => reject(aborted());
      signal.addEventListener('abort', onAbort, { once: true });
    });
    const response = await Promise.race([nativeFetch(url, rest), abortion]);
    if (!response.body) return response;
    const reader = response.body.getReader();
    const body = new ReadableStream<Uint8Array>({
      async pull(controller) {
        try {
          const chunk = await Promise.race([reader.read(), abortion]);
          if (chunk.done) controller.close();
          else controller.enqueue(chunk.value);
        } catch (error) {
          void reader.cancel().catch(() => undefined);
          controller.error(error);
        }
      },
      cancel: () => reader.cancel(),
    });
    return new Response(body, { status: response.status, headers: response.headers });
  }) as typeof fetch;
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
  // Must wrap the fetch that MSW patched in listen(), which is the one that rejects jsdom signals.
  globalThis.fetch = installFetch(globalThis.fetch.bind(globalThis));
});
// Every test starts signed in as Nurse Levi; tests of the sign-in flow sign out first.
beforeEach(() => signInAs('nurse.levi'));
afterEach(() => {
  cleanup();
  authStore.signOut();
  server.resetHandlers();
  resetMockState();
});
afterAll(() => server.close());
