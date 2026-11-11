import { describe, expect, it } from 'vitest';
import {
  chatReducer,
  initialChatState,
  parseCopilotEvent,
  type ChatAction,
  type ChatState,
} from './chatReducer';
import type { CopilotEvent } from './types';

const run = (actions: ChatAction[], from: ChatState = initialChatState) =>
  actions.reduce(chatReducer, from);
const event = (e: CopilotEvent): ChatAction => ({ type: 'event', event: e });
const started = () => run([{ type: 'send', text: 'How is P-0003?', id: 'm1' }]);
const last = (s: ChatState) => s.messages[s.messages.length - 1];

describe('chatReducer', () => {
  it('send adds the user message and an empty streaming assistant message', () => {
    const s = started();
    expect(s.status).toBe('streaming');
    expect(s.messages.map((m) => m.role)).toEqual(['user', 'assistant']);
    expect(s.messages[0].text).toBe('How is P-0003?');
    expect(last(s)).toMatchObject({ text: '', finished: false });
  });

  it('session event stores the server-issued id', () => {
    const s = run([event({ type: 'session', data: { sessionId: 'abc' } })], started());
    expect(s.sessionId).toBe('abc');
  });

  it('token events append in order', () => {
    const s = run(
      [
        event({ type: 'token', data: { text: 'NEWS2 ' } }),
        event({ type: 'token', data: { text: 'is 12' } }),
      ],
      started(),
    );
    expect(last(s).text).toBe('NEWS2 is 12');
  });

  it('tool_call is upserted by id: running then ok keeps one chip', () => {
    const call = { id: 't1', name: 'get_patient_vitals', arguments: { patient_id: 'P-0003' } };
    const s = run(
      [
        event({ type: 'tool_call', data: { ...call, status: 'running' } }),
        event({ type: 'tool_call', data: { ...call, status: 'ok', durationMs: 90 } }),
        event({
          type: 'tool_call',
          data: { id: 't2', name: 'list_alerts', arguments: {}, status: 'error', error: 'boom' },
        }),
      ],
      started(),
    );
    expect(last(s).toolCalls).toHaveLength(2);
    expect(last(s).toolCalls[0]).toMatchObject({ status: 'ok', durationMs: 90 });
    expect(last(s).toolCalls[1]).toMatchObject({ status: 'error', error: 'boom' });
  });

  it('citation events accumulate and are de-duplicated', () => {
    const citation = { index: 1, title: 'Sepsis', chunkId: 'c1', provenance: 'curated' };
    const s = run(
      [
        event({ type: 'citation', data: citation }),
        event({ type: 'citation', data: citation }),
        event({
          type: 'citation',
          data: { ...citation, index: 2, chunkId: 'c2', provenance: 'api-upload' },
        }),
      ],
      started(),
    );
    expect(last(s).citations.map((c) => c.provenance)).toEqual(['curated', 'api-upload']);
  });

  it('done finishes the message, takes the final text, usage and correlation id', () => {
    const s = run(
      [
        event({ type: 'token', data: { text: 'partial' } }),
        event({
          type: 'done',
          data: {
            text: 'Final answer',
            sessionId: 'abc',
            correlationId: 'corr-1',
            toolCalls: 2,
            usage: { inputTokens: 10, outputTokens: 5 },
          },
        }),
      ],
      started(),
    );
    expect(s.status).toBe('idle');
    expect(s.sessionId).toBe('abc');
    expect(last(s)).toMatchObject({
      text: 'Final answer',
      finished: true,
      correlationId: 'corr-1',
      usage: { inputTokens: 10, outputTokens: 5 },
    });
    expect(last(s).error).toBeUndefined();
  });

  it('done keeps streamed text when the event carries none', () => {
    const s = run(
      [event({ type: 'token', data: { text: 'abc' } }), event({ type: 'done', data: {} })],
      started(),
    );
    expect(last(s).text).toBe('abc');
  });

  it('done with error:true puts the chat in the error state and keeps partial text', () => {
    const s = run(
      [
        event({ type: 'token', data: { text: 'partial' } }),
        event({ type: 'done', data: { error: true, text: 'LLM unavailable' } }),
      ],
      started(),
    );
    expect(s.status).toBe('error');
    expect(last(s)).toMatchObject({ text: 'partial', error: 'LLM unavailable', finished: true });
  });

  it('done with error:true and no text uses a generic message', () => {
    const s = run([event({ type: 'done', data: { error: true } })], started());
    expect(last(s).error).toMatch(/could not finish/i);
  });

  it('a network failure marks the assistant message and the chat as failed', () => {
    const s = run([{ type: 'failed', message: 'offline' }], started());
    expect(s.status).toBe('error');
    expect(last(s)).toMatchObject({ error: 'offline', finished: true });
  });

  it('keeps the session id across a second question', () => {
    const first = run(
      [event({ type: 'session', data: { sessionId: 'abc' } }), event({ type: 'done', data: {} })],
      started(),
    );
    const second = run([{ type: 'send', text: 'And now?', id: 'm2' }], first);
    expect(second.sessionId).toBe('abc');
    expect(second.messages).toHaveLength(4);
  });

  it('reset clears everything, including the session', () => {
    const s = run([{ type: 'reset' }], started());
    expect(s).toEqual(initialChatState);
  });

  it('events before any assistant message are ignored', () => {
    const s = run([event({ type: 'token', data: { text: 'x' } })]);
    expect(s.messages).toEqual([]);
  });
});

describe('parseCopilotEvent', () => {
  it('decodes known events', () => {
    expect(parseCopilotEvent({ event: 'token', data: '{"text":"a"}' })).toEqual({
      type: 'token',
      data: { text: 'a' },
    });
  });
  it('drops unknown events and malformed JSON', () => {
    expect(parseCopilotEvent({ event: 'ping', data: '{}' })).toBeNull();
    expect(parseCopilotEvent({ event: 'token', data: '{oops' })).toBeNull();
  });
});
