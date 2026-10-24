/**
 * Capability strings (web.md §5.3): channel ids, party tokens, store codes,
 * delete tokens — all drawn from a ≥128-bit CSPRNG space, base64url-encoded.
 * The server persists only sha256(token) hex.
 */

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

export function randomToken(bytes = 32): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return encodeBase64Url(buf);
}

export function randomCode(bytes = 18): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  let out = "";
  for (const b of buf) {
    out += ALPHABET[b % 64];
  }
  return out;
}

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  const bytes = new Uint8Array(digest);
  let hex = "";
  for (const b of bytes) hex += b.toString(16).padStart(2, "0");
  return hex;
}

/** Charset shared by client-computed content-addressed codes. */
export function isPlausibleCode(code: string): boolean {
  return /^[A-Za-z0-9_-]{16,128}$/.test(code);
}
