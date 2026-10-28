/**
 * GUI colors (gui.md section 4, fixtures/gui/raster/README.md). A color is one
 * 32-bit ARGB integer, straight (non-premultiplied) alpha, exactly the shape
 * of a Java `int` pixel, so the TS studio renderer and the Java compiler hold
 * the same numbers. Alpha 0 is always stored as 0: "transparent" has exactly
 * one representation, which keeps pixel-for-pixel comparison honest.
 *
 * On disk and on the wire a color is a string: six hex digits (`RRGGBB`,
 * opaque) or eight (`RRGGBBAA`, CSS order), led by a hash sign.
 */

export type Argb = number;

export const TRANSPARENT: Argb = 0;

export function argb(a: number, r: number, g: number, b: number): Argb {
  return a === 0 ? 0 : (((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255)) >>> 0;
}

export function alphaOf(color: Argb): number {
  return color >>> 24;
}

export function redOf(color: Argb): number {
  return (color >>> 16) & 255;
}

export function greenOf(color: Argb): number {
  return (color >>> 8) & 255;
}

export function blueOf(color: Argb): number {
  return color & 255;
}

const HEX = /^#?([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$/;

/** `RRGGBB` / `RRGGBBAA` (hash optional) to ARGB; null when it is neither. */
export function parseColor(text: string): Argb | null {
  const match = HEX.exec(text.trim());
  if (!match) return null;
  const rgb = parseInt(match[1]!, 16);
  const a = match[2] === undefined ? 255 : parseInt(match[2], 16);
  return argb(a, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
}

function pair(value: number): string {
  return value.toString(16).padStart(2, '0').toUpperCase();
}

/** ARGB to the stored string: six digits when opaque, eight otherwise. */
export function formatColor(color: Argb): string {
  const a = alphaOf(color);
  const head = String.fromCharCode(35);
  const body = pair(redOf(color)) + pair(greenOf(color)) + pair(blueOf(color));
  return a === 255 ? head + body : head + body + pair(a);
}

/** The same color as CSS: eight-digit hex is valid there, so the stored form is the CSS form. */
export function cssColor(color: Argb): string {
  return formatColor(color);
}

/** `<input type=color>` wants exactly six digits, lowercase, no alpha. */
export function pickerValue(color: Argb): string {
  return formatColor(argb(255, redOf(color), greenOf(color), blueOf(color))).toLowerCase();
}
