/**
 * The agent loop and its one tool (issue 348; codemode.md sections 2.2, 4.2, 5.6, 6), with every dependency a fake: what the
 * tool reports for each way a program can end, that its result is bounded, that it has one parameter and refuses any other,
 * the repair budget and the turn ceiling, refusals and usage from the provider, and the scripted provider's replay. The real
 * checker, sandbox and proposal store run the recorded conversation in `codemode-chat-bloodfang.spec.ts`.
 */
import { describe, expect, it } from 'vitest';
import { disclosure } from '../src/lib/codemode/chat/disclosure';
import { MAX_MODEL_TURNS, REPAIR_BUDGET, runAgent, stopText, type LoopEvent } from '../src/lib/codemode/chat/loop';
import { addUsage, refusalText, RUN_PROGRAM, type ChatMessage, type ModelEvent, type ModelProvider, type ModelRequest } from '../src/lib/codemode/chat/provider';
import { createScriptedProvider, type Script, type ScriptedTurn } from '../src/lib/codemode/chat/scripted';
import { readInput, RUN_PROGRAM_TOOL, runProgram, TOOL_RESULT_MAX_CHARS, type ToolDeps, type ToolOutcome } from '../src/lib/codemode/chat/tool';
import type { Proposal } from '../src/lib/codemode/host';
import type { RunResult } from '../src/lib/codemode/sandbox';
import type { CheckResult } from '../src/lib/codemode/typecheck';
import { surfaceNamed } from './helpers/codemode';

const surface = surfaceNamed('plugin-current');

const passes: CheckResult = { ok: true, diagnostics: [], truncated: false, js: 'x', map: { version: 3, sources: [], names: [], mappings: '' } };

function ran(extra: Partial<RunResult> = {}): RunResult {
  return {
    ok: true,
    sdkVersion: '1',
    pack: 'demo',
    operations: [],
    sources: [],
    diagnostics: [],
    late: [],
    summary: null,
    logs: [],
    value: null,
    stats: { reads: 0, readBytes: 0, staged: 0, documents: 0 },
    metrics: { cpuMs: 0, engineWallMs: 0, setupMs: 0, totalMs: 0, hostReads: 0, hostReadBytes: 0, booted: false, bootMs: 0 },
    ...extra,
  };
}

const staged = (count = 1): Partial<RunResult> => ({
  operations: Array.from({ length: count }, (_, index) => ({ kind: 'create', type: 'items', id: `thing_${index}` })),
  sources: Array.from({ length: count }, (_, index) => ({ line: index + 1, column: 1 })),
});

function proposal(state: Proposal['state'], extra: Partial<Proposal> = {}): Proposal {
  return {
    id: 'p1',
    state,
    plan: null,
    pack: 'demo',
    program: '',
    summary: null,
    operations: [],
    sources: [],
    classification: null,
    documents: [{ path: 'items/thing_0.yml', kind: 'created' }],
    problems: [],
    needs: [],
    needsProposalApproval: false,
    transactionId: state === 'applied' ? 'tx-1' : null,
    revertible: state === 'applied',
    revertNote: null,
    revertedBy: null,
    appliedAt: null,
    before: {},
    ...extra,
  };
}

interface Spy {
  deps: ToolDeps;
  checks: string[];
  runs: number;
  proposed: { input: unknown; options: unknown }[];
}

function tool(options: { check?: CheckResult; run?: RunResult | Error; propose?: Proposal | Error } = {}): Spy {
  const spy: Spy = {
    checks: [],
    runs: 0,
    proposed: [],
    deps: {
      surface,
      check: async (program) => {
        spy.checks.push(program);
        return options.check ?? passes;
      },
      run: async () => {
        spy.runs++;
        if (options.run instanceof Error) throw options.run;
        return options.run ?? ran();
      },
      agent: {
        propose: async (input, proposeOptions) => {
          spy.proposed.push({ input, options: proposeOptions });
          if (options.propose instanceof Error) throw options.propose;
          return options.propose ?? proposal('applied');
        },
      },
    },
  };
  return spy;
}

const body = (outcome: ToolOutcome): Record<string, unknown> => JSON.parse(outcome.content) as Record<string, unknown>;

describe('run_program has one parameter', () => {
  it('declares exactly text, and nothing that applies or grants', () => {
    expect(RUN_PROGRAM_TOOL.name).toBe('run_program');
    expect(Object.keys(RUN_PROGRAM_TOOL.inputSchema.properties)).toEqual(['text']);
    expect(RUN_PROGRAM_TOOL.inputSchema.required).toEqual(['text']);
    expect(RUN_PROGRAM_TOOL.inputSchema.additionalProperties).toBe(false);
    expect(Object.isFrozen(RUN_PROGRAM_TOOL)).toBe(true);
    expect(Object.isFrozen(RUN_PROGRAM_TOOL.inputSchema.properties)).toBe(true);
  });

  it.each([
    [{ text: 'return 1', apply: true }, 'apply'],
    [{ text: 'return 1', grant: ['demo:items/a.yml'] }, 'grant'],
    [{ text: 'return 1', approve: 'p1' }, 'approve'],
    [{ text: 'return 1', autoApply: false }, 'autoApply'],
    [{ text: 'return 1', plan: 'plan-1' }, 'plan'],
  ])('refuses %j instead of ignoring the extra key', (input, key) => {
    const read = readInput(input);
    expect('error' in read && read.error).toContain(`'${key}'`);
  });

  it.each([null, 'return 1', 7, [], {}, { text: 3 }, { text: '   ' }])('refuses %j', (input) => {
    expect('error' in readInput(input)).toBe(true);
  });

  it('a call with an extra key checks nothing, runs nothing and proposes nothing', async () => {
    const spy = tool();
    const outcome = await runProgram({ text: 'return 1', apply: true }, spy.deps);
    expect(outcome.status).toBe('invalid');
    expect(outcome.failed).toBe(true);
    expect(spy.checks).toEqual([]);
    expect(spy.runs).toBe(0);
    expect(spy.proposed).toEqual([]);
  });

  it('passes the store only the program result and the program text: no plan, no auto-apply switch, no approval', async () => {
    const spy = tool({ run: ran(staged()) });
    await runProgram({ text: 'the program' }, spy.deps);
    expect(spy.proposed).toHaveLength(1);
    expect(spy.proposed[0]!.options).toEqual({ program: 'the program' });
    expect(Object.keys(spy.proposed[0]!.input as object).sort()).toEqual(['diagnostics', 'ok', 'operations', 'pack', 'sources', 'summary']);
  });
});

describe('what the tool reports', () => {
  it('a type error: nothing runs, the diagnostics and the missing primitive go back', async () => {
    const spy = tool({
      check: {
        ok: false,
        truncated: false,
        diagnostics: [
          { code: 'TS2339', severity: 'error', message: "Property 'commands' does not exist on type 'PackWriter'.", line: 3, column: 5, hint: 'report it', missingPrimitive: { member: 'commands', catalog: 'PackWriter', nearest: [] } },
        ],
      },
    });
    const outcome = await runProgram({ text: 'p.commands' }, spy.deps);
    expect(outcome.status).toBe('type-error');
    expect(outcome.failed).toBe(true);
    expect(spy.runs).toBe(0);
    expect(outcome.missingPrimitives).toEqual([{ member: 'commands', catalog: 'PackWriter', nearest: [] }]);
    const sent = body(outcome);
    expect(sent.status).toBe('type-error');
    expect(sent.note).toContain('missingPrimitive');
    expect(sent.diagnostics).toEqual([
      { phase: 'type-check', code: 'TS2339', severity: 'error', message: "Property 'commands' does not exist on type 'PackWriter'.", line: 3, column: 5, hint: 'report it', missingPrimitive: { member: 'commands', catalog: 'PackWriter', nearest: [] } },
    ]);
  });

  it('a checker that is ok but hands back no JavaScript is not run', async () => {
    const spy = tool({ check: { ok: true, diagnostics: [], truncated: false } });
    expect((await runProgram({ text: 'x' }, spy.deps)).status).toBe('type-error');
    expect(spy.runs).toBe(0);
  });

  it('a run the sandbox refused: nothing is proposed', async () => {
    const spy = tool({ run: ran({ ok: false, diagnostics: [{ code: 'SBX-0002', severity: 'error', message: 'ran too long', source: { line: 2, column: 1 } }] }) });
    const outcome = await runProgram({ text: 'while(true){}' }, spy.deps);
    expect(outcome.status).toBe('run-error');
    expect(outcome.failed).toBe(true);
    expect(spy.proposed).toEqual([]);
    expect(outcome.diagnostics[0]).toMatchObject({ phase: 'sandbox', code: 'SBX-0002', line: 2 });
  });

  it('a read: the value and the logs are the answer, nothing is proposed, and it does not count as failed', async () => {
    const spy = tool({ run: ran({ value: ['ruby_sword'], logs: ['looked'], stats: { reads: 1, readBytes: 40, staged: 0, documents: 0 } }) });
    const outcome = await runProgram({ text: 'return 1' }, spy.deps);
    expect(outcome.status).toBe('read');
    expect(outcome.failed).toBe(false);
    expect(outcome.proposalId).toBeNull();
    expect(spy.proposed).toEqual([]);
    expect(body(outcome).result).toMatchObject({ value: ['ruby_sword'], logs: ['looked'], staged: 0 });
  });

  it.each([
    ['applied', 'applied', false],
    ['waiting', 'waiting', false],
    ['refused', 'refused', true],
    ['stale', 'stale', true],
    ['failed', 'failed', true],
  ] as const)('a proposal that ends %s is reported as %s', async (state, status, failed) => {
    const spy = tool({
      run: ran(staged()),
      propose: proposal(state, {
        needs: state === 'waiting' ? (['demo:items/ruby.yml'] as never) : [],
        problems: state === 'refused' ? [{ phase: 'dry-run', code: 'MTR-0012', severity: 'error', message: 'unknown field', operation: 0, source: { line: 4, column: 2 }, resource: 'items/thing_0.yml' }] : [],
      }),
    });
    const outcome = await runProgram({ text: 'p.items.create(...)' }, spy.deps);
    expect(outcome.status).toBe(status);
    expect(outcome.failed).toBe(failed);
    expect(outcome.proposalId).toBe('p1');
    const sent = body(outcome);
    expect(sent.proposal).toMatchObject({ id: 'p1', state });
    if (state === 'waiting') {
      expect(sent.note).toContain('WAITS');
      expect(sent.note).toContain('cannot approve');
      expect((sent.proposal as { needsApprovalOf: string[] }).needsApprovalOf).toEqual(['demo:items/ruby.yml']);
    }
    if (state === 'refused') expect(outcome.diagnostics).toContainEqual({ phase: 'dry-run', code: 'MTR-0012', severity: 'error', message: 'items/thing_0.yml: unknown field', line: 4, column: 2 });
  });

  it('never throws: a checker, a sandbox or a store that fails is an outcome', async () => {
    const check = tool();
    check.deps.check = async () => {
      throw new Error('worker gone');
    };
    expect((await runProgram({ text: 'x' }, check.deps)).status).toBe('type-error');
    expect((await runProgram({ text: 'x' }, tool({ run: new Error('no frame') }).deps)).status).toBe('run-error');
    const store = await runProgram({ text: 'x' }, tool({ run: ran(staged()), propose: new Error('channel closed') }).deps);
    expect(store.status).toBe('failed');
    expect(store.diagnostics.at(-1)?.message).toContain('channel closed');
  });
});

describe('the result is bounded (codemode.md section 4.2)', () => {
  const huge = 'x'.repeat(200_000);

  it('a large value, long logs, many operations and many long diagnostics stay under the cap and stay JSON', async () => {
    const run = ran({
      ...staged(200),
      value: { text: huge, list: Array.from({ length: 5000 }, (_, index) => index) },
      logs: Array.from({ length: 400 }, () => huge.slice(0, 500)),
      summary: huge,
      diagnostics: Array.from({ length: 60 }, (_, index) => ({ code: 'SDK-0016', severity: 'warning' as const, message: `${index} ${huge}`, hint: huge, source: { line: index, column: 1 } })),
    });
    const outcome = await runProgram(
      { text: 'x' },
      tool({
        run,
        propose: proposal('refused', {
          documents: Array.from({ length: 300 }, (_, index) => ({ path: `items/${index}_${huge.slice(0, 400)}.yml`, kind: 'created' })),
          problems: Array.from({ length: 60 }, () => ({ phase: 'dry-run' as const, code: 'MTR-0012', severity: 'error' as const, message: huge, hint: huge })),
        }),
      }).deps,
    );
    expect(outcome.content.length).toBeLessThanOrEqual(TOOL_RESULT_MAX_CHARS);
    const sent = body(outcome);
    expect(sent.status).toBe('refused');
    // errors lead a cut list
    expect((sent.diagnostics as { severity: string }[])[0]?.severity).toBe('error');
    expect(outcome.content).not.toContain(huge.slice(0, 5000));
  });

  it('a type-check with the most diagnostics it can return stays under the cap', async () => {
    const diagnostics = Array.from({ length: 50 }, (_, index) => ({
      code: 'TS2339',
      severity: 'error' as const,
      message: huge,
      line: index + 1,
      column: 1,
      hint: huge,
      missingPrimitive: { member: huge, catalog: huge, nearest: Array.from({ length: 40 }, () => huge) },
    }));
    const outcome = await runProgram({ text: 'x' }, tool({ check: { ok: false, diagnostics, truncated: true } }).deps);
    expect(outcome.content.length).toBeLessThanOrEqual(TOOL_RESULT_MAX_CHARS);
    expect(body(outcome).status).toBe('type-error');
  });

  it('a small value is passed whole and a large one is cut with its size', async () => {
    const small = body(await runProgram({ text: 'x' }, tool({ run: ran({ value: { a: 1 } }) }).deps)).result as Record<string, unknown>;
    expect(small.value).toEqual({ a: 1 });
    const large = body(await runProgram({ text: 'x' }, tool({ run: ran({ value: huge }) }).deps)).result as Record<string, unknown>;
    expect(large.value).toBeUndefined();
    expect(large.valueChars).toBe(huge.length + 2);
    expect((large.valueCut as string).length).toBeLessThan(TOOL_RESULT_MAX_CHARS);
  });
});

// ---- the loop --------------------------------------------------------------------------------------------------

const user = (text: string): ChatMessage => ({ role: 'user', text });
const ok: ToolOutcome = { status: 'applied', failed: false, content: '{"status":"applied"}', proposalId: 'p1', diagnostics: [], missingPrimitives: [] };
const bad: ToolOutcome = { status: 'type-error', failed: true, content: '{"status":"type-error"}', proposalId: null, diagnostics: [], missingPrimitives: [] };

function script(turns: ScriptedTurn[]): Script {
  return { id: 'test', title: 'test', prompts: [], turns };
}

describe('the agent loop', () => {
  it('runs a program, hands the result back, and ends on a turn without a call', async () => {
    const provider = createScriptedProvider(script([{ text: 'Writing it.', program: 'P1' }, { text: 'Done.' }]));
    const inputs: unknown[] = [];
    const events: LoopEvent['type'][] = [];
    const result = await runAgent([user('make a thing')], {
      provider,
      system: 'S',
      tool: async (input) => {
        inputs.push(input);
        return ok;
      },
      onEvent: (event) => events.push(event.type),
    });
    expect(result.stop).toBe('done');
    expect(result.turns).toBe(2);
    expect(result.failedCalls).toBe(0);
    expect(inputs).toEqual([{ text: 'P1' }]);
    expect(result.added).toEqual([
      { role: 'assistant', text: 'Writing it.', toolCalls: [{ id: 'call-1', name: RUN_PROGRAM, input: { text: 'P1' } }] },
      { role: 'tool', callId: 'call-1', name: RUN_PROGRAM, content: ok.content },
      { role: 'assistant', text: 'Done.', toolCalls: [] },
    ]);
    expect(events).toEqual(['turn-start', 'text', 'text', 'tool-start', 'tool-end', 'turn-start', 'text']);
  });

  it('asks the provider with the system text, the history and the one tool, and with no model name or key', async () => {
    const requests: ModelRequest[] = [];
    const provider: ModelProvider = {
      info: { id: 'spy', label: 'spy', remote: false },
      async *stream(request): AsyncIterable<ModelEvent> {
        requests.push({ ...request, messages: [...request.messages] });
        yield { type: 'text', delta: 'hi' };
      },
    };
    const abort = new AbortController();
    await runAgent([user('hello')], { provider, system: 'THE CONTEXT', tool: async () => ok, signal: abort.signal });
    expect(requests).toHaveLength(1);
    expect(Object.keys(requests[0]!).sort()).toEqual(['messages', 'signal', 'system', 'tools']);
    expect(requests[0]!.system).toBe('THE CONTEXT');
    expect(requests[0]!.messages).toEqual([user('hello')]);
    expect(requests[0]!.tools).toEqual([RUN_PROGRAM_TOOL]);
  });

  it(`stops after ${REPAIR_BUDGET} failed programs and does not ask the model again`, async () => {
    const turns = Array.from({ length: REPAIR_BUDGET + 3 }, (_, index) => ({ text: `try ${index}`, program: `P${index}` }));
    let asked = 0;
    const inner = createScriptedProvider(script(turns));
    const provider: ModelProvider = {
      info: inner.info,
      stream(request) {
        asked++;
        return inner.stream(request);
      },
    };
    let ran = 0;
    const result = await runAgent([user('go')], { provider, system: '', tool: async () => (ran++, bad) });
    expect(result.stop).toBe('repair-budget');
    expect(result.failedCalls).toBe(REPAIR_BUDGET);
    expect(ran).toBe(REPAIR_BUDGET);
    expect(asked).toBe(REPAIR_BUDGET);
    // every call has its result, so the history stays well formed
    expect(result.added.filter((message) => message.role === 'tool')).toHaveLength(REPAIR_BUDGET);
    expect(stopText(result)).toContain(`the limit is ${REPAIR_BUDGET}`);
  });

  it('a read and an accepted dry run do not spend the budget; a failure between successes still counts', async () => {
    const turns = [{ program: 'a' }, { program: 'b' }, { program: 'c' }, { program: 'd' }, { program: 'e' }, { text: 'done' }];
    const outcomes = [bad, ok, bad, ok, ok];
    let index = 0;
    const result = await runAgent([user('go')], { provider: createScriptedProvider(script(turns)), system: '', tool: async () => outcomes[index++]! });
    expect(result.stop).toBe('done');
    expect(result.failedCalls).toBe(2);
  });

  it(`stops at ${MAX_MODEL_TURNS} model turns even when every program succeeds`, async () => {
    const turns = Array.from({ length: MAX_MODEL_TURNS + 5 }, (_, index) => ({ program: `P${index}` }));
    const result = await runAgent([user('go')], { provider: createScriptedProvider(script(turns)), system: '', tool: async () => ok });
    expect(result.stop).toBe('turn-limit');
    expect(result.turns).toBe(MAX_MODEL_TURNS);
    expect(stopText(result)).toContain(String(MAX_MODEL_TURNS));
  });

  it('a refusal ends the request with the provider\'s words and adds no assistant turn', async () => {
    const provider = createScriptedProvider(script([{ refusal: { code: 'allowance_exhausted', message: 'Buy credits to continue.', retryAfterSeconds: 3600 } }]));
    let ran = 0;
    const result = await runAgent([user('go')], { provider, system: '', tool: async () => (ran++, ok) });
    expect(result.stop).toBe('refused');
    expect(result.refusal).toEqual({ code: 'allowance_exhausted', message: 'Buy credits to continue.', retryAfterSeconds: 3600 });
    expect(result.added).toEqual([]);
    expect(ran).toBe(0);
    expect(refusalText(result.refusal!)).toBe('Your AI allowance is used up. Buy credits to continue. Try again in about 3600 s.');
    expect(refusalText({ code: 'something_new', message: 'No.' })).toBe('The model provider refused the request. No.');
  });

  it('sums the usage the provider reports and reports none when it reports none', async () => {
    const provider = createScriptedProvider(
      script([
        { program: 'a', usage: { inputTokens: 100, outputTokens: 10 } },
        { text: 'done', usage: { inputTokens: 120, outputTokens: 5, cachedInputTokens: 100, credits: 2 } },
      ]),
    );
    const result = await runAgent([user('go')], { provider, system: '', tool: async () => ok });
    expect(result.usage).toEqual({ inputTokens: 220, outputTokens: 15, cachedInputTokens: 100, credits: 2 });
    const silent = await runAgent([user('go')], { provider: createScriptedProvider(script([{ text: 'hi' }])), system: '', tool: async () => ok });
    expect(silent.usage).toEqual({});
    expect(addUsage({ inputTokens: 1 }, { inputTokens: Number.NaN, outputTokens: 2 })).toEqual({ inputTokens: 1, outputTokens: 2 });
  });

  it('a call to a tool that does not exist, or with an extra parameter, is answered as a failure and runs nothing it should not', async () => {
    const provider = createScriptedProvider(script([{ rawCall: { name: 'approve_proposal', input: { id: 'p1' } } }, { text: 'ok' }]));
    let ran = 0;
    const result = await runAgent([user('go')], { provider, system: '', tool: async () => (ran++, ok) });
    expect(ran).toBe(0);
    expect(result.failedCalls).toBe(1);
    expect(result.added[1]).toMatchObject({ role: 'tool', name: 'approve_proposal' });
    expect((result.added[1] as { content: string }).content).toContain('no tool named');
  });

  it('a provider that throws ends the request as an error, and an abort ends it as stopped', async () => {
    const broken: ModelProvider = {
      info: { id: 'broken', label: 'broken', remote: true },
      // eslint-disable-next-line require-yield
      async *stream(): AsyncIterable<ModelEvent> {
        throw new Error('502 from the proxy');
      },
    };
    const failed = await runAgent([user('go')], { provider: broken, system: '', tool: async () => ok });
    expect(failed.stop).toBe('error');
    expect(stopText(failed)).toContain('502 from the proxy');

    const abort = new AbortController();
    const provider = createScriptedProvider(script([{ text: 'one', program: 'a' }, { text: 'two', program: 'b' }, { text: 'three' }]));
    const stopped = await runAgent([user('go')], {
      provider,
      system: '',
      signal: abort.signal,
      tool: async () => {
        abort.abort();
        return ok;
      },
    });
    expect(stopped.stop).toBe('aborted');
    expect(stopped.turns).toBe(1);
    // the call that ran has its result
    expect(stopped.added.map((message) => message.role)).toEqual(['assistant', 'tool']);
  });
});

describe('the scripted provider', () => {
  const recorded = script([{ text: 'first turn here', program: 'A' }, { text: 'second' }]);
  const collect = async (provider: ModelProvider, messages: ChatMessage[]): Promise<ModelEvent[]> => {
    const events: ModelEvent[] = [];
    for await (const event of provider.stream({ system: '', messages, tools: [] })) events.push(event);
    return events;
  };

  it('says it is not remote', () => {
    expect(createScriptedProvider(recorded).info.remote).toBe(false);
  });

  it('replays the turn after the assistant turns the history already has, and the text joins back exactly', async () => {
    const provider = createScriptedProvider(recorded);
    const first = await collect(provider, [user('x')]);
    expect(first.filter((event) => event.type === 'text').map((event) => (event as { delta: string }).delta).join('')).toBe('first turn here');
    expect(first.at(-1)).toEqual({ type: 'tool_call', call: { id: 'call-1', name: RUN_PROGRAM, input: { text: 'A' } } });
    const again = await collect(provider, [user('anything else')]);
    expect(again).toEqual(first);
    const second = await collect(provider, [user('x'), { role: 'assistant', text: 'first turn here', toolCalls: [] }]);
    expect(second).toEqual([{ type: 'text', delta: 'second' }]);
  });

  it('refuses past the end of the recording', async () => {
    const events = await collect(createScriptedProvider(recorded), [user('x'), { role: 'assistant', text: '', toolCalls: [] }, { role: 'assistant', text: '', toolCalls: [] }]);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: 'refusal', refusal: { code: 'script_ended' } });
  });
});

describe('what leaves the page', () => {
  it('nothing, with a provider that is not remote', () => {
    const stated = disclosure({ id: 'scripted:x', label: 'x', remote: false });
    expect(stated.headline).toBe('Nothing leaves this page.');
    expect(stated.sent).toEqual([]);
  });

  it('the conversation, the SDK slice, diagnostics and bounded results, with a remote provider', () => {
    const stated = disclosure({ id: 'proxy', label: 'Materione AI', remote: true });
    expect(stated.headline).toContain('outside this page');
    expect(stated.sent).toHaveLength(4);
    expect(stated.sent[0]).toContain('conversation');
    expect(stated.sent[1]).toContain('SDK description');
    expect(stated.sent[2]).toContain('diagnostics');
    expect(stated.sent[3]).toContain(TOOL_RESULT_MAX_CHARS.toLocaleString('en-US'));
  });
});
