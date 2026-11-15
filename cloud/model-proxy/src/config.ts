import type { Env } from "./http";

export const ALLOWED_ORIGIN = "https://editor.materione.com";

export const MAX_BODY_BYTES = 512 * 1024; // 524,288 bytes (512 KiB)
export const MAX_INPUT_TOKENS = 80_000;
export const MAX_OUTPUT_TOKENS = 4_000;
export const MAX_MESSAGES = 64;

export const RATE_LIMIT_RETRY_SECONDS = 60;
export const RETENTION_DAYS = 2;
export const RESERVATION_RETENTION_DAYS = 30;

export const PICO_PER_USD = 1_000_000_000_000;
export const PICOS_PER_CREDIT = 7_500_000_000; // 1 credit = $0.0075 wholesale; 100 credits = $1 retail
export const MIN_CHECKOUT_CENTS = 1_000; // $10.00
export const MAX_CHECKOUT_CENTS = 20_000; // $200.00

export const DISCORD_MIN_FREE_AGE_MS = 7 * 24 * 60 * 60 * 1_000;
export const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
export const SESSION_TOUCH_INTERVAL_SECONDS = 900;
export const OAUTH_STATE_TTL_SECONDS = 600;

export const WEBHOOK_MAX_BODY_BYTES = 256 * 1024;
export const WEBHOOK_TOLERANCE_SECONDS = 300;

export const DEFAULT_PUBLIC_BASE_URL = "https://ai.materione.com";
export const DEFAULT_POLAR_API_BASE = "https://api.polar.sh";
export const SANDBOX_POLAR_API_BASE = "https://sandbox-api.polar.sh";

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

export function resolvePublicBaseUrl(env: Env): string | null {
  const raw = env.PUBLIC_BASE_URL?.trim();
  if (!raw) return DEFAULT_PUBLIC_BASE_URL;
  try {
    const parsed = new URL(raw);
    const allowHttp = env.ALLOW_TEST_UPSTREAM_OVERRIDE === "true";
    if (parsed.protocol !== "https:" && !(allowHttp && parsed.protocol === "http:")) {
      return null;
    }
    return parsed.origin;
  } catch {
    return null;
  }
}

export function resolvePolarApiBase(env: Env): string | null {
  const raw = env.POLAR_API_BASE?.trim();
  if (!raw) return DEFAULT_POLAR_API_BASE;
  const normalized = raw.replace(/\/+$/, "");
  const allowOverride = env.ALLOW_TEST_UPSTREAM_OVERRIDE === "true";
  if (!allowOverride && normalized !== SANDBOX_POLAR_API_BASE) {
    return null;
  }
  try {
    const parsed = new URL(normalized);
    if (parsed.protocol !== "https:" && !(allowOverride && parsed.protocol === "http:")) {
      return null;
    }
    return normalized;
  } catch {
    return null;
  }
}
