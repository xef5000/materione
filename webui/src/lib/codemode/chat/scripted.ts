/**
 * The scripted provider (issue 348): it replays recorded assistant turns, for tests and the demonstration. It runs wholly in
 * the page (`remote: false`), reads nothing but the request's message list, and has no timer unless a pace is asked for.
 *
 * Replay is a function of the conversation: the turn replayed is the one whose index is the number of assistant messages
 * already in the request. A new conversation therefore starts the script again, and the same history always yields the same
 * turn. A recorded refusal adds no assistant message, so asking again replays the same refusal (an exhausted allowance stays
 * exhausted). Past the last recorded turn the provider refuses with `script_ended`.
 */
import { RUN_PROGRAM, type ModelEvent, type ModelProvider, type ModelRefusal, type ModelUsage } from './provider';

/** One recorded assistant turn: what it said, and the program it ran, if it ran one. */
export interface ScriptedTurn {
  text?: string;
  /** The text of a `run_program` call. */
  program?: string;
  /** A raw tool call, for tests of the loop's input validation (a call the real tool would never be offered). */
  rawCall?: { name: string; input: unknown };
  usage?: ModelUsage;
  /** The provider refused this turn; nothing else of the turn is replayed. */
  refusal?: ModelRefusal;
}

export interface Script {
  id: string;
  title: string;
  /** What the user says, in order: the panel offers the next one, since a recording answers only what was recorded. */
  prompts: readonly string[];
  turns: readonly ScriptedTurn[];
}

export interface ScriptedOptions {
  /** Milliseconds between text chunks, so a demonstration reads like a stream. 0 (the default) emits without waiting. */
  paceMs?: number;
}

const wait = (ms: number, signal: AbortSignal | undefined): Promise<void> =>
  new Promise((resolve) => {
    if (signal?.aborted === true) return resolve();
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
  });

/** Words with their trailing space, so the chunks join back into the text exactly. */
function chunks(text: string): string[] {
  return text.match(/\S+\s*|\s+/g) ?? [];
}

export function createScriptedProvider(script: Script, options: ScriptedOptions = {}): ModelProvider {
  const paceMs = options.paceMs ?? 0;
  return {
    info: { id: `scripted:${script.id}`, label: `Recorded demonstration: ${script.title}`, remote: false },
    async *stream(request): AsyncIterable<ModelEvent> {
      const index = request.messages.filter((message) => message.role === 'assistant').length;
      const turn = script.turns[index];
      if (turn === undefined) {
        yield { type: 'refusal', refusal: { code: 'script_ended', message: 'A recording answers only what was recorded; there is nothing after this point.' } };
        return;
      }
      if (turn.refusal !== undefined) {
        yield { type: 'refusal', refusal: { ...turn.refusal } };
        return;
      }
      for (const delta of chunks(turn.text ?? '')) {
        if (request.signal?.aborted === true) return;
        if (paceMs > 0) await wait(paceMs, request.signal);
        yield { type: 'text', delta };
      }
      if (request.signal?.aborted === true) return;
      if (turn.program !== undefined) yield { type: 'tool_call', call: { id: `call-${index + 1}`, name: RUN_PROGRAM, input: { text: turn.program } } };
      if (turn.rawCall !== undefined) yield { type: 'tool_call', call: { id: `call-${index + 1}r`, name: turn.rawCall.name, input: structuredClone(turn.rawCall.input) } };
      if (turn.usage !== undefined) yield { type: 'usage', usage: { ...turn.usage } };
    },
  };
}
