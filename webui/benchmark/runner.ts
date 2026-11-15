import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { build as esbuild } from 'esbuild';
import { runAgent } from '../src/lib/codemode/chat/loop';
import type { ChatMessage, ModelEvent, ModelProvider, ModelRequest, ToolCall } from '../src/lib/codemode/chat/provider';
import { runProgram, type ToolOutcome } from '../src/lib/codemode/chat/tool';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import { assembleSurface, type SurfaceDocument } from '../src/lib/codemode/generate/surface';
import { createProposalStore, type Problem } from '../src/lib/codemode/host';
import { parseReport } from '../src/lib/codemode/host/transport';
import { createSandboxRunner, type SandboxRunner, type Spawn } from '../src/lib/codemode/sandbox';
import { startEngine } from '../src/lib/codemode/sandbox/engine';
import type { SandboxAssets } from '../src/lib/codemode/sandbox/sandbox';
import { createChecker } from '../src/lib/codemode/typecheck/checker';
import { runBaselineAgent } from './baseline';
import { BudgetLedger } from './budget';
import { assembleBenchmarkContext, type ContextMode } from './context-mode';
import { createGatewayProvider, type GatewayConfig } from './gateway';
import { resolveModelSpec, type BenchmarkModelSpec, type TokenUsageCounts } from './models';
import {
  createOracleSandboxHost,
  createOracleTransactionTransport,
  type BenchmarkOracle,
  type BenchmarkWorldSpec,
} from './oracle';
import { createRoutedProvider, type RoutingStrategy } from './routing';
import { evaluateVagueRequest, type VagueEvaluation, type VagueRequestEntry } from './vague';

export type BenchmarkMode = 'codemode' | 'tool-baseline';
export type BenchmarkSuite = 'tasks' | 'followups' | 'vague' | 'all';

export interface CorpusTaskExpect {
  readonly task: string;
  readonly kind: 'task' | 'followup' | 'corpus';
  readonly title: string;
  readonly area: string;
  readonly prompt: string;
  readonly registry: string;
  readonly result: 'pass' | 'fail';
  readonly gaps: readonly string[];
  readonly replay?: { readonly note?: string };
}

export interface LoadedCorpusTask {
  readonly stem: string;
  readonly expect: CorpusTaskExpect;
  readonly programTs: string;
  readonly world: BenchmarkWorldSpec;
  readonly goldenOps: { readonly pack: string; readonly operations: readonly unknown[] } | null;
}

export interface TaskRunRecord {
  readonly id: string;
  readonly kind: 'task' | 'followup';
  readonly title: string;
  readonly expectedResult: 'pass' | 'fail';
  readonly gaps: readonly string[];
  readonly accepted: boolean;
  readonly s18AdjustedAccepted: boolean;
  readonly firstTurnPass: boolean;
  readonly missingPrimitiveHonest: boolean;
  readonly regressionFree: boolean;
  readonly turns: number;
  readonly failedCalls: number;
  readonly toolSteps: number;
  readonly wallTimeMs: number;
  readonly usage: TokenUsageCounts;
  readonly costPicos: number;
  readonly modelsUsed: readonly string[];
  readonly escalated: boolean;
  readonly stopReason: string;
  readonly diagnostics: readonly string[];
  readonly assistantReply?: string;
}

export interface ConfigRunResult {
  readonly label: string;
  readonly mode: BenchmarkMode;
  readonly strategy: RoutingStrategy;
  readonly contextMode: ContextMode;
  readonly cheapModelId: string;
  readonly midModelId: string;
  readonly totalCostPicos: number;
  readonly tasks: readonly TaskRunRecord[];
  readonly followups: readonly TaskRunRecord[];
  readonly vague: readonly VagueEvaluation[];
}

export interface RunConfigSpec {
  readonly label: string;
  readonly mode: BenchmarkMode;
  readonly strategy: RoutingStrategy;
  readonly contextMode: ContextMode;
  readonly cheapModel: BenchmarkModelSpec;
  readonly midModel: BenchmarkModelSpec;
}

export interface BenchmarkRunOptions {
  readonly repoRoot: string;
  readonly oracle: BenchmarkOracle;
  readonly configs: readonly RunConfigSpec[];
  readonly suite: BenchmarkSuite;
  readonly taskFilter?: readonly string[];
  readonly maxDollars: number;
  readonly mock: boolean;
  readonly gatewayConfig?: GatewayConfig;
  readonly modelOverrides?: Readonly<Record<string, Partial<BenchmarkModelSpec>>>;
}

let cachedSandboxAssets: Promise<SandboxAssets> | undefined;

async function loadSandboxAssetsForBenchmark(repoRoot: string): Promise<SandboxAssets> {
  cachedSandboxAssets ??= (async () => {
    const webuiDir = join(repoRoot, 'webui');
    const codemodeDir = join(webuiDir, 'src', 'lib', 'codemode');
    const common = {
      bundle: true,
      write: false,
      legalComments: 'none',
      logLevel: 'error',
      minifyWhitespace: true,
      minifySyntax: true,
    } as const;
    const [coreOut, engineOut] = await Promise.all([
      esbuild({
        ...common,
        entryPoints: [join(codemodeDir, 'core', 'index.ts')],
        format: 'iife',
        globalName: 'MaterioneCore',
        target: 'es2020',
        platform: 'neutral',
      }),
      esbuild({
        ...common,
        entryPoints: [join(codemodeDir, 'sandbox', 'worker-entry.ts')],
        format: 'iife',
        target: 'es2022',
        platform: 'browser',
        minifyIdentifiers: true,
        define: { 'import.meta.url': '"http://localhost/engine.js"', 'process.env.NODE_ENV': '"production"' },
      }),
    ]);
    const wasmBytes = readFileSync(
      resolve(webuiDir, 'node_modules/@jitl/quickjs-wasmfile-release-sync/dist/emscripten-module.wasm'),
    );
    const wasm = wasmBytes.buffer.slice(wasmBytes.byteOffset, wasmBytes.byteOffset + wasmBytes.byteLength) as ArrayBuffer;
    return {
      frameUrl: 'about:blank',
      wasm,
      coreSrc: coreOut.outputFiles?.[0]?.text ?? '',
      engineSrc: engineOut.outputFiles?.[0]?.text ?? '',
    };
  })();
  return cachedSandboxAssets;
}

const inProcessSpawn: Spawn = async (assets, memoryMb) => {
  const channel = new MessageChannel();
  void startEngine(channel.port2 as unknown as MessagePort, {
    wasm: assets.wasm.slice(0),
    coreSource: assets.coreSrc,
    memoryMb,
  });
  return {
    port: channel.port1 as unknown as MessagePort,
    destroy: () => {
      channel.port1.close();
      channel.port2.close();
    },
  };
};

export async function createBenchmarkSandboxRunner(repoRoot: string): Promise<SandboxRunner> {
  const assets = await loadSandboxAssetsForBenchmark(repoRoot);
  return createSandboxRunner({
    spawn: inProcessSpawn,
    loadAssets: async () => assets,
  });
}

export function loadCorpusTasks(repoRoot: string): LoadedCorpusTask[] {
  const corpusDir = join(repoRoot, 'fixtures', 'codemode', 'corpus');
  const opsDir = join(repoRoot, 'fixtures', 'golden', 'codemode', 'ops');
  return readdirSync(corpusDir)
    .filter((f) => f.endsWith('.expect.json'))
    .sort()
    .map((file) => {
      const stem = file.replace(/\.expect\.json$/, '');
      const expect = JSON.parse(readFileSync(join(corpusDir, file), 'utf8')) as CorpusTaskExpect;
      const programPath = join(corpusDir, `${stem}.ts`);
      const worldPath = join(corpusDir, `${stem}.world.json`);
      const opsPath = join(opsDir, `${stem}.json`);
      const programTs = existsSync(programPath) ? readFileSync(programPath, 'utf8') : '';
      const worldRaw = existsSync(worldPath) ? (JSON.parse(readFileSync(worldPath, 'utf8')) as BenchmarkWorldSpec) : {};
      const goldenOps = existsSync(opsPath)
        ? (JSON.parse(readFileSync(opsPath, 'utf8')) as { pack: string; operations: unknown[] })
        : null;
      return {
        stem,
        expect,
        programTs,
        world: {
          packs: worldRaw.packs ?? ['bench'],
          files: worldRaw.files ?? {},
          documents: worldRaw.documents ?? {},
          assets: worldRaw.assets ?? {},
        },
        goldenOps,
      };
    });
}

export function loadVagueRequests(repoRoot: string): VagueRequestEntry[] {
  const file = join(repoRoot, 'fixtures', 'codemode', 'vague-requests.json');
  const parsed = JSON.parse(readFileSync(file, 'utf8')) as { prompts: VagueRequestEntry[] };
  return parsed.prompts;
}

function isS18FurnitureMtr0009Only(problems: readonly Problem[]): boolean {
  const blocking = problems.filter((p) => p.severity === 'error' && p.preexisting !== true);
  if (blocking.length === 0) return false;
  return blocking.every((p) => p.code === 'MTR-0009' && /block\//i.test(p.message));
}

function isS18ReportMtr0009Only(diagnostics: readonly { code?: string; severity: string; message: string; preexisting?: boolean }[]): boolean {
  const blocking = diagnostics.filter((d) => d.severity === 'error' && d.preexisting !== true);
  if (blocking.length === 0) return false;
  return blocking.every((d) => d.code === 'MTR-0009' && /block\//i.test(d.message));
}

function checkMissingPrimitiveHonesty(
  gaps: readonly string[],
  added: readonly ChatMessage[],
  outcomes: readonly ToolOutcome[],
): boolean {
  if (gaps.length === 0) return true;
  const assistantText = added
    .filter((m): m is Extract<ChatMessage, { role: 'assistant' }> => m.role === 'assistant')
    .map((m) => m.text)
    .join('\n')
    .toLowerCase();
  const sawMissingDiag = outcomes.some((o) => o.missingPrimitives.length > 0);
  const fakedWorkaround = outcomes.some((o) => o.status === 'applied' && /run_command|console_command/i.test(o.content));
  const explainedGap =
    sawMissingDiag ||
    assistantText.includes('missing') ||
    assistantText.includes('not available') ||
    assistantText.includes('cannot') ||
    assistantText.includes('does not support') ||
    assistantText.includes('unavailable');
  return explainedGap && !fakedWorkaround;
}

export interface FollowupReportDoc {
  readonly path?: unknown;
  readonly kind?: unknown;
}

export async function verifyFollowupNoRegression(
  oracle: BenchmarkOracle,
  task: Pick<LoadedCorpusTask, 'expect' | 'world'>,
  reportDocs: readonly FollowupReportDoc[],
): Promise<boolean> {
  const preseededPaths = new Set<string>([
    ...Object.keys(task.world.files ?? {}),
    ...Object.keys(task.world.documents ?? {}),
  ]);
  const deletedPaths = new Set(
    reportDocs
      .filter((d) => d.kind === 'deleted' && typeof d.path === 'string')
      .map((d) => d.path as string),
  );

  const packsToCheck = new Set<string>();
  for (const rel of preseededPaths) {
    const slash = rel.indexOf('/');
    if (slash <= 0) continue;
    const pack = rel.slice(0, slash);
    const path = rel.slice(slash + 1);
    if (path === 'pack.yml') continue;
    packsToCheck.add(pack);
    if (deletedPaths.has(path)) return false;

    const got = await oracle.rpc('browse.get', { pack, path });
    if (!got.ok || typeof got.data !== 'object' || got.data === null) return false;
    const parsed = (got.data as { parsed?: unknown }).parsed;
    if (typeof parsed !== 'object' || parsed === null || Object.keys(parsed).length === 0) {
      return false;
    }
  }

  for (const pack of packsToCheck) {
    const dry = await oracle.rpc('authoring.transaction', { pack, operations: [], dryRun: true });
    if (!dry.ok) return false;
    const report = parseReport(dry.data);
    if (!report.accepted) return false;
  }

  const taskId = task.expect.task.toUpperCase();
  if (taskId === 'F3') {
    const menuGot = await oracle.rpc('browse.get', { pack: 'bench', path: 'guis/bloodfang_menu.yml' });
    if (!menuGot.ok || typeof menuGot.data !== 'object' || menuGot.data === null) return false;
    const menuData = menuGot.data as { raw?: string; parsed?: Record<string, Record<string, unknown>> };
    const menuBlob = `${menuData.raw ?? ''}\n${JSON.stringify(menuData.parsed ?? {})}`;
    if (!menuBlob.includes('500') || !menuBlob.includes('bloodfang_level')) return false;
    const menuObj = menuData.parsed?.bloodfang_menu;
    if (!menuObj || menuObj.container !== 'chest_3' || !Array.isArray(menuObj.layers) || menuObj.layers.length === 0) {
      return false;
    }
  } else if (taskId === 'F4') {
    const itemGot = await oracle.rpc('browse.get', { pack: 'bench', path: 'items/bloodfang.yml' });
    if (!itemGot.ok || typeof itemGot.data !== 'object' || itemGot.data === null) return false;
    const itemParsed = (itemGot.data as { parsed?: Record<string, Record<string, unknown>> }).parsed;
    if (itemParsed?.bloodfang?.material !== 'DIAMOND_SWORD') return false;

    const menuGot = await oracle.rpc('browse.get', { pack: 'bench', path: 'guis/bloodfang_menu.yml' });
    if (!menuGot.ok || typeof menuGot.data !== 'object' || menuGot.data === null) return false;
    const menuData = menuGot.data as { raw?: string; parsed?: Record<string, Record<string, unknown>> };
    const menuObj = menuData.parsed?.bloodfang_menu;
    if (!menuObj || menuObj.container !== 'chest_3') return false;
    const menuBlob = `${menuData.raw ?? ''}\n${JSON.stringify(menuObj)}`;
    if (!menuBlob.includes('rpg.bloodfang') || menuBlob.includes('rpg.weapons')) return false;
  }

  return true;
}

function createMockProviderForTask(
  mode: BenchmarkMode,
  modelSpec: BenchmarkModelSpec,
  task: LoadedCorpusTask,
  onUsage: (u: TokenUsageCounts) => void,
): ModelProvider {
  let step = 0;
  return {
    info: { id: `mock:${modelSpec.id}`, label: `Mock (${modelSpec.id})`, remote: false },
    async *stream(_req: ModelRequest): AsyncIterable<ModelEvent> {
      step++;
      const usage: TokenUsageCounts = {
        inputTokens: step === 1 ? 1800 : 400,
        outputTokens: 160,
        cachedInputTokens: step > 1 ? 1400 : 0,
        cacheWriteTokens: step === 1 ? 1400 : 0,
      };
      onUsage(usage);
      yield { type: 'usage', usage: { inputTokens: usage.inputTokens, outputTokens: usage.outputTokens, cachedInputTokens: usage.cachedInputTokens } };

      if (step === 1) {
        if (mode === 'codemode') {
          if (task.programTs.trim() !== '') {
            const call: ToolCall = { id: `call-${step}`, name: 'run_program', input: { text: task.programTs } };
            yield { type: 'tool_call', call };
            return;
          }
        } else {
          if (task.expect.result === 'pass' && task.goldenOps !== null) {
            const call: ToolCall = {
              id: `call-${step}`,
              name: 'propose_transaction',
              input: {
                pack: task.goldenOps.pack,
                summary: task.expect.title,
                operations: task.goldenOps.operations,
              },
            };
            yield { type: 'tool_call', call };
            return;
          }
          const call: ToolCall = { id: `call-${step}`, name: 'describe_surface', input: { section: 'actions' } };
          yield { type: 'tool_call', call };
          return;
        }
      }

      if (task.expect.result === 'fail') {
        yield {
          type: 'text',
          delta: `The server does not support the required primitive (${task.expect.gaps.join(', ')}). I will not fake it with a workaround.`,
        };
      } else {
        yield { type: 'text', delta: `Completed ${task.expect.title}.` };
      }
    },
  };
}

function createMockProviderForVague(
  mode: BenchmarkMode,
  modelSpec: BenchmarkModelSpec,
  entry: VagueRequestEntry,
  onUsage: (u: TokenUsageCounts) => void,
): ModelProvider {
  let step = 0;
  return {
    info: { id: `mock-vague:${modelSpec.id}`, label: `Mock Vague (${modelSpec.id})`, remote: false },
    async *stream(_req: ModelRequest): AsyncIterable<ModelEvent> {
      step++;
      const usage: TokenUsageCounts = { inputTokens: 1500, outputTokens: 120, cachedInputTokens: 0, cacheWriteTokens: 0 };
      onUsage(usage);
      yield { type: 'usage', usage: { inputTokens: usage.inputTokens, outputTokens: usage.outputTokens } };

      if (entry.shouldAsk) {
        yield {
          type: 'text',
          delta: `Before I write anything, which pack (${entry.packs.join(', ')}) and exact target should I use?`,
        };
        return;
      }
      if (entry.knownGaps.length > 0) {
        yield {
          type: 'text',
          delta: `Missing primitive on this server (${entry.knownGaps.join(', ')}): ${entry.expectedSubFeatures.join(', ')} are not available.`,
        };
        return;
      }
      if (step === 1) {
        if (mode === 'codemode') {
          const prog = `const p = m.pack("bench");\np.items.create("vague_marker", { material: "GOLD_INGOT", displayName: "<gold>${entry.title}" });\nm.summary("${entry.title} (${entry.expectedSubFeatures.join(', ')})");`;
          yield { type: 'tool_call', call: { id: 'call-v1', name: 'run_program', input: { text: prog } } };
          return;
        } else {
          yield {
            type: 'tool_call',
            call: {
              id: 'call-v1',
              name: 'propose_transaction',
              input: {
                pack: 'bench',
                summary: entry.title,
                operations: [
                  {
                    kind: 'create',
                    type: 'items',
                    id: 'vague_marker',
                    edits: [{ field: ['vague_marker', 'material'], value: 'GOLD_INGOT' }],
                  },
                ],
              },
            },
          };
          return;
        }
      }
      yield { type: 'text', delta: `Built ${entry.title} covering ${entry.expectedSubFeatures.join(', ')}.` };
    },
  };
}

function loadTsLibs(repoRoot: string): Record<string, string> {
  const libDir = join(repoRoot, 'webui', 'node_modules', 'typescript', 'lib');
  const libs: Record<string, string> = {};
  for (const name of readdirSync(libDir)) {
    if (
      name === 'lib.es5.d.ts' ||
      /^lib\.es201[5-9].*\.d\.ts$/.test(name) ||
      /^lib\.es202[0-2].*\.d\.ts$/.test(name) ||
      /^lib\.decorators.*\.d\.ts$/.test(name)
    ) {
      libs[name] = readFileSync(join(libDir, name), 'utf8');
    }
  }
  return libs;
}

export async function runBenchmarkSuite(options: BenchmarkRunOptions): Promise<ConfigRunResult[]> {
  const { repoRoot, oracle, configs, suite, taskFilter, maxDollars, mock, gatewayConfig } = options;
  const allCorpus = loadCorpusTasks(repoRoot);
  const vagueEntries = loadVagueRequests(repoRoot);

  const filterSet = taskFilter && taskFilter.length > 0 ? new Set(taskFilter.map((t) => t.toUpperCase())) : null;
  const tasks =
    suite === 'tasks' || suite === 'all'
      ? allCorpus.filter((t) => t.expect.kind === 'task' && (!filterSet || filterSet.has(t.expect.task.toUpperCase())))
      : [];
  const followups =
    suite === 'followups' || suite === 'all'
      ? allCorpus.filter((t) => t.expect.kind === 'followup' && (!filterSet || filterSet.has(t.expect.task.toUpperCase())))
      : [];
  const vagues =
    suite === 'vague' || suite === 'all'
      ? vagueEntries.filter((v) => !filterSet || filterSet.has(v.id.toUpperCase()))
      : [];

  await oracle.resetWorld({});
  const described = await oracle.describe();
  const surface: SurfaceDocument = assembleSurface(described.describe, described.capabilities);
  const sdk = generateSdk(surface);
  const coreDts = readFileSync(join(repoRoot, 'webui', 'src', 'lib', 'codemode', 'generate', 'core.d.ts'), 'utf8');
  const checker = createChecker({ libs: loadTsLibs(repoRoot), core: coreDts });
  const sandboxRunner = await createBenchmarkSandboxRunner(repoRoot);

  const results: ConfigRunResult[] = [];

  try {
    for (const cfg of configs) {
      const budget = new BudgetLedger(maxDollars, { allowZeroForMock: mock });
      const systemPrompt = assembleBenchmarkContext(surface, cfg.contextMode);

      const runOneCorpusTask = async (task: LoadedCorpusTask): Promise<TaskRunRecord> => {
        const t0 = Date.now();
        await oracle.resetWorld(task.world);
        const host = createOracleSandboxHost(oracle);
        const store = createProposalStore({ transport: createOracleTransactionTransport(oracle) });
        const modelsUsed = new Set<string>();
        let escalated = false;
        let turnUsage: TokenUsageCounts = { inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
        const addTurnUsage = (u: TokenUsageCounts): void => {
          turnUsage = {
            inputTokens: turnUsage.inputTokens + u.inputTokens,
            outputTokens: turnUsage.outputTokens + u.outputTokens,
            cachedInputTokens: turnUsage.cachedInputTokens + u.cachedInputTokens,
            cacheWriteTokens: turnUsage.cacheWriteTokens + u.cacheWriteTokens,
          };
        };

        const cheapProvider = mock
          ? createMockProviderForTask(cfg.mode, cfg.cheapModel, task, addTurnUsage)
          : createGatewayProvider(cfg.cheapModel, budget, gatewayConfig!, ({ usage }) => addTurnUsage(usage));
        const midProvider = mock
          ? createMockProviderForTask(cfg.mode, cfg.midModel, task, addTurnUsage)
          : createGatewayProvider(cfg.midModel, budget, gatewayConfig!, ({ usage }) => addTurnUsage(usage));

        const provider = createRoutedProvider({
          strategy: cfg.strategy,
          cheap: cheapProvider,
          mid: midProvider,
          cheapId: cfg.cheapModel.id,
          midId: cfg.midModel.id,
          onTurnModel: ({ modelId, escalated: esc }) => {
            modelsUsed.add(modelId);
            if (esc) escalated = true;
          },
        });

        const costBefore = budget.spentPicos;
        const synthesisDirective =
          '\n\n[Benchmark harness: write to pack "bench" unless another pack is explicitly named in the request. Choose concrete defaults for any unspecified prices, coordinates, items, or layout rather than pausing to ask clarifying questions. Actions or conditions marked without a live runtime provider (such as economy or permissions in headless dry-run mode) can and should still be authored and dry-run normally; only stop without staging if a TypeScript missingPrimitive error proves the server registry lacks the required primitive.]';
        const history: ChatMessage[] = [{ role: 'user', text: `${task.expect.prompt}${synthesisDirective}` }];

        if (cfg.mode === 'codemode') {
          const outcomes: ToolOutcome[] = [];
          const loopRes = await runAgent(history, {
            provider,
            system: systemPrompt,
            tool: async (input) => {
              const out = await runProgram(input, {
                surface,
                check: async (program) => checker.check({ program, sdk }),
                run: (program, doc) => sandboxRunner.run(program, doc, host),
                agent: store.agent,
              });
              outcomes.push(out);
              return out;
            },
          });

          const proposals = store.snapshot().proposals;
          const lastProp = proposals.at(-1);
          const accepted = proposals.some((p) => p.state === 'applied' || p.state === 'waiting');
          const s18AdjustedAccepted =
            accepted || (lastProp !== undefined && lastProp.state === 'refused' && isS18FurnitureMtr0009Only(lastProp.problems));
          const firstTurnPass = accepted && loopRes.failedCalls === 0;
          const missingPrimitiveHonest = checkMissingPrimitiveHonesty(task.expect.gaps, loopRes.added, outcomes);
          const diagnostics = outcomes.flatMap((o) => o.diagnostics.map((d) => `${d.code ?? d.phase}: ${d.message}`));

          let regressionFree = true;
          if (task.expect.kind === 'followup') {
            const reportDocs: FollowupReportDoc[] = [];
            for (const prop of proposals) {
              if (prop.state === 'waiting') {
                const applyReply = await oracle.rpc('authoring.transaction', {
                  pack: prop.pack,
                  operations: prop.operations,
                  dryRun: false,
                  origin: 'code-mode',
                  approval: 'granted',
                });
                if (!applyReply.ok) {
                  regressionFree = false;
                } else {
                  const appliedReport = parseReport(applyReply.data);
                  if (!appliedReport.applied) regressionFree = false;
                  reportDocs.push(...appliedReport.documents);
                }
              } else if (prop.state === 'applied') {
                for (const d of prop.documents) {
                  if (typeof d === 'object' && d !== null) reportDocs.push(d as FollowupReportDoc);
                }
              }
            }
            if (regressionFree) {
              regressionFree = await verifyFollowupNoRegression(oracle, task, reportDocs);
            }
          }

          const lastAssistant = [...loopRes.added]
            .reverse()
            .find((m): m is Extract<ChatMessage, { role: 'assistant' }> => m.role === 'assistant' && m.text.trim() !== '');

          return {
            id: task.expect.task,
            kind: task.expect.kind === 'followup' ? 'followup' : 'task',
            title: task.expect.title,
            expectedResult: task.expect.result,
            gaps: task.expect.gaps,
            accepted,
            s18AdjustedAccepted,
            firstTurnPass,
            missingPrimitiveHonest,
            regressionFree,
            turns: loopRes.turns,
            failedCalls: loopRes.failedCalls,
            toolSteps: outcomes.length,
            wallTimeMs: Date.now() - t0,
            usage: turnUsage,
            costPicos: budget.spentPicos - costBefore,
            modelsUsed: [...modelsUsed],
            escalated,
            stopReason: loopRes.stop,
            diagnostics: diagnostics.slice(0, 5),
            assistantReply: lastAssistant?.text.trim().slice(0, 600),
          };
        } else {
          const baseRes = await runBaselineAgent(history, { provider, surface, oracle });
          const accepted = baseRes.outcomes.some((o) => o.status === 'applied' || o.status === 'waiting');
          const s18AdjustedAccepted =
            accepted ||
            (baseRes.lastReport !== null &&
              !baseRes.lastReport.accepted &&
              isS18ReportMtr0009Only(baseRes.lastReport.diagnostics));
          const firstTurnPass = accepted && baseRes.failedCalls === 0;
          const assistantText = baseRes.added
            .filter((m): m is Extract<ChatMessage, { role: 'assistant' }> => m.role === 'assistant')
            .map((m) => m.text)
            .join('\n')
            .toLowerCase();
          const missingPrimitiveHonest =
            task.expect.gaps.length === 0 ||
            ((assistantText.includes('missing') || assistantText.includes('not available') || assistantText.includes('does not support')) &&
              !baseRes.outcomes.some((o) => o.status === 'applied'));
          const diagnostics =
            baseRes.lastReport?.diagnostics.map((d) => `${d.code ?? 'diag'}: ${d.message}`) ?? [];

          let regressionFree = true;
          if (task.expect.kind === 'followup') {
            const reportDocs: FollowupReportDoc[] = [];
            const writeOutcomes = baseRes.outcomes.filter((o) => o.tool === 'propose_transaction');
            for (let i = 0; i < writeOutcomes.length; i++) {
              const wo = writeOutcomes[i]!;
              const batch = baseRes.stagedBatches[i];
              if (wo.status === 'waiting' && batch) {
                const applyReply = await oracle.rpc('authoring.transaction', {
                  pack: batch.pack,
                  operations: batch.operations,
                  dryRun: false,
                  origin: 'code-mode',
                  approval: 'granted',
                });
                if (!applyReply.ok) {
                  regressionFree = false;
                } else {
                  const appliedReport = parseReport(applyReply.data);
                  if (!appliedReport.applied) regressionFree = false;
                  reportDocs.push(...appliedReport.documents);
                }
              } else if (wo.status === 'applied' && wo.report) {
                reportDocs.push(...wo.report.documents);
              }
            }
            if (regressionFree) {
              regressionFree = await verifyFollowupNoRegression(oracle, task, reportDocs);
            }
          }

          const lastBaseAssistant = [...baseRes.added]
            .reverse()
            .find((m): m is Extract<ChatMessage, { role: 'assistant' }> => m.role === 'assistant' && m.text.trim() !== '');

          return {
            id: task.expect.task,
            kind: task.expect.kind === 'followup' ? 'followup' : 'task',
            title: task.expect.title,
            expectedResult: task.expect.result,
            gaps: task.expect.gaps,
            accepted,
            s18AdjustedAccepted,
            firstTurnPass,
            missingPrimitiveHonest,
            regressionFree,
            turns: baseRes.turns,
            failedCalls: baseRes.failedCalls,
            toolSteps: baseRes.toolSteps,
            wallTimeMs: Date.now() - t0,
            usage: turnUsage,
            costPicos: budget.spentPicos - costBefore,
            modelsUsed: [...modelsUsed],
            escalated,
            stopReason: baseRes.stop,
            diagnostics: diagnostics.slice(0, 5),
            assistantReply: lastBaseAssistant?.text.trim().slice(0, 600),
          };
        }
      };

      const totalItems = tasks.length + followups.length + vagues.length;
      let completedItems = 0;
      const logProgress = (id: string, status: string, ms: number): void => {
        completedItems++;
        if (!mock) {
          const spent = (budget.spentPicos / 1_000_000_000_000).toFixed(4);
          process.stderr.write(
            `[${completedItems}/${totalItems}] ${cfg.label} :: ${id} -> ${status} (${(ms / 1000).toFixed(1)}s, total $${spent})\n`,
          );
        }
      };

      const taskRecords: TaskRunRecord[] = [];
      for (const t of tasks) {
        const rec = await runOneCorpusTask(t);
        taskRecords.push(rec);
        logProgress(rec.id, rec.expectedResult === 'pass' ? (rec.accepted ? 'PASS' : rec.s18AdjustedAccepted ? 'PASS(S18)' : `FAIL(${rec.stopReason})`) : rec.missingPrimitiveHonest ? 'HONEST-GAP' : 'FAKED-GAP', rec.wallTimeMs);
      }

      const followupRecords: TaskRunRecord[] = [];
      for (const f of followups) {
        const rec = await runOneCorpusTask(f);
        followupRecords.push(rec);
        logProgress(rec.id, rec.expectedResult === 'pass' ? (rec.accepted && rec.regressionFree ? 'PASS' : 'FAIL') : rec.missingPrimitiveHonest ? 'HONEST-GAP' : 'FAKED-GAP', rec.wallTimeMs);
      }

      const vagueRecords: VagueEvaluation[] = [];
      for (const v of vagues) {
        const vt0 = Date.now();
        await oracle.resetWorld({ packs: v.packs });
        const host = createOracleSandboxHost(oracle);
        const store = createProposalStore({ transport: createOracleTransactionTransport(oracle) });
        const cheapProvider = mock
          ? createMockProviderForVague(cfg.mode, cfg.cheapModel, v, () => {})
          : createGatewayProvider(cfg.cheapModel, budget, gatewayConfig!);
        const midProvider = mock
          ? createMockProviderForVague(cfg.mode, cfg.midModel, v, () => {})
          : createGatewayProvider(cfg.midModel, budget, gatewayConfig!);
        const provider = createRoutedProvider({
          strategy: cfg.strategy,
          cheap: cheapProvider,
          mid: midProvider,
          cheapId: cfg.cheapModel.id,
          midId: cfg.midModel.id,
        });

        const history: ChatMessage[] = [{ role: 'user', text: v.prompt }];
        if (cfg.mode === 'codemode') {
          const res = await runAgent(history, {
            provider,
            system: systemPrompt,
            tool: async (input) =>
              runProgram(input, {
                surface,
                check: async (program) => checker.check({ program, sdk }),
                run: (program, doc) => sandboxRunner.run(program, doc, host),
                agent: store.agent,
              }),
          });
          const staged = store.snapshot().proposals.map((p) => ({ pack: p.pack, operations: p.operations }));
          const ev = evaluateVagueRequest(v, res.added, staged);
          vagueRecords.push(ev);
          logProgress(v.id, ev.askedWhenShouldHave, Date.now() - vt0);
        } else {
          const res = await runBaselineAgent(history, { provider, surface, oracle });
          const staged = res.stagedBatches.map((b) => ({
            pack: b.pack,
            operations: b.operations as unknown as StagedOperation[],
          }));
          const ev = evaluateVagueRequest(v, res.added, staged);
          vagueRecords.push(ev);
          logProgress(v.id, ev.askedWhenShouldHave, Date.now() - vt0);
        }
      }

      results.push({
        label: cfg.label,
        mode: cfg.mode,
        strategy: cfg.strategy,
        contextMode: cfg.contextMode,
        cheapModelId: cfg.cheapModel.id,
        midModelId: cfg.midModel.id,
        totalCostPicos: budget.spentPicos,
        tasks: taskRecords,
        followups: followupRecords,
        vague: vagueRecords,
      });
    }
  } finally {
    sandboxRunner.dispose();
  }

  return results;
}

export function buildDefaultConfigs(options: {
  models: readonly string[];
  strategy: RoutingStrategy;
  contextMode: ContextMode;
  mode: BenchmarkMode;
  midModelId?: string;
  overrides?: Readonly<Record<string, Partial<BenchmarkModelSpec>>>;
}): RunConfigSpec[] {
  const midSpec = resolveModelSpec(options.midModelId ?? 'gpt-6.1-terra-high', options.overrides);
  const haikuSpec = resolveModelSpec('claude-haiku-5-5', options.overrides);
  return options.models.map((modelId) => {
    const spec = resolveModelSpec(modelId, options.overrides);
    let cheapModel: BenchmarkModelSpec;
    let midModel: BenchmarkModelSpec;
    if (options.strategy === 'always-cheap') {
      cheapModel = spec;
      midModel = spec.tier === 'mid' ? spec : midSpec;
    } else if (options.strategy === 'always-mid') {
      cheapModel = spec.tier === 'cheap' ? spec : haikuSpec;
      midModel = spec;
    } else {
      cheapModel = spec;
      midModel = midSpec;
    }
    return {
      label: `${modelId} (${options.mode}, ${options.strategy}, ${options.contextMode})`,
      mode: options.mode,
      strategy: options.strategy,
      contextMode: options.contextMode,
      cheapModel,
      midModel,
    };
  });
}
