/**
 * The smallcaps title font, as the studio reads it (gui.md section 4, fonts/smallcaps/README.md).
 *
 * The artwork is one text file, `fonts/smallcaps/smallcaps.txt`, shared with the Java compiler
 * (`SmallcapsFont`). This module parses that same format, so the studio's preview and the compiled
 * sheet come from identical pixels. Advances are the client's bitmap rule applied to those pixels:
 * the rightmost ink column plus one, then one pixel of spacing. The Java and TypeScript sides
 * both test against fixtures/gui/smallcaps/advances.txt.
 */

/** The parsed font: its metrics and the seven art rows of every printable ASCII glyph. */
export interface SmallcapsFont {
  readonly cellWidth: number;
  readonly cellHeight: number;
  readonly capHeight: number;
  readonly smallCapHeight: number;
  readonly space: number;
  /** Seven rows per glyph, top to bottom; '#' is ink, '.' is none. Keyed by codepoint. */
  readonly glyphs: ReadonlyMap<number, readonly string[]>;
}

/** The glyph rows a font must carry: the cap band (0–5) and the descender row (6). */
export const GLYPH_ROWS = 7;
const FIRST = 0x21;
const LAST = 0x7e;
const GLYPH_HEADER = /^glyph U\+([0-9A-Fa-f]{4}) (.)$/;
const ROW = /^[#.]+$/;

/** Parses the source format of fonts/smallcaps/smallcaps.txt; throws on anything malformed. */
export function parseSmallcaps(text: string): SmallcapsFont {
  const header: Record<string, number> = {};
  const glyphs = new Map<number, string[]>();
  let codepoint: number | null = null;
  let rows: string[] = [];

  const close = (): void => {
    if (codepoint === null) return;
    if (rows.length !== GLYPH_ROWS) {
      throw new Error(`smallcaps: U+${codepoint.toString(16)} has ${rows.length} rows, expected ${GLYPH_ROWS}`);
    }
    if (glyphs.has(codepoint)) throw new Error(`smallcaps: U+${codepoint.toString(16)} is defined twice`);
    glyphs.set(codepoint, rows);
  };

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trimEnd();
    if (line === '' || line.startsWith(';')) continue;
    if (line.startsWith('glyph ')) {
      close();
      const match = GLYPH_HEADER.exec(line);
      if (!match) throw new Error(`smallcaps: bad glyph header: ${line}`);
      codepoint = parseInt(match[1]!, 16);
      if (match[2]!.codePointAt(0) !== codepoint) {
        throw new Error(`smallcaps: header character does not match U+${match[1]}: ${line}`);
      }
      rows = [];
      continue;
    }
    if (codepoint !== null) {
      if (!ROW.test(line)) throw new Error(`smallcaps: bad art row under U+${codepoint.toString(16)}: ${line}`);
      rows.push(line);
      continue;
    }
    const pair = /^(cell|cap|small|space) (\d+)(?: (\d+))?$/.exec(line);
    if (!pair) throw new Error(`smallcaps: unrecognised line: ${line}`);
    header[pair[1]!] = Number(pair[2]);
    if (pair[1] === 'cell') header.cellHeight = Number(pair[3]);
  }
  close();

  for (let cp = FIRST; cp <= LAST; cp++) {
    if (!glyphs.has(cp)) throw new Error(`smallcaps: no glyph for U+${cp.toString(16)}`);
  }
  for (const key of ['cell', 'cap', 'small', 'space']) {
    if (header[key] === undefined) throw new Error(`smallcaps: the source has no ${key} line`);
  }
  return {
    cellWidth: header.cell!,
    cellHeight: header.cellHeight!,
    capHeight: header.cap!,
    smallCapHeight: header.small!,
    space: header.space!,
    glyphs,
  };
}

/** The ink width of one glyph: the rightmost '#' column plus one. */
export function inkWidth(rows: readonly string[]): number {
  let width = 0;
  for (const row of rows) width = Math.max(width, row.lastIndexOf('#') + 1);
  return width;
}

/** The advance of one character: its ink width + 1, or the space advance; 0 for characters the font does not draw. */
export function advanceOf(font: SmallcapsFont, codepoint: number): number {
  if (codepoint === 0x20) return font.space;
  const rows = font.glyphs.get(codepoint);
  return rows === undefined ? 0 : inkWidth(rows) + 1;
}

/** The advance of a string: the sum of its characters' advances. */
export function measure(font: SmallcapsFont, text: string): number {
  let total = 0;
  for (const character of text) total += advanceOf(font, character.codePointAt(0)!);
  return total;
}
