import type { ModelRefusal } from '../src/lib/codemode/chat/provider';
import { computeTurnPicos, formatDollars, PICOS_PER_DOLLAR, type BenchmarkModelSpec, type TokenUsageCounts } from './models';

export const MAX_BENCHMARK_DOLLARS = 50;

export interface ModelLedgerStats {
  turns: number;
  inputTokens: number;
  cachedInputTokens: number;
  cacheWriteTokens: number;
  outputTokens: number;
  spentPicos: number;
}

export class BudgetLedger {
  readonly maxDollars: number;
  readonly capPicos: number;
  #spentPicos = 0;
  readonly #byModel = new Map<string, ModelLedgerStats>();

  constructor(maxDollars: number, options: { allowZeroForMock?: boolean } = {}) {
    if (!Number.isFinite(maxDollars)) {
      throw new Error(`Invalid --max-dollars: ${maxDollars}`);
    }
    if (options.allowZeroForMock && maxDollars === 0) {
      this.maxDollars = 0;
      this.capPicos = 0;
      return;
    }
    if (maxDollars <= 0 || maxDollars > MAX_BENCHMARK_DOLLARS) {
      throw new Error(`--max-dollars must be in (0, ${MAX_BENCHMARK_DOLLARS}], got ${maxDollars}`);
    }
    this.maxDollars = maxDollars;
    this.capPicos = Math.round(maxDollars * PICOS_PER_DOLLAR);
  }

  get spentPicos(): number {
    return this.#spentPicos;
  }

  get remainingPicos(): number {
    return Math.max(0, this.capPicos - this.#spentPicos);
  }

  statsFor(modelId: string): ModelLedgerStats {
    return (
      this.#byModel.get(modelId) ?? {
        turns: 0,
        inputTokens: 0,
        cachedInputTokens: 0,
        cacheWriteTokens: 0,
        outputTokens: 0,
        spentPicos: 0,
      }
    );
  }

  estimateWorstCaseTurnPicos(model: BenchmarkModelSpec, upperBoundInputTokens: number): number {
    const boundedInput = Math.max(0, Math.ceil(upperBoundInputTokens));
    const worstInputRate = Math.max(model.inputPicosPerToken, model.cacheWritePicosPerToken);
    return boundedInput * worstInputRate + model.maxOutputTokens * model.outputPicosPerToken;
  }

  canAffordTurn(
    model: BenchmarkModelSpec,
    upperBoundInputTokens: number,
  ): { ok: true; worstCasePicos: number } | { ok: false; refusal: ModelRefusal } {
    const worstCasePicos = this.estimateWorstCaseTurnPicos(model, upperBoundInputTokens);
    if (this.#spentPicos + worstCasePicos > this.capPicos) {
      return {
        ok: false,
        refusal: {
          code: 'budget_cap_reached',
          message: `Hard budget cap $${formatDollars(this.capPicos)} reached (spent $${formatDollars(this.#spentPicos)}, worst-case next turn $${formatDollars(worstCasePicos)}).`,
        },
      };
    }
    return { ok: true, worstCasePicos };
  }

  settleTurn(model: BenchmarkModelSpec, worstCasePicos: number, actual?: Partial<TokenUsageCounts>): number {
    const clean = (v: unknown): number | undefined =>
      typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.trunc(v) : undefined;
    const inputTokens = clean(actual?.inputTokens);
    const outputTokens = clean(actual?.outputTokens);
    const cachedInputTokens = clean(actual?.cachedInputTokens);
    const cacheWriteTokens = clean(actual?.cacheWriteTokens);
    const hasUsage =
      actual !== undefined &&
      (inputTokens !== undefined ||
        outputTokens !== undefined ||
        cachedInputTokens !== undefined ||
        cacheWriteTokens !== undefined);
    const usage: TokenUsageCounts = {
      inputTokens: inputTokens ?? 0,
      outputTokens: outputTokens ?? 0,
      cachedInputTokens: cachedInputTokens ?? 0,
      cacheWriteTokens: cacheWriteTokens ?? 0,
    };
    const turnPicos = hasUsage ? computeTurnPicos(model, usage) : worstCasePicos;
    this.#spentPicos += turnPicos;

    const prev = this.statsFor(model.id);
    this.#byModel.set(model.id, {
      turns: prev.turns + 1,
      inputTokens: prev.inputTokens + usage.inputTokens,
      cachedInputTokens: prev.cachedInputTokens + usage.cachedInputTokens,
      cacheWriteTokens: prev.cacheWriteTokens + usage.cacheWriteTokens,
      outputTokens: prev.outputTokens + usage.outputTokens,
      spentPicos: prev.spentPicos + turnPicos,
    });
    return turnPicos;
  }
}
