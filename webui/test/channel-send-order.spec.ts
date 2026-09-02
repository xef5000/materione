// Regression pin for the MTR-0033 quick-double-click session kill
// (web.md §5 strict sequence order). The browser's send side seals frames
// asynchronously (WebCrypto) but the receiver demands strict sequence
// order: concurrent RPCs must take their sequence numbers in exactly the
// order frames hit the wire. Before the send-chain fix, concurrent
// sealAndSend calls raced between `sendSeq++` and the awaited sign(), so
// a later frame could reach the server first — "expected sequence 10, got
// 11 — dropping editor session", and the site had to be re-opened.
//
// Drives a full handshake against the golden fixture's plugin identity
// (the one Ed25519 private the browser tests may hold) with an in-memory
// transport, fires N concurrent calls, then plays the strict server.
import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  aesDecrypt,
  aesEncrypt,
  concat,
  decodeBase64Url as b64d,
  ed25519FromRaw,
  ecdhSharedSecret,
  encodeBase64Url as b64e,
  generateEphemeralPair,
  hkdf,
  hmacSha256,
  importAesKey,
  LABEL_KEY_CONFIRM,
  LABEL_SESSION_KEY,
  randomBytes,
  sha256,
  sign,
  subtle,
  utf8,
  utf8Decode,
} from '../src/lib/protocol/crypto';
import { canonicalJson } from '../src/lib/protocol/wire';
import { EditorChannel, type Transport } from '../src/lib/protocol/session';

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-protocol-v1.json', import.meta.url), 'utf8'),
) as Record<string, any>;

interface ServerState {
  sessionKey: CryptoKey;
  nextSendSeq: number;
}

/** In-memory transport whose send() side plays the plugin (server) role. */
class FakeServerTransport implements Transport {
  readonly sent: string[] = [];
  private handler: ((text: string) => void) | null = null;
  private serverState: ServerState | null = null;

  constructor(private readonly pluginPriv: CryptoKey) {}

  send(text: string): void {
    this.sent.push(text);
    if (text.includes('"type":"hello"') && !this.serverState) {
      void this.handshake(text);
    }
  }

  onMessage(handler: ((text: string) => void) | null): void {
    this.handler = handler;
  }

  onClose(): void {}

  close(): void {}

  /** Delivers one text to the channel (browser side). */
  feed(text: string): void {
    this.handler?.(text);
  }

  state(): ServerState {
    if (!this.serverState) {
      throw new Error('handshake not finished');
    }
    return this.serverState;
  }

  private async handshake(helloText: string): Promise<void> {
    const hello = JSON.parse(helloText) as Record<string, string>;
    const serverEphemeral = await generateEphemeralPair();
    const sharedSecret = await ecdhSharedSecret(
      serverEphemeral.privateKey,
      b64d(hello.editorEphemeralPubKey!),
    );
    const replyNoConfirm = {
      type: 'hello-reply',
      ecdhPub: b64e(
        new Uint8Array((await subtle().exportKey('raw', serverEphemeral.publicKey)) as Uint8Array),
      ),
    };
    const transcriptHash = await sha256(
      concat(utf8.encode(canonicalJson(hello)), utf8.encode(canonicalJson(replyNoConfirm))),
    );
    const confirm = await hmacSha256(
      await hkdf(sharedSecret, transcriptHash, LABEL_KEY_CONFIRM, 32),
      transcriptHash,
    );
    const reply = { ...replyNoConfirm, confirm: b64e(confirm) };
    const signature = await sign(this.pluginPriv, utf8.encode(canonicalJson(reply)));
    // the real open() awaits key imports between hello and reply — yield
    // a few ticks so the timing is comparable
    for (let i = 0; i < 5; i++) {
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    this.feed(canonicalJson({ reply, sig: b64e(signature) }));
    this.serverState = {
      sessionKey: await importAesKey(
        await hkdf(sharedSecret, transcriptHash, LABEL_SESSION_KEY, 32),
      ),
      nextSendSeq: 0,
    };
  }

  /** Decrypts one received browser frame — call in strict arrival order. */
  async open(index: number): Promise<{ seq: number; body: Record<string, unknown> }> {
    const parsed = JSON.parse(this.sent[index]!) as {
      seq: number;
      nonce: string;
      ct: string;
    };
    return {
      seq: parsed.seq,
      body: JSON.parse(
        utf8Decode(
          await aesDecrypt(
            this.state().sessionKey,
            b64d(parsed.nonce),
            b64d(parsed.ct),
          ),
        ),
      ) as Record<string, unknown>,
    };
  }

  /** Seals one server→browser frame and feeds it to the channel. */
  async respond(body: Record<string, unknown>): Promise<void> {
    const state = this.state();
    const seq = state.nextSendSeq++;
    const nonce = randomBytes(12);
    const ct = await aesEncrypt(state.sessionKey, nonce, utf8.encode(canonicalJson(body)));
    const nonceText = b64e(nonce);
    const ctText = b64e(ct);
    const signature = await sign(
      this.pluginPriv,
      utf8.encode(`${seq}.${nonceText}.${ctText}`),
    );
    this.feed(canonicalJson({ seq, nonce: nonceText, ct: ctText, sig: b64e(signature) }));
  }
}

describe('channel send ordering under concurrency', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('never emits frame N+1 before frame N, even when calls race', async () => {
    const plugin = await ed25519FromRaw(
      b64d(fixture.ed25519.pluginSeed),
      b64d(fixture.ed25519.pluginPub),
    );
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      json: async () => fixture.sessionBlob.envelope,
    }));

    const transport = new FakeServerTransport(plugin.privateKey);
    const channel = await EditorChannel.open(
      'http://test',
      null,
      fixture.handshake.hello.sessionId,
      fixture.sessionBlob.wrappingKey,
      'vitest',
      { transport },
    );

    // Make the race deterministic: the FIRST concurrent frame's signature
    // resolves slowest — exactly the interleaving that fired in the wild.
    // Under the send-chain fix the ordering cannot change; without it, the
    // later frames pass the stalled one on the way out.
    const subtleObj = globalThis.crypto.subtle;
    const originalSign = subtleObj.sign.bind(subtleObj);
    let signCalls = 0;
    vi.spyOn(subtleObj, 'sign').mockImplementation(async (...args: unknown[]) => {
      if (signCalls++ === 0) {
        await new Promise((resolve) => setTimeout(resolve, 30));
      }
      return originalSign(...(args as Parameters<typeof originalSign>));
    });

    // Six calls fired at once — the "open two files quickly" shape, squared.
    const calls = Array.from({ length: 6 }, (_, index) =>
      channel.call('browse.get', { pack: 'sample', path: `items/gem${index}.yml` }),
    );

    // Wait for every frame to hit the wire, then play the strict server:
    // frame N must carry seq N, exactly like FrameCodec.open demands.
    for (let wait = 0; wait < 200 && transport.sent.length < 7; wait++) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(transport.sent.length).toBe(7); // hello + 6 frames, all sent
    for (let index = 1; index < transport.sent.length; index++) {
      const { seq, body } = await transport.open(index);
      expect(seq, `browser frame #${index - 1}`).toBe(index - 1);
      await transport.respond({
        op: body.op,
        id: body.id,
        ok: true,
        data: { echo: body.op },
      });
    }

    const results = await Promise.all(calls);
    expect(results.every((response) => response.ok)).toBe(true);
  });
});
