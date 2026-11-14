/**
 * The type-check Worker is lazy (D13, codemode.md section 5.1): the TypeScript compiler and its library files are a separate
 * static asset that only `new Worker(...)` fetches, so the editor's own bundle does not carry them. Two production builds:
 *
 *   - the editor as shipped: no compiler anywhere in its output, no worker asset;
 *   - the client imported STATICALLY (the worst case: whatever imports it eagerly): its own JavaScript stays small and the
 *     compiler is still a separate, much larger asset the page would fetch only on the first check.
 *
 * The sizes are printed; the review reads them from the log.
 */
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { build } from 'vite';
import { describe, expect, it } from 'vitest';
import { workerBuild } from '../vite.worker';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const COMPILER_MARKERS = ['createSourceFile', 'getPreEmitDiagnostics', 'lib.es5.d.ts'];

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

interface Emitted {
  name: string;
  raw: number;
  gzip: number;
  text: string;
}

function emitted(dir: string): Emitted[] {
  return walk(dir)
    // the vendored Blockbench build under public/ is copied verbatim and is not part of the editor's own bundle
    .filter((path) => path.endsWith('.js') && !path.replaceAll('\\', '/').includes('/blockbench/'))
    .map((path) => {
      const buffer = readFileSync(path);
      return { name: path.slice(dir.length + 1).replaceAll('\\', '/'), raw: buffer.length, gzip: gzipSync(buffer).length, text: buffer.toString('utf8') };
    });
}

const sum = (files: Emitted[], field: 'raw' | 'gzip'): number => files.reduce((total, file) => total + file[field], 0);

describe('the type-check Worker does not enter the editor bundle', () => {
  it('the editor as shipped holds no compiler and no worker asset', async () => {
    const out = mkdtempSync(join(tmpdir(), 'materione-spa-'));
    try {
      await build({ root: ROOT, configFile: join(ROOT, 'vite.config.ts'), logLevel: 'silent', build: { outDir: out, emptyOutDir: true, reportCompressedSize: false } });
      const files = emitted(out);
      for (const file of files) for (const marker of COMPILER_MARKERS) expect(file.text.includes(marker), `${file.name} must not contain ${marker}`).toBe(false);
      expect(files.some((file) => /worker/i.test(file.name))).toBe(false);
      console.log(`editor bundle (JS): ${files.length} files, ${sum(files, 'raw')} B raw, ${sum(files, 'gzip')} B gzip`);
    } finally {
      rmSync(out, { recursive: true, force: true });
    }
  }, 180_000);

  it('the client imported statically stays small; the compiler is its own lazily fetched asset', async () => {
    const out = mkdtempSync(join(tmpdir(), 'materione-tc-'));
    // a page that imports the client eagerly and uses it, so nothing is shaken away (under build/, which is ignored)
    mkdirSync(join(ROOT, 'build'), { recursive: true });
    const entry = mkdtempSync(join(ROOT, 'build', 'tc-entry-'));
    writeFileSync(join(entry, 'index.html'), '<!doctype html><html><body><script type="module">import { check } from "/src/lib/codemode/typecheck/index.ts"; window.__check = check;</script></body></html>');
    try {
      await build({
        root: ROOT,
        configFile: false,
        publicDir: false,
        logLevel: 'silent',
        worker: workerBuild,
        build: {
          outDir: out,
          emptyOutDir: true,
          reportCompressedSize: false,
          rollupOptions: { input: join(entry, 'index.html') },
        },
      });
      const files = emitted(out);
      const worker = files.filter((file) => /worker/i.test(file.name));
      const rest = files.filter((file) => !/worker/i.test(file.name));
      expect(worker).toHaveLength(1);
      for (const marker of COMPILER_MARKERS) expect(worker[0]!.text.includes(marker)).toBe(true);
      for (const file of rest) for (const marker of COMPILER_MARKERS) expect(file.text.includes(marker), `${file.name} must not contain ${marker}`).toBe(false);
      // the library files in the worker are the ES libraries only: no DOM
      expect(worker[0]!.text.includes('interface HTMLElement')).toBe(false);
      console.log(`type-check client (eager import): ${sum(rest, 'raw')} B raw, ${sum(rest, 'gzip')} B gzip; worker asset ${worker[0]!.name}: ${worker[0]!.raw} B raw, ${worker[0]!.gzip} B gzip`);
      expect(sum(rest, 'raw')).toBeLessThan(100_000);
      expect(worker[0]!.raw).toBeGreaterThan(1_000_000);
    } finally {
      rmSync(out, { recursive: true, force: true });
      rmSync(entry, { recursive: true, force: true });
    }
  }, 180_000);
});
