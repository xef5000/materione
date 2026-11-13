// SPIKE (#340) - throwaway. Message vocabulary of the host channel (codemode.md 4.4).
export interface Limits {
  cpuMs: number; // CPU deadline, counted only while guest code executes
  memBytes: number; // QuickJS heap cap
  stackBytes: number;
  maxStaged: number;
  maxStagedBytes: number;
  maxReads: number;
  maxReadBytes: number; // total bytes of read answers
  maxLogBytes: number;
  maxResultBytes: number;
}

export const DEFAULT_LIMITS: Limits = {
  cpuMs: 1000,
  memBytes: 32 * 1024 * 1024,
  stackBytes: 256 * 1024, // 512 KiB overflows the browser's native wasm stack first (measured): keep it low
  maxStaged: 200,
  maxStagedBytes: 256 * 1024,
  maxReads: 100,
  maxReadBytes: 2 * 1024 * 1024,
  maxLogBytes: 16 * 1024,
  maxResultBytes: 64 * 1024,
};

export type HostToFrame =
  | { t: 'run'; runId: number; js: string; limits: Limits; fixedTime: number; seed: number }
  | { t: 'readResult'; runId: number; rid: number; ok: boolean; json: string };

export type Violation =
  | 'cpu-deadline'
  | 'memory-limit'
  | 'stack-limit'
  | 'stage-limit'
  | 'read-limit'
  | 'log-limit'
  | 'result-limit'
  | 'deadlock';

export interface GuestError {
  name: string;
  message: string;
  /** 1-based line in the JS handed to the engine; the host maps it to TS. */
  jsLine: number | null;
  jsCol: number | null;
}

export interface StagedRaw {
  json: string;
  jsLine: number | null;
}

export interface ResultMsg {
  t: 'result';
  runId: number;
  ok: boolean;
  violation?: Violation;
  error?: GuestError;
  staged: StagedRaw[];
  logs: string[];
  returnJson: string | null;
  /** the engine instance is unusable after this run; the host must discard the frame */
  poisoned?: boolean;
  cpuMs: number;
  wallMs: number;
  setupMs: number;
}

export type FrameToHost =
  | { t: 'ready'; initMs: number }
  | { t: 'read'; runId: number; rid: number; op: string; args: string }
  | ResultMsg;
