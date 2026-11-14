// SPIKE (#340) - throwaway. Runs INSIDE the opaque-origin iframe. Loads QuickJS-WASM from bytes the
// host transfers (no fetch: the frame's CSP is connect-src 'none'), then runs one-shot programs.
import { newQuickJSWASMModuleFromVariant, newVariant } from 'quickjs-emscripten-core';
import baseVariant from '@jitl/quickjs-wasmfile-release-sync';
import type { QuickJSWASMModule, QuickJSHandle } from 'quickjs-emscripten-core';
import type { FrameToHost, HostToFrame, Limits, Violation, GuestError, StagedRaw, ResultMsg } from './protocol';

let port: MessagePort | null = null;
let qjs: QuickJSWASMModule | null = null;
const post = (m: FrameToHost) => port!.postMessage(m);

// Answers to guest reads, keyed `${runId}:${rid}`.
const readWaiters = new Map<string, (ok: boolean, json: string) => void>();

/** Runs in the iframe's main thread (frame.ts) or in a Worker the iframe spawned (worker-entry.ts). */
export async function startEngine(p: MessagePort, wasm: ArrayBuffer, memMaxMb = 64) {
  port = p;
  port.onmessage = (ev) => onHost(ev.data as HostToFrame);
  const t0 = performance.now();
  // The memory cap that actually holds: QuickJS's own setMemoryLimit does not bound array/string storage
  // (measured: 22,000 x 80 KB arrays allocated under a 16 MB limit), so the WebAssembly.Memory itself is
  // given a maximum. Growth past it makes malloc return NULL and QuickJS throws "out of memory".
  const wasmMemory = new WebAssembly.Memory({ initial: 256, maximum: memMaxMb * 16 });
  const variant = newVariant(baseVariant, { wasmBinary: wasm, wasmMemory });
  qjs = await newQuickJSWASMModuleFromVariant(variant);
  post({ t: 'ready', initMs: performance.now() - t0 });
}

function onHost(m: HostToFrame) {
  if (m.t === 'run') void run(m);
  else if (m.t === 'readResult') readWaiters.get(`${m.runId}:${m.rid}`)?.(m.ok, m.json);
}

const PRELUDE = `(function (read, stage, log, FIXED, SEED) {
  const D = Date;
  function FD(...a) {
    if (!new.target) return new D(FIXED).toString();
    return a.length ? new D(...a) : new D(FIXED);
  }
  FD.prototype = D.prototype; FD.now = () => FIXED; FD.parse = D.parse; FD.UTC = D.UTC;
  Object.defineProperty(D.prototype, 'constructor', { value: FD, writable: true, configurable: true });
  globalThis.Date = FD;
  let s = SEED >>> 0;
  Math.random = () => { s = (s + 0x6D2B79F5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const J = JSON;
  return Object.freeze({
    read: (op, args) => read(String(op), J.stringify(args === undefined ? null : args)).then(r => J.parse(r)),
    stage: (op) => stage(J.stringify(op), new Error().stack),
    log: (...a) => log(a.map(x => typeof x === 'string' ? x : J.stringify(x)).join(' ')),
  });
})`;

function lineOf(stack: string | undefined): { line: number | null; col: number | null } {
  // QuickJS: "    at __main (program.js:3:9)" - take the first frame inside program.js.
  const mm = /program\.js:(\d+)(?::(\d+))?/.exec(stack ?? '');
  return mm ? { line: +mm[1]!, col: mm[2] ? +mm[2] : null } : { line: null, col: null };
}

async function run(m: Extract<HostToFrame, { t: 'run' }>) {
  const L: Limits = m.limits;
  const wall0 = performance.now();
  const runtime = qjs!.newRuntime();
  const ctx = runtime.newContext();
  let violation: Violation | undefined;
  const flag = (v: Violation) => (violation ??= v);
  const staged: StagedRaw[] = [];
  let stagedBytes = 0;
  const logs: string[] = [];
  let logBytes = 0;
  let reads = 0;
  let readBytes = 0;
  let setupMs = 0;
  let poisoned = false;

  // CPU accounting: only time spent inside the engine counts; waiting for the host does not.
  let cpuUsed = 0;
  let sliceStart = performance.now();
  let inSlice = false;
  const beginSlice = () => { sliceStart = performance.now(); inSlice = true; };
  const endSlice = () => { if (inSlice) cpuUsed += performance.now() - sliceStart; inSlice = false; };
  runtime.setMemoryLimit(L.memBytes);
  runtime.setMaxStackSize(L.stackBytes);
  runtime.setInterruptHandler(() => {
    if (cpuUsed + (performance.now() - sliceStart) > L.cpuMs) { flag('cpu-deadline'); return true; }
    return false;
  });

  const pendingIds = new Set<number>();
  let wake: (() => void) | null = null;
  const handles: QuickJSHandle[] = [];
  const keep = (h: QuickJSHandle) => (handles.push(h), h);

  const nRead = ctx.newFunction('read', (opH, argsH) => {
    const op = ctx.getString(opH), args = ctx.getString(argsH);
    if (++reads > L.maxReads) { flag('read-limit'); return { error: ctx.newError('read limit') }; }
    const rid = reads;
    const d = ctx.newPromise();
    keep(d.handle);
    pendingIds.add(rid);
    readWaiters.set(`${m.runId}:${rid}`, (ok, json) => {
      pendingIds.delete(rid);
      readWaiters.delete(`${m.runId}:${rid}`);
      readBytes += json.length;
      if (readBytes > L.maxReadBytes) flag('read-limit');
      if (ok) d.resolve(ctx.newString(json)); else d.reject(ctx.newError(json));
      wake?.();
    });
    post({ t: 'read', runId: m.runId, rid, op, args });
    return d.handle;
  });
  const nStage = ctx.newFunction('stage', (jH, stackH) => {
    const json = ctx.getString(jH);
    stagedBytes += json.length;
    if (staged.length >= L.maxStaged || stagedBytes > L.maxStagedBytes) { flag('stage-limit'); return { error: ctx.newError('stage limit') }; }
    staged.push({ json, jsLine: lineOf(ctx.typeof(stackH) === 'string' ? ctx.getString(stackH) : undefined).line });
    return ctx.undefined;
  });
  const nLog = ctx.newFunction('log', (sH) => {
    const s = ctx.getString(sH);
    logBytes += s.length;
    if (logBytes > L.maxLogBytes) { flag('log-limit'); return { error: ctx.newError('log limit') }; }
    logs.push(s);
    return ctx.undefined;
  });

  const finish = (extra: { ok: boolean; error?: GuestError; returnJson?: string | null }) => {
    endSlice();
    for (const h of handles) if (h.alive) h.dispose();
    for (const h of [nRead, nStage, nLog]) if (h.alive) h.dispose();
    // A program that retained memory in a global (or died mid-allocation) can make QuickJS's leak assertion
    // abort the whole module on dispose: then the instance is dead and the host must discard the frame.
    try { ctx.dispose(); runtime.dispose(); } catch { poisoned = true; }
    for (const k of [...readWaiters.keys()]) if (k.startsWith(`${m.runId}:`)) readWaiters.delete(k);
    const ok = extra.ok && violation === undefined;
    const res: ResultMsg = {
      t: 'result', runId: m.runId, ok, violation, error: extra.error, staged: ok ? staged : [], logs,
      returnJson: extra.returnJson ?? null, poisoned, cpuMs: cpuUsed, wallMs: performance.now() - wall0, setupMs,
    };
    post(res);
  };
  const errOf = (h: QuickJSHandle): GuestError => {
    const o = ctx.dump(h) as { name?: string; message?: string; stack?: string } | string;
    const obj = typeof o === 'object' && o ? o : { message: String(o) };
    const { line, col } = lineOf(obj.stack);
    return { name: obj.name ?? 'Error', message: String(obj.message ?? ''), jsLine: line, jsCol: col };
  };
  const classify = (e: GuestError) => {
    if (/interrupted/i.test(e.message)) flag('cpu-deadline');
    else if (/out of memory/i.test(e.message)) flag('memory-limit');
    else if (/stack overflow/i.test(e.message)) flag('stack-limit');
  };
  const fail = (h: QuickJSHandle) => { const e = errOf(h); h.dispose(); classify(e); return finish({ ok: false, error: e }); };

  try {
    beginSlice();
    const pre = ctx.evalCode(PRELUDE, 'prelude.js');
    if (pre.error) return fail(pre.error);
    const mk = keep(pre.value);
    const fx = keep(ctx.newNumber(m.fixedTime)), sd = keep(ctx.newNumber(m.seed));
    const mr = ctx.callFunction(mk, ctx.undefined, nRead, nStage, nLog, fx, sd);
    if (mr.error) return fail(mr.error);
    const mObj = keep(mr.value);
    const progR = ctx.evalCode(m.js, 'program.js');
    if (progR.error) return fail(progR.error);
    const fn = keep(progR.value);
    setupMs = performance.now() - wall0;
    const pr = ctx.callFunction(fn, ctx.undefined, mObj);
    if (pr.error) return fail(pr.error);
    const promise = keep(pr.value);
    for (;;) {
      if (!inSlice) beginSlice();
      const jobs = runtime.executePendingJobs();
      if (jobs.error) { const e = errOf(jobs.error); jobs.error.dispose(); classify(e); }
      endSlice();
      if (violation) return finish({ ok: false });
      const st = ctx.getPromiseState(promise);
      if (st.type === 'fulfilled') {
        const v = keep(st.value);
        let json: string | null = null;
        if (ctx.typeof(v) !== 'undefined') {
          const js = ctx.getProp(ctx.global, 'JSON');
          const str = ctx.getProp(js, 'stringify');
          beginSlice();
          const r = ctx.callFunction(str, js, v);
          endSlice();
          js.dispose(); str.dispose();
          if (r.error) return fail(r.error);
          json = ctx.typeof(r.value) === 'string' ? ctx.getString(r.value) : null;
          r.value.dispose();
        }
        if (json !== null && json.length > L.maxResultBytes) { flag('result-limit'); return finish({ ok: false }); }
        return finish({ ok: true, returnJson: json });
      }
      if (st.type === 'rejected') return fail(st.error);
      if (pendingIds.size === 0) { flag('deadlock'); return finish({ ok: false }); }
      await new Promise<void>((res) => { wake = res; });
      wake = null;
    }
  } catch (e) {
    // Engine-level failure (should not happen): report, never throw out of the frame.
    const msg = String((e as Error)?.message ?? e);
    // A native wasm stack overflow (QuickJS's own limit set too high for the browser's stack) leaves the
    // module's shadow stack unusable: tell the host to discard this frame.
    if (/Maximum call stack/i.test(msg)) { flag('stack-limit'); poisoned = true; }
    return finish({ ok: false, error: { name: 'HostError', message: msg, jsLine: null, jsCol: null } });
  }
}
