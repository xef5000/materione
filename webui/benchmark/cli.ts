import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { ContextMode } from './context-mode';
import { parseModelOverride, type BenchmarkModelSpec } from './models';
import { defaultRegistrySnapshotPath, JvmBenchmarkOracle, MemoryBenchmarkOracle, type BenchmarkOracle } from './oracle';
import { renderMarkdownReport, totalSpentDollars, type BenchmarkArtifact } from './report';
import type { RoutingStrategy } from './routing';
import {
  buildDefaultConfigs,
  runBenchmarkSuite,
  type BenchmarkMode,
  type BenchmarkSuite,
} from './runner';

function printHelp(): void {
  console.log(`Materione Code-Mode Benchmark CLI (issues 385 & 349)

Usage:
  npm --prefix webui run benchmark -- [options]

Options:
  --help                          Show this help
  --mock                          Run with scripted offline mock provider ($0.00)
  --oracle jvm|memory             Oracle backend (default: memory)
  --models id1,id2                Comma-separated model presets (default: claude-haiku-5-5)
                                  Key presets: claude-haiku-5-5, claude-haiku-5-5-xhigh,
                                  gpt-6-luna-medium, gpt-6-luna-xhigh, deepseek-v4.1-flash-1017,
                                  deepseek-v4.1-flash-1017-max, gemini-3.8-flash,
                                  gemini-4-hydrogen-xhigh, gpt-6.1-terra-medium,
                                  gpt-6.1-terra-high, gpt-6.1-sol-medium, gpt-6.1-sol-high,
                                  gpt-6.1-sol-xhigh, gpt-6.1-sol-max, claude-sonnet-5-5,
                                  claude-sonnet-5-5-high
  --mid-model id                  Mid-tier model for escalate strategy (default: gpt-6.1-terra-high)
  --model-override id=json        Override model pricing/fields (repeatable)
  --strategy always-cheap|always-mid|escalate   Routing strategy (default: always-cheap)
  --context index|full-dts        Context mode (default: index)
  --mode codemode|tool-baseline   Agent mode (default: codemode)
  --suite tasks|followups|vague|all   Suite to run (default: all)
  --tasks T04,T08,T27,F3          Comma-separated task/followup/vague IDs
  --max-dollars <N>               Hard spend cap in USD (required >0 for live; default: 1.00)
  --out-dir <dir>                 Output directory for report.json & report.md
`);
}

function findRepoRoot(): string {
  const here = dirname(fileURLToPath(import.meta.url));
  // When bundled to webui/build/benchmark-cli.mjs or run from webui/benchmark/cli.ts, repo root is ../..
  return resolve(here, '..', '..');
}

function gitHead(repoRoot: string): string {
  try {
    return execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) {
    printHelp();
    return;
  }

  let mock = false;
  let oracleKind: 'jvm' | 'memory' = 'memory';
  let models = ['claude-haiku-5-5'];
  let midModelId = 'gpt-6.1-terra-high';
  let strategy: RoutingStrategy = 'always-cheap';
  let contextMode: ContextMode = 'index';
  let mode: BenchmarkMode = 'codemode';
  let suite: BenchmarkSuite = 'all';
  let taskFilter: string[] | undefined;
  let maxDollars = 1.0;
  let outDir: string | undefined;
  const overrides: Record<string, Partial<BenchmarkModelSpec>> = {};

  for (let i = 0; i < args.length; i++) {
    const arg = args[i]!;
    const next = (): string => {
      const v = args[++i];
      if (v === undefined) throw new Error(`Missing value for ${arg}`);
      return v;
    };
    switch (arg) {
      case '--mock':
        mock = true;
        break;
      case '--oracle':
        oracleKind = next() as 'jvm' | 'memory';
        break;
      case '--models':
        models = next().split(',').map((s) => s.trim()).filter(Boolean);
        break;
      case '--mid-model':
        midModelId = next();
        break;
      case '--model-override': {
        const { id, patch } = parseModelOverride(next());
        overrides[id] = patch;
        break;
      }
      case '--strategy':
        strategy = next() as RoutingStrategy;
        break;
      case '--context':
        contextMode = next() as ContextMode;
        break;
      case '--mode':
        mode = next() as BenchmarkMode;
        break;
      case '--suite':
        suite = next() as BenchmarkSuite;
        break;
      case '--tasks':
        taskFilter = next().split(',').map((s) => s.trim()).filter(Boolean);
        break;
      case '--max-dollars':
        maxDollars = Number(next());
        break;
      case '--out-dir':
        outDir = next();
        break;
      default:
        throw new Error(`Unknown argument: ${arg}`);
    }
  }

  const repoRoot = findRepoRoot();
  const configs = buildDefaultConfigs({ models, strategy, contextMode, mode, midModelId, overrides });

  let gatewayConfig;
  if (!mock) {
    const accountId = (process.env.CF_ACCOUNT_ID ?? '').trim();
    const gatewayId = (process.env.CF_AIG_GATEWAY_ID ?? '').trim();
    const token = (process.env.CF_AIG_TOKEN ?? '').trim();
    if (!accountId || !gatewayId || !token) {
      throw new Error(
        'Live gateway mode requires CF_ACCOUNT_ID, CF_AIG_GATEWAY_ID, and CF_AIG_TOKEN env vars (or pass --mock for $0.00 offline run).',
      );
    }
    gatewayConfig = { accountId, gatewayId, token };
  }

  const oracle: BenchmarkOracle =
    oracleKind === 'jvm'
      ? new JvmBenchmarkOracle(repoRoot)
      : new MemoryBenchmarkOracle(defaultRegistrySnapshotPath(repoRoot));

  try {
    const results = await runBenchmarkSuite({
      repoRoot,
      oracle,
      configs,
      suite,
      taskFilter,
      maxDollars: mock ? 0 : maxDollars,
      mock,
      gatewayConfig,
      modelOverrides: overrides,
    });

    const artifact: BenchmarkArtifact = {
      generatedAt: new Date().toISOString(),
      gitCommit: gitHead(repoRoot),
      oracle: oracleKind,
      mock,
      maxDollars: mock ? 0 : maxDollars,
      totalSpentDollars: totalSpentDollars(results),
      configs: results,
    };
    const markdown = renderMarkdownReport(artifact);

    const targetDir = outDir
      ? resolve(repoRoot, outDir)
      : join(repoRoot, 'webui', 'build', 'benchmark-reports');
    mkdirSync(targetDir, { recursive: true });
    writeFileSync(join(targetDir, 'report.json'), `${JSON.stringify(artifact, null, 2)}\n`, 'utf8');
    writeFileSync(join(targetDir, 'report.md'), `${markdown}\n`, 'utf8');

    console.log(markdown);
    console.log(`Saved report.json and report.md to ${targetDir}`);
  } finally {
    await oracle.close();
  }
}

void main().catch((err) => {
  console.error(err instanceof Error ? err.message : String(err));
  process.exitCode = 1;
});
