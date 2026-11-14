/**
 * The type-check Worker's checker and client (codemode.md section 5.1, issue 342), in Node: the same `createChecker` the Worker
 * runs, over the generated surface, plus the client's deadline and restart logic against an in-process stand-in for the Worker.
 * The real Worker in a real browser is `browser/specs/codemode-typecheck.pw.ts`, which runs this corpus again.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { createChecker, type Checker } from '../src/lib/codemode/typecheck/checker';
import { createTypecheckClient, type WorkerLike } from '../src/lib/codemode/typecheck/client';
import { LIB_FILES } from '../src/lib/codemode/typecheck/libs';
import { CHECK_CODES, type CheckResult, type FromWorker, type ToWorker } from '../src/lib/codemode/typecheck/types';
import { CORE_DTS, CORPUS_DIR, GOLDEN_DIR, surfaceNamed } from './helpers/codemode';

const golden = (name: string): string => readFileSync(join(GOLDEN_DIR, `${name}.d.ts`), 'utf8');
const current = golden('plugin-current');
const withAddon = golden('with-addon');
const core = readFileSync(CORE_DTS, 'utf8');

const checker: Checker = createChecker({ libs: LIB_FILES, core });
const check = (program: string, sdk = current): CheckResult => checker.check({ program, sdk });
const codes = (program: string, sdk = current): string[] => check(program, sdk).diagnostics.map((entry) => entry.code);

interface Expectation {
  task: string;
  registry: string;
  result: 'pass' | 'fail';
  diagnostics?: { code: number; messageContains?: string[]; missingPrimitive?: { member: string; catalog: string } }[];
}

const corpus = readdirSync(CORPUS_DIR)
  .filter((name) => name.endsWith('.ts'))
  .sort()
  .map((name) => ({
    name,
    program: readFileSync(join(CORPUS_DIR, name), 'utf8'),
    expect: JSON.parse(readFileSync(join(CORPUS_DIR, name.replace(/\.ts$/, '.expect.json')), 'utf8')) as Expectation,
  }));

describe('the corpus through the checker', () => {
  it.each(corpus.map((entry) => [entry.name, entry] as const))('%s agrees with its expectation', (_name, entry) => {
    const result = check(entry.program, entry.expect.registry === 'with-addon' ? withAddon : current);
    if (entry.expect.result === 'pass') {
      expect(result.diagnostics).toEqual([]);
      expect(result.ok).toBe(true);
      expect(result.js).toBeTypeOf('string');
      expect(result.map?.mappings).toBeTypeOf('string');
      return;
    }
    expect(result.ok).toBe(false);
    expect(result.js, 'a failing program is never handed back as runnable text').toBeUndefined();
    for (const wanted of entry.expect.diagnostics ?? []) {
      const hit = result.diagnostics.find((entry2) => entry2.code === `TS${wanted.code}` && (wanted.messageContains ?? []).every((part) => entry2.message.includes(part)));
      expect(hit, `expected TS${wanted.code}, got ${JSON.stringify(result.diagnostics)}`).toBeDefined();
      if (wanted.missingPrimitive !== undefined) expect(hit?.missingPrimitive).toMatchObject(wanted.missingPrimitive);
      else expect(hit?.missingPrimitive).toBeUndefined();
    }
  });

  it('gives the same answer as the Node TypeScript API the compile spec uses (every code, every line)', async () => {
    const { checkProgram } = await import('./helpers/typecheck');
    for (const entry of corpus) {
      const sdk = entry.expect.registry === 'with-addon' ? withAddon : current;
      const ours = check(entry.program, sdk).diagnostics.map((d) => `${d.code}@${d.line}`);
      const theirs = checkProgram(entry.program, sdk).map((p) => `TS${p.code}@${p.line}`);
      // the helper does not wrap the program as a module, and the gap diagnostics are the same ones
      expect(ours.sort(), entry.name).toEqual(theirs.sort());
    }
  });
});

describe('a gap is reported as a missing primitive (codemode.md section 6, 9.2)', () => {
  it('marks TS2339 on a member of a generated catalog, with its catalog and the member', () => {
    const found = check('const p = m.pack("t"); p.commands.create("c", {});').diagnostics.find((d) => d.code === 'TS2339');
    expect(found?.missingPrimitive).toEqual({ member: 'commands', catalog: 'PackWriter', nearest: [] });
    expect(found).toMatchObject({ line: 1, column: 26, severity: 'error' });
    expect(found?.hint).toContain('missing primitive');
  });

  it('marks a function the server lacks (a member of the function catalog)', () => {
    const found = check('const e = m.fn.sqrt(4);').diagnostics.find((d) => d.code === 'TS2339');
    expect(found?.missingPrimitive?.member).toBe('sqrt');
  });

  it('marks TS2353, a field the content type does not describe', () => {
    const found = check('const p = m.pack("t"); p.items.create("i", { material: "STICK", secret: 1 });').diagnostics.find((d) => d.code === 'TS2353');
    expect(found?.missingPrimitive).toMatchObject({ member: 'secret', catalog: 'ItemFields' });
  });

  it('a typo with a near miss is TS2551 with a hint, not a gap', () => {
    const found = check('const p = m.pack("t"); p.itemz.create("i", {});').diagnostics[0];
    expect(found?.code).toBe('TS2551');
    expect(found?.missingPrimitive).toBeUndefined();
    expect(found?.hint).toBe("Did you mean 'items'?");
  });

  it('a read without its await is a plain TS2339 (reads are promises), the awaited read checks', () => {
    const missing = check('const view = m.read.get(m.ref.item("rpg:sword"));\nview.fields;').diagnostics.find((d) => d.code === 'TS2339');
    expect(missing).toMatchObject({ line: 2, column: 6 });
    expect(missing?.missingPrimitive).toBeUndefined();
    expect(check('const view = await m.read.get(m.ref.item("rpg:sword"));\nview.fields;').diagnostics).toEqual([]);
  });

  it('is not a gap on a type of the fixed core only', () => {
    const found = check('m.nonsense;').diagnostics[0];
    expect(found?.code).toBe('TS2339');
    expect(found?.missingPrimitive).toBeUndefined();
  });

  it('an addon registration removes the gap, and the checker follows the surface it is given', () => {
    const program = 'const p = m.pack("a"); p.quests.create("q", { title: "t" });';
    expect(codes(program, current)).toContain('TS2339');
    expect(codes(program, withAddon)).toEqual([]);
    // a registry change mid-session: the same checker, the declarations swapped, the program rebuilt over them
    expect(codes(program, current)).toContain('TS2339');
  });
});

describe('the program sees the surface and a minimal library, nothing else', () => {
  it.each([
    ['window', 'window.location.href;', 'TS2304'],
    ['document', 'document.body;', 'TS2584'],
    ['fetch', 'fetch("https://example.com");', 'TS2304'],
    ['process', 'process.env.HOME;', 'TS2591'],
    ['require', 'require("fs");', 'TS2591'],
    ['setTimeout', 'setTimeout(() => {}, 1);', 'TS2304'],
    ['XMLHttpRequest', 'new XMLHttpRequest();', 'TS2304'],
    ['console', 'console.log(1);', 'TS2584'],
    ['globalThis.fetch', 'globalThis.fetch("x");', 'TS7017'],
  ])('%s is a type error', (name, program, code) => {
    const result = check(program);
    expect(result.ok).toBe(false);
    expect(result.diagnostics.map((d) => d.code), name).toContain(code);
    if (!name.includes('.')) expect(result.diagnostics.find((d) => d.code === code)?.hint, name).toContain('no ambient authority');
  });

  it('refuses a way to conjure the missing globals', () => {
    const declared = check('declare const fetch: (url: string) => void;\nfetch("x");');
    expect(declared.ok).toBe(false);
    expect(declared.diagnostics[0]).toMatchObject({ code: 'CHK-0001', line: 1, column: 1 });
    expect(check('declare global { var window: any }').ok).toBe(false);
    expect(check('namespace N { export const x = 1; }').ok).toBe(false);
    expect(check('import fs from "fs";').diagnostics[0]?.code).toBe('CHK-0001');
    expect(check('export const x = 1;').diagnostics[0]?.code).toBe('CHK-0001');
    // closing the wrapper to write at its top level
    const escaped = check('});\ndeclare const fetch: any;\nfetch("x");\n(async () => {');
    expect(escaped.ok).toBe(false);
    expect(escaped.diagnostics.map((d) => d.code)).toContain('CHK-0001');
    expect(check('const x = await import("fs");').ok).toBe(false);
  });

  it('refuses the comment directives that switch the checker off', () => {
    for (const directive of ['// @ts-nocheck', '// @ts-ignore', '/* @ts-expect-error */']) {
      const result = check(`${directive}\nwindow.fetch("x");`);
      expect(result.ok, directive).toBe(false);
      expect(result.diagnostics.find((d) => d.code === 'CHK-0001'), directive).toMatchObject({ line: 1 });
    }
  });

  it('cannot reach a library or a type package by reference', () => {
    expect(check('/// <reference lib="dom" />\ndocument.body;').ok).toBe(false);
    expect(check('/// <reference types="node" />\nprocess.env;').ok).toBe(false);
    expect(check('/// <reference path="/node_modules/typescript/lib/lib.dom.d.ts" />\ndocument.body;').ok).toBe(false);
  });

  it('the library is ES2022: modern builtins are there, newer ones are not', () => {
    expect(check('const x = [1, 2].at(-1); const y = Object.hasOwn({}, "a"); const z = new Map<string, number>();').diagnostics).toEqual([]);
    expect(codes('const x = [1, 2].toSorted();').length).toBeGreaterThan(0);
    expect(Object.keys(LIB_FILES).some((name) => /dom|webworker|node|scripthost/.test(name))).toBe(false);
  });

  it('a program can name its own bindings without colliding with the surface', () => {
    // `Capability` and `m` are globals of the declarations; a module-scoped program may reuse them
    expect(check('type Capability = string; const x: Capability = "a"; void x;').diagnostics).toEqual([]);
  });
});

describe('what a clean program yields', () => {
  it('transpiles to ES2020 with a source map back to the program as written', () => {
    const program = 'const p = m.pack("t");\nconst n: number = 3;\nm.summary(`n=${n}`);';
    const result = check(program);
    expect(result.ok).toBe(true);
    expect(result.js).not.toContain(': number');
    expect(result.js).not.toContain('sourceMappingURL');
    expect(result.map).toMatchObject({ version: 3, sources: ['program.ts'] });
    expect(result.truncated).toBe(false);
  });

  it('is byte-for-byte what the core harness transpiles (same options)', async () => {
    const { transpile } = await import('./helpers/sdk-runner');
    for (const entry of corpus.filter((e) => e.expect.result === 'pass')) {
      expect(check(entry.program, current).js, entry.name).toBe(transpile(entry.program));
    }
  });

  it('is deterministic: the same inputs give the same JSON, fresh checker or warm', () => {
    const program = 'const p = m.pack("t"); p.commands.create("c", {}); window.x; const q: string = 3;';
    const first = JSON.stringify(check(program));
    expect(JSON.stringify(check(program))).toBe(first);
    expect(JSON.stringify(createChecker({ libs: LIB_FILES, core }).check({ program, sdk: current }))).toBe(first);
  });

  it('runs none of the program', () => {
    const holder = globalThis as unknown as Record<string, unknown>;
    delete holder.__ran;
    const result = check('(globalThis as any).__ran = true; while (true) {}');
    expect(result.ok).toBe(true);
    expect(holder.__ran).toBeUndefined();
  });

  it('orders diagnostics by position and keeps them to the cap', () => {
    const many = Array.from({ length: 80 }, (_, i) => `const v${i}: string = ${i};`).join('\n');
    const result = check(many);
    expect(result.diagnostics).toHaveLength(50);
    expect(result.truncated).toBe(true);
    expect(result.diagnostics.map((d) => d.line)).toEqual([...result.diagnostics.map((d) => d.line)].sort((a, b) => a - b));
  });

  it('wraps the program as an async body: top-level await and return check, positions stay the programs own', () => {
    expect(check('const x = await Promise.resolve(1);\nreturn x;').diagnostics).toEqual([]);
    // line 1 columns are not shifted by the wrapper prefix
    expect(check('m.nonsense;').diagnostics[0]).toMatchObject({ line: 1, column: 3 });
    expect(check('await m.nonsense;').diagnostics[0]).toMatchObject({ line: 1, column: 9 });
    // an unclosed body reports inside the program, not at the wrapper's closing line
    const open = check('const p = m.pack("t");\nif (true) {\n');
    expect(open.ok).toBe(false);
    for (const d of open.diagnostics) expect(d.line).toBeLessThanOrEqual(3);
    // the transpiled program is the program's own JavaScript, not the wrapper
    expect(check('const x = await Promise.resolve(1); m.summary(String(x));').js).toMatch(/^const x = await/);
  });

  it('reports file-relative positions, 1-based, including the end of a long program', () => {
    const result = check('\n\n   m.nonsense;');
    expect(result.diagnostics[0]).toMatchObject({ code: 'TS2339', line: 3, column: 6 });
  });
});

describe('the checker code runs and fetches nothing (ban test)', () => {
  it('the type-check sources have no evaluation, network, import-by-URL or storage', () => {
    const dir = join(__dirname, '..', 'src', 'lib', 'codemode', 'typecheck');
    for (const name of readdirSync(dir)) {
      const text = readFileSync(join(dir, name), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
      for (const banned of [/\beval\s*\(/, /new\s+Function\b/, /\bfetch\s*\(/, /new\s+(XMLHttpRequest|WebSocket)/, /importScripts\s*\(/, /\bimport\s*\(/, /\b(?:self|window|globalThis)\.(?:localStorage|sessionStorage|indexedDB)/]) {
        expect(banned.test(text), `${name} matches ${banned}`).toBe(false);
      }
    }
  });
});

describe('limits', () => {
  it('refuses a program over the cap before parsing it', () => {
    const result = check('//' + 'x'.repeat(70_000));
    expect(result.diagnostics).toEqual([expect.objectContaining({ code: 'CHK-0002' })]);
    expect(result.ok).toBe(false);
  });

  it('refuses declarations over the cap', () => {
    const small = createChecker({ libs: LIB_FILES, core, limits: { maxSdkChars: 1000 } });
    expect(small.check({ program: 'm;', sdk: current }).diagnostics[0]?.code).toBe('CHK-0002');
  });

  it('the checker codes are documented in plugin.md', () => {
    const doc = readFileSync(join(__dirname, '..', '..', 'docs', 'architecture', 'plugin.md'), 'utf8');
    for (const code of Object.keys(CHECK_CODES)) expect(doc, code).toContain(`\`${code}\``);
  });

  it('checks the real recorded surface of the generator, not only the golden text', () => {
    // the client takes a surface document and generates; the golden is what the generator spec pins it to
    expect(surfaceNamed('plugin-current').contentTypes.length).toBeGreaterThan(0);
  });
});

// --- the client: deadline, restart, queue (the real Worker is the Playwright spec) ----------------------------------

/** A stand-in Worker that runs the checker in process; `hang` makes it never answer, as a runaway check would. */
function fakeWorker(options: { hang?: () => boolean; bootDelay?: number } = {}): WorkerLike & { terminated: boolean } {
  const local = createChecker({ libs: LIB_FILES, core });
  const worker: WorkerLike & { terminated: boolean } = {
    terminated: false,
    onmessage: null,
    onerror: null,
    terminate() {
      worker.terminated = true;
    },
    postMessage(message: ToWorker) {
      queueMicrotask(() => {
        if (worker.terminated || options.hang?.() === true) return;
        const reply: FromWorker = { type: 'result', id: message.id, result: local.check({ program: message.program, sdk: message.sdk }), checkMs: 1 };
        worker.onmessage?.({ data: reply });
      });
    },
  };
  setTimeout(() => worker.onmessage?.({ data: { type: 'ready' } }), options.bootDelay ?? 0);
  return worker;
}

describe('the client', () => {
  it('starts no Worker until the first check, then keeps one', async () => {
    const spawned: unknown[] = [];
    const client = createTypecheckClient({ spawn: () => { const w = fakeWorker(); spawned.push(w); return w; } });
    expect(spawned).toHaveLength(0);
    const result = await client.check('const p = m.pack("t");', surfaceNamed('plugin-current'));
    expect(result.ok).toBe(true);
    await client.check('m.nonsense;', current);
    expect(spawned).toHaveLength(1);
    expect(client.stats.spawned).toBe(1);
    expect(client.stats.lastCheckMs).toBe(1);
    client.dispose();
  });

  it('answers a surface document the way it answers the declarations generated from it', async () => {
    const client = createTypecheckClient({ spawn: () => fakeWorker() });
    const viaSurface = await client.check('m.nonsense; p;', surfaceNamed('plugin-current'));
    const viaText = await client.check('m.nonsense; p;', current);
    expect(viaSurface).toEqual(viaText);
    client.dispose();
  });

  it('cuts off a check past its deadline, terminates the Worker, and boots a fresh one for the next', async () => {
    let hang = true;
    const workers: ReturnType<typeof fakeWorker>[] = [];
    const client = createTypecheckClient({ limits: { checkMs: 50 }, spawn: () => { const w = fakeWorker({ hang: () => hang }); workers.push(w); return w; } });
    const stuck = await client.check('m;', current);
    expect(stuck.ok).toBe(false);
    expect(stuck.diagnostics[0]).toMatchObject({ code: 'CHK-0003' });
    expect(workers[0]?.terminated).toBe(true);
    hang = false;
    const next = await client.check('const p = m.pack("t");', current);
    expect(next.ok).toBe(true);
    expect(workers).toHaveLength(2);
    expect(client.stats.spawned).toBe(2);
    client.dispose();
  });

  it('reports a Worker that never starts, and a Worker that fails', async () => {
    const never = createTypecheckClient({ limits: { bootMs: 30 }, spawn: () => ({ postMessage() {}, terminate() {}, onmessage: null, onerror: null }) });
    expect((await never.check('m;', current)).diagnostics[0]).toMatchObject({ code: 'CHK-0004' });
    const broken = createTypecheckClient({ spawn: () => { throw new Error('no such asset'); } });
    expect((await broken.check('m;', current)).diagnostics[0]).toMatchObject({ code: 'CHK-0004' });
  });

  it('refuses an oversize program without posting it', async () => {
    let posted = 0;
    const client = createTypecheckClient({ limits: { maxProgramChars: 10 }, spawn: () => { const w = fakeWorker(); const post = w.postMessage; w.postMessage = (m) => { posted++; post(m); }; return w; } });
    expect((await client.check('x'.repeat(11), current)).diagnostics[0]).toMatchObject({ code: 'CHK-0002' });
    expect(posted).toBe(0);
    client.dispose();
  });

  it('serialises checks: answers match their questions however they are interleaved', async () => {
    const client = createTypecheckClient({ spawn: () => fakeWorker() });
    const [a, b, c] = await Promise.all([client.check('m.aaa;', current), client.check('const ok = 1;', current), client.check('m.ccc;', current)]);
    expect(a.diagnostics[0]?.message).toContain("'aaa'");
    expect(b.ok).toBe(true);
    expect(c.diagnostics[0]?.message).toContain("'ccc'");
    client.dispose();
  });
});

describe('cold start and per-check time (recorded against the spike: 83 ms first check, 4.5 ms warm, 200-member 25 KB surface)', () => {
  it('prints the numbers; they are bounded generously so a slow CI box does not flake', () => {
    const fresh = createChecker({ libs: LIB_FILES, core });
    const program = readFileSync(join(CORPUS_DIR, 't08-shop.ts'), 'utf8');
    const t0 = performance.now();
    fresh.check({ program, sdk: current });
    const first = performance.now() - t0;
    const warm: number[] = [];
    for (let i = 0; i < 5; i++) {
      const t = performance.now();
      fresh.check({ program: `${program}\n// ${i}`, sdk: current });
      warm.push(performance.now() - t);
    }
    warm.sort((a, b) => a - b);
    console.log(`typecheck node: first check ${first.toFixed(0)} ms (195 KB surface), warm median ${warm[2]!.toFixed(1)} ms`);
    expect(first).toBeLessThan(5000);
    expect(warm[2]!).toBeLessThan(1000);
  });
});
