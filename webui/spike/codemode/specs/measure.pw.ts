// SPIKE (#340) - throwaway. Measurements: cold start, per-run overhead, async host calls, the TypeScript
// checker. Numbers go to the console and to build/spike-codemode/measurements.json (quoted in codemode.md 13).
import { test, expect, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

declare global { interface Window { spike: any } }
const stats = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  return { n: s.length, min: s[0]!, median: s[Math.floor(s.length / 2)]!, p90: s[Math.floor(s.length * 0.9)]!, max: s[s.length - 1]! };
};
const round = (o: any): any => (typeof o === 'number' ? Math.round(o * 10) / 10 : o && typeof o === 'object' ? Object.fromEntries(Object.entries(o).map(([k, v]) => [k, round(v)])) : o);

const out: Record<string, unknown> = {};
test.afterAll(() => {
  const dir = resolve(process.cwd(), 'build/spike-codemode');
  let sizes = {};
  try { sizes = JSON.parse(readFileSync(resolve(dir, 'sizes.json'), 'utf8')); } catch { /* ignore */ }
  writeFileSync(resolve(dir, 'measurements.json'), JSON.stringify({ browser: 'chromium (Playwright 1.64.1 build)', sizes, ...out }, null, 2));
});

async function boot(page: Page) {
  await page.goto('/harness.html');
  await page.waitForFunction(() => document.title === 'spike ready');
}

/** A realistic-size SDK declaration: 20 namespaces x 10 members = 200 members, plus the `m` entry point. */
function sdkDts(): string {
  const parts: string[] = ['interface Ref<T extends string> { readonly kind: T; readonly id: string }'];
  const ns: string[] = [];
  for (let i = 0; i < 20; i++) {
    const members: string[] = [];
    for (let j = 0; j < 10; j++) {
      members.push(`  op${j}(args: { id: string; amount?: number; tags?: readonly string[]; nested?: { a: number; b: 'x' | 'y' } }): Ref<'t${i}'>;`);
    }
    parts.push(`interface Ns${i} {\n${members.join('\n')}\n}`);
    ns.push(`  readonly ns${i}: Ns${i};`);
  }
  parts.push(`interface Sdk {\n${ns.join('\n')}\n  read(op: string, args?: unknown): Promise<any>;\n  stage(op: unknown): void;\n  log(...a: unknown[]): void;\n}`);
  return parts.join('\n');
}
const PROGRAM_25 = `const a = m.ns0.op0({ id: 'sword', amount: 3 });
const b = m.ns1.op1({ id: 'axe', tags: ['x', 'y'] });
const c = m.ns2.op2({ id: 'bow', nested: { a: 1, b: 'x' } });
const names: string[] = [];
for (let i = 0; i < 5; i++) {
  const r = m.ns3.op3({ id: 'n' + i, amount: i });
  names.push(r.id);
}
const d = m.ns4.op4({ id: 'shield' });
const e = m.ns5.op5({ id: 'helm', tags: names });
const f = m.ns6.op6({ id: 'boots', amount: names.length });
function pick(x: { id: string }, y: { id: string }) {
  return x.id < y.id ? x : y;
}
const g = pick(a, b);
const h = pick(c, d);
const k = m.ns7.op7({ id: g.id + h.id });
const l = m.ns8.op8({ id: e.id, nested: { a: 2, b: 'y' } });
const n = m.ns9.op9({ id: f.id });
m.log('staged', k.id, l.id, n.id);
const summary = { a: a.id, b: b.id, c: c.id, count: names.length };
m.stage({ op: 'summary', ...summary });
const tags = ['p', 'q', 'r'].map((t) => t.toUpperCase());
m.stage({ op: 'tags', tags });
return summary;`;

test('cold start, per-run overhead and async reads in both engine modes', async ({ page }) => {
  await boot(page);
  for (const mode of ['frame', 'worker'] as const) {
    const cold = await page.evaluate(async (m) => {
      const rows: any[] = [];
      for (let i = 0; i < 9; i++) {
        const t0 = performance.now();
        const s = await window.spike.make(0, m);
        rows.push({ ...s.coldStart, wall: performance.now() - t0 });
        if (i < 8) s.destroy();
      }
      return rows;
    }, mode);
    const first = cold[0];
    const rest = cold.slice(1);
    const sb = await page.evaluate(() => window.spike.created.length - 1);
    const runs = await page.evaluate(async ([i]) => {
      const s = window.spike.created[i as number];
      const rows: any[] = [];
      const firstT0 = performance.now();
      const first = await s.runTs(`m.stage({ op: 'x' }); return 1;`);
      const firstMs = performance.now() - firstT0;
      for (let k = 0; k < 30; k++) {
        const t0 = performance.now();
        const r = await s.runTs(`m.stage({ op: 'x' }); return 1;`);
        rows.push({ total: performance.now() - t0, ...r.metrics });
      }
      // sequential and parallel async host calls
      const seq = await s.runTs(`for (let i = 0; i < 50; i++) await m.read('echo', i);`);
      const par = await s.runTs(`await Promise.all(Array.from({ length: 50 }, (_, i) => m.read('echo', i)));`);
      return { rows, firstMs, firstOk: first.ok, seqMs: seq.metrics.wallMs, parMs: par.metrics.wallMs };
    }, [sb] as const);
    const j = (k: string) => stats(runs.rows.map((r: any) => r[k]));
    out[`mode:${mode}`] = round({
      coldFirst: first,
      coldWarmFiles: { frameLoadMs: stats(rest.map((r: any) => r.frameLoadMs)), engineInitMs: stats(rest.map((r: any) => r.engineInitMs)), totalMs: stats(rest.map((r: any) => r.totalMs)) },
      firstRunMs: runs.firstMs,
      perRun: { totalMs: j('total'), engineWallMs: j('wallMs'), setupMs: j('setupMs'), transpileMs: j('transpileMs'), cpuMs: j('cpuMs') },
      reads50: { sequentialMs: runs.seqMs, perReadMs: runs.seqMs / 50, parallelMs: runs.parMs },
    });
    console.log(`MEASURE[${mode}]`, JSON.stringify(out[`mode:${mode}`]));
    expect(runs.firstOk).toBe(true);
  }
});

test('TypeScript checker in a Worker: size, cold start, 25-line program vs a 200-member .d.ts', async ({ page }) => {
  await boot(page);
  const dts = sdkDts();
  const res = await page.evaluate(async ([dts, prog]) => {
    const t0 = performance.now();
    const { TsClient } = window.spike;
    const ts = new TsClient(window.spike.tsWorkerUrl);
    const ping = await ts.ping();
    const workerColdMs = performance.now() - t0; // spawn + download-from-localhost + parse/eval of the bundle
    const tr0 = performance.now();
    const tr = await ts.transpile(prog);
    const transpileFirstMs = performance.now() - tr0;
    const c0 = performance.now();
    const first = await ts.check(prog, dts);
    const checkFirstMs = performance.now() - c0;
    const warm: number[] = [];
    let diags = first.diags;
    for (let i = 0; i < 10; i++) {
      const w0 = performance.now();
      const r = await ts.check(prog + '\n// ' + i, dts); // changed text, same declarations
      warm.push(performance.now() - w0);
      diags = r.diags;
    }
    const tw: number[] = [];
    for (let i = 0; i < 10; i++) { const w0 = performance.now(); await ts.transpile(prog + '\n// ' + i); tw.push(performance.now() - w0); }
    // an erroneous program: the diagnostic and its line (offset 0: wrapper keeps the body's line numbers)
    const bad = await ts.check(`const a = m.ns0.op0({ id: 5 });\nm.ns99.nope();\nconst z: string = 1;`, dts);
    ts.terminate();
    return { ping, workerColdMs, transpileFirstMs, checkFirstMs, warm, tw, firstDiags: first.diags, diags, bad: bad.diags, transpileSyntax: tr.syntax, jsLen: tr.js.length };
  }, [dts, PROGRAM_25] as const);
  expect(res.firstDiags).toEqual([]);
  expect(res.diags).toEqual([]);
  expect([...new Set(res.bad.map((d: any) => d.line))]).toEqual([1, 2, 3]);
  out.typescript = round({ dtsBytes: dts.length, dtsMembers: 200, programLines: PROGRAM_25.split('\n').length, workerColdMs: res.workerColdMs, transpileFirstMs: res.transpileFirstMs, transpileWarmMs: stats(res.tw), checkFirstMs: res.checkFirstMs, checkWarmMs: stats(res.warm), badDiagnostics: res.bad });
  console.log('MEASURE[typescript]', JSON.stringify(out.typescript));
});
