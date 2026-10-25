// Version-skew guardrails (web.md §4.2): the panel negotiates the hello's
// protocol version against the blob's advertised list instead of assuming
// its own, and reads the blob's capability list to hide unsupported UI.
// The skew matrix this pins:
//   new panel + old jar / old panel + new jar → connect on the common version
//   disjoint lists → readable error before any socket opens
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ed25519FromRaw } from '../src/lib/protocol/crypto';
import {
  CURRENT_CAPABILITIES,
  EditorChannel,
  SUPPORTED_PROTOCOL_VERSIONS,
  hasCapability,
  negotiateProtocolVersion,
} from '../src/lib/protocol/session';
import {
  FakeServerTransport,
  fixtureBlobEnvelope,
  fixturePluginPub,
  fixturePluginSeed,
  fixtureSessionId,
  fixtureWrappingKey,
} from './helpers/fake-server-transport';
import { readFileSync } from 'node:fs';

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-protocol-v1.json', import.meta.url), 'utf8'),
) as Record<string, any>;

describe('protocol version negotiation', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('the golden fixture pins the panel vocabulary to the jar vocabulary', () => {
    expect(fixture.sessionBlob.plaintext.capabilities).toEqual([...CURRENT_CAPABILITIES]);
    expect(fixture.sessionBlob.plaintext.protocolVersions).toEqual([
      ...SUPPORTED_PROTOCOL_VERSIONS,
    ]);
  });

  it('picks the highest version both sides support', () => {
    expect(negotiateProtocolVersion({ protocolVersions: [1] })).toBe(1);
    // jar newer than the panel: the panel's own list bounds the pick
    expect(negotiateProtocolVersion({ protocolVersions: [1, 2] })).toBe(1);
    // panel newer than the jar: the blob's list bounds the pick
    const panelSpeaksTwo = [...SUPPORTED_PROTOCOL_VERSIONS, 2];
    const original = [...SUPPORTED_PROTOCOL_VERSIONS];
    SUPPORTED_PROTOCOL_VERSIONS.splice(0, original.length, ...panelSpeaksTwo);
    try {
      expect(negotiateProtocolVersion({ protocolVersions: [1] })).toBe(1);
      expect(negotiateProtocolVersion({ protocolVersions: [1, 2] })).toBe(2);
    } finally {
      SUPPORTED_PROTOCOL_VERSIONS.splice(0, panelSpeaksTwo.length, ...original);
    }
  });

  it('a disjoint list fails with a readable message before any socket opens', async () => {
    const plugin = await ed25519FromRaw(fixturePluginSeed(), fixturePluginPub());
    const transport = new FakeServerTransport(plugin.privateKey);

    // A blob whose advertised list shares nothing with the panel's — the
    // future where the jar dropped a version the cached panel still speaks.
    await expect(
      EditorChannel.open(
        'http://test',
        null,
        fixtureSessionId(),
        fixtureWrappingKey(),
        'vitest',
        {
          transport,
          blob: {
            sessionId: fixtureSessionId(),
            protocolVersions: [7],
            pluginSigningPubKey: fixturePluginPub(),
          },
        },
      ),
    ).rejects.toThrow(/no common protocol version.*\[7\]/);
    expect(transport.sent).toEqual([]); // nothing hit the wire
  });

  it('the hello carries the negotiated version, not a hardcoded one', async () => {
    const plugin = await ed25519FromRaw(fixturePluginSeed(), fixturePluginPub());
    vi.stubGlobal('fetch', async () => ({
      ok: true,
      json: async () => fixtureBlobEnvelope(),
    }));
    const transport = new FakeServerTransport(plugin.privateKey);
    await EditorChannel.open(
      'http://test',
      null,
      fixtureSessionId(),
      fixtureWrappingKey(),
      'vitest',
      { transport },
    );
    const hello = JSON.parse(transport.sent[0]!) as Record<string, unknown>;
    expect(hello.protocolVersion).toBe(1);
  });
});

describe('capability gating', () => {
  const base = {
    sessionId: fixtureSessionId(),
    protocolVersions: [1],
    pluginSigningPubKey: fixturePluginPub(),
  };

  it('treats an absent list as full support (pre-capabilities jar)', () => {
    expect(hasCapability({ ...base }, 'glyphs')).toBe(true);
    expect(hasCapability({ ...base }, 'something-future')).toBe(true);
  });

  it('checks membership when the jar advertises', () => {
    const blob = { ...base, capabilities: ['glyphs'] };
    expect(hasCapability(blob, 'glyphs')).toBe(true);
    expect(hasCapability(blob, 'text-effects')).toBe(false);
  });

  it('an explicitly empty list supports nothing', () => {
    expect(hasCapability({ ...base, capabilities: [] }, 'glyphs')).toBe(false);
  });
});
