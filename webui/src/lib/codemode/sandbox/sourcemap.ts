/**
 * Positions back to the program the agent wrote (codemode.md section 4.6): the engine reports a staged operation's or a thrown
 * error's position in the program it ran, which is the TypeScript emitter's JavaScript inside the shared wrapper
 * (`wrapProgram`); the emitter's source map (parsed by the type-check Worker) gives the line and column of the program as written.
 *
 * One wrapper, not a second copy: the prefix is `WRAP_PREFIX` and sits on the program's first line, so only line 1's columns
 * shift, by exactly its length (typecheck/wrap.ts says the same of the checker's positions).
 */
import type { SourcePosition } from '../core/types';
import { WRAP_PREFIX } from '../typecheck/wrap';

const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** A decoded map: per generated line, segments `[generatedColumn, sourceLine, sourceColumn]`, all 0-based. */
export type DecodedMap = number[][][];

/** Decodes a source map's `mappings` (base64 VLQ) into per-line segments. */
export function decodeMappings(mappings: string): DecodedMap {
  const lines: DecodedMap = [];
  let sourceLine = 0;
  let sourceColumn = 0;
  for (const line of mappings.split(';')) {
    const segments: number[][] = [];
    let generatedColumn = 0;
    for (const text of line.split(',')) {
      if (text === '') continue;
      const values: number[] = [];
      let shift = 0;
      let value = 0;
      for (const char of text) {
        const digit = BASE64.indexOf(char);
        value += (digit & 31) << shift;
        if (digit & 32) {
          shift += 5;
        } else {
          values.push(value & 1 ? -(value >> 1) : value >> 1);
          value = 0;
          shift = 0;
        }
      }
      generatedColumn += values[0] ?? 0;
      if (values.length >= 4) {
        sourceLine += values[2] ?? 0;
        sourceColumn += values[3] ?? 0;
        segments.push([generatedColumn, sourceLine, sourceColumn]);
      }
    }
    lines.push(segments);
  }
  return lines;
}

/** The program position (1-based) of a generated position (1-based): the last segment at or before it. */
export function originalPosition(decoded: DecodedMap, line: number, column: number): SourcePosition {
  const segments = decoded[line - 1] ?? [];
  let best: number[] | undefined;
  for (const segment of segments) if ((segment[0] ?? 0) <= column - 1) best = segment;
  best ??= segments[0];
  return best === undefined ? { line, column } : { line: (best[1] ?? 0) + 1, column: (best[2] ?? 0) + 1 };
}

/**
 * The column a call's frame names. V8 (the engine the goldens were first recorded under) reports the callee's own name
 * (`create` of `p.items.create(`); QuickJS reports the opening parenthesis. The host normalizes to the name, so a position
 * points at the member the agent called whichever engine ran: back over blanks, then over the identifier before the `(`.
 */
export function calleeColumn(lineText: string, column: number): number {
  if (lineText[column - 1] !== '(') return column;
  let index = column - 1;
  while (index > 0 && /\s/.test(lineText[index - 1]!)) index -= 1;
  const end = index;
  while (index > 0 && /[A-Za-z0-9_$]/.test(lineText[index - 1]!)) index -= 1;
  return index === end ? column : index + 1;
}

/**
 * Maps positions the engine reports (in `wrappedJs`, the wrapped program it ran) to the program as written.
 */
export function createPositionMapper(mappings: string, wrappedJs: string): (position: SourcePosition) => SourcePosition {
  const decoded = decodeMappings(mappings);
  const lines = wrappedJs.split('\n');
  return (position) => {
    const named = calleeColumn(lines[position.line - 1] ?? '', position.column);
    const column = position.line === 1 ? named - WRAP_PREFIX.length : named;
    return originalPosition(decoded, position.line, column);
  };
}
