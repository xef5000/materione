import { createServer, type Server } from 'node:http';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import { build } from 'vite';
import { workerBuild } from '../../vite.worker';
import { check, corpus } from './helpers/typecheck';

// The PRODUCTION artifact of the type-check Worker (codemode.md section 5.1): the minified module Worker Vite emits, served
// by a plain static host that, like both real hosts (EditorListener.serveStatic, Workers static assets), sends a Content-Type
// and no other header. Same corpus, same expectations as the dev-server spec.

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const TYPES: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8' };

let server: Server;
let out = '';
let entry = '';
let base = '';

test.beforeAll(async () => {
  out = mkdtempSync(join(tmpdir(), 'materione-tc-built-'));
  mkdirSync(join(ROOT, 'build'), { recursive: true });
  entry = mkdtempSync(join(ROOT, 'build', 'tc-built-'));
  // the same entry the dev spec uses (browser/typecheck.ts), as a page of its own
  writeFileSync(join(entry, 'index.html'), '<!doctype html><html><body><script type="module" src="/browser/typecheck.ts"></script></body></html>');
  await build({ root: ROOT, configFile: false, publicDir: false, logLevel: 'silent', worker: workerBuild, build: { outDir: out, emptyOutDir: true, reportCompressedSize: false, rollupOptions: { input: join(entry, 'index.html') } } });
  const page = join(out, 'build', entry.slice(entry.lastIndexOf('tc-built-')), 'index.html');
  server = createServer((request, response) => {
    const url = (request.url ?? '/').split('?')[0]!;
    const file = url === '/' ? page : join(out, url);
    if (!file.startsWith(out) || !existsSync(file)) {
      response.statusCode = 404;
      response.end();
      return;
    }
    response.setHeader('Content-Type', TYPES[extname(file)] ?? 'application/octet-stream');
    response.end(readFileSync(file));
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

test('the built Worker: corpus, a gap, an ambient name, and a deadline', async ({ page }) => {
  const requests: string[] = [];
  page.context().on('request', (request) => requests.push(request.url()));
  await page.goto(`${base}/`);
  await expect(page.locator('html')).toHaveAttribute('data-typecheck-ready', 'true');
  expect(requests.filter((url) => /worker/.test(url)), 'lazy: no Worker file before the first check').toEqual([]);

  for (const item of corpus) {
    const result = await check(page, item.program, item.expect.registry);
    expect(result.ok, `${item.name} ${JSON.stringify(result.diagnostics)}`).toBe(item.expect.result === 'pass');
    for (const wanted of item.expect.diagnostics ?? []) {
      expect(result.diagnostics.some((d) => d.code === `TS${wanted.code}`), `${item.name} TS${wanted.code}`).toBe(true);
    }
  }
  const worker = requests.find((url) => /assets\/worker-.*\.js$/.test(url));
  expect(worker, 'the Worker is a static file of the build').toBeDefined();

  const gap = await check(page, 'const p = m.pack("t"); p.commands.create("c", {});');
  expect(gap.diagnostics[0]!.missingPrimitive).toMatchObject({ member: 'commands', catalog: 'PackWriter' });
  const ambient = await check(page, 'fetch("https://example.com"); window.x;');
  expect(ambient.ok).toBe(false);
  expect(ambient.diagnostics.map((d) => d.code)).toEqual(['TS2304', 'TS2304']);

  const handle = await page.evaluate(() => window.__tc!.fresh({ checkMs: 1 }));
  const cut = await page.evaluate(([h, sdk]) => window.__tc!.checkWith(h!, 'm;', sdk!), [handle, (await import('./helpers/typecheck')).surfaces['plugin-current']] as const);
  expect(cut.diagnostics[0]!.code === 'CHK-0003' || cut.diagnostics[0]!.code === 'CHK-0004').toBe(true);
});
