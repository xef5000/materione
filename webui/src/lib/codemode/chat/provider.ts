/**
 * The model provider interface of the editor chatbot (issue 348; codemode.md section 12.1; master section 14.8).
 *
 * Provider-neutral: messages and tool definitions in, a stream of events out. Nothing here names a model, a key, a
 * vendor or an endpoint, on purpose. The real provider will call the model proxy (issue 402), which holds the credential
 * and picks the model from its own allowlist; a request that could name a model or carry a key would be a request the
 * page could tamper with. This file is types and two small pure helpers: it makes no network call, and nothing in
 * `chat/` does (a ban test reads the directory).
 */

/** The one tool the agent has (codemode.md section 2.2: the alternative, many fine-grained tools, is not built). */
export const RUN_PROGRAM = 'run_program';

/** A tool call the model made. `input` is the parsed arguments, as the provider read them; the loop validates it. */
export interface ToolCall {
  id: string;
  name: string;
  input: unknown;
}

export type ChatMessage =
  | { role: 'user'; text: string }
  | { role: 'assistant'; text: string; toolCalls: readonly ToolCall[]; continuation?: unknown }
  /** The result of one tool call, as bounded text (JSON). */
  | { role: 'tool'; callId: string; name: string; content: string };

/** A tool as the provider is told about it: a name, what it is for, and a JSON schema of its input. */
export interface ToolSpec {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: {
    readonly type: 'object';
    readonly properties: Readonly<Record<string, { readonly type: string; readonly description: string }>>;
    readonly required: readonly string[];
    readonly additionalProperties: false;
  };
}

/** What one model turn is asked with. There is no model name and no credential: the provider's side decides both. */
export interface ModelRequest {
  /** The agent's instructions and the generated index of the connected server's SDK (codemode.md section 3.7). */
  readonly system: string;
  readonly messages: readonly ChatMessage[];
  readonly tools: readonly ToolSpec[];
  /** Aborts the stream (the user pressed Stop, or the session ended). */
  readonly signal?: AbortSignal;
}

/** Token counts, when the provider reports them. Every field is optional: a provider that reports nothing sends no usage event. */
export interface ModelUsage {
  inputTokens?: number;
  outputTokens?: number;
  /** Input tokens served from the provider's cache (billed at a fraction, codemode.md section 12.1). */
  cachedInputTokens?: number;
  /** What the turn cost the user, in the provider's own unit (Materione AI credits), when it says. */
  credits?: number;
}

/**
 * A turn the provider did not make. The codes the proxy is expected to send are listed; an unknown code is shown with its
 * message as it came. A refusal is not an error of the editor and never retried by the loop.
 */
export interface ModelRefusal {
  code: 'allowance_exhausted' | 'rate_limited' | 'unavailable' | 'unauthenticated' | 'too_large' | 'script_ended' | (string & {});
  /** The provider's words, shown to the user as they are. */
  message: string;
  /** When trying again could work, if the provider says. */
  retryAfterSeconds?: number;
}

export type ModelEvent =
  | { type: 'text'; delta: string }
  | { type: 'tool_call'; call: ToolCall }
  | { type: 'continuation'; state: unknown }
  | { type: 'usage'; usage: ModelUsage }
  | { type: 'refusal'; refusal: ModelRefusal };

/** What the panel says about a provider. `remote` is the fact the disclosure is written from. */
export interface ProviderInfo {
  readonly id: string;
  /** A name for the user ("Recorded demonstration"). */
  readonly label: string;
  /** True when a turn sends the request off this page. False for a provider that runs wholly in the browser. */
  readonly remote: boolean;
}

export interface ModelProvider {
  readonly info: ProviderInfo;
  /** One model turn. The stream ends when the turn does; a refusal is an event, a transport failure is a thrown error. */
  stream(request: ModelRequest): AsyncIterable<ModelEvent>;
}

/** Adds one turn's usage to a running total; a field neither side reports stays absent. */
export function addUsage(total: ModelUsage, turn: ModelUsage): ModelUsage {
  const next: ModelUsage = { ...total };
  for (const key of ['inputTokens', 'outputTokens', 'cachedInputTokens', 'credits'] as const) {
    const value = turn[key];
    if (typeof value === 'number' && Number.isFinite(value)) next[key] = (next[key] ?? 0) + value;
  }
  return next;
}

/** A refusal in the user's terms. The provider's message is kept; the code only picks the lead-in. */
export function refusalText(refusal: ModelRefusal): string {
  const lead: Record<string, string> = {
    allowance_exhausted: 'Your AI allowance is used up.',
    rate_limited: 'Too many requests for now.',
    unavailable: 'The model is unavailable.',
    unauthenticated: 'You are not signed in for the assistant.',
    too_large: 'The request is larger than the provider takes.',
    script_ended: 'The recorded demonstration has ended.',
  };
  const head = lead[refusal.code] ?? 'The model provider refused the request.';
  const retry = typeof refusal.retryAfterSeconds === 'number' ? ` Try again in about ${Math.ceil(refusal.retryAfterSeconds)} s.` : '';
  const said = refusal.message.trim();
  return `${head}${said !== '' && said !== head ? ` ${said}` : ''}${retry}`;
}
