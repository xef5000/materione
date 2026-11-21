#!/usr/bin/env node
// Machine-verified benchmark corpus tally and doc-drift check (issue #349, agent-benchmark.md).
//
// Reads fixtures/codemode/corpus/*.expect.json and fixtures/golden/codemode/replay/*.json,
// computes the pass/fail counts, replay status, gap tallies, and ranked cumulative pass counts,
// and with --check verifies that docs/architecture/agent-benchmark.md matches the corpus.

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const CORPUS_DIR = join(ROOT, 'fixtures', 'codemode', 'corpus');
export const REPLAY_DIR = join(ROOT, 'fixtures', 'golden', 'codemode', 'replay');
export const BENCHMARK_DOC = join(ROOT, 'docs', 'architecture', 'agent-benchmark.md');

export const CLOSED_GAPS = ['RULE', 'TRIG-ENTITY', 'TRIG-PLAYER', 'TIME', 'TARGET', 'CMD'];

export const RANKING = [
  { rank: 1, gaps: ['RULE', 'TRIG-ENTITY', 'TRIG-PLAYER'] },
  { rank: 2, gaps: ['TIME'] },
  { rank: 3, gaps: ['VERB'] },
  { rank: 4, gaps: ['CMD'] },
  { rank: 5, gaps: ['TARGET'] },
  { rank: 6, gaps: ['TIMER'] },
  { rank: 7, gaps: ['STATE'] },
  { rank: 8, gaps: ['CUSTODY'] },
  { rank: 9, gaps: ['VIEW'] },
  { rank: 10, gaps: ['QUERY'] },
  { rank: 11, gaps: ['PROVIDER'] },
  { rank: 12, gaps: ['QTY'] },
];

export function loadCorpus(corpusDir = CORPUS_DIR, replayDir = REPLAY_DIR) {
  const entries = readdirSync(corpusDir)
    .filter((name) => name.endsWith('.expect.json'))
    .sort()
    .map((name) => {
      const stem = name.replace(/\.expect\.json$/, '');
      const expect = JSON.parse(readFileSync(join(corpusDir, name), 'utf8'));
      const replayPath = join(replayDir, `${stem}.json`);
      const replay = existsSync(replayPath) ? JSON.parse(readFileSync(replayPath, 'utf8')) : null;
      return { stem, expect, replay };
    });
  return entries;
}

export function computeReport(entries) {
  const tasks = entries.filter((e) => e.expect.kind === 'task');
  const followups = entries.filter((e) => e.expect.kind === 'followup');
  const corpusOnly = entries.filter((e) => e.expect.kind === 'corpus');

  const taskPass = tasks.filter((e) => e.expect.result === 'pass');
  const taskFail = tasks.filter((e) => e.expect.result === 'fail');
  const followupPass = followups.filter((e) => e.expect.result === 'pass');
  const followupFail = followups.filter((e) => e.expect.result === 'fail');

  const replayClean = [];
  const replayWithNote = [];
  for (const entry of [...taskPass, ...followupPass, ...corpusOnly]) {
    if (!entry.replay) continue;
    const hasError = !entry.replay.accepted || (entry.replay.diagnostics ?? []).some((d) => d.severity === 'error');
    if (hasError || entry.expect.replay?.note) {
      replayWithNote.push(entry.expect.task);
    } else {
      replayClean.push(entry.expect.task);
    }
  }

  const gapTasks = new Map();
  for (const gap of CLOSED_GAPS) gapTasks.set(gap, []);
  for (const entry of [...taskFail, ...followupFail]) {
    for (const gap of entry.expect.gaps ?? []) {
      if (!gapTasks.has(gap)) gapTasks.set(gap, []);
      gapTasks.get(gap).push(entry.expect.task);
    }
  }

  // Cumulative pass counts along the ranked primitive issues
  const resolved = new Set();
  const cumulative = [{ rank: 0, passes: taskPass.length, newlyPassing: [] }];
  let prevCount = taskPass.length;
  for (const step of RANKING) {
    for (const gap of step.gaps) resolved.add(gap);
    const nowPassing = tasks.filter(
      (e) => e.expect.result === 'pass' || (e.expect.gaps ?? []).every((g) => resolved.has(g)),
    );
    const newly = nowPassing
      .filter((e) => e.expect.result === 'fail' && (e.expect.gaps ?? []).some((g) => step.gaps.includes(g)))
      .filter((e) => (e.expect.gaps ?? []).every((g) => resolved.has(g)))
      .map((e) => e.expect.task);
    cumulative.push({
      rank: step.rank,
      gaps: step.gaps,
      passes: nowPassing.length,
      delta: nowPassing.length - prevCount,
      newlyPassing: newly,
    });
    prevCount = nowPassing.length;
  }

  return {
    tasks: { total: tasks.length, pass: taskPass.length, fail: taskFail.length, entries: tasks },
    followups: { total: followups.length, pass: followupPass.length, fail: followupFail.length, entries: followups },
    corpusOnly: { total: corpusOnly.length, entries: corpusOnly },
    replay: { clean: replayClean, withNote: replayWithNote },
    gapTasks,
    cumulative,
  };
}

function splitRow(line) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim());
}

function sortedEq(a, b) {
  const sa = [...a].sort();
  const sb = [...b].sort();
  return sa.length === sb.length && sa.every((v, i) => v === sb[i]);
}

export function checkBenchmarkDoc(report, docText) {
  const errors = [];
  const lines = docText.split(/\r?\n/);

  // 1. §2 task table
  const docTasks = new Map();
  for (const line of lines) {
    const m = /^\|\s*(T\d{2})\s*\|/.exec(line);
    if (!m) continue;
    const cells = splitRow(line);
    if (cells.length < 5) continue;
    const [, , , verdictCell, failsOnCell] = cells;
    const verdict = /^Pass\b/i.test(verdictCell) ? 'pass' : /^Fail\b/i.test(verdictCell) ? 'fail' : 'other';
    const gaps = failsOnCell && !/by-hand route/i.test(failsOnCell)
      ? failsOnCell.split(/[\s,]+/).filter(Boolean)
      : [];
    docTasks.set(m[1], { verdict, gaps });
  }

  for (const entry of report.tasks.entries) {
    const id = entry.expect.task;
    const doc = docTasks.get(id);
    if (!doc) {
      errors.push(`§2 table missing row for ${id}`);
      continue;
    }
    if (doc.verdict !== entry.expect.result) {
      errors.push(`§2 ${id}: doc verdict "${doc.verdict}" != corpus "${entry.expect.result}"`);
    }
    if (!sortedEq(doc.gaps, entry.expect.gaps ?? [])) {
      errors.push(`§2 ${id}: doc gaps [${doc.gaps.join(', ')}] != corpus [${(entry.expect.gaps ?? []).join(', ')}]`);
    }
  }

  // 2. §2 summary count
  const summaryMatch = /\*\*(\d+)\s+pass,\s+(\d+)\s+partial,\s+(\d+)\s+fail\*\*/.exec(docText);
  if (!summaryMatch) {
    errors.push('§2 summary line "**N pass, N partial, N fail**" not found');
  } else {
    const [, docPass, docPartial, docFail] = summaryMatch.map(Number);
    if (docPass !== report.tasks.pass || docPartial !== 0 || docFail !== report.tasks.fail) {
      errors.push(
        `§2 summary says ${docPass} pass, ${docPartial} partial, ${docFail} fail; corpus is ${report.tasks.pass} pass, 0 partial, ${report.tasks.fail} fail`,
      );
    }
  }

  // 3. §4.1 gap table
  const docGaps = new Map();
  for (const line of lines) {
    const m = /^\|\s*\*\*([A-Z-]+)\*\*\s*\|/.exec(line);
    if (!m) continue;
    const cells = splitRow(line);
    if (cells.length < 5) continue;
    const gapId = m[1];
    const tasksCell = cells[3];
    const unblocksCell = cells[4];
    const closed = /\*\*Closed by #\d+\*\*/i.test(unblocksCell);
    const listedIds = [...tasksCell.matchAll(/\b(T\d{2}|F\d+)\b/g)].map((x) => x[1]);
    // Ignore "T03 by hand" annotation in CUSTODY row
    const filtered = listedIds.filter((id) => !(gapId === 'CUSTODY' && id === 'T03' && /T03 by hand/.test(tasksCell)));
    docGaps.set(gapId, { closed, tasks: filtered });
  }

  for (const [gap, corpusIds] of report.gapTasks.entries()) {
    const doc = docGaps.get(gap);
    if (!doc) {
      errors.push(`§4.1 table missing row for ${gap}`);
      continue;
    }
    if (CLOSED_GAPS.includes(gap)) {
      if (!doc.closed) errors.push(`§4.1 ${gap} should be marked Closed`);
      if (corpusIds.length !== 0) errors.push(`§4.1 ${gap} is closed in doc, but corpus still fails on it: ${corpusIds.join(', ')}`);
    } else if (!sortedEq(doc.tasks, corpusIds)) {
      errors.push(`§4.1 ${gap}: doc tasks [${doc.tasks.join(', ')}] != corpus [${corpusIds.join(', ')}]`);
    }
  }

  // 4. §4.2 ranking table
  const docRanks = new Map();
  for (const line of lines) {
    const m = /^\|\s*(\d+)\s*\|/.exec(line);
    if (!m) continue;
    const cells = splitRow(line);
    if (cells.length < 6) continue;
    const rank = Number(m[1]);
    const passesMatch = /^(\d+)/.exec(cells[3]);
    if (passesMatch) docRanks.set(rank, Number(passesMatch[1]));
  }

  for (const step of report.cumulative) {
    if (step.rank <= 1) continue; // ranks 0 and 1 are historical pre-#328 baselines (10 and 13 of 29)
    const docPasses = docRanks.get(step.rank);
    if (docPasses === undefined) {
      errors.push(`§4.2 ranking missing rank ${step.rank}`);
    } else if (docPasses !== step.passes) {
      errors.push(`§4.2 rank ${step.rank}: doc cumulative ${docPasses} != corpus ${step.passes}`);
    }
  }

  return errors;
}

export function formatReport(report) {
  const lines = [];
  lines.push(`Tasks: ${report.tasks.pass} pass, ${report.tasks.fail} fail (of ${report.tasks.total})`);
  lines.push(`Follow-ups: ${report.followups.pass} pass, ${report.followups.fail} fail (of ${report.followups.total})`);
  lines.push(`Corpus-only: ${report.corpusOnly.total}`);
  lines.push(`Replay clean (${report.replay.clean.length}): ${report.replay.clean.join(', ')}`);
  lines.push(`Replay with note (${report.replay.withNote.length}): ${report.replay.withNote.join(', ')}`);
  lines.push('Gaps:');
  for (const [gap, ids] of report.gapTasks.entries()) {
    lines.push(`  ${gap.padEnd(12)} ${String(ids.length).padStart(2)}  ${ids.join(', ')}`);
  }
  lines.push('Ranking (cumulative passes):');
  for (const step of report.cumulative) {
    if (step.rank === 0) continue;
    lines.push(`  rank ${String(step.rank).padStart(2)}: ${String(step.passes).padStart(2)}/29  (+${step.newlyPassing.join(', ')})`);
  }
  return lines.join('\n');
}

function main(argv) {
  const check = argv.includes('--check');
  const entries = loadCorpus();
  const report = computeReport(entries);
  if (!check) {
    console.log(formatReport(report));
    return 0;
  }
  const docText = readFileSync(BENCHMARK_DOC, 'utf8');
  const errors = checkBenchmarkDoc(report, docText);
  if (errors.length > 0) {
    console.error('Benchmark corpus drift detected:');
    for (const err of errors) console.error(`  - ${err}`);
    return 1;
  }
  console.log(`corpus-report: ${report.tasks.pass}/${report.tasks.total} tasks pass, ${report.followups.pass}/${report.followups.total} follow-ups pass, docs in sync.`);
  return 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
