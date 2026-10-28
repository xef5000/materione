/**
 * Snapping (gui.md section 6): while a layer is moved or resized its edges and
 * center pull toward what the eye expects to align with: the canvas edges and
 * middle, the slot grid, and the edges and centers of sibling layers. Positions
 * are always whole GUI pixels (pixel snapping is rounding); everything here
 * returns an adjustment plus the guide lines to draw.
 */
import { ITEM_SIZE, cellsOf, wellRect } from './chest';
import type { Rect } from './raster/canvas';
import type { Handle } from './transform';

export type SnapKind = 'canvas' | 'slot' | 'sibling';

/** A line to snap to: `pos` along `axis`, spanning `from..to` on the other axis (for drawing). */
export interface SnapLine {
  axis: 'x' | 'y';
  pos: number;
  from: number;
  to: number;
  kind: SnapKind;
}

export interface SnapTargets {
  x: SnapLine[];
  y: SnapLine[];
}

export interface Guide extends SnapLine {}

export interface TargetSources {
  width: number;
  height: number;
  rows: number;
  /** Rectangles of the layers that are not being dragged. */
  siblings: readonly Rect[];
  /** Include the slot grid (item origins and wells). */
  slots: boolean;
}

export function collectTargets(source: TargetSources): SnapTargets {
  const x: SnapLine[] = [];
  const y: SnapLine[] = [];
  const addRect = (rect: Rect, kind: SnapKind, centers = true): void => {
    for (const pos of centers ? [rect.x, rect.x + rect.w / 2, rect.x + rect.w] : [rect.x, rect.x + rect.w]) {
      x.push({ axis: 'x', pos, from: rect.y, to: rect.y + rect.h, kind });
    }
    for (const pos of centers ? [rect.y, rect.y + rect.h / 2, rect.y + rect.h] : [rect.y, rect.y + rect.h]) {
      y.push({ axis: 'y', pos, from: rect.x, to: rect.x + rect.w, kind });
    }
  };
  addRect({ x: 0, y: 0, w: source.width, h: source.height }, 'canvas');
  if (source.slots) {
    const cells = cellsOf(source.rows, 'all');
    for (const cell of cells) {
      // the item area (16 px) and the well around it (18 px) both read as "the slot"
      addRect({ x: cell.x, y: cell.y, w: ITEM_SIZE, h: ITEM_SIZE }, 'slot', false);
      const well = wellRect(cell);
      addRect(well, 'slot', false);
    }
  }
  for (const rect of source.siblings) addRect(rect, 'sibling');
  return { x, y };
}

export interface SnapResult {
  dx: number;
  dy: number;
  guides: Guide[];
}

/** The closest of `candidates` to any of `edges`, within `threshold`: the shift to apply, and the lines hit. */
function bestShift(
  edges: readonly number[],
  lines: readonly SnapLine[],
  threshold: number,
): { shift: number; hits: SnapLine[] } | null {
  let best: number | null = null;
  for (const edge of edges) {
    for (const line of lines) {
      const delta = line.pos - edge;
      // only whole-pixel shifts: a center that would land on a half pixel is not a snap
      if (Number.isInteger(delta) && Math.abs(delta) <= threshold && (best === null || Math.abs(delta) < Math.abs(best))) best = delta;
    }
  }
  if (best === null) return null;
  const shift = best;
  const hits = lines.filter((line) => edges.some((edge) => Math.abs(line.pos - (edge + shift)) < 1e-6));
  return { shift, hits };
}

function guidesFor(rect: Rect, hits: readonly SnapLine[]): Guide[] {
  return hits.map((line) => {
    // stretch the guide across both the dragged layer and the thing it aligned with
    const lo = line.axis === 'x' ? rect.y : rect.x;
    const hi = line.axis === 'x' ? rect.y + rect.h : rect.x + rect.w;
    return { ...line, from: Math.min(line.from, lo), to: Math.max(line.to, hi) };
  });
}

/**
 * Snaps a rectangle being moved: its left/center/right and top/middle/bottom
 * pull toward the targets within `threshold` GUI pixels. The returned shift
 * lands the rectangle on whole pixels.
 */
export function snapMove(rect: Rect, targets: SnapTargets, threshold: number): SnapResult {
  const moved = rect;
  const xs = bestShift([moved.x, moved.x + moved.w / 2, moved.x + moved.w], targets.x, threshold);
  const ys = bestShift([moved.y, moved.y + moved.h / 2, moved.y + moved.h], targets.y, threshold);
  const dx = xs ? xs.shift : 0;
  const dy = ys ? ys.shift : 0;
  const final = { x: moved.x + dx, y: moved.y + dy, w: moved.w, h: moved.h };
  return {
    dx,
    dy,
    guides: [...(xs ? guidesFor(final, xs.hits) : []), ...(ys ? guidesFor(final, ys.hits) : [])],
  };
}

/**
 * Snaps a rectangle being resized: only the edges the handle moves are
 * considered, and snapping one of them changes the size, not the opposite edge.
 */
export function snapResize(
  rect: Rect,
  handle: Handle,
  targets: SnapTargets,
  threshold: number,
  min = 1,
): { rect: Rect; guides: Guide[] } {
  const west = handle.includes('w');
  const east = handle.includes('e');
  const north = handle.includes('n');
  const south = handle.includes('s');
  let { x, y, w, h } = rect;
  const hits: SnapLine[] = [];
  if (west || east) {
    const edge = west ? x : x + w;
    const found = bestShift([edge], targets.x, threshold);
    if (found) {
      if (west) {
        const right = x + w;
        x = Math.min(x + found.shift, right - min);
        w = right - x;
      } else {
        w = Math.max(min, w + found.shift);
      }
      hits.push(...found.hits);
    }
  }
  if (north || south) {
    const edge = north ? y : y + h;
    const found = bestShift([edge], targets.y, threshold);
    if (found) {
      if (north) {
        const bottom = y + h;
        y = Math.min(y + found.shift, bottom - min);
        h = bottom - y;
      } else {
        h = Math.max(min, h + found.shift);
      }
      hits.push(...found.hits);
    }
  }
  const final = { x, y, w, h };
  return { rect: final, guides: guidesFor(final, hits) };
}

/** Screen-space pull distance expressed in GUI pixels: constant on screen, tighter as you zoom in. */
export function snapThreshold(zoom: number, screenPixels = 6): number {
  return Math.max(0.5, screenPixels / zoom);
}
