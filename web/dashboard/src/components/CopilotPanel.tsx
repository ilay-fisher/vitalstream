import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useChat } from '../hooks/useChat';
import type { ChatMessage } from '../lib/chatReducer';
import { Markdown } from '../lib/markdown';
import type { Citation, ToolCall } from '../lib/types';
import { useApi } from './ApiContext';

const SUGGESTIONS = [
  'Summarise the current state of P-0003',
  'Which patients need attention first?',
  'What does the sepsis bundle recommend?',
];

function argsSummary(args: unknown): string {
  if (typeof args !== 'object' || args === null) return '';
  return Object.entries(args as Record<string, unknown>)
    .map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`)
    .join(', ');
}

/** A tool the copilot called: name, arguments and a textual state (never colour alone). */
export function ToolCallChip({ call }: { call: ToolCall }) {
  const state =
    call.status === 'running'
      ? 'running'
      : call.status === 'ok'
        ? `done${call.durationMs !== undefined ? ` in ${call.durationMs} ms` : ''}`
        : `failed${call.error ? `: ${call.error}` : ''}`;
  return (
    <li className={`chip chip-${call.status}`} data-testid="tool-call">
      <span className="chip-icon" aria-hidden="true">
        {call.status === 'running' ? '…' : call.status === 'ok' ? '✓' : '!'}
      </span>
      <code>{call.name}</code>
      <span className="chip-args">{argsSummary(call.arguments)}</span>
      <span className="chip-state">{state}</span>
    </li>
  );
}

/** A source behind the answer, with where it came from (curated corpus or an uploaded document). */
export function CitationCard({ citation }: { citation: Citation }) {
  return (
    <li id={`citation-${citation.index}`} className="citation" data-testid="citation">
      <span className="cite-index">{citation.index}</span>
      <div>
        <strong>{citation.title}</strong>
        <span className={`prov prov-${citation.provenance}`}>
          {citation.provenance === 'curated'
            ? 'Curated guideline'
            : `Source: ${citation.provenance}`}
        </span>
        {citation.snippet && <p>{citation.snippet}</p>}
      </div>
    </li>
  );
}

function Message({ message, onRetry }: { message: ChatMessage; onRetry?: () => void }) {
  if (message.role === 'user') {
    return (
      <li className="msg msg-user">
        <p>{message.text}</p>
      </li>
    );
  }
  const waiting = !message.finished && message.text === '';
  return (
    <li className="msg msg-assistant" aria-busy={!message.finished}>
      {message.toolCalls.length > 0 && (
        <ul className="chips" aria-label="Tools used">
          {message.toolCalls.map((c) => (
            <ToolCallChip key={c.id} call={c} />
          ))}
        </ul>
      )}
      {waiting && message.toolCalls.length === 0 && <p className="muted">Thinking…</p>}
      <div className="answer">
        <Markdown
          text={message.text}
          onCitation={(i) =>
            document.getElementById(`citation-${i}`)?.scrollIntoView({ block: 'nearest' })
          }
        />
        {!message.finished && message.text !== '' && <span className="caret" aria-hidden="true" />}
      </div>
      {message.citations.length > 0 && (
        <ol className="citations" aria-label="Sources">
          {message.citations.map((c) => (
            <CitationCard key={`${c.chunkId}-${c.index}`} citation={c} />
          ))}
        </ol>
      )}
      {message.error && (
        <div role="alert" className="notice notice-error">
          {message.error}
          {onRetry && <button onClick={onRetry}>Try again</button>}
        </div>
      )}
      {message.finished && !message.error && message.usage && (
        <p className="usage">
          {message.usage.inputTokens ?? 0} tokens in, {message.usage.outputTokens ?? 0} out
        </p>
      )}
    </li>
  );
}

export function CopilotPanel({ patientId, onClose }: { patientId?: string; onClose: () => void }) {
  const api = useApi();
  const { state, send, stop, reset } = useChat(api, patientId);
  const [draft, setDraft] = useState('');
  const log = useRef<HTMLDivElement>(null);
  const streaming = state.status === 'streaming';

  useEffect(() => {
    const el = log.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [state.messages]);

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    if (!draft.trim() || streaming) return;
    void send(draft);
    setDraft('');
  };
  const onKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) submit(event);
  };
  const lastUser = [...state.messages].reverse().find((m) => m.role === 'user');

  return (
    <aside className="copilot" aria-label="Clinical copilot">
      <header>
        <div>
          <h2>Copilot</h2>
          <p className="muted">
            {patientId ? `Context: ${patientId}` : 'Decision support, not a diagnosis'}
          </p>
        </div>
        <div className="row">
          <button onClick={reset} disabled={state.messages.length === 0}>
            New conversation
          </button>
          <button onClick={onClose} aria-label="Close copilot">
            Close
          </button>
        </div>
      </header>
      <div className="log" ref={log}>
        {state.messages.length === 0 ? (
          <div className="starter">
            <p>Ask about a patient, an alert or a guideline. Answers cite their sources.</p>
            <ul>
              {SUGGESTIONS.map((s) => (
                <li key={s}>
                  <button onClick={() => void send(s)}>{s}</button>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <ol className="messages" aria-label="Conversation">
            {state.messages.map((m, i) => (
              <Message
                key={m.id}
                message={m}
                onRetry={
                  m.error && i === state.messages.length - 1 && lastUser
                    ? () => void send(lastUser.text)
                    : undefined
                }
              />
            ))}
          </ol>
        )}
      </div>
      <form onSubmit={submit} className="composer">
        <label>
          <span className="sr-only">Message to the copilot</span>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={onKey}
            rows={2}
            placeholder="Ask the copilot…"
          />
        </label>
        {streaming ? (
          <button type="button" onClick={stop}>
            Stop
          </button>
        ) : (
          <button type="submit" className="primary" disabled={!draft.trim()}>
            Send
          </button>
        )}
      </form>
      <p className="sr-only" role="status">
        {streaming ? 'The copilot is answering' : ''}
      </p>
    </aside>
  );
}
