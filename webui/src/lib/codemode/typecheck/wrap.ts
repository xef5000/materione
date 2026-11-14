/**
 * How a program is wrapped to be checked (codemode.md section 3.2, 5.1). Reads are promises and a program awaits them, so its
 * body is the body of an async function: the sandbox runs it as one, and the checker sees the same, or top-level `await` would
 * be TS1375 and a program could not be both checked and run in the same shape.
 *
 * The prefix is one line and the program starts right after it, so every line number of the wrapped text is the program's own,
 * and only line 1's columns shift by the prefix length. Nothing here parses anything: pure text, shared by the checker and by
 * the test helpers (`test/helpers/typecheck.ts`), so there is one wrapper.
 *
 * The TRANSPILED program is not wrapped: the checker hands the host the program's own JavaScript and source map, and the host
 * wraps it for the run (the core's harness does, `test/helpers/sdk-runner.ts`).
 */
export const WRAP_PREFIX = '(async () => {';
export const WRAP_SUFFIX = '\n})();\n';

/** The text the type checker reads for `program`. */
export function wrapProgram(program: string): string {
  return `${WRAP_PREFIX}${program}${WRAP_SUFFIX}`;
}

/** A 1-based position. */
export interface Position {
  line: number;
  column: number;
}

/**
 * A position in the wrapped text, as a position in the program as written. A position past the program's end (an unclosed
 * brace reports at the wrapper's closing line) is clamped to the program's last character.
 */
export function programPosition(program: string, wrapped: Position): Position {
  const lines = program.split('\n');
  if (wrapped.line > lines.length) return { line: lines.length, column: lines[lines.length - 1]!.length + 1 };
  const column = wrapped.line === 1 ? wrapped.column - WRAP_PREFIX.length : wrapped.column;
  return { line: wrapped.line, column: Math.max(1, column) };
}

/** The 1-based position of a character offset in the program as written. */
export function offsetPosition(program: string, offset: number): Position {
  const before = program.slice(0, offset).split('\n');
  return { line: before.length, column: before[before.length - 1]!.length + 1 };
}
