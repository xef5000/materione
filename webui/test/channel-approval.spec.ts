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

describe('editor approval state', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('shows the approval nonce before the server approves the handshake', async () => {
    const plugin = await ed25519FromRaw(fixturePluginSeed(), fixturePluginPub());
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      json: async () => fixtureBlobEnvelope(),
    }));
    const transport = new FakeServerTransport(plugin.privateKey);
    const send = transport.send.bind(transport);
    let hello: string | undefined;
    vi.spyOn(transport, 'send').mockImplementation((text) => {
      hello = text;
    });
    const states: ChannelState[] = [];
    const opening = EditorChannel.open(
      'http://test',
      null,
      fixtureSessionId(),
      fixtureWrappingKey(),
      'vitest',
      { transport, onState: (state) => states.push(state) },
    );

    try {
      await expect.poll(() => states.find((state) => state.kind === 'awaiting-approval'))
        .toEqual({ kind: 'awaiting-approval', noncePrefix: expect.stringMatching(/^[0-9a-f]{8}$/) });
      expect(states.some((state) => state.kind === 'active')).toBe(false);
    } finally {
      if (hello !== undefined) send(hello);
    }
    const channel = await opening;
    expect(states.at(-1)?.kind).toBe('active');
    channel.close();
  });
});
