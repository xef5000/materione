/**
 * The client's default-font advance widths, printable ASCII (gui.md section 9.4, issue 202).
 *
 * The table is one file, `fonts/vanilla-ascii/advances.txt`, produced from the client jar by
 * `scripts/gui/derive-vanilla-ascii-advances.mjs`. The Java compiler (`VanillaAscii`) reads the same
 * file, so the studio's pen and the compiled title's pen are the same numbers. Only widths ship here:
 * the vanilla texture is referenced by resource location and is never redistributed, so the studio
 * still draws the `default` font as an approximation.
 *
 * Pure: no DOM, no Svelte.
 */
import source from '../../../../../fonts/vanilla-ascii/advances.txt?raw';

export const VANILLA_FIRST = 0x20;
export const VANILLA_LAST = 0x7e;

export interface VanillaAsciiTable {
  /** The advance of a space: the client's `include/space` provider. */
  readonly space: number;
  /** The advance of each printable ASCII codepoint, `0x21`..`0x7E`, keyed by codepoint. */
  readonly advances: ReadonlyMap<number, number>;
}

const SPACE_LINE = /^space (\d+)$/;
const CODE_LINE = /^0x([0-9A-Fa-f]{2}) (\d+)$/;

/** Parses the table format; throws on anything malformed or incomplete. */
export function parseVanillaAscii(text: string): VanillaAsciiTable {
  let space: number | null = null;
  const advances = new Map<number, number>();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '' || line.startsWith(';')) continue;
    const spaceMatch = SPACE_LINE.exec(line);
    if (spaceMatch) {
      space = Number(spaceMatch[1]);
      continue;
    }
    const codeMatch = CODE_LINE.exec(line);
    if (!codeMatch) throw new Error(`vanilla-ascii: unrecognised line: ${line}`);
    const codepoint = parseInt(codeMatch[1]!, 16);
    if (advances.has(codepoint)) throw new Error(`vanilla-ascii: U+${codepoint.toString(16)} is defined twice`);
    advances.set(codepoint, Number(codeMatch[2]));
  }
  if (space === null) throw new Error('vanilla-ascii: the table has no space line');
  for (let cp = VANILLA_FIRST + 1; cp <= VANILLA_LAST; cp++) {
    if (!advances.has(cp)) throw new Error(`vanilla-ascii: no advance for U+${cp.toString(16)}`);
  }
  return { space, advances };
}

/** The shipped table, parsed once. */
export const vanillaAscii: VanillaAsciiTable = parseVanillaAscii(source);

/** Whether the default font draws `codepoint`: printable ASCII only. */
export function supportsDefault(codepoint: number): boolean {
  return codepoint >= VANILLA_FIRST && codepoint <= VANILLA_LAST;
}

/** The advance of one character in the default font; 0 for characters it does not draw. */
export function defaultAdvance(char: string): number {
  const codepoint = char.codePointAt(0)!;
  if (codepoint === 0x20) return vanillaAscii.space;
  return vanillaAscii.advances.get(codepoint) ?? 0;
}

/** The advance of a whole string in the default font: the sum of its characters' advances. */
export function measureDefault(text: string): number {
  let total = 0;
  for (const char of text) total += defaultAdvance(char);
  return total;
}
