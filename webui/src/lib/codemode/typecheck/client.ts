/**
 * The type-check client (codemode.md section 5.1): `check(program, surface)` for the chat tool (issue 348), the sandbox
 * driver (issue 343) and the review flow (issue 347).
 *
 * The compiler is a static asset in a module Worker of the editor's own origin, spawned on the FIRST check and never
 * before (a program of the editor that never asks costs nothing), so this file is small and importing it is free.
 * Bounded: an oversized program is refused before it is posted; a check that passes its wall-clock deadline has its
 * Worker terminated (the editor's main thread is never involved, so it stays responsive) and the next check boots a
 * fresh one. One check runs at a time; later calls wait their turn.
 */
import { generateSdk } from '../generate/generate';
import type { SurfaceDocument } from '../generate/surface';
import {
  DEFAULT_CHECK_LIMITS,
  type CheckDiagnostic,
  type CheckCode,
  type CheckInput,
  type CheckLimits,
  type CheckResult,
  type FromWorker,
  type ToWorker,
} from './types';

/** What the client needs of a Worker: the browser's own, or a stand-in in a test. */
export interface WorkerLike {
  postMessage(message: ToWorker): void;
  terminate(): void;
  onmessage: ((event: { data: FromWorker }) => void) | null;
  onerror: ((event: { message?: string }) => void) | null;
}

export interface ClientOptions {
  limits?: Partial<CheckLimits>;
  /** Starts a Worker. The default is the bundled module Worker of this editor; a test passes its own. */
  spawn?: () => WorkerLike;
}

export interface ClientStats {
  /** Workers started so far (1 after the first check; 2 after a deadline). */
  spawned: number;
  /** Milliseconds from spawn to the Worker's `ready` (compiler downloaded and parsed), of the last boot. */
  lastBootMs: number | undefined;
  /** Milliseconds the compiler spent on the last check, as the Worker measured. */
  lastCheckMs: number | undefined;
}

export interface TypecheckClient {
  /** Checks one program against a surface (or against declarations already generated from one). */
  check(program: string, surface: SurfaceDocument | string): Promise<CheckResult>;
  /** Terminates the Worker. A later `check` boots a new one. */
  dispose(): void;
  readonly stats: Readonly<ClientStats>;
}

function defaultSpawn(): WorkerLike {
  // Vite emits this as a separate asset (assets/worker-*.js) next to the SPA: same origin, no header, no endpoint.
  return new Worker(new URL('./worker.ts', import.meta.url), { type: 'module', name: 'materione-typecheck' }) as unknown as WorkerLike;
}

const generated = new WeakMap<SurfaceDocument, string>();

/** The generated declarations for a surface, computed once per surface object. */
function declarationsOf(surface: SurfaceDocument | string): string {
  if (typeof surface === 'string') return surface;
  let text = generated.get(surface);
  if (text === undefined) {
    text = generateSdk(surface);
    generated.set(surface, text);
  }
  return text;
}

function failed(code: CheckCode, message: string, hint: string): CheckResult {
  const diagnostic: CheckDiagnostic = { code, severity: 'error', message, line: 0, column: 0, hint };
  return { ok: false, diagnostics: [diagnostic], truncated: false };
}

export function createTypecheckClient(options: ClientOptions = {}): TypecheckClient {
  const limits: CheckLimits = { ...DEFAULT_CHECK_LIMITS, ...options.limits };
  const spawn = options.spawn ?? defaultSpawn;
  const stats: ClientStats = { spawned: 0, lastBootMs: undefined, lastCheckMs: undefined };

  let worker: WorkerLike | undefined;
  let ready: Promise<void> | undefined;
  let nextId = 1;
  let queue: Promise<unknown> = Promise.resolve();
  // What the current Worker is being waited on for; settled by its messages, its error, or the deadline.
  let pending: ((message: FromWorker | { type: 'dead'; message: string }) => void) | undefined;

  const drop = (): void => {
    const gone = worker;
    worker = undefined;
    ready = undefined;
    if (gone !== undefined) {
      gone.onmessage = null;
      gone.onerror = null;
      gone.terminate();
    }
  };

  function ensureWorker(): Promise<void> {
    if (ready !== undefined) return ready;
    const started = performance.now();
    stats.spawned++;
    let created: WorkerLike;
    try {
      created = spawn();
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new Error(String(error)));
    }
    worker = created;
    ready = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        if (worker === created) drop();
        reject(new Error(`the checker did not start within ${limits.bootMs} ms`));
      }, limits.bootMs);
      created.onmessage = (event): void => {
        const message = event.data;
        if (message.type === 'ready') {
          clearTimeout(timer);
          stats.lastBootMs = performance.now() - started;
          resolve();
        } else {
          pending?.(message);
        }
      };
      created.onerror = (event): void => {
        clearTimeout(timer);
        const reason = event.message ?? 'the Worker failed';
        if (worker === created) drop();
        pending?.({ type: 'dead', message: reason });
        reject(new Error(reason));
      };
    });
    // a rejection here is reported by the check that awaits `ready`; an unobserved one must not surface as unhandled
    ready.catch(() => undefined);
    return ready;
  }

  async function run(input: CheckInput): Promise<CheckResult> {
    if (input.program.length > limits.maxProgramChars) {
      return failed('CHK-0002', `The program is ${input.program.length} characters; the checker takes at most ${limits.maxProgramChars}.`, 'Split the work into more than one program: each is one transaction in one pack.');
    }
    if (input.sdk.length > limits.maxSdkChars) {
      return failed('CHK-0002', `The generated declarations are ${input.sdk.length} characters; the checker takes at most ${limits.maxSdkChars}.`, 'This is a fault of the connected surface, not of the program.');
    }
    try {
      await ensureWorker();
    } catch (error) {
      return failed('CHK-0004', `The checker could not start: ${error instanceof Error ? error.message : String(error)}`, 'Reload the editor; if it persists the checker asset is missing from this host.');
    }
    const active = worker;
    if (active === undefined) return failed('CHK-0004', 'The checker stopped before the check began.', 'Try again.');
    const id = nextId++;
    return new Promise<CheckResult>((resolve) => {
      const timer = setTimeout(() => {
        pending = undefined;
        // The only defence against a check that does not end: the Worker dies, the editor never waited on it, the next check boots a new one.
        if (worker === active) drop();
        resolve(failed('CHK-0003', `The check did not finish within ${limits.checkMs} ms and was cut off.`, 'Simplify the program: avoid deeply nested or recursive types and very large literals.'));
      }, limits.checkMs);
      pending = (message): void => {
        if (message.type === 'dead') {
          clearTimeout(timer);
          pending = undefined;
          resolve(failed('CHK-0004', `The checker stopped: ${message.message}`, 'Try again; a fresh checker starts on the next check.'));
        } else if (message.type === 'result' && message.id === id) {
          clearTimeout(timer);
          pending = undefined;
          stats.lastCheckMs = message.checkMs;
          resolve(message.result);
        } else if (message.type === 'failed' && message.id === id) {
          clearTimeout(timer);
          pending = undefined;
          resolve(failed('CHK-0004', `The checker failed: ${message.message}`, 'Try again.'));
        }
      };
      active.postMessage({ type: 'check', id, program: input.program, sdk: input.sdk });
    });
  }

  return {
    check(program, surface): Promise<CheckResult> {
      const input: CheckInput = { program, sdk: declarationsOf(surface) };
      const turn = queue.then(() => run(input));
      queue = turn.catch(() => undefined);
      return turn;
    },
    dispose(): void {
      drop();
    },
    stats,
  };
}

let shared: TypecheckClient | undefined;

/** The editor's one client: `await check(program, surface)`. The Worker starts on the first call. */
export function check(program: string, surface: SurfaceDocument | string): Promise<CheckResult> {
  shared ??= createTypecheckClient();
  return shared.check(program, surface);
}
