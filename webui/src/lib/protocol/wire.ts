/**
 * Canonical JSON + base64url — the wire encoding both runtimes must agree on
 * byte for byte (web.md §4.2/§5.4; mirrors dev.materione.web.protocol.Wire).
 *
 * Rules: UTF-8, no whitespace, object keys sorted lexically (recursively),
 * strings escaped exactly as JSON.stringify escapes them, no floats, binary
 * values carried as unpadded base64url.
 */

export function canonicalJson(value: unknown): string {
  return writeCanonical(sort(value));
}

function sort(value: unknown): unknown {
  if (value === null || typeof value !== 'object') {
    if (typeof value === 'number' && !Number.isInteger(value)) {
      throw new Error('the wire carries no floats: ' + value);
    }
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(sort);
  }
  const out: Record<string, unknown> = {};
  for (const key of Object.keys(value as Record<string, unknown>).sort()) {
    out[key] = sort((value as Record<string, unknown>)[key]);
  }
  return out;
}

function writeCanonical(value: unknown): string {
  if (value === null || value === undefined) {
    return 'null';
  }
  switch (typeof value) {
    case 'boolean':
    case 'number':
      return String(value);
    case 'string':
      return JSON.stringify(value);
    default:
      break;
  }
  if (Array.isArray(value)) {
    return '[' + value.map(writeCanonical).join(',') + ']';
  }
  const entries = Object.entries(value as Record<string, unknown>);
  return '{' + entries.map(([k, v]) => JSON.stringify(k) + ':' + writeCanonical(v)).join(',') + '}';
}

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeBase64Url(text: string): Uint8Array {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export const utf8 = new TextEncoder();
export function utf8Decode(bytes: Uint8Array): string {
  return new TextDecoder().decode(bytes);
}

/** The RPC shapes ({op,id,payload} / {op,id,ok,data|error}) — typed mirrors of Wire.java. */
export interface Request {
  op: string;
  id: string;
  payload: Record<string, unknown>;
}

export interface WireError {
  code: string;
  message: string;
  resource?: string | null;
  hint?: string | null;
}

export type Response =
  | { op: string; id: string; ok: true; data: unknown }
  | { op: string; id: string; ok: false; error: WireError };
