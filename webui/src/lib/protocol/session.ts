/**
 * The browser side of the editor session (web.md §4.1/§5.4): capability-URL
 * blob decryption, the hello handshake, key confirmation, and the framed
 * encrypted+signed channel every later message rides on. Byte-for-byte
 * mirror of dev.materione.web.protocol; drift is caught by the golden
 * fixture test (test/protocol.spec.ts) and by live servers refusing us.
 */
import {
  aesDecrypt,
  aesEncrypt,
  bytesEqual,
  concat,
  decodeBase64Url as b64d,
  encodeBase64Url as b64e,
  ecdhSharedSecret,
  generateEphemeralPair,
  generateSigningPair,
  hkdf,
  hmacSha256,
  importAesKey,
  importEd25519Public,
  LABEL_KEY_CONFIRM,
  LABEL_SESSION_KEY,
  randomBytes,
  sha256,
  sign,
  subtle,
  utf8,
  utf8Decode,
  verify,
} from './crypto';
import { canonicalJson } from './wire';
import type { Request, Response } from './wire';

/** Relay-mode routing carried inside the encrypted blob (web.md §3.2). */
export interface RelayInfo {
  base: string;
  channelId: string;
  editorToken: string;
}

/** Best-effort direct-mode hint for LAN auto-preference (web.md §4.4). */
export interface DirectInfo {
  url: string;
}

export interface SessionBlob {
  sessionId: string;
  protocolVersions: number[];
  capabilities?: string[];
  pluginSigningPubKey: Uint8Array;
  relay?: RelayInfo;
  direct?: DirectInfo;
}

/**
 * Protocol versions this SPA build speaks (web.md §4.2). The session blob
 * advertises the jar's list; the hello carries the highest version present
 * in BOTH lists — so a cloud panel served from cache one release behind (or
 * ahead of) the connected jar negotiates instead of being hard-rejected.
 * Keep in lockstep with the jar's Wire.SUPPORTED_VERSIONS (web.md §4.2).
 */
export const SUPPORTED_PROTOCOL_VERSIONS = [1];

/**
 * Intersects the two sides' protocol versions and returns the one the hello
 * will speak. Throws before any transport is opened when the lists are
 * disjoint — the panel cannot serve this jar at all, and the message says
 * which side to move.
 */
export function negotiateProtocolVersion(blob: {
  protocolVersions?: number[];
}): number {
  const offered = blob.protocolVersions ?? [];
  const common = SUPPORTED_PROTOCOL_VERSIONS.filter((v) => offered.includes(v));
  if (common.length === 0) {
    throw new Error(
      `no common protocol version — this panel speaks [${SUPPORTED_PROTOCOL_VERSIONS.join(', ')}] ` +
        `but the server offers [${offered.join(', ')}]; update the Materione plugin or hard-refresh this page`,
    );
  }
  return Math.max(...common);
}

/**
 * Capability names this SPA build knows, mirroring the jar's
 * SessionBlob.CURRENT_CAPABILITIES (dev.materione.web.protocol) — the
 * golden fixture test pins the two lists together.
 */
export const CURRENT_CAPABILITIES = [
  'assets',
  'blocks',
  'delivery',
  'furniture',
  'glyphs',
  'gui',
  'items',
  'models',
  'paintings',
  'recipes',
  'sounds',
  'text-effects',
] as const;

/**
 * Capability gating (web.md §4.1): the blob names the editor features the
 * connected jar implements; the panel hides or annotates the rest instead
 * of collecting per-call "unknown op" errors. An absent list is a
 * pre-capabilities jar — assume the full surface so old jars keep working
 * against new panels.
 */
export function hasCapability(blob: SessionBlob, name: string): boolean {
  return blob.capabilities === undefined || blob.capabilities.includes(name);
}

export type ChannelState =
  | { kind: 'connecting' }
  | { kind: 'awaiting-approval'; noncePrefix: string }
  | { kind: 'active' }
  | { kind: 'closed'; reason: string };

/** Text-transport seam so tests can drive the handshake without sockets. */
export interface Transport {
  send(text: string): void;
  /** `null` detaches: incoming frames buffer until the next handler attaches. */
  onMessage(handler: ((text: string) => void) | null): void;
  onClose(handler: (code: number) => void): void;
  close(): void;
}

export class WebSocketTransport implements Transport {
  private readonly socket: WebSocketLike;
  private messageHandler: ((text: string) => void) | null = null;
  /** Frames that arrive with no handler attached — a dropped frame would
   * desync the strict receive sequence, so they buffer until a handler
   * attaches and drain in order. */
  private readonly buffered: string[] = [];

  /** Injectable socket shape so tests can drive the handover gap. */
  constructor(socket: WebSocketLike) {
    this.socket = socket;
  }

  /** Resolves once the socket is open and ready to send. */
  get opened(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.socket.readyState === WebSocket.OPEN) {
        resolve();
      } else {
        this.socket.addEventListener('open', () => resolve(), { once: true });
        this.socket.addEventListener('error', () => reject(new Error('websocket failed')), {
          once: true,
        });
      }
    });
  }

  /** Adopts an already-open socket (test seam); {@link connect} is the real path. */
  static adopt(socket: WebSocketLike): WebSocketTransport {
    const transport = new WebSocketTransport(socket);
    socket.addEventListener('message', (event) =>
      transport.deliver(event.data as string),
    );
    return transport;
  }

  static async connect(url: string): Promise<WebSocketTransport> {
    // Wire the listener at construction (no open-gap), then wait for OPEN —
    // callers send immediately after this resolves.
    const transport = WebSocketTransport.adopt(new WebSocket(url) as unknown as WebSocketLike);
    await transport.opened;
    return transport;
  }

  send(text: string): void {
    this.socket.send(text);
  }

  onMessage(handler: ((text: string) => void) | null): void {
    this.messageHandler = handler;
    if (!handler) {
      return;
    }
    const backlog = [...this.buffered];
    this.buffered.length = 0;
    for (const text of backlog) {
      handler(text);
    }
  }

  onClose(handler: (code: number) => void): void {
    this.socket.addEventListener('close', (event) => handler(event.code ?? 1006));
  }

  close(): void {
    this.socket.close();
  }

  private deliver(text: string): void {
    if (this.messageHandler) {
      this.messageHandler(text);
    } else {
      this.buffered.push(text);
    }
  }
}

/** The slice of the WebSocket API the transport actually uses. */
export interface WebSocketLike {
  readyState: number;
  send(text: string): void;
  close(): void;
  addEventListener(
    type: string,
    listener: (event: { data?: unknown; code?: number }) => void,
    options?: { once?: boolean },
  ): void;
}

interface FrameJson {
  seq: number;
  nonce: string;
  ct: string;
  sig: string;
}

/** Opens the envelope ({v,sess,nonce,ct}) under `key`; returns [type, body]. */
export async function openEnvelope(
  keyRaw: Uint8Array,
  expectedSessionId: string,
  envelope: Record<string, unknown>,
): Promise<{ type: string; body: Uint8Array }> {
  if (envelope.v !== 1) {
    throw new Error('unknown envelope version');
  }
  if (envelope.sess !== expectedSessionId) {
    throw new Error('envelope bound to another session');
  }
  const nonce = b64d(envelope.nonce as string);
  const ciphertext = b64d(envelope.ct as string);
  let plaintext: Uint8Array;
  try {
    plaintext = await aesDecrypt(await importAesKey(keyRaw), nonce, ciphertext);
  } catch {
    throw new Error('ciphertext failed authentication — wrong fragment key?');
  }
  const inner = JSON.parse(utf8Decode(plaintext)) as { type: string; body: string };
  return { type: inner.type, body: b64d(inner.body) };
}

/** Fetches + decrypts the session blob behind a capability URL's halves. */
export async function fetchSessionBlob(
  httpBase: string,
  code: string,
  fragmentKey: string,
): Promise<SessionBlob> {
  const response = await fetch(`${httpBase}/s/${code}`);
  if (!response.ok) {
    throw new Error(`session blob fetch failed: HTTP ${response.status}`);
  }
  const wrappingKey = b64d(fragmentKey);
  const opened = await openEnvelope(wrappingKey, code, await response.json());
  if (opened.type !== 'session-blob') {
    throw new Error('unexpected blob type ' + opened.type);
  }
  const parsed = JSON.parse(utf8Decode(opened.body)) as {
    sessionId: string;
    protocolVersions: number[];
    capabilities?: string[];
    pluginSigningPubKey: string;
    relay?: { base: string; channelId: string; editorToken: string };
    direct?: { url: string };
  };
  return {
    sessionId: parsed.sessionId,
    protocolVersions: parsed.protocolVersions,
    capabilities: parsed.capabilities,
    pluginSigningPubKey: b64d(parsed.pluginSigningPubKey),
    relay: parsed.relay,
    direct: parsed.direct,
  };
}

export interface OpenOptions {
  nonceOverride?: Uint8Array; // golden-fixture determinism only
  transport?: Transport; // in-memory transport for tests
  blob?: SessionBlob; // already-fetched blob (transport selection did it)
  onState?: (state: ChannelState) => void;
}

/** One active editor channel; construct via {@link EditorChannel.open}. */
export class EditorChannel {
  private transport!: Transport;
  private sendSeq = 0;
  private receiveSeq = 0;
  /**
   * Sealing is async (WebCrypto) but the receiver demands strict sequence
   * order — the seq must be assigned in exactly the order frames hit the
   * wire. Each send chains onto the previous one, so frame N is fully on
   * the wire before frame N+1 takes its number (the browser mirror of the
   * server's synchronized sendFrame).
   */
  private sendChain: Promise<void> = Promise.resolve();
  /** Receive-side twin of {@link sendChain}: frames process in arrival order. */
  private receiveChain: Promise<void> = Promise.resolve();
  private pending = new Map<
    string,
    { resolve: (r: Response) => void; reject: (e: Error) => void }
  >();
  private pushListener?: (request: Request) => void;
  private stateListener?: (state: ChannelState) => void;

  private constructor(
    readonly blob: SessionBlob,
    private readonly aesKey: CryptoKey,
    private readonly editorSigningPrivate: CryptoKey,
    readonly serverPublic: CryptoKey,
  ) {}

  onState(listener: (state: ChannelState) => void): void {
    this.stateListener = listener;
  }

  onPush(listener: (request: Request) => void): void {
    this.pushListener = listener;
  }

  /**
   * The full handshake: blob → transport → hello → in-game approval →
   * signed reply → key confirmation. Resolves once the channel is ACTIVE.
   */
  static async open(
    httpBase: string,
    wsUrl: string | null,
    code: string,
    fragmentKey: string,
    browserLabel: string,
    options: OpenOptions = {},
  ): Promise<EditorChannel> {
    options.onState?.({ kind: 'connecting' });
    const blob = options.blob ?? (await fetchSessionBlob(httpBase, code, fragmentKey));
    // Negotiate before any transport is opened: a disjoint version list
    // throws a readable error instead of a socket-level mystery.
    const protocolVersion = negotiateProtocolVersion(blob);

    const signing = await generateSigningPair();
    const ephemeral = await generateEphemeralPair();
    const ephemeralPub = new Uint8Array(
      (await subtle().exportKey('raw', ephemeral.publicKey)) as Uint8Array,
    );
    const editorSigningPub = new Uint8Array(
      (await subtle().exportKey('raw', signing.publicKey)) as Uint8Array,
    );
    const nonce = options.nonceOverride ?? randomBytes(16);

    const hello = {
      type: 'hello',
      nonce: b64e(nonce),
      sessionId: blob.sessionId,
      protocolVersion,
      editorSigningPubKey: b64e(editorSigningPub),
      editorEphemeralPubKey: b64e(ephemeralPub),
      browser: browserLabel,
    };

    const serverPublic = await importEd25519Public(blob.pluginSigningPubKey);
    const transport =
      options.transport ?? (await WebSocketTransport.connect(wsUrl ?? ''));

    // First message is the signed plaintext reply; the transcript covers
    // hello + reply WITHOUT confirm (see HelloReplyMessage#transcriptPart).
    // The one-shot handler detaches itself the moment it fires: key
    // derivation below spans several awaits, during which the server may
    // already push frames (MTR-0032's diagnostic broadcast fires the instant
    // the reply is written). A stale resolve() would swallow them — and a
    // swallowed frame desyncs the strict receive sequence, killing the
    // channel on the very next RPC. Detached frames buffer instead.
    const replyText = await new Promise<string>((resolve) => {
      transport.onMessage((text) => {
        transport.onMessage(null);
        resolve(text);
      });
      transport.send(canonicalJson(hello));
      options.onState?.({
        kind: 'awaiting-approval',
        noncePrefix: hexPrefix(nonce),
      });
    });

    const payload = JSON.parse(replyText) as Record<string, unknown>;
    const reply = payload.reply as Record<string, unknown>;
    if (
      !(await verify(serverPublic, b64d(payload.sig as string), utf8.encode(canonicalJson(reply))))
    ) {
      throw new Error('hello-reply signature invalid');
    }
    const transcriptHash = await sha256(
      concat(
        utf8.encode(canonicalJson(hello)),
        utf8.encode(canonicalJson({ type: reply.type, ecdhPub: reply.ecdhPub })),
      ),
    );

    const sharedSecret = await ecdhSharedSecret(ephemeral.privateKey, b64d(reply.ecdhPub as string));
    const confirmKey = await hkdf(sharedSecret, transcriptHash, LABEL_KEY_CONFIRM, 32);
    if (!bytesEqual(await hmacSha256(confirmKey, transcriptHash), b64d(reply.confirm as string))) {
      throw new Error('key confirmation mismatch');
    }
    const sessionKeyRaw = await hkdf(sharedSecret, transcriptHash, LABEL_SESSION_KEY, 32);

    const channel = new EditorChannel(
      blob,
      await importAesKey(sessionKeyRaw),
      signing.privateKey,
      serverPublic,
    );
    channel.transport = transport;
    // Fire-and-forget, but each frame's processing chains onto the previous
    // one: the seq check must run in exactly the arrival order, and the
    // signature verify inside receive() suspends — two frames flushed
    // back-to-back would otherwise race (frame 2's seq check runs while
    // frame 1 still awaits verify) and kill the channel (MTR-0033's
    // receive-side twin).
    transport.onMessage((text) => {
      channel.receiveChain = channel.receiveChain
        .then(() => channel.receive(text))
        .catch(() => {}); // receive() reports its own failures via fail()
    });
    transport.onClose((code) => {
      channel.pending.forEach((p) => p.reject(new Error(`channel closed (${code})`)));
      channel.pending.clear();
      // A fail() already reported the true reason; the browser's close
      // event (code 1000 for our own close()) must not overwrite it.
      if (!channel.isDead()) {
        channel.stateListener?.({ kind: 'closed', reason: `closed (${code})` });
      }
    });
    options.onState?.({ kind: 'active' });
    return channel;
  }

  /** One RPC round trip over sealed frames. */
  async call(op: string, payload: Record<string, unknown> = {}): Promise<Response> {
    const id = 'c' + Math.random().toString(36).slice(2, 10);
    const promise = new Promise<Response>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
    this.deliver(canonicalJson({ op, id, payload }));
    return promise;
  }

  close(): void {
    this.transport.close();
  }

  // ------------------------------------------------------------------

  private deliver(text: string): void {
    this.sendChain = this.sendChain
      .then(() => this.sealAndSend(text))
      .catch((error: unknown) => this.fail(error));
  }

  private async sealAndSend(text: string): Promise<void> {
    const nonce = randomBytes(12);
    const ciphertext = await aesEncrypt(this.aesKey, nonce, utf8.encode(text));
    const seq = this.sendSeq++;
    const nonceText = b64e(nonce);
    const ctText = b64e(ciphertext);
    const signature = await sign(
      this.editorSigningPrivate,
      utf8.encode(`${seq}.${nonceText}.${ctText}`),
    );
    const frame: FrameJson = { seq, nonce: nonceText, ct: ctText, sig: b64e(signature) };
    this.transport.send(canonicalJson(frame));
  }

  private async receive(data: string): Promise<void> {
    try {
      const frame = JSON.parse(data) as FrameJson;
      if (frame.seq !== this.receiveSeq) {
        throw new Error(`sequence break: expected ${this.receiveSeq}, got ${frame.seq}`);
      }
      if (!(await verify(this.serverPublic, b64d(frame.sig), utf8.encode(`${frame.seq}.${frame.nonce}.${frame.ct}`)))) {
        throw new Error('frame signature invalid');
      }
      this.receiveSeq++;
      const plaintext = await aesDecrypt(this.aesKey, b64d(frame.nonce), b64d(frame.ct));
      const message = JSON.parse(utf8Decode(plaintext)) as Response & Partial<Request>;
      if ('ok' in message) {
        const waiting = this.pending.get(message.id);
        if (waiting) {
          this.pending.delete(message.id);
          waiting.resolve(message as Response);
        }
      } else if ('op' in message && this.pushListener) {
        this.pushListener(message as Request);
      }
    } catch (error) {
      this.fail(error);
    }
  }

  private dead = false;

  /** True once {@link fail} ran — close events then carry no new information. */
  isDead(): boolean {
    return this.dead;
  }

  private fail(error: unknown): void {
    if (this.dead) {
      return; // one failure report is enough; queued sends land here too
    }
    this.dead = true;
    this.close();
    this.pending.forEach((p) => p.reject(error instanceof Error ? error : new Error(String(error))));
    this.pending.clear();
    this.stateListener?.({
      kind: 'closed',
      reason: error instanceof Error ? error.message : String(error),
    });
  }
}

// ------------------------------------------------------------------ helpers

function hexPrefix(nonce: Uint8Array): string {
  return Array.from(nonce.slice(0, 4), (b) => b.toString(16).padStart(2, '0')).join('');
}
