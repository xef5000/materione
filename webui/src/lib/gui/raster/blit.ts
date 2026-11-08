/**
 * Image blits (gui.md section 4): nearest-neighbour scaling and the 1:1 paint
 * layer copy. Both composite source-over onto the target, one pixel at a time.
 */
import type { ArgbCanvas } from './canvas';

/**
 * Draws `source` stretched to the destination rectangle (dx, dy, dw, dh).
 * Destination pixel (i, j) samples source pixel
 * `(floor((2i+1) * sw / (2dw)), floor((2j+1) * sh / (2dh)))`: the sample point
 * is the pixel center, so integer upscales replicate pixels evenly and
 * downscales are symmetric. Destination pixels outside the target are clipped;
 * the index math never sees the clip.
 */
export function blitScaled(
  target: ArgbCanvas,
  source: ArgbCanvas,
  dx: number,
  dy: number,
  dw: number,
  dh: number,
): void {
  if (dw <= 0 || dh <= 0 || source.width <= 0 || source.height <= 0) return;
  // only the part of the destination that lands on the target is visited
  const j0 = Math.max(0, target.originY - dy);
  const j1 = Math.min(dh, target.originY + target.height - dy);
  const i0 = Math.max(0, target.originX - dx);
  const i1 = Math.min(dw, target.originX + target.width - dx);
  for (let j = j0; j < j1; j++) {
    const sy = Math.floor(((2 * j + 1) * source.height) / (2 * dh));
    for (let i = i0; i < i1; i++) {
      const sx = Math.floor(((2 * i + 1) * source.width) / (2 * dw));
      target.blend(dx + i, dy + j, source.get(sx, sy));
    }
  }
}

/** A paint layer's bitmap: copied 1:1 with its top-left at (x, y), never scaled; excess is clipped. */
export function blitPaint(target: ArgbCanvas, source: ArgbCanvas, x = 0, y = 0): void {
  blitScaled(target, source, x, y, source.width, source.height);
}
