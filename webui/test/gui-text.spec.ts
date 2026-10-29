/**
 * Text layers as the compiler treats them (GuiCompiler.textProblem and the title assembler's pen
 * rule): every restriction the studio surfaces is pinned here against the same wording the compiler
 * uses, and the composed canvas draws smallcaps exactly where the client would.
 */
import { describe, expect, it } from 'vitest';
import type { GuiDocument, GuiLayer } from '../src/lib/gui/model';
import { composeCanvas } from '../src/lib/gui/compose';
import { smallcaps } from '../src/lib/gui/font/render';
import { ArgbCanvas } from '../src/lib/gui/raster/canvas';
import { analyzeText, colorOf, fontOf, isSkipped, measureTextLayer, offendingCharacter } from '../src/lib/gui/text';

const doc = (...layers: GuiLayer[]): GuiDocument => ({ title: '', rows: 3, layers, rest: {}, order: [] });
const text = (id: string, extra: Record<string, unknown> = {}): GuiLayer =>
  ({ id, type: 'text', x: 8, y: 6, text: 'Shop', font: 'smallcaps', ...extra }) as GuiLayer;
const messages = (d: GuiDocument, id: string): string[] => (analyzeText(d).get(id) ?? []).map((issue) => issue.message);

describe('what the compiler will skip', () => {
  it('accepts plain smallcaps text', () => {
    expect(analyzeText(doc(text('a')))).toEqual(new Map());
  });

  it('rejects the MiniMessage and legacy-code characters and anything outside printable ASCII', () => {
    for (const bad of ['a<b', 'a\\b', 'a&b', 'a§b']) {
      expect(messages(doc(text('a', { text: bad })), 'a'), bad).toEqual(['text may not contain <, \\, & or §']);
    }
    expect(messages(doc(text('a', { text: 'café' })), 'a')[0]).toContain('printable ASCII only');
    expect(messages(doc(text('a', { text: 'café', font: 'default', y: 8 })), 'a')[0]).toContain('default font');
    expect(offendingCharacter('ok', 'smallcaps')).toBeNull();
    expect(offendingCharacter('a\t', 'smallcaps')).toEqual({ char: '\t', reserved: false });
  });

  it('skips blank text, over-long text, bad fonts, bad colours and out-of-range positions', () => {
    expect(messages(doc(text('a', { text: '   ' })), 'a')).toEqual(['empty text is skipped']);
    expect(messages(doc(text('a', { text: 'x'.repeat(129) })), 'a')[0]).toContain('128');
    expect(messages(doc(text('a', { font: 'comic' })), 'a')).toEqual(['font must be smallcaps or default']);
    expect(messages(doc(text('a', { color: 'red' })), 'a')[0]).toContain('colour');
    expect(messages(doc(text('a', { x: 600 })), 'a')[0]).toContain('x must be');
    expect(fontOf(text('a', { font: undefined }))).toBe('smallcaps');
    expect(fontOf(text('a', { font: 'comic' }))).toBeNull();
  });

  it('default-font text must start on row 5 or lower (its glyph cell is 8 px high)', () => {
    expect(messages(doc(text('a', { font: 'default', y: 4 })), 'a')[0]).toContain('row 5 or lower');
    expect(analyzeText(doc(text('a', { font: 'default', y: 5 })))).toEqual(new Map());
    // smallcaps has no such limit
    expect(analyzeText(doc(text('a', { y: 0 })))).toEqual(new Map());
  });

  it('only the LAST text run may be default: every run after one is skipped, and the default run is told so', () => {
    const d = doc(text('d', { font: 'default', y: 20 }), text('after', { y: 30 }), text('third', { y: 40 }));
    expect(isSkipped(analyzeText(d).get('d'))).toBe(false);
    expect(analyzeText(d).get('d')![0]).toMatchObject({ severity: 'note' });
    expect(analyzeText(d).get('d')![0]!.message).toContain('skipped in game');
    expect(messages(d, 'after')[0]).toContain('follows the default-font text');
    expect(isSkipped(analyzeText(d).get('after'))).toBe(true);
    expect(isSkipped(analyzeText(d).get('third'))).toBe(true);
    // a default run LAST is fine, and a hidden layer does not count
    const last = doc(text('s'), text('d', { font: 'default', y: 20 }));
    expect(analyzeText(last)).toEqual(new Map());
    const hidden = doc(text('d', { font: 'default', y: 20 }), text('after', { visible: false }));
    expect(analyzeText(hidden)).toEqual(new Map());
  });

  it('a run with its own problem is not a run, so it does not trip the layers around it', () => {
    const d = doc(text('bad', { font: 'default', y: 2 }), text('fine', { y: 30 }));
    expect(isSkipped(analyzeText(d).get('bad'))).toBe(true);
    expect(analyzeText(d).has('fine')).toBe(false);
  });
});

describe('measuring and drawing', () => {
  it('measures smallcaps by its ink extent and the height of its art', () => {
    expect(measureTextLayer(text('a'))).toEqual({ w: smallcaps.measure('Shop') - 1, h: smallcaps.artRows });
    expect(measureTextLayer(text('a', { text: '' }))).toMatchObject({ w: 1 });
  });

  it('defaults the colour to the vanilla title grey', () => {
    expect(colorOf(text('a'))).toBe(0xff404040);
    expect(colorOf(text('a', { color: '#80FF0000' }))).toBe(0x80ff0000);
  });

  it('composes smallcaps text over the background at its x and y, and skips what the compiler skips', () => {
    const scene = { width: 64, height: 24, rows: 1, image: () => null };
    const shown = composeCanvas(doc(text('a', { x: 4, y: 3, color: '#FF0000' })), scene);
    const expected = new ArgbCanvas(64, 24);
    smallcaps.draw(expected, 4, 3, 'Shop', 0xffff0000);
    expect(shown.equals(expected)).toBe(true);
    const skipped = composeCanvas(doc(text('a', { x: 4, y: 3, text: 'a<b' })), scene);
    expect(skipped.equals(new ArgbCanvas(64, 24))).toBe(true);
    // default-font text is not pixels the studio owns
    const approx = composeCanvas(doc(text('a', { font: 'default', y: 8 })), scene);
    expect(approx.equals(new ArgbCanvas(64, 24))).toBe(true);
  });
});

describe('drawing a run exactly as the client draws the title', () => {
  it('puts each glyph row at the pen, advancing by ink width + 1, tinted by the colour (alpha is not part of a title colour)', () => {
    const canvas = new ArgbCanvas(60, 14);
    const pen = smallcaps.draw(canvas, 3, 2, 'Hi', 0x80ff0000);
    expect(pen).toBe(3 + smallcaps.measure('Hi'));
    const red = 0xffff0000;
    const rows = smallcaps.font.glyphs.get('H'.codePointAt(0)!)!;
    rows.forEach((row, dy) => {
      for (let dx = 0; dx < row.length; dx++) expect(canvas.get(3 + dx, 2 + dy), `H (${dx},${dy})`).toBe(row[dx] === '#' ? red : 0);
    });
    const second = smallcaps.font.glyphs.get('i'.codePointAt(0)!)!;
    const left = 3 + smallcaps.advance('H'.codePointAt(0)!);
    second.forEach((row, dy) => {
      for (let dx = 0; dx < row.length; dx++) expect(canvas.get(left + dx, 2 + dy)).toBe(row[dx] === '#' ? red : 0);
    });
  });

  it('draws a space as an advance only and skips characters the font does not draw', () => {
    const canvas = new ArgbCanvas(20, 10);
    const pen = smallcaps.draw(canvas, 0, 0, 'I éI', 0xffffffff);
    expect(pen).toBe(smallcaps.advance(0x49) + smallcaps.spaceAdvance + smallcaps.advance(0xe9) + smallcaps.advance(0x49));
    expect(canvas.get(smallcaps.advance(0x49), 0)).toBe(0);
  });
});
