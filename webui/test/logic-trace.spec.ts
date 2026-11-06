/**
 * The live trace on the studio side (logic.md section 7.6, 32): the golden frames the JVM regenerates
 * (fixtures/protocol/editor-logic-trace-v1.json) hold the subscribe and unsubscribe exchange and one pushed frame; the
 * SPA's wrappers must send the golden requests byte for byte and read the golden push. Then the mapping of step paths
 * onto nodes and wires, the folding of a stream into the run on screen, and the toggle's life cycle against a fake
 * connection.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { parse } from 'yaml';
import type { RpcChannel } from '../src/lib/api';
import { Signal, type PushFrame } from '../src/lib/events';
import { project } from '../src/lib/logic/project';
import { logicTraceSubscribe, logicTraceUnsubscribe } from '../src/lib/logic/server';
import {
  applyEntries, emptyTrace, hasRun, litEdges, nodeOfPath, parseTracePush, traceResourceOf, type TraceEntry,
} from '../src/lib/logic/trace';
import { TraceState, TRACE_FADE_MS } from '../src/lib/logic/trace.svelte';
import { blockOf } from '../src/lib/logic/tree';
import { canonicalJson } from '../src/lib/protocol/wire';
import type { Response } from '../src/lib/protocol/wire';

interface Pair {
  request: { op: string; id: string; payload: Record<string, any> };
  response: Response;
}

const fixture = JSON.parse(
  readFileSync(new URL('../../fixtures/protocol/editor-logic-trace-v1.json', import.meta.url), 'utf8'),
) as {
  meta: { file: string; version: number };
  subscribe: Pair;
  subscribeAll: Pair;
  subscribeRefused: Pair;
  push: { op: string; id: string; payload: Record<string, unknown> };
  unsubscribe: Pair;
};

/** A shop rule: a message, a branch that pays or refuses, and a stop, plus a named flow the rule calls. */
const SHOP = blockOf(parse(`
r:
  behavior:
    - on: gui.click
      do:
        - message: hello
        - if: "player.level > 5"
          then:
            - give: sword
            - take_money: 10
          else:
            - message: too poor
        - call: bonus
        - stop
    - on: gui.open
      do:
        - sound: block.chest.open
  flows:
    bonus:
      - give: apple
      - message: thanks
`).r);
const graph = project(SHOP);

const entry = (path: string, kind: string, detail = '', run = 1): TraceEntry => ({ run, rule: 'demo:shop#0', path, kind, detail, at: 1 });

function recordingChannel(answer: Response) {
  const sent: { op: string; payload: Record<string, unknown> }[] = [];
  const channel = {
    call: async (op: string, payload: Record<string, unknown> = {}): Promise<Response> => {
      sent.push({ op, payload });
      return answer;
    },
    blob: { sessionId: 'x', protocolVersions: [1], capabilities: ['gui', 'logic'], pluginSigningPubKey: new Uint8Array(32) },
  };
  return { sent, channel: channel as unknown as RpcChannel };
}

describe('golden live trace frames (fixtures/protocol/editor-logic-trace-v1.json)', () => {
  it('pins the frame file it was generated as', () => {
    expect(fixture.meta.file).toBe('editor-logic-trace-v1.json');
    expect(fixture.meta.version).toBe(1);
  });

  it('logic.trace.subscribe: the wrapper sends the golden request and reads the lease', async () => {
    const { request, response } = fixture.subscribe;
    const { sent, channel } = recordingChannel(response);

    const subscribed = await logicTraceSubscribe(channel, request.payload.resource);

    expect(sent[0]!.op).toBe(request.op);
    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(subscribed).toMatchObject({ resource: 'demo:shop', scope: 'player', leaseSeconds: 300 });
    expect(subscribed.limits.maxBatch).toBeGreaterThan(0);
  });

  it('logic.trace.subscribe for every player sends allPlayers exactly as the golden does', async () => {
    const { request, response } = fixture.subscribeAll;
    const { sent, channel } = recordingChannel(response);

    const subscribed = await logicTraceSubscribe(channel, request.payload.resource, true);

    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(subscribed.scope).toBe('all');
  });

  it('a refused subscription rejects with the server\'s code', async () => {
    const { channel } = recordingChannel(fixture.subscribeRefused.response);
    await expect(logicTraceSubscribe(channel, 'bad')).rejects.toMatchObject({ code: 'MTR-0008' });
  });

  it('logic.trace.unsubscribe: the wrapper sends the golden request and counts what closed', async () => {
    const { request, response } = fixture.unsubscribe;
    const { sent, channel } = recordingChannel(response);

    const closed = await logicTraceUnsubscribe(channel, request.payload.resource);

    expect(canonicalJson(sent[0]!.payload)).toBe(canonicalJson(request.payload));
    expect(closed).toBe(1);
    expect(await logicTraceUnsubscribe(recordingChannel(response).channel)).toBe(1);
  });

  it('push.logic.trace: the golden frame parses to the entries the server sent', () => {
    expect(fixture.push.op).toBe('push.logic.trace');
    const push = parseTracePush(fixture.push.payload)!;
    expect(push.resource).toBe('demo:shop');
    expect(push.entries.map((item) => item.kind)).toEqual(['run-started', 'ran', 'branch', 'failed', 'skipped', 'run-ended']);
    expect(push.entries[3]).toMatchObject({ path: 'behavior.0.do.1.then.0', detail: 'not enough money', run: 11 });
    expect(push.dropped).toBe(0);
  });
});

describe('reading a push', () => {
  it('drops a frame it cannot read whole, and entries it cannot read one by one', () => {
    expect(parseTracePush(null)).toBeNull();
    expect(parseTracePush({ resource: 3, entries: [] })).toBeNull();
    expect(parseTracePush({ resource: 'a:b' })).toBeNull();
    const push = parseTracePush({ resource: 'a:b', seq: 1, dropped: 4, entries: [{ path: 'x' }, { path: 'behavior.0', kind: 'ran' }, 7] })!;
    expect(push.entries).toHaveLength(1);
    expect(push.dropped).toBe(4);
  });

  it('cuts a very long detail, whatever the server sent', () => {
    const push = parseTracePush({ resource: 'a:b', entries: [{ path: 'p', kind: 'failed', detail: 'x'.repeat(5_000) }] })!;
    expect(push.entries[0]!.detail.length).toBeLessThanOrEqual(400);
  });

  it('names the GUI the way the server does', () => {
    expect(traceResourceOf({ pack: 'shop', id: 'shop:main' })).toBe('shop:main');
    expect(traceResourceOf({ pack: 'shop', id: 'main' })).toBe('shop:main');
  });
});

describe('step paths onto nodes (the scheme the diagnostics use)', () => {
  it('a path names the deepest node that is the path or a prefix of it', () => {
    expect(nodeOfPath(graph, 'behavior.0')).toBe('behavior.0');
    expect(nodeOfPath(graph, 'behavior.0.do.1')).toBe('behavior.0.do.1');
    expect(nodeOfPath(graph, 'behavior.0.do.1.then.1')).toBe('behavior.0.do.1.then.1');
    expect(nodeOfPath(graph, 'behavior.0.do.1.if')).toBe('behavior.0.do.1');
    expect(nodeOfPath(graph, 'behavior.0.if')).toBe('behavior.0');
    expect(nodeOfPath(graph, 'flows.bonus.1')).toBe('flows.bonus.1');
    expect(nodeOfPath(graph, 'behavior.9.do.0')).toBeNull();
    expect(nodeOfPath(graph, 'behavior.1')).toBe('behavior.1'); // not behavior.10's prefix confusion
  });
});

describe('folding a stream into the run on screen', () => {
  const run = [
    entry('behavior.0', 'run-started'),
    entry('behavior.0.do.0', 'ran'),
    entry('behavior.0.do.1', 'branch', 'then'),
    entry('behavior.0.do.1.then.0', 'ran'),
    entry('behavior.0.do.1.then.1', 'failed', 'not enough money'),
    entry('behavior.0.do.2', 'called', 'bonus'),
    entry('flows.bonus.0', 'ran'),
    entry('flows.bonus.1', 'ran'),
    entry('behavior.0.do.3', 'ran', 'stop'),
    entry('behavior.0', 'run-ended', 'STOPPED: stop'),
  ];

  it('lights the nodes a run reached, in the order it reached them, and the wires that fed them', () => {
    const view = applyEntries(emptyTrace(), graph, run);

    const states = Object.fromEntries([...view.nodes].map(([id, node]) => [id, node.state]));
    expect(states).toEqual({
      'behavior.0': 'ran',
      'behavior.0.do.0': 'ran',
      'behavior.0.do.1': 'ran',
      'behavior.0.do.1.then.0': 'ran',
      'behavior.0.do.1.then.1': 'failed',
      'behavior.0.do.2': 'ran',
      'flows.bonus.0': 'ran',
      'flows.bonus.1': 'ran',
      'behavior.0.do.3': 'ran',
    });
    // the branch not taken, and the other rule, are untouched
    expect(view.nodes.has('behavior.0.do.1.else.0')).toBe(false);
    expect(view.nodes.has('behavior.1')).toBe(false);
    const orders = [...view.nodes.values()].map((node) => node.order);
    expect(orders).toEqual([...orders].sort((a, b) => a - b));
    expect(new Set(orders).size).toBe(orders.length);

    const lit = litEdges(graph, view);
    expect([...lit.keys()].sort()).toEqual([
      'behavior.0.do.0|after>behavior.0.do.1',
      'behavior.0.do.1.then.0|after>behavior.0.do.1.then.1',
      'behavior.0.do.1|after>behavior.0.do.2',
      'behavior.0.do.1|then>behavior.0.do.1.then.0',
      'behavior.0.do.2|after>behavior.0.do.3',
      'behavior.0|out>behavior.0.do.0',
      'flows.bonus.0|after>flows.bonus.1',
      'flows.bonus|out>flows.bonus.0',
    ]);
    // a wire is lit in the order its target was reached
    expect(lit.get('behavior.0|out>behavior.0.do.0')).toBeLessThan(lit.get('behavior.0.do.1|then>behavior.0.do.1.then.0')!);
  });

  it('a failed action keeps its reason on the node, and a later success never hides it', () => {
    const view = applyEntries(emptyTrace(), graph, [
      entry('behavior.0.do.1.then.1', 'failed', 'not enough money'),
      entry('behavior.0.do.1.then.1', 'ran'),
    ]);
    expect(view.nodes.get('behavior.0.do.1.then.1')).toMatchObject({ state: 'failed', detail: 'not enough money' });
  });

  it('a skipped step is marked but its wire stays dark', () => {
    const view = applyEntries(emptyTrace(), graph, [entry('behavior.0.do.0', 'skipped', 'chance 0.5')]);
    expect(view.nodes.get('behavior.0.do.0')).toMatchObject({ state: 'skipped', detail: 'chance 0.5' });
    expect(litEdges(graph, view).size).toBe(0);
  });

  it('a run that parks is waiting, and an aborted run says so on its rule', () => {
    const parked = applyEntries(emptyTrace(), graph, [entry('behavior.0.do.0', 'suspended', 'wait 20')]);
    expect(parked.nodes.get('behavior.0.do.0')!.state).toBe('waiting');
    const aborted = applyEntries(emptyTrace(), graph, [entry('behavior.0', 'run-ended', 'ABORTED: 1000 steps')]);
    expect(aborted.nodes.get('behavior.0')).toMatchObject({ state: 'failed', detail: 'ABORTED: 1000 steps' });
    const finished = applyEntries(emptyTrace(), graph, [entry('behavior.0', 'run-ended', 'FINISHED')]);
    expect(finished.nodes.size).toBe(0);
  });

  it('the next run of a rule clears that rule\'s previous run, not another rule\'s', () => {
    let view = applyEntries(emptyTrace(), graph, [
      entry('behavior.0', 'run-started', '', 1), entry('behavior.0.do.0', 'ran', '', 1),
      entry('behavior.1', 'run-started', '', 2), entry('behavior.1.do.0', 'ran', '', 2),
    ]);
    view = applyEntries(view, graph, [entry('behavior.0', 'run-started', '', 3)]);

    expect([...view.nodes.keys()].sort()).toEqual(['behavior.0', 'behavior.1', 'behavior.1.do.0']);
    expect(view.run).toBe(3);
  });

  it('counts what the server dropped and ignores a path no node has', () => {
    const view = applyEntries(emptyTrace(), graph, [entry('behavior.7.do.0', 'ran')], 12);
    expect(view.dropped).toBe(12);
    expect(hasRun(view)).toBe(false);
    expect(applyEntries(view, graph, [], 3).dropped).toBe(15);
  });
});

describe('the toggle', () => {
  function setup(answer: Response = fixture.subscribe.response) {
    const pushes = new Signal<PushFrame>();
    const { sent, channel } = recordingChannel(answer);
    const state = new TraceState({ resource: 'demo:shop', graph: () => graph, pushes });
    return { pushes, sent, channel, state };
  }

  it('is off by default and asks the server for nothing until it is turned on', async () => {
    const { state, sent, channel } = setup();
    await state.attach(channel);
    expect(state.enabled).toBe(false);
    expect(state.status).toBe('off');
    expect(sent).toEqual([]);
  });

  it('subscribes to its own GUI, draws the pushes of that GUI only, and lets go when turned off', async () => {
    const { state, sent, channel, pushes } = setup();
    await state.attach(channel);

    await state.set(true);
    expect(state.status).toBe('live');
    expect(sent.map((call) => call.op)).toEqual(['logic.trace.subscribe']);
    expect(sent[0]!.payload).toEqual({ resource: 'demo:shop' });

    pushes.emit({ op: 'push.logic.trace', payload: { resource: 'demo:other', entries: [entry('behavior.0', 'run-started')] } });
    expect(state.showing).toBe(false);
    pushes.emit({ op: 'push.logic.trace', payload: { resource: 'demo:shop', seq: 1, entries: [entry('behavior.0', 'run-started'), entry('behavior.0.do.0', 'ran')] } });
    expect(state.showing).toBe(true);
    expect(state.of('behavior.0.do.0')!.state).toBe('ran');
    expect(state.lit.has('behavior.0|out>behavior.0.do.0')).toBe(true);

    await state.set(false);
    expect(sent.map((call) => call.op)).toEqual(['logic.trace.subscribe', 'logic.trace.unsubscribe']);
    expect(state.showing).toBe(false);
    pushes.emit({ op: 'push.logic.trace', payload: { resource: 'demo:shop', entries: [entry('behavior.0', 'run-started')] } });
    expect(state.showing).toBe(false); // it stopped listening
  });

  it('fades the run away once the clicking stops', async () => {
    vi.useFakeTimers();
    try {
      const { state, channel, pushes } = setup();
      await state.attach(channel);
      await state.set(true);
      pushes.emit({ op: 'push.logic.trace', payload: { resource: 'demo:shop', dropped: 3, entries: [entry('behavior.0', 'run-started')] } });
      expect(state.showing).toBe(true);
      expect(state.view.dropped).toBe(3);

      vi.advanceTimersByTime(TRACE_FADE_MS - 1);
      expect(state.showing).toBe(true);
      vi.advanceTimersByTime(2);
      expect(state.showing).toBe(false);
      expect(state.view.dropped).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('renews its lease while it is on', async () => {
    vi.useFakeTimers();
    try {
      const { state, channel, sent } = setup();
      await state.attach(channel);
      await state.set(true);
      await vi.advanceTimersByTimeAsync(300 * 1000 * 0.4 + 1);
      expect(sent.filter((call) => call.op === 'logic.trace.subscribe')).toHaveLength(2);
      await state.set(false);
      await vi.advanceTimersByTimeAsync(600_000);
      expect(sent.filter((call) => call.op === 'logic.trace.subscribe')).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('says why when the server refuses, and when the server has no logic API', async () => {
    const refused: Response = { op: 'logic.trace.subscribe', id: 'r', ok: false, error: { code: 'MTR-0089', message: 'the server already holds 8 live trace subscriptions' } };
    const full = setup(refused);
    await full.state.attach(full.channel);
    await full.state.set(true);
    expect(full.state.status).toBe('failed');
    expect(full.state.message).toContain('8 live trace subscriptions');
    expect(full.state.enabled).toBe(true);

    const bare = setup();
    await bare.state.attach({ call: async () => fixture.subscribe.response } as unknown as RpcChannel); // no blob: no capability
    await bare.state.set(true);
    expect(bare.state.status).toBe('unavailable');
  });

  it('leaving the tab lets the lease go and coming back takes it again, keeping the author\'s choice', async () => {
    const { state, sent, channel } = setup();
    await state.attach(channel);
    await state.set(true);

    state.detach();
    await Promise.resolve();
    await Promise.resolve();
    expect(state.enabled).toBe(true);
    expect(sent.map((call) => call.op)).toEqual(['logic.trace.subscribe', 'logic.trace.unsubscribe']);

    await state.attach(channel);
    expect(state.status).toBe('live');
    expect(sent.map((call) => call.op)).toEqual(['logic.trace.subscribe', 'logic.trace.unsubscribe', 'logic.trace.subscribe']);
  });

  it('a lost connection says so and the trace resumes on the next one', async () => {
    const { state, channel } = setup();
    await state.attach(channel);
    await state.set(true);

    await state.attach(null);
    expect(state.status).toBe('failed');
    expect(state.message).toContain('Disconnected');

    await state.attach(channel);
    expect(state.status).toBe('live');
  });

  it('has nothing to trace on a canvas with no GUI id', async () => {
    const state = new TraceState({ resource: undefined, graph: () => graph, pushes: new Signal<PushFrame>() });
    expect(state.available).toBe(false);
  });
});
