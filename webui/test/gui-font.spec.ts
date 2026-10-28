/**
 * The TS side of the shared smallcaps contract (gui.md section 4, fonts/smallcaps/README.md): the
 * font source the Java compiler loads must parse here to the same metrics, and every advance must
 * equal the table in fixtures/gui/smallcaps/advances.txt, which the Java test reads too.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { advanceOf, measure, parseSmallcaps } from '../src/lib/gui/font/smallcaps';

const SOURCE = readFileSync(fileURLToPath(new URL('../../fonts/smallcaps/smallcaps.txt', import.meta.url)), 'utf8');
const TABLE = readFileSync(
  fileURLToPath(new URL('../../fixtures/gui/smallcaps/advances.txt', import.meta.url)),
  'utf8',
);

function sharedTable(): { space: number; advances: Map<number, number> } {
  const advances = new Map<number, number>();
  let space = -1;
  for (const raw of TABLE.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '' || line.startsWith(';')) continue;
    const [key, value] = line.split(' ');
    if (key === 'space') space = Number(value);
    else advances.set(Number(key), Number(value));
  }
  return { space, advances };
}

describe('smallcaps font source (fonts/smallcaps)', () => {
  const font = parseSmallcaps(SOURCE);

  it('declares the metrics the README and the sheet cell promise', () => {
    expect(font.cellWidth).toBe(8);
    expect(font.cellHeight).toBe(16);
    expect(font.capHeight).toBe(6);
    expect(font.smallCapHeight).toBe(5);
    expect(font.space).toBe(3);
  });

  it('carries seven rows for every printable ASCII glyph, and nothing else', () => {
    expect(font.glyphs.size).toBe(0x7e - 0x21 + 1);
    for (const rows of font.glyphs.values()) {
      expect(rows).toHaveLength(7);
      for (const row of rows) expect(row.length).toBeLessThanOrEqual(font.cellWidth);
    }
  });

  it('draws capitals six rows tall and lowercase as small capitals on the same baseline', () => {
    const inkRows = (rows: readonly string[]) => rows.flatMap((row, index) => (row.includes('#') ? [index] : []));
    for (let cp = 'A'.charCodeAt(0); cp <= 'Z'.charCodeAt(0); cp++) {
      const rows = inkRows(font.glyphs.get(cp)!);
      expect(rows).toContain(0);
      expect(rows).toContain(font.capHeight - 1);
    }
    for (let cp = 'a'.charCodeAt(0); cp <= 'z'.charCodeAt(0); cp++) {
      const rows = inkRows(font.glyphs.get(cp)!);
      expect(rows.every((row) => row >= 1 && row <= font.capHeight - 1)).toBe(true);
      expect(rows).toContain(font.capHeight - 1);
    }
  });

  it('computes every advance as the shared width table says (the same file the Java test reads)', () => {
    const table = sharedTable();
    expect(font.space).toBe(table.space);
    expect(table.advances.size).toBe(0x7e - 0x21 + 1);
    for (const [codepoint, advance] of table.advances) {
      expect(advanceOf(font, codepoint), `advance of U+${codepoint.toString(16)}`).toBe(advance);
    }
  });

  it('measures a string as the sum of its characters', () => {
    expect(measure(font, 'Shop')).toBe(
      advanceOf(font, 'S'.charCodeAt(0)) + advanceOf(font, 'h'.charCodeAt(0))
        + advanceOf(font, 'o'.charCodeAt(0)) + advanceOf(font, 'p'.charCodeAt(0)),
    );
    expect(measure(font, 'A B')).toBe(advanceOf(font, 0x41) + font.space + advanceOf(font, 0x42));
  });

  it('rejects a source with a missing glyph, so a truncated file cannot ship', () => {
    const truncated = SOURCE.replace(/glyph U\+0041 A[\s\S]*?(?=glyph U\+0042)/, '');
    expect(() => parseSmallcaps(truncated)).toThrow(/no glyph for U\+41|has 0 rows|row/);
  });
});
