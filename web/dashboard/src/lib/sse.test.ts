import { describe, expect, it } from 'vitest';
import { SseHttpError, SseParser, streamSse, type SseMessage } from './sse';

describe('SseParser', () => {
  it('parses a complete event', () => {
    const parser = new SseParser();
    expect(parser.push('event: token\ndata: {"text":"hi"}\n\n')).toEqual([
      { event: 'token', data: '{"text":"hi"}' },
    ]);
  });

  it('buffers events split across chunks, even mid-line', () => {
    const parser = new SseParser();
    expect(parser.push('event: tok')).toEqual([]);
    expect(parser.push('en\ndata: {"a":')).toEqual([]);
    expect(parser.push('1}\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: 'token', data: '{"a":1}' }]);
  });

  it('handles CRLF, comments, multi-line data and the default event name', () => {
    const parser = new SseParser();
    const out = parser.push(': keep-alive\r\n\r\ndata: one\r\ndata: two\r\n\r\n');
    expect(out).toEqual([{ event: 'message', data: 'one\ntwo' }]);
  });

  it('emits several events from one chunk and ignores blank events', () => {
    const parser = new SseParser();
    const out = parser.push('\n\nevent: a\ndata: 1\n\nevent: b\ndata: 2\n\n');
    expect(out.map((m) => m.event)).toEqual(['a', 'b']);
  });

  it('treats a field without colon as an empty value', () => {
    const parser = new SseParser();
    expect(parser.push('data\n\n')).toEqual([{ event: 'message', data: '' }]);
  });
});

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
}

describe('streamSse', () => {
  it('sends headers and JSON body, and delivers parsed messages', async () => {
    let captured: { url: string; init?: RequestInit } | undefined;
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      captured = { url, init };
      return new Response(
        streamOf(['event: session\ndata: {"sessionId":"s"}\n\nevent: do', 'ne\ndata: {}\n\n']),
      );
    }) as unknown as typeof fetch;
    const received: SseMessage[] = [];
    await streamSse(
      {
        url: '/x',
        method: 'POST',
        body: { a: 1 },
        headers: { 'X-Correlation-Id': 'c1' },
        fetchImpl,
      },
      (m) => received.push(m),
    );
    expect(received.map((m) => m.event)).toEqual(['session', 'done']);
    expect(captured?.init?.method).toBe('POST');
    expect(captured?.init?.body).toBe('{"a":1}');
    const headers = captured?.init?.headers as Record<string, string>;
    expect(headers['X-Correlation-Id']).toBe('c1');
    expect(headers.Accept).toBe('text/event-stream');
    expect(headers['Content-Type']).toBe('application/json');
  });

  it('calls onOpen once the stream is accepted, before any event', async () => {
    const order: string[] = [];
    const fetchImpl = (async () => new Response(streamOf([': hi\n\n']))) as unknown as typeof fetch;
    await streamSse({ url: '/x', fetchImpl, onOpen: () => order.push('open') }, () =>
      order.push('message'),
    );
    expect(order).toEqual(['open']);
  });

  it('throws SseHttpError on a non-2xx answer', async () => {
    const fetchImpl = (async () => new Response('no', { status: 503 })) as unknown as typeof fetch;
    await expect(streamSse({ url: '/x', fetchImpl }, () => undefined)).rejects.toBeInstanceOf(
      SseHttpError,
    );
  });
});
