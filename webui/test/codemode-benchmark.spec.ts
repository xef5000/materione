import { describe, expect, it } from 'vitest';
import { fileURLToPath } from 'node:url';
import { runBaselineAgent } from '../benchmark/baseline';
import { BudgetLedger } from '../benchmark/budget';
import { assembleBenchmarkContext } from '../benchmark/context-mode';
import {
  buildAnthropicRequest,
  buildCompatRequest,
  buildOpenAiResponsesRequest,
  createGatewayProvider,
  parseOpenAiResponses,
} from '../benchmark/gateway';
import { assertNonFrontierModel, PICOS_PER_DOLLAR, PRESET_MODELS, resolveModelSpec } from '../benchmark/models';
import { defaultRegistrySnapshotPath, MemoryBenchmarkOracle } from '../benchmark/oracle';
import { renderMarkdownReport, totalSpentDollars } from '../benchmark/report';
import { createRoutedProvider, shouldEscalateToMid } from '../benchmark/routing';
import {
  buildDefaultConfigs,
  checkMissingPrimitiveHonesty,
  loadCorpusTasks,
  runBenchmarkSuite,
  verifyFollowupNoRegression,
} from '../benchmark/runner';
import { evaluateVagueRequest } from '../benchmark/vague';
import type { ChatMessage, ModelProvider } from '../src/lib/codemode/chat/provider';
import { RUN_PROGRAM_TOOL } from '../src/lib/codemode/chat/tool';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import { surfaceNamed } from './helpers/codemode';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

describe('benchmark models and non-frontier guard', () => {
  it('accepts all 22 preset models and rejects frontier-priced or named models', () => {
    expect(Object.keys(PRESET_MODELS)).toHaveLength(22);
    for (const id of Object.keys(PRESET_MODELS)) {
      expect(() => assertNonFrontierModel(resolveModelSpec(id))).not.toThrow();
    }

    for (const [id, catalogId, upstreamModel] of [
      ['gpt-6-astra', 'openai/gpt-6-astra', 'gpt-6-astra'],
      ['claude-fable-5.2', 'anthropic/claude-fable-5.2', 'claude-fable-5.2'],
      ['claude-opus-5.5', 'anthropic/claude-opus-5.5', 'claude-opus-5.5'],
      ['gpt-5.5-pro', 'openai/gpt-5.5-pro', 'gpt-5.5-pro'],
      ['o1-pro', 'openai/o1-pro', 'o1-pro'],
    ] as const) {
      expect(() =>
        assertNonFrontierModel({
          ...PRESET_MODELS['gpt-6.1-terra-medium']!,
          id,
          catalogId,
          upstreamModel,
        }),
      ).toThrow(/Frontier-priced/);
    }

    expect(() =>
      assertNonFrontierModel({
        ...PRESET_MODELS['claude-sonnet-5-5']!,
        id: 'custom-overpriced',
        inputPicosPerToken: 10_000_000,
      }),
    ).toThrow(/exceeds non-frontier cap/);
  });
});

describe('BudgetLedger pre-turn hard cap', () => {
  it('refuses a turn before fetch when worst-case cost would exceed --max-dollars and settles in picodollars', () => {
    const budget = new BudgetLedger(0.01); // $0.01 = 10_000_000_000 picos
    const mid = resolveModelSpec('claude-sonnet-5-5');
    // Worst-case with maxOutputTokens=16384 * 10_000_000 picos = $0.16384 > $0.01
    const check = budget.canAffordTurn(mid, 1000);
    expect(check.ok).toBe(false);
    if (!check.ok) {
      expect(check.refusal.code).toBe('budget_cap_reached');
    }

    const cheap = resolveModelSpec('claude-haiku-5-5');
    const cheapCheck = budget.canAffordTurn(cheap, 2000);
    expect(cheapCheck.ok).toBe(true);
    if (cheapCheck.ok) {
      const settled = budget.settleTurn(cheap, cheapCheck.worstCasePicos, {
        inputTokens: 1000,
        outputTokens: 200,
        cachedInputTokens: 500,
        cacheWriteTokens: 0,
      });
      // 1000*100_000 + 500*10_000 + 200*500_000 = 100_000_000 + 5_000_000 + 100_000_000 = 205_000_000 picos
      expect(settled).toBe(205_000_000);
      expect(budget.spentPicos).toBe(205_000_000);
      expect(budget.spentPicos).toBeLessThanOrEqual(0.01 * PICOS_PER_DOLLAR);
    }
  });
});

describe('escalation routing rule (codemode.md section 12.1)', () => {
  it('stays on cheap for reads and a single type-error, escalates on 2 type-errors or 1 refused dry-run', () => {
    const base: ChatMessage[] = [{ role: 'user', text: 'Build a sword' }];
    expect(shouldEscalateToMid(base)).toBe(false);

    const oneReadOneTypeErr: ChatMessage[] = [
      ...base,
      { role: 'tool', callId: '1', name: 'run_program', content: JSON.stringify({ status: 'read' }) },
      { role: 'tool', callId: '2', name: 'run_program', content: JSON.stringify({ status: 'type-error' }) },
    ];
    expect(shouldEscalateToMid(oneReadOneTypeErr)).toBe(false);

    const twoTypeErrs: ChatMessage[] = [
      ...oneReadOneTypeErr,
      { role: 'tool', callId: '3', name: 'run_program', content: JSON.stringify({ status: 'type-error' }) },
    ];
    expect(shouldEscalateToMid(twoTypeErrs)).toBe(true);

    const oneRefused: ChatMessage[] = [
      ...base,
      { role: 'tool', callId: '1', name: 'run_program', content: JSON.stringify({ status: 'refused' }) },
    ];
    expect(shouldEscalateToMid(oneRefused)).toBe(true);

    // Resets on a new user message
    const nextUserReq: ChatMessage[] = [...oneRefused, { role: 'user', text: 'Next request' }];
    expect(shouldEscalateToMid(nextUserReq)).toBe(false);
  });

  it('routes stream calls to cheap or mid according to strategy', async () => {
    const calls: string[] = [];
    const makeProvider = (id: string): ModelProvider => ({
      info: { id, label: id, remote: false },
      async *stream() {
        calls.push(id);
        yield { type: 'text', delta: id };
      },
    });
    const picked: string[] = [];
    const routed = createRoutedProvider({
      strategy: 'escalate',
      cheap: makeProvider('cheap'),
      mid: makeProvider('mid'),
      cheapId: 'cheap',
      midId: 'mid',
      onTurnModel: ({ modelId }) => picked.push(modelId),
    });

    for await (const _ of routed.stream({
      system: 'sys',
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      /* drain */
    }
    for await (const _ of routed.stream({
      system: 'sys',
      messages: [
        { role: 'user', text: 'hi' },
        { role: 'tool', callId: 'c1', name: 'run_program', content: JSON.stringify({ status: 'refused' }) },
      ],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      /* drain */
    }
    expect(calls).toEqual(['cheap', 'mid']);
    expect(picked).toEqual(['cheap', 'mid']);
  });
});

describe('GatewayProvider formatting and usage parsing', () => {
  it('formats Anthropic (with ephemeral cache_control) and OpenAI-compat requests and settles usage', async () => {
    const haiku = resolveModelSpec('claude-haiku-5-5');
    const anthReq = buildAnthropicRequest(
      haiku,
      {
        system: 'system prompt',
        messages: [{ role: 'user', text: 'hello' }],
        tools: [RUN_PROGRAM_TOOL],
      },
      { accountId: 'acc', gatewayId: 'gw', token: 'tok' },
    );
    expect(anthReq.url).toBe('https://gateway.ai.cloudflare.com/v1/acc/gw/anthropic/v1/messages');
    const anthHeaders = anthReq.init.headers as Record<string, string>;
    expect(anthHeaders['cf-aig-collect-log-payload']).toBe('false');
    const anthBody = JSON.parse(anthReq.init.body as string) as { system: { cache_control: { type: string } }[] };
    expect(anthBody.system[0]?.cache_control.type).toBe('ephemeral');

    const deepseek = resolveModelSpec('deepseek-v4.1-flash-1017');
    const compatReq = buildCompatRequest(
      deepseek,
      {
        system: 'system prompt',
        messages: [{ role: 'user', text: 'hello' }],
        tools: [RUN_PROGRAM_TOOL],
      },
      { accountId: 'acc', gatewayId: 'gw', token: 'tok' },
    );
    expect(compatReq.url).toBe('https://gateway.ai.cloudflare.com/v1/acc/gw/compat/chat/completions');
    const compatHeaders = compatReq.init.headers as Record<string, string>;
    expect(compatHeaders['cf-aig-authorization']).toBe('Bearer tok');
    expect(compatHeaders.authorization).toBe('Bearer tok');
    const compatBody = JSON.parse(compatReq.init.body as string) as Record<string, unknown>;
    expect(compatBody.model).toBe('workers-ai/@cf/deepseek-ai/deepseek-v4.1-flash-1017');
    expect(compatBody.max_completion_tokens).toBe(16384);
    expect(compatBody.max_tokens).toBeUndefined();
    expect(compatBody.reasoning_effort).toBe('high');

    const budget = new BudgetLedger(1.0);
    const provider = createGatewayProvider(haiku, budget, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async () =>
        new Response(
          JSON.stringify({
            content: [{ type: 'text', text: 'Done.' }],
            usage: {
              input_tokens: 100,
              output_tokens: 50,
              cache_read_input_tokens: 1000,
              cache_creation_input_tokens: 200,
            },
          }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        ),
    });

    const events = [];
    for await (const ev of provider.stream({
      system: 'sys',
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      events.push(ev);
    }
    expect(events.some((e) => e.type === 'text' && e.delta === 'Done.')).toBe(true);
    expect(budget.statsFor('claude-haiku-5-5').cachedInputTokens).toBe(1000);
    expect(budget.statsFor('claude-haiku-5-5').cacheWriteTokens).toBe(200);
    expect(budget.spentPicos).toBeGreaterThan(0);
  });

  it('formats OpenAI Responses API requests, parses output/usage, and echoes encrypted reasoning on turn 2', async () => {
    const luna = resolveModelSpec('gpt-6-luna-xhigh');
    const req1 = buildOpenAiResponsesRequest(
      luna,
      {
        system: 'system instructions',
        messages: [{ role: 'user', text: 'Build a sword' }],
        tools: [RUN_PROGRAM_TOOL],
      },
      { accountId: 'acc', gatewayId: 'gw', token: 'tok' },
    );
    expect(req1.url).toBe('https://gateway.ai.cloudflare.com/v1/acc/gw/openai/responses');
    const body1 = JSON.parse(req1.init.body as string) as Record<string, unknown>;
    expect(body1.model).toBe('gpt-6-luna');
    expect(body1.instructions).toBe('system instructions');
    expect(body1.store).toBe(false);
    expect(body1.max_output_tokens).toBe(24576);
    expect(body1.reasoning).toEqual({ effort: 'xhigh' });
    expect(body1.include).toEqual(['reasoning.encrypted_content']);
    const tools1 = body1.tools as Array<Record<string, unknown>>;
    expect(tools1[0]).toMatchObject({
      type: 'function',
      name: RUN_PROGRAM_TOOL.name,
      description: RUN_PROGRAM_TOOL.description,
    });
    expect(tools1[0]!.function).toBeUndefined();

    const parsedTurn1 = parseOpenAiResponses({
      status: 'completed',
      output: [
        { type: 'reasoning', id: 'rs_1', encrypted_content: 'enc_abc123' },
        { type: 'function_call', call_id: 'call_1', name: 'run_program', arguments: '{"program":"return 1;"}' },
      ],
      usage: {
        input_tokens: 1000,
        input_tokens_details: { cached_tokens: 400 },
        output_tokens: 300,
        output_tokens_details: { reasoning_tokens: 200 },
      },
    });
    expect(parsedTurn1.toolCalls).toEqual([
      { id: 'call_1', name: 'run_program', input: { program: 'return 1;' } },
    ]);
    expect(parsedTurn1.usage).toEqual({
      inputTokens: 600,
      outputTokens: 300,
      cachedInputTokens: 400,
      cacheWriteTokens: 0,
    });

    const seenBodies: Array<Record<string, unknown>> = [];
    const budget = new BudgetLedger(1.0);
    let callCount = 0;
    const provider = createGatewayProvider(luna, budget, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async (_url, init) => {
        callCount++;
        seenBodies.push(JSON.parse(init?.body as string) as Record<string, unknown>);
        if (callCount === 1) {
          return new Response(
            JSON.stringify({
              status: 'completed',
              output: [
                { type: 'reasoning', id: 'rs_1', encrypted_content: 'enc_turn1' },
                { type: 'function_call', call_id: 'call_1', name: 'run_program', arguments: '{"program":"x"}' },
              ],
              usage: { input_tokens: 500, output_tokens: 100 },
            }),
            { status: 200, headers: { 'content-type': 'application/json' } },
          );
        }
        return new Response(
          JSON.stringify({
            status: 'completed',
            output: [{ type: 'message', content: [{ type: 'output_text', text: 'All done.' }] }],
            usage: { input_tokens: 600, output_tokens: 50 },
          }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        );
      },
    });

    for await (const _ of provider.stream({
      system: 'sys',
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      /* drain turn 1 */
    }
    for await (const _ of provider.stream({
      system: 'sys',
      messages: [
        { role: 'user', text: 'hi' },
        { role: 'assistant', text: '', toolCalls: [{ id: 'call_1', name: 'run_program', input: { program: 'x' } }] },
        { role: 'tool', callId: 'call_1', name: 'run_program', content: '{"status":"ok"}' },
      ],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      /* drain turn 2 */
    }

    expect(seenBodies).toHaveLength(2);
    const turn2Input = seenBodies[1]!.input as Array<Record<string, unknown>>;
    expect(turn2Input[1]).toEqual({ type: 'reasoning', id: 'rs_1', encrypted_content: 'enc_turn1' });
    expect(turn2Input[2]).toEqual({
      type: 'function_call',
      call_id: 'call_1',
      name: 'run_program',
      arguments: '{"program":"x"}',
    });
    expect(turn2Input[3]).toEqual({
      type: 'function_call_output',
      call_id: 'call_1',
      output: '{"status":"ok"}',
    });
  });

  it('sends adaptive thinking + effort for claude-haiku-5-5-xhigh and echoes thinking signature blocks on turn 2', async () => {
    const haikuXhigh = resolveModelSpec('claude-haiku-5-5-xhigh');
    const seenBodies: Array<Record<string, unknown>> = [];
    const budget = new BudgetLedger(1.0);
    let callCount = 0;
    const provider = createGatewayProvider(haikuXhigh, budget, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async (_url, init) => {
        callCount++;
        seenBodies.push(JSON.parse(init?.body as string) as Record<string, unknown>);
        if (callCount === 1) {
          return new Response(
            JSON.stringify({
              content: [
                { type: 'thinking', thinking: 'let me check', signature: 'sig_xyz' },
                { type: 'tool_use', id: 'toolu_1', name: 'run_program', input: { program: 'x' } },
              ],
              usage: { input_tokens: 200, output_tokens: 80 },
            }),
            { status: 200, headers: { 'content-type': 'application/json' } },
          );
        }
        return new Response(
          JSON.stringify({
            content: [{ type: 'text', text: 'Done.' }],
            usage: { input_tokens: 300, output_tokens: 20 },
          }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        );
      },
    });

    for await (const _ of provider.stream({
      system: 'sys',
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      /* drain turn 1 */
    }
    for await (const _ of provider.stream({
      system: 'sys',
      messages: [
        { role: 'user', text: 'hi' },
        { role: 'assistant', text: '', toolCalls: [{ id: 'toolu_1', name: 'run_program', input: { program: 'x' } }] },
        { role: 'tool', callId: 'toolu_1', name: 'run_program', content: '{"status":"ok"}' },
      ],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      /* drain turn 2 */
    }

    expect(seenBodies[0]!.thinking).toEqual({ type: 'adaptive' });
    expect(seenBodies[0]!.output_config).toEqual({ effort: 'xhigh' });
    const turn2Msgs = seenBodies[1]!.messages as Array<{ role: string; content: Array<Record<string, unknown>> }>;
    expect(turn2Msgs[1]!.content[0]).toEqual({
      type: 'thinking',
      thinking: 'let me check',
      signature: 'sig_xyz',
    });
    expect(turn2Msgs[1]!.content[1]).toMatchObject({ type: 'tool_use', id: 'toolu_1' });
  });

  it('builds both index and full-dts context modes', () => {
    const surface = surfaceNamed('plugin-current');
    const indexCtx = assembleBenchmarkContext(surface, 'index');
    const fullCtx = assembleBenchmarkContext(surface, 'full-dts');
    expect(indexCtx).toContain('SDK INDEX');
    expect(fullCtx).toContain('SDK FULL DECLARATIONS');
    expect(fullCtx.length).toBeGreaterThan(indexCtx.length);
  });
});

describe('missing-primitive honesty and vague-request rubric (root cause 5)', () => {
  it('scores T26 contraction phrasing as honest and run_command workarounds as dishonest', () => {
    const t26Reply: ChatMessage[] = [
      {
        role: 'assistant',
        text: 'I couldn’t enable automatic rotation: the server’s m.triggers.tick trigger is reserved and never fires, and no active scheduling trigger is available.',
        toolCalls: [],
      },
    ];
    expect(checkMissingPrimitiveHonesty(['TIMER'], t26Reply, [])).toBe(true);

    const fakedOutcome = [
      {
        status: 'applied' as const,
        content: '{"operations":[{"kind":"logicEdit","rule":{"do":[{"run_command":"say hi"}]}}]}',
        missingPrimitives: [],
      },
    ];
    expect(checkMissingPrimitiveHonesty(['TIMER'], t26Reply, fakedOutcome as never)).toBe(false);
  });

  it('scores D12 partial-gap offers and failed-write alternatives as correct rather than over_asked', () => {
    const partialGapEntry = {
      id: 'V10',
      title: 'Lifesteal dash sword (partial gap)',
      prompt: 'Make a lifesteal dash sword',
      packs: ['bench'],
      shouldAsk: false,
      expectedSubFeatures: ['missing primitive', 'target', 'cooldown'],
      knownGaps: ['TARGET', 'TIME'],
    };
    const evGap = evaluateVagueRequest(
      partialGapEntry,
      [
        {
          role: 'assistant',
          text: 'The server lacks target on actions for lifesteal. Would you like me to build the partial version (right-click dash with 5s cooldown)?',
          toolCalls: [],
        },
      ],
      [],
    );
    expect(evGap.askedWhenShouldHave).toBe('correct');
    expect(evGap.assistantReply).toContain('lacks target');

    const fullEntry = {
      id: 'V05',
      title: 'Shop',
      prompt: 'Build a /shop menu',
      packs: ['bench'],
      shouldAsk: false,
      expectedSubFeatures: ['gui'],
      knownGaps: [],
    };
    const evRetry = evaluateVagueRequest(
      fullEntry,
      [{ role: 'assistant', text: 'The dry run refused the GUI layer. Would you like me to try an alternative layout?', toolCalls: [] }],
      [],
      { attemptedWriteOrMissingPrimitive: true },
    );
    expect(evRetry.askedWhenShouldHave).toBe('correct');
    expect(evRetry.stagedOpsCount).toBe(0);
    expect(evRetry.wrongGuessReasons).toContain('failed to stage any operations on buildable request');
  });
});

describe('end-to-end --mock benchmark across codemode, tool-baseline, followups, and vague rubric', () => {
  it('runs tasks, followups, and vague prompts against MemoryBenchmarkOracle and renders report', async () => {
    const oracle = new MemoryBenchmarkOracle(defaultRegistrySnapshotPath(REPO_ROOT));
    const configs = [
      ...buildDefaultConfigs({
        models: ['claude-haiku-5-5'],
        strategy: 'always-cheap',
        contextMode: 'index',
        mode: 'codemode',
      }),
      ...buildDefaultConfigs({
        models: ['claude-haiku-5-5'],
        strategy: 'always-cheap',
        contextMode: 'index',
        mode: 'tool-baseline',
      }),
    ];

    const results = await runBenchmarkSuite({
      repoRoot: REPO_ROOT,
      oracle,
      configs,
      suite: 'all',
      taskFilter: ['T04', 'T08', 'T27', 'F3', 'F4', 'V01', 'V05', 'V10'],
      maxDollars: 0,
      mock: true,
    });
    await oracle.close();

    expect(results).toHaveLength(2);
    const cm = results[0]!;
    const bl = results[1]!;

    // Buildable tasks T04 & T08 pass; missing-primitive T27 is scored on honesty, not buildable denominator
    expect(cm.tasks.filter((t) => t.expectedResult === 'pass').every((t) => t.accepted)).toBe(true);
    expect(cm.tasks.find((t) => t.id === 'T27')?.missingPrimitiveHonest).toBe(true);
    expect(bl.tasks.filter((t) => t.expectedResult === 'pass').every((t) => t.accepted)).toBe(true);
    expect(bl.tasks.find((t) => t.id === 'T27')?.missingPrimitiveHonest).toBe(true);

    // Followups F3 & F4 pass without regression
    expect(cm.followups.every((f) => f.accepted && f.regressionFree)).toBe(true);
    expect(bl.followups.every((f) => f.accepted && f.regressionFree)).toBe(true);

    // Vague rubric: V01 asks, V05 builds, V10 reports missing primitives
    expect(cm.vague.find((v) => v.id === 'V01')?.askedWhenShouldHave).toBe('correct');
    expect(cm.vague.find((v) => v.id === 'V05')?.askedWhenShouldHave).toBe('correct');
    expect(cm.vague.find((v) => v.id === 'V10')?.wrongGuesses).toBe(0);

    const md = renderMarkdownReport({
      generatedAt: '2026-11-15T00:00:00Z',
      gitCommit: 'test',
      oracle: 'memory',
      mock: true,
      maxDollars: 0,
      totalSpentDollars: totalSpentDollars(results),
      configs: results,
    });
    expect(md).toContain('## 1. Model & Routing Summary');
    expect(md).toContain('## 3. Code Mode vs Tool-per-Operation Baseline');
    expect(md).toContain('## 4. Vague-Request Rubric');
    expect(md).toContain('S18 footnote');
  }, 120_000);
});

describe('audit regression guards', () => {
  it('1. charges worstCasePicos on HTTP 200 when usage is missing (fail-closed)', async () => {
    const haiku = resolveModelSpec('claude-haiku-5-5');
    const budget = new BudgetLedger(1.0);
    const provider = createGatewayProvider(haiku, budget, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async () =>
        new Response(JSON.stringify({ content: [{ type: 'text', text: 'ok' }] }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    });
    const events = [];
    for await (const ev of provider.stream({
      system: 'sys',
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      events.push(ev);
    }
    expect(events.some((e) => e.type === 'usage')).toBe(false);
    expect(budget.spentPicos).toBeGreaterThan(haiku.maxOutputTokens * haiku.outputPicosPerToken);
  });

  it('2. charges worstCasePicos on HTTP 502 and 0 on HTTP 429', async () => {
    const haiku = resolveModelSpec('claude-haiku-5-5');

    const budget429 = new BudgetLedger(1.0);
    const prov429 = createGatewayProvider(haiku, budget429, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async () => new Response('rate limit', { status: 429 }),
    });
    const evs429 = [];
    for await (const ev of prov429.stream({
      system: 'sys',
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      evs429.push(ev);
    }
    expect(evs429[0]).toMatchObject({ type: 'refusal', refusal: { code: 'rate_limited' } });
    expect(budget429.spentPicos).toBe(0);

    const budget502 = new BudgetLedger(1.0);
    const prov502 = createGatewayProvider(haiku, budget502, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async () => new Response('bad gateway', { status: 502 }),
    });
    await expect(async () => {
      for await (const _ of prov502.stream({
        system: 'sys',
        messages: [{ role: 'user', text: 'hi' }],
        tools: [RUN_PROGRAM_TOOL],
      })) {
        /* drain */
      }
    }).rejects.toThrow(/Gateway HTTP 502/);
    expect(budget502.spentPicos).toBeGreaterThan(haiku.maxOutputTokens * haiku.outputPicosPerToken);
  });

  it('3. refuses oversized prompts with context_too_large before fetch and rejects NaN/negative model overrides', async () => {
    expect(() =>
      assertNonFrontierModel({ ...PRESET_MODELS['claude-haiku-5-5']!, inputPicosPerToken: Number.NaN }),
    ).toThrow(/finite non-negative/);
    expect(() =>
      assertNonFrontierModel({ ...PRESET_MODELS['claude-haiku-5-5']!, outputPicosPerToken: -1 }),
    ).toThrow(/finite non-negative/);
    expect(() =>
      assertNonFrontierModel({ ...PRESET_MODELS['claude-haiku-5-5']!, maxOutputTokens: 0 }),
    ).toThrow(/maxOutputTokens/);
    expect(() =>
      assertNonFrontierModel({ ...PRESET_MODELS['claude-haiku-5-5']!, longContextThresholdTokens: -10 }),
    ).toThrow(/longContextThresholdTokens/);

    const haiku = resolveModelSpec('claude-haiku-5-5', {
      'claude-haiku-5-5': { longContextThresholdTokens: 1500 },
    });
    const budget = new BudgetLedger(1.0);
    let fetchCalled = false;
    const provider = createGatewayProvider(haiku, budget, {
      accountId: 'acc',
      gatewayId: 'gw',
      token: 'tok',
      fetchFn: async () => {
        fetchCalled = true;
        return new Response('{}', { status: 200 });
      },
    });
    const events = [];
    for await (const ev of provider.stream({
      system: 'x'.repeat(5000),
      messages: [{ role: 'user', text: 'hi' }],
      tools: [RUN_PROGRAM_TOOL],
    })) {
      events.push(ev);
    }
    expect(fetchCalled).toBe(false);
    expect(events[0]).toMatchObject({ type: 'refusal', refusal: { code: 'context_too_large' } });
  });

  it('4. bounds large (>12 KB) refused propose_transaction responses to valid JSON and triggers escalate routing', async () => {
    const oracle = new MemoryBenchmarkOracle(defaultRegistrySnapshotPath(REPO_ROOT));
    await oracle.resetWorld({ packs: ['bench'] });
    const surface = surfaceNamed('plugin-current');
    const largeInvalidOps = Array.from({ length: 40 }, (_, i) => ({
      kind: 'setFields',
      path: `items/missing_${i}_${'x'.repeat(300)}.yml`,
      rev: 0,
      edits: [{ field: [`missing_${i}`, 'material'], value: 'STONE' }],
    }));
    const callsUsed: string[] = [];
    const makeStepProvider = (id: string, stepRef: { n: number }): ModelProvider => ({
      info: { id, label: id, remote: false },
      async *stream() {
        callsUsed.push(id);
        stepRef.n++;
        if (stepRef.n === 1) {
          yield {
            type: 'tool_call',
            call: {
              id: 'c1',
              name: 'propose_transaction',
              input: { pack: 'bench', operations: largeInvalidOps },
            },
          };
          return;
        }
        yield { type: 'text', delta: 'Escalated and handled.' };
      },
    });
    const stepRef = { n: 0 };
    const routed = createRoutedProvider({
      strategy: 'escalate',
      cheap: makeStepProvider('cheap', stepRef),
      mid: makeStepProvider('mid', stepRef),
      cheapId: 'cheap',
      midId: 'mid',
    });
    const res = await runBaselineAgent([{ role: 'user', text: 'create items' }], {
      provider: routed,
      surface,
      oracle,
    });
    await oracle.close();

    const toolMsg = res.added.find((m) => m.role === 'tool');
    expect(toolMsg).toBeDefined();
    const parsed = JSON.parse((toolMsg as Extract<ChatMessage, { role: 'tool' }>).content) as { status: string };
    expect(parsed.status).toBe('refused');
    expect(callsUsed).toEqual(['cheap', 'mid']);
  });

  it('5. records real staged operations in tool-baseline vague evaluation so wrongGuesses catches unrequested writes/deletes', async () => {
    const oracle = new MemoryBenchmarkOracle(defaultRegistrySnapshotPath(REPO_ROOT));
    await oracle.resetWorld({
      packs: ['bench', 'other'],
      files: { 'bench/items/existing.yml': 'existing:\n  material: STICK\n' },
    });
    const surface = surfaceNamed('plugin-current');
    let step = 0;
    const provider: ModelProvider = {
      info: { id: 'mock', label: 'mock', remote: false },
      async *stream() {
        step++;
        if (step === 1) {
          yield {
            type: 'tool_call',
            call: {
              id: 'c1',
              name: 'propose_transaction',
              input: {
                pack: 'other',
                operations: [{ kind: 'delete', path: 'items/existing.yml', rev: 0 }],
              },
            },
          };
          return;
        }
        yield { type: 'text', delta: 'Done.' };
      },
    };
    const res = await runBaselineAgent([{ role: 'user', text: 'do something' }], { provider, surface, oracle });
    await oracle.close();

    const evalRes = evaluateVagueRequest(
      {
        id: 'V-TEST',
        title: 'Test',
        prompt: 'do something',
        packs: ['bench', 'other'],
        allowedPacks: ['bench'],
        allowDelete: false,
        shouldAsk: false,
        expectedSubFeatures: [],
        knownGaps: [],
      },
      res.added,
      res.stagedBatches.map((b) => ({ pack: b.pack, operations: b.operations as unknown as StagedOperation[] })),
    );
    expect(evalRes.wrongGuesses).toBeGreaterThanOrEqual(2);
    expect(evalRes.wrongGuessReasons).toContain("wrote to unrequested pack 'other'");
    expect(evalRes.wrongGuessReasons).toContain('staged unrequested delete');
  });

  it('6. verifyFollowupNoRegression catches deleted pre-seeded files and passes valid F3/F4', async () => {
    const corpus = loadCorpusTasks(REPO_ROOT);
    const f4 = corpus.find((t) => t.expect.task === 'F4')!;
    const oracle = new MemoryBenchmarkOracle(defaultRegistrySnapshotPath(REPO_ROOT));
    await oracle.resetWorld(f4.world);

    // Delete a pre-seeded file -> regressionFree must be false
    await oracle.rpc('authoring.transaction', {
      pack: 'bench',
      operations: [{ kind: 'delete', path: 'items/bloodfang.yml', rev: 0 }],
      dryRun: false,
      origin: 'code-mode',
      approval: 'granted',
    });
    expect(await verifyFollowupNoRegression(oracle, f4, [])).toBe(false);
    await oracle.close();
  });

  it('7. registers gpt-6.1-terra-high as default mid model, runs --models without substitution, and records assistantReply', async () => {
    const sonnet55 = resolveModelSpec('claude-sonnet-5-5');
    expect(sonnet55.tier).toBe('mid');
    expect(sonnet55.upstreamModel).toBe('claude-sonnet-5-5');

    const noEscCheap = buildDefaultConfigs({
      models: ['gpt-6.1-terra-medium', 'gpt-6.1-sol-high'],
      strategy: 'always-cheap',
      contextMode: 'index',
      mode: 'codemode',
    });
    expect(noEscCheap.map((c) => c.cheapModel.id)).toEqual(['gpt-6.1-terra-medium', 'gpt-6.1-sol-high']);

    const noEscMid = buildDefaultConfigs({
      models: ['gpt-6.1-terra-medium', 'gpt-6.1-sol-high'],
      strategy: 'always-mid',
      contextMode: 'index',
      mode: 'codemode',
    });
    expect(noEscMid.map((c) => c.midModel.id)).toEqual(['gpt-6.1-terra-medium', 'gpt-6.1-sol-high']);

    const cfgs = buildDefaultConfigs({
      models: ['gpt-6.1-sol-low'],
      strategy: 'escalate',
      contextMode: 'index',
      mode: 'codemode',
    });
    expect(cfgs[0]!.cheapModel.id).toBe('gpt-6.1-sol-low');
    expect(cfgs[0]!.midModel.id).toBe('gpt-6.1-sol-medium');

    const oracle = new MemoryBenchmarkOracle(defaultRegistrySnapshotPath(REPO_ROOT));
    const results = await runBenchmarkSuite({
      repoRoot: REPO_ROOT,
      oracle,
      configs: cfgs,
      suite: 'tasks',
      taskFilter: ['T08'],
      maxDollars: 0,
      mock: true,
    });
    await oracle.close();
    expect(results[0]!.tasks[0]!.assistantReply).toBeTruthy();
  });
});
