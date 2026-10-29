/**
 * Text layers as the studio draws them: smallcaps is pixel-exact against the shared font source, and
 * the composed canvas leaves out what the compiler will skip. Which runs are skipped, and why, is the
 * server's verdict (`gui.validate`, pinned by the Java compiler tests and gui-validation.spec.ts); here
 * the studio is only told the ids, and draws accordingly.
 */
import { describe, expect, it } from 'vitest';
import type { GuiDocument, GuiLayer } from '../src/lib/gui/model';
import { composeCanvas } from '../src/lib/gui/compose';
import { smallcaps } from '../src/lib/gui/font/render';
import { ArgbCanvas } from '../src/lib/gui/raster/canvas';
import { colorOf, measureTextLayer } from '../src/lib/gui/text';

const doc = (...layers: GuiLayer[]): GuiDocument => ({ title: '', rows: 3, layers, rest: {}, order: [] });
const text = (id: string, extra: Record<string, unknown> = {}): GuiLayer =>
  ({ id, type: 'text', x: 8, y: 6, text: 'Shop', font: 'smallcaps', ...extra }) as GuiLayer;
const NONE = new Set<string>();

describe('measuring and drawing', () => {
  it('measures smallcaps by its ink extent and the height of its art', () => {
    expect(measureTextLayer(text('a'))).toEqual({ w: smallcaps.measure('Shop') - 1, h: smallcaps.artRows });
    expect(measureTextLayer(text('a', { text: '' }))).toMatchObject({ w: 1 });
  });

  it('defaults the colour to the vanilla title grey', () => {
    expect(colorOf(text('a'))).toBe(0xff404040);
    expect(colorOf(text('a', { color: '#80FF0000' }))).toBe(0x80ff0000);
  });

  it('composes smallcaps text over the background at its x and y', () => {
    const scene = { width: 64, height: 24, rows: 1, image: () => null };
    const shown = composeCanvas(doc(text('a', { x: 4, y: 3, color: '#FF0000' })), scene, NONE);
    const expected = new ArgbCanvas(64, 24);
    smallcaps.draw(expected, 4, 3, 'Shop', 0xffff0000);
    expect(shown.equals(expected)).toBe(true);
  });

  it('leaves out a text layer the server says the compiler skips', () => {
    const scene = { width: 64, height: 24, rows: 1, image: () => null };
    const skipped = composeCanvas(doc(text('a', { x: 4, y: 3 })), scene, new Set(['a']));
    expect(skipped.equals(new ArgbCanvas(64, 24))).toBe(true);
  });

  it('draws default-font text as nothing the studio owns (the client draws it)', () => {
    const scene = { width: 64, height: 24, rows: 1, image: () => null };
    const approx = composeCanvas(doc(text('a', { font: 'default', y: 8 })), scene, NONE);
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
