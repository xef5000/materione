/**
 * The context budget of a model turn (codemode.md section 3.7): what a turn sends fits what the model proxy accepts, the
 * history the loop keeps is untouched, and a compacted view stays well formed.
 */
import { describe, expect, it } from 'vitest';
import { CONTEXT_BUDGET_BYTES, elidedResult, fitContext, MAX_WIRE_MESSAGES } from '../src/lib/codemode/chat/budget';
import { runAgent } from '../src/lib/codemode/chat/loop';
import { RUN_PROGRAM, type ChatMessage, type ModelEvent, type ModelProvider, type ModelRequest } from '../src/lib/codemode/chat/provider';
import type { ToolOutcome } from '../src/lib/codemode/chat/tool';

const SYSTEM = 'S'.repeat(28_000);
const bytes = (system: string, messages: readonly ChatMessage[]): number => new TextEncoder().encode(system + JSON.stringify(messages)).byteLength + messages.length * 16;
const big = (status: string, n: number): string => JSON.stringify({ status, note: 'n', diagnostics: [], result: { valueCut: 'x'.repeat(n) } });

/** One model turn that called the tool once: the assistant message (with carried reasoning) and its result. */
function turn(id: string, resultChars: number, reasoningChars = 4_000): ChatMessage[] {
  return [
    { role: 'assistant', text: '', toolCalls: [{ id, name: RUN_PROGRAM, input: { text: `return m.docs.type("item"); // ${'p'.repeat(600)}` } }], continuation: [{ type: 'reasoning', encrypted_content: 'r'.repeat(reasoningChars) }] },
    { role: 'tool', callId: id, name: RUN_PROGRAM, content: big('read', resultChars) },
  ];
}

/** Every call has its result, every result follows its call, and the view starts with the user. */
function wellFormed(view: readonly ChatMessage[]): void {
  expect(view[0]?.role).toBe('user');
  const open = new Set<string>();
  for (const message of view) {
    if (message.role === 'assistant') for (const call of message.toolCalls) open.add(call.id);
    if (message.role === 'tool') {
      expect(open.has(message.callId)).toBe(true);
      open.delete(message.callId);
    }
  }
  expect([...open]).toEqual([]);
}

describe('fitContext', () => {
  it('sends a history that fits unchanged', () => {
    const history: ChatMessage[] = [{ role: 'user', text: 'make a ruby' }, ...turn('c1', 2_000)];
    expect(fitContext(SYSTEM, history)).toEqual(history);
  });

  it('fits the request that overflowed in the field: five large reads after a 28 KB system text', () => {
    const history: ChatMessage[] = [{ role: 'user', text: 'a kits menu' }];
    for (let i = 1; i <= 5; i++) history.push(...turn(`c${i}`, 9_000));
    expect(bytes(SYSTEM, history)).toBeGreaterThan(80_000);
    const view = fitContext(SYSTEM, history);
    expect(bytes(SYSTEM, view)).toBeLessThanOrEqual(CONTEXT_BUDGET_BYTES);
    wellFormed(view);
    // the latest turn is whole: its reasoning and its result
    expect(view.at(-2)).toEqual(history.at(-2));
    expect(view.at(-1)).toEqual(history.at(-1));
    // the oldest result is a stub that keeps its status
    const first = view.find((message) => message.role === 'tool');
    expect(first?.role === 'tool' && JSON.parse(first.content)).toMatchObject({ status: 'read', elided: true });
  });

  it('drops carried reasoning before it touches any result', () => {
    const history: ChatMessage[] = [{ role: 'user', text: 'go' }, ...turn('c1', 1_000, 30_000), ...turn('c2', 1_000, 30_000)];
    const view = fitContext(SYSTEM, history);
    expect(view[1]).not.toHaveProperty('continuation');
    expect(view[2]).toEqual(history[2]);
    expect(view[3]).toEqual(history[3]);
  });

  it('cuts an earlier program only where no carried reasoning repeats it (the proxy checks the two match)', () => {
    const history: ChatMessage[] = [{ role: 'user', text: 'go' }];
    for (let i = 1; i <= 30; i++) history.push(...turn(`c${i}`, 100, 0));
    const view = fitContext(SYSTEM, history, 50_000);
    wellFormed(view);
    for (const message of view.slice(0, -2)) {
      if (message.role !== 'assistant') continue;
      expect(message.continuation).toBeUndefined();
    }
    const firstCall = view.find((message) => message.role === 'assistant');
    expect(firstCall?.role === 'assistant' && (firstCall.toolCalls[0]!.input as { text: string }).text).toContain('[earlier program cut');
  });

  it('drops whole earlier requests, never the current one, and keeps the wire message count under the proxy limit', () => {
    const history: ChatMessage[] = [];
    for (let r = 0; r < 10; r++) {
      history.push({ role: 'user', text: `request ${r}` });
      for (let i = 0; i < 4; i++) history.push(...turn(`r${r}c${i}`, 100, 0));
      history.push({ role: 'assistant', text: 'done', toolCalls: [] });
    }
    const view = fitContext('', history);
    wellFormed(view);
    expect(view.length).toBeLessThanOrEqual(MAX_WIRE_MESSAGES);
    expect(view.at(-1)).toEqual(history.at(-1));
    expect(view.some((message) => message.role === 'user' && message.text === 'request 9')).toBe(true);
    expect(view.some((message) => message.role === 'user' && message.text === 'request 0')).toBe(false);
  });

  it('does not change the history it was given', () => {
    const history: ChatMessage[] = [{ role: 'user', text: 'go' }];
    for (let i = 1; i <= 6; i++) history.push(...turn(`c${i}`, 9_000));
    const copy = structuredClone(history);
    fitContext(SYSTEM, history);
    expect(history).toEqual(copy);
  });

  it('stubs keep the status of a result that is not JSON as unknown', () => {
    expect(JSON.parse(elidedResult('plain text'))).toMatchObject({ status: 'unknown', elided: true });
  });
});

describe('the loop sends the fitted view and keeps the whole history', () => {
  it('a long request stays within the budget on every turn', async () => {
    const sizes: number[] = [];
    let turnNo = 0;
    const provider: ModelProvider = {
      info: { id: 'spy', label: 'spy', remote: false },
      async *stream(request: ModelRequest): AsyncIterable<ModelEvent> {
        sizes.push(bytes(request.system, request.messages));
        turnNo++;
        if (turnNo <= 8) {
          yield { type: 'continuation', state: [{ type: 'reasoning', encrypted_content: 'r'.repeat(5_000) }] };
          yield { type: 'tool_call', call: { id: `c${turnNo}`, name: RUN_PROGRAM, input: { text: 'return m.docs.type("item");' } } };
        } else {
          yield { type: 'text', delta: 'done' };
        }
      },
    };
    const read: ToolOutcome = { status: 'read', failed: false, content: big('read', 11_000), proposalId: null, diagnostics: [], missingPrimitives: [] };
    const result = await runAgent([{ role: 'user', text: 'go' }], { provider, system: SYSTEM, tool: async () => read });
    expect(result.stop).toBe('done');
    expect(Math.max(...sizes)).toBeLessThanOrEqual(CONTEXT_BUDGET_BYTES);
    // the history the next request carries is whole
    expect(result.added.filter((message) => message.role === 'tool').every((message) => message.role === 'tool' && message.content === read.content)).toBe(true);
  });
});
