import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  decodeBase64Url as b64d,
  encodeBase64Url as b64e,
  ed25519FromRaw,
  generateSigningPair,
  sign,
  subtle,
} from '../src/lib/protocol/crypto';
import { describeDecision, decideTransport, relayOutageGuidance } from '../src/lib/protocol/connect';
import { probeDirect, probeSignedBytes } from '../src/lib/protocol/probe';
import type { WebSocketLike } from '../src/lib/protocol/session';
import type { SessionBlob } from '../src/lib/protocol/session';
import {
  isLoopbackHost,
  parseOverride,
  planDirect,
  readPreference,
  writePreference,
} from '../src/lib/protocol/transport-plan';

/**
 * The cloud-vs-direct decision (web.md §4.4). The invariants under test are
 * the owner's: the relay is the default, a direct connection replaces it only
 * after an authenticated, session-bound proof, and EVERY other outcome —
 * including every failure mode of the probe — silently stays on the relay.
 */

// ------------------------------------------------------------------ fixtures

const SESSION_ID = 'AAECAwQFBgcICQoLDA0ODw';

async function pluginKey() {
  const pair = await generateSigningPair();
  const pub = new Uint8Array((await subtle().exportKey('raw', pair.publicKey)) as ArrayBuffer);
  return { pair, pub };
}

function blobWith(pub: Uint8Array, directUrl?: string): SessionBlob {
  return {
    sessionId: SESSION_ID,
    protocolVersions: [1],
    pluginSigningPubKey: pub,
    relay: { base: 'https://editor.materione.com', channelId: 'c', editorToken: 't' },
    direct: directUrl ? { url: directUrl } : undefined,
  };
}

type Listener = (event: { data?: unknown; code?: number }) => void;

/** A scriptable stand-in for whatever answers on the hinted address. */
class FakeSocket implements WebSocketLike {
  readyState = 0;
  sent: string[] = [];
  closed = false;
  #listeners = new Map<string, Listener[]>();

  constructor(private readonly behavior: (socket: FakeSocket, text: string) => void) {
    queueMicrotask(() => {
      this.readyState = 1;
      this.emit('open', {});
    });
  }

  send(text: string): void {
    this.sent.push(text);
    queueMicrotask(() => this.behavior(this, text));
  }

  close(): void {
    this.closed = true;
    this.readyState = 3;
  }

  addEventListener(type: string, listener: Listener): void {
    this.#listeners.set(type, [...(this.#listeners.get(type) ?? []), listener]);
  }

  emit(type: string, event: { data?: unknown; code?: number }): void {
    for (const listener of this.#listeners.get(type) ?? []) listener(event);
  }
}

/** The genuine plugin: signs the probe with its identity key. */
function genuine(privateKey: CryptoKey): (socket: FakeSocket, text: string) => void {
  return (socket, text) => {
    const probe = JSON.parse(text) as { sessionId: string; challenge: string };
    void sign(privateKey, probeSignedBytes(probe.sessionId, probe.challenge)).then((sig) =>
      socket.emit('message', {
        data: JSON.stringify({ type: 'probe-reply', sessionId: probe.sessionId, sig: b64e(sig) }),
      }),
    );
  };
}

const fixedRandom = (length: number) => new Uint8Array(length).fill(7);

// ---------------------------------------------------------------------- plan

describe('planDirect', () => {
  const base = { pageProtocol: 'https:', hint: 'http://127.0.0.1:8089', override: null, preference: 'auto' } as const;

  it('probes a loopback hint from the https cloud page', () => {
    expect(planDirect(base)).toEqual({ kind: 'probe', wsUrl: 'ws://127.0.0.1:8089/editor/ws' });
  });

  it('never probes without a hint — the server never invited it', () => {
    expect(planDirect({ ...base, hint: undefined })).toEqual({ kind: 'skip', reason: 'no-hint' });
  });

  it('?mode=relay always wins, even over ?mode=direct semantics', () => {
    expect(planDirect({ ...base, override: 'relay' })).toEqual({ kind: 'skip', reason: 'override-relay' });
  });

  it('the stored prefer-cloud switch skips, ?mode=direct bypasses it', () => {
    expect(planDirect({ ...base, preference: 'cloud' })).toEqual({ kind: 'skip', reason: 'preference-cloud' });
    expect(planDirect({ ...base, preference: 'cloud', override: 'direct' }).kind).toBe('probe');
  });

  it('skips a plain-http LAN address from an https page: mixed content can never work', () => {
    expect(planDirect({ ...base, hint: 'http://192.168.1.20:8089' })).toEqual({ kind: 'skip', reason: 'mixed-content' });
    expect(planDirect({ ...base, hint: 'http://mc.example.com:8089' })).toEqual({ kind: 'skip', reason: 'mixed-content' });
  });

  it('allows an https front (reverse proxy) as wss', () => {
    expect(planDirect({ ...base, hint: 'https://editor.team.example' })).toEqual({
      kind: 'probe',
      wsUrl: 'wss://editor.team.example/editor/ws',
    });
  });

  it('a plain-http page (self-hosted/dev relay) may probe plain-http targets', () => {
    expect(planDirect({ ...base, pageProtocol: 'http:', hint: 'http://192.168.1.20:8089' }).kind).toBe('probe');
  });

  it('rejects hints that are not http(s) URLs', () => {
    expect(planDirect({ ...base, hint: 'not a url' })).toEqual({ kind: 'skip', reason: 'bad-hint' });
    expect(planDirect({ ...base, hint: 'javascript:alert(1)' })).toEqual({ kind: 'skip', reason: 'bad-hint' });
    expect(planDirect({ ...base, hint: 'ftp://127.0.0.1' })).toEqual({ kind: 'skip', reason: 'bad-hint' });
  });
});

describe('helpers', () => {
  it('classifies loopback hosts the way browsers do', () => {
    for (const host of ['localhost', 'foo.localhost', '127.0.0.1', '127.1.2.3', '::1', '[::1]']) {
      expect(isLoopbackHost(host)).toBe(true);
    }
    for (const host of ['192.168.1.1', '10.0.0.4', 'example.com', '127.0.0.1.evil.com', '0.0.0.0']) {
      expect(isLoopbackHost(host)).toBe(false);
    }
  });

  it('parses the manual override', () => {
    expect(parseOverride('?mode=relay')).toBe('relay');
    expect(parseOverride('?mode=cloud')).toBe('relay');
    expect(parseOverride('?s=abc&mode=direct')).toBe('direct');
    expect(parseOverride('?mode=local')).toBe('direct');
    expect(parseOverride('?mode=wat')).toBeNull();
    expect(parseOverride('')).toBeNull();
  });

  it('stores the preference per browser and survives broken storage', () => {
    const data = new Map<string, string>();
    const storage = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) };
    expect(readPreference(storage)).toBe('auto');
    writePreference('cloud', storage);
    expect(readPreference(storage)).toBe('cloud');
    writePreference('auto', storage);
    expect(readPreference(storage)).toBe('auto');
    const broken = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(readPreference(broken)).toBe('auto');
    expect(() => writePreference('cloud', broken)).not.toThrow();
    expect(readPreference(null)).toBe('auto');
  });
});

// --------------------------------------------------------------------- probe

describe('probeDirect — the proof', () => {
  it('accepts the genuine plugin and hands over the OPEN socket', async () => {
    const { pair, pub } = await pluginKey();
    let socket!: FakeSocket;
    const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () => (socket = new FakeSocket(genuine(pair.privateKey))),
      random: fixedRandom,
    });
    expect(result.ok).toBe(true);
    expect(socket.closed).toBe(false); // reused as the session transport: no second connect
    const sent = JSON.parse(socket.sent[0]!) as Record<string, string>;
    expect(sent).toEqual({ type: 'probe', sessionId: SESSION_ID, challenge: b64e(fixedRandom(16)) });
  });

  it('rejects a server that merely accepts the connection (impersonation by an open port)', async () => {
    const { pub } = await pluginKey();
    let socket!: FakeSocket;
    const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () => (socket = new FakeSocket(() => {})),
      random: fixedRandom,
      timeoutMs: 40,
    });
    expect(result).toEqual({ ok: false, reason: 'timeout' });
    expect(socket.closed).toBe(true);
  });

  it('rejects another Materione server (different identity key) on the same address', async () => {
    const { pub } = await pluginKey();
    const other = await pluginKey();
    const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () => new FakeSocket(genuine(other.pair.privateKey)),
      random: fixedRandom,
    });
    expect(result).toEqual({ ok: false, reason: 'bad-signature' });
  });

  it('rejects a replayed reply recorded for another challenge', async () => {
    const { pair, pub } = await pluginKey();
    const recordedChallenge = b64e(new Uint8Array(16).fill(1));
    const recordedSig = b64e(await sign(pair.privateKey, probeSignedBytes(SESSION_ID, recordedChallenge)));
    const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () =>
        new FakeSocket((socket) =>
          socket.emit('message', {
            data: JSON.stringify({ type: 'probe-reply', sessionId: SESSION_ID, sig: recordedSig }),
          }),
        ),
      random: fixedRandom, // fresh challenge differs from the recorded one
    });
    expect(result).toEqual({ ok: false, reason: 'bad-signature' });
  });

  it('rejects a reply that names another session', async () => {
    const { pair, pub } = await pluginKey();
    const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () =>
        new FakeSocket((socket, text) => {
          const probe = JSON.parse(text) as { challenge: string };
          void sign(pair.privateKey, probeSignedBytes('OTHERSESSIONOTHERSESSION', probe.challenge)).then((sig) =>
            socket.emit('message', {
              data: JSON.stringify({ type: 'probe-reply', sessionId: 'OTHERSESSIONOTHERSESSION', sig: b64e(sig) }),
            }),
          );
        }),
      random: fixedRandom,
    });
    expect(result).toEqual({ ok: false, reason: 'bad-reply' });
  });

  it('treats garbage and non-text replies as a failed probe, never an exception', async () => {
    const { pub } = await pluginKey();
    for (const data of ['<html>hello</html>', '{"type":"probe-reply"}', 42, null]) {
      const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
        openSocket: () => new FakeSocket((socket) => socket.emit('message', { data })),
        random: fixedRandom,
      });
      expect(result.ok).toBe(false);
    }
  });

  it('maps a 4404 close to "declined" and any other close/error to unreachable', async () => {
    const { pub } = await pluginKey();
    const declined = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () => new FakeSocket((socket) => socket.emit('close', { code: 4404 })),
    });
    expect(declined).toEqual({ ok: false, reason: 'declined' });
    const refused = await probeDirect('ws://127.0.0.1:8089/editor/ws', blobWith(pub), {
      openSocket: () => new FakeSocket((socket) => socket.emit('error', {})),
    });
    expect(refused).toEqual({ ok: false, reason: 'unreachable' });
  });

  it('survives a browser that throws on construction (mixed content / blocked)', async () => {
    const { pub } = await pluginKey();
    const result = await probeDirect('ws://192.168.1.20:8089/editor/ws', blobWith(pub), {
      openSocket: () => {
        throw new DOMException('insecure WebSocket from https page', 'SecurityError');
      },
    });
    expect(result).toEqual({ ok: false, reason: 'unreachable' });
  });
});

// ------------------------------------------------------------ decision machine

describe('decideTransport — relay is the default; direct only when proven', () => {
  const env = { pageProtocol: 'https:', override: null, preference: 'auto' } as const;

  it('goes direct after a verified proof, adopting the proven socket', async () => {
    const { pair, pub } = await pluginKey();
    const decision = await decideTransport(blobWith(pub, 'http://127.0.0.1:8089'), env, {
      openSocket: () => new FakeSocket(genuine(pair.privateKey)),
    });
    expect(decision.mode).toBe('direct');
    expect(decision.reason).toBe('direct-proven');
    expect(decision.transport).toBeDefined();
  });

  it('stays on the cloud when there is no hint, with no socket ever opened', async () => {
    const { pub } = await pluginKey();
    let opened = 0;
    const decision = await decideTransport(blobWith(pub), env, {
      openSocket: () => {
        opened++;
        return new FakeSocket(() => {});
      },
    });
    expect(decision).toEqual({ mode: 'cloud', reason: 'no-hint' });
    expect(opened).toBe(0);
  });

  it.each([
    ['override', { ...env, override: 'relay' as const }, 'http://127.0.0.1:8089', 'override-relay'],
    ['preference', { ...env, preference: 'cloud' as const }, 'http://127.0.0.1:8089', 'preference-cloud'],
    ['mixed content', env, 'http://192.168.1.20:8089', 'mixed-content'],
    ['bad hint', env, 'nonsense', 'bad-hint'],
  ])('never even opens a socket on %s', async (_name, scenario, hint, reason) => {
    const { pub } = await pluginKey();
    let opened = 0;
    const decision = await decideTransport(blobWith(pub, hint), scenario, {
      openSocket: () => {
        opened++;
        return new FakeSocket(() => {});
      },
    });
    expect(decision).toEqual({ mode: 'cloud', reason });
    expect(opened).toBe(0);
  });

  it('falls back to the cloud — silently — for every probe failure', async () => {
    const { pub } = await pluginKey();
    const other = await pluginKey();
    const scenarios: Array<[string, () => WebSocketLike, string]> = [
      ['silent port', () => new FakeSocket(() => {}), 'probe-timeout'],
      ['wrong server', () => new FakeSocket(genuine(other.pair.privateKey)), 'probe-bad-signature'],
      ['closed', () => new FakeSocket((s) => s.emit('close', { code: 1006 })), 'probe-unreachable'],
      ['declined', () => new FakeSocket((s) => s.emit('close', { code: 4404 })), 'probe-declined'],
      [
        'blocked',
        () => {
          throw new Error('blocked');
        },
        'probe-unreachable',
      ],
    ];
    for (const [, factory, reason] of scenarios) {
      const decision = await decideTransport(blobWith(pub, 'http://127.0.0.1:8089'), env, {
        openSocket: factory,
        timeoutMs: 40,
      });
      expect(decision).toEqual({ mode: 'cloud', reason });
      expect(decision.transport).toBeUndefined();
    }
  });

  it('?mode=direct cannot force a broken page: an unproven direct still lands on the cloud', async () => {
    const { pub } = await pluginKey();
    const decision = await decideTransport(
      blobWith(pub, 'http://127.0.0.1:8089'),
      { ...env, override: 'direct', preference: 'cloud' },
      { openSocket: () => new FakeSocket(() => {}), timeoutMs: 40 },
    );
    expect(decision.mode).toBe('cloud');
    expect(decision.reason).toBe('probe-timeout');
  });
});

describe('copy', () => {
  it('only a proven direct connection talks about being direct', () => {
    expect(describeDecision('direct', 'direct-proven')).toMatch(/verified/);
    for (const reason of ['no-hint', 'probe-timeout', 'mixed-content', 'preference-cloud', 'override-relay'] as const) {
      expect(describeDecision('cloud', reason)).toMatch(/^cloud relay/);
    }
  });

  it('relay outage guidance names the escape hatch with its caveat — and only for a cloud origin', () => {
    const outage = relayOutageGuidance(new Error('session blob fetch failed: HTTP 503'), 'https://editor.materione.com');
    expect(outage).toContain('HTTP 503');
    expect(outage).toContain('/materione editor local');
    expect(outage).toContain('not reachable');
    expect(outage).toContain('Nothing was switched');
    expect(relayOutageGuidance(new TypeError('Failed to fetch'), 'https://editor.materione.com')).not.toBeNull();
    expect(relayOutageGuidance(new Error('session blob fetch failed: HTTP 429'), 'https://editor.materione.com')).not.toBeNull();
    // not an outage: a missing/expired link, or a plugin-served page
    expect(relayOutageGuidance(new Error('session blob fetch failed: HTTP 404'), 'https://editor.materione.com')).toBeNull();
    expect(relayOutageGuidance(new Error('session blob fetch failed: HTTP 503'), 'http://127.0.0.1:8089')).toBeNull();
    expect(relayOutageGuidance(new Error('ciphertext failed authentication'), 'https://editor.materione.com')).toBeNull();
  });
});

// ------------------------------------------------------------ golden fixture

describe('golden probe fixture (shared with the JVM tests)', () => {
  const fixture = JSON.parse(
    readFileSync(new URL('../../fixtures/protocol/editor-protocol-v1.json', import.meta.url), 'utf8'),
  ) as Record<string, any>;

  it('signs exactly the bytes the plugin signed, and the plugin reply verifies', async () => {
    const { request, reply, signedText } = fixture.probe;
    expect(new TextDecoder().decode(probeSignedBytes(request.sessionId, request.challenge))).toBe(signedText);

    const plugin = await ed25519FromRaw(b64d(fixture.ed25519.pluginSeed), b64d(fixture.ed25519.pluginPub));
    // Ed25519 is deterministic: re-signing reproduces the committed JVM signature byte for byte.
    expect(b64e(await sign(plugin.privateKey, probeSignedBytes(request.sessionId, request.challenge)))).toBe(reply.sig);

    // And the real probe accepts that recorded reply when the random challenge matches the fixture's.
    const challengeBytes = b64d(request.challenge);
    const result = await probeDirect('ws://127.0.0.1:8089/editor/ws', {
      sessionId: request.sessionId,
      pluginSigningPubKey: b64d(fixture.ed25519.pluginPub),
    }, {
      openSocket: () => new FakeSocket((socket) => socket.emit('message', { data: JSON.stringify(reply) })),
      random: () => challengeBytes,
    });
    expect(result.ok).toBe(true);
  });
});
