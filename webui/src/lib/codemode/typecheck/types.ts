/**
 * The type-check Worker's vocabulary (codemode.md section 5.1, 6): what the checker takes and what it answers.
 * Types and constants only, so the client can import them without pulling the compiler in.
 */

/** A diagnostic in the project's shape (plugin.md section 5): a stable code, a position in the program the agent wrote, a hint. */
export interface CheckDiagnostic {
  /** `TS####` for a TypeScript diagnostic, `CHK-####` for the checker's own (plugin.md section 5). */
  code: string;
  severity: 'error' | 'warning';
  message: string;
  /** 1-based line of the program as written; 0 when the diagnostic has no position. */
  line: number;
  /** 1-based column; 0 when the diagnostic has no position. */
  column: number;
  /** What the agent should try: TypeScript's own suggestion, or a note on the checker's rules. */
  hint?: string;
  /** Set on a `TS2339` or `TS2353` on a member of a generated catalog type: the server lacks this primitive (codemode.md section 9.2). */
  missingPrimitive?: MissingPrimitive;
}

export interface MissingPrimitive {
  /** The member the program asked for (`commands`). */
  member: string;
  /** The generated catalog type that lacks it (`PackWriter`). */
  catalog: string;
  /** The members of that catalog that look like it, nearest first (a typo has some, a real gap usually has none). */
  nearest: string[];
}

/** A source map of the transpiled program back to the program as written (the TypeScript emitter's, parsed). */
export interface RawSourceMap {
  version: number;
  file?: string;
  sources: string[];
  names: string[];
  mappings: string;
}

export interface CheckResult {
  /** No error diagnostics: `js` is present and the program may go to the sandbox. */
  ok: boolean;
  diagnostics: CheckDiagnostic[];
  /** More diagnostics existed than `CheckLimits.maxDiagnostics`; the first ones are here. */
  truncated: boolean;
  /** The ES2020 the program became. Present only when `ok`: a program with errors is never handed back as runnable text. */
  js?: string;
  /** The map from `js` to the program's own lines. Present with `js`. */
  map?: RawSourceMap;
}

export interface CheckInput {
  program: string;
  /** The generated `.d.ts` of the connected server (`generateSdk`), never the core. */
  sdk: string;
}

/** The checker's own diagnostic codes. Stable API like `MTR-####` and `SDK-####`; documented in plugin.md section 5. */
export const CHECK_CODES = {
  'CHK-0001': 'The program uses a construct the checker refuses',
  'CHK-0002': 'The program or the surface is larger than the checker takes',
  'CHK-0003': 'The check ran past its deadline and was cut off',
  'CHK-0004': 'The checker could not run',
} as const;

export type CheckCode = keyof typeof CHECK_CODES;

/** Bounds of one check (codemode.md section 4.2, as built by issue 342). */
export interface CheckLimits {
  /** Largest program, in UTF-16 code units. */
  maxProgramChars: number;
  /** Largest generated declarations text, in UTF-16 code units. */
  maxSdkChars: number;
  /** Diagnostics returned; the rest are counted as truncated. */
  maxDiagnostics: number;
  /** Wall-clock deadline of one check once the Worker is up. The Worker is terminated when it passes. */
  checkMs: number;
  /** Wall-clock deadline for the Worker to start (a static asset of about 1 MB gzip, parsed once). */
  bootMs: number;
}

export const DEFAULT_CHECK_LIMITS: CheckLimits = {
  maxProgramChars: 65_536,
  maxSdkChars: 1_048_576,
  maxDiagnostics: 50,
  checkMs: 5_000,
  bootMs: 20_000,
};

/** The Worker's messages: a closed vocabulary, like the sandbox channel of codemode.md section 4.4. */
export type ToWorker = { type: 'check'; id: number; program: string; sdk: string };
export type FromWorker =
  | { type: 'ready' }
  | { type: 'result'; id: number; result: CheckResult; checkMs: number }
  | { type: 'failed'; id: number; message: string };
