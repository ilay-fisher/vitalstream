/** Minimal Server-Sent Events client over fetch, so that every request can carry headers. */

export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Incremental parser for the text/event-stream format. Chunks may split lines or events anywhere,
 * so unfinished input is buffered until the terminating blank line arrives.
 */
export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];

  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const messages: SseMessage[] = [];
    let newline = this.nextLineBreak();
    while (newline) {
      const line = this.buffer.slice(0, newline.index);
      this.buffer = this.buffer.slice(newline.index + newline.length);
      const message = this.consumeLine(line);
      if (message) messages.push(message);
      newline = this.nextLineBreak();
    }
    return messages;
  }

  private nextLineBreak(): { index: number; length: number } | null {
    const match = /\r\n|\n|\r(?!$)/.exec(this.buffer);
    return match ? { index: match.index, length: match[0].length } : null;
  }

  private consumeLine(line: string): SseMessage | null {
    if (line === '') {
      if (this.data.length === 0) {
        this.event = '';
        return null;
      }
      const message = { event: this.event || 'message', data: this.data.join('\n') };
      this.event = '';
      this.data = [];
      return message;
    }
    if (line.startsWith(':')) return null;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
    return null;
  }
}

export interface SseRequest {
  url: string;
  method?: 'GET' | 'POST';
  headers?: Record<string, string>;
  body?: unknown;
  signal?: AbortSignal;
  /** Called once the server accepted the stream, before any event arrives. */
  onOpen?: () => void;
  fetchImpl?: typeof fetch;
}

export class SseHttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'SseHttpError';
  }
}

/** Streams events until the server closes the connection or `signal` aborts. */
export async function streamSse(
  request: SseRequest,
  onMessage: (message: SseMessage) => void,
): Promise<void> {
  const doFetch = request.fetchImpl ?? fetch;
  const hasBody = request.body !== undefined;
  const response = await doFetch(request.url, {
    method: request.method ?? 'GET',
    headers: {
      Accept: 'text/event-stream',
      ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
      ...request.headers,
    },
    body: hasBody ? JSON.stringify(request.body) : undefined,
    signal: request.signal,
  });
  if (!response.ok || !response.body) {
    throw new SseHttpError(response.status, `stream request failed with ${response.status}`);
  }
  request.onOpen?.();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    for (const message of parser.push(decoder.decode(value, { stream: true }))) onMessage(message);
  }
}
