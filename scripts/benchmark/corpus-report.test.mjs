import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { BENCHMARK_DOC, checkBenchmarkDoc, computeReport, loadCorpus } from './corpus-report.mjs';

describe('corpus-report', () => {
  it('computes 22/29 passing tasks and 3/5 passing follow-ups from the committed corpus', () => {
    const entries = loadCorpus();
    const report = computeReport(entries);
    assert.equal(report.tasks.total, 29);
    assert.equal(report.tasks.pass, 22);
    assert.equal(report.tasks.fail, 7);
    assert.equal(report.followups.total, 5);
    assert.equal(report.followups.pass, 3);
    assert.equal(report.followups.fail, 2);
    assert.equal(report.corpusOnly.total, 3);
    assert.deepEqual(report.replay.withNote.sort(), ['C03', 'T16', 'T19']);
    assert.equal(report.gapTasks.get('TIME').length, 0);
    assert.equal(report.gapTasks.get('RULE').length, 0);
    assert.equal(report.cumulative.at(-1).passes, 29);
  });

  it('matches docs/architecture/agent-benchmark.md with zero drift', () => {
    const report = computeReport(loadCorpus());
    const docText = readFileSync(BENCHMARK_DOC, 'utf8');
    assert.deepEqual(checkBenchmarkDoc(report, docText), []);
  });

  it('detects drift when a task verdict or summary count disagrees with the doc', () => {
    const report = computeReport(loadCorpus());
    const docText = readFileSync(BENCHMARK_DOC, 'utf8').replace('**22 pass, 0 partial, 7 fail**', '**21 pass, 0 partial, 8 fail**');
    const errors = checkBenchmarkDoc(report, docText);
    assert.ok(errors.some((e) => e.includes('§2 summary says 21 pass')));
  });
});
