import type { Env } from "./http";

const textEncoder = new TextEncoder();
const GATEWAY_ID_REGEX = /^[a-zA-Z0-9_-]{1,100}$/;

export type RateLimitOutcome = "allowed" | "limited" | "unavailable";

export function isProxyConfigured(env: Env): boolean {
  const hasRateLimiter = Boolean(env.RATE_LIMITER && typeof env.RATE_LIMITER.limit === "function");
  const hasSalt = typeof env.IP_HASH_SALT === "string" && env.IP_HASH_SALT.trim().length >= 16;
  const hasToken = typeof env.CF_AIG_TOKEN === "string" && env.CF_AIG_TOKEN.trim().length > 0;
  const hasBaseUrl =
    env.ALLOW_TEST_UPSTREAM_OVERRIDE === "true" &&
    typeof env.AIG_BASE_URL === "string" &&
    env.AIG_BASE_URL.trim().length > 0;
  const hasGateway =
    typeof env.CF_ACCOUNT_ID === "string" &&
    GATEWAY_ID_REGEX.test(env.CF_ACCOUNT_ID.trim()) &&
    typeof env.CF_AIG_GATEWAY_ID === "string" &&
    GATEWAY_ID_REGEX.test(env.CF_AIG_GATEWAY_ID.trim());

  return hasRateLimiter && hasSalt && hasToken && (hasBaseUrl || hasGateway);
}

function parseStrictIpv4(input: string): [number, number, number, number] | null {
  const parts = input.split(".");
  if (parts.length !== 4) return null;
  const octets: number[] = [];
  for (const part of parts) {
    if (!/^(?:0|[1-9]\d{0,2})$/.test(part)) return null;
    const n = Number(part);
    if (n < 0 || n > 255) return null;
    octets.push(n);
  }
  return [octets[0]!, octets[1]!, octets[2]!, octets[3]!];
}

function parseHextetGroup(group: string, allowTrailingIpv4: boolean): number[] | null {
  if (group === "") return [];
  const parts = group.split(":");
  const out: number[] = [];
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]!;
    if (allowTrailingIpv4 && i === parts.length - 1 && part.includes(".")) {
      const ipv4 = parseStrictIpv4(part);
      if (!ipv4) return null;
      out.push((ipv4[0] << 8) | ipv4[1], (ipv4[2] << 8) | ipv4[3]);
      continue;
    }
    if (!/^[0-9a-fA-F]{1,4}$/.test(part)) return null;
    out.push(parseInt(part, 16));
  }
  return out;
}

function parseStrictIpv6(input: string): number[] | null {
  if (!/^[0-9a-fA-F:.]+$/.test(input)) return null;
  const doubleColonParts = input.split("::");
  if (doubleColonParts.length > 2) return null;

  if (doubleColonParts.length === 2) {
    const left = parseHextetGroup(doubleColonParts[0]!, false);
    const right = parseHextetGroup(doubleColonParts[1]!, true);
    if (!left || !right) return null;
    const missing = 8 - (left.length + right.length);
    if (missing < 1) return null;
    return [...left, ...Array<number>(missing).fill(0), ...right];
  }

  const hextets = parseHextetGroup(input, true);
  if (!hextets || hextets.length !== 8) return null;
  return hextets;
}

export function canonicalizeCallerNetwork(rawIp: string): string | null {
  if (typeof rawIp !== "string" || rawIp.length === 0) return null;
  if (/[\s\x00-\x1f\x7f%,[\]]/.test(rawIp)) return null;

  const ipv4 = parseStrictIpv4(rawIp);
  if (ipv4) {
    return `ipv4:${ipv4[0]}.${ipv4[1]}.${ipv4[2]}.${ipv4[3]}`;
  }

  const hextets = parseStrictIpv6(rawIp);
  if (!hextets) return null;

  const isIpv4Mapped =
    hextets[0] === 0 &&
    hextets[1] === 0 &&
    hextets[2] === 0 &&
    hextets[3] === 0 &&
    hextets[4] === 0 &&
    hextets[5] === 0xffff;
  if (isIpv4Mapped) {
    const w = (hextets[6]! >> 8) & 0xff;
    const x = hextets[6]! & 0xff;
    const y = (hextets[7]! >> 8) & 0xff;
    const z = hextets[7]! & 0xff;
    return `ipv4:${w}.${x}.${y}.${z}`;
  }

  // RFC 3056 6to4 (2002:wxyz:wxyz::/48) embeds a /32 IPv4 address in bits 16..47;
  // normalize to ipv4 so a single IPv4 cannot rotate across 2^16 /64 subnets.
  if (hextets[0] === 0x2002) {
    const w = (hextets[1]! >> 8) & 0xff;
    const x = hextets[1]! & 0xff;
    const y = (hextets[2]! >> 8) & 0xff;
    const z = hextets[2]! & 0xff;
    return `ipv4:${w}.${x}.${y}.${z}`;
  }

  const prefix = hextets
    .slice(0, 4)
    .map((h) => h.toString(16).padStart(4, "0"))
    .join(":");
  return `ipv6:${prefix}::/64`;
}

export function requireCallerIp(request: Request): string | null {
  const raw = request.headers.get("cf-connecting-ip");
  if (!raw) return null;
  return canonicalizeCallerNetwork(raw.trim());
}

export async function hashCallerIp(canonicalNetwork: string, day: string, salt: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    textEncoder.encode(`${canonicalNetwork}:${day}:${salt}`),
  );
  const bytes = new Uint8Array(digest);
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i]!.toString(16).padStart(2, "0");
  }
  return hex;
}

export async function checkRateLimit(env: Env, key: string): Promise<RateLimitOutcome> {
  if (!env.RATE_LIMITER || typeof env.RATE_LIMITER.limit !== "function") {
    return "unavailable";
  }
  try {
    const { success } = await env.RATE_LIMITER.limit({ key });
    return success ? "allowed" : "limited";
  } catch {
    return "unavailable";
  }
}
