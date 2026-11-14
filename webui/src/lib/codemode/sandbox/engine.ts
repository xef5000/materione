/**
 * The engine (codemode.md section 4.3, 4.6): QuickJS compiled to WebAssembly, the SYNC build (no asyncify), running one
 * program per fresh runtime. It runs in a Worker the sandboxed frame spawns from a blob URL, so a CPU-bound guest never blocks a
 * page's main thread (spike amendment 1); `worker-entry.ts` is its entry and a test runs it in-process over a `MessageChannel`.
 *
 * What the engine is and is not. It is the host's tool for bounding a guest: an interrupt handler for CPU time, a
 * `WebAssembly.Memory` with a `maximum` for memory (QuickJS's own limit does not bound array or string storage: spike
 * amendment 2), a 256 KiB QuickJS stack (amendment 3). It is NOT the host's authority: the host re-checks everything it is
 * told here (reads, bytes, sizes, shapes), and a wall-clock backstop destroys the whole frame if the engine never answers.
 *
 * The guest sees QuickJS's intrinsics and one global, `m`, the SDK core's object. The host functions (`read`, `resolve`) are
 * arguments of the setup function, closed over by the core's port, never globals: a program cannot name them. `m.read.*` return
 * guest promises that the host resolves when its answer arrives (then pending jobs are pumped); staging makes no host call;
 * the one `resolve` happens at the seal, inside `session.finish`.
 */
import { newQuickJSWASMModuleFromVariant, newVariant } from 'quickjs-emscripten-core';
import baseVariant from '@jitl/quickjs-wasmfile-release-sync';
import type { QuickJSContext, QuickJSDeferredPromise, QuickJSHandle, QuickJSWASMModule } from 'quickjs-emscripten-core';
import type { CallKind, EngineResult, EngineToHost, HostToEngine, Violation } from './protocol';

type Run = Extract<HostToEngine, { t: 'run' }>;

/** The file name of the program's frames in a stack trace: the core tags staged operations and errors with positions in it. */
export const PROGRAM_FILE = 'program.js';

/** The engine's setup, as text: the determinism prelude, the core (a function body, so it names no global), the session. */
function setupSource(coreSource: string): string {
  return `(function (hostRead, hostResolve, surfaceJson, limitsJson, FIXED, SEED) {
  var J = JSON;
  var stringify = J.stringify, parse = J.parse;
  var D = Date;
  function FD() {
    if (!new.target) return new D(FIXED).toString();
    return arguments.length ? new D(...arguments) : new D(FIXED);
  }
  FD.prototype = D.prototype; FD.now = function () { return FIXED; }; FD.parse = D.parse; FD.UTC = D.UTC;
  Object.defineProperty(D.prototype, 'constructor', { value: FD, writable: true, configurable: true });
  globalThis.Date = FD;
  var s = SEED >>> 0;
  Math.random = function () {
    s = (s + 0x6D2B79F5) >>> 0; var t = s; t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  var Core = (function () {
${coreSource}
;return MaterioneCore;
  })();
  var port = {
    read: function (op, payload) { return hostRead(String(op), stringify(payload === undefined ? {} : payload)).then(function (r) { return parse(r); }); },
    resolve: function (request) { return hostResolve(stringify(request)).then(function (r) { return parse(r); }); },
  };
  var session = Core.createSession({ surface: parse(surfaceJson), port: port, limits: parse(limitsJson), programFile: '${PROGRAM_FILE}' });
  Object.defineProperty(globalThis, 'm', { value: session.m, writable: false, configurable: false, enumerable: false });
  return function (program) {
    return program
      .then(function (v) { return { returned: v }; }, function (e) { return { threw: e }; })
      .then(function (outcome) { return session.finish(outcome); })
      .then(function (result) { return stringify({ result: result, late: session.late() }); });
  };
})`;
}

export interface EngineInit {
  wasm: ArrayBuffer;
  coreSource: string;
  memoryMb: number;
}

/**
 * Starts the engine on `port`: instantiates QuickJS from the bytes the host transferred (the frame never fetches) over a
 * memory with a hard `maximum`, then serves one `run` at a time. The engine posts `ready` when it can.
 */
export async function startEngine(port: MessagePort, init: EngineInit): Promise<void> {
  const post = (message: EngineToHost): void => port.postMessage(message);
  const started = performance.now();
  let qjs: QuickJSWASMModule;
  try {
    // 256 initial pages (16 MiB); growth past `maximum` makes malloc return NULL and QuickJS throws "out of memory".
    const wasmMemory = new WebAssembly.Memory({ initial: 256, maximum: Math.max(256, Math.floor(init.memoryMb) * 16) });
    qjs = await newQuickJSWASMModuleFromVariant(newVariant(baseVariant, { wasmBinary: init.wasm, wasmMemory }));
  } catch (error) {
    post({ t: 'boot-error', message: String((error as Error)?.message ?? error) });
    return;
  }

  /** Answers of the host to the guest's calls, by `runId:rid`. */
  const waiters = new Map<string, (ok: boolean, json: string) => void>();
  let busy = false;

  port.onmessage = (event: MessageEvent) => {
    const message = event.data as HostToEngine;
    if (typeof message !== 'object' || message === null) return;
    if (message.t === 'run' && !busy) {
      busy = true;
      void run(qjs, message, init.coreSource, post, waiters).finally(() => {
        busy = false;
      });
    } else if (message.t === 'answer') {
      waiters.get(`${message.runId}:${message.rid}`)?.(message.ok === true, String(message.json));
    }
  };
  post({ t: 'ready', initMs: performance.now() - started });
}

async function run(
  qjs: QuickJSWASMModule,
  message: Run,
  coreSource: string,
  post: (message: EngineToHost) => void,
  waiters: Map<string, (ok: boolean, json: string) => void>,
): Promise<void> {
  const limits = message.limits;
  const wall0 = performance.now();
  const runtime = qjs.newRuntime();
  const ctx: QuickJSContext = runtime.newContext();
  let violation: Violation | undefined;
  let failure: string | undefined;
  const flag = (value: Violation, text?: string): void => {
    violation ??= value;
    if (text !== undefined) failure ??= text;
  };
  let poisoned = false;
  let reads = 0;
  let readBytes = 0;
  let nextRid = 0;
  let setupMs = 0;
  let resultJson: string | null = null;

  // CPU accounting: only time spent inside the engine counts; waiting for the host does not.
  let cpuUsed = 0;
  let sliceStart = performance.now();
  let inSlice = false;
  const begin = (): void => {
    sliceStart = performance.now();
    inSlice = true;
  };
  const end = (): void => {
    if (inSlice) cpuUsed += performance.now() - sliceStart;
    inSlice = false;
  };
  runtime.setMemoryLimit(limits.memoryBytes);
  runtime.setMaxStackSize(limits.stackBytes);
  runtime.setInterruptHandler(() => {
    if (cpuUsed + (performance.now() - sliceStart) > limits.cpuMs) {
      flag('cpu-deadline');
      return true;
    }
    return false;
  });

  const pending = new Set<number>();
  let wake: (() => void) | null = null;
  const handles: QuickJSHandle[] = [];
  const deferreds: QuickJSDeferredPromise[] = [];
  const keep = <T extends QuickJSHandle>(handle: T): T => {
    handles.push(handle);
    return handle;
  };

  /** A host function: a guest promise the host answers (a read, or the seal's resolve). */
  const hostCall = (name: string, kind: CallKind): QuickJSHandle =>
    ctx.newFunction(name, (...args: QuickJSHandle[]) => {
      const op = kind === 'read' ? ctx.getString(args[0]!) : 'resolve';
      const payload = ctx.getString(args[kind === 'read' ? 1 : 0]!);
      if (kind === 'read' && ++reads > limits.reads) {
        flag('read-limit', `more than ${limits.reads} reads`);
        return { error: ctx.newError('read limit') };
      }
      const rid = ++nextRid;
      const deferred = ctx.newPromise();
      deferreds.push(deferred);
      pending.add(rid);
      waiters.set(`${message.runId}:${rid}`, (ok, json) => {
        pending.delete(rid);
        waiters.delete(`${message.runId}:${rid}`);
        if (kind === 'read') {
          readBytes += json.length;
          if (readBytes > limits.readBytes) flag('read-limit', `more than ${limits.readBytes} bytes of reads`);
        }
        if (!deferred.alive) return;
        if (ok) deferred.resolve(ctx.newString(json));
        else deferred.reject(ctx.newError(json));
        wake?.();
      });
      post({ t: 'call', runId: message.runId, rid, kind, op, payload });
      return deferred.handle;
    });
  const nRead = hostCall('read', 'read');
  const nResolve = hostCall('resolve', 'resolve');

  const finish = (): void => {
    end();
    for (const deferred of deferreds) if (deferred.alive) deferred.dispose();
    for (const handle of handles) if (handle.alive) handle.dispose();
    for (const handle of [nRead, nResolve]) if (handle.alive) handle.dispose();
    // A program that retained memory in a global (or died mid-allocation) can make QuickJS's leak assertion abort the
    // module on dispose: the instance is then dead and the host discards the frame.
    try {
      ctx.dispose();
      runtime.dispose();
    } catch {
      poisoned = true;
    }
    for (const key of [...waiters.keys()]) if (key.startsWith(`${message.runId}:`)) waiters.delete(key);
    const out: EngineResult = { t: 'result', runId: message.runId, resultJson: violation === undefined ? resultJson : null, poisoned, cpuMs: cpuUsed, wallMs: performance.now() - wall0, setupMs };
    if (violation !== undefined) out.violation = violation;
    if (failure !== undefined) out.message = failure;
    post(out);
  };

  const errorText = (handle: QuickJSHandle): string => {
    const dumped = ctx.dump(handle) as { name?: string; message?: string } | string | null;
    const text = typeof dumped === 'object' && dumped !== null ? `${dumped.name ?? 'Error'}: ${dumped.message ?? ''}` : String(dumped);
    return text;
  };
  /** An engine-level failure (not the program's: the core catches those): classified by what QuickJS says. */
  const fail = (handle: QuickJSHandle): void => {
    const text = errorText(handle);
    handle.dispose();
    if (/interrupted/i.test(text)) flag('cpu-deadline', text);
    else if (/out of memory/i.test(text)) flag('memory-limit', text);
    else if (/stack overflow/i.test(text)) flag('stack-limit', text);
    else flag('engine-error', text);
    finish();
  };

  try {
    begin();
    const setup = ctx.evalCode(setupSource(coreSource), 'setup.js');
    if (setup.error) return fail(setup.error);
    const setupFn = keep(setup.value);
    const surface = keep(ctx.newString(message.surfaceJson));
    const coreLimits = keep(ctx.newString(message.coreLimitsJson));
    const fixed = keep(ctx.newNumber(message.fixedTime));
    const seed = keep(ctx.newNumber(message.seed));
    const made = ctx.callFunction(setupFn, ctx.undefined, nRead, nResolve, surface, coreLimits, fixed, seed);
    if (made.error) return fail(made.error);
    const driver = keep(made.value);
    const program = ctx.evalCode(message.js, PROGRAM_FILE);
    if (program.error) return fail(program.error);
    const programPromise = keep(program.value);
    const driven = ctx.callFunction(driver, ctx.undefined, programPromise);
    if (driven.error) return fail(driven.error);
    const final = keep(driven.value);
    setupMs = performance.now() - wall0;

    for (;;) {
      if (!inSlice) begin();
      const jobs = runtime.executePendingJobs();
      if (jobs.error) {
        const text = errorText(jobs.error);
        jobs.error.dispose();
        if (/interrupted/i.test(text)) flag('cpu-deadline', text);
        else if (/out of memory/i.test(text)) flag('memory-limit', text);
        else if (/stack overflow/i.test(text)) flag('stack-limit', text);
        else flag('engine-error', text);
      }
      end();
      if (violation !== undefined) return finish();
      const state = ctx.getPromiseState(final);
      if (state.type === 'fulfilled') {
        const value = keep(state.value);
        if (ctx.typeof(value) !== 'string') {
          flag('engine-error', 'the run ended without a result');
          return finish();
        }
        resultJson = ctx.getString(value);
        if (resultJson.length > limits.resultBytes) {
          flag('output-limit', `the result is larger than ${limits.resultBytes} bytes`);
        }
        return finish();
      }
      if (state.type === 'rejected') return fail(state.error);
      if (pending.size === 0) {
        flag('deadlock', 'the program is waiting on something that can never happen');
        return finish();
      }
      await new Promise<void>((resolve) => {
        wake = resolve;
      });
      wake = null;
    }
  } catch (error) {
    // An engine-level failure that is not QuickJS's own: report, never throw out of the engine. A native wasm stack
    // overflow (QuickJS's limit set too high for the browser's stack) leaves the module unusable: the host discards the frame.
    const text = String((error as Error)?.message ?? error);
    if (/Maximum call stack/i.test(text)) {
      flag('stack-limit', text);
      poisoned = true;
    } else flag('engine-error', text);
    finish();
  }
}
