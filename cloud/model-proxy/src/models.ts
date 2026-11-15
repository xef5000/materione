import { MAX_INPUT_TOKENS } from "./config";

export interface ModelSpec {
  readonly id: string;
  readonly catalogId: string;
  readonly inputPicosPerToken: number;
  readonly cacheReadPicosPerToken: number;
  readonly cacheWritePicosPerToken: number;
  readonly outputPicosPerToken: number;
  readonly longContextThresholdTokens: number;
}

export interface TokenUsage {
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly cacheReadTokens: number;
  readonly cacheWriteTokens: number;
}

export const DEFAULT_MODEL_ID = "claude-haiku-5-5";

export const ALLOWLIST: Readonly<Record<string, ModelSpec>> = {
  "claude-haiku-5-5": {
    id: "claude-haiku-5-5",
    catalogId: "anthropic/claude-haiku-5.5",
    inputPicosPerToken: 100_000, // $0.10 / 1M short-context input
    cacheReadPicosPerToken: 10_000, // $0.01 / 1M short-context cached input
    cacheWritePicosPerToken: 125_000, // $0.125 / 1M short-context cache creation
    outputPicosPerToken: 500_000, // $0.50 / 1M short-context output
    longContextThresholdTokens: 200_000,
  },
};

export function getDefaultModel(): ModelSpec {
  const model = ALLOWLIST[DEFAULT_MODEL_ID];
  if (!model) {
    throw new Error(`Default model ${DEFAULT_MODEL_ID} is not in ALLOWLIST`);
  }
  return model;
}

export function computeWorstCaseReservationPicos(
  model: ModelSpec,
  upperBoundInputTokens: number,
  maxOutputTokens: number,
): number {
  const boundedInputTokens = Math.min(MAX_INPUT_TOKENS, Math.max(0, Math.ceil(upperBoundInputTokens)));
  const worstCaseInputPrice = Math.max(model.inputPicosPerToken, model.cacheWritePicosPerToken);
  return boundedInputTokens * worstCaseInputPrice + maxOutputTokens * model.outputPicosPerToken;
}

export function computeSettledPicos(model: ModelSpec, usage: TokenUsage): number {
  const input = Math.max(0, Math.trunc(usage.inputTokens));
  const output = Math.max(0, Math.trunc(usage.outputTokens));
  const cacheRead = Math.max(0, Math.trunc(usage.cacheReadTokens));
  const cacheWrite = Math.max(0, Math.trunc(usage.cacheWriteTokens));

  return (
    input * model.inputPicosPerToken +
    cacheRead * model.cacheReadPicosPerToken +
    cacheWrite * model.cacheWritePicosPerToken +
    output * model.outputPicosPerToken
  );
}
