import type { Env } from "./http";

const textEncoder = new TextEncoder();

export function isProxyConfigured(env: Env): boolean {
  const hasSalt = typeof env.IP_HASH_SALT === "string" && env.IP_HASH_SALT.trim().length >= 16;
  const hasToken = typeof env.CF_AIG_TOKEN === "string" && env.CF_AIG_TOKEN.trim().length > 0;
  const hasBaseUrl = typeof env.AIG_BASE_URL === "string" && env.AIG_BASE_URL.trim().length > 0;
  const hasGateway =
    typeof env.CF_ACCOUNT_ID === "string" &&
    env.CF_ACCOUNT_ID.trim().length > 0 &&
    typeof env.CF_AIG_GATEWAY_ID === "string" &&
    env.CF_AIG_GATEWAY_ID.trim().length > 0;

  return hasSalt && hasToken && (hasBaseUrl || hasGateway);
}

export function requireCallerIp(request: Request): string | null {
  const raw = request.headers.get("cf-connecting-ip");
  if (!raw) return null;
  const trimmed = raw.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export async function hashCallerIp(ip: string, day: string, salt: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", textEncoder.encode(`${ip}:${day}:${salt}`));
  const bytes = new Uint8Array(digest);
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i]!.toString(16).padStart(2, "0");
  }
  return hex;
}

export async function checkRateLimit(env: Env, key: string): Promise<boolean> {
  if (!env.RATE_LIMITER) return true;
  const { success } = await env.RATE_LIMITER.limit({ key });
  return success;
}
