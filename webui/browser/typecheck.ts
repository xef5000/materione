// A bare page for the type-check Worker's Playwright specs (codemode.md section 5.1): it exposes the client the later issues
// will call, and nothing else. The editor is not mounted, so what a spec measures is the Worker alone. The same file is the
// entry of the production-build spec (`codemode-typecheck-built.pw.ts`).
import { check, createTypecheckClient, type CheckLimits, type CheckResult, type TypecheckClient } from '../src/lib/codemode/typecheck';

export interface TypecheckProbe {
  /** The editor's shared client, the one the chat tool will use. */
  check(program: string, sdk: string): Promise<CheckResult>;
  /** A client of its own (its own Worker and limits); returns its handle. */
  fresh(limits: Partial<CheckLimits>): number;
  checkWith(handle: number, program: string, sdk: string): Promise<CheckResult>;
  /** `checkWith`, with the longest stall of this page's main thread while it ran (a 10 ms timer's worst gap). */
  checkWithLag(handle: number, program: string, sdk: string): Promise<{ result: CheckResult; maxGapMs: number; ms: number }>;
  stats(handle: number): { spawned: number; lastBootMs: number | undefined; lastCheckMs: number | undefined };
}

declare global {
  interface Window {
    __tc?: TypecheckProbe;
  }
}

const clients: TypecheckClient[] = [];

window.__tc = {
  check: (program, sdk) => check(program, sdk),
  fresh(limits) {
    clients.push(createTypecheckClient({ limits }));
    return clients.length - 1;
  },
  checkWith: (handle, program, sdk) => clients[handle]!.check(program, sdk),
  async checkWithLag(handle, program, sdk) {
    let last = performance.now();
    let maxGapMs = 0;
    const timer = setInterval(() => {
      const now = performance.now();
      maxGapMs = Math.max(maxGapMs, now - last);
      last = now;
    }, 10);
    const started = performance.now();
    const result = await clients[handle]!.check(program, sdk);
    const ms = performance.now() - started;
    clearInterval(timer);
    return { result, maxGapMs, ms };
  },
  stats: (handle) => ({ ...clients[handle]!.stats }),
};
document.documentElement.setAttribute('data-typecheck-ready', 'true');
