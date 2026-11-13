// SPIKE (#340) - throwaway. Isolation + limits + determinism + line mapping, against the real
// harness (iframe sandbox="allow-scripts", QuickJS-WASM fed over a MessageChannel).
import { test, expect, type Page } from '@playwright/test';

declare global { interface Window { spike: any } }

async function boot(page: Page) {
  await page.goto('/harness.html');
  await page.waitForFunction(() => document.title === 'spike ready');
}
/** Create a sandbox in the page; returns its index in window.spike.created. */
let MODE: 'frame' | 'worker' = 'frame';
async function mk(page: Page, readDelayMs = 0, memMaxMb = 64): Promise<number> {
  return page.evaluate(async ([d, m, mem]) => { await window.spike.make(d, m, mem); return window.spike.created.length - 1; }, [readDelayMs, MODE, memMaxMb] as const);
}
async function runTs(page: Page, idx: number, body: string, limits: object = {}, wall = 5000) {
  return page.evaluate(([i, b, l, w]) => window.spike.created[i as number].runTs(b, l, w), [idx, body, limits, wall] as const);
}
const hits = async (page: Page) => (await (await page.request.get('/__hits')).json()) as string[];

test.describe('outer layer: the opaque-origin iframe with its CSP (code that escaped QuickJS)', () => {
  async function probe(page: Page, file: string) {
    return page.evaluate((f) => new Promise<Record<string, string>>((res) => {
      window.addEventListener('message', (e) => { if (e.data?.t === 'probe-result') res(e.data.out); });
      const fr = document.createElement('iframe');
      fr.setAttribute('sandbox', 'allow-scripts');
      fr.src = f;
      document.body.appendChild(fr);
    }), file);
  }

  test('with the CSP: nothing reachable, nothing reaches the server', async ({ page }) => {
    await boot(page);
    await page.request.get('/__reset');
    const out = await probe(page, '/probe.html');
    console.log('PROBE (CSP):', JSON.stringify(out));
    expect(out.opaque).toBe('true');
    // sendBeacon and a nested iframe are fire-and-forget (no error either way): the server's hit log is
    // their evidence.
    for (const [k, v] of Object.entries(out)) if (!['origin', 'opaque', 'sendBeacon', 'nested iframe'].includes(k)) expect(v, k).toMatch(/^blocked/);
    expect(await hits(page)).toEqual([]);
  });

  test('without the CSP: the sandbox attribute alone leaves the network open (so the CSP is load-bearing)', async ({ page }) => {
    await boot(page);
    await page.request.get('/__reset');
    const out = await probe(page, '/probe-nocsp.html');
    console.log('PROBE (no CSP):', JSON.stringify(out));
    // Origin boundary holds by itself ...
    for (const k of ['parent.document', 'parent.localStorage', 'parent.__secret', 'own localStorage', 'own sessionStorage', 'document.cookie', 'indexedDB', 'top.location.href', 'top navigation', 'window.open']) {
      expect(out[k], k).toMatch(/^blocked/);
    }
    // ... but nothing stops requests: fetch/XHR reach the server (CORS only hides the answer).
    expect(out['fetch absolute']).toBe('ALLOWED');
    expect((await hits(page)).length).toBeGreaterThan(0);
  });
});

test.describe('outer layer, MODE worker: the blob: Worker the iframe spawns inherits origin and CSP', () => {
  test('worker probe: no network, no editor storage, no editor BroadcastChannel', async ({ page }) => {
    await boot(page);
    await page.request.get('/__reset');
    const out = await page.evaluate(() => new Promise<Record<string, string>>((res) => {
      window.addEventListener('message', (e) => { if (e.data?.t === 'probe-result') res(e.data.out); });
      const fr = document.createElement('iframe');
      fr.setAttribute('sandbox', 'allow-scripts');
      fr.src = '/probe-worker.html';
      document.body.appendChild(fr);
    }));
    console.log('PROBE (worker):', JSON.stringify(out));
    expect(out.error).toBeUndefined();
    for (const k of ['fetch', 'fetch no-cors', 'XMLHttpRequest', 'WebSocket', 'importScripts', 'import(data:)', 'nested Worker']) expect(out[k], k).toMatch(/^blocked/);
    expect(await hits(page)).toEqual([]);
    await page.waitForTimeout(300);
    expect(await page.evaluate(() => (window as any).__bc)).toEqual([]); // nothing reached the editor's channel
    // IndexedDB/caches from an opaque origin are separate stores (or refused): never the editor's.
    console.log('worker idb/caches:', out['indexedDB (editor db)'], out.caches);
  });
});

for (const mode of ['frame', 'worker'] as const) {
test.describe(`inner layer (engine in ${mode}): guest code in QuickJS`, () => {
  test.beforeEach(() => { MODE = mode; });
  test('globals: only standard intrinsics exist; nothing ambient', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `return Object.getOwnPropertyNames(globalThis).sort();`);
    expect(r.ok).toBe(true);
    const names = r.returnValue as string[];
    console.log('GUEST GLOBALS:', names.join(','));
    for (const banned of ['fetch', 'XMLHttpRequest', 'WebSocket', 'window', 'self', 'document', 'parent', 'top', 'localStorage', 'indexedDB', 'setTimeout', 'setInterval', 'queueMicrotask', 'console', 'postMessage', 'importScripts', 'require', 'process', 'navigator', 'location', 'Worker', 'WebAssembly', 'Atomics', 'performance', 'crypto', 'm', '__main'])
      expect(names, banned).not.toContain(banned);
  });

  test('network, DOM, parent, storage, timers, import() are unavailable and nothing hits the server', async ({ page }) => {
    await boot(page);
    await page.request.get('/__reset');
    const s = await mk(page);
    const r = await runTs(page, s, `
      const t = (x: string) => { try { return (0, eval)('typeof ' + x); } catch (e) { return 'threw'; } };
      const names = ['fetch','XMLHttpRequest','WebSocket','EventSource','window','document','parent','top','localStorage','sessionStorage','indexedDB','setTimeout','postMessage','navigator','location','Worker'];
      const out: Record<string, string> = {};
      for (const n of names) out[n] = t(n);
      let imp = 'resolved';
      try { await import('data:text/javascript,export default 1'); } catch (e) { imp = 'rejected'; }
      let via = 'none';
      try { via = String((Function('return this')() as any).fetch); } catch (e) { via = 'threw'; }
      let cons = 'none';
      try { cons = String((m as any).constructor.constructor('return typeof fetch')()); } catch (e) { cons = 'threw'; }
      return { out, imp, via, cons, secret: t('__secret'), canary: t('canary') };
    `);
    expect(r.ok).toBe(true);
    const v = r.returnValue as any;
    for (const [k, t] of Object.entries(v.out)) expect(t, k).toBe('undefined');
    expect(v.imp).toBe('rejected');
    expect(v.via).toBe('undefined');
    expect(v.cons).toBe('undefined');
    expect(v.secret).toBe('undefined');
    expect(v.canary).toBe('undefined');
    expect(await hits(page)).toEqual([]);
  });

  test('a fake SDK: async read across the channel, staged ops carry the TS line', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `interface Item {
  id: string;
  name: string;
}
const d = await m.read('registry.describe');
const it: Item = await m.read('item.get', { id: 'sword' });
m.stage({ op: 'item.create', id: it.id });
m.log('hello', d);
m.stage({ op: 'item.set', id: it.id, field: 'name' });
return d.types.length;`);
    expect(r.ok).toBe(true);
    expect(r.returnValue).toBe(3);
    expect(r.staged.map((x: any) => x.op.op)).toEqual(['item.create', 'item.set']);
    expect(r.staged.map((x: any) => x.tsLine)).toEqual([7, 9]);
    expect(r.logs[0]).toContain('hello');
  });

  test('a read the host did not map is rejected, not forwarded', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `try { await m.read('fs.readAll', {}); return 'leak'; } catch (e) { return 'rejected'; }`);
    expect(r.returnValue).toBe('rejected');
  });

  test('a throw returns a structured error mapped to the TS line', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `interface A { a: number }
type B = A & {
  b: string
}
const x: B = { a: 1, b: 'x' };
function boom(n: number): never {
  throw new RangeError('bad ' + n);
}
m.stage({ op: 'before' });
boom(x.a);`);
    expect(r.ok).toBe(false);
    expect(r.staged).toEqual([]); // a failed program yields no operations
    expect(r.error.name).toBe('RangeError');
    expect(r.error.message).toBe('bad 1');
    expect(r.error.tsLine).toBe(7);
    // The JS line differs from the TS line: the type declarations are gone from the output.
    expect(r.error.jsLine).not.toBe(r.error.tsLine);
  });

  test('a syntax error is reported with its TS line', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `const a = 1;\nconst b = ;\n`);
    expect(r.ok).toBe(false);
    expect(r.error.tsLine).toBe(2);
  });

  test('determinism: Date and Math.random are fixed and seeded', async ({ page }) => {
    await boot(page);
    const s1 = await mk(page);
    const s2 = await mk(page);
    const prog = `return { now: Date.now(), iso: new Date().toISOString(), str: typeof Date(), r: [Math.random(), Math.random(), Math.random()], cons: (new Date(0)) instanceof Date };`;
    const a = await runTs(page, s1, prog);
    const b = await runTs(page, s2, prog);
    const c = await runTs(page, s1, prog);
    expect(a.ok && b.ok && c.ok).toBe(true);
    expect(a.returnValue).toEqual(b.returnValue);
    expect(a.returnValue).toEqual(c.returnValue);
    expect((a.returnValue as any).iso).toBe('2026-01-01T00:00:00.000Z');
    expect((a.returnValue as any).cons).toBe(true);
  });
});

}

for (const mode of ['frame', 'worker'] as const) {
test.describe(`limits (engine in ${mode}): each ends the run with a diagnostic, no operations, and a live page`, () => {
  test.beforeEach(() => { MODE = mode; });
  /** Max gap between 10 ms ticks of the EDITOR page while `f` runs: how long the editor froze. */
  async function withLag<T>(page: Page, f: () => Promise<T>) {
    await page.evaluate(() => {
      const w = window as any; w.__lag = 0; let last = performance.now();
      w.__tick = setInterval(() => { const n = performance.now(); w.__lag = Math.max(w.__lag, n - last); last = n; }, 10);
    });
    const r = await f();
    const lag = await page.evaluate(() => { clearInterval((window as any).__tick); return (window as any).__lag as number; });
    return { r, lag };
  }

  test('infinite loop: interrupt deadline, no ops, same frame still works', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const { r, lag } = await withLag(page, () => runTs(page, s, `m.stage({ op: 'a' });\nwhile (true) {}`, { cpuMs: 250 }));
    console.log(`LOOP[${mode}]: wall=${r.metrics.wallMs.toFixed(0)}ms cpu=${r.metrics.cpuMs.toFixed(0)}ms editorLag=${lag.toFixed(0)}ms`);
    // The headline finding: with the engine on the iframe's main thread the editor freezes for the whole
    // deadline (same renderer thread); in a Worker it does not.
    if (mode === 'worker') expect(lag).toBeLessThan(150);
    else expect(lag).toBeGreaterThan(150);
    expect(r.ok).toBe(false);
    expect(r.violation).toBe('cpu-deadline');
    expect(r.staged).toEqual([]);
    expect(r.metrics.wallMs).toBeLessThan(250 + 500);
    const after = await runTs(page, s, `return 1 + 1;`);
    expect(after.returnValue).toBe(2);
  });

  test('infinite loop that swallows the interrupt error is still killed', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `while (true) { try { while (true) {} } catch (e) {} }`, { cpuMs: 250 });
    expect(r.violation).toBe('cpu-deadline');
    expect(r.metrics.wallMs).toBeLessThan(1500);
  });

  test('async loop (promise ping-pong) is bounded by the same CPU deadline', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `async function f(): Promise<void> { for (;;) await Promise.resolve(); }\nawait f();`, { cpuMs: 250 });
    expect(r.violation).toBe('cpu-deadline');
  });

  test('unbounded allocation: memory cap, no ops, frame still works', async ({ page }) => {
    await boot(page);
    const s = await mk(page, 0, 32); // hard cap: WebAssembly.Memory maximum 32 MiB
    const prog = `m.stage({ op: 'a' });\nconst a: number[][] = [];\nwhile (true) a.push(new Array(10000).fill(a.length));`;
    // QuickJS's own memBytes is set far above the cap on purpose: it does not bound array/string storage.
    const { r, lag } = await withLag(page, () => runTs(page, s, prog, { memBytes: 256 * 1024 * 1024 }));
    console.log(`ALLOC[${mode}]: wall=${r.metrics.wallMs.toFixed(0)}ms editorLag=${lag.toFixed(0)}ms violation=${r.violation}`);
    if (mode === 'worker') expect(lag).toBeLessThan(150);
    expect(r.violation).toBe('memory-limit');
    expect(r.staged).toEqual([]);
    expect(r.metrics.wallMs).toBeLessThan(1000);
    const alive = await runTs(page, s, `return 'alive';`);
    console.log(`ALLOC[${mode}] after: poisoned=${alive.poisoned} ok=${alive.ok}`);
    // strings too
    const str = await runTs(page, s, `const a: string[] = []; let i = 0; while (true) a.push('abc'.repeat(1000) + i++);`, { memBytes: 256 * 1024 * 1024 });
    expect(str.violation).toBe('memory-limit');
  });

  test('string doubling bomb is contained', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const r = await runTs(page, s, `let s = 'ab';\nwhile (true) s += s;`);
    expect(r.ok).toBe(false);
    // QuickJS refuses the string itself ("string too long"): an ordinary exception, so a diagnostic.
    expect(r.error?.message).toMatch(/too long|memory/i);
    expect((await runTs(page, s, `return 'alive';`)).returnValue).toBe('alive');
  });

  test('one huge allocation and a deep recursion are contained', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const a = await runTs(page, s, `const b = new Array(1e9).fill(0); return b.length;`);
    expect(a.ok).toBe(false);
    expect(a.violation).toBe('memory-limit');
    const b = await runTs(page, s, `function f(n: number): number { return f(n + 1) + 1; }\nreturn f(0);`);
    expect(b.violation).toBe('stack-limit');
  });

  test('wall-clock backstop: the host destroys a frame that never answers', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    // A read the host answers after 60 s (artificial) with a 400 ms wall budget.
    const r = await page.evaluate(async () => {
      const sb = await window.spike.make(60_000);
      const out = await sb.runTs(`return await m.read('echo', 1);`, {}, 400);
      return { out, frameAttached: sb.frame.isConnected };
    });
    expect(r.out.violation).toBe('wall-timeout');
    expect(r.out.killed).toBe(true);
    expect(r.frameAttached).toBe(false);
    // A fresh sandbox boots fine after a kill.
    const s2 = await mk(page);
    expect((await runTs(page, s2, `return 7;`)).returnValue).toBe(7);
    void s;
  });

  test('staging past the cap, read count, log size, result size', async ({ page }) => {
    await boot(page);
    const s = await mk(page);
    const st = await runTs(page, s, `for (let i = 0; i < 1000; i++) m.stage({ op: 'x', i });`, { maxStaged: 50 });
    expect(st.violation).toBe('stage-limit');
    expect(st.staged).toEqual([]);
    const sw = await runTs(page, s, `for (let i = 0; i < 1000; i++) { try { m.stage({ op: 'x', i }); } catch (e) { /* swallowed */ } }`, { maxStaged: 50 });
    expect(sw.violation).toBe('stage-limit'); // swallowing the error does not help
    expect(sw.staged).toEqual([]);
    const rd = await runTs(page, s, `for (let i = 0; i < 1000; i++) await m.read('echo', i);`, { maxReads: 10 });
    expect(rd.violation).toBe('read-limit');
    const lg = await runTs(page, s, `for (let i = 0; i < 1000; i++) m.log('x'.repeat(100));`, { maxLogBytes: 1000 });
    expect(lg.violation).toBe('log-limit');
    const rs = await runTs(page, s, `return 'x'.repeat(100000);`, { maxResultBytes: 1000 });
    expect(rs.violation).toBe('result-limit');
  });
});
}
