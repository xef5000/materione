/**
 * The public types of the SDK core (codemode.md section 3.1): what the host injects, what the core returns.
 * DOM-free and dependency-free, so the same source runs in the editor's tests and, bundled, inside the sandbox
 * (codemode.md section 4, issue 343).
 */
import type { SurfaceDocument } from '../generate/surface';

/** Any JSON value: what a document, a staged operation and a read reply are made of. */
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

/** A position in the program's source, 1-based, as the engine's stack trace prints it. */
export interface SourcePosition {
  line: number;
  column: number;
}

/** What a read answers with: an editor op's reply, success or failure. */
export type ReadReply = { ok: true; data: unknown } | { ok: false; error: { code?: string; message: string } };

/**
 * The one injected port (codemode.md section 3.1). The core never reaches the network, the editor's state or
 * the server on its own: every read goes through `read`, which the host answers from the live session and may
 * refuse (it also holds the allowlist: the core only asks for ops of its operation table). `stage`, `log` and
 * `done` are observers: the core tells the host what it recorded, it never asks the host to apply anything.
 */
export interface HostPort {
  /** One read-only editor op. Synchronous from the program's side: the sandbox suspends the program while the host answers. */
  read(op: string, payload: Record<string, unknown>): ReadReply;
  stage?(operation: StagedOperation, source: SourcePosition | null, index: number): void;
  log?(line: string): void;
  done?(result: ProgramResult): void;
}

/** The limits of one run (codemode.md section 4.2). The transaction's own caps are the server's (api.md section 3.3.1). */
export interface Limits {
  /** Staged operations: the transaction's cap. */
  operations: number;
  /** Edits in one operation: the transaction's cap. */
  editsPerOperation: number;
  /** Distinct documents touched: the transaction's cap, decision C12. */
  documents: number;
  /** Read calls that reach the host (a repeated identical read is answered from the run's own cache and not counted). */
  reads: number;
  /** Bytes of read replies. */
  readBytes: number;
  /** Rows `m.read.resources` returns at most. */
  resources: number;
  /** Bytes of log lines. */
  logBytes: number;
  /** Bytes of the return value. */
  returnBytes: number;
  /** Characters of the summary. */
  summaryChars: number;
}

export const DEFAULT_LIMITS: Limits = {
  operations: 200,
  editsPerOperation: 256,
  documents: 50,
  reads: 200,
  readBytes: 4 * 1024 * 1024,
  resources: 1000,
  logBytes: 16 * 1024,
  returnBytes: 16 * 1024,
  summaryChars: 500,
};

/** One operation of the `authoring.transaction` payload, in exactly its wire shape: `{ kind, ...the kind's fields }`. */
export type StagedOperation = { kind: string } & { [key: string]: JsonValue };

export type DiagnosticSeverity = 'error' | 'warning';

/** One SDK diagnostic (codemode.md section 6, phase 2). The codes are stable API, documented in plugin.md section 5. */
export interface SdkDiagnostic {
  code: string;
  severity: DiagnosticSeverity;
  message: string;
  hint?: string;
  /** The program line that caused it, when the stack named one. */
  source?: SourcePosition;
  /** The server's own code, for a read the server refused. */
  serverCode?: string;
}

/** The output of a run (codemode.md section 5.2). `operations` is what goes to `authoring.transaction`, untouched. */
export interface ProgramResult {
  /** False when any diagnostic is an error: the operation list is then empty. */
  ok: boolean;
  sdkVersion: string;
  /** The one pack the program wrote to, or null when it staged nothing. */
  pack: string | null;
  operations: StagedOperation[];
  /** `sources[i]` is where in the program `operations[i]` was staged. */
  sources: (SourcePosition | null)[];
  diagnostics: SdkDiagnostic[];
  summary: string | null;
  logs: string[];
  returned: JsonValue | null;
  stats: { reads: number; readBytes: number; staged: number; documents: number };
}

/** How a run ended, as the host that ran the program reports it. */
export interface Outcome {
  /** The program's return value. */
  returned?: unknown;
  /** What the program threw, if it did. */
  threw?: unknown;
}

export interface SessionConfig {
  /** The surface document of the connected server (generate/surface.ts): the only description of it the core reads. */
  surface: SurfaceDocument;
  port: HostPort;
  limits?: Partial<Limits>;
  /** The file name the program's frames carry in a stack trace; staged operations are tagged with positions in it. */
  programFile?: string;
}

export interface Session {
  /** The program's one global. */
  readonly m: Record<string, unknown>;
  /** Ends the run and returns its result. After it, every call on `m` fails with SDK-0003 (and is reported by `late`). */
  finish(outcome?: Outcome): ProgramResult;
  /** Diagnostics raised after `finish` (a continuation that kept a handle). Empty for a program that ended cleanly. */
  late(): SdkDiagnostic[];
}
