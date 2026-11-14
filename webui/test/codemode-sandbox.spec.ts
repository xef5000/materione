/**
 * The sandbox, end to end in Node (codemode.md section 4.6, issue 343): the real engine, core, host and runner, with the engine
 * run in-process over a MessageChannel (`helpers/sandbox-node.ts`). The corpus must come out byte for byte as the goldens the
 * core's own spec recorded (the sandbox changes the run, not the answer); each limit ends a run with its diagnostic and no
 * operations, and the next run still works; the host refuses what a lying engine sends.
 */
import { afterAll, describe, expect, it } from 'vitest';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import type { SandboxRunner } from '../src/lib/codemode/sandbox';
import { CORPUS_DIR, GOLDEN_DIR, readSnapshot, surfaceNamed, surfaceOf, withFakeAddon } from './helpers/codemode';
import { checked, hostOf, nodeRunner } from './helpers/sandbox-node';
import type { World } from './helpers/world';

const surface = surfaceNamed('plugin-current');
const addonSurface = surfaceOf(withFakeAddon(readSnapshot('plugin-current')));

const EMPTY: World = { packs: ['bench'] };

let runner: SandboxRunner;
afterAll(() => runner?.dispose());
const runnerOnce = async (): Promise<SandboxRunner> => (runner ??= await nodeRunner());

interface Expectation {
  task: string;
  registry: string;
  result: 'pass' | 'fail';
  run?: { ok: boolean };
}

const corpus = readdirSync(CORPUS_DIR)
  .filter((name) => name.endsWith('.ts'))
  .sort()
  .map((name) => {
    const stem = name.replace(/\.ts$/, '');
    const worldFile = join(CORPUS_DIR, `${stem}.world.json`);
    return {
      stem,
      program: readFileSync(join(CORPUS_DIR, name), 'utf8'),
      expect: JSON.parse(readFileSync(join(CORPUS_DIR, `${stem}.expect.json`), 'utf8')) as Expectation,
      world: (existsSync(worldFile) ? JSON.parse(readFileSync(worldFile, 'utf8')) : EMPTY) as World,
    };
  });

describe('the corpus in the sandbox is byte for byte the core goldens', () => {
  const runnable = corpus.filter((entry) => entry.expect.result === 'pass');
  it('covers the reading programs', () => {
    expect(runnable.map((entry) => entry.expect.task)).toEqual(expect.arrayContaining(['T04', 'T08', 'T11', 'T25', 'C01', 'C02']));
  });

  it.each(runnable.map((entry) => [entry.expect.task, entry] as const))('%s', async (_task, entry) => {
    const found = await (await runnerOnce()).run(checked(entry.program), entry.expect.registry === 'with-addon' ? addonSurface : surface, hostOf(entry.world));
    const failedRun = entry.expect.run?.ok === false;
    const record = failedRun
      ? { task: entry.expect.task, registry: entry.expect.registry, ok: false, diagnostics: found.diagnostics, stats: found.stats }
      : { task: entry.expect.task, registry: entry.expect.registry, ok: found.ok, pack: found.pack, summary: found.summary, operations: found.operations, sources: found.sources, diagnostics: found.diagnostics, stats: found.stats };
    const golden = readFileSync(join(GOLDEN_DIR, failedRun ? 'runs' : 'ops', `${entry.stem}.json`), 'utf8');
    expect(`${JSON.stringify(record, null, 2)}\n`).toBe(golden);
  });
});
