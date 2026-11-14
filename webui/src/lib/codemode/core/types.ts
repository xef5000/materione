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
 * The one injected port (codemode.md section 3.1, 3.9). The core never reaches the network, the editor's state or
 * the server on its own, and **staging makes no call at all**: a program that only writes calls the port once, when
 * it finishes, with `resolve`. Only `m.read.*` waits for the host, through `read`, which is asynchronous, so the
 * sandbox needs no asyncify. `stage`, `log` and `done` are observers.
 */
export interface HostPort {
  /** One read-only editor op, for `m.read.*`: an allowlisted op of the operation table. The host answers when it has the reply. */
  read(op: string, payload: Record<string, unknown>): Promise<ReadReply>;
  /**
   * The seal step: everything staging could not know, in one batch, answered by the host from read-only editor
   * ops. Called once, after the program has returned, and not at all when the program staged nothing and named
   * nothing.
   */
  resolve(request: ResolveRequest): Promise<ResolveReply>;
  /** Told of each operation as the seal builds it, in order. */
  stage?(operation: StagedOperation, source: SourcePosition | null, index: number): void;
  log?(line: string): void;
  done?(result: ProgramResult): void;
}

/** One stored resource the program names (`m.ref.<type>`, `m.edit`). */
export interface ResolveRef {
  /** The content type name. */
  kind: string;
  /** The directory its documents live under: what `authoring.summaries` filters on. */
  directory: string;
  pack: string;
  /** The resource's own id. */
  id: string;
  /** Whether an operation names it: then the answer carries its path, revision and rule count; otherwise only whether it exists. */
  needsDocument: boolean;
  /** The field of its tree that holds its rules, when the content type has one (the rule count is its length). */
  rulesKey?: string;
}

/** The seal's batch. Every list in the reply is parallel to the list of the same name here. */
export interface ResolveRequest {
  /** The packs the program writes to (at most one). */
  packs: string[];
  refs: ResolveRef[];
  /** Recipes to expand, in the order the program instantiated them. */
  instantiate: { recipe: string; params: JsonValue }[];
  /** Assets the program referenced, in the pack it writes to. */
  assets: { pack: string; path: string }[];
}

export interface ResolveReply {
  /** `state`: ok, builtin (read-only), or missing; `nearest` names the closest packs when it is missing. */
  packs: { state: 'ok' | 'builtin' | 'missing'; nearest?: string[] }[];
  /** A found reference with `needsDocument: true` carries `path`, `rev` and `rules`; a missing one carries the nearest ids (`pack:id`) of its type. */
  refs: ({ found: true; path?: string; rev?: number; rules?: number } | { found: false; nearest?: string[] })[];
  /** The expansion, or the server's refusal (its code and message). */
  instantiate: ({ rules: JsonValue[]; warnings?: unknown[] } | { error: { code?: string; message: string } })[];
  assets: { found: boolean; nearest?: string[] }[];
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
  /**
   * Ends the run: the program has returned (and awaited what it meant to), the list is sealed with one `resolve`, and the
   * result comes back. After it, every call on `m` fails with SDK-0003 (and is reported by `late`).
   */
  finish(outcome?: Outcome): Promise<ProgramResult>;
  /** Diagnostics raised after `finish` (a continuation that kept a handle). Empty for a program that ended cleanly. */
  late(): SdkDiagnostic[];
}
