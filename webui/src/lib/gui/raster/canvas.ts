/**
 * The ARGB raster the studio and the Java compiler both draw on (gui.md
 * section 4). Everything here is integer math with one rounding rule
 * (round half up), because the studio preview is not an approximation of the
 * compiled background -- it is the same function. The exact rules are pinned
 * by the shared vectors in fixtures/gui/raster/ (see its README).
 */
import { alphaOf, argb, blueOf, greenOf, redOf, type Argb } from './color';

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Layer opacity (0..100) applied to a color's alpha: round half up. */
export function scaleAlpha(color: Argb, opacity: number): Argb {
  if (opacity >= 100) return color;
  const a = alphaOf(color);
  const scaled = Math.floor((a * Math.max(0, opacity) + 50) / 100);
  return scaled === 0 ? 0 : argb(scaled, redOf(color), greenOf(color), blueOf(color));
}

/**
 * Straight-alpha source-over of one pixel, integers only:
 *
 *   den   = sa*255 + da*(255 - sa)                 (= outA * 255, unrounded)
 *   outA  = floor((den + 127) / 255)
 *   outC  = floor((2*num + den) / (2*den)),  num = sc*sa*255 + dc*da*(255 - sa)
 *
 * Fully opaque or fully transparent sources short-circuit to an exact copy
 * or no-op; the formula agrees with both, the shortcut is only speed.
 */
export function blendOver(dst: Argb, src: Argb): Argb {
  const sa = alphaOf(src);
  if (sa === 0) return dst;
  if (sa === 255) return src;
  const da = alphaOf(dst);
  if (da === 0) return src;
  const keep = 255 - sa;
  const den = sa * 255 + da * keep;
  const outA = Math.floor((den + 127) / 255);
  const channel = (s: number, d: number): number =>
    Math.floor((2 * (s * sa * 255 + d * da * keep) + den) / (2 * den));
  return argb(
    outA,
    channel(redOf(src), redOf(dst)),
    channel(greenOf(src), greenOf(dst)),
    channel(blueOf(src), blueOf(dst)),
  );
}

export class ArgbCanvas {
  readonly pixels: Uint32Array;

  constructor(
    readonly width: number,
    readonly height: number,
  ) {
    this.pixels = new Uint32Array(width * height);
  }

  inside(x: number, y: number): boolean {
    return x >= 0 && y >= 0 && x < this.width && y < this.height;
  }

  get(x: number, y: number): Argb {
    return this.inside(x, y) ? this.pixels[y * this.width + x]! : 0;
  }

  /** Replaces one pixel (no blending). Out-of-bounds writes are dropped. */
  set(x: number, y: number, color: Argb): void {
    if (this.inside(x, y)) this.pixels[y * this.width + x] = alphaOf(color) === 0 ? 0 : color >>> 0;
  }

  /** Composites one pixel over what is there (source-over). */
  blend(x: number, y: number, color: Argb): void {
    if (!this.inside(x, y)) return;
    const index = y * this.width + x;
    this.pixels[index] = blendOver(this.pixels[index]!, color) >>> 0;
  }

  fillRect(x: number, y: number, w: number, h: number, color: Argb): void {
    for (let py = Math.max(0, y); py < Math.min(this.height, y + h); py++) {
      for (let px = Math.max(0, x); px < Math.min(this.width, x + w); px++) {
        this.blend(px, py, color);
      }
    }
  }

  /**
   * Composites `layer` (same coordinate space) over this canvas with a layer
   * opacity: every source pixel's alpha is scaled (round half up) and blended.
   */
  compositeLayer(layer: ArgbCanvas, opacity: number): void {
    const count = Math.min(this.pixels.length, layer.pixels.length);
    for (let index = 0; index < count; index++) {
      const source = layer.pixels[index]!;
      if (source === 0) continue;
      this.pixels[index] = blendOver(this.pixels[index]!, scaleAlpha(source, opacity)) >>> 0;
    }
  }

  clone(): ArgbCanvas {
    const copy = new ArgbCanvas(this.width, this.height);
    copy.pixels.set(this.pixels);
    return copy;
  }

  equals(other: ArgbCanvas): boolean {
    if (this.width !== other.width || this.height !== other.height) return false;
    for (let index = 0; index < this.pixels.length; index++) {
      if (this.pixels[index] !== other.pixels[index]) return false;
    }
    return true;
  }

  /** Straight RGBA bytes, row-major: the `ImageData` / `pixels.ts` `Bitmap` layout. */
  toRgba(): Uint8ClampedArray {
    const out = new Uint8ClampedArray(this.pixels.length * 4);
    for (let index = 0; index < this.pixels.length; index++) {
      const color = this.pixels[index]!;
      out[index * 4] = redOf(color);
      out[index * 4 + 1] = greenOf(color);
      out[index * 4 + 2] = blueOf(color);
      out[index * 4 + 3] = alphaOf(color);
    }
    return out;
  }

  static fromRgba(width: number, height: number, data: ArrayLike<number>): ArgbCanvas {
    const canvas = new ArgbCanvas(width, height);
    for (let index = 0; index < canvas.pixels.length; index++) {
      canvas.pixels[index] = argb(
        data[index * 4 + 3]!,
        data[index * 4]!,
        data[index * 4 + 1]!,
        data[index * 4 + 2]!,
      );
    }
    return canvas;
  }
}
