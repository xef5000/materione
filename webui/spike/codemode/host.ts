// SPIKE (#340) - throwaway. The editor-side half: creates the sandboxed iframe, owns the channel,
// answers reads, maps lines back to TypeScript, kills a runaway frame.
import { DEFAULT_LIMITS, type Limits, type FrameToHost, type ResultMsg } from './protocol';

export interface Diag { line: number; col: number; code: number; message: string }

// ---- TypeScript worker client ---------------------------------------------------------------
export class TsClient {
  private w: Worker;
  private n = 0;
  private waiters = new Map<number, (v: any) => void>();
  constructor(url: string) {
    this.w = new Worker(url);
    this.w.onmessage = (e) => { this.waiters.get(e.data.id)?.(e.data); this.waiters.delete(e.data.id); };
  }
  private call(msg: object): Promise<any> {
    const id = ++this.n;
    return new Promise((res) => { this.waiters.set(id, res); this.w.postMessage({ id, ...msg }); });
  }
  ping() { return this.call({ t: 'ping' }); }
  transpile(body: string) { return this.call({ t: 'transpile', body }); }
  check(body: string, dts: string) { return this.call({ t: 'check', body, dts }); }
  terminate() { this.w.terminate(); }
}

// ---- source map: JS line -> TS line -----------------------------------------------------------
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function decodeMappings(mappings: string): number[][][] {
  const lines: number[][][] = [];
  let srcLine = 0, srcCol = 0;
  for (const ln of mappings.split(';')) {
    const segs: number[][] = [];
    let genCol = 0;
    for (const seg of ln.split(',')) {
      if (!seg) continue;
      const vals: number[] = [];
      let shift = 0, val = 0;
      for (const ch of seg) {
        const d = B64.indexOf(ch);
        val += (d & 31) << shift;
        if (d & 32) shift += 5;
        else { vals.push(val & 1 ? -(val >> 1) : val >> 1); val = 0; shift = 0; }
      }
      genCol += vals[0]!;
      if (vals.length >= 4) { srcLine += vals[2]!; srcCol += vals[3]!; segs.push([genCol, srcLine, srcCol]); }
    }
    lines.push(segs);
  }
  return lines;
}
export function mapToTs(map: { mappings: string }, jsLine: number | null, jsCol: number | null): number | null {
  if (jsLine === null) return null;
  const lines = decodeMappings(map.mappings);
  const segs = lines[jsLine - 1];
  if (!segs?.length) return null;
  let best = segs[0]!;
  if (jsCol !== null) for (const s of segs) if (s[0]! <= jsCol - 1) best = s;
  return best[1]! + 1; // wrapper keeps the body's first line on TS line 1
}

// ---- the sandbox ------------------------------------------------------------------------------
export interface RunResult {
  ok: boolean;
  violation?: string;
  error?: { name: string; message: string; tsLine: number | null; jsLine: number | null };
  staged: { op: unknown; tsLine: number | null }[];
  logs: string[];
  returnValue?: unknown;
  metrics: { cpuMs: number; wallMs: number; setupMs: number; hostRoundTripMs: number; transpileMs: number };
  killed?: boolean;
  poisoned?: boolean;
}

export interface SandboxOpts {
  frameUrl: string;
  wasmUrl: string;
  /** "frame": engine on the iframe's main thread. "worker": engine in a blob: Worker spawned by the iframe. */
  mode?: 'frame' | 'worker';
  workerUrl?: string;
  /** hard cap of the engine's WebAssembly.Memory, in MiB (default 64) */
  memMaxMb?: number;
  /** per-read artificial latency in the fake host (ms) */
  readDelayMs?: number;
}

const READ_OPS = new Set(['registry.describe', 'item.get', 'echo']);
const MAX_ARGS = 64 * 1024;

export class Sandbox {
  frame!: HTMLIFrameElement;
  port!: MessagePort;
  coldStart = { frameLoadMs: 0, wasmFetchMs: 0, engineInitMs: 0, totalMs: 0 };
  private runId = 0;
  private active: { runId: number; done: (r: ResultMsg) => void } | null = null;
  private static wasmCache: Promise<ArrayBuffer> | null = null;
  private static workerSrc: Promise<string> | null = null;
  hostReads = 0;

  constructor(private opts: SandboxOpts, private ts: TsClient) {}

  static async create(opts: SandboxOpts, ts: TsClient) {
    const s = new Sandbox(opts, ts);
    await s.boot();
    return s;
  }

  private async boot() {
    const t0 = performance.now();
    this.frame = document.createElement('iframe');
    // The whole boundary: scripts only, and NO allow-same-origin => opaque origin.
    this.frame.setAttribute('sandbox', 'allow-scripts');
    this.frame.style.display = 'none';
    this.frame.src = this.opts.frameUrl;
    const loaded = new Promise<void>((r) => this.frame.addEventListener('load', () => r(), { once: true }));
    document.body.appendChild(this.frame);
    await loaded;
    const t1 = performance.now();
    Sandbox.wasmCache ??= fetch(this.opts.wasmUrl).then((r) => r.arrayBuffer());
    const bytes = await Sandbox.wasmCache;
    let workerSrc: string | undefined;
    if (this.opts.mode === 'worker') {
      Sandbox.workerSrc ??= fetch(this.opts.workerUrl ?? '/engine-worker.js').then((r) => r.text());
      workerSrc = await Sandbox.workerSrc;
    }
    const t2 = performance.now();
    const ch = new MessageChannel();
    this.port = ch.port1;
    const ready = new Promise<number>((res, rej) => {
      this.port.onmessage = (e) => {
        const m = e.data as FrameToHost | { t: 'boot-error'; message: string };
        if (m.t === 'ready') res(m.initMs);
        else if (m.t === 'boot-error') rej(new Error(m.message));
        else this.onFrame(m);
      };
    });
    const copy = bytes.slice(0); // the frame gets its own copy; ours stays cached
    // targetOrigin must be '*': an opaque origin cannot be named. The frame checks e.source instead,
    // and everything after this message travels on the private port.
    this.frame.contentWindow!.postMessage({ t: 'init', wasm: copy, workerSrc, memMaxMb: this.opts.memMaxMb ?? 64 }, '*', [ch.port2, copy]);
    const initMs = await ready;
    this.coldStart = { frameLoadMs: t1 - t0, wasmFetchMs: t2 - t1, engineInitMs: initMs, totalMs: performance.now() - t0 };
  }

  private onFrame(m: FrameToHost) {
    if (m.t === 'read') {
      void this.answerRead(m);
    } else if (m.t === 'result') {
      if (this.active && this.active.runId === m.runId) this.active.done(m);
    }
  }

  private async answerRead(m: Extract<FrameToHost, { t: 'read' }>) {
    // Shape, allowlist and size validation: the host decides what to call; the frame only asks.
    const reply = (ok: boolean, json: string) => this.port.postMessage({ t: 'readResult', runId: m.runId, rid: m.rid, ok, json });
    if (typeof m.op !== 'string' || !READ_OPS.has(m.op)) return reply(false, `unknown read op`);
    if (typeof m.args !== 'string' || m.args.length > MAX_ARGS) return reply(false, `args too large`);
    this.hostReads++;
    if (this.opts.readDelayMs) await new Promise((r) => setTimeout(r, this.opts.readDelayMs));
    const args = JSON.parse(m.args);
    if (m.op === 'registry.describe') return reply(true, JSON.stringify({ types: ['item', 'block', 'menu'] }));
    if (m.op === 'item.get') return reply(true, JSON.stringify({ id: args?.id ?? null, name: 'Bloodfang' }));
    return reply(true, JSON.stringify(args)); // echo
  }

  async runTs(body: string, limits: Partial<Limits> = {}, wallBudgetMs = 5000): Promise<RunResult> {
    const t0 = performance.now();
    const tr = await this.ts.transpile(body);
    const transpileMs = performance.now() - t0;
    if (tr.syntax?.length) {
      return { ok: false, staged: [], logs: [], error: { name: 'SyntaxError', message: tr.syntax[0].message, tsLine: tr.syntax[0].line, jsLine: null }, metrics: { cpuMs: 0, wallMs: 0, setupMs: 0, hostRoundTripMs: 0, transpileMs } };
    }
    const r = await this.runJs(tr.js, limits, wallBudgetMs);
    const map = tr.map;
    r.metrics.transpileMs = transpileMs;
    r.staged = r.staged.map((s) => ({ ...s, tsLine: mapToTs(map, s.tsLine, null) }));
    if (r.error) r.error.tsLine = mapToTs(map, r.error.jsLine, null);
    return r;
  }

  runJs(js: string, limits: Partial<Limits> = {}, wallBudgetMs = 5000): Promise<RunResult> {
    const runId = ++this.runId;
    const lim: Limits = { ...DEFAULT_LIMITS, ...limits };
    const start = performance.now();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        // Backstop: the engine never answered. Destroy the frame; the editor is untouched.
        this.destroy();
        resolve({ ok: false, violation: 'wall-timeout', killed: true, staged: [], logs: [], metrics: { cpuMs: -1, wallMs: performance.now() - start, setupMs: 0, hostRoundTripMs: 0, transpileMs: 0 } });
      }, wallBudgetMs);
      this.active = {
        runId,
        done: (m) => {
          clearTimeout(timer);
          this.active = null;
          if (m.poisoned) this.destroy();
          const rt = performance.now() - start - m.wallMs;
          resolve({
            ok: m.ok,
            violation: m.violation,
            error: m.error ? { name: m.error.name, message: m.error.message, tsLine: m.error.jsLine, jsLine: m.error.jsLine } : undefined,
            // staged[].tsLine holds the JS line until runTs maps it
            staged: m.staged.map((s) => ({ op: JSON.parse(s.json), tsLine: s.jsLine })),
            logs: m.logs,
            poisoned: !!m.poisoned,
            returnValue: m.returnJson === null ? undefined : JSON.parse(m.returnJson),
            metrics: { cpuMs: m.cpuMs, wallMs: m.wallMs, setupMs: m.setupMs, hostRoundTripMs: rt, transpileMs: 0 },
          });
        },
      };
      this.port.postMessage({ t: 'run', runId, js, limits: lim, fixedTime: Date.UTC(2026, 0, 1), seed: 12345 });
    });
  }

  destroy() {
    this.port?.close();
    this.frame?.remove();
  }
}
