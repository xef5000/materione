/**
 * The sandbox's vocabulary (codemode.md section 4.2, 4.4, 4.6): the limits of a run, the diagnostics the host raises when a
 * limit ends one, and the closed set of messages between the host and the engine that runs in the sandboxed frame.
 *
 * Types, constants and validators only: no engine, no DOM, so the host, the engine and the tests all import it. Everything the
 * frame sends is UNTRUSTED input to the host (the engine is the layer a guest that escaped QuickJS would control), so every
 * message is validated here, by shape and by size, before the host reads a field of it.
 */
import type { Limits as CoreLimits } from '../core/types';

// ---- limits ---------------------------------------------------------------------------------------------------

/** The limits of one run, all enforced by the host; the numbers are codemode.md section 4.2's, as built. */
export interface SandboxLimits {
  /** Milliseconds of ENGINE time (an interrupt handler) the PROGRAM may use: counted only while its code runs (waiting on a host answer is free), from its first statement (guest setup is not charged). */
  cpuMs: number;
  /** Backstop, milliseconds the frame may take with no host answer outstanding; past it the host destroys the frame. */
  wallMs: number;
  /** Backstop, milliseconds one read or the seal's resolve may stay unanswered by the injected host. */
  readMs: number;
  /** Absolute ceiling of a whole run, host answers included. */
  totalMs: number;
  /** Milliseconds a fresh sandbox may take to boot. */
  bootMs: number;
  /** Hard cap of the engine's `WebAssembly.Memory`, in MiB (QuickJS's own limit is set below it). */
  memoryMb: number;
  /** QuickJS's native stack, in bytes (512 KiB overflows the browser's wasm stack first: spike). */
  stackBytes: number;
  /** Read calls that reach the host, and bytes of their answers. */
  reads: number;
  readBytes: number;
  /** Bytes of one read's payload and of the seal's resolve request. */
  payloadBytes: number;
  /** Staged operations, distinct documents, and bytes of the operation list. */
  operations: number;
  documents: number;
  stagedBytes: number;
  /** Bytes of the log lines and of the return value. */
  logBytes: number;
  returnBytes: number;
}

export const DEFAULT_SANDBOX_LIMITS: SandboxLimits = {
  cpuMs: 1000,
  wallMs: 10_000,
  readMs: 5_000,
  totalMs: 60_000,
  bootMs: 10_000,
  memoryMb: 64,
  stackBytes: 256 * 1024,
  reads: 100,
  readBytes: 2 * 1024 * 1024,
  payloadBytes: 64 * 1024,
  operations: 200,
  documents: 50,
  stagedBytes: 256 * 1024,
  logBytes: 16 * 1024,
  returnBytes: 64 * 1024,
};

/** The SDK core's own limits for a run: the host's numbers where they overlap, so the core refuses first and says why. */
export function coreLimitsOf(limits: SandboxLimits): Partial<CoreLimits> {
  return {
    operations: limits.operations,
    documents: limits.documents,
    reads: limits.reads,
    readBytes: limits.readBytes,
    logBytes: limits.logBytes,
    returnBytes: limits.returnBytes,
  };
}

/** The most the engine may send back as the run's result: the operations, the logs and the return value with room for diagnostics. */
export function resultBytesOf(limits: SandboxLimits): number {
  return limits.stagedBytes + limits.logBytes + limits.returnBytes + 256 * 1024;
}

/** The fixed instant and the seed of a run (determinism, codemode.md section 4.2): the same program and answers give the same bytes. */
export const FIXED_TIME = Date.UTC(2026, 0, 1);
export const SEED = 12345;

// ---- the diagnostics the host raises ----------------------------------------------------------------------------

/**
 * The sandbox's own diagnostic codes. Stable API like `MTR-####`, `SDK-####` and `CHK-####`: documented in plugin.md section 5
 * (a test compares this table with that one), never reused. Each ends the run with no operations.
 */
export const SANDBOX_CODES = {
  'SBX-0001': 'The program used more CPU time than a run may',
  'SBX-0002': 'The program used more memory than a run may',
  'SBX-0003': 'The program recursed too deeply',
  'SBX-0004': 'The program read more than a run may',
  'SBX-0005': 'A read or the seal was not answered in time',
  'SBX-0006': 'The run passed its wall-clock backstop and the sandbox was destroyed',
  'SBX-0007': 'The staged operations are larger than a run may stage',
  'SBX-0008': 'The logs or the return value are larger than a run may produce',
  'SBX-0009': 'The program never finished',
  'SBX-0010': 'The sandbox sent something the host refuses',
  'SBX-0011': 'The sandbox could not start',
  'SBX-0012': 'The program could not run',
} as const;

export type SandboxCode = keyof typeof SANDBOX_CODES;

/** What ended a run before the program did. */
export type Violation =
  | 'cpu-deadline'
  | 'memory-limit'
  | 'stack-limit'
  | 'read-limit'
  | 'read-deadline'
  | 'wall-timeout'
  | 'stage-limit'
  | 'output-limit'
  | 'deadlock'
  | 'protocol'
  | 'boot-failed'
  | 'engine-error';

export const VIOLATION_CODE: Record<Violation, SandboxCode> = {
  'cpu-deadline': 'SBX-0001',
  'memory-limit': 'SBX-0002',
  'stack-limit': 'SBX-0003',
  'read-limit': 'SBX-0004',
  'read-deadline': 'SBX-0005',
  'wall-timeout': 'SBX-0006',
  'stage-limit': 'SBX-0007',
  'output-limit': 'SBX-0008',
  deadlock: 'SBX-0009',
  protocol: 'SBX-0010',
  'boot-failed': 'SBX-0011',
  'engine-error': 'SBX-0012',
};

export const VIOLATIONS = Object.keys(VIOLATION_CODE) as Violation[];

// ---- the channel ----------------------------------------------------------------------------------------------

/** The host's one message into the frame, on `window.postMessage` (the frame has no nameable origin, so target `'*'`). */
export interface InitMessage {
  t: 'init';
  wasm: ArrayBuffer;
  engineSrc: string;
  coreSrc: string;
  memoryMb: number;
}

/** Host to engine, on the private port. */
export type HostToEngine =
  | {
      t: 'run';
      runId: number;
      /** The wrapped, transpiled program (`wrapProgram`). */
      js: string;
      surfaceJson: string;
      coreLimitsJson: string;
      limits: { cpuMs: number; stackBytes: number; reads: number; readBytes: number; resultBytes: number; memoryBytes: number };
      fixedTime: number;
      seed: number;
    }
  | { t: 'answer'; runId: number; rid: number; json: string; ok: boolean };

export type CallKind = 'read' | 'resolve';

/** Engine to host. */
export type EngineToHost =
  | { t: 'ready'; initMs: number }
  | { t: 'boot-error'; message: string }
  | { t: 'call'; runId: number; rid: number; kind: CallKind; op: string; payload: string }
  | EngineResult;

export interface EngineResult {
  t: 'result';
  runId: number;
  /** Set when the run ended before the program finished. */
  violation?: Violation;
  /** What the engine itself reports about a failure (never trusted for a decision). */
  message?: string;
  /** The guest's JSON of `{ result, late }`, when the program finished. */
  resultJson: string | null;
  /** The engine instance is unusable after this run. */
  poisoned: boolean;
  cpuMs: number;
  wallMs: number;
  setupMs: number;
}

const MAX_OP_CHARS = 128;
const MAX_MESSAGE_CHARS = 4096;

function own(value: unknown, key: string): unknown {
  return typeof value === 'object' && value !== null && Object.hasOwn(value, key) ? (value as Record<string, unknown>)[key] : undefined;
}

const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);
const index = (value: unknown): value is number => finite(value) && Number.isInteger(value) && value >= 0;

/**
 * One message from the frame, validated by shape and size, or undefined. The only reader of an engine message: the host
 * never touches a field of the raw value. `maxPayload` bounds a call's payload and `maxResult` the result's JSON.
 */
export function parseEngineMessage(raw: unknown, maxPayload: number, maxResult: number): EngineToHost | undefined {
  const t = own(raw, 't');
  if (t === 'ready') {
    const initMs = own(raw, 'initMs');
    return finite(initMs) ? { t, initMs } : undefined;
  }
  if (t === 'boot-error') {
    const message = own(raw, 'message');
    return typeof message === 'string' ? { t, message: message.slice(0, MAX_MESSAGE_CHARS) } : undefined;
  }
  if (t === 'call') {
    const runId = own(raw, 'runId');
    const rid = own(raw, 'rid');
    const kind = own(raw, 'kind');
    const op = own(raw, 'op');
    const payload = own(raw, 'payload');
    if (!index(runId) || !index(rid) || (kind !== 'read' && kind !== 'resolve') || typeof op !== 'string' || typeof payload !== 'string') return undefined;
    if (op.length > MAX_OP_CHARS || payload.length > maxPayload) return undefined;
    return { t, runId, rid, kind, op, payload };
  }
  if (t === 'result') {
    const runId = own(raw, 'runId');
    const violation = own(raw, 'violation');
    const message = own(raw, 'message');
    const resultJson = own(raw, 'resultJson');
    const poisoned = own(raw, 'poisoned');
    const cpuMs = own(raw, 'cpuMs');
    const wallMs = own(raw, 'wallMs');
    const setupMs = own(raw, 'setupMs');
    if (!index(runId) || typeof poisoned !== 'boolean' || !finite(cpuMs) || !finite(wallMs) || !finite(setupMs)) return undefined;
    if (violation !== undefined && !VIOLATIONS.includes(violation as Violation)) return undefined;
    if (message !== undefined && typeof message !== 'string') return undefined;
    if (resultJson !== null && (typeof resultJson !== 'string' || resultJson.length > maxResult)) return undefined;
    const result: EngineResult = { t, runId, resultJson, poisoned, cpuMs, wallMs, setupMs };
    if (violation !== undefined) result.violation = violation as Violation;
    if (typeof message === 'string') result.message = message.slice(0, MAX_MESSAGE_CHARS);
    return result;
  }
  return undefined;
}

/** The frame's init message as the frame validates it (the frame is the first reader of the host's one message). */
export function isInit(raw: unknown): raw is InitMessage {
  return (
    own(raw, 't') === 'init' &&
    own(raw, 'wasm') instanceof ArrayBuffer &&
    typeof own(raw, 'engineSrc') === 'string' &&
    typeof own(raw, 'coreSrc') === 'string' &&
    finite(own(raw, 'memoryMb'))
  );
}
