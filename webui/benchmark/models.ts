export const PICOS_PER_DOLLAR = 1_000_000_000_000;

export type ModelTier = 'cheap' | 'mid';
export type ModelTransport = 'anthropic' | 'compat';

export interface BenchmarkModelSpec {
  readonly id: string;
  readonly catalogId: string;
  readonly tier: ModelTier;
  readonly transport: ModelTransport;
  readonly upstreamModel: string;
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

const FRONTIER_NAME_PATTERN = /\b(opus|o1-pro|o3-pro|gpt-4\.5|gpt-5-pro)\b/i;
export const MAX_NON_FRONTIER_INPUT_PICOS = 5_000_000; // $5.00 / 1M tokens
export const MAX_NON_FRONTIER_OUTPUT_PICOS = 25_000_000; // $25.00 / 1M tokens

export const DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS = 195_000;
export const MAX_OUTPUT_TOKENS_CEILING = 16_384;

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
    maxOutputTokens: 4096,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'claude-sonnet-4-5': {
    id: 'claude-sonnet-4-5',
    catalogId: 'anthropic/claude-sonnet-4-5',
    tier: 'mid',
    transport: 'anthropic',
    upstreamModel: 'claude-sonnet-4-5',
    inputPicosPerToken: 2_000_000, // $2.00 / 1M
    cacheReadPicosPerToken: 100_000, // $0.10 / 1M
    cacheWritePicosPerToken: 2_500_000, // $2.50 / 1M
    outputPicosPerToken: 10_000_000, // $10.00 / 1M
    maxOutputTokens: 4096,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gemini-2.5-flash': {
    id: 'gemini-2.5-flash',
    catalogId: 'google-ai-studio/gemini-2.5-flash',
    tier: 'cheap',
    transport: 'compat',
    upstreamModel: 'google-ai-studio/gemini-2.5-flash',
    inputPicosPerToken: 300_000, // $0.30 / 1M
    cacheReadPicosPerToken: 75_000, // $0.075 / 1M
    cacheWritePicosPerToken: 300_000,
    outputPicosPerToken: 2_500_000, // $2.50 / 1M
    maxOutputTokens: 4096,
    longContextThresholdTokens: DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
  },
  'gpt-5-mini': {
    id: 'gpt-5-mini',
    catalogId: 'openai/gpt-5-mini',
    tier: 'cheap',
    transport: 'compat',
    upstreamModel: 'openai/gpt-5-mini',
    inputPicosPerToken: 250_000, // $0.25 / 1M
    cacheReadPicosPerToken: 25_000, // $0.025 / 1M
    cacheWritePicosPerToken: 250_000,
    outputPicosPerToken: 2_000_000, // $2.00 / 1M
    maxOutputTokens: 4096,
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
  const merged: BenchmarkModelSpec = {
    id,
    catalogId: patch?.catalogId ?? base?.catalogId ?? id,
    tier: patch?.tier ?? base?.tier ?? 'mid',
    transport: patch?.transport ?? base?.transport ?? 'anthropic',
    upstreamModel: patch?.upstreamModel ?? base?.upstreamModel ?? id,
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
