import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, type Page } from '@playwright/test';
import type { RunResult } from '../../../src/lib/codemode/sandbox';
import type { SurfaceDocument } from '../../../src/lib/codemode/generate/surface';
import { CORPUS_DIR, GOLDEN_DIR, readSnapshot, surfaceOf, withFakeAddon } from '../../../test/helpers/codemode';
import type { World } from '../../../test/helpers/world';
import type { SandboxProbe } from '../../sandbox';

export type { SandboxProbe };

export const surfaces: Record<string, SurfaceDocument> = {
  'plugin-current': surfaceOf(readSnapshot('plugin-current')),
  'with-addon': surfaceOf(withFakeAddon(readSnapshot('plugin-current'))),
};

interface Expectation {
  task: string;
  registry: string;
  result: 'pass' | 'fail';
  run?: { ok: boolean };
}

export const corpus = readdirSync(CORPUS_DIR)
  .filter((name) => name.endsWith('.ts'))
  .sort()
  .map((name) => {
    const stem = name.replace(/\.ts$/, '');
    const worldFile = join(CORPUS_DIR, `${stem}.world.json`);
    return {
      stem,
      program: readFileSync(join(CORPUS_DIR, name), 'utf8'),
      expect: JSON.parse(readFileSync(join(CORPUS_DIR, `${stem}.expect.json`), 'utf8')) as Expectation,
      world: (existsSync(worldFile) ? JSON.parse(readFileSync(worldFile, 'utf8')) : { packs: ['bench'] }) as World,
    };
  });

export const WORLD: World = { packs: ['bench'], resources: { bench: [{ type: 'items', path: 'items/a1.yml', id: 'a1' }] } };

/** One program through the page's whole pipeline (type-check Worker, then the sandbox). */
export function checkAndRun(page: Page, program: string, world: World = WORLD, limits: object = {}, host: 'world' | 'silent' = 'world') {
  return page.evaluate(([p, s, w, l, h]) => window.__sbx!.checkAndRun(p as string, s as never, w as never, l as never, h as never), [program, surfaces['plugin-current'], world, limits, host] as const);
}

/** The corpus through the real sandbox: every record must be byte for byte the golden the core's own spec recorded. */
export async function expectCorpusGoldens(page: Page): Promise<number> {
  let compared = 0;
  for (const entry of corpus.filter((item) => item.expect.result === 'pass')) {
    const ran = await page.evaluate(([p, s, w]) => window.__sbx!.checkAndRun(p as string, s as never, w as never), [entry.program, surfaces[entry.expect.registry], entry.world] as const);
    expect(ran.check.ok, `${entry.stem} checks`).toBe(true);
    const found = ran.result as RunResult;
    const failedRun = entry.expect.run?.ok === false;
    const record = failedRun
      ? { task: entry.expect.task, registry: entry.expect.registry, ok: false, diagnostics: found.diagnostics, stats: found.stats }
      : { task: entry.expect.task, registry: entry.expect.registry, ok: found.ok, pack: found.pack, summary: found.summary, operations: found.operations, sources: found.sources, diagnostics: found.diagnostics, stats: found.stats };
    const golden = readFileSync(join(GOLDEN_DIR, failedRun ? 'runs' : 'ops', `${entry.stem}.json`), 'utf8');
    expect(`${JSON.stringify(record, null, 2)}\n`, entry.stem).toBe(golden);
    compared += 1;
  }
  return compared;
}
