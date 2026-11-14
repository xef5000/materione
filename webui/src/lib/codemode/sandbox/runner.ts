/**
 * The sandbox's public face (codemode.md section 4.6): `run(checkedProgram, surface, host, limits?)`.
 *
 * A runner owns at most one live sandbox and boots it on the FIRST run (the assets are not fetched before). It runs one
 * program at a time (later calls wait their turn), reuses the sandbox between runs (each run is a fresh QuickJS runtime inside
 * it), and discards it when a run ended badly (a limit, a message the host refused, an engine that aborted): the next run boots a
 * fresh one. A run never rejects and never throws into the editor: every way it can end is a `RunResult` whose diagnostics say
 * which, with no operations when it failed.
 */
import type { JsonValue, ProgramResult, SdkDiagnostic, SourcePosition, StagedOperation } from '../core/types';
import type { SurfaceDocument } from '../generate/surface';
import type { RawSourceMap } from '../typecheck/types';
import { wrapProgram } from '../typecheck/wrap';
import { loadSandboxAssets } from './assets';
import type { SandboxHost } from './ophost';
import {
  coreLimitsOf,
  DEFAULT_SANDBOX_LIMITS,
  FIXED_TIME,
  SANDBOX_CODES,
  SEED,
  VIOLATION_CODE,
  type SandboxCode,
  type SandboxLimits,
  type Violation,
} from './protocol';
import { iframeSpawn, Sandbox, type RunEnd, type SandboxAssets, type Spawn } from './sandbox';
import { createPositionMapper } from './sourcemap';

/** What the type-check Worker hands back for a program that passed: its JavaScript and the map of it to the program as written. */
export interface CheckedProgram {
  js: string;
  map: Pick<RawSourceMap, 'mappings'>;
}

/** What the host measured and the engine reported; never part of a determinism comparison (timings differ run to run). */
export interface RunMetrics {
  /** Engine CPU time, wall time inside the engine, and the time to set the guest up (core evaluated, program compiled). */
  cpuMs: number;
  engineWallMs: number;
  setupMs: number;
  /** Wall time of the whole run as the host saw it, boot excluded. */
  totalMs: number;
  /** What the host itself counted of the guest's reads. */
  hostReads: number;
  hostReadBytes: number;
  /** A sandbox was booted for this run (the first, or after a discarded one), and how long that took. */
  booted: boolean;
  bootMs: number;
  /** What ended the run before the program did, if anything. */
  violation?: Violation;
}

/** The result of a run (codemode.md section 5.2): the core's `ProgramResult`, sources mapped to the program as written. */
export interface RunResult {
  /** False when any diagnostic is an error: the operation list is then empty. */
  ok: boolean;
  sdkVersion: string;
  pack: string | null;
  /** The list for `authoring.transaction`, untouched. */
  operations: StagedOperation[];
  /** `sources[i]` is the line and column of the program as written that staged `operations[i]`. */
  sources: (SourcePosition | null)[];
  /** SDK and sandbox diagnostics, positions in the program as written. */
  diagnostics: SdkDiagnostic[];
  /** Diagnostics raised after the program returned (a continuation that kept a handle). */
  late: SdkDiagnostic[];
  summary: string | null;
  logs: string[];
  /** What the program returned. */
  value: JsonValue | null;
  stats: ProgramResult['stats'];
  metrics: RunMetrics;
}

export interface RunnerOptions {
  /** Starts a sandbox. The default is the sandboxed iframe. */
  spawn?: Spawn;
  /** Loads the static files. The default fetches them from beside the page. */
  loadAssets?: () => Promise<SandboxAssets>;
}

export interface SandboxRunner {
  run(program: CheckedProgram, surface: SurfaceDocument, host: SandboxHost, limits?: Partial<SandboxLimits>): Promise<RunResult>;
  /** Destroys the live sandbox. A later run boots a new one. */
  dispose(): void;
  readonly stats: Readonly<{ booted: number; discarded: number }>;
}

const surfaceText = new WeakMap<SurfaceDocument, string>();
const MAX_PROGRAM_JS = 512 * 1024;

export function sandboxDiagnostic(code: SandboxCode, message: string, hint?: string): SdkDiagnostic {
  const diagnostic: SdkDiagnostic = { code, severity: 'error', message };
  if (hint !== undefined) diagnostic.hint = hint;
  return diagnostic;
}

const HINTS: Record<Violation, string> = {
  'cpu-deadline': 'a loop or recursion ran too long: stage the work directly instead of computing it, and avoid unbounded loops',
  'memory-limit': 'the program built something too large: work on a page of the data at a time',
  'stack-limit': 'recursion went too deep: use a loop',
  'read-limit': 'read a list once and filter it in the program',
  'read-deadline': 'the editor did not answer a read in time: try again, and read less',
  'wall-timeout': 'the program did not finish: simplify it',
  'stage-limit': 'stage fewer operations or smaller values, and split the work into more than one program',
  'output-limit': 'log and return summaries (counts and ids), not documents',
  deadlock: 'something the program awaits never settles: await only the SDK reads',
  protocol: 'the sandbox was discarded; run the program again',
  'boot-failed': 'reload the editor; if it persists the host is not serving the sandbox files',
  'engine-error': 'the program could not be run; run it again',
};

function failed(violation: Violation, message: string, end?: Partial<RunEnd>, booted = false, bootMs = 0, totalMs = 0): RunResult {
  const code = VIOLATION_CODE[violation];
  return {
    ok: false,
    sdkVersion: '',
    pack: null,
    operations: [],
    sources: [],
    diagnostics: [sandboxDiagnostic(code, message || SANDBOX_CODES[code], HINTS[violation])],
    late: [],
    summary: null,
    logs: [],
    value: null,
    stats: { reads: end?.hostReads ?? 0, readBytes: end?.hostReadBytes ?? 0, staged: 0, documents: 0 },
    metrics: { cpuMs: end?.cpuMs ?? 0, engineWallMs: end?.wallMs ?? 0, setupMs: end?.setupMs ?? 0, totalMs, hostReads: end?.hostReads ?? 0, hostReadBytes: end?.hostReadBytes ?? 0, booted, bootMs, violation },
  };
}

type Dict = Record<string, unknown>;
const isDict = (value: unknown): value is Dict => typeof value === 'object' && value !== null && !Array.isArray(value);
const own = (value: Dict, key: string): unknown => (Object.hasOwn(value, key) ? value[key] : undefined);
const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0;
const jsonBytes = (value: unknown): number => JSON.stringify(value)?.length ?? 0;

const MAX_DIAGNOSTICS = 1000;
const MAX_TEXT = 8192;

/** A diagnostic from the frame, rebuilt field by field (nothing the guest wrote is passed through whole), or undefined. */
function cleanDiagnostic(value: unknown, mapPosition: (position: SourcePosition) => SourcePosition): SdkDiagnostic | undefined {
  if (!isDict(value)) return undefined;
  const code = own(value, 'code');
  const severity = own(value, 'severity');
  const message = own(value, 'message');
  if (typeof code !== 'string' || (severity !== 'error' && severity !== 'warning') || typeof message !== 'string') return undefined;
  const diagnostic: SdkDiagnostic = { code: code.slice(0, 32), severity, message: message.slice(0, MAX_TEXT) };
  const hint = own(value, 'hint');
  if (typeof hint === 'string') diagnostic.hint = hint.slice(0, MAX_TEXT);
  const serverCode = own(value, 'serverCode');
  if (typeof serverCode === 'string') diagnostic.serverCode = serverCode.slice(0, 64);
  const source = own(value, 'source');
  if (isDict(source) && isCount(own(source, 'line')) && isCount(own(source, 'column'))) {
    diagnostic.source = mapPosition({ line: own(source, 'line') as number, column: own(source, 'column') as number });
  }
  return diagnostic;
}

/**
 * The guest's `{ result, late }` as a `RunResult`, validated by shape and by every size the host bounds, or a violation. The
 * guest is untrusted: the host recounts operations, bytes, logs and the return value itself, and rebuilds each record.
 */
function accept(resultJson: string, limits: SandboxLimits, mapPosition: (position: SourcePosition) => SourcePosition): { result: Omit<RunResult, 'metrics'> } | { violation: Violation; message: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(resultJson);
  } catch {
    return { violation: 'protocol', message: 'the sandbox returned a result that is not JSON' };
  }
  if (!isDict(parsed) || !isDict(own(parsed, 'result')) || !Array.isArray(own(parsed, 'late'))) return { violation: 'protocol', message: 'the sandbox returned a result of the wrong shape' };
  const raw = own(parsed, 'result') as Dict;
  const ok = own(raw, 'ok');
  const operations = own(raw, 'operations');
  const sources = own(raw, 'sources');
  const diagnostics = own(raw, 'diagnostics');
  const logs = own(raw, 'logs');
  const stats = own(raw, 'stats');
  const summary = own(raw, 'summary');
  const pack = own(raw, 'pack');
  const sdkVersion = own(raw, 'sdkVersion');
  if (typeof ok !== 'boolean' || !Array.isArray(operations) || !Array.isArray(sources) || !Array.isArray(diagnostics) || !Array.isArray(logs) || !isDict(stats)) {
    return { violation: 'protocol', message: 'the sandbox returned a result of the wrong shape' };
  }
  if (operations.length > limits.operations) return { violation: 'stage-limit', message: `the program staged ${operations.length} operations; a run may stage ${limits.operations}` };
  if (operations.some((operation) => !isDict(operation) || typeof own(operation, 'kind') !== 'string') || sources.length !== operations.length) {
    return { violation: 'protocol', message: 'the sandbox returned operations of the wrong shape' };
  }
  if (jsonBytes(operations) > limits.stagedBytes) return { violation: 'stage-limit', message: `the staged operations are larger than ${limits.stagedBytes} bytes` };
  const documents = own(stats, 'documents');
  const reads = own(stats, 'reads');
  const readBytes = own(stats, 'readBytes');
  const staged = own(stats, 'staged');
  if (![documents, reads, readBytes, staged].every(isCount)) return { violation: 'protocol', message: 'the sandbox returned stats of the wrong shape' };
  if ((documents as number) > limits.documents) return { violation: 'stage-limit', message: `the program touched ${documents as number} documents; a run may touch ${limits.documents}` };
  if (!logs.every((line) => typeof line === 'string')) return { violation: 'protocol', message: 'the sandbox returned logs of the wrong shape' };
  if (jsonBytes(logs) > limits.logBytes + 2 * logs.length + 2) return { violation: 'output-limit', message: `the logs are larger than ${limits.logBytes} bytes` };
  const returned = Object.hasOwn(raw, 'returned') ? raw.returned : null;
  if (jsonBytes(returned) > limits.returnBytes) return { violation: 'output-limit', message: `the return value is larger than ${limits.returnBytes} bytes` };
  if (diagnostics.length > MAX_DIAGNOSTICS) return { violation: 'output-limit', message: 'the program raised too many diagnostics' };
  const cleaned = (list: unknown[]): SdkDiagnostic[] | undefined => {
    const out: SdkDiagnostic[] = [];
    for (const entry of list) {
      const diagnostic = cleanDiagnostic(entry, mapPosition);
      if (diagnostic === undefined) return undefined;
      out.push(diagnostic);
    }
    return out;
  };
  const diagnosticList = cleaned(diagnostics);
  const lateList = cleaned(own(parsed, 'late') as unknown[]);
  if (diagnosticList === undefined || lateList === undefined) return { violation: 'protocol', message: 'the sandbox returned diagnostics of the wrong shape' };
  const sourceList: (SourcePosition | null)[] = [];
  for (const entry of sources) {
    if (entry === null) sourceList.push(null);
    else if (isDict(entry) && isCount(own(entry, 'line')) && isCount(own(entry, 'column'))) sourceList.push(mapPosition({ line: own(entry, 'line') as number, column: own(entry, 'column') as number }));
    else return { violation: 'protocol', message: 'the sandbox returned a source position of the wrong shape' };
  }
  // a run with an error has no operations, whatever the guest says
  // QuickJS throws "out of memory" INTO the program, and the core reports an uncaught throw as SDK-0001: the host names it
  if (diagnosticList.some((entry) => entry.code === 'SDK-0001' && /out of memory/i.test(entry.message))) return { violation: 'memory-limit', message: 'the program used more memory than a run may' };
  const hasError = diagnosticList.some((entry) => entry.severity === 'error');
  const success = ok && !hasError;
  return {
    result: {
      ok: success,
      sdkVersion: typeof sdkVersion === 'string' ? sdkVersion.slice(0, 32) : '',
      pack: typeof pack === 'string' ? pack : null,
      operations: success ? (operations as StagedOperation[]) : [],
      sources: success ? sourceList : [],
      diagnostics: diagnosticList,
      late: lateList,
      summary: typeof summary === 'string' ? summary.slice(0, 2048) : null,
      logs: logs as string[],
      value: success ? (returned as JsonValue | null) : null,
      stats: { reads: reads as number, readBytes: readBytes as number, staged: staged as number, documents: documents as number },
    },
  };
}

export function createSandboxRunner(options: RunnerOptions = {}): SandboxRunner {
  const spawn = options.spawn ?? iframeSpawn;
  const loadAssets = options.loadAssets ?? (() => loadSandboxAssets());
  let sandbox: Promise<Sandbox> | undefined;
  let queue: Promise<unknown> = Promise.resolve();
  const stats = { booted: 0, discarded: 0 };

  async function execute(program: CheckedProgram, surface: SurfaceDocument, host: SandboxHost, overrides: Partial<SandboxLimits>): Promise<RunResult> {
    const limits: SandboxLimits = { ...DEFAULT_SANDBOX_LIMITS, ...overrides };
    if (typeof program?.js !== 'string' || typeof program.map?.mappings !== 'string' || program.js.length > MAX_PROGRAM_JS) {
      return failed('engine-error', 'the program is not a checked program: it needs the JavaScript and the source map the type-check Worker returns');
    }
    let text = surfaceText.get(surface);
    if (text === undefined) {
      text = JSON.stringify(surface);
      surfaceText.set(surface, text);
    }
    const started = performance.now();
    let booted = false;
    let bootMs = 0;
    if (sandbox === undefined || (await sandbox.catch(() => undefined))?.destroyed !== false) {
      booted = true;
      sandbox = (async () => {
        const assets = await loadAssets();
        const fresh = await Sandbox.boot(spawn, assets, limits);
        stats.booted += 1;
        return fresh;
      })();
    }
    let live: Sandbox;
    try {
      live = await sandbox;
      bootMs = booted ? live.bootMs : 0;
    } catch (error) {
      sandbox = undefined;
      return failed('boot-failed', `the sandbox could not start: ${String((error as Error)?.message ?? error)}`, undefined, true, 0, performance.now() - started);
    }
    const runStarted = performance.now();
    const wrapped = wrapProgram(program.js);
    const end = await live.run({
      js: wrapped,
      surfaceJson: text,
      coreLimitsJson: JSON.stringify(coreLimitsOf(limits)),
      host,
      limits,
      fixedTime: FIXED_TIME,
      seed: SEED,
    });
    if (end.destroyed) {
      stats.discarded += 1;
      sandbox = undefined;
    }
    const totalMs = performance.now() - runStarted;
    if (end.violation !== undefined) return failed(end.violation, end.message ?? '', end, booted, bootMs, totalMs);
    if (end.resultJson === null) return failed('engine-error', 'the sandbox ended the run without a result', end, booted, bootMs, totalMs);
    const mapPosition = createPositionMapper(program.map.mappings, wrapped);
    const accepted = accept(end.resultJson, limits, mapPosition);
    if ('violation' in accepted) {
      // the host refuses the result: the frame produced something it must not have, so it is discarded as well
      sandbox?.then((s) => s.destroy(), () => undefined);
      sandbox = undefined;
      stats.discarded += 1;
      return failed(accepted.violation, accepted.message, end, booted, bootMs, totalMs);
    }
    const metrics: RunMetrics = { cpuMs: end.cpuMs, engineWallMs: end.wallMs, setupMs: end.setupMs, totalMs, hostReads: end.hostReads, hostReadBytes: end.hostReadBytes, booted, bootMs };
    return { ...accepted.result, metrics };
  }

  return {
    run(program, surface, host, limits = {}) {
      const turn = queue.then(() => execute(program, surface, host, limits));
      queue = turn.catch(() => undefined);
      return turn;
    },
    dispose() {
      const live = sandbox;
      sandbox = undefined;
      void live?.then((s) => s.destroy(), () => undefined);
    },
    stats,
  };
}

let shared: SandboxRunner | undefined;

/**
 * Runs a checked program in the editor's shared sandbox (booted on first use). `host` answers its reads and the seal; nothing
 * here applies anything: the result is a list for review (codemode.md section 5.6).
 */
export function run(program: CheckedProgram, surface: SurfaceDocument, host: SandboxHost, limits?: Partial<SandboxLimits>): Promise<RunResult> {
  shared ??= createSandboxRunner();
  return shared.run(program, surface, host, limits);
}
