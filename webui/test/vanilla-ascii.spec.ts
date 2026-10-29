/**
 * The TS side of the vanilla default-font contract (gui.md section 9.4, #202): the table in
 * fonts/vanilla-ascii/advances.txt, the file the Java compiler (VanillaAscii) also reads, must parse
 * here, and its digest and histogram must equal fixtures/gui/vanilla-ascii/golden.txt.
 */
import { describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defaultAdvance, measureDefault, parseVanillaAscii, vanillaAscii } from '../src/lib/gui/font/vanilla-ascii';
import { measureDefault as measureFromDefaultFont } from '../src/lib/gui/font/default-font';

const SOURCE = readFileSync(fileURLToPath(new URL('../../fonts/vanilla-ascii/advances.txt', import.meta.url)), 'utf8');
const GOLDEN = readFileSync(
  fileURLToPath(new URL('../../fixtures/gui/vanilla-ascii/golden.txt', import.meta.url)),
  'utf8',
);

function golden(): Map<string, string> {
  const values = new Map<string, string>();
  for (const raw of GOLDEN.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '' || line.startsWith(';')) continue;
    const space = line.indexOf(' ');
    values.set(line.slice(0, space), line.slice(space + 1));
  }
  return values;
}

/** The canonical form the golden describes: "space N", then "0xHH N" per glyph, LF endings. */
function canonical(table: { space: number; advances: ReadonlyMap<number, number> }): string {
  let text = `space ${table.space}\n`;
  for (let cp = 0x21; cp <= 0x7e; cp++) {
    text += `0x${cp.toString(16).toUpperCase().padStart(2, '0')} ${table.advances.get(cp)}\n`;
  }
  return text;
}

describe('vanilla default-font advances (fonts/vanilla-ascii)', () => {
  it('matches the golden digest and histogram', () => {
    const expected = golden();
    const digest = createHash('sha256').update(canonical(vanillaAscii), 'utf8').digest('hex');
    expect(digest).toBe(expected.get('canonical-sha256'));

    const histogram = new Map<number, number>();
    const bump = (advance: number) => histogram.set(advance, (histogram.get(advance) ?? 0) + 1);
    bump(vanillaAscii.space);
    for (let cp = 0x21; cp <= 0x7e; cp++) bump(vanillaAscii.advances.get(cp)!);
    const pinned = new Map(
      expected
        .get('histogram')!
        .split(' ')
        .map((pair) => pair.split(':').map(Number) as [number, number]),
    );
    expect(histogram).toEqual(pinned);
  });

  it('takes the space advance from the client include/space provider', () => {
    expect(vanillaAscii.space).toBe(4);
    expect(defaultAdvance(' ')).toBe(4);
  });

  it('measures a string as the sum of its characters', () => {
    expect(measureDefault('Hi')).toBe(defaultAdvance('H') + defaultAdvance('i'));
    expect(measureFromDefaultFont('Hi')).toBe(measureDefault('Hi'));
    expect(measureDefault('')).toBe(0);
  });

  it('rejects a table with a missing glyph or a malformed line', () => {
    expect(() => parseVanillaAscii('space 4\n0x21 2\n')).toThrow(/no advance for U\+22/);
    expect(() => parseVanillaAscii('space 4\nnonsense\n')).toThrow(/unrecognised line/);
  });
});
