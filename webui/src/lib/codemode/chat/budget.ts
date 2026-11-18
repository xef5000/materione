/**
 * The context budget of one model turn (codemode.md section 3.7). The loop keeps the whole history (the panel and the next
 * request need it), but what a turn SENDS must fit what the model proxy accepts: it bounds a request at 80,000 tokens,
 * counted as one token per byte of the upstream body, and at 64 messages. A history that only grows would cross that
 * after a few reads, and every later turn would be refused.
 *
 * `fitContext` returns the view of the history a turn sends: unchanged when it fits, otherwise compacted, oldest first,
 * in steps that keep it well formed (every call keeps its result; a user message starts it):
 *   1. the model's carried reasoning (`continuation`) of earlier requests, then of this request's earlier turns;
 *   2. the results of earlier tool calls, replaced by a short stub that keeps the status;
 *   3. the text of earlier programs, cut to their head;
 *   4. whole earlier requests, from the start.
 * The latest turn's results and the request's own user message are never compacted. Pure: no state, no clock.
 */
import type { ChatMessage } from './provider';

/**
 * The bytes a turn's system text and messages may take, as the client measures them. The proxy's bound is 80,000; the
 * rest is headroom for what the proxy adds and the client does not see (its own charter in place of the client's
 * instructions, a fixed overhead, per-block framing).
 */
export const CONTEXT_BUDGET_BYTES = 62_000;

/** The most wire messages a turn sends (the proxy refuses more than 64; consecutive tool results are one wire message). */
export const MAX_WIRE_MESSAGES = 60;

/** How much of an earlier program's text is kept once it is cut. */
const PROGRAM_HEAD_CHARS = 240;

const encoder = new TextEncoder();
const bytes = (text: string): number => encoder.encode(text).byteLength;

/** What an earlier result becomes: its status, and how to get it again. */
export function elidedResult(content: string): string {
  let status = 'unknown';
  try {
    const parsed = JSON.parse(content) as { status?: unknown };
    if (typeof parsed.status === 'string') status = parsed.status;
  } catch {
    // not JSON: keep the unknown status
  }
  return JSON.stringify({ status, elided: true, note: 'This earlier result was removed to keep the conversation within the model\'s context. Run a narrow read again if you still need it.' });
}

function size(system: string, messages: readonly ChatMessage[]): number {
  return bytes(system) + bytes(JSON.stringify(messages)) + messages.length * 16;
}

function wireCount(messages: readonly ChatMessage[]): number {
  let count = 0;
  for (let i = 0; i < messages.length; i++) {
    if (messages[i]!.role === 'tool' && i > 0 && messages[i - 1]!.role === 'tool') continue;
    count++;
  }
  return count;
}

/** The view of `messages` a turn sends: at most `budget` bytes with `system`, when compaction can get there. */
export function fitContext(system: string, messages: readonly ChatMessage[], budget: number = CONTEXT_BUDGET_BYTES): ChatMessage[] {
  let view: ChatMessage[] = [...messages];
  const fits = (): boolean => size(system, view) <= budget && wireCount(view) <= MAX_WIRE_MESSAGES;
  if (fits()) return view;

  const lastUser = view.map((message) => message.role).lastIndexOf('user');
  // the latest turn: the last assistant message and the results after it
  let lastAssistant = view.map((message) => message.role).lastIndexOf('assistant');
  if (lastAssistant < lastUser) lastAssistant = view.length;

  // 1. carried reasoning: earlier requests first, then this request's earlier turns, oldest first
  for (let i = 0; i < view.length && !fits(); i++) {
    const message = view[i]!;
    if (message.role !== 'assistant' || message.continuation === undefined || i >= lastAssistant) continue;
    view[i] = { role: 'assistant', text: message.text, toolCalls: message.toolCalls };
  }
  // 2. earlier results, oldest first; the latest turn's results stay whole
  for (let i = 0; i < view.length && !fits(); i++) {
    const message = view[i]!;
    if (message.role !== 'tool' || i > lastAssistant) continue;
    const stub = elidedResult(message.content);
    if (stub.length < message.content.length) view[i] = { ...message, content: stub };
  }
  // 3. earlier programs, cut to their head (only where no carried reasoning repeats them: the proxy checks the two match)
  for (let i = 0; i < view.length && !fits(); i++) {
    const message = view[i]!;
    if (message.role !== 'assistant' || message.continuation !== undefined || i >= lastAssistant) continue;
    const toolCalls = message.toolCalls.map((call) => {
      const text = (call.input as { text?: unknown } | null)?.text;
      if (typeof text !== 'string' || text.length <= PROGRAM_HEAD_CHARS) return call;
      return { ...call, input: { text: `${text.slice(0, PROGRAM_HEAD_CHARS)}\n// ... [earlier program cut to keep the context small]` } };
    });
    view[i] = { ...message, toolCalls };
  }
  // 4. whole earlier requests, from the start, never this request
  while (!fits()) {
    const next = view.findIndex((message, index) => index > 0 && message.role === 'user');
    const current = view.map((message) => message.role).lastIndexOf('user');
    if (next < 0 || next > current) break;
    view = view.slice(next);
  }
  return view;
}
