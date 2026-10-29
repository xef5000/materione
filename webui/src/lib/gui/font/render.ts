/**
 * Drawing the `smallcaps` font on the studio canvas (gui.md section 4, "Fonts for text layers").
 *
 * The font itself is parsed by `smallcaps.ts` from `fonts/smallcaps/smallcaps.txt`, the single source
 * the Java compiler reads too (the artwork is issue 188's, shared across both PRs). This module only
 * draws what that parser returns, exactly as the client draws the compiled title: each glyph's rows at
 * the pen, the pen advancing by the client's bitmap rule (ink width + 1, a space is the font's own
 * width), tinted by the text colour. Swapping the artwork changes nothing here.
 *
 * Pure: no DOM, no Svelte.
 */
import smallcapsSource from '../../../../../fonts/smallcaps/smallcaps.txt?raw';
import type { ArgbCanvas } from '../raster/canvas';
import { argb, blueOf, greenOf, redOf, type Argb } from '../raster/color';
import { GLYPH_ROWS, advanceOf, measure, parseSmallcaps, type SmallcapsFont } from './smallcaps';

const FIRST = 0x20;
const LAST = 0x7e;

export class SmallcapsRenderer {
  constructor(readonly font: SmallcapsFont) {}

  /** Rows of art per glyph (cap band plus the descender row); the height of a text layer's box. */
  get artRows(): number {
    return GLYPH_ROWS;
  }

  get spaceAdvance(): number {
    return this.font.space;
  }

  /** Whether the compiler draws `codepoint` with this font (printable ASCII). */
  supports(codepoint: number): boolean {
    return codepoint >= FIRST && codepoint <= LAST;
  }

  advance(codepoint: number): number {
    return advanceOf(this.font, codepoint);
  }

  measure(text: string): number {
    return measure(this.font, text);
  }

  /** The visible extent of a string: its advance without the trailing spacing pixel. */
  inkExtent(text: string): number {
    return Math.max(0, this.measure(text) - 1);
  }

  /**
   * Draws a run with its top-left at (x, y) in GUI pixels; returns the pen after the run. The colour's
   * alpha is not part of a title colour (the client tints with RGB).
   */
  draw(canvas: ArgbCanvas, x: number, y: number, text: string, color: Argb): number {
    const ink = argb(255, redOf(color), greenOf(color), blueOf(color));
    let pen = x;
    for (const char of text) {
      const codepoint = char.codePointAt(0)!;
      const rows = this.supports(codepoint) ? this.font.glyphs.get(codepoint) : undefined;
      rows?.forEach((row, dy) => {
        for (let dx = 0; dx < row.length; dx++) if (row[dx] === '#') canvas.blend(pen + dx, y + dy, ink);
      });
      pen += this.advance(codepoint);
    }
    return pen;
  }
}

export const smallcaps = new SmallcapsRenderer(parseSmallcaps(smallcapsSource));
