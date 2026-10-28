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
  for (let j = 0; j < dh; j++) {
    const ty = dy + j;
    if (ty < 0 || ty >= target.height) continue;
    const sy = Math.floor(((2 * j + 1) * source.height) / (2 * dh));
    for (let i = 0; i < dw; i++) {
      const tx = dx + i;
      if (tx < 0 || tx >= target.width) continue;
      const sx = Math.floor(((2 * i + 1) * source.width) / (2 * dw));
      target.blend(tx, ty, source.get(sx, sy));
    }
  }
}

/** A paint layer's bitmap: copied 1:1 at the origin, never scaled; excess is clipped. */
export function blitPaint(target: ArgbCanvas, source: ArgbCanvas): void {
  blitScaled(target, source, 0, 0, source.width, source.height);
}
