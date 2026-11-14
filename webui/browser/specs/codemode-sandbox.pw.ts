import { expect, test } from '@playwright/test';
import { checkAndRun, expectCorpusGoldens, surfaces, WORLD } from './helpers/sandbox';

// The sandbox in a real browser, against the dev server (codemode.md section 4.6): the whole corpus through the type-check Worker
// and then the real sandboxed frame, byte for byte the goldens; the limits that need a real thread (a wall-clock backstop that
// destroys the frame, an editor that stays responsive under a CPU-bound guest); laziness. The production artifact has its own
// spec (`codemode-sandbox-built.pw.ts`) for isolation.

test.beforeEach(async ({ page }) => {
  await page.goto('/browser/sandbox.html');
  await expect(page.locator('html')).toHaveAttribute('data-sandbox-ready', 'true');
});

test('nothing of the sandbox loads before the first run, and the frame is opaque-origin', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => requests.push(request.url()));
  await page.goto('/browser/sandbox.html');
  await expect(page.locator('html')).toHaveAttribute('data-sandbox-ready', 'true');
  const assets = (list: string[]) => list.filter((url) => /\/sandbox\/(frame|engine|core|quickjs)/.test(url));
  expect(assets(requests)).toEqual([]);
  expect(await page.evaluate(() => window.__sbx!.frames())).toBe(0);
  const ran = await checkAndRun(page, 'return 1;');
  expect(ran.result?.value).toBe(1);
  expect(assets(requests).map((url) => url.slice(url.lastIndexOf('/') + 1)).sort()).toEqual(['core.js', 'engine.js', 'frame.html', 'quickjs.wasm']);
  expect(await page.evaluate(() => document.querySelector('iframe')?.getAttribute('sandbox'))).toBe('allow-scripts');
});

test('the corpus, in the real sandbox, is byte for byte the goldens', async ({ page }) => {
  test.setTimeout(180_000);
  expect(await expectCorpusGoldens(page)).toBeGreaterThanOrEqual(8);
});

test('a CPU-bound guest ends at its deadline, the editor barely stalls, and a fresh sandbox boots after', async ({ page }) => {
  const handle = await page.evaluate(() => window.__sbx!.fresh());
  const loop = await page.evaluate(([h, s, w]) => window.__sbx!.runWithLag(h as number, 'while (true) {}', s as never, w as never, { cpuMs: 250 }), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(loop.result.diagnostics.map((d) => d.code)).toEqual(['SBX-0001']);
  expect(loop.result.operations).toEqual([]);
  console.log(`CPU deadline 250 ms: editor main thread worst gap ${loop.maxGapMs.toFixed(0)} ms`);
  expect(loop.maxGapMs).toBeLessThan(150);
  const next = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'm.pack("bench").items.create("a1", { material: "STONE" }); return 1;', s as never, w as never), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(next.ok).toBe(true);
  expect(await page.evaluate((h) => window.__sbx!.stats(h), handle)).toEqual({ booted: 2, discarded: 1 });
});

test('the wall-clock backstop destroys a frame the CPU deadline did not stop', async ({ page }) => {
  const handle = await page.evaluate(() => window.__sbx!.fresh());
  const started = Date.now();
  const result = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'while (true) {}', s as never, w as never, { cpuMs: 600_000, wallMs: 400 }), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(result.diagnostics.map((d) => d.code)).toEqual(['SBX-0006']);
  expect(Date.now() - started).toBeLessThan(5000);
  expect(await page.evaluate(() => window.__sbx!.frames())).toBe(0);
  const next = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'return 2;', s as never, w as never), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(next.value).toBe(2);
});

test('unbounded allocation hits the memory cap in the browser and the editor lives', async ({ page }) => {
  const result = await checkAndRun(page, 'const keep: number[][] = []; while (true) keep.push(new Array(100000).fill(1));', WORLD, { memoryMb: 32 });
  expect(result.result?.diagnostics.map((d) => d.code)).toEqual(['SBX-0002']);
  expect(result.result?.operations).toEqual([]);
});

test('a read the editor never answers ends at the per-read deadline', async ({ page }) => {
  const result = await checkAndRun(page, 'return await m.read.packs();', WORLD, { readMs: 300 }, 'silent');
  expect(result.result?.diagnostics.map((d) => d.code)).toEqual(['SBX-0005']);
  expect(result.reads).toEqual(['browse.packs']);
});
