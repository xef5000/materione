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

/**
 * How a raised frame's outer corners are cut (gui.md section 11.6). `square` is the 1-px corner of every
 * earlier panel and of buttons; `vanilla` is the chest's own rounded corner, pixel for pixel. An absent
 * `corners` field in a file is `square`.
 */
export type Corners = 'square' | 'vanilla';

/**
 * The vanilla corners, applied over the shared raised part of a 2-px frame (the rules and their vectors
 * are in fixtures/gui/raster/README.md). Top-left, offsets from the top-left corner: `x + y <= 1` is
 * skipped, `x + y = 2` is outline, `(3, 3)` is highlight. Top-right, `dx = w - 1 - x` from the right edge
 * and `y` from the top: `dx + y <= 2` is skipped, `dx + y = 3` is outline. The bottom corners are plain
 * outline (the chest's bottom edge is the inventory separator). Rules apply in that order, so a later
 * one wins where two overlap (frames smaller than 8 x 8).
 */
export function vanillaPart(lx: number, ly: number, w: number, h: number, base: BevelPart): BevelPart {
  let part = base;
  if (lx + ly <= 1) part = 'skip';
  else if (lx + ly === 2) part = 'outline';
  else if (lx === 3 && ly === 3) part = 'highlight';
  const dx = w - 1 - lx;
  if (dx + ly <= 2) part = 'skip';
  else if (dx + ly === 3) part = 'outline';
  if (ly === h - 1 && (lx === 0 || lx === w - 1)) part = 'outline';
  return part;
}

export function paintRaised(
  canvas: ArgbCanvas,
  x: number,
  y: number,
  w: number,
  h: number,
  style: BevelStyle,
  edge: number,
  corners: Corners = 'square',
): void {
  for (let ly = 0; ly < h; ly++) {
    for (let lx = 0; lx < w; lx++) {
      let part = raisedPart(lx, ly, w, h, edge);
      if (corners === 'vanilla') part = vanillaPart(lx, ly, w, h, part);
      const color = colorOf(part, style);
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
