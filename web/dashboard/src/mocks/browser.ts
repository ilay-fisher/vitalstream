import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/** `?liveCopilot` leaves the copilot unmocked so the page can talk to a real service. */
const live = new URLSearchParams(location.search).has('liveCopilot');
const active = live
  ? handlers.filter((h) => !String((h.info as { path?: unknown }).path).includes('/copilot-api/'))
  : handlers;

export const worker = setupWorker(...active);
