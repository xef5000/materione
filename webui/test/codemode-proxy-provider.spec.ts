/**
 * Unit tests for the cloud model proxy origin gate and SSE provider (issue #410, ai-credits.md section 8.2).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ModelEvent, ModelRequest } from '../src/lib/codemode/chat/provider';
import { RUN_PROGRAM_TOOL } from '../src/lib/codemode/chat/tool';
import {
  CLOUD_EDITOR_ORIGIN,
  DEFAULT_MODEL_PROXY_BASE_URL,
  resolveCloudProxyBaseUrl,
  setTestProxyBaseOverride,
} from '../src/lib/codemode/proxy/config';
import { createProxyModelProvider, toUpstreamBody } from '../src/lib/codemode/proxy/provider';

afterEach(() => {
  setTestProxyBaseOverride(null);
});

describe('resolveCloudProxyBaseUrl', () => {
  it('returns null for plugin host even on editor.materione.com or with test override', () => {
    expect(resolveCloudProxyBaseUrl('plugin', CLOUD_EDITOR_ORIGIN)).toBeNull();
    expect(resolveCloudProxyBaseUrl(null, CLOUD_EDITOR_ORIGIN)).toBeNull();
    setTestProxyBaseOverride('https://ai.example');
    expect(resolveCloudProxyBaseUrl('plugin', CLOUD_EDITOR_ORIGIN)).toBeNull();
  });

  it('returns null for cloud host on localhost when no test override is set', () => {
    expect(resolveCloudProxyBaseUrl('cloud', 'http://localhost:5173')).toBeNull();
    expect(resolveCloudProxyBaseUrl('cloud', 'http://127.0.0.1:4173')).toBeNull();
  });

  it('returns https://ai.materione.com only for cloud host on https://editor.materione.com', () => {
    expect(resolveCloudProxyBaseUrl('cloud', CLOUD_EDITOR_ORIGIN)).toBe(DEFAULT_MODEL_PROXY_BASE_URL);
  });

  it('honors test override only for cloud host', () => {
    setTestProxyBaseOverride('https://proxy.test');
    expect(resolveCloudProxyBaseUrl('cloud', 'http://127.0.0.1:4173')).toBe('https://proxy.test');
  });
});

describe('toUpstreamBody', () => {
  it('translates user, assistant, and coalesced consecutive tool messages to Anthropic wire format', () => {
    const request: ModelRequest = {
      system: 'SDK INDEX',
      tools: [RUN_PROGRAM_TOOL],
      messages: [
        { role: 'user', text: 'Create two items.' },
        {
          role: 'assistant',
          text: 'Running two checks.',
          toolCalls: [
            { id: 'call_1', name: 'run_program', input: { text: 'return 1;' } },
            { id: 'call_2', name: 'run_program', input: { text: 'return 2;' } },
          ],
        },
        { role: 'tool', callId: 'call_1', name: 'run_program', content: '{"status":"read","value":1}' },
        { role: 'tool', callId: 'call_2', name: 'run_program', content: '{"status":"read","value":2}' },
        { role: 'assistant', text: '', toolCalls: [{ id: 'call_3', name: 'run_program', input: { text: 'return 3;' } }] },
        { role: 'tool', callId: 'call_3', name: 'run_program', content: '{"status":"applied"}' },
        { role: 'assistant', text: 'Done.', toolCalls: [] },
      ],
    };

    const body = toUpstreamBody(request);
    expect(body.system).toBe('SDK INDEX');
    expect(body.tools).toEqual([RUN_PROGRAM_TOOL]);
    expect(body.messages).toEqual([
      { role: 'user', content: 'Create two items.' },
      {
        role: 'assistant',
        content: [
          { type: 'text', text: 'Running two checks.' },
          { type: 'tool_use', id: 'call_1', name: 'run_program', input: { text: 'return 1;' } },
          { type: 'tool_use', id: 'call_2', name: 'run_program', input: { text: 'return 2;' } },
        ],
      },
      {
        role: 'user',
        content: [
          { type: 'tool_result', tool_use_id: 'call_1', content: '{"status":"read","value":1}' },
          { type: 'tool_result', tool_use_id: 'call_2', content: '{"status":"read","value":2}' },
        ],
      },
      {
        role: 'assistant',
        content: [{ type: 'tool_use', id: 'call_3', name: 'run_program', input: { text: 'return 3;' } }],
      },
      {
        role: 'user',
        content: [{ type: 'tool_result', tool_use_id: 'call_3', content: '{"status":"applied"}' }],
      },
      { role: 'assistant', content: 'Done.' },
    ]);
  });
});

function sseResponse(chunks: readonly string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { 'content-type': 'text/event-stream; charset=utf-8' },
  });
}

async function collect(iterable: AsyncIterable<ModelEvent>): Promise<ModelEvent[]> {
  const out: ModelEvent[] = [];
  for await (const event of iterable) out.push(event);
  return out;
}

describe('createProxyModelProvider', () => {
  const baseRequest: ModelRequest = {
    system: 'SDK INDEX',
    tools: [RUN_PROGRAM_TOOL],
    messages: [{ role: 'user', text: 'Make a ruby.' }],
  };

  it('parses multi-chunk SSE with split CRLF, text deltas, tool_use JSON assembly, and materione_settlement', async () => {
    const onSettled = vi.fn();
    const fetchMock = vi.fn(async () =>
      sseResponse([
        'event: message_start\r\ndata: {"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":80,"output_tokens":0}}}\r',
        '\n\r\nevent: content_block_start\r\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\r\n\r\n',
        'event: content_block_delta\r\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Creating "}}\r\n\r\n',
        'event: content_block_delta\r\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"it."}}\r\n\r\n',
        'event: content_block_stop\r\ndata: {"type":"content_block_stop","index":0}\r\n\r\n',
        'event: content_block_start\r\ndata: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"run_program","input":{}}}\r\n\r\n',
        'event: content_block_delta\r\ndata: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"text\\":\\"return "}}\r\n\r\n',
        'event: content_block_delta\r\ndata: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"1;\\"}"}}\r\n\r\n',
        'event: content_block_stop\r\ndata: {"type":"content_block_stop","index":1}\r\n\r\n',
        'event: message_delta\r\ndata: {"type":"message_delta","usage":{"output_tokens":42}}\r\n\r\n',
        'event: message_stop\r\ndata: {"type":"message_stop"}\r\n\r\n',
        'event: materione_settlement\r\ndata: {"settledCredits":0.35,"usage":{"inputTokens":120,"outputTokens":42,"cachedInputTokens":80}}\r\n\r\n',
      ]),
    );

    const provider = createProxyModelProvider({
      baseUrl: 'https://ai.materione.com',
      fetchImpl: fetchMock as unknown as typeof fetch,
      onSettled,
    });

    expect(provider.info).toEqual({ id: 'materione-cloud', label: 'Materione AI', remote: true });
    const events = await collect(provider.stream(baseRequest));

    expect(fetchMock).toHaveBeenCalledWith(
      'https://ai.materione.com/v1/chat',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: { 'content-type': 'application/json' },
      }),
    );
    expect(events).toEqual([
      { type: 'text', delta: 'Creating ' },
      { type: 'text', delta: 'it.' },
      { type: 'tool_call', call: { id: 'tu_1', name: 'run_program', input: { text: 'return 1;' } } },
      { type: 'usage', usage: { inputTokens: 120, cachedInputTokens: 80, outputTokens: 42, credits: 0.35 } },
    ]);
    expect(onSettled).toHaveBeenCalledTimes(1);
  });

  it('invokes onUnauthenticated and yields unauthenticated refusal on HTTP 401', async () => {
    const onUnauthenticated = vi.fn();
    const onSettled = vi.fn();
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ code: 'unauthenticated', message: 'Session expired.' }), {
        status: 401,
        headers: { 'content-type': 'application/json' },
      }),
    );

    const provider = createProxyModelProvider({
      baseUrl: 'https://ai.materione.com',
      fetchImpl: fetchMock as unknown as typeof fetch,
      onUnauthenticated,
      onSettled,
    });

    const events = await collect(provider.stream(baseRequest));
    expect(onUnauthenticated).toHaveBeenCalledTimes(1);
    expect(onSettled).not.toHaveBeenCalled();
    expect(events).toEqual([
      { type: 'refusal', refusal: { code: 'unauthenticated', message: 'Session expired.' } },
    ]);
  });

  it('yields allowance_exhausted refusal with retryAfterSeconds on HTTP 429', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          error: 'allowance_exhausted',
          code: 'allowance_exhausted',
          message: 'Daily free AI allowance reached.',
          retryAfterSeconds: 3600,
        }),
        { status: 429, headers: { 'content-type': 'application/json' } },
      ),
    );

    const provider = createProxyModelProvider({
      baseUrl: 'https://ai.materione.com',
      fetchImpl: fetchMock as unknown as typeof fetch,
    });

    const events = await collect(provider.stream(baseRequest));
    expect(events).toEqual([
      {
        type: 'refusal',
        refusal: {
          code: 'allowance_exhausted',
          message: 'Daily free AI allowance reached.',
          retryAfterSeconds: 3600,
        },
      },
    ]);
  });

  it('yields unavailable refusal when SSE stream emits an error event', async () => {
    const onSettled = vi.fn();
    const fetchMock = vi.fn(async () =>
      sseResponse([
        'event: error\ndata: {"type":"error","error":{"message":"Upstream overloaded."}}\n\n',
      ]),
    );

    const provider = createProxyModelProvider({
      baseUrl: 'https://ai.materione.com',
      fetchImpl: fetchMock as unknown as typeof fetch,
      onSettled,
    });

    const events = await collect(provider.stream(baseRequest));
    expect(events).toEqual([
      { type: 'refusal', refusal: { code: 'unavailable', message: 'Upstream overloaded.' } },
    ]);
    expect(onSettled).not.toHaveBeenCalled();
  });
});
