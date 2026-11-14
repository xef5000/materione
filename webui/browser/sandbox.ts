// A bare page for the code-mode sandbox's Playwright specs (codemode.md section 4.6): the editor is not mounted. It plants the
// canaries a program that escaped the sandbox would want (a global secret, storage, a cookie, an IndexedDB database, a
// BroadcastChannel it listens on), exposes the real pipeline (type-check Worker, then the sandbox) and a probe launcher. The same
// file is the entry of the production-build spec (`codemode-sandbox-built.pw.ts`).
import { fakePort, type World } from '../test/helpers/world';
import { check } from '../src/lib/codemode/typecheck';
import { createOpHost, createSandboxRunner, type RunResult, type SandboxLimits, type SandboxRunner } from '../src/lib/codemode/sandbox';
import type { SurfaceDocument } from '../src/lib/codemode/generate/surface';
import type { CheckResult } from '../src/lib/codemode/typecheck';

export interface SandboxProbe {
  /** Checks, then runs: the whole pipeline of the chat tool, against a recorded world answered by the op host. */
  checkAndRun(program: string, surface: SurfaceDocument, world: World, limits?: Partial<SandboxLimits>, host?: 'world' | 'silent'): Promise<{ check: CheckResult; result: RunResult | null; reads: string[] }>;
  /** A runner of its own (so a spec can count boots). */
  fresh(): number;
  runWith(handle: number, program: string, surface: SurfaceDocument, world: World, limits?: Partial<SandboxLimits>): Promise<RunResult>;
  stats(handle: number): { booted: number; discarded: number };
  /** Runs `runWith` while measuring the longest stall of this page's main thread. */
  runWithLag(handle: number, program: string, surface: SurfaceDocument, world: World, limits?: Partial<SandboxLimits>): Promise<{ result: RunResult; maxGapMs: number }>;
  /** Loads a probe document in a `sandbox="allow-scripts"` iframe and resolves with what it reports. */
  probe(url: string): Promise<Record<string, string>>;
  broadcasts: string[];
  frames(): number;
}

declare global {
  interface Window {
    __sbx?: SandboxProbe;
    __secret?: string;
  }
}

window.__secret = 'PAGE-SECRET';
try {
  localStorage.setItem('canary', 'LOCAL-SECRET');
  document.cookie = 'canary=COOKIE-SECRET; path=/';
  indexedDB.open('canary-db');
} catch {
  /* canaries are best-effort */
}
const broadcasts: string[] = [];
const channel = new BroadcastChannel('canary');
channel.onmessage = (event) => broadcasts.push(String(event.data));

const runners: SandboxRunner[] = [];
const shared = createSandboxRunner();

function hostOf(world: World, reads: string[], silent: boolean) {
  const port = fakePort(world);
  const inner = createOpHost(async (op, payload) => {
    reads.push(op);
    if (silent) return new Promise(() => {});
    return port.read(op, JSON.parse(JSON.stringify(payload)) as Record<string, unknown>);
  });
  return { read: inner.read, resolve: inner.resolve };
}

window.__sbx = {
  async checkAndRun(program, surface, world, limits, host = 'world') {
    const checkResult = await check(program, surface);
    const reads: string[] = [];
    if (!checkResult.ok || checkResult.js === undefined || checkResult.map === undefined) return { check: checkResult, result: null, reads };
    const result = await shared.run({ js: checkResult.js, map: checkResult.map }, surface, hostOf(world, reads, host === 'silent'), limits);
    return { check: checkResult, result, reads };
  },
  fresh() {
    runners.push(createSandboxRunner());
    return runners.length - 1;
  },
  async runWith(handle, program, surface, world, limits) {
    const checkResult = await check(program, surface);
    if (checkResult.js === undefined || checkResult.map === undefined) throw new Error(`does not check: ${JSON.stringify(checkResult.diagnostics)}`);
    return runners[handle]!.run({ js: checkResult.js, map: checkResult.map }, surface, hostOf(world, [], false), limits);
  },
  stats: (handle) => ({ ...runners[handle]!.stats }),
  async runWithLag(handle, program, surface, world, limits) {
    const checkResult = await check(program, surface);
    if (checkResult.js === undefined || checkResult.map === undefined) throw new Error('does not check');
    let last = performance.now();
    let maxGapMs = 0;
    const timer = setInterval(() => {
      const now = performance.now();
      maxGapMs = Math.max(maxGapMs, now - last);
      last = now;
    }, 10);
    const result = await runners[handle]!.run({ js: checkResult.js, map: checkResult.map }, surface, hostOf(world, [], false), limits);
    clearInterval(timer);
    return { result, maxGapMs };
  },
  probe(url) {
    return new Promise((resolve) => {
      const listener = (event: MessageEvent) => {
        if ((event.data as { t?: string } | null)?.t === 'probe-result') {
          window.removeEventListener('message', listener);
          resolve((event.data as { out: Record<string, string> }).out);
        }
      };
      window.addEventListener('message', listener);
      const frame = document.createElement('iframe');
      frame.setAttribute('sandbox', 'allow-scripts');
      frame.src = url;
      document.body.appendChild(frame);
    });
  },
  broadcasts,
  frames: () => document.querySelectorAll('iframe').length,
};
document.documentElement.setAttribute('data-sandbox-ready', 'true');
