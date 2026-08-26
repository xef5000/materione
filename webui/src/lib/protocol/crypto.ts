/**
 * WebCrypto wrappers for the protocol's pinned schemes (web.md §5): Ed25519
 * signatures, P-256 ECDH, AES-256-GCM, HKDF-SHA256. Requires a secure
 * context — http://127.0.0.1 qualifies; remote plain-HTTP does not.
 *
 * Requires Ed25519 in WebCrypto (Chrome 133+, Safari 17+, Firefox 129+).
 */

const ED25519 = 'Ed25519';
const P256 = { name: 'ECDH', namedCurve: 'P-256' } as const;
const AES = { name: 'AES-GCM' } as const;

// Re-exported so protocol consumers need only this module.
export { canonicalJson, decodeBase64Url, encodeBase64Url } from './wire';
export { utf8, utf8Decode } from './wire';
import { encodeBase64Url } from './wire';

export function subtle(): SubtleCrypto {
  if (!globalThis.crypto?.subtle) {
    throw new Error('WebCrypto unavailable — open the editor over localhost or HTTPS');
  }
  return globalThis.crypto.subtle;
}

export function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

// ---------------------------------------------------------------- Ed25519

export async function generateSigningPair(): Promise<CryptoKeyPair> {
  return subtle().generateKey(ED25519, true, ['sign', 'verify']) as Promise<CryptoKeyPair>;
}

export async function importEd25519Public(raw: Uint8Array): Promise<CryptoKey> {
  return subtle().importKey('raw', raw as BufferSource, ED25519, true, ['verify']);
}

export async function sign(privateKey: CryptoKey, data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle().sign(ED25519, privateKey, data as BufferSource));
}

export async function verify(
  publicKey: CryptoKey,
  signature: Uint8Array,
  data: Uint8Array,
): Promise<boolean> {
  try {
    return await subtle().verify(ED25519, publicKey, signature as BufferSource, data as BufferSource);
  } catch {
    return false;
  }
}

/** Reconstructs an Ed25519 keypair from raw halves — golden fixtures and tests only. */
export async function ed25519FromRaw(seed: Uint8Array, rawPublic: Uint8Array): Promise<CryptoKeyPair> {
  const pkcs8 = concat(new Uint8Array([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]), seed);
  const spki = concat(new Uint8Array([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00]), rawPublic);
  const privateKey = await subtle().importKey('pkcs8', pkcs8 as BufferSource, ED25519, true, ['sign']);
  const publicKey = await subtle().importKey('spki', spki as BufferSource, ED25519, true, ['verify']);
  return { privateKey, publicKey };
}

// ---------------------------------------------------------------- P-256 ECDH

export async function generateEphemeralPair(): Promise<CryptoKeyPair> {
  const pair = await subtle().generateKey(P256, true, ['deriveBits']) as CryptoKeyPair;
  return { privateKey: pair.privateKey, publicKey: pair.publicKey };
}

export async function exportP256Public(publicKey: CryptoKey): Promise<Uint8Array> {
  return new Uint8Array(await subtle().exportKey('raw', publicKey));
}

export async function importP256Public(raw: Uint8Array): Promise<CryptoKey> {
  return subtle().importKey('raw', raw as BufferSource, P256, true, []);
}

export async function ecdhSharedSecret(
  privateKey: CryptoKey,
  peerPublic: Uint8Array,
): Promise<Uint8Array> {
  const peer = await importP256Public(peerPublic);
  return new Uint8Array(
    await subtle().deriveBits({ name: 'ECDH', public: peer }, privateKey, 256),
  );
}

/**
 * Reconstructs a P-256 ephemeral from its raw scalar + public point —
 * golden fixtures and tests only. JWK import does the curve validation;
 * hand-built PKCS#8 DER proved fragile across OpenSSL versions.
 */
export async function p256FromScalar(
  scalar: Uint8Array,
  rawPublic: Uint8Array,
): Promise<CryptoKey> {
  if (rawPublic.length !== 65 || rawPublic[0] !== 0x04) {
    throw new Error('expected a 65-byte uncompressed P-256 point');
  }
  const jwk = {
    kty: 'EC',
    crv: 'P-256',
    d: encodeBase64Url(scalar),
    x: encodeBase64Url(rawPublic.slice(1, 33)),
    y: encodeBase64Url(rawPublic.slice(33, 65)),
  };
  return subtle().importKey('jwk', jwk, P256, true, ['deriveBits']);
}

// ---------------------------------------------------------------- HKDF-SHA256

async function hmac(keyBytes: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const key = await subtle().importKey(
    'raw',
    keyBytes as BufferSource,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  return new Uint8Array(await subtle().sign('HMAC', key, data as BufferSource));
}

export async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle().digest('SHA-256', data as BufferSource));
}

/** HKDF extract+expand (RFC 5869) over HMAC-SHA256. */
export async function hkdf(
  ikm: Uint8Array,
  salt: Uint8Array,
  info: Uint8Array,
  length: number,
): Promise<Uint8Array> {
  let prk = await hmac(salt.length ? salt : new Uint8Array(32), ikm);
  const blocks: Uint8Array[] = [];
  let previous = new Uint8Array(0);
  let counter = 1;
  let generated = 0;
  while (generated < length) {
    previous = await hmac(prk, concat(concat(previous, info), new Uint8Array([counter])));
    blocks.push(previous);
    generated += previous.length;
    counter++;
  }
  return concat(...blocks).slice(0, length);
}

export const LABEL_SESSION_KEY = utf8Of('MATERIONE-EDITOR-v1/session-key');
export const LABEL_KEY_CONFIRM = utf8Of('MATERIONE-EDITOR-v1/key-confirm');

function utf8Of(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

export async function hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  return hmac(key, data);
}

// ---------------------------------------------------------------- AES-GCM

export async function importAesKey(raw: Uint8Array): Promise<CryptoKey> {
  return subtle().importKey('raw', raw as BufferSource, AES, true, ['encrypt', 'decrypt']);
}

export async function aesEncrypt(
  key: CryptoKey,
  nonce12: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  return new Uint8Array(
    await subtle().encrypt({ name: 'AES-GCM', iv: nonce12 as BufferSource, tagLength: 128 }, key, plaintext as BufferSource),
  );
}

export async function aesDecrypt(
  key: CryptoKey,
  nonce12: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  return new Uint8Array(
    await subtle().decrypt({ name: 'AES-GCM', iv: nonce12 as BufferSource, tagLength: 128 }, key, ciphertext as BufferSource),
  );
}

// ---------------------------------------------------------------- helpers

export function concat(...arrays: Uint8Array[]): Uint8Array {
  const total = arrays.reduce((sum, array) => sum + array.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const array of arrays) {
    out.set(array, offset);
    offset += array.length;
  }
  return out;
}

export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a[i]! ^ b[i]!;
  }
  return diff === 0;
}
