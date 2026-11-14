/**
 * The sandbox's limits and what it refuses (codemode.md section 4.2, 4.6), in Node with the real engine run in-process:
 * each limit ends a run with its structured diagnostic and NO operations, the editor (this process) keeps going, and a fresh
 * sandbox boots for the next run. A LYING core (a stand-in for a guest that escaped the program and controls the engine's
 * side) proves the host enforces the allowlist, the counts and the sizes itself, and never trusts a message or a result.
 * What needs a browser (the wall-clock backstop of a guest that blocks its thread, the CSP, the isolation probes) is the
 * Playwright specs'.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { SANDBOX_CODES, VIOLATION_CODE, type SandboxHost, type SandboxLimits, type SandboxRunner } from '../src/lib/codemode/sandbox';
import { DEFAULT_SANDBOX_LIMITS } from '../src/lib/codemode/sandbox';
import { surfaceNamed } from './helpers/codemode';
import { checked, hostOf, nodeRunner } from './helpers/sandbox-node';

const surface = surfaceNamed('plugin-current');
const WORLD = { packs: ['bench'], resources: { bench: [{ type: 'items', path: 'items/a1.yml', id: 'a1' }] } };
const runners: SandboxRunner[] = [];
afterEach(() => {
  for (const runner of runners.splice(0)) runner.dispose();
});

async function runnerOf(coreSrc?: string): Promise<SandboxRunner> {
  const runner = await nodeRunner(coreSrc === undefined ? {} : { coreSrc });
  runners.push(runner);
  return runner;
}

const codesOf = (result: { diagnostics: { code: string }[] }): string[] => result.diagnostics.map((diagnostic) => diagnostic.code);

/** A core that lies: `createSession` returns the given `finish` body, which sees `cfg` (surface, port, limits). */
const lyingCore = (finishBody: string): string =>
  `var MaterioneCore = { createSession: function (cfg) { return { m: {}, late: function () { return []; }, finish: async function (outcome) { ${finishBody} } }; } };`;

const GOOD_RESULT = `{ ok: true, sdkVersion: 'x', pack: null, operations: [], sources: [], diagnostics: [], summary: null, logs: [], returned: null, stats: { reads: 0, readBytes: 0, staged: 0, documents: 0 } }`;

describe('each limit ends the run with a diagnostic and no operations, and the next run works', () => {
  const failures: [string, string, string, Partial<SandboxLimits>][] = [
    ['an endless loop', 'm.pack("bench").items.create("a1", { material: "STONE" }); while (true) {}', 'SBX-0001', { cpuMs: 150 }],
    ['unbounded allocation', 'const keep = []; while (true) keep.push(new Array(100000).fill(1));', 'SBX-0002', { memoryMb: 32 }],
    ['an await that never settles', 'm.pack("bench").items.create("a1", { material: "STONE" }); await new Promise(() => {});', 'SBX-0009', {}],
  ];
  it.each(failures)('%s is %s', async (_name, program, code, limits) => {
    const runner = await runnerOf();
    const started = performance.now();
    const failed = await runner.run(checked(program), surface, hostOf(WORLD), limits);
    expect(failed.ok).toBe(false);
    expect(failed.operations).toEqual([]);
    expect(failed.sources).toEqual([]);
    expect(codesOf(failed)).toEqual([code]);
    expect(failed.diagnostics[0]!.hint).toBeTruthy();
    expect(performance.now() - started).toBeLessThan(5000);
    // a fresh sandbox boots afterwards and runs the next program
    const next = await runner.run(checked('const h = m.pack("bench").items.create("a1", { material: "STONE" }); return 1;'), surface, hostOf(WORLD));
    expect(next.ok).toBe(true);
    expect(next.operations).toHaveLength(1);
    expect(next.metrics.booted).toBe(true);
    expect(runner.stats.discarded).toBe(1);
  });

  it('every code is documented in the vocabulary, one violation each', () => {
    expect(new Set(Object.values(VIOLATION_CODE)).size).toBe(Object.keys(VIOLATION_CODE).length);
    for (const code of Object.values(VIOLATION_CODE)) expect(SANDBOX_CODES[code]).toBeTruthy();
  });

  it('deep recursion is the program\'s own error (QuickJS stack overflow), not a dead sandbox', async () => {
    const runner = await runnerOf();
    const result = await runner.run(checked('function f(n: number): number { return f(n + 1) + 1; } f(0);'), surface, hostOf(WORLD));
    expect(result.ok).toBe(false);
    expect(codesOf(result)).toEqual(['SDK-0001']);
    expect(result.diagnostics[0]!.message.toLowerCase()).toContain('stack');
    expect(result.diagnostics[0]!.source).toEqual({ line: 1, column: expect.any(Number) });
    const next = await runner.run(checked('return 2;'), surface, hostOf(WORLD));
    expect(next.value).toBe(2);
    expect(runner.stats.discarded).toBe(0);
  });

  it('a read the host never answers ends the run at the per-read deadline', async () => {
    const runner = await runnerOf();
    const host: SandboxHost = { read: () => new Promise(() => {}), resolve: async () => ({ packs: [], refs: [], instantiate: [], assets: [] }) };
    const started = performance.now();
    const result = await runner.run(checked('return await m.read.packs();'), surface, host, { readMs: 120 });
    expect(codesOf(result)).toEqual(['SBX-0005']);
    expect(result.operations).toEqual([]);
    expect(performance.now() - started).toBeLessThan(3000);
  });

  it('waiting on the host adds nothing to the CPU the program is charged, and guest setup is not charged to the program', async () => {
    const runner = await runnerOf();
    const slow = hostOf(WORLD);
    const WAIT_MS = 400;
    const host: SandboxHost = { read: async (op, payload) => (await new Promise((resolve) => setTimeout(resolve, WAIT_MS)), slow.read(op, payload)), resolve: (request) => slow.resolve(request) };
    // a deadline generous for a loaded runner: the property is on the ACCOUNTED engine time, not on a race with a tiny budget
    const result = await runner.run(checked('const p = await m.read.packs(); return p.length;'), surface, host, { cpuMs: 5000, wallMs: 5000 });
    expect(result.diagnostics).toEqual([]);
    expect(result.value).toBe(1);
    // the read's wait is wall time, not engine time
    expect(result.metrics.totalMs).toBeGreaterThanOrEqual(WAIT_MS);
    expect(result.metrics.cpuMs).toBeLessThan(WAIT_MS / 2);
    // setup is charged to nobody's CPU deadline: the program's accounted time fits in the engine's wall time minus setup
    expect(result.metrics.cpuMs).toBeLessThanOrEqual(result.metrics.engineWallMs - result.metrics.setupMs + 5);
  });

  it('the program\'s own diagnostics for the core\'s limits survive (reads, operations, logs, return value)', async () => {
    const runner = await runnerOf();
    const reads = await runner.run(checked('for (let i = 0; i < 5; i += 1) await m.read.resources({ pack: "bench", query: "q" + i });'), surface, hostOf(WORLD), { reads: 3 });
    expect(codesOf(reads)).toEqual(['SDK-0005']);
    expect(reads.operations).toEqual([]);
    const ops = await runner.run(checked('const p = m.pack("bench"); for (let i = 0; i < 5; i += 1) p.items.create("it" + i, {});'), surface, hostOf(WORLD), { operations: 3 });
    expect(codesOf(ops)).toEqual(['SDK-0005']);
    expect(ops.operations).toEqual([]);
    const returned = await runner.run(checked('return "x".repeat(5000);'), surface, hostOf(WORLD), { returnBytes: 1000 });
    expect(codesOf(returned)).toContain('SDK-0014');
    expect(returned.ok).toBe(false);
  });
});

describe('determinism: the same program and the same answers are the same bytes, in any sandbox', () => {
  const program = 'const stamp = [new Date().getTime(), Date.now(), typeof Date(), new Date(2020, 0, 1).getFullYear(), Math.random(), Math.random()]; m.pack("bench").items.create("a1", { material: "STONE" }); return stamp;';
  it('a fixed Date and a seeded Math.random, run after run and across fresh sandboxes', async () => {
    const first = await (await runnerOf()).run(checked(program), surface, hostOf(WORLD));
    const again = await (await runnerOf()).run(checked(program), surface, hostOf(WORLD));
    const same = await (await runnerOf()).run(checked(program), surface, hostOf(WORLD));
    const strip = (result: typeof first) => JSON.stringify({ ...result, metrics: undefined });
    expect(strip(first)).toBe(strip(again));
    expect(strip(first)).toBe(strip(same));
    expect((first.value as number[])[0]).toBe(Date.UTC(2026, 0, 1));
    expect((first.value as number[])[1]).toBe(Date.UTC(2026, 0, 1));
  });
  it('a second run in the same sandbox restarts the sequence', async () => {
    const runner = await runnerOf();
    const a = await runner.run(checked('return [Math.random(), Math.random()];'), surface, hostOf(WORLD));
    const b = await runner.run(checked('return [Math.random(), Math.random()];'), surface, hostOf(WORLD));
    expect(a.value).toEqual(b.value);
    expect(b.metrics.booted).toBe(false);
  });
});

describe('what the program can see', () => {
  it('QuickJS intrinsics and `m`, nothing else: no host function, no timer, no console, no network', async () => {
    const runner = await runnerOf();
    const result = await runner.run(
      checked(`return {
        names: Object.getOwnPropertyNames(globalThis).sort(),
        escapes: [Function('return this')() === globalThis, (() => {}).constructor('return this')() === globalThis, Object.getPrototypeOf(m).constructor.constructor('return this')() === globalThis],
        absent: ['fetch', 'XMLHttpRequest', 'WebSocket', 'window', 'document', 'self', 'parent', 'top', 'localStorage', 'indexedDB', 'setTimeout', 'queueMicrotask', 'console', 'postMessage', 'importScripts', 'WebAssembly', 'Atomics', 'performance', 'read', 'resolve', 'hostRead', 'hostResolve', 'MaterioneCore', 'Core', 'require', 'process'].filter((name) => typeof (globalThis as any)[name] !== 'undefined'),
      };`),
      surface,
      hostOf(WORLD),
    );
    const seen = result.value as { names: string[]; escapes: boolean[]; absent: string[] };
    expect(seen.absent).toEqual([]);
    expect(seen.escapes).toEqual([true, true, true]);
    expect(seen.names).toEqual([
      'AggregateError', 'Array', 'ArrayBuffer', 'BigInt', 'BigInt64Array', 'BigUint64Array', 'Boolean', 'DataView', 'Date', 'Error', 'EvalError',
      'FinalizationRegistry', 'Float16Array', 'Float32Array', 'Float64Array', 'Function', 'Infinity', 'Int16Array', 'Int32Array', 'Int8Array', 'InternalError',
      'Iterator', 'JSON', 'Map', 'Math', 'NaN', 'Number', 'Object', 'Promise', 'Proxy', 'RangeError', 'ReferenceError', 'Reflect', 'RegExp', 'Set',
      'SharedArrayBuffer', 'String', 'Symbol', 'SyntaxError', 'TypeError', 'URIError', 'Uint16Array', 'Uint32Array', 'Uint8Array', 'Uint8ClampedArray',
      'WeakMap', 'WeakRef', 'WeakSet', 'decodeURI', 'decodeURIComponent', 'encodeURI', 'encodeURIComponent', 'escape', 'eval', 'globalThis', 'isFinite', 'isNaN',
      'm', 'parseFloat', 'parseInt', 'undefined', 'unescape',
    ]);
  });

  it('`import()` rejects and there is no module loader', async () => {
    const result = await (await runnerOf()).run(checked('let outcome = "loaded"; try { await import("data:text/javascript,export default 1"); } catch (error) { outcome = "refused"; } return outcome;'), surface, hostOf(WORLD));
    expect(result.value).toBe('refused');
  });

  it('prototype pollution inside the guest stays in the guest: the host\'s realm and its handling of messages are untouched', async () => {
    const runner = await runnerOf();
    const result = await runner.run(
      checked(`(Object.prototype as any).polluted = 'yes'; (Object.prototype as any).ok = false; (Array.prototype as any).length; Object.defineProperty(Object.prototype, 'kind', { value: 'forged' });
        JSON.parse('{"__proto__": {"hostPolluted": true}}');
        const p = m.pack("bench"); p.items.create("a1", { material: "STONE" }); return { got: ({} as any).polluted };`),
      surface,
      hostOf(WORLD),
    );
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();
    expect(({} as Record<string, unknown>).hostPolluted).toBeUndefined();
    expect(({} as Record<string, unknown>).kind).toBeUndefined();
    // the run still ended as the program made it end, and the host read its result as data
    expect(result.ok).toBe(true);
    expect(result.operations).toHaveLength(1);
    expect(Object.getPrototypeOf(result.operations[0])).toBe(Object.prototype);
  });
});

describe('source positions: operations and errors name the line the agent wrote', () => {
  it('a staged operation and a thrown error map back through the source map to the program as written', async () => {
    const runner = await runnerOf();
    const program = 'const p = m.pack("bench");\n\nconst h: any = p.items.create("a1", { material: "STONE" });\nconst typed: number = 3;\nthrow new Error("boom " + typed);';
    const result = await runner.run(checked(program), surface, hostOf(WORLD));
    expect(result.ok).toBe(false);
    expect(result.diagnostics).toMatchObject([{ code: 'SDK-0001', message: expect.stringContaining('boom 3'), source: { line: 5 } }]);
    const staged = await runner.run(checked('const p = m.pack("bench");\n\n  p.items.create("a1", { material: "STONE" });'), surface, hostOf(WORLD));
    expect(staged.sources).toEqual([{ line: 3, column: 11 }]);
  });
});

describe('the host enforces what a guest could lie about (a lying core)', () => {
  it('an op outside the SDK\'s reads is never made: a write op, a made-up op, an op of the resolve family', async () => {
    for (const op of ['authoring.transaction', 'admin.shutdown', 'logic.instantiate', 'browse.resources']) {
      const runner = await runnerOf(lyingCore(`await cfg.port.read('${op}', {}); return ${GOOD_RESULT};`));
      const host = hostOf(WORLD);
      const result = await runner.run(checked('return 1;'), surface, host);
      expect(codesOf(result), op).toEqual(['SBX-0010']);
      expect(host.calls, op).toEqual([]);
      expect(result.operations).toEqual([]);
    }
  });

  it('a read past the budget ends the run even when the core would have allowed it', async () => {
    const runner = await runnerOf(lyingCore(`for (let i = 0; i < 500; i += 1) await cfg.port.read('browse.packs', { i: i }); return ${GOOD_RESULT};`));
    const host = hostOf(WORLD);
    const result = await runner.run(checked('return 1;'), surface, host, { reads: 10 });
    expect(codesOf(result)).toEqual(['SBX-0004']);
    expect(host.calls.length).toBeLessThanOrEqual(10);
  });

  it('answers past the byte budget end the run', async () => {
    const runner = await runnerOf(lyingCore(`for (let i = 0; i < 50; i += 1) await cfg.port.read('browse.packs', {}); return ${GOOD_RESULT};`));
    const result = await runner.run(checked('return 1;'), surface, hostOf(WORLD), { readBytes: 200 });
    expect(codesOf(result)).toEqual(['SBX-0004']);
  });

  it('a payload past its cap is refused', async () => {
    const runner = await runnerOf(lyingCore(`await cfg.port.read('browse.packs', { big: 'x'.repeat(100000) }); return ${GOOD_RESULT};`));
    const result = await runner.run(checked('return 1;'), surface, hostOf(WORLD));
    expect(codesOf(result)).toEqual(['SBX-0010']);
  });

  it('the seal resolves once: a second resolve, or a malformed request, ends the run', async () => {
    const twice = await runnerOf(lyingCore(`const req = { packs: [], refs: [], instantiate: [], assets: [] }; await cfg.port.resolve(req); await cfg.port.resolve(req); return ${GOOD_RESULT};`));
    expect(codesOf(await twice.run(checked('return 1;'), surface, hostOf(WORLD)))).toEqual(['SBX-0010']);
    const bad = await runnerOf(lyingCore(`await cfg.port.resolve({ packs: 'x' }); return ${GOOD_RESULT};`));
    expect(codesOf(await bad.run(checked('return 1;'), surface, hostOf(WORLD)))).toEqual(['SBX-0010']);
  });

  it('too many operations, or too many bytes of them, is refused whatever the core says', async () => {
    const many = await runnerOf(lyingCore(`const ops = []; for (let i = 0; i < 300; i += 1) ops.push({ kind: 'create', i: i }); return ${GOOD_RESULT.replace('operations: [], sources: []', 'operations: ops, sources: ops.map(function () { return null; })')};`));
    const manyResult = await many.run(checked('return 1;'), surface, hostOf(WORLD));
    expect(codesOf(manyResult)).toEqual(['SBX-0007']);
    expect(manyResult.operations).toEqual([]);
    const big = await runnerOf(lyingCore(`const ops = [{ kind: 'create', text: 'x'.repeat(300000) }]; return ${GOOD_RESULT.replace('operations: [], sources: []', 'operations: ops, sources: [null]')};`));
    expect(codesOf(await big.run(checked('return 1;'), surface, hostOf(WORLD)))).toEqual(['SBX-0007']);
  });

  it('logs and a return value past their caps are refused', async () => {
    const logs = await runnerOf(lyingCore(`return ${GOOD_RESULT.replace('logs: []', "logs: ['x'.repeat(20000)]")};`));
    expect(codesOf(await logs.run(checked('return 1;'), surface, hostOf(WORLD)))).toEqual(['SBX-0008']);
    const value = await runnerOf(lyingCore(`return ${GOOD_RESULT.replace('returned: null', "returned: 'x'.repeat(100000)")};`));
    expect(codesOf(await value.run(checked('return 1;'), surface, hostOf(WORLD)))).toEqual(['SBX-0008']);
  });

  it('a result of the wrong shape is refused, and the frame is discarded', async () => {
    for (const body of [`return { ok: 'yes' };`, `return ${GOOD_RESULT.replace('operations: []', "operations: [{ nokind: 1 }]").replace('sources: []', 'sources: [null]')};`, `return ${GOOD_RESULT.replace("diagnostics: []", "diagnostics: [{ code: 5 }]")};`]) {
      const runner = await runnerOf(lyingCore(body));
      const result = await runner.run(checked('return 1;'), surface, hostOf(WORLD));
      expect(codesOf(result), body).toEqual(['SBX-0010']);
      expect(runner.stats.discarded).toBe(1);
    }
  });

  it('a forged result is data: `__proto__` keys do not reach a prototype, operations stay plain', async () => {
    const forged = `JSON.parse('{"kind":"create","__proto__":{"polluted":true},"constructor":{"prototype":{"polluted2":true}}}')`;
    const runner = await runnerOf(lyingCore(`return ${GOOD_RESULT.replace('operations: [], sources: []', `operations: [${forged}], sources: [null]`)};`));
    const result = await runner.run(checked('return 1;'), surface, hostOf(WORLD));
    expect(result.ok).toBe(true);
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();
    expect(({} as Record<string, unknown>).polluted2).toBeUndefined();
    expect(Object.getPrototypeOf(result.operations[0])).toBe(Object.prototype);
    expect(Object.hasOwn(result.operations[0]!, '__proto__')).toBe(true);
  });

  it('an error result is never given operations, whatever the guest says', async () => {
    const runner = await runnerOf(lyingCore(`return ${GOOD_RESULT.replace('ok: true', 'ok: true').replace('operations: [], sources: []', "operations: [{ kind: 'create' }], sources: [null]").replace('diagnostics: []', "diagnostics: [{ code: 'SDK-0001', severity: 'error', message: 'x' }]")};`));
    const result = await runner.run(checked('return 1;'), surface, hostOf(WORLD));
    expect(result.ok).toBe(false);
    expect(result.operations).toEqual([]);
  });
});

describe('defaults', () => {
  it('are the numbers of codemode.md section 4.2', () => {
    expect(DEFAULT_SANDBOX_LIMITS).toMatchObject({ cpuMs: 1000, wallMs: 10_000, readMs: 5_000, memoryMb: 64, stackBytes: 262_144, reads: 100, readBytes: 2_097_152, operations: 200, stagedBytes: 262_144, logBytes: 16_384, returnBytes: 65_536 });
  });
});
