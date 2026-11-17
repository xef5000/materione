// An RPC whose reply never arrives must reject, not hang the UI forever
// (relay latency epic, #422). A late reply after the deadline is ignored.
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

describe('EditorChannel.call deadline', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('rejects after the timeout and ignores the late reply', async () => {
    const plugin = await ed25519FromRaw(fixturePluginSeed(), fixturePluginPub());
    vi.stubGlobal('fetch', async () => ({ ok: true, json: async () => fixtureBlobEnvelope() }));
    const transport = new FakeServerTransport(plugin.privateKey);
    const channel = await EditorChannel.open(
      'http://test', null, fixtureSessionId(), fixtureWrappingKey(), 'vitest', { transport },
    );
    const states: string[] = [];
    channel.onState((state) => states.push(state.kind));

    await expect(channel.call('browse.get', { pack: 'p', path: 'a.yml' }, 50))
      .rejects.toThrow('browse.get timed out');

    const { body } = await transport.open(1);
    await transport.respond({ op: body.op, id: body.id, ok: true, data: {} });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(states).not.toContain('closed');
  });
});
