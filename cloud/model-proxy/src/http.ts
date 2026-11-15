import { ALLOWED_ORIGIN } from "./config";

export interface Env {
  DB: D1Database;
  RATE_LIMITER?: RateLimit;
  CF_ACCOUNT_ID?: string;
  CF_AIG_GATEWAY_ID?: string;
  CF_AIG_TOKEN?: string;
  IP_HASH_SALT?: string;
  AIG_BASE_URL?: string;
  CALLER_DAILY_CAP_PICOS?: string;
  GLOBAL_DAILY_POOL_PICOS?: string;
}

export const CORS_HEADERS: Record<string, string> = {
  "access-control-allow-origin": ALLOWED_ORIGIN,
  vary: "Origin",
};

export function checkOrigin(request: Request): Response | null {
  const origin = request.headers.get("origin");
  if (origin !== ALLOWED_ORIGIN) {
    return new Response(
      JSON.stringify({
        error: "bad_origin",
        code: "bad_origin",
        message: `Requests are accepted only from ${ALLOWED_ORIGIN}.`,
      }),
      {
        status: 403,
        headers: {
          "content-type": "application/json",
          "cache-control": "no-store",
        },
      },
    );
  }
  return null;
}

export function preflightResponse(): Response {
  return new Response(null, {
    status: 204,
    headers: {
      "access-control-allow-origin": ALLOWED_ORIGIN,
      "access-control-allow-methods": "GET, POST, OPTIONS",
      "access-control-allow-headers": "content-type",
      "access-control-max-age": "86400",
      vary: "Origin",
      "cache-control": "no-store",
    },
  });
}

export function jsonResponse(body: unknown, status = 200, extraHeaders?: HeadersInit): Response {
  const headers = new Headers(extraHeaders);
  headers.set("content-type", "application/json");
  headers.set("cache-control", "no-store");
  headers.set("access-control-allow-origin", ALLOWED_ORIGIN);
  headers.set("vary", "Origin");
  return new Response(JSON.stringify(body), { status, headers });
}

export function errorResponse(
  status: number,
  error: string,
  message: string,
  extra?: Record<string, unknown>,
  extraHeaders?: HeadersInit,
): Response {
  return jsonResponse(
    {
      error,
      code: typeof extra?.code === "string" ? extra.code : error,
      message,
      ...extra,
    },
    status,
    extraHeaders,
  );
}

export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

export function utcDay(date = new Date()): string {
  return date.toISOString().slice(0, 10);
}

export function cutoffUtcDay(daysBack: number, now = new Date()): string {
  const cutoff = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - daysBack));
  return cutoff.toISOString().slice(0, 10);
}

export function secondsUntilUtcMidnight(): number {
  const now = Date.now();
  const midnight = Date.UTC(
    new Date(now).getUTCFullYear(),
    new Date(now).getUTCMonth(),
    new Date(now).getUTCDate() + 1,
  );
  return Math.max(1, Math.floor((midnight - now) / 1000));
}
