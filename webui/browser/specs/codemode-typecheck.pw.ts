import { expect, test } from '@playwright/test';
import type { Page, Request } from '@playwright/test';
import { check, corpus, surfaces, type Probe } from './helpers/typecheck';

// The type-check Worker in a real browser (codemode.md section 5.1, issue 342). The page is a bare fixture that exposes the
// client; the Worker is the real module Worker with the real compiler. Every spec here is the Worker agreeing with what
// the Node suite (test/codemode-typecheck.spec.ts) pins, plus what only a browser can show: laziness, no network, a main
// thread that stays responsive, and a Worker that is killed at its deadline.

declare const window: Window & { __tc?: Probe };

async function open(page: Page): Promise<Request[]> {
  const requests: Request[] = [];
  page.on('request', (request) => requests.push(request));
  page.context().on('request', (request) => requests.push(request));
  await page.goto('/browser/typecheck.html');
  await expect(page.locator('html')).toHaveAttribute('data-typecheck-ready', 'true');
  return requests;
}

const isCompiler = (request: Request): boolean => /typecheck\/(worker|libs)|typescript/.test(request.url());

test('the compiler is not fetched until the first check, and nothing is fetched while checking', async ({ page }) => {
  const requests = await open(page);
  await page.waitForTimeout(300);
  expect(requests.filter(isCompiler), 'nothing of the compiler before the first agent use').toEqual([]);

  const first = await check(page, 'const p = m.pack("t");');
  expect(first.ok).toBe(true);
  expect(requests.filter(isCompiler).length, 'the Worker loads on the first check').toBeGreaterThan(0);

  // From here every check runs with the network closed: it fetches nothing at check time.
  await page.context().setOffline(true);
  const before = requests.length;
  for (const entry of corpus) {
    const result = await check(page, entry.program, entry.expect.registry);
    expect(result.ok, entry.name).toBe(entry.expect.result === 'pass');
  }
  await page.context().setOffline(false);
  expect(requests.slice(before).map((request) => request.url()), 'no request at check time').toEqual([]);
});

test('the corpus: the Worker agrees with the recorded expectations (pass or fail, the TS codes, the gap)', async ({ page }) => {
  await open(page);
  for (const entry of corpus) {
    const result = await check(page, entry.program, entry.expect.registry);
    if (entry.expect.result === 'pass') {
      expect(result.diagnostics, entry.name).toEqual([]);
      expect(result.ok, entry.name).toBe(true);
      expect(result.js, entry.name).toContain('m.');
      expect(result.map?.mappings, entry.name).toBeTruthy();
      continue;
    }
    expect(result.ok, entry.name).toBe(false);
    expect(result.js, `${entry.name}: a failing program is not handed back as runnable text`).toBeUndefined();
    for (const wanted of entry.expect.diagnostics ?? []) {
      const hit = result.diagnostics.find((d) => d.code === `TS${wanted.code}` && (wanted.messageContains ?? []).every((part) => d.message.includes(part)));
      expect(hit, `${entry.name}: expected TS${wanted.code}, got ${JSON.stringify(result.diagnostics)}`).toBeDefined();
      if (wanted.missingPrimitive !== undefined) expect(hit!.missingPrimitive, entry.name).toMatchObject(wanted.missingPrimitive);
    }
  }
});

test('the Worker and the Node checker give the same diagnostics for the same text', async ({ page }) => {
  await open(page);
  const program = 'const p = m.pack("t");\np.commands.create("c", {});\nwindow.fetch("x");\nconst n: string = 3;';
  const result = await check(page, program);
  expect(result.diagnostics.map((d) => `${d.code}@${d.line}:${d.column}`)).toEqual(['TS2339@2:3', 'TS2304@3:1', 'TS2322@4:7']);
  expect(result.diagnostics[0]!.missingPrimitive).toEqual({ member: 'commands', catalog: 'PackWriter', nearest: [] });
  expect(await check(page, program)).toEqual(result);
});

test('a program that touches the ambient world fails the type check', async ({ page }) => {
  await open(page);
  for (const [name, program] of [
    ['window', 'window.location.href;'],
    ['fetch', 'fetch("https://example.com");'],
    ['document', 'document.body;'],
    ['process', 'process.env.HOME;'],
    ['require', 'require("fs");'],
    ['setTimeout', 'setTimeout(() => {}, 1);'],
  ] as const) {
    const result = await check(page, program);
    expect(result.ok, name).toBe(false);
    expect(result.diagnostics[0]!.line, name).toBe(1);
    expect(result.diagnostics[0]!.hint, name).toContain('no ambient authority');
  }
  expect((await check(page, '// @ts-nocheck\nwindow.fetch("x");')).diagnostics[0]!.code).toBe('CHK-0001');
  expect((await check(page, 'declare const fetch: any;\nfetch("x");')).diagnostics[0]!.code).toBe('CHK-0001');
});

test('a same-origin module Worker runs under a strict CSP (what a host that adds one needs: worker-src self)', async ({ page }) => {
  await page.route('**/browser/typecheck.html', async (route) => {
    const response = await route.fetch();
    await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': "default-src 'self'; connect-src 'self' ws://127.0.0.1:4173; worker-src 'self'; script-src 'self'; img-src 'none'" } });
  });
  const violations: string[] = [];
  page.on('console', (message) => { if (/Content Security Policy/i.test(message.text())) violations.push(message.text()); });
  await open(page);
  const result = await check(page, 'const p = m.pack("t");');
  expect(violations).toEqual([]);
  expect(result.ok).toBe(true);
});

test('a read without its await is a plain TS2339 through the Worker; the awaited read checks', async ({ page }) => {
  await open(page);
  const missing = await check(page, 'const view = m.read.get(m.ref.item("rpg:sword"));\nview.fields;');
  expect(missing.ok).toBe(false);
  const found = missing.diagnostics.find((d) => d.code === 'TS2339');
  expect(found).toMatchObject({ line: 2, column: 6 });
  expect(found!.missingPrimitive).toBeUndefined();
  const awaited = await check(page, 'const view = await m.read.get(m.ref.item("rpg:sword"));\nview.fields;');
  expect(awaited.ok).toBe(true);
  expect(awaited.js).toContain('await m.read.get');
});

test('a registry change is a new surface: the same program checks differently against it', async ({ page }) => {
  await open(page);
  const program = 'const p = m.pack("a"); p.quests.create("q", { title: "t" });';
  expect((await check(page, program, 'plugin-current')).diagnostics[0]!.code).toBe('TS2339');
  expect((await check(page, program, 'with-addon')).ok).toBe(true);
  expect((await check(page, program, 'plugin-current')).ok).toBe(false);
});

test('a runaway check is cut off at its deadline, the page stays responsive, a fresh Worker answers next', async ({ page }) => {
  await open(page);
  // quadratic in the key count: about 0.9 s at 500 keys on a warm checker, many seconds at 1500
  const keys = Array.from({ length: 1500 }, (_, i) => `'k${i}'`).join('|');
  const bomb = `type U = ${keys}; type M = { [A in U]: { [B in U]: [A, B] } }; type V = M[U][U]; m;`;
  const handle = await page.evaluate(() => window.__tc!.fresh({ checkMs: 1500 }));
  // warm the Worker so the deadline is measured on the check, not on booting the compiler
  expect((await page.evaluate(([h, sdk]) => window.__tc!.checkWith(h!, 'm;', sdk!), [handle, surfaces['plugin-current']] as const)).ok).toBe(true);

  const stuck = await page.evaluate(([h, program, sdk]) => window.__tc!.checkWithLag(h!, program!, sdk!), [handle, bomb, surfaces['plugin-current']] as const);
  expect(stuck.result.ok).toBe(false);
  expect(stuck.result.diagnostics[0]!.code).toBe('CHK-0003');
  expect(stuck.ms, 'cut off near the deadline, not after the work').toBeLessThan(3500);
  expect(stuck.maxGapMs, 'the main thread never waited on the check').toBeLessThan(250);

  const next = await page.evaluate(([h, sdk]) => window.__tc!.checkWith(h!, 'const p = m.pack("t");', sdk!), [handle, surfaces['plugin-current']] as const);
  expect(next.ok).toBe(true);
  expect(await page.evaluate((h) => window.__tc!.stats(h!).spawned, handle)).toBe(2);
});

test('a pathological program neither hangs nor kills the checker', async ({ page }) => {
  await open(page);
  const handle = await page.evaluate(() => window.__tc!.fresh({ checkMs: 4000 }));
  const nested = '['.repeat(30_000) + ']'.repeat(30_000);
  const outcome = await page.evaluate(([h, program, sdk]) => window.__tc!.checkWithLag(h!, program!, sdk!), [handle, nested, surfaces['plugin-current']] as const);
  expect(outcome.result.ok).toBe(false);
  expect(['CHK-0004', 'CHK-0003', 'TS1005']).toContain(outcome.result.diagnostics[0]!.code);
  expect(outcome.maxGapMs).toBeLessThan(250);
  const huge = await page.evaluate(([h, sdk]) => window.__tc!.checkWith(h!, '//' + 'x'.repeat(70_000), sdk!), [handle, surfaces['plugin-current']] as const);
  expect(huge.diagnostics[0]!.code).toBe('CHK-0002');
  const after = await page.evaluate(([h, sdk]) => window.__tc!.checkWith(h!, 'const p = m.pack("t");', sdk!), [handle, surfaces['plugin-current']] as const);
  expect(after.ok).toBe(true);
});

test('cold start and per-check time, recorded (spike: 170 ms boot, 83 ms first check, 4.5 ms warm)', async ({ page }) => {
  await open(page);
  const handle = await page.evaluate(() => window.__tc!.fresh({}));
  const program = corpus.find((entry) => entry.name.startsWith('t08'))!.program;
  const run = (): Promise<{ ms: number; checkMs: number | undefined }> =>
    page.evaluate(async ([h, text, sdk]) => {
      const started = performance.now();
      await window.__tc!.checkWith(h!, text!, sdk!);
      return { ms: performance.now() - started, checkMs: window.__tc!.stats(h!).lastCheckMs };
    }, [handle, program, surfaces['plugin-current']] as const);
  const first = await run();
  const boot = await page.evaluate((h) => window.__tc!.stats(h!).lastBootMs, handle);
  const warm: number[] = [];
  for (let i = 0; i < 7; i++) warm.push((await run()).ms);
  warm.sort((a, b) => a - b);
  console.log(`typecheck browser (dev server, 195 KB surface): worker boot ${boot?.toFixed(0)} ms, first check ${first.ms.toFixed(0)} ms (compiler ${first.checkMs?.toFixed(0)} ms), warm median ${warm[3]!.toFixed(1)} ms round trip`);
  expect(first.ms).toBeLessThan(10_000);
  expect(warm[3]!).toBeLessThan(1000);
});
