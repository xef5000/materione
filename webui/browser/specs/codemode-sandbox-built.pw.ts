import { createServer, type Server } from 'node:http';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { extname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import { build as esbuild } from 'esbuild';
import { build } from 'vite';
import { frameDocument, sandboxAssets } from '../../vite.sandbox';
import { workerBuild } from '../../vite.worker';
import { checkAndRun, expectCorpusGoldens, surfaces, WORLD } from './helpers/sandbox';

// The PRODUCTION artifact of the sandbox (codemode.md section 4.6), served by a plain static host that, like both real hosts, sends a
// Content-Type and no other header (no CSP header, no CORS), with a hit log that records any request that reaches the server.
// Same corpus as the dev spec; plus the two isolation layers:
//   - the frame document the build emitted is driven for real (every program below runs in it);
//   - code that has ALREADY escaped QuickJS is probed natively in a frame and in a blob Worker under the production CSP (the
//     policy generator and the hash construction are the build's own, `frameDocument`): the probes cannot be injected into the
//     emitted frame itself, so they run in documents built the same way, and the emitted frame's own policy is asserted equal.

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const TYPES: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.wasm': 'application/wasm' };

let server: Server;
let out = '';
let entry = '';
let base = '';
let hits: string[] = [];

test.beforeAll(async () => {
  out = mkdtempSync(join(tmpdir(), 'materione-sbx-built-'));
  mkdirSync(join(ROOT, 'build'), { recursive: true });
  entry = mkdtempSync(join(ROOT, 'build', 'sbx-built-'));
  writeFileSync(join(entry, 'index.html'), '<!doctype html><html><body><script type="module" src="/browser/sandbox.ts"></script></body></html>');
  await build({ root: ROOT, configFile: false, publicDir: false, logLevel: 'silent', plugins: [sandboxAssets()], worker: workerBuild, build: { outDir: out, emptyOutDir: true, reportCompressedSize: false, rollupOptions: { input: join(entry, 'index.html') } } });
  // probe documents: the production CSP around a different inline script
  for (const [name, source] of [['probe-frame', 'browser/probes/frame-probe.ts'], ['probe-worker', 'browser/probes/worker-probe.ts']] as const) {
    const bundled = await esbuild({ entryPoints: [join(ROOT, source)], bundle: true, write: false, format: 'iife', target: 'es2022', minify: true, logLevel: 'error' });
    writeFileSync(join(out, 'sandbox', `${name}.html`), frameDocument(bundled.outputFiles[0]!.text).html);
  }
  const page = join(out, 'build', entry.slice(entry.lastIndexOf('sbx-built-')), 'index.html');
  server = createServer((request, response) => {
    const url = (request.url ?? '/').split('?')[0]!;
    if (url === '/__hits') return void response.end(JSON.stringify(hits));
    if (url === '/__reset') {
      hits = [];
      return void response.end('ok');
    }
    if (url.startsWith('/__hit/')) {
      hits.push(url);
      response.statusCode = 204;
      return void response.end();
    }
    const file = url === '/' ? page : join(out, url);
    if (!file.startsWith(out) || !existsSync(file)) {
      response.statusCode = 404;
      return void response.end();
    }
    response.setHeader('Content-Type', TYPES[extname(file)] ?? 'application/octet-stream');
    response.end(readFileSync(file));
  });
  server.on('upgrade', (request, socket) => {
    hits.push(request.url ?? '');
    socket.destroy();
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  base = `http://127.0.0.1:${typeof address === 'object' && address !== null ? address.port : 0}`;
});

test.afterAll(() => {
  server?.close();
  rmSync(out, { recursive: true, force: true });
  rmSync(entry, { recursive: true, force: true });
});

test.beforeEach(async ({ page, request }) => {
  hits = [];
  await request.get(`${base}/__reset`);
  await page.goto(`${base}/`);
  await expect(page.locator('html')).toHaveAttribute('data-sandbox-ready', 'true');
});

test('the built sandbox: lazy, the corpus byte for byte the goldens, and no request leaves it', async ({ page }) => {
  test.setTimeout(180_000);
  const requests: string[] = [];
  page.context().on('request', (request) => requests.push(request.url()));
  await page.goto(`${base}/`);
  await expect(page.locator('html')).toHaveAttribute('data-sandbox-ready', 'true');
  expect(requests.filter((url) => /\/sandbox\//.test(url)), 'lazy: no sandbox file before the first run').toEqual([]);
  expect(await expectCorpusGoldens(page)).toBeGreaterThanOrEqual(8);
  expect(requests.filter((url) => /\/sandbox\//.test(url)).map((url) => url.slice(url.lastIndexOf('/') + 1)).sort()).toEqual(['core.js', 'engine.js', 'frame.html', 'quickjs.wasm']);
  expect(hits).toEqual([]);
});

test('the emitted frame carries the hash CSP the browser enforces', async ({ page }) => {
  const html = await (await page.request.get(`${base}/sandbox/frame.html`)).text();
  expect(html).toMatch(/script-src 'sha256-[A-Za-z0-9+/=]+' 'wasm-unsafe-eval'/);
  // the frame booted and ran programs under exactly this policy (the corpus test above); a wrong hash would not have booted
  const ran = await checkAndRun(page, 'return 1 + 1;');
  expect(ran.result?.value).toBe(2);
  expect(await page.evaluate(() => document.querySelector('iframe')?.getAttribute('sandbox'))).toBe('allow-scripts');
});

test('outer layer, in a frame: code that escaped QuickJS reaches nothing and nothing reaches the server', async ({ page, request }) => {
  const result = await page.evaluate((url) => window.__sbx!.probe(url), `${base}/sandbox/probe-frame.html`);
  console.log('FRAME PROBE:', JSON.stringify(result));
  expect(result.opaque).toBe('true');
  // sendBeacon, a nested iframe and BroadcastChannel are fire-and-forget: the server's hit log and the editor's listener are their evidence
  for (const [name, value] of Object.entries(result)) if (!['origin', 'opaque', 'sendBeacon', 'nested iframe', 'broadcast'].includes(name) && !name.startsWith('BroadcastChannel')) expect(value, name).toMatch(/^blocked/);
  await page.waitForTimeout(500);
  expect(JSON.parse(await (await request.get(`${base}/__hits`)).text())).toEqual([]);
  expect(await page.evaluate(() => window.__sbx!.broadcasts)).toEqual([]);
});

test('outer layer, in the engine Worker the frame spawns: it inherits the opaque origin and the policy', async ({ page, request }) => {
  const result = await page.evaluate((url) => window.__sbx!.probe(url), `${base}/sandbox/probe-worker.html`);
  console.log('WORKER PROBE:', JSON.stringify(result));
  expect(result.origin).toBe('null');
  for (const [name, value] of Object.entries(result)) if (!['origin', 'broadcast'].includes(name)) expect(value, name).toMatch(/^blocked/);
  await page.waitForTimeout(500);
  expect(JSON.parse(await (await request.get(`${base}/__hits`)).text())).toEqual([]);
  expect(await page.evaluate(() => window.__sbx!.broadcasts)).toEqual([]);
});

test('inner layer, in the real sandbox: the guest sees intrinsics and `m` only, and its constructor walks reach nothing', async ({ page }) => {
  const ran = await checkAndRun(
    page,
    `return {
      names: Object.getOwnPropertyNames(globalThis).sort(),
      walks: [Function('return this')() === globalThis, (() => {}).constructor('return this')() === globalThis],
      absent: ['fetch', 'XMLHttpRequest', 'WebSocket', 'window', 'document', 'parent', 'top', 'localStorage', 'indexedDB', 'setTimeout', 'importScripts', 'BroadcastChannel', 'postMessage', 'WebAssembly', 'navigator', 'location'].filter((name) => typeof (globalThis as any)[name] !== 'undefined'),
    };`,
  );
  const seen = ran.result!.value as { names: string[]; walks: boolean[]; absent: string[] };
  expect(seen.absent).toEqual([]);
  expect(seen.walks).toEqual([true, true]);
  expect(seen.names).toContain('m');
  expect(seen.names).not.toContain('fetch');
  expect(seen.names.length).toBeLessThan(80);
  // a program that tries every door finds them shut
  const doors = await checkAndRun(page, `let seen: string[] = []; for (const probe of ['fetch', 'import']) { try { if (probe === 'import') await Function("return import('data:text/javascript,export default 1')")(); else await (globalThis as any).fetch('/__hit/guest'); seen.push(probe + ' ALLOWED'); } catch { seen.push(probe + ' blocked'); } } return seen;`);
  expect(doors.result?.value).toEqual(['fetch blocked', 'import blocked']);
  expect(hits).toEqual([]);
});

test('prototype pollution inside the guest cannot touch the editor\'s realm or its handling of messages', async ({ page }) => {
  const ran = await checkAndRun(page, `(Object.prototype as any).polluted = 'yes'; JSON.parse('{"__proto__": {"hostPolluted": true}}'); m.pack("bench").items.create("a1", { material: "STONE" }); return 1;`);
  expect(ran.result?.ok).toBe(true);
  expect(ran.result?.operations).toHaveLength(1);
  expect(await page.evaluate(() => ({} as Record<string, unknown>).polluted === undefined && ({} as Record<string, unknown>).hostPolluted === undefined)).toBe(true);
});

test('wall-clock backstop, a runaway and a discarded frame on the built artifact; the editor survives', async ({ page }) => {
  const handle = await page.evaluate(() => window.__sbx!.fresh());
  const stalled = await page.evaluate(([h, s, w]) => window.__sbx!.runWithLag(h as number, 'while (true) {}', s as never, w as never, { cpuMs: 300 }), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(stalled.result.diagnostics.map((d) => d.code)).toEqual(['SBX-0001']);
  console.log(`built artifact: CPU deadline 300 ms, editor worst gap ${stalled.maxGapMs.toFixed(0)} ms`);
  expect(stalled.maxGapMs).toBeLessThan(150);
  const killed = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'while (true) {}', s as never, w as never, { cpuMs: 600_000, wallMs: 400 }), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(killed.diagnostics.map((d) => d.code)).toEqual(['SBX-0006']);
  expect(await page.evaluate(() => window.__sbx!.frames())).toBe(0);
  const next = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'return 7;', s as never, w as never), [handle, surfaces['plugin-current'], WORLD] as const);
  expect(next.value).toBe(7);
});

test('timing: first run (boot included) and a warm run', async ({ page }) => {
  const handle = await page.evaluate(() => window.__sbx!.fresh());
  const first = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'return 1;', s as never, w as never), [handle, surfaces['plugin-current'], WORLD] as const);
  const warm = await page.evaluate(([h, s, w]) => window.__sbx!.runWith(h as number, 'return 1;', s as never, w as never), [handle, surfaces['plugin-current'], WORLD] as const);
  console.log(`TIMING first run: boot ${first.metrics.bootMs.toFixed(0)} ms, run ${first.metrics.totalMs.toFixed(0)} ms (cpu ${first.metrics.cpuMs.toFixed(0)}, setup ${first.metrics.setupMs.toFixed(0)}); warm run ${warm.metrics.totalMs.toFixed(0)} ms (cpu ${warm.metrics.cpuMs.toFixed(0)}, setup ${warm.metrics.setupMs.toFixed(0)})`);
  expect(first.metrics.booted).toBe(true);
  expect(warm.metrics.booted).toBe(false);
});
