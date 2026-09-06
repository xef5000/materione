// Regression pins for the "Connection failed: closed (1000)" session kill
// (the receive-side twin of channel-send-order.spec.ts). The browser
// dispatches websocket message events back-to-back when the server writes
// several frames in one flush — which a save-triggered reload always does
// (per-diagnostic pushes + push.reload, written microseconds apart).
// receive() used to check the frame sequence before its awaited signature
// verify and only advanced the counter afterwards, so frame 2's check ran
// while frame 1 still verified: "sequence break", the channel died, and
// the close event's own code 1000 masked the real reason on screen.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ed25519FromRaw } from '../src/lib/protocol/crypto';
import { EditorChannel, type ChannelState } from '../src/lib/protocol/session';
import {
  FakeServerTransport,
  fixtureBlobEnvelope,
  fixturePluginPub,
  fixturePluginSeed,
  fixtureSessionId,
  fixtureWrappingKey,
} from './helpers/fake-server-transport';

async function openChannel(): Promise<{
  transport: FakeServerTransport;
  channel: EditorChannel;
  states: ChannelState[];
}> {
  vi.stubGlobal('fetch', async () => ({
    ok: true,
    json: async () => fixtureBlobEnvelope(),
  }));
  const plugin = await ed25519FromRaw(fixturePluginSeed(), fixturePluginPub());
  const states: ChannelState[] = [];
  const transport = new FakeServerTransport(plugin.privateKey);
  const channel = await EditorChannel.open(
    'http://test',
    null,
    fixtureSessionId(),
    fixtureWrappingKey(),
    'vitest',
    {
      transport,
      onState: (state) => states.push(state),
    },
  );
  // The app registers its state listener via onState() after open(); the
  // fail() path reports through that one, not through options.onState.
  channel.onState((state) => states.push(state));
  return { transport, channel, states };
}

function reasonOf(state: ChannelState | undefined): string {
  return state && state.kind === 'closed' ? state.reason : '';
}

describe('channel receive ordering under back-to-back frames', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('processes two frames delivered in one flush, in order', async () => {
    const { transport, channel, states } = await openChannel();

    // Two RPCs registered before any response exists — the studio's
    // save → load → refreshPack shape.
    const first = channel.call('browse.get', { pack: 'sample', path: 'glyphs/heart.yml' });
    const second = channel.call('browse.get', { pack: 'sample', path: 'glyphs/spark.yml' });

    // Read the request ids off the wire frames once both have been sealed.
    for (let wait = 0; wait < 200 && transport.sent.length < 3; wait++) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    const { body: request1 } = await transport.open(1);
    const { body: request2 } = await transport.open(2);

    // Pre-seal both responses, then deliver them synchronously: one flush,
    // two message events, no gap for frame 1 to finish verifying in.
    const response1 = await transport.seal({ id: request1.id, ok: true, data: { n: 1 } });
    const response2 = await transport.seal({ id: request2.id, ok: true, data: { n: 2 } });
    transport.feed(response1);
    transport.feed(response2);

    const [r1, r2] = await Promise.all([first, second]);
    expect(r1.ok).toBe(true);
    expect(r2.ok).toBe(true);
    expect(channel.isDead()).toBe(false);
    expect(states.at(-1)?.kind).toBe('active');
  });

  it('keeps the true failure reason when the close event follows', async () => {
    const { transport, channel, states } = await openChannel();

    const first = channel.call('browse.get', { pack: 'sample', path: 'x.yml' });
    first.catch(() => {}); // its rejection is the expected failure, not noise

    // A frame with the wrong sequence number must kill the channel with
    // THAT reason…
    transport.feed(JSON.stringify({ seq: 5, nonce: 'A', ct: 'B', sig: 'C' }));
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(channel.isDead()).toBe(true);
    expect(reasonOf(states.at(-1))).toContain('sequence break');

    // …and the browser's close event (1000 for our own close()) must not
    // overwrite it — that masking is what turned a diagnosable failure
    // into a mysterious "closed (1000)".
    transport.simulateClose(1000);
    expect(reasonOf(states.at(-1))).toContain('sequence break');
  });
});
