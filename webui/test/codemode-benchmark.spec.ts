import { describe, expect, it } from 'vitest';
import { fileURLToPath } from 'node:url';
import { runBaselineAgent } from '../benchmark/baseline';
import { BudgetLedger } from '../benchmark/budget';
import { assembleBenchmarkContext } from '../benchmark/context-mode';
import { buildAnthropicRequest, buildCompatRequest, createGatewayProvider } from '../benchmark/gateway';
import { assertNonFrontierModel, PICOS_PER_DOLLAR, PRESET_MODELS, resolveModelSpec } from '../benchmark/models';
import { defaultRegistrySnapshotPath, MemoryBenchmarkOracle } from '../benchmark/oracle';
import { renderMarkdownReport, totalSpentDollars } from '../benchmark/report';
import { createRoutedProvider, shouldEscalateToMid } from '../benchmark/routing';
import { buildDefaultConfigs, loadCorpusTasks, runBenchmarkSuite, verifyFollowupNoRegression } from '../benchmark/runner';
import { evaluateVagueRequest } from '../benchmark/vague';
import type { ChatMessage, ModelProvider } from '../src/lib/codemode/chat/provider';
import { RUN_PROGRAM_TOOL } from '../src/lib/codemode/chat/tool';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import { surfaceNamed } from './helpers/codemode';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

describe('benchmark models and non-frontier guard', () => {
  it('accepts preset cheap and mid models and rejects frontier-priced or named models', () => {
    expect(() => assertNonFrontierModel(resolveModelSpec('claude-haiku-5-5'))).not.toThrow();
    expect(() => assertNonFrontierModel(resolveModelSpec('claude-sonnet-4-5'))).not.toThrow();

    expect(() =>
      assertNonFrontierModel({
        ...PRESET_MODELS['claude-sonnet-4-5']!,
        id: 'claude-opus-4-6',
        catalogId: 'anthropic/claude-opus-4-6',
        upstreamModel: 'claude-opus-4-6',
      }),
    ).toThrow(/Frontier-priced/);

    expect(() =>
      assertNonFrontierModel({
        ...PRESET_MODELS['gpt-5-mini']!,
        id: 'o1-pro',
        catalogId: 'openai/o1-pro',
        upstreamModel: 'o1-pro',
      }),
    ).toThrow(/Frontier-priced/);

    expect(() =>
      assertNonFrontierModel({
        ...PRESET_MODELS['claude-sonnet-4-5']!,
        id: 'custom-overpriced',
        inputPicosPerToken: 10_000_000,
      }),
    ).toThrow(/exceeds non-frontier cap/);
  });
});

describe('BudgetLedger pre-turn hard cap', () => {
  it('refuses a turn before fetch when worst-case cost would exceed --max-dollars and settles in picodollars', () => {
    const budget = new BudgetLedger(0.01); // $0.01 = 10_000_000_000 picos
    const mid = resolveModelSpec('claude-sonnet-4-5');
    // Worst-case with maxOutputTokens=4096 * 10_000_000 picos = $0.04096 > $0.01
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

    const gemini = resolveModelSpec('gemini-2.5-flash');
    const compatReq = buildCompatRequest(
      gemini,
      {
        system: 'system prompt',
        messages: [{ role: 'user', text: 'hello' }],
        tools: [RUN_PROGRAM_TOOL],
      },
      { accountId: 'acc', gatewayId: 'gw', token: 'tok' },
    );
    expect(compatReq.url).toBe('https://gateway.ai.cloudflare.com/v1/acc/gw/compat/chat/completions');

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

  it('builds both index and full-dts context modes', () => {
    const surface = surfaceNamed('plugin-current');
    const indexCtx = assembleBenchmarkContext(surface, 'index');
    const fullCtx = assembleBenchmarkContext(surface, 'full-dts');
    expect(indexCtx).toContain('SDK INDEX');
    expect(fullCtx).toContain('SDK FULL DECLARATIONS');
    expect(fullCtx.length).toBeGreaterThan(indexCtx.length);
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

  it('7. registers claude-sonnet-5-5 as default mid model and records assistantReply on tasks', async () => {
    const sonnet55 = resolveModelSpec('claude-sonnet-5-5');
    expect(sonnet55.tier).toBe('mid');
    expect(sonnet55.upstreamModel).toBe('claude-sonnet-5-5');
    const cfgs = buildDefaultConfigs({
      models: ['claude-haiku-5-5'],
      strategy: 'escalate',
      contextMode: 'index',
      mode: 'codemode',
    });
    expect(cfgs[0]!.midModel.id).toBe('claude-sonnet-5-5');

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
