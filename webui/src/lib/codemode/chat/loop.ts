/**
 * The agent loop (issue 348): one user message becomes model turns until the model answers without calling the tool, the
 * provider refuses, or a budget ends it. DOM-free and provider-neutral: the provider, the tool and the context are injected.
 *
 * The loop can do exactly two things: ask the provider for a turn, and run `run_program`. It holds no reference to the
 * proposal store, a grant or a gesture, so nothing a model says can approve, apply or revert (a ban test reads this file).
 */
import { addUsage, RUN_PROGRAM, type ChatMessage, type ModelProvider, type ModelRefusal, type ModelUsage, type ToolCall } from './provider';
import { RUN_PROGRAM_TOOL, type ToolOutcome } from './tool';

/**
 * The repair budget: the loop stops a request after this many FAILED tool calls (a type-check with errors, a run the
 * sandbox refused, a refused or stale dry run, a call that was not valid). A read and an accepted dry run do not count.
 * AWAITING OWNER CONFIRMATION: 3 is a placeholder until the benchmark measures how many repair turns a task needs
 * (codemode.md section 12.1, issue 385).
 */
export const REPAIR_BUDGET = 3;

/**
 * The most model turns one user message may take, failed or not: a ceiling on what one request can cost when a real
 * provider bills per turn. AWAITING OWNER CONFIRMATION, with `REPAIR_BUDGET`.
 */
export const MAX_MODEL_TURNS = 12;

/** Why a request ended. */
export type StopReason =
  /** The model answered without calling the tool. */
  | 'done'
  /** The provider refused the turn (allowance, rate, availability). */
  | 'refused'
  | 'repair-budget'
  | 'turn-limit'
  /** The user stopped it, or the session ended. */
  | 'aborted'
  /** The provider's stream failed. */
  | 'error';

export type LoopEvent =
  | { type: 'turn-start' }
  | { type: 'text'; delta: string }
  | { type: 'tool-start'; call: ToolCall; program: string | null }
  | { type: 'tool-end'; call: ToolCall; outcome: ToolOutcome }
  | { type: 'usage'; usage: ModelUsage };

export interface LoopDeps {
  provider: ModelProvider;
  /** The instructions and the generated index (context.ts). */
  system: string;
  /** Runs one `run_program` call. It never throws (tool.ts). */
  tool(input: unknown): Promise<ToolOutcome>;
  signal?: AbortSignal;
  onEvent?(event: LoopEvent): void;
}

export interface LoopResult {
  stop: StopReason;
  /** The messages this request added after the user's, in order: what the next request's history must carry. */
  added: ChatMessage[];
  failedCalls: number;
  turns: number;
  /** The sum of what the provider reported; empty when it reported nothing. */
  usage: ModelUsage;
  refusal?: ModelRefusal;
  /** The provider's failure, for `error`. */
  error?: string;
}

const UNKNOWN_TOOL = (name: string): ToolOutcome => ({
  status: 'invalid',
  failed: true,
  content: JSON.stringify({ status: 'invalid', note: `There is no tool named '${name.slice(0, 60)}'. The only tool is ${RUN_PROGRAM}({ text }).`, diagnostics: [] }),
  proposalId: null,
  diagnostics: [{ phase: 'input', severity: 'error', message: `There is no tool named '${name.slice(0, 60)}'.` }],
  missingPrimitives: [],
});

/** Runs one user request. `history` ends with the user's message. */
export async function runAgent(history: readonly ChatMessage[], deps: LoopDeps): Promise<LoopResult> {
  const messages: ChatMessage[] = [...history];
  const added: ChatMessage[] = [];
  let usage: ModelUsage = {};
  let failedCalls = 0;
  let turns = 0;
  const end = (stop: StopReason, extra: Partial<LoopResult> = {}): LoopResult => ({ stop, added, failedCalls, turns, usage, ...extra });
  // a function, so TypeScript does not narrow the flag across an await
  const aborted = (): boolean => deps.signal?.aborted === true;
  const push = (message: ChatMessage): void => {
    messages.push(message);
    added.push(message);
  };

  while (turns < MAX_MODEL_TURNS) {
    if (aborted()) return end('aborted');
    turns++;
    deps.onEvent?.({ type: 'turn-start' });
    let text = '';
    const calls: ToolCall[] = [];
    let refusal: ModelRefusal | undefined;
    try {
      for await (const event of deps.provider.stream({ system: deps.system, messages, tools: [RUN_PROGRAM_TOOL], signal: deps.signal })) {
        if (event.type === 'text') {
          text += event.delta;
          deps.onEvent?.({ type: 'text', delta: event.delta });
        } else if (event.type === 'tool_call') {
          calls.push(event.call);
        } else if (event.type === 'usage') {
          usage = addUsage(usage, event.usage);
          deps.onEvent?.({ type: 'usage', usage: event.usage });
        } else {
          refusal = event.refusal;
        }
      }
    } catch (error) {
      if (aborted()) return end('aborted');
      return end('error', { error: error instanceof Error ? error.message : String(error) });
    }
    if (aborted()) {
      // what was said before the stop is kept; a call that was not run is not kept (a history must not hold a call with no result)
      if (text !== '') push({ role: 'assistant', text, toolCalls: [] });
      return end('aborted');
    }
    if (refusal !== undefined) {
      if (text !== '') push({ role: 'assistant', text, toolCalls: [] });
      return end('refused', { refusal });
    }
    push({ role: 'assistant', text, toolCalls: calls });
    if (calls.length === 0) return end('done');

    for (const call of calls) {
      const program = call.name === RUN_PROGRAM && typeof (call.input as { text?: unknown } | null)?.text === 'string' ? (call.input as { text: string }).text : null;
      deps.onEvent?.({ type: 'tool-start', call, program });
      // every call gets a result, also after a stop, so the history stays well formed
      const outcome = call.name !== RUN_PROGRAM ? UNKNOWN_TOOL(call.name) : await deps.tool(call.input);
      if (outcome.failed) failedCalls++;
      push({ role: 'tool', callId: call.id, name: call.name, content: outcome.content });
      deps.onEvent?.({ type: 'tool-end', call, outcome });
    }
    if (failedCalls >= REPAIR_BUDGET) return end('repair-budget');
  }
  return end('turn-limit');
}

/** What the panel says when a request ended for a reason other than the model's own answer. Empty for `done` and `refused` (the refusal has its own text). */
export function stopText(result: Pick<LoopResult, 'stop' | 'failedCalls' | 'error'>): string {
  switch (result.stop) {
    case 'repair-budget':
      return `Stopped: ${result.failedCalls} programs of this request failed (the limit is ${REPAIR_BUDGET}). Nothing those programs staged was written. The findings are above; you can rephrase the request or ask for a smaller step.`;
    case 'turn-limit':
      return `Stopped: this request took ${MAX_MODEL_TURNS} model turns, the most one request may take. What was applied is in the change log.`;
    case 'aborted':
      return 'Stopped at your request.';
    case 'error':
      return `The model provider could not be reached: ${result.error ?? 'unknown error'}. Nothing was changed by this turn.`;
    default:
      return '';
  }
}
