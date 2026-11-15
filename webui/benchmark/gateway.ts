import type { ChatMessage, ModelEvent, ModelProvider, ModelRequest, ToolCall, ToolSpec } from '../src/lib/codemode/chat/provider';
import { BudgetLedger } from './budget';
import { assertNonFrontierModel, type BenchmarkModelSpec, type TokenUsageCounts } from './models';

export interface GatewayConfig {
  readonly accountId: string;
  readonly gatewayId: string;
  readonly token: string;
  readonly fetchFn?: typeof fetch;
  readonly baseUrlOverride?: string;
}

export type TurnSettlementCallback = (info: {
  model: BenchmarkModelSpec;
  usage: TokenUsageCounts;
  costPicos: number;
}) => void;

const textEncoder = new TextEncoder();
const DEFAULT_REQUEST_TIMEOUT_MS = 240_000;

function isFiniteNonNeg(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v) && v >= 0;
}

function estimateUpperBoundInputTokensFromBody(serializedBody: string): number {
  const utf8Bytes = textEncoder.encode(serializedBody).byteLength;
  return Math.ceil(utf8Bytes / 2) + 1_024;
}

function toAnthropicMessages(
  messages: readonly ChatMessage[],
  thinkingByCallId?: ReadonlyMap<string, readonly unknown[]>,
): unknown[] {
  const out: unknown[] = [];
  for (let i = 0; i < messages.length; i++) {
    const msg = messages[i]!;
    if (msg.role === 'user') {
      out.push({ role: 'user', content: msg.text });
    } else if (msg.role === 'assistant') {
      const blocks: unknown[] = [];
      const firstCallId = msg.toolCalls[0]?.id;
      const cachedThinking = firstCallId ? thinkingByCallId?.get(firstCallId) : undefined;
      if (cachedThinking && cachedThinking.length > 0) {
        blocks.push(...cachedThinking);
      }
      if (msg.text !== '') blocks.push({ type: 'text', text: msg.text });
      for (const call of msg.toolCalls) {
        blocks.push({ type: 'tool_use', id: call.id, name: call.name, input: call.input ?? {} });
      }
      if (blocks.length === 0) blocks.push({ type: 'text', text: '' });
      out.push({ role: 'assistant', content: blocks });
    } else if (msg.role === 'tool') {
      const toolBlocks: unknown[] = [{ type: 'tool_result', tool_use_id: msg.callId, content: msg.content }];
      while (i + 1 < messages.length && messages[i + 1]!.role === 'tool') {
        i++;
        const next = messages[i]! as Extract<ChatMessage, { role: 'tool' }>;
        toolBlocks.push({ type: 'tool_result', tool_use_id: next.callId, content: next.content });
      }
      out.push({ role: 'user', content: toolBlocks });
    }
  }
  return out;
}

function toAnthropicTools(tools: readonly ToolSpec[]): unknown[] {
  return tools.map((tool) => ({
    name: tool.name,
    description: tool.description,
    input_schema: tool.inputSchema,
  }));
}

function toCompatMessages(system: string, messages: readonly ChatMessage[]): unknown[] {
  const out: unknown[] = [{ role: 'system', content: system }];
  for (const msg of messages) {
    if (msg.role === 'user') {
      out.push({ role: 'user', content: msg.text });
    } else if (msg.role === 'assistant') {
      const entry: Record<string, unknown> = {
        role: 'assistant',
        content: msg.text !== '' ? msg.text : null,
      };
      if (msg.toolCalls.length > 0) {
        entry.tool_calls = msg.toolCalls.map((call) => ({
          id: call.id,
          type: 'function',
          function: {
            name: call.name,
            arguments: JSON.stringify(call.input ?? {}),
          },
        }));
      }
      out.push(entry);
    } else if (msg.role === 'tool') {
      out.push({ role: 'tool', tool_call_id: msg.callId, content: msg.content });
    }
  }
  return out;
}

function toCompatTools(tools: readonly ToolSpec[]): unknown[] {
  return tools.map((tool) => ({
    type: 'function',
    function: {
      name: tool.name,
      description: tool.description,
      parameters: tool.inputSchema,
    },
  }));
}

function toOpenAiResponsesInput(
  messages: readonly ChatMessage[],
  rawOutputByCallId?: ReadonlyMap<string, readonly unknown[]>,
): unknown[] {
  const out: unknown[] = [];
  for (const msg of messages) {
    if (msg.role === 'user') {
      out.push({ role: 'user', content: msg.text });
    } else if (msg.role === 'assistant') {
      const firstCallId = msg.toolCalls[0]?.id;
      const cachedRaw = firstCallId ? rawOutputByCallId?.get(firstCallId) : undefined;
      if (cachedRaw && cachedRaw.length > 0) {
        out.push(...cachedRaw);
      } else {
        if (msg.text !== '') {
          out.push({ role: 'assistant', content: msg.text });
        }
        for (const call of msg.toolCalls) {
          out.push({
            type: 'function_call',
            call_id: call.id,
            name: call.name,
            arguments: JSON.stringify(call.input ?? {}),
          });
        }
      }
    } else if (msg.role === 'tool') {
      out.push({ type: 'function_call_output', call_id: msg.callId, output: msg.content });
    }
  }
  return out;
}

function toOpenAiResponsesTools(tools: readonly ToolSpec[]): unknown[] {
  return tools.map((tool) => ({
    type: 'function',
    name: tool.name,
    description: tool.description,
    parameters: tool.inputSchema,
  }));
}

export function buildAnthropicRequest(
  model: BenchmarkModelSpec,
  req: ModelRequest,
  config: GatewayConfig,
  thinkingByCallId?: ReadonlyMap<string, readonly unknown[]>,
): { url: string; init: RequestInit } {
  const url =
    config.baseUrlOverride ??
    `https://gateway.ai.cloudflare.com/v1/${config.accountId}/${config.gatewayId}/anthropic/v1/messages`;
  const body: Record<string, unknown> = {
    model: model.upstreamModel,
    max_tokens: model.maxOutputTokens,
    stream: false,
    system: [{ type: 'text', text: req.system, cache_control: { type: 'ephemeral' } }],
    tools: toAnthropicTools(req.tools),
    messages: toAnthropicMessages(req.messages, thinkingByCallId),
  };
  if (model.anthropicAdaptiveThinking === true) {
    body.thinking = { type: 'adaptive' };
    if (model.reasoningEffort && model.reasoningEffort !== 'none') {
      body.output_config = { effort: model.reasoningEffort };
    }
  }
  return {
    url,
    init: {
      method: 'POST',
      signal: req.signal,
      headers: {
        'content-type': 'application/json',
        'anthropic-version': '2023-06-01',
        'cf-aig-authorization': `Bearer ${config.token}`,
        'cf-aig-collect-log-payload': 'false',
      },
      body: JSON.stringify(body),
    },
  };
}

export function buildOpenAiResponsesRequest(
  model: BenchmarkModelSpec,
  req: ModelRequest,
  config: GatewayConfig,
  rawOutputByCallId?: ReadonlyMap<string, readonly unknown[]>,
): { url: string; init: RequestInit } {
  const url =
    config.baseUrlOverride ??
    `https://gateway.ai.cloudflare.com/v1/${config.accountId}/${config.gatewayId}/openai/responses`;
  const body: Record<string, unknown> = {
    model: model.upstreamModel,
    instructions: req.system,
    input: toOpenAiResponsesInput(req.messages, rawOutputByCallId),
    tools: toOpenAiResponsesTools(req.tools),
    max_output_tokens: model.maxOutputTokens,
    store: false,
  };
  if (model.reasoningEffort) {
    body.reasoning = { effort: model.reasoningEffort };
    body.include = ['reasoning.encrypted_content'];
  }
  return {
    url,
    init: {
      method: 'POST',
      signal: req.signal,
      headers: {
        'content-type': 'application/json',
        'cf-aig-authorization': `Bearer ${config.token}`,
        'cf-aig-collect-log-payload': 'false',
      },
      body: JSON.stringify(body),
    },
  };
}

export function buildCompatRequest(
  model: BenchmarkModelSpec,
  req: ModelRequest,
  config: GatewayConfig,
): { url: string; init: RequestInit } {
  const url =
    config.baseUrlOverride ??
    `https://gateway.ai.cloudflare.com/v1/${config.accountId}/${config.gatewayId}/compat/chat/completions`;
  const body: Record<string, unknown> = {
    model: model.upstreamModel,
    max_completion_tokens: model.maxOutputTokens,
    stream: false,
    tools: toCompatTools(req.tools),
    messages: toCompatMessages(req.system, req.messages),
  };
  if (model.reasoningEffort) {
    body.reasoning_effort = model.reasoningEffort;
  }
  return {
    url,
    init: {
      method: 'POST',
      signal: req.signal,
      headers: {
        'content-type': 'application/json',
        'cf-aig-authorization': `Bearer ${config.token}`,
        authorization: `Bearer ${config.token}`,
        'cf-aig-collect-log-payload': 'false',
      },
      body: JSON.stringify(body),
    },
  };
}

function parseAnthropicResponse(data: Record<string, unknown>): {
  text: string;
  toolCalls: ToolCall[];
  usage: TokenUsageCounts | undefined;
  truncated: boolean;
  thinkingBlocks: unknown[];
} {
  let text = '';
  const toolCalls: ToolCall[] = [];
  const thinkingBlocks: unknown[] = [];
  const truncated = data.stop_reason === 'max_tokens';
  const content = Array.isArray(data.content) ? data.content : [];
  for (const block of content) {
    if (typeof block !== 'object' || block === null) continue;
    const b = block as Record<string, unknown>;
    if (b.type === 'thinking' || b.type === 'redacted_thinking') {
      thinkingBlocks.push(block);
    } else if (b.type === 'text' && typeof b.text === 'string') {
      text += b.text;
    } else if (b.type === 'tool_use' && typeof b.id === 'string' && typeof b.name === 'string') {
      toolCalls.push({ id: b.id, name: b.name, input: b.input ?? {} });
    }
  }
  if (truncated) {
    text += `${text === '' ? '' : '\n'}[Response truncated: stop_reason=max_tokens]`;
  }
  let usage: TokenUsageCounts | undefined;
  if (typeof data.usage === 'object' && data.usage !== null && !Array.isArray(data.usage)) {
    const u = data.usage as Record<string, unknown>;
    if (isFiniteNonNeg(u.input_tokens) || isFiniteNonNeg(u.output_tokens)) {
      usage = {
        inputTokens: isFiniteNonNeg(u.input_tokens) ? u.input_tokens : 0,
        outputTokens: isFiniteNonNeg(u.output_tokens) ? u.output_tokens : 0,
        cachedInputTokens: isFiniteNonNeg(u.cache_read_input_tokens) ? u.cache_read_input_tokens : 0,
        cacheWriteTokens: isFiniteNonNeg(u.cache_creation_input_tokens) ? u.cache_creation_input_tokens : 0,
      };
    }
  }
  return { text, toolCalls, usage, truncated, thinkingBlocks };
}

export function parseOpenAiResponses(data: Record<string, unknown>): {
  text: string;
  toolCalls: ToolCall[];
  usage: TokenUsageCounts | undefined;
  truncated: boolean;
  rawOutputItems: unknown[];
} {
  let text = '';
  const toolCalls: ToolCall[] = [];
  const rawOutputItems: unknown[] = [];
  const truncated = data.status === 'incomplete';
  const output = Array.isArray(data.output) ? data.output : [];
  for (const item of output) {
    if (typeof item !== 'object' || item === null) continue;
    rawOutputItems.push(item);
    const it = item as Record<string, unknown>;
    if (it.type === 'message' && Array.isArray(it.content)) {
      for (const part of it.content) {
        if (typeof part !== 'object' || part === null) continue;
        const p = part as Record<string, unknown>;
        if (p.type === 'output_text' && typeof p.text === 'string') {
          text += p.text;
        }
      }
    } else if (it.type === 'function_call') {
      let input: unknown = {};
      if (typeof it.arguments === 'string' && it.arguments.trim() !== '') {
        try {
          input = JSON.parse(it.arguments);
        } catch {
          input = {
            __invalidToolArguments: true,
            __truncated: truncated,
            rawArguments: it.arguments.slice(0, 500),
          };
        }
      } else if (typeof it.arguments === 'object' && it.arguments !== null) {
        input = it.arguments;
      }
      toolCalls.push({
        id: String(it.call_id ?? it.id ?? 'call'),
        name: String(it.name ?? ''),
        input,
      });
    }
  }
  if (truncated) {
    text += `${text === '' ? '' : '\n'}[Response truncated: status=incomplete]`;
  }
  let usage: TokenUsageCounts | undefined;
  if (typeof data.usage === 'object' && data.usage !== null && !Array.isArray(data.usage)) {
    const u = data.usage as Record<string, unknown>;
    if (isFiniteNonNeg(u.input_tokens) || isFiniteNonNeg(u.output_tokens)) {
      const details = (typeof u.input_tokens_details === 'object' && u.input_tokens_details !== null
        ? u.input_tokens_details
        : {}) as Record<string, unknown>;
      const cached = isFiniteNonNeg(details.cached_tokens) ? details.cached_tokens : 0;
      const cacheWrite = isFiniteNonNeg(details.cache_write_tokens) ? details.cache_write_tokens : 0;
      const totalInput = isFiniteNonNeg(u.input_tokens) ? u.input_tokens : 0;
      usage = {
        inputTokens: Math.max(0, totalInput - cached - cacheWrite),
        outputTokens: isFiniteNonNeg(u.output_tokens) ? u.output_tokens : 0,
        cachedInputTokens: cached,
        cacheWriteTokens: cacheWrite,
      };
    }
  }
  return { text, toolCalls, usage, truncated, rawOutputItems };
}

function parseCompatResponse(data: Record<string, unknown>): {
  text: string;
  toolCalls: ToolCall[];
  usage: TokenUsageCounts | undefined;
  truncated: boolean;
} {
  const choices = Array.isArray(data.choices) ? data.choices : [];
  const first = (typeof choices[0] === 'object' && choices[0] !== null ? choices[0] : {}) as Record<string, unknown>;
  const truncated = first.finish_reason === 'length';
  const message = (typeof first.message === 'object' && first.message !== null ? first.message : {}) as Record<string, unknown>;
  let text = typeof message.content === 'string' ? message.content : '';
  const toolCalls: ToolCall[] = [];
  const rawCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
  for (const raw of rawCalls) {
    if (typeof raw !== 'object' || raw === null) continue;
    const c = raw as Record<string, unknown>;
    const fn = (typeof c.function === 'object' && c.function !== null ? c.function : {}) as Record<string, unknown>;
    if (typeof c.id === 'string' && typeof fn.name === 'string') {
      let input: unknown = {};
      if (typeof fn.arguments === 'string' && fn.arguments.trim() !== '') {
        try {
          input = JSON.parse(fn.arguments);
        } catch {
          input = {
            __invalidToolArguments: true,
            __truncated: truncated,
            rawArguments: fn.arguments.slice(0, 500),
          };
        }
      }
      toolCalls.push({ id: c.id, name: fn.name, input });
    }
  }
  if (truncated) {
    text += `${text === '' ? '' : '\n'}[Response truncated: finish_reason=length]`;
  }
  let usage: TokenUsageCounts | undefined;
  if (typeof data.usage === 'object' && data.usage !== null && !Array.isArray(data.usage)) {
    const u = data.usage as Record<string, unknown>;
    if (isFiniteNonNeg(u.prompt_tokens) || isFiniteNonNeg(u.completion_tokens)) {
      const details = (typeof u.prompt_tokens_details === 'object' && u.prompt_tokens_details !== null
        ? u.prompt_tokens_details
        : {}) as Record<string, unknown>;
      const cached = isFiniteNonNeg(details.cached_tokens) ? details.cached_tokens : 0;
      const totalPrompt = isFiniteNonNeg(u.prompt_tokens) ? u.prompt_tokens : 0;
      usage = {
        inputTokens: Math.max(0, totalPrompt - cached),
        outputTokens: isFiniteNonNeg(u.completion_tokens) ? u.completion_tokens : 0,
        cachedInputTokens: cached,
        cacheWriteTokens: 0,
      };
    }
  }
  return { text, toolCalls, usage, truncated };
}

export function createGatewayProvider(
  model: BenchmarkModelSpec,
  budget: BudgetLedger,
  config: GatewayConfig,
  onSettled?: TurnSettlementCallback,
): ModelProvider {
  assertNonFrontierModel(model);
  const fetchImpl = config.fetchFn ?? fetch;
  const thinkingByCallId = new Map<string, readonly unknown[]>();
  const rawOutputByCallId = new Map<string, readonly unknown[]>();

  return {
    info: {
      id: `gateway:${model.id}`,
      label: `Cloudflare AI Gateway (${model.id})`,
      remote: true,
    },
    async *stream(req: ModelRequest): AsyncIterable<ModelEvent> {
      if (req.signal?.aborted) {
        throw req.signal.reason instanceof Error
          ? req.signal.reason
          : new DOMException('The operation was aborted.', 'AbortError');
      }

      const built =
        model.transport === 'anthropic'
          ? buildAnthropicRequest(model, req, config, thinkingByCallId)
          : model.transport === 'openai-responses'
            ? buildOpenAiResponsesRequest(model, req, config, rawOutputByCallId)
            : buildCompatRequest(model, req, config);
      const bodyStr = typeof built.init.body === 'string' ? built.init.body : '';
      const upperBoundInput = estimateUpperBoundInputTokensFromBody(bodyStr);

      if (upperBoundInput > model.longContextThresholdTokens) {
        yield {
          type: 'refusal',
          refusal: {
            code: 'context_too_large',
            message: `Estimated upper-bound input tokens (${upperBoundInput}) exceed short-context ceiling (${model.longContextThresholdTokens}) for ${model.id}.`,
          },
        };
        return;
      }

      const check = budget.canAffordTurn(model, upperBoundInput);
      if (!check.ok) {
        yield { type: 'refusal', refusal: check.refusal };
        return;
      }

      const timeoutSignal = AbortSignal.timeout(DEFAULT_REQUEST_TIMEOUT_MS);
      const combinedSignal = req.signal ? AbortSignal.any([req.signal, timeoutSignal]) : timeoutSignal;
      const requestInit: RequestInit = { ...built.init, signal: combinedSignal };

      let settled = false;
      try {
        const res = await fetchImpl(built.url, requestInit);
        if (!res.ok) {
          const bodyText = await res.text().catch(() => '');
          const zeroUsage: TokenUsageCounts = {
            inputTokens: 0,
            outputTokens: 0,
            cachedInputTokens: 0,
            cacheWriteTokens: 0,
          };
          if (res.status >= 400 && res.status < 500) {
            budget.settleTurn(model, 0, zeroUsage);
            settled = true;
            onSettled?.({ model, usage: zeroUsage, costPicos: 0 });
            if (res.status === 429) {
              yield {
                type: 'refusal',
                refusal: { code: 'rate_limited', message: bodyText || 'AI Gateway rate limit' },
              };
              return;
            }
            throw new Error(`Gateway HTTP ${res.status}: ${bodyText.slice(0, 300)}`);
          }
          // 5xx or unexpected status: upstream compute may have run, so settle worst-case (fail-closed)
          const costPicos = budget.settleTurn(model, check.worstCasePicos, undefined);
          settled = true;
          onSettled?.({ model, usage: zeroUsage, costPicos });
          throw new Error(`Gateway HTTP ${res.status}: ${bodyText.slice(0, 300)}`);
        }

        const json = (await res.json()) as Record<string, unknown>;
        let parsed: {
          text: string;
          toolCalls: ToolCall[];
          usage: TokenUsageCounts | undefined;
          truncated: boolean;
        };
        if (model.transport === 'anthropic') {
          const anthParsed = parseAnthropicResponse(json);
          if (anthParsed.thinkingBlocks.length > 0 && anthParsed.toolCalls.length > 0) {
            thinkingByCallId.set(anthParsed.toolCalls[0]!.id, anthParsed.thinkingBlocks);
          }
          parsed = anthParsed;
        } else if (model.transport === 'openai-responses') {
          const respParsed = parseOpenAiResponses(json);
          if (respParsed.rawOutputItems.length > 0 && respParsed.toolCalls.length > 0) {
            rawOutputByCallId.set(respParsed.toolCalls[0]!.id, respParsed.rawOutputItems);
          }
          parsed = respParsed;
        } else {
          parsed = parseCompatResponse(json);
        }
        const costPicos = budget.settleTurn(model, check.worstCasePicos, parsed.usage);
        settled = true;
        const reportedUsage: TokenUsageCounts = parsed.usage ?? {
          inputTokens: 0,
          outputTokens: 0,
          cachedInputTokens: 0,
          cacheWriteTokens: 0,
        };
        onSettled?.({ model, usage: reportedUsage, costPicos });

        if (parsed.text !== '') {
          yield { type: 'text', delta: parsed.text };
        }
        for (const call of parsed.toolCalls) {
          yield { type: 'tool_call', call };
        }
        if (parsed.usage !== undefined) {
          yield {
            type: 'usage',
            usage: {
              inputTokens: parsed.usage.inputTokens,
              outputTokens: parsed.usage.outputTokens,
              cachedInputTokens: parsed.usage.cachedInputTokens,
            },
          };
        }
      } finally {
        if (!settled) {
          const costPicos = budget.settleTurn(model, check.worstCasePicos);
          onSettled?.({
            model,
            usage: { inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 },
            costPicos,
          });
        }
      }
    },
  };
}
