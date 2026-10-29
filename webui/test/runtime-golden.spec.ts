/**
 * The browser side of the preview-op drift guard (web.md §9, gui.md §7): the same golden fixture the
 * JVM test regenerates (fixtures/protocol/editor-runtime-v1.json) holds the exact frames of
 * `runtime.openGui` (a window still opening, a refusal, a session with no approved player) and of
 * `runtime.previewTarget` (online and offline). The SPA's wrappers must send those requests byte for
 * byte and decode those replies, so the JVM and the studio cannot disagree about the preview.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ops, RpcError, unwrap } from '../src/lib/api';
import { canonicalJson } from '../src/lib/protocol/wire';
import type { Response } from '../src/lib/protocol/wire';

interface Frame {
  op: string;
  id: string;
  payload: Record<string, any>;
}

interface Entry {
  request: Frame;
  response: Response;
}

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-runtime-v1.json', import.meta.url), 'utf8'),
) as {
  openGui: { pending: Entry; refused: Entry; noPlayer: Entry };
  previewTarget: { online: Entry; offline: Entry };
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

describe('golden preview frames (fixtures/protocol/editor-runtime-v1.json)', () => {
  it('the openGui wrapper sends the golden request byte for byte', async () => {
    const { request, response } = fixture.openGui.pending;
    const { sent, channel } = recordingChannel(response);

    await ops.openGui(channel, request.payload.id as string);

    expect(sent).toHaveLength(1);
    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
  });

  it('a window still opening is acknowledged as queued', () => {
    const data = unwrap(fixture.openGui.pending.response) as { queued: boolean };
    expect(data.queued).toBe(true);
  });

  it('a refused open surfaces the code the server gave it', () => {
    const refusal = fixture.openGui.refused.response;
    expect(() => unwrap(refusal)).toThrow(RpcError);
    try {
      unwrap(refusal);
    } catch (error) {
      expect((error as RpcError).code).toBe('MTR-0009');
      expect((error as RpcError).message).toContain('not live');
    }
  });

  it('a session with no approved player is refused with the hint that says what to do', () => {
    const refusal = fixture.openGui.noPlayer.response;
    expect(refusal.ok).toBe(false);
    if (refusal.ok) return;
    expect(refusal.error.code).toBe('MTR-0009');
    expect(refusal.error.hint).toContain('/materione editor');
  });

  it('the previewTarget wrapper sends the golden request byte for byte', async () => {
    const { request, response } = fixture.previewTarget.online;
    const { sent, channel } = recordingChannel(response);

    await ops.previewTarget(channel);

    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
  });

  it('decodes an online approved player and an offline one', () => {
    const online = unwrap(fixture.previewTarget.online.response) as { online: boolean; player?: string };
    expect(online).toEqual({ online: true, player: 'Probe' });

    const offline = unwrap(fixture.previewTarget.offline.response) as { online: boolean; player?: string };
    expect(offline).toEqual({ online: false });
  });
});
