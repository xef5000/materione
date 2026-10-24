export interface Env {
  DB: D1Database;
  RATE_LIMITER?: RateLimit;
}

export function jsonResponse(body: unknown, status = 200, extraHeaders?: HeadersInit): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", ...extraHeaders },
  });
}

export function errorResponse(status: number, error: string, detail?: string): Response {
  return jsonResponse({ error, ...(detail ? { detail } : {}) }, status);
}

export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
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

export function bearerToken(request: Request): string | null {
  const header = request.headers.get("authorization");
  if (!header || !header.startsWith("Bearer ")) return null;
  const token = header.slice(7).trim();
  return token.length >= 16 ? token : null;
}

/** Per-IP rate limit through the Workers rate-limiting binding (approximate,
 * per-location). Treated as optional: quotas are the hard caps. */
export async function rateLimited(env: Env, request: Request, route: string): Promise<boolean> {
  if (!env.RATE_LIMITER) return false;
  const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
  const { success } = await env.RATE_LIMITER.limit({ key: `${route}:${ip}` });
  return !success;
}
