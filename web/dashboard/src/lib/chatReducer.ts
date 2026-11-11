import type { Citation, CopilotEvent, ToolCall, Usage } from './types';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  toolCalls: ToolCall[];
  citations: Citation[];
  usage?: Usage;
  correlationId?: string;
  /** Set when the server finished with `done.error` or the connection failed. */
  error?: string;
  finished: boolean;
}

export interface ChatState {
  /** Server-issued; only ids the server handed out are accepted, so never invent one. */
  sessionId: string | null;
  messages: ChatMessage[];
  status: 'idle' | 'streaming' | 'error';
}

export type ChatAction =
  | { type: 'send'; text: string; id: string }
  | { type: 'event'; event: CopilotEvent }
  | { type: 'failed'; message: string }
  | { type: 'reset' };

export const initialChatState: ChatState = { sessionId: null, messages: [], status: 'idle' };

function updateLast(state: ChatState, change: (m: ChatMessage) => ChatMessage): ChatState {
  const last = state.messages[state.messages.length - 1];
  if (!last || last.role !== 'assistant') return state;
  return { ...state, messages: [...state.messages.slice(0, -1), change(last)] };
}

function upsertToolCall(calls: ToolCall[], incoming: ToolCall): ToolCall[] {
  const index = calls.findIndex((c) => c.id === incoming.id);
  if (index === -1) return [...calls, incoming];
  const next = calls.slice();
  next[index] = { ...calls[index], ...incoming };
  return next;
}

/** Folds copilot SSE events into the conversation; one case per event type of the contract. */
export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'send': {
      const user: ChatMessage = {
        id: `${action.id}-user`,
        role: 'user',
        text: action.text,
        toolCalls: [],
        citations: [],
        finished: true,
      };
      const assistant: ChatMessage = {
        id: `${action.id}-assistant`,
        role: 'assistant',
        text: '',
        toolCalls: [],
        citations: [],
        finished: false,
      };
      return { ...state, messages: [...state.messages, user, assistant], status: 'streaming' };
    }
    case 'event':
      return applyEvent(state, action.event);
    case 'failed':
      return {
        ...updateLast(state, (m) => ({ ...m, error: action.message, finished: true })),
        status: 'error',
      };
    case 'reset':
      return initialChatState;
  }
}

function applyEvent(state: ChatState, event: CopilotEvent): ChatState {
  switch (event.type) {
    case 'session':
      return { ...state, sessionId: event.data.sessionId };
    case 'token':
      return updateLast(state, (m) => ({ ...m, text: m.text + event.data.text }));
    case 'tool_call':
      return updateLast(state, (m) => ({
        ...m,
        toolCalls: upsertToolCall(m.toolCalls, event.data),
      }));
    case 'citation':
      return updateLast(state, (m) =>
        m.citations.some((c) => c.chunkId === event.data.chunkId && c.index === event.data.index)
          ? m
          : { ...m, citations: [...m.citations, event.data] },
      );
    case 'done': {
      const failed = event.data.error === true;
      const next = updateLast(state, (m) => ({
        ...m,
        // `done.text` is the authoritative full answer; on failure it carries the error text.
        text: failed ? m.text : (event.data.text ?? m.text),
        error: failed
          ? (event.data.text ?? 'The copilot could not finish this answer.')
          : undefined,
        usage: event.data.usage,
        correlationId: event.data.correlationId,
        finished: true,
      }));
      return {
        ...next,
        sessionId: event.data.sessionId ?? next.sessionId,
        status: failed ? 'error' : 'idle',
      };
    }
  }
}

/** Decodes one raw SSE message into a typed event; unknown events and bad JSON yield null. */
export function parseCopilotEvent(message: { event: string; data: string }): CopilotEvent | null {
  if (!['session', 'token', 'tool_call', 'citation', 'done'].includes(message.event)) return null;
  try {
    return { type: message.event, data: JSON.parse(message.data) } as CopilotEvent;
  } catch {
    return null;
  }
}
