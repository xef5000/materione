import { formatDollars, PICOS_PER_DOLLAR } from './models';
import type { ConfigRunResult, TaskRunRecord } from './runner';

export interface BenchmarkArtifact {
  readonly generatedAt: string;
  readonly gitCommit: string;
  readonly oracle: 'jvm' | 'memory';
  readonly mock: boolean;
  readonly maxDollars: number;
  readonly totalSpentDollars: string;
  readonly configs: readonly ConfigRunResult[];
}

function summarizeTasks(records: readonly TaskRunRecord[]) {
  const buildable = records.filter((r) => r.expectedResult === 'pass');
  const missing = records.filter((r) => r.expectedResult === 'fail');
  const accepted = buildable.filter((r) => r.accepted).length;
  const s18Accepted = buildable.filter((r) => r.s18AdjustedAccepted).length;
  const firstTurn = buildable.filter((r) => r.firstTurnPass).length;
  const honest = missing.filter((r) => r.missingPrimitiveHonest).length;
  const avgTurns =
    records.length > 0 ? (records.reduce((s, r) => s + r.turns, 0) / records.length).toFixed(2) : '0.00';
  const avgWallSec =
    records.length > 0
      ? (records.reduce((s, r) => s + r.wallTimeMs, 0) / records.length / 1000).toFixed(2)
      : '0.00';
  const inputTokens = records.reduce((s, r) => s + r.usage.inputTokens, 0);
  const cacheReadTokens = records.reduce((s, r) => s + r.usage.cachedInputTokens, 0);
  const cacheWriteTokens = records.reduce((s, r) => s + r.usage.cacheWriteTokens, 0);
  const outputTokens = records.reduce((s, r) => s + r.usage.outputTokens, 0);
  const totalPicos = records.reduce((s, r) => s + r.costPicos, 0);
  const costPerAccepted =
    accepted > 0 ? formatDollars(Math.round(totalPicos / accepted)) : 'n/a';
  const avgToolSteps =
    records.length > 0 ? (records.reduce((s, r) => s + r.toolSteps, 0) / records.length).toFixed(2) : '0.00';
  const avgFailed =
    records.length > 0 ? (records.reduce((s, r) => s + r.failedCalls, 0) / records.length).toFixed(2) : '0.00';

  return {
    buildableTotal: buildable.length,
    missingTotal: missing.length,
    accepted,
    s18Accepted,
    firstTurn,
    honest,
    avgTurns,
    avgWallSec,
    inputTokens,
    cacheReadTokens,
    cacheWriteTokens,
    outputTokens,
    totalPicos,
    costPerAccepted,
    avgToolSteps,
    avgFailed,
  };
}

export function renderMarkdownReport(artifact: BenchmarkArtifact): string {
  const lines: string[] = [];
  lines.push('# Materione Code-Mode Benchmark Report');
  lines.push('');
  lines.push(`- **Generated:** ${artifact.generatedAt}`);
  lines.push(`- **Git commit:** \`${artifact.gitCommit}\``);
  lines.push(`- **Oracle:** \`${artifact.oracle}\` (${artifact.mock ? 'mock/$0.00' : 'live AI Gateway'})`);
  lines.push(`- **Budget cap:** $${artifact.maxDollars.toFixed(2)} | **Total spent:** $${artifact.totalSpentDollars}`);
  lines.push('');

  lines.push('## 1. Model & Routing Summary (issue 385)');
  lines.push('');
  lines.push(
    '| Run / Config | Buildable Accepted | S18-Adjusted | First-Turn Pass | Avg Turns | Input / Cache-Read / Cache-Write / Output Tokens | Total Cost ($) | Cost / Accepted Task ($) | Avg Wall Time (s) | Missing-Primitive Honesty |',
  );
  lines.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const cfg of artifact.configs) {
    const s = summarizeTasks(cfg.tasks);
    lines.push(
      `| ${cfg.label} | ${s.accepted}/${s.buildableTotal} | ${s.s18Accepted}/${s.buildableTotal} | ${s.firstTurn}/${s.buildableTotal} | ${s.avgTurns} | ${s.inputTokens} / ${s.cacheReadTokens} / ${s.cacheWriteTokens} / ${s.outputTokens} | $${formatDollars(s.totalPicos)} | ${s.costPerAccepted === 'n/a' ? 'n/a' : `$${s.costPerAccepted}`} | ${s.avgWallSec} | ${s.honest}/${s.missingTotal} |`,
    );
  }
  lines.push('');
  lines.push(
    '> **S18 footnote:** `T16` and `T19` stage furniture scaffolds whose default `block/<id>` texture raises `MTR-0009` in strict `authoring.transaction` dry-runs (`agent-benchmark.md` S18). Both strict server `accepted` and S18-adjusted acceptance are reported.',
  );
  lines.push('');

  lines.push('## 2. Context Size Comparison (`index` vs `full-dts`)');
  lines.push('');
  lines.push('| Config | Context Mode | Buildable Accepted | Cache-Read Tokens | Total Cost ($) |');
  lines.push('| --- | --- | --- | --- | --- |');
  for (const cfg of artifact.configs) {
    const s = summarizeTasks(cfg.tasks);
    lines.push(
      `| ${cfg.label} | ${cfg.contextMode} | ${s.accepted}/${s.buildableTotal} | ${s.cacheReadTokens} | $${formatDollars(s.totalPicos)} |`,
    );
  }
  lines.push('');

  lines.push('## 3. Code Mode vs Tool-per-Operation Baseline (issue 349)');
  lines.push('');
  lines.push('| Config | Mode | First-Attempt Pass | Avg Fix Iterations | Avg Tool Steps | Total Tokens | Total Cost ($) | Follow-ups Pass & Regression-Free |');
  lines.push('| --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const cfg of artifact.configs) {
    const s = summarizeTasks(cfg.tasks);
    const totalTokens = s.inputTokens + s.cacheReadTokens + s.cacheWriteTokens + s.outputTokens;
    const fPass = cfg.followups.filter((f) => f.accepted && f.regressionFree).length;
    lines.push(
      `| ${cfg.label} | ${cfg.mode} | ${s.firstTurn}/${s.buildableTotal} | ${s.avgFailed} | ${s.avgToolSteps} | ${totalTokens} | $${formatDollars(s.totalPicos)} | ${fPass}/${cfg.followups.length} |`,
    );
  }
  lines.push('');

  const vagueConfigs = artifact.configs.filter((c) => c.vague.length > 0);
  if (vagueConfigs.length > 0) {
    lines.push('## 4. Vague-Request Rubric (`V01`–`V12`)');
    lines.push('');
    lines.push('| Config | ID | Title | Should Ask | Asked? | Verdict | Sub-Features Matched | Wrong Guesses | Human Review |');
    lines.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- |');
    for (const cfg of vagueConfigs) {
      for (const v of cfg.vague) {
        lines.push(
          `| ${cfg.label} | ${v.id} | ${v.title} | ${v.shouldAsk ? 'yes' : 'no'} | ${v.askedClarifyingQuestion ? 'yes' : 'no'} | ${v.askedWhenShouldHave} | ${v.subFeaturesMatched.length}/${v.subFeaturesTotal} | ${v.wrongGuesses} | ${v.humanReviewNote} |`,
        );
      }
    }
    lines.push('');
  }

  lines.push('## 5. Per-Task Breakdown');
  lines.push('');
  lines.push('| Config | Task | Kind | Expected | Accepted | S18 | First-Turn | Honest | Turns | Failed | Cost ($) | Stop |');
  lines.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const cfg of artifact.configs) {
    for (const r of [...cfg.tasks, ...cfg.followups]) {
      lines.push(
        `| ${cfg.label} | ${r.id} | ${r.kind} | ${r.expectedResult} | ${r.accepted ? 'yes' : 'no'} | ${r.s18AdjustedAccepted ? 'yes' : 'no'} | ${r.firstTurnPass ? 'yes' : 'no'} | ${r.missingPrimitiveHonest ? 'yes' : 'no'} | ${r.turns} | ${r.failedCalls} | $${formatDollars(r.costPicos)} | ${r.stopReason} |`,
      );
    }
  }
  lines.push('');
  return lines.join('\n');
}

export function totalSpentDollars(configs: readonly ConfigRunResult[]): string {
  const totalPicos = configs.reduce((acc, c) => acc + c.totalCostPicos, 0);
  return (totalPicos / PICOS_PER_DOLLAR).toFixed(4);
}
