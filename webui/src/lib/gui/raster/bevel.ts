/**
 * The Minecraft bevel painter (gui.md section 4), shared by `panel`, `button`
 * and `slots`: a panel is a large button and a slot well is an inset one.
 *
 * Every pixel of a bevel is classified by one pure function of its position
 * inside the rectangle, so the rule is a table, not a drawing procedure. Each
 * pixel is written at most once. fixtures/gui/raster/README.md restates the
 * rules in words; the vectors there pin them.
 */
import type { Argb } from './color';
import type { ArgbCanvas } from './canvas';

export interface BevelStyle {
  fill: Argb;
  highlight: Argb;
  shadow: Argb;
  outline: Argb;
}

export type BevelPart = 'skip' | 'outline' | 'highlight' | 'shadow' | 'fill';

/** Edge thickness of the highlight/shadow band: panels 2 px, buttons 1 px. */
export const PANEL_EDGE = 2;
export const BUTTON_EDGE = 1;

/**
 * Raised bevel (panel, button), pixel (lx, ly) of a w x h rectangle:
 *
 *  - the outermost ring is `outline`, except the four corner pixels, which are
 *    left untouched (transparent corners);
 *  - inside the ring, `top`/`left` mean the first `edge` rows/columns and
 *    `bottom`/`right` the last `edge`: top or left alone is `highlight`,
 *    bottom or right alone is `shadow`, both (the two off-diagonal corners) or
 *    neither (the interior) is `fill`.
 */
export function raisedPart(lx: number, ly: number, w: number, h: number, edge: number): BevelPart {
  const onX = lx === 0 || lx === w - 1;
  const onY = ly === 0 || ly === h - 1;
  if (onX || onY) return onX && onY ? 'skip' : 'outline';
  const ix = lx - 1;
  const iy = ly - 1;
  const light = iy < edge || ix < edge;
  const dark = iy >= h - 2 - edge || ix >= w - 2 - edge;
  if (light && dark) return 'fill';
  if (light) return 'highlight';
  if (dark) return 'shadow';
  return 'fill';
}

/**
 * Inset bevel (slot wells), no outline: the first row/column is `shadow`, the
 * last row/column is `highlight`; where they meet (top-right, bottom-left) and
 * in the interior the pixel is `fill`.
 */
export function insetPart(lx: number, ly: number, w: number, h: number): BevelPart {
  const dark = ly === 0 || lx === 0;
  const light = ly === h - 1 || lx === w - 1;
  if (dark && light) return 'fill';
  if (dark) return 'shadow';
  if (light) return 'highlight';
  return 'fill';
}

function colorOf(part: BevelPart, style: BevelStyle): Argb | null {
  switch (part) {
    case 'skip':
      return null;
    case 'outline':
      return style.outline;
    case 'highlight':
      return style.highlight;
    case 'shadow':
      return style.shadow;
    case 'fill':
      return style.fill;
  }
}

export function paintRaised(
  canvas: ArgbCanvas,
  x: number,
  y: number,
  w: number,
  h: number,
  style: BevelStyle,
  edge: number,
): void {
  for (let ly = 0; ly < h; ly++) {
    for (let lx = 0; lx < w; lx++) {
      const color = colorOf(raisedPart(lx, ly, w, h, edge), style);
      if (color !== null) canvas.blend(x + lx, y + ly, color);
    }
  }
}

export function paintInset(
  canvas: ArgbCanvas,
  x: number,
  y: number,
  w: number,
  h: number,
  style: BevelStyle,
): void {
  for (let ly = 0; ly < h; ly++) {
    for (let lx = 0; lx < w; lx++) {
      const color = colorOf(insetPart(lx, ly, w, h), style);
      if (color !== null) canvas.blend(x + lx, y + ly, color);
    }
  }
}
