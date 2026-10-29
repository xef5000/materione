/**
 * Paint tools for `paint` layers (gui.md section 6). The drawing rules are the pixel editor's
 * (`pixels.ts`: stamp, Bresenham line, exact-match flood fill), run on a working RGBA copy of the
 * layer's `ArgbCanvas`; what a tool changed comes back as a `PixelPatch` (the touched pixels,
 * before and after), which is the payload of one undoable command. A stroke is one patch however
 * many pointer events built it.
 *
 * Pure: no DOM, no Svelte.
 */
import {
  brushCovers,
  createBitmap,
  fill as floodFill,
  line,
  stamp,
  type Bitmap,
  type Brush,
  type Color,
} from '../pixels';
import { ArgbCanvas } from './raster/canvas';
import { alphaOf, argb, blueOf, greenOf, redOf, TRANSPARENT, type Argb } from './raster/color';

export const MIN_BRUSH = 1;
export const MAX_BRUSH = 8;
export type BrushShape = 'square' | 'round';

export interface BrushSettings {
  size: number;
  shape: BrushShape;
}

export function clampBrush(size: number): number {
  return Math.min(MAX_BRUSH, Math.max(MIN_BRUSH, Math.round(size)));
}

export function toColor(color: Argb): Color {
  return [redOf(color), greenOf(color), blueOf(color), alphaOf(color)];
}

export function fromColor(color: Color): Argb {
  return argb(color[3], color[0], color[1], color[2]);
}

/** The pixels a stamp at (x, y) paints, relative to the canvas: the exact footprint `pixels.stamp` writes. */
export function footprint(x: number, y: number, settings: BrushSettings): { x: number; y: number }[] {
  const reach = clampBrush(settings.size);
  const start = Math.floor((reach - 1) / 2);
  const cells: { x: number; y: number }[] = [];
  for (let dy = 0; dy < reach; dy++) {
    for (let dx = 0; dx < reach; dx++) {
      if (brushCovers(reach, settings.shape, dx, dy)) cells.push({ x: x - start + dx, y: y - start + dy });
    }
  }
  return cells;
}

/**
 * The outline of a set of pixel cells as SVG path data in pixel units (`M x y h w`...): every cell
 * edge that borders a cell outside the set. The canvas strokes it as the brush ghost.
 */
export function outlinePath(cells: readonly { x: number; y: number }[]): string {
  const key = (x: number, y: number): string => `${x},${y}`;
  const set = new Set(cells.map((cell) => key(cell.x, cell.y)));
  const parts: string[] = [];
  for (const { x, y } of cells) {
    if (!set.has(key(x, y - 1))) parts.push(`M${x} ${y}h1`);
    if (!set.has(key(x, y + 1))) parts.push(`M${x} ${y + 1}h1`);
    if (!set.has(key(x - 1, y))) parts.push(`M${x} ${y}v1`);
    if (!set.has(key(x + 1, y))) parts.push(`M${x + 1} ${y}v1`);
  }
  return parts.join('');
}

/** An `ArgbCanvas` as the pixel engine's straight RGBA bitmap (a copy). */
export function toBitmap(canvas: ArgbCanvas): Bitmap {
  const bitmap = createBitmap(canvas.width, canvas.height);
  bitmap.data.set(canvas.toRgba());
  return bitmap;
}

/** Writes a rectangle of the working bitmap back into the canvas (alpha 0 is stored as 0). */
function writeBack(canvas: ArgbCanvas, bitmap: Bitmap, left: number, top: number, right: number, bottom: number): void {
  const x0 = Math.max(0, left);
  const y0 = Math.max(0, top);
  const x1 = Math.min(canvas.width - 1, right);
  const y1 = Math.min(canvas.height - 1, bottom);
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const offset = (y * bitmap.width + x) * 4;
      canvas.pixels[y * canvas.width + x] = argb(
        bitmap.data[offset + 3]!,
        bitmap.data[offset]!,
        bitmap.data[offset + 1]!,
        bitmap.data[offset + 2]!,
      );
    }
  }
}

/** What one tool use changed: parallel arrays of pixel index, value before and value after. */
export interface PixelPatch {
  reference: string;
  indices: Uint32Array;
  before: Uint32Array;
  after: Uint32Array;
}

export function patchSize(patch: PixelPatch): number {
  return patch.indices.length;
}

/** Writes the patch's `after` (redo) or `before` (undo) values into the canvas. Indices past the canvas are ignored. */
export function applyPatch(canvas: ArgbCanvas, patch: PixelPatch, direction: 'after' | 'before'): void {
  const values = patch[direction];
  for (let k = 0; k < patch.indices.length; k++) {
    const index = patch.indices[k]!;
    if (index < canvas.pixels.length) canvas.pixels[index] = values[k]!;
  }
}

/** The patch between a snapshot and the canvas as it is now, over a bounding box; null when nothing differs. */
export function diffPatch(
  reference: string,
  snapshot: Uint32Array,
  canvas: ArgbCanvas,
  box: { left: number; top: number; right: number; bottom: number },
): PixelPatch | null {
  const indices: number[] = [];
  const x0 = Math.max(0, box.left);
  const y0 = Math.max(0, box.top);
  const x1 = Math.min(canvas.width - 1, box.right);
  const y1 = Math.min(canvas.height - 1, box.bottom);
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const index = y * canvas.width + x;
      if (snapshot[index] !== canvas.pixels[index]) indices.push(index);
    }
  }
  if (!indices.length) return null;
  return {
    reference,
    indices: Uint32Array.from(indices),
    before: Uint32Array.from(indices, (index) => snapshot[index]!),
    after: Uint32Array.from(indices, (index) => canvas.pixels[index]!),
  };
}

/** The patch between a snapshot and the whole canvas; null when they are the same. */
export function diffAll(reference: string, snapshot: Uint32Array, canvas: ArgbCanvas): PixelPatch | null {
  return diffPatch(reference, snapshot, canvas, { left: 0, top: 0, right: canvas.width - 1, bottom: canvas.height - 1 });
}

/**
 * One brush or eraser stroke over a canvas. `begin` stamps the first pixel, every `to` joins the
 * pointer's new position to the last one with a Bresenham line (so a fast drag, or a coalesced
 * pointer event, never leaves gaps), and `end` returns the stroke as one patch. The canvas shows
 * the stroke live; the history only hears about it at the end.
 */
export class Stroke {
  readonly #bitmap: Bitmap;
  readonly #snapshot: Uint32Array;
  readonly #brush: Brush;
  #last: { x: number; y: number };
  #box: { left: number; top: number; right: number; bottom: number };

  constructor(
    readonly canvas: ArgbCanvas,
    readonly reference: string,
    color: Argb,
    settings: BrushSettings,
    start: { x: number; y: number },
    /** Shift-click: a straight segment from the previous stroke's end. */
    from?: { x: number; y: number },
  ) {
    this.#bitmap = toBitmap(canvas);
    this.#snapshot = canvas.pixels.slice();
    const size = clampBrush(settings.size);
    this.#brush = { color: toColor(color), size, mirrorX: false, shape: settings.shape };
    this.#last = from ?? start;
    this.#box = { left: start.x, top: start.y, right: start.x, bottom: start.y };
    this.to(start);
  }

  /** Draws from the last position to `point`; returns whether anything may have changed. */
  to(point: { x: number; y: number }): boolean {
    if (this.touched && point.x === this.#last.x && point.y === this.#last.y) return false;
    line(this.#bitmap, this.#last.x, this.#last.y, point.x, point.y, this.#brush);
    const pad = this.#brush.size;
    const left = Math.min(this.#last.x, point.x) - pad;
    const right = Math.max(this.#last.x, point.x) + pad;
    const top = Math.min(this.#last.y, point.y) - pad;
    const bottom = Math.max(this.#last.y, point.y) + pad;
    writeBack(this.canvas, this.#bitmap, left, top, right, bottom);
    this.#box = {
      left: Math.min(this.#box.left, left),
      top: Math.min(this.#box.top, top),
      right: Math.max(this.#box.right, right),
      bottom: Math.max(this.#box.bottom, bottom),
    };
    this.#last = point;
    this.touched = true;
    return true;
  }

  /** Whether the stroke has stamped at least once (the constructor stamps the first pixel). */
  touched = false;

  get last(): { x: number; y: number } {
    return this.#last;
  }

  end(): PixelPatch | null {
    return diffPatch(this.reference, this.#snapshot, this.canvas, this.#box);
  }
}

/** Erasing is painting with transparent: a replace, not a blend. */
export const ERASER: Argb = TRANSPARENT;

/** Flood fill (contiguous, exact colour match, tolerance 0) from (x, y); the patch, or null when nothing changes. */
export function fillPatch(
  canvas: ArgbCanvas,
  reference: string,
  x: number,
  y: number,
  color: Argb,
): PixelPatch | null {
  if (!canvas.inside(x, y)) return null;
  const bitmap = toBitmap(canvas);
  const snapshot = canvas.pixels.slice();
  floodFill(bitmap, x, y, toColor(color));
  writeBack(canvas, bitmap, 0, 0, canvas.width - 1, canvas.height - 1);
  return diffPatch(reference, snapshot, canvas, { left: 0, top: 0, right: canvas.width - 1, bottom: canvas.height - 1 });
}

/** A copy of the canvas on a new size: the overlap is kept, the rest is transparent. */
export function resizedCanvas(canvas: ArgbCanvas, width: number, height: number): ArgbCanvas {
  const out = new ArgbCanvas(width, height);
  for (let y = 0; y < Math.min(height, canvas.height); y++) {
    for (let x = 0; x < Math.min(width, canvas.width); x++) out.pixels[y * width + x] = canvas.pixels[y * canvas.width + x]!;
  }
  return out;
}

/** The most recent colours first, no duplicates, bounded. */
export function pushRecent(recent: readonly Argb[], color: Argb, limit = 12): Argb[] {
  if (alphaOf(color) === 0) return [...recent];
  return [color, ...recent.filter((entry) => entry !== color)].slice(0, limit);
}
