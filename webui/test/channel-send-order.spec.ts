// Regression pin for the MTR-0033 quick-double-click session kill
// (web.md §5 strict sequence order). The browser's send side seals frames
// asynchronously (WebCrypto) but the receiver demands strict sequence
// order: concurrent RPCs must take their sequence numbers in exactly the
// order frames hit the wire. Before the send-chain fix, concurrent
// sealAndSend calls raced between `sendSeq++` and the awaited sign(), so
// a later frame could reach the server first — "expected sequence 10, got
// 11 — dropping editor session", and the site had to be re-opened.
//
// The shared FakeServerTransport lives in helpers/fake-server-transport.ts.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ed25519FromRaw } from '../src/lib/protocol/crypto';
import { EditorChannel } from '../src/lib/protocol/session';
import {
  FakeServerTransport,
  fixtureBlobEnvelope,
  fixturePluginPub,
  fixturePluginSeed,
  fixtureSessionId,
  fixtureWrappingKey,
} from './helpers/fake-server-transport';

describe('channel send ordering under concurrency', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('never emits frame N+1 before frame N, even when calls race', async () => {
    const plugin = await ed25519FromRaw(fixturePluginSeed(), fixturePluginPub());
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      json: async () => fixtureBlobEnvelope(),
    }));

    const transport = new FakeServerTransport(plugin.privateKey);
    const channel = await EditorChannel.open(
      'http://test',
      null,
      fixtureSessionId(),
      fixtureWrappingKey(),
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
