/**
 * The sandbox's build and its host-side contracts (codemode.md section 4.6): the frame document's CSP names the sha256 of its
 * own inline script (computed by the build step, checked on the PRODUCTION output), the sandbox is a static part of the SPA
 * build and nothing of it enters the editor's own bundle, the diagnostics are documented, and the op host and the message
 * validator hold their allowlist and shapes.
 */
import { createHash } from 'node:crypto';
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { build } from 'vite';
import { describe, expect, it } from 'vitest';
import { READS, WRITES } from '../src/lib/codemode/core';
import { createOpHost, READ_ALLOWLIST, RESOLVE_OPS } from '../src/lib/codemode/sandbox';
import { DEFAULT_SANDBOX_LIMITS, parseEngineMessage, SANDBOX_CODES } from '../src/lib/codemode/sandbox/protocol';
import { calleeColumn } from '../src/lib/codemode/sandbox/sourcemap';
import { sandboxCsp } from '../vite.sandbox';
import { workerBuild } from '../vite.worker';

const ROOT = fileURLToPath(new URL('..', import.meta.url));

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

describe('the production build', () => {
  it('emits the sandbox as static files, hash-CSP frame included, and leaves the editor bundle byte-identical', async () => {
    const withSandbox = mkdtempSync(join(tmpdir(), 'materione-sbx-'));
    const without = mkdtempSync(join(tmpdir(), 'materione-nosbx-'));
    try {
      await build({ root: ROOT, configFile: join(ROOT, 'vite.config.ts'), logLevel: 'silent', build: { outDir: withSandbox, emptyOutDir: true, reportCompressedSize: false } });
      // the same editor without the sandbox plugin: what the editor's own bundle was before this work
      await build({ root: ROOT, configFile: false, plugins: [svelte()], worker: workerBuild, logLevel: 'silent', build: { outDir: without, emptyOutDir: true, reportCompressedSize: false } });

      const files = walk(withSandbox).map((path) => path.slice(withSandbox.length + 1).replaceAll('\\', '/'));
      for (const name of ['sandbox/frame.html', 'sandbox/engine.js', 'sandbox/core.js', 'sandbox/quickjs.wasm']) expect(files, name).toContain(name);

      // the frame: one inline script, no external script, and a CSP that names exactly that script's sha256
      const html = readFileSync(join(withSandbox, 'sandbox/frame.html'), 'utf8');
      const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
      expect(scripts).toHaveLength(1);
      expect(html).not.toMatch(/<script[^>]*\ssrc=/);
      expect(scripts[0]![1]).not.toContain('<!--');
      const hash = createHash('sha256').update(scripts[0]![1]!).digest('base64');
      const meta = /<meta http-equiv="Content-Security-Policy" content="([^"]+)">/.exec(html);
      expect(meta?.[1]).toBe(sandboxCsp(hash));
      expect(meta?.[1]).toContain("connect-src 'none'");
      expect(meta?.[1]).toContain("worker-src blob:");
      expect(meta?.[1]).toContain("default-src 'none'");
      expect(meta?.[1]).not.toMatch(/'self'|'unsafe-eval'|'unsafe-inline'/);

      // the editor's own files: the same names (content-hashed) as without the sandbox, so not one byte more
      const own = (list: string[]) => list.filter((name) => !name.startsWith('sandbox/')).sort();
      const baseline = walk(without).map((path) => path.slice(without.length + 1).replaceAll('\\', '/'));
      expect(own(files)).toEqual(own(baseline));
      const js = own(files).filter((name) => name.endsWith('.js') && !name.startsWith('blockbench/'));
      for (const name of js) {
        const text = readFileSync(join(withSandbox, name), 'utf8');
        expect(text.includes('newQuickJSWASMModule'), name).toBe(false);
        expect(text.includes('MaterioneCore'), name).toBe(false);
      }
      const size = (dir: string, names: string[]) => names.reduce((total, name) => total + readFileSync(join(dir, name)).length, 0);
      const gz = (dir: string, names: string[]) => names.reduce((total, name) => total + gzipSync(readFileSync(join(dir, name))).length, 0);
      const sandboxFiles = files.filter((name) => name.startsWith('sandbox/'));
      console.log(`editor JS: ${size(withSandbox, js)} B raw (baseline ${size(without, js)} B); sandbox files: ${sandboxFiles.map((name) => `${name} ${statSync(join(withSandbox, name)).size} B raw / ${gz(withSandbox, [name])} B gzip`).join(', ')}`);
      expect(size(withSandbox, js)).toBe(size(without, js));
    } finally {
      rmSync(withSandbox, { recursive: true, force: true });
      rmSync(without, { recursive: true, force: true });
    }
  }, 240_000);
});

describe('the diagnostics are documented', () => {
  it('plugin.md section 5 lists every SBX code', () => {
    const doc = readFileSync(join(ROOT, '../docs/architecture/plugin.md'), 'utf8');
    const listed = [...doc.matchAll(/^\| `(SBX-\d{4})` \|/gm)].map((match) => match[1]);
    expect(listed).toEqual(Object.keys(SANDBOX_CODES));
  });
});

describe('the op host', () => {
  it('reads only the table\'s read ops the core makes itself, and no write op is reachable', () => {
    expect([...READ_ALLOWLIST].sort()).toEqual(['admin.diagnostics.recent', 'authoring.references', 'authoring.summaries', 'browse.get', 'browse.history', 'browse.packs']);
    for (const op of [...READ_ALLOWLIST, ...RESOLVE_OPS]) expect(WRITES.some((entry) => entry.op === op)).toBe(false);
    expect(READS.every((entry) => !entry.op.includes('transaction'))).toBe(true);
  });

  it('refuses an op outside the allowlist without calling the editor', async () => {
    const called: string[] = [];
    const host = createOpHost(async (op) => (called.push(op), { ok: true, data: {} }));
    expect(await host.read('authoring.transaction', {})).toMatchObject({ ok: false, error: { code: 'SBX-0010' } });
    expect(await host.read('logic.instantiate', {})).toMatchObject({ ok: false });
    expect(called).toEqual([]);
    expect(await host.read('browse.packs', {})).toEqual({ ok: true, data: {} });
  });
});

describe('messages from the frame are untrusted input', () => {
  const parse = (raw: unknown) => parseEngineMessage(raw, 1000, 5000);
  it('accepts the closed vocabulary with its exact shapes', () => {
    expect(parse({ t: 'ready', initMs: 3 })).toEqual({ t: 'ready', initMs: 3 });
    expect(parse({ t: 'call', runId: 1, rid: 2, kind: 'read', op: 'browse.packs', payload: '{}' })).toMatchObject({ t: 'call' });
    expect(parse({ t: 'result', runId: 1, resultJson: null, poisoned: false, cpuMs: 1, wallMs: 2, setupMs: 3 })).toMatchObject({ t: 'result' });
  });
  it.each([
    ['not an object', 'x'],
    ['null', null],
    ['an unknown type', { t: 'eval', code: 'x' }],
    ['a call with a non-string payload', { t: 'call', runId: 1, rid: 1, kind: 'read', op: 'a', payload: {} }],
    ['a call with a negative id', { t: 'call', runId: -1, rid: 1, kind: 'read', op: 'a', payload: '{}' }],
    ['a call of an unknown kind', { t: 'call', runId: 1, rid: 1, kind: 'write', op: 'a', payload: '{}' }],
    ['an oversize payload', { t: 'call', runId: 1, rid: 1, kind: 'read', op: 'a', payload: 'x'.repeat(1001) }],
    ['an oversize result', { t: 'result', runId: 1, resultJson: 'x'.repeat(5001), poisoned: false, cpuMs: 1, wallMs: 1, setupMs: 1 }],
    ['an unknown violation', { t: 'result', runId: 1, violation: 'boom', resultJson: null, poisoned: false, cpuMs: 1, wallMs: 1, setupMs: 1 }],
    ['a non-finite number', { t: 'ready', initMs: Number.NaN }],
  ])('refuses %s', (_name, raw) => {
    expect(parse(raw)).toBeUndefined();
  });
  it('reads only own properties: an inherited field is not a field', () => {
    const inherited = Object.create({ t: 'ready', initMs: 1 }) as unknown;
    expect(parse(inherited)).toBeUndefined();
  });
});

describe('positions', () => {
  it('names the callee, not the parenthesis', () => {
    expect(calleeColumn('const refined = p.items.create("x")', 31)).toBe(25);
    expect(calleeColumn('f  (1)', 4)).toBe(1);
    expect(calleeColumn('x = 1', 3)).toBe(3);
  });
  it('the defaults are positive and the host\'s own', () => {
    for (const value of Object.values(DEFAULT_SANDBOX_LIMITS)) expect(value).toBeGreaterThan(0);
  });
});
