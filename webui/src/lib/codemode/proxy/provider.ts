/**
 * Remote `ModelProvider` speaking to `https://ai.materione.com/v1/chat` (ai-credits.md section 8.2).
 *
 * Translates `ChatMessage[]` into Anthropic Messages wire format (coalescing consecutive `tool` entries into
 * a single `user` message of `tool_result` blocks), parses SSE frames with cross-chunk CR/LF handling, emits
 * text/tool/usage/refusal events, and triggers account balance refresh on settlement.
 */
import type { ChatMessage, ModelEvent, ModelProvider, ModelRequest, ModelUsage, ToolSpec } from '../chat/provider';

export interface ProxyProviderOptions {
  baseUrl: string;
  fetchImpl?: typeof fetch;
  onSettled?: () => void;
  onUnauthenticated?: () => void;
}

export type WireContentBlock =
  | { type: 'text'; text: string }
  | { type: 'tool_use'; id: string; name: string; input: unknown }
  | { type: 'tool_result'; tool_use_id: string; content: string };

export interface WireMessage {
  role: 'user' | 'assistant';
  content: string | WireContentBlock[];
}

export interface UpstreamChatBody {
  system: string;
  tools: readonly ToolSpec[];
  messages: WireMessage[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function sanitizeToolInput(raw: unknown): { text: string } {
  if (isRecord(raw) && typeof raw.text === 'string' && raw.text.length >= 1) {
    return { text: raw.text.length <= 60_000 ? raw.text : raw.text.slice(0, 60_000) };
  }
  return { text: '// invalid tool input' };
}

/** Converts editor chat history into Anthropic Messages wire format, coalescing consecutive tool results. */
export function toUpstreamBody(request: ModelRequest): UpstreamChatBody {
  const messages: WireMessage[] = [];
  let inToolBatch = false;

  for (const message of request.messages) {
    if (message.role === 'tool') {
      const block: WireContentBlock = { type: 'tool_result', tool_use_id: message.callId, content: message.content };
      const last = messages[messages.length - 1];
      if (inToolBatch && last !== undefined && last.role === 'user' && Array.isArray(last.content)) {
        last.content.push(block);
      } else {
        messages.push({ role: 'user', content: [block] });
        inToolBatch = true;
      }
      continue;
    }

    inToolBatch = false;
    if (message.role === 'user') {
      messages.push({ role: 'user', content: message.text });
      continue;
    }

    if (message.toolCalls.length === 0) {
      if (message.text !== '') {
        messages.push({ role: 'assistant', content: message.text });
      }
    } else {
      const content: WireContentBlock[] = [
        ...(message.text !== '' ? [{ type: 'text' as const, text: message.text }] : []),
        ...message.toolCalls.map((call) => ({
          type: 'tool_use' as const,
          id: call.id || 'toolu_invalid',
          name: 'run_program',
          input: sanitizeToolInput(call.input),
        })),
      ];
      messages.push({ role: 'assistant', content });
    }
  }

  return {
    system: request.system,
    tools: request.tools,
    messages,
  };
}

interface PendingToolBlock {
  id: string;
  name: string;
  partialJson: string;
  initialInput: unknown;
}

function parseRefusalBody(raw: string): Record<string, unknown> | null {
  if (raw.trim() === '') return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function parseSseFrame(frame: string): { eventType: string; payload: Record<string, unknown> } | null {
  if (frame.trim() === '') return null;
  let eventName = '';
  const dataLines: string[] = [];

  for (const line of frame.split('\n')) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  if (dataLines.length === 0) return null;
  const rawData = dataLines.join('\n');
  if (rawData === '[DONE]') return null;

  try {
    const parsed: unknown = JSON.parse(rawData);
    if (!isRecord(parsed)) return null;
    const eventType = eventName || (typeof parsed.type === 'string' ? parsed.type : '');
    return { eventType, payload: parsed };
  } catch {
    return null;
  }
}

export function createProxyModelProvider(options: ProxyProviderOptions): ModelProvider {
  const fetchImpl = options.fetchImpl ?? fetch;
  const endpoint = `${options.baseUrl.replace(/\/$/, '')}/v1/chat`;

  return {
    info: { id: 'materione-cloud', label: 'Materione AI', remote: true },
    async *stream(request): AsyncIterable<ModelEvent> {
      const response = await fetchImpl(endpoint, {
        method: 'POST',
        credentials: 'include',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(toUpstreamBody(request)),
        signal: request.signal,
      });

      if (!response.ok) {
        const raw = await response.text().catch(() => '');
        const body = parseRefusalBody(raw);
        const message = typeof body?.message === 'string' ? body.message : '';
        if (response.status === 401) {
          options.onUnauthenticated?.();
          yield {
            type: 'refusal',
            refusal: {
              code: 'unauthenticated',
              message: message || 'Sign in with Google or Discord to use the assistant.',
            },
          };
          return;
        }
        const code =
          (typeof body?.code === 'string' && body.code !== '' ? body.code : '') ||
          (typeof body?.error === 'string' && body.error !== '' ? body.error : '') ||
          'unavailable';
        const retryAfterSeconds =
          typeof body?.retryAfterSeconds === 'number' && Number.isFinite(body.retryAfterSeconds)
            ? body.retryAfterSeconds
            : undefined;
        yield {
          type: 'refusal',
          refusal: {
            code,
            message: message || 'The model proxy refused the request.',
            ...(retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}),
          },
        };
        return;
      }

      if (!response.body) {
        throw new Error('The model proxy returned an empty response body.');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const blocks = new Map<number, PendingToolBlock>();
      const usage: ModelUsage = {};
      let hasUsage = false;
      let rawBuffer = '';

      const setUsage = (key: keyof ModelUsage, value: unknown): void => {
        if (typeof value === 'number' && Number.isFinite(value)) {
          usage[key] = value;
          hasUsage = true;
        }
      };

      function* handleFrame(frame: string): Generator<ModelEvent, boolean> {
        const parsed = parseSseFrame(frame);
        if (parsed === null) return true;
        const { eventType, payload } = parsed;

        if (eventType === 'message_start') {
          if (isRecord(payload.message) && isRecord(payload.message.usage)) {
            const msgUsage = payload.message.usage;
            setUsage('inputTokens', msgUsage.input_tokens);
            setUsage('cachedInputTokens', msgUsage.cache_read_input_tokens);
            setUsage('outputTokens', msgUsage.output_tokens);
          }
          return true;
        }

        if (eventType === 'content_block_start') {
          const index = payload.index;
          const block = payload.content_block;
          if (
            typeof index === 'number' &&
            isRecord(block) &&
            block.type === 'tool_use' &&
            typeof block.id === 'string' &&
            typeof block.name === 'string'
          ) {
            blocks.set(index, {
              id: block.id,
              name: block.name,
              partialJson: '',
              initialInput: block.input,
            });
          }
          return true;
        }

        if (eventType === 'content_block_delta') {
          const index = payload.index;
          const delta = payload.delta;
          if (!isRecord(delta)) return true;
          if (delta.type === 'text_delta' && typeof delta.text === 'string' && delta.text.length > 0) {
            yield { type: 'text', delta: delta.text };
          } else if (
            delta.type === 'input_json_delta' &&
            typeof delta.partial_json === 'string' &&
            typeof index === 'number'
          ) {
            const pending = blocks.get(index);
            if (pending !== undefined) pending.partialJson += delta.partial_json;
          }
          return true;
        }

        if (eventType === 'content_block_stop') {
          const index = payload.index;
          if (typeof index === 'number') {
            const pending = blocks.get(index);
            if (pending !== undefined) {
              blocks.delete(index);
              const input: unknown = pending.partialJson !== ''
                ? JSON.parse(pending.partialJson)
                : (pending.initialInput ?? {});
              yield { type: 'tool_call', call: { id: pending.id, name: pending.name, input } };
            }
          }
          return true;
        }

        if (eventType === 'message_delta') {
          if (isRecord(payload.usage)) {
            setUsage('outputTokens', payload.usage.output_tokens);
            setUsage('inputTokens', payload.usage.input_tokens);
            setUsage('cachedInputTokens', payload.usage.cache_read_input_tokens);
          }
          return true;
        }

        if (eventType === 'materione_settlement') {
          setUsage('credits', payload.settledCredits);
          if (isRecord(payload.usage)) {
            setUsage('inputTokens', payload.usage.inputTokens);
            setUsage('outputTokens', payload.usage.outputTokens);
            setUsage('cachedInputTokens', payload.usage.cachedInputTokens);
          }
          return true;
        }

        if (eventType === 'error') {
          const errObj = isRecord(payload.error) ? payload.error : null;
          const errMsg =
            typeof errObj?.message === 'string' && errObj.message !== ''
              ? errObj.message
              : 'The model stream ended with an error.';
          yield { type: 'refusal', refusal: { code: 'unavailable', message: errMsg } };
          return false;
        }

        return true;
      }

      let finishedCleanly = false;
      const aborted = (): boolean => request.signal?.aborted === true;
      try {
        while (true) {
          if (aborted()) return;
          const { done, value } = await reader.read();
          if (done) {
            rawBuffer += decoder.decode();
            if (rawBuffer.length > 0) {
              const normalized = rawBuffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
              let remaining = normalized;
              let splitIdx = remaining.indexOf('\n\n');
              while (splitIdx !== -1) {
                const frame = remaining.slice(0, splitIdx);
                remaining = remaining.slice(splitIdx + 2);
                const keepGoing = yield* handleFrame(frame);
                if (!keepGoing) return;
                splitIdx = remaining.indexOf('\n\n');
              }
              if (remaining.trim().length > 0) {
                const keepGoing = yield* handleFrame(remaining);
                if (!keepGoing) return;
              }
              rawBuffer = '';
            }
            finishedCleanly = true;
            break;
          }

          if (value !== undefined && value.byteLength > 0) {
            rawBuffer += decoder.decode(value, { stream: true });
            let heldCr = '';
            let processable = rawBuffer;
            if (processable.endsWith('\r')) {
              heldCr = '\r';
              processable = processable.slice(0, -1);
            }
            const normalized = processable.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
            let remaining = normalized;
            let splitIdx = remaining.indexOf('\n\n');
            while (splitIdx !== -1) {
              const frame = remaining.slice(0, splitIdx);
              remaining = remaining.slice(splitIdx + 2);
              const keepGoing = yield* handleFrame(frame);
              if (!keepGoing) return;
              splitIdx = remaining.indexOf('\n\n');
            }
            rawBuffer = remaining + heldCr;
          }
        }
      } finally {
        if (!finishedCleanly) {
          await reader.cancel().catch(() => {});
        }
        reader.releaseLock();
      }

      if (aborted()) return;
      if (hasUsage) {
        yield { type: 'usage', usage };
      }
      options.onSettled?.();
    },
  };
}
