/**
 * The browser side of the wire-protocol drift guard (web.md §9): this spec
 * consumes the SAME golden fixture as the JVM tests
 * (fixtures/protocol/editor-protocol-v1.json) and proves this TypeScript
 * implementation reconstructs keys, hashes transcripts, verifies signatures,
 * opens envelopes and decrypts frames identically.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  aesDecrypt,
  bytesEqual,
  concat,
  decodeBase64Url as b64d,
  ed25519FromRaw,
  ecdhSharedSecret,
  hkdf,
  hmacSha256,
  importAesKey,
  LABEL_KEY_CONFIRM,
  LABEL_SESSION_KEY,
  p256FromScalar,
  sha256,
  sign,
  utf8,
  utf8Decode,
  verify,
} from '../src/lib/protocol/crypto';
import { canonicalJson } from '../src/lib/protocol/wire';
import { openEnvelope } from '../src/lib/protocol/session';

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-protocol-v1.json', import.meta.url), 'utf8'),
) as Record<string, any>;

const raw = (path: string): Uint8Array => {
  const parts = path.split('.');
  let current: unknown = fixture;
  for (const part of parts) {
    current = (current as Record<string, unknown>)[part];
  }
  return b64d(current as string);
};

describe('golden wire-protocol fixture', () => {
  it('reconstructs both Ed25519 identities from raw halves', async () => {
    const plugin = await ed25519FromRaw(raw('ed25519.pluginSeed'), raw('ed25519.pluginPub'));
    const message = utf8.encode('probe');
    const signature = await sign(plugin.privateKey, message);
    expect(await verify(plugin.publicKey, signature, message)).toBe(true);
    // Tampering is caught.
    signature[0]! ^= 0x01;
    expect(await verify(plugin.publicKey, signature, message)).toBe(false);
  });

  it('reproduces transcript hash, session key and key confirmation', async () => {
    const hello = fixture.handshake.hello;
    const replyNoConfirm = {
      type: fixture.handshake['hello-reply'].type,
      ecdhPub: fixture.handshake['hello-reply'].ecdhPub,
    };
    const transcriptHash = await sha256(
      concat(utf8.encode(canonicalJson(hello)), utf8.encode(canonicalJson(replyNoConfirm))),
    );
    expect(bytesEqual(transcriptHash, raw('handshake.transcriptHash')), 'transcriptHash').toBe(true);

    const editorPrivate = await p256FromScalar(
      raw('p256.editorScalar'),
      raw('p256.editorEphemeralPub'),
    );
    // Browser side: our private × the plugin's ephemeral point from the reply.
    const sharedSecret = await ecdhSharedSecret(
      editorPrivate,
      raw('handshake.hello-reply.ecdhPub'),
    );
    expect(bytesEqual(sharedSecret, raw('p256.sharedSecret')), 'sharedSecret').toBe(true);

    const sessionKey = await hkdf(sharedSecret, transcriptHash, LABEL_SESSION_KEY, 32);
    expect(bytesEqual(sessionKey, raw('handshake.sessionKey'))).toBe(true);

    const confirmKey = await hkdf(sharedSecret, transcriptHash, LABEL_KEY_CONFIRM, 32);
    expect(
      bytesEqual(await hmacSha256(confirmKey, transcriptHash), raw('handshake.confirmation')),
    ).toBe(true);
  });

  it('opens the wrapped session blob with the wrapping key alone', async () => {
    const opened = await openEnvelope(
      raw('sessionBlob.wrappingKey'),
      fixture.handshake.hello.sessionId,
      fixture.sessionBlob.envelope,
    );
    expect(opened.type).toBe('session-blob');
    const blob = JSON.parse(utf8Decode(opened.body));
    expect(blob.sessionId).toBe(fixture.handshake.hello.sessionId);
    expect(blob.protocolVersions).toContain(1);

    const wrong = new Uint8Array(32);
    await expect(
      openEnvelope(wrong, blob.sessionId, fixture.sessionBlob.envelope),
    ).rejects.toThrow(/authentication|fragment/);
  });

  it('verifies and decrypts both plugin→editor frames in order', async () => {
    const plugin = await ed25519FromRaw(raw('ed25519.pluginSeed'), raw('ed25519.pluginPub'));
    const aes = await importAesKey(raw('handshake.sessionKey'));

    let expectedSeq = 0;
    for (const index of ['frame0', 'frame1'] as const) {
      const frame = fixture.frames[index];
      expect(frame.seq).toBe(expectedSeq++);
      const signedText = `${frame.seq}.${frame.nonce}.${frame.ct}`;
      expect(await verify(plugin.publicKey, b64d(frame.sig), utf8.encode(signedText))).toBe(true);
      const plaintext = utf8Decode(
        await aesDecrypt(aes, b64d(frame.nonce), b64d(frame.ct)),
      );
      expect(canonicalJson(JSON.parse(plaintext))).toBe(canonicalJson(fixture.frames[`body${index.slice(-1)}`]));
    }
  });
});
