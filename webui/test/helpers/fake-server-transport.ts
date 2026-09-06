// The browser side of the editor protocol, played by tests: an in-memory
// transport whose send() side completes the real handshake (against the
// golden fixture's plugin identity — the one Ed25519 private the browser
// tests may hold), then seals/delivers frames exactly like the plugin's
// FrameCodec does.
import { readFileSync } from 'node:fs';
import {
  aesDecrypt,
  aesEncrypt,
  concat,
  decodeBase64Url as b64d,
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
} from '../../src/lib/protocol/crypto';
import { canonicalJson } from '../../src/lib/protocol/wire';
import type { Transport } from '../../src/lib/protocol/session';

const fixture = JSON.parse(
  readFileSync(new URL('../../../fixtures/protocol/editor-protocol-v1.json', import.meta.url), 'utf8'),
) as Record<string, any>;

interface ServerState {
  sessionKey: CryptoKey;
  nextSendSeq: number;
}

export function fixturePluginSeed(): Uint8Array {
  return b64d(fixture.ed25519.pluginSeed);
}

export function fixturePluginPub(): Uint8Array {
  return b64d(fixture.ed25519.pluginPub);
}

export function fixtureSessionId(): string {
  return fixture.handshake.hello.sessionId;
}

export function fixtureWrappingKey(): string {
  return fixture.sessionBlob.wrappingKey;
}

export function fixtureBlobEnvelope(): Record<string, unknown> {
  return fixture.sessionBlob.envelope;
}

/** In-memory transport whose send() side plays the plugin (server) role. */
export class FakeServerTransport implements Transport {
  readonly sent: string[] = [];
  private handler: ((text: string) => void) | null = null;
  private closeHandler: ((code: number) => void) | null = null;
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

  onClose(handler: (code: number) => void): void {
    this.closeHandler = handler;
  }

  close(): void {}

  /** Plays the browser's close event (the channel's own close() uses 1000). */
  simulateClose(code: number): void {
    this.closeHandler?.(code);
  }

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
    this.feed(await this.seal(body));
  }

  /** Seals a server→browser frame without delivering it — for burst tests. */
  async seal(body: Record<string, unknown>): Promise<string> {
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
    return canonicalJson({ seq, nonce: nonceText, ct: ctText, sig: b64e(signature) });
  }
}
