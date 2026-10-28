/**
 * The browser side of the op-level drift guard (web.md §9): the same golden
 * fixture the JVM test regenerates (fixtures/protocol/editor-ops-v1.json) holds
 * the exact frames of `authoring.setFields` carrying a paint asset, and of the
 * same save after its revision went stale. The SPA's wrapper must send that
 * request byte for byte and decode that response; the JVM must keep producing it.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { guiComponentsOf, ops, RpcError, unwrap } from '../src/lib/api';
import { canonicalJson } from '../src/lib/protocol/wire';
import type { Response } from '../src/lib/protocol/wire';

interface Frame {
  op: string;
  id: string;
  payload: Record<string, any>;
}

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-ops-v1.json', import.meta.url), 'utf8'),
) as {
  setFields: {
    request: Frame;
    response: Response;
    conflict: { request: Frame; response: Response };
  };
};

/** A channel that records what the SPA sends and answers with a fixed frame. */
function recordingChannel(answer: Response) {
  const sent: { op: string; payload: Record<string, unknown> }[] = [];
  return {
    sent,
    channel: {
      call: async (op: string, payload: Record<string, unknown> = {}): Promise<Response> => {
        sent.push({ op, payload });
        return answer;
      },
    },
  };
}

describe('golden op frames (fixtures/protocol/editor-ops-v1.json)', () => {
  it('the setFields wrapper sends the golden request byte for byte', async () => {
    const { request } = fixture.setFields;
    const { sent, channel } = recordingChannel(fixture.setFields.response);
    const { pack, path, rev, edits, assets } = request.payload;

    await ops.setFields(channel, pack, path, rev, edits, assets);

    expect(sent).toHaveLength(1);
    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
  });

  it('a save without binaries sends no assets key at all', async () => {
    const { sent, channel } = recordingChannel(fixture.setFields.response);
    const { pack, path, rev, edits } = fixture.setFields.request.payload;

    await ops.setFields(channel, pack, path, rev, edits);

    expect(sent[0]!.payload).not.toHaveProperty('assets');
    expect(sent[0]!.payload).toEqual({ pack, path, rev, edits });
  });

  it('decodes the server response: the revision advances and the document comes back', () => {
    const data = unwrap(fixture.setFields.response) as { rev: number; raw: string; parsed: any };
    expect(data.rev).toBe(2);
    expect(data.raw).toContain('title: Market');
    expect(data.parsed.shop.title).toBe('Market');
  });

  it('surfaces the stale-revision refusal with the code the server gave it', () => {
    const refusal = fixture.setFields.conflict.response;
    expect(() => unwrap(refusal)).toThrow(RpcError);
    try {
      unwrap(refusal);
    } catch (error) {
      expect((error as RpcError).code).toBe('MTR-0034');
    }
  });

  it('pins the paint bytes as standard base64 of a PNG signature at an assets/ path', () => {
    const [asset] = fixture.setFields.request.payload.assets as { path: string; data: string }[];
    const bytes = atob(asset!.data);
    expect(bytes.charCodeAt(0)).toBe(0x89);
    expect(bytes.slice(1, 4)).toBe('PNG');
    expect(asset!.path).toBe('assets/textures/gui/shop/art.png');
  });
});

describe('the gui-components registry as the studio reads it', () => {
  it('keeps well-formed registry entries and drops the rest rather than guessing', () => {
    const panel = {
      id: 'materione:panel',
      displayName: 'Panel',
      description: 'A raised frame',
      parameters: [],
      target: 'raster',
      registeredBy: 'materione',
    };
    const payload = {
      guiComponents: [
        panel,
        { ...panel, id: 'example:nameless', displayName: undefined },
        { ...panel, id: 'example:warp', target: 'portal' },
        'not an entry',
      ],
    };
    expect(guiComponentsOf(payload).map((entry) => entry.id)).toEqual(['materione:panel']);
    expect(guiComponentsOf({})).toEqual([]);
    expect(guiComponentsOf({ guiComponents: 'nope' })).toEqual([]);
  });
});
