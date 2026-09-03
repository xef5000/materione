/**
 * The pixel engine behind the texture editor. Everything here is pure and
 * synchronous so the drawing rules can be pinned by tests rather than by
 * clicking around: the canvas component only translates pointer events into
 * these calls and paints the result.
 *
 * A bitmap is straight (non-premultiplied) RGBA, row-major, exactly like
 * `ImageData` — so handing one to or taking one from a canvas is a copy, not a
 * conversion.
 */

export interface Bitmap {
  width: number;
  height: number;
  data: Uint8ClampedArray;
}

/** Straight RGBA, each channel 0..255. */
export type Color = readonly [number, number, number, number];

export const TRANSPARENT: Color = [0, 0, 0, 0];

export function createBitmap(width: number, height: number): Bitmap {
  return { width, height, data: new Uint8ClampedArray(width * height * 4) };
}

export function cloneBitmap(bitmap: Bitmap): Bitmap {
  return {
    width: bitmap.width,
    height: bitmap.height,
    data: new Uint8ClampedArray(bitmap.data),
  };
}

export function inside(bitmap: Bitmap, x: number, y: number): boolean {
  return x >= 0 && y >= 0 && x < bitmap.width && y < bitmap.height;
}

export function getPixel(bitmap: Bitmap, x: number, y: number): Color {
  if (!inside(bitmap, x, y)) return TRANSPARENT;
  const offset = (y * bitmap.width + x) * 4;
  return [
    bitmap.data[offset]!,
    bitmap.data[offset + 1]!,
    bitmap.data[offset + 2]!,
    bitmap.data[offset + 3]!,
  ];
}

/** Writes one pixel. Out-of-bounds writes are dropped, not wrapped. */
export function setPixel(bitmap: Bitmap, x: number, y: number, color: Color): void {
  if (!inside(bitmap, x, y)) return;
  const offset = (y * bitmap.width + x) * 4;
  bitmap.data[offset] = color[0];
  bitmap.data[offset + 1] = color[1];
  bitmap.data[offset + 2] = color[2];
  bitmap.data[offset + 3] = color[3];
}

export function samePixel(a: Color, b: Color): boolean {
  return a[0] === b[0] && a[1] === b[1] && a[2] === b[2] && a[3] === b[3];
}

/**
 * Draws with a square brush of `size` pixels, and mirrors across the vertical
 * axis when asked — the symmetry toggle a Minecraft texture almost always
 * wants.
 */
export interface Brush {
  color: Color;
  size: number;
  mirrorX: boolean;
}

export function stamp(bitmap: Bitmap, x: number, y: number, brush: Brush): void {
  const reach = Math.max(1, Math.floor(brush.size));
  const start = Math.floor((reach - 1) / 2);
  for (let dy = 0; dy < reach; dy++) {
    for (let dx = 0; dx < reach; dx++) {
      const px = x - start + dx;
      const py = y - start + dy;
      setPixel(bitmap, px, py, brush.color);
      if (brush.mirrorX) {
        setPixel(bitmap, bitmap.width - 1 - px, py, brush.color);
      }
    }
  }
}

/** Bresenham, so a fast drag paints a continuous stroke instead of dots. */
export function line(
  bitmap: Bitmap,
  x0: number,
  y0: number,
  x1: number,
  y1: number,
  brush: Brush,
): void {
  let x = x0;
  let y = y0;
  const dx = Math.abs(x1 - x0);
  const dy = -Math.abs(y1 - y0);
  const stepX = x0 < x1 ? 1 : -1;
  const stepY = y0 < y1 ? 1 : -1;
  let error = dx + dy;
  for (;;) {
    stamp(bitmap, x, y, brush);
    if (x === x1 && y === y1) return;
    const doubled = 2 * error;
    if (doubled >= dy) {
      error += dy;
      x += stepX;
    }
    if (doubled <= dx) {
      error += dx;
      y += stepY;
    }
  }
}

export function rectangle(
  bitmap: Bitmap,
  x0: number,
  y0: number,
  x1: number,
  y1: number,
  brush: Brush,
  filled = false,
): void {
  const left = Math.min(x0, x1);
  const right = Math.max(x0, x1);
  const top = Math.min(y0, y1);
  const bottom = Math.max(y0, y1);
  for (let y = top; y <= bottom; y++) {
    for (let x = left; x <= right; x++) {
      const edge = x === left || x === right || y === top || y === bottom;
      if (filled || edge) stamp(bitmap, x, y, brush);
    }
  }
}

/**
 * Flood fill over exactly-equal pixels, four-connected. Exact equality is the
 * right rule for pixel art: "the same colour" means the same colour, and a
 * tolerance slider only ever produces surprises at 16x16.
 */
export function fill(bitmap: Bitmap, x: number, y: number, color: Color): void {
  if (!inside(bitmap, x, y)) return;
  const target = getPixel(bitmap, x, y);
  if (samePixel(target, color)) return;
  const stack: number[] = [x, y];
  while (stack.length > 0) {
    const py = stack.pop()!;
    const px = stack.pop()!;
    if (!inside(bitmap, px, py)) continue;
    if (!samePixel(getPixel(bitmap, px, py), target)) continue;
    setPixel(bitmap, px, py, color);
    stack.push(px + 1, py, px - 1, py, px, py + 1, px, py - 1);
  }
}

/** Replaces every pixel of one colour — "recolour this material" in one click. */
export function replaceColor(bitmap: Bitmap, from: Color, to: Color): void {
  for (let y = 0; y < bitmap.height; y++) {
    for (let x = 0; x < bitmap.width; x++) {
      if (samePixel(getPixel(bitmap, x, y), from)) setPixel(bitmap, x, y, to);
    }
  }
}

/** Nearest-neighbour resize; growing keeps the art crisp, shrinking crops cleanly. */
export function resize(bitmap: Bitmap, width: number, height: number): Bitmap {
  const next = createBitmap(width, height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const sx = Math.floor((x * bitmap.width) / width);
      const sy = Math.floor((y * bitmap.height) / height);
      setPixel(next, x, y, getPixel(bitmap, sx, sy));
    }
  }
  return next;
}

/** Canvas coordinates → pixel coordinates, given zoom and pan. */
export function toPixel(
  point: { x: number; y: number },
  view: { zoom: number; panX: number; panY: number },
): { x: number; y: number } {
  return {
    x: Math.floor((point.x - view.panX) / view.zoom),
    y: Math.floor((point.y - view.panY) / view.zoom),
  };
}

export function hexToColor(hex: string, alpha = 255): Color {
  const clean = hex.trim().replace(/^#/, '');
  const full =
    clean.length === 3
      ? clean
          .split('')
          .map((c) => c + c)
          .join('')
      : clean;
  if (!/^[0-9a-f]{6}([0-9a-f]{2})?$/i.test(full)) return [0, 0, 0, alpha];
  const value = parseInt(full.slice(0, 6), 16);
  const a = full.length === 8 ? parseInt(full.slice(6, 8), 16) : alpha;
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255, a];
}

export function colorToHex(color: Color): string {
  const part = (value: number) => value.toString(16).padStart(2, '0');
  return `#${part(color[0])}${part(color[1])}${part(color[2])}`;
}

/**
 * Bounded undo/redo. Bounded on purpose: a 64x64 texture is 16 KB a step, and
 * an editor that quietly eats memory while someone doodles is a bug.
 */
export class History {
  private past: Bitmap[] = [];
  private future: Bitmap[] = [];

  constructor(private readonly limit = 40) {}

  /** Call *before* mutating, with the state as it is now. */
  push(bitmap: Bitmap): void {
    this.past.push(cloneBitmap(bitmap));
    if (this.past.length > this.limit) this.past.shift();
    this.future = [];
  }

  get canUndo(): boolean {
    return this.past.length > 0;
  }

  get canRedo(): boolean {
    return this.future.length > 0;
  }

  undo(current: Bitmap): Bitmap | null {
    const previous = this.past.pop();
    if (!previous) return null;
    this.future.push(cloneBitmap(current));
    return previous;
  }

  redo(current: Bitmap): Bitmap | null {
    const next = this.future.pop();
    if (!next) return null;
    this.past.push(cloneBitmap(current));
    return next;
  }

  clear(): void {
    this.past = [];
    this.future = [];
  }
}

/** The colours actually used, most-used first — a palette the art already has. */
export function usedColors(bitmap: Bitmap, limit = 16): Color[] {
  const counts = new Map<string, { color: Color; count: number }>();
  for (let y = 0; y < bitmap.height; y++) {
    for (let x = 0; x < bitmap.width; x++) {
      const color = getPixel(bitmap, x, y);
      if (color[3] === 0) continue;
      const key = color.join(',');
      const entry = counts.get(key);
      if (entry) entry.count += 1;
      else counts.set(key, { color, count: 1 });
    }
  }
  return [...counts.values()]
    .sort((a, b) => b.count - a.count)
    .slice(0, limit)
    .map((entry) => entry.color);
}
