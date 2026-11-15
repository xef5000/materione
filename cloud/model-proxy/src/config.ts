import type { Env } from "./http";

export const ALLOWED_ORIGIN = "https://editor.materione.com";

export const MAX_BODY_BYTES = 512 * 1024; // 524,288 bytes (512 KiB)
export const MAX_INPUT_TOKENS = 80_000;
export const MAX_OUTPUT_TOKENS = 4_000;
export const MAX_MESSAGES = 64;

export const RATE_LIMIT_RETRY_SECONDS = 60;
export const RETENTION_DAYS = 2;

export const PICO_PER_USD = 1_000_000_000_000;
export const DEFAULT_CALLER_DAILY_CAP_PICOS = 50_000_000_000; // $0.05 / day
export const DEFAULT_GLOBAL_DAILY_POOL_PICOS = 1_000_000_000_000; // $1.00 / day
export const MAX_CONFIGURABLE_CALLER_CAP_PICOS = 250_000_000_000; // $0.25 / day
export const MAX_CONFIGURABLE_GLOBAL_POOL_PICOS = 10_000_000_000_000; // $10.00 / day

export interface AllowanceCaps {
  callerDailyCapPicos: number;
  globalDailyPoolPicos: number;
}

function parsePositiveInt(raw: string | undefined, fallback: number): number {
  if (!raw) return fallback;
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) return fallback;
  const parsed = Number(trimmed);
  if (!Number.isSafeInteger(parsed) || parsed < 0) return fallback;
  return parsed;
}

export function resolveCaps(env: Env): AllowanceCaps {
  return {
    callerDailyCapPicos: Math.min(
      parsePositiveInt(env.CALLER_DAILY_CAP_PICOS, DEFAULT_CALLER_DAILY_CAP_PICOS),
      MAX_CONFIGURABLE_CALLER_CAP_PICOS,
    ),
    globalDailyPoolPicos: Math.min(
      parsePositiveInt(env.GLOBAL_DAILY_POOL_PICOS, DEFAULT_GLOBAL_DAILY_POOL_PICOS),
      MAX_CONFIGURABLE_GLOBAL_POOL_PICOS,
    ),
  };
}
