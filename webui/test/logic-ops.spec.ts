/**
 * The browser side of the logic op drift guard (web.md §9, logic.md §8): the golden frames the JVM
 * regenerates (fixtures/protocol/editor-logic-v1.json) hold one request and reply per logic op. The SPA's
 * wrappers in src/lib/logic/server.ts must send each golden request byte for byte, and decode each golden
 * reply the way the server meant it; the JVM test keeps producing the same frames.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { RpcError, unwrap, type RpcChannel } from '../src/lib/api';
import {
  logicApiAvailable,
  logicEdit,
  logicGet,
  logicInstantiate,
  logicValidate,
  logicVocabulary,
  type LogicEdit,
  type LogicView,
} from '../src/lib/logic/server';
import { canonicalJson } from '../src/lib/protocol/wire';
import type { Response } from '../src/lib/protocol/wire';

interface Frame {
  op: string;
  id: string;
  payload: Record<string, any>;
}

interface Pair {
  request: Frame;
  response: Response;
}

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-logic-v1.json', import.meta.url), 'utf8'),
) as {
  meta: { file: string; version: number };
  vocabulary: Pair;
  get: Pair;
  edit: Pair;
  editConflict: Pair;
  editRefused: Pair;
  validate: Pair;
  instantiate: Pair;
};

/** A channel that records what the SPA sends and answers with a fixed frame. */
function recordingChannel(answer: Response) {
  const sent: { op: string; payload: Record<string, unknown> }[] = [];
  const channel = {
    sent,
    call: async (op: string, payload: Record<string, unknown> = {}): Promise<Response> => {
      sent.push({ op, payload });
      return answer;
    },
  };
  return { sent, channel: channel as unknown as RpcChannel & { sent: typeof sent } };
}

/** The edits of the golden edit frame, typed as the wrapper takes them. */
const editsOf = (pair: Pair): LogicEdit[] => pair.request.payload.edits as LogicEdit[];

describe('golden logic op frames (fixtures/protocol/editor-logic-v1.json)', () => {
  it('pins the frame file it was generated as', () => {
    expect(fixture.meta.file).toBe('editor-logic-v1.json');
    expect(fixture.meta.version).toBe(1);
  });

  it('logic.vocabulary: the wrapper sends the empty payload the golden holds', async () => {
    const { sent, channel } = recordingChannel(fixture.vocabulary.response);
    await logicVocabulary(channel);
    expect(sent).toHaveLength(1);
    expect(sent[0]!.op).toBe(fixture.vocabulary.request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(fixture.vocabulary.request.payload));
  });

  it('logic.get: the wrapper sends the golden request and decodes the typed view', async () => {
    const { request, response } = fixture.get;
    const { sent, channel } = recordingChannel(response);

    const view: LogicView = await logicGet(channel, request.payload.pack, request.payload.path);

    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(view.rules).toHaveLength(1);
    expect(view.rules[0]!.on).toBe('click:right');
    expect(view.rules[0]!.steps[0]!.kind).toBe('give');
  });

  it('logic.edit: the wrapper sends the golden edits at the golden revision and decodes the view', async () => {
    const { request, response } = fixture.edit;
    const { sent, channel } = recordingChannel(response);
    const { pack, path, rev } = request.payload;

    const view = await logicEdit(channel, pack, path, rev, editsOf(fixture.edit));

    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(view.rev).toBe(Number(rev) + 1);
    expect(view.rules[0]!.node).toEqual({ x: 30, y: 40 });
    expect(view.rules[0]!.steps[0]!.value).toBe('front');
  });

  it('logic.edit at a stale revision surfaces the conflict code the server gave it', () => {
    expect(() => unwrap(fixture.editConflict.response)).toThrow(RpcError);
    try {
      unwrap(fixture.editConflict.response);
    } catch (error) {
      expect((error as RpcError).code).toBe('MTR-0034');
    }
  });

  it('logic.edit refused: the step path is the error target, and the request is the golden one', async () => {
    const { request, response } = fixture.editRefused;
    const { sent, channel } = recordingChannel(response);
    const { pack, path, rev } = request.payload;

    await expect(logicEdit(channel, pack, path, rev, editsOf(fixture.editRefused))).rejects.toBeInstanceOf(RpcError);
    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    try {
      await logicEdit(channel, pack, path, rev, editsOf(fixture.editRefused));
    } catch (error) {
      expect((error as RpcError).code).toBe('MTR-0057');
    }
  });

  it('logic.validate: the wrapper sends the golden draft and reads its step-path diagnostic', async () => {
    const { request, response } = fixture.validate;
    const { sent, channel } = recordingChannel(response);

    const diagnostics = await logicValidate(channel, request.payload as Parameters<typeof logicValidate>[1]);

    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]!.field).toEqual(['behavior', '0', 'do', '1']);
    expect(diagnostics[0]!.code).toBe('MTR-0057');
  });

  it('logic.instantiate: the wrapper sends the golden recipe and surfaces the unknown-recipe refusal', async () => {
    const { request, response } = fixture.instantiate;
    const { sent, channel } = recordingChannel(response);

    await expect(logicInstantiate(channel, request.payload.recipe, request.payload.params)).rejects.toBeInstanceOf(
      RpcError,
    );
    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(() => unwrap(response)).toThrow(/unknown recipe 'pay_to_receive'/);
  });
});

describe('the logic capability gate', () => {
  it('answers only when the session blob advertises logic', () => {
    const withLogic = { blob: { capabilities: ['gui', 'logic'] } } as unknown as RpcChannel;
    const withoutLogic = { blob: { capabilities: ['gui'] } } as unknown as RpcChannel;
    expect(logicApiAvailable(withLogic)).toBe(true);
    expect(logicApiAvailable(withoutLogic)).toBe(false);
    expect(logicApiAvailable(null)).toBe(false);
  });
});
