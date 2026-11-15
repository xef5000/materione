export const PICOS_PER_DOLLAR = 1_000_000_000_000;

export type ModelTier = 'cheap' | 'mid';
export type ModelTransport = 'anthropic' | 'openai-responses' | 'compat';
export type ReasoningEffort = 'none' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';

export interface BenchmarkModelSpec {
  readonly id: string;
  readonly catalogId: string;
  readonly tier: ModelTier;
  readonly transport: ModelTransport;
  readonly upstreamModel: string;
  readonly reasoningEffort?: ReasoningEffort;
  readonly anthropicAdaptiveThinking?: boolean;
  readonly inputPicosPerToken: number;
  readonly cacheReadPicosPerToken: number;
  readonly cacheWritePicosPerToken: number;
  readonly outputPicosPerToken: number;
  readonly maxOutputTokens: number;
  readonly longContextThresholdTokens: number;
}

export interface TokenUsageCounts {
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly cachedInputTokens: number;
  readonly cacheWriteTokens: number;
}

const FRONTIER_NAME_PATTERN = /\b(opus|fable|astra|o1-pro|o3-pro|gpt-4\.5|gpt-5-pro|gpt-5\.4-pro|gpt-5\.5-pro)\b/i;
export const MAX_NON_FRONTIER_INPUT_PICOS = 5_000_000; // $5.00 / 1M tokens
export const MAX_NON_FRONTIER_OUTPUT_PICOS = 25_000_000; // $25.00 / 1M tokens

export const DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS = 195_000;
export const MAX_OUTPUT_TOKENS_CEILING = 65_536;

export const PRESET_MODELS: Readonly<Record<string, BenchmarkModelSpec>> = {
  'claude-haiku-5-5': {
    id: 'claude-haiku-5-5',
    catalogId: 'anthropic/claude-haiku-5.5',
    tier: 'cheap',
    transport: 'anthropic',
    upstreamModel: 'claude-haiku-5-5',
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 8192,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'claude-haiku-5-5-medium': {
    id: 'claude-haiku-5-5-medium',
    catalogId: 'anthropic/claude-haiku-5.5',
    tier: 'cheap',
    transport: 'anthropic',
    upstreamModel: 'claude-haiku-5-5',
    reasoningEffort: 'medium',
    anthropicAdaptiveThinking: true,
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 12288,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'claude-haiku-5-5-high': {
    id: 'claude-haiku-5-5-high',
    catalogId: 'anthropic/claude-haiku-5.5',
    tier: 'cheap',
    transport: 'anthropic',
    upstreamModel: 'claude-haiku-5-5',
    reasoningEffort: 'high',
    anthropicAdaptiveThinking: true,
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'claude-haiku-5-5-xhigh': {
    id: 'claude-haiku-5-5-xhigh',
    catalogId: 'anthropic/claude-haiku-5.5',
    tier: 'cheap',
    transport: 'anthropic',
    upstreamModel: 'claude-haiku-5-5',
    reasoningEffort: 'xhigh',
    anthropicAdaptiveThinking: true,
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6-luna-low': {
    id: 'gpt-6-luna-low',
    catalogId: 'openai/gpt-6-luna',
    tier: 'cheap',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6-luna',
    reasoningEffort: 'low',
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 8192,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6-luna-medium': {
    id: 'gpt-6-luna-medium',
    catalogId: 'openai/gpt-6-luna',
    tier: 'cheap',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6-luna',
    reasoningEffort: 'medium',
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 12288,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6-luna-high': {
    id: 'gpt-6-luna-high',
    catalogId: 'openai/gpt-6-luna',
    tier: 'cheap',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6-luna',
    reasoningEffort: 'high',
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6-luna-xhigh': {
    id: 'gpt-6-luna-xhigh',
    catalogId: 'openai/gpt-6-luna',
    tier: 'cheap',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6-luna',
    reasoningEffort: 'xhigh',
    inputPicosPerToken: 100_000, // $0.10 / 1M
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M
    outputPicosPerToken: 500_000, // $0.50 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'deepseek-v4.1-flash-1017': {
    id: 'deepseek-v4.1-flash-1017',
    catalogId: '@cf/deepseek-ai/deepseek-v4.1-flash-1017',
    tier: 'cheap',
    transport: 'compat',
    upstreamModel: 'workers-ai/@cf/deepseek-ai/deepseek-v4.1-flash-1017',
    reasoningEffort: 'high',
    inputPicosPerToken: 300_000, // $0.30 / 1M
    cacheReadPicosPerToken: 6_000, // $0.006 / 1M
    cacheWritePicosPerToken: 300_000, // $0.30 / 1M
    outputPicosPerToken: 1_200_000, // $1.20 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'deepseek-v4.1-flash-1017-max': {
    id: 'deepseek-v4.1-flash-1017-max',
    catalogId: '@cf/deepseek-ai/deepseek-v4.1-flash-1017',
    tier: 'cheap',
    transport: 'compat',
    upstreamModel: 'workers-ai/@cf/deepseek-ai/deepseek-v4.1-flash-1017',
    reasoningEffort: 'max',
    inputPicosPerToken: 300_000, // $0.30 / 1M
    cacheReadPicosPerToken: 6_000, // $0.006 / 1M
    cacheWritePicosPerToken: 300_000, // $0.30 / 1M
    outputPicosPerToken: 1_200_000, // $1.20 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gemini-4-hydrogen-xhigh': {
    id: 'gemini-4-hydrogen-xhigh',
    catalogId: 'google/gemini-4-hydrogen',
    tier: 'mid',
    transport: 'compat',
    upstreamModel: 'google/gemini-4-hydrogen',
    reasoningEffort: 'xhigh',
    inputPicosPerToken: 750_000, // $0.75 / 1M
    cacheReadPicosPerToken: 75_000, // $0.075 / 1M
    cacheWritePicosPerToken: 750_000, // $0.75 / 1M
    outputPicosPerToken: 3_750_000, // $3.75 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gemini-3.8-flash': {
    id: 'gemini-3.8-flash',
    catalogId: 'google/gemini-3.8-flash',
    tier: 'cheap',
    transport: 'compat',
    upstreamModel: 'google/gemini-3.8-flash',
    inputPicosPerToken: 750_000, // $0.75 / 1M
    cacheReadPicosPerToken: 75_000, // $0.075 / 1M
    cacheWritePicosPerToken: 750_000, // $0.75 / 1M
    outputPicosPerToken: 3_750_000, // $3.75 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-terra-low': {
    id: 'gpt-6.1-terra-low',
    catalogId: 'openai/gpt-6.1-terra',
    tier: 'cheap',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-terra',
    reasoningEffort: 'low',
    inputPicosPerToken: 1_000_000, // $1.00 / 1M
    cacheReadPicosPerToken: 50_000, // $0.05 / 1M
    cacheWritePicosPerToken: 1_250_000, // $1.25 / 1M
    outputPicosPerToken: 5_000_000, // $5.00 / 1M
    maxOutputTokens: 12288,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-terra-medium': {
    id: 'gpt-6.1-terra-medium',
    catalogId: 'openai/gpt-6.1-terra',
    tier: 'mid',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-terra',
    reasoningEffort: 'medium',
    inputPicosPerToken: 1_000_000, // $1.00 / 1M
    cacheReadPicosPerToken: 50_000, // $0.05 / 1M
    cacheWritePicosPerToken: 1_250_000, // $1.25 / 1M
    outputPicosPerToken: 5_000_000, // $5.00 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-terra-high': {
    id: 'gpt-6.1-terra-high',
    catalogId: 'openai/gpt-6.1-terra',
    tier: 'mid',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-terra',
    reasoningEffort: 'high',
    inputPicosPerToken: 1_000_000, // $1.00 / 1M
    cacheReadPicosPerToken: 50_000, // $0.05 / 1M
    cacheWritePicosPerToken: 1_250_000, // $1.25 / 1M
    outputPicosPerToken: 5_000_000, // $5.00 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-sol-low': {
    id: 'gpt-6.1-sol-low',
    catalogId: 'openai/gpt-6.1-sol',
    tier: 'cheap',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-sol',
    reasoningEffort: 'low',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 12288,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-sol-medium': {
    id: 'gpt-6.1-sol-medium',
    catalogId: 'openai/gpt-6.1-sol',
    tier: 'mid',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-sol',
    reasoningEffort: 'medium',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-sol-high': {
    id: 'gpt-6.1-sol-high',
    catalogId: 'openai/gpt-6.1-sol',
    tier: 'mid',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-sol',
    reasoningEffort: 'high',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-sol-xhigh': {
    id: 'gpt-6.1-sol-xhigh',
    catalogId: 'openai/gpt-6.1-sol',
    tier: 'mid',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-sol',
    reasoningEffort: 'xhigh',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 32768,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-6.1-sol-max': {
    id: 'gpt-6.1-sol-max',
    catalogId: 'openai/gpt-6.1-sol',
    tier: 'mid',
    transport: 'openai-responses',
    upstreamModel: 'gpt-6.1-sol',
    reasoningEffort: 'max',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 32768,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'claude-sonnet-5-5': {
    id: 'claude-sonnet-5-5',
    catalogId: 'anthropic/claude-sonnet-5.5',
    tier: 'mid',
    transport: 'anthropic',
    upstreamModel: 'claude-sonnet-5-5',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 16384,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'claude-sonnet-5-5-high': {
    id: 'claude-sonnet-5-5-high',
    catalogId: 'anthropic/claude-sonnet-5.5',
    tier: 'mid',
    transport: 'anthropic',
    upstreamModel: 'claude-sonnet-5-5',
    reasoningEffort: 'high',
    anthropicAdaptiveThinking: true,
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 24576,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
};

export function assertNonFrontierModel(spec: BenchmarkModelSpec): void {
  for (const label of [spec.id, spec.catalogId, spec.upstreamModel]) {
    if (FRONTIER_NAME_PATTERN.test(label)) {
      throw new Error(`Frontier-priced model "${label}" is excluded by issue 351 / CLAUDE.md`);
    }
  }
  const priceFields = [
    ['inputPicosPerToken', spec.inputPicosPerToken],
    ['cacheReadPicosPerToken', spec.cacheReadPicosPerToken],
    ['cacheWritePicosPerToken', spec.cacheWritePicosPerToken],
    ['outputPicosPerToken', spec.outputPicosPerToken],
  ] as const;
  for (const [name, value] of priceFields) {
    if (!Number.isFinite(value) || value < 0) {
      throw new Error(`Model "${spec.id}" ${name} must be a finite non-negative number, got ${value}`);
    }
  }
  if (spec.inputPicosPerToken <= 0 || spec.outputPicosPerToken <= 0) {
    throw new Error(`Model "${spec.id}" inputPicosPerToken and outputPicosPerToken must be > 0`);
  }
  if (
    !Number.isFinite(spec.maxOutputTokens) ||
    !Number.isInteger(spec.maxOutputTokens) ||
    spec.maxOutputTokens < 1 ||
    spec.maxOutputTokens > MAX_OUTPUT_TOKENS_CEILING
  ) {
    throw new Error(
      `Model "${spec.id}" maxOutputTokens must be an integer in [1, ${MAX_OUTPUT_TOKENS_CEILING}], got ${spec.maxOutputTokens}`,
    );
  }
  if (!Number.isFinite(spec.longContextThresholdTokens) || spec.longContextThresholdTokens <= 0) {
    throw new Error(
      `Model "${spec.id}" longContextThresholdTokens must be a positive finite number, got ${spec.longContextThresholdTokens}`,
    );
  }
  if (spec.inputPicosPerToken > MAX_NON_FRONTIER_INPUT_PICOS) {
    throw new Error(
      `Model "${spec.id}" input price ${spec.inputPicosPerToken} picos/token exceeds non-frontier cap (${MAX_NON_FRONTIER_INPUT_PICOS})`,
    );
  }
  if (spec.outputPicosPerToken > MAX_NON_FRONTIER_OUTPUT_PICOS) {
    throw new Error(
      `Model "${spec.id}" output price ${spec.outputPicosPerToken} picos/token exceeds non-frontier cap (${MAX_NON_FRONTIER_OUTPUT_PICOS})`,
    );
  }
}

export function resolveModelSpec(
  id: string,
  overrides: Readonly<Record<string, Partial<BenchmarkModelSpec>>> = {},
): BenchmarkModelSpec {
  const base = PRESET_MODELS[id];
  const patch = overrides[id];
  if (!base && !patch) {
    throw new Error(`Unknown benchmark model "${id}". Available presets: ${Object.keys(PRESET_MODELS).join(', ')}`);
  }
  const reasoningEffort = patch?.reasoningEffort ?? base?.reasoningEffort;
  const anthropicAdaptiveThinking = patch?.anthropicAdaptiveThinking ?? base?.anthropicAdaptiveThinking;
  const merged: BenchmarkModelSpec = {
    id,
    catalogId: patch?.catalogId ?? base?.catalogId ?? id,
    tier: patch?.tier ?? base?.tier ?? 'mid',
    transport: patch?.transport ?? base?.transport ?? 'anthropic',
    upstreamModel: patch?.upstreamModel ?? base?.upstreamModel ?? id,
    ...(reasoningEffort !== undefined ? { reasoningEffort } : {}),
    ...(anthropicAdaptiveThinking !== undefined ? { anthropicAdaptiveThinking } : {}),
    inputPicosPerToken: patch?.inputPicosPerToken ?? base?.inputPicosPerToken ?? 0,
    cacheReadPicosPerToken: patch?.cacheReadPicosPerToken ?? base?.cacheReadPicosPerToken ?? 0,
    cacheWritePicosPerToken: patch?.cacheWritePicosPerToken ?? base?.cacheWritePicosPerToken ?? 0,
    outputPicosPerToken: patch?.outputPicosPerToken ?? base?.outputPicosPerToken ?? 0,
    maxOutputTokens: patch?.maxOutputTokens ?? base?.maxOutputTokens ?? 4096,
    longContextThresholdTokens:
      patch?.longContextThresholdTokens ?? base?.longContextThresholdTokens ?? DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  };
  assertNonFrontierModel(merged);
  return merged;
}

export function parseModelOverride(expr: string): { id: string; patch: Partial<BenchmarkModelSpec> } {
  const eq = expr.indexOf('=');
  if (eq <= 0) {
    throw new Error(`Invalid --model-override "${expr}"; expected id={"inputPicosPerToken":...}`);
  }
  const id = expr.slice(0, eq).trim();
  const patch = JSON.parse(expr.slice(eq + 1)) as Partial<BenchmarkModelSpec>;
  return { id, patch };
}

function sanitizeTokenCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.trunc(value) : 0;
}

export function computeTurnPicos(model: BenchmarkModelSpec, usage: TokenUsageCounts): number {
  const input = sanitizeTokenCount(usage.inputTokens);
  const output = sanitizeTokenCount(usage.outputTokens);
  const cached = sanitizeTokenCount(usage.cachedInputTokens);
  const write = sanitizeTokenCount(usage.cacheWriteTokens);
  return (
    input * model.inputPicosPerToken +
    cached * model.cacheReadPicosPerToken +
    write * model.cacheWritePicosPerToken +
    output * model.outputPicosPerToken
  );
}

export function formatDollars(picos: number): string {
  return (picos / PICOS_PER_DOLLAR).toFixed(4);
}
