/**
 * Paint layers own their geometry (gui.md section 11.2): a `paint` layer carries `x`, `y`, `width` and
 * `height` like an `image`, its PNG holds exactly those pixels, and no PNG is canvas-sized any more.
 *
 * While you edit, the layer grows as strokes leave it (a margin of transparent pixels may remain, for
 * instance after erasing); on save every paint layer is trimmed to the box of its painted pixels, so the
 * PNG holds no transparent margin. A layer with no painted pixel at all saves no PNG: it is written as
 * `width: 0`, `height: 0` and the compiler draws nothing for it (gui.md section 10, issue 184, decision 8).
 *
 * The bitmap is the truth for the size: the layer is drawn 1:1 at (`x`, `y`) with the bitmap's own size,
 * exactly as the compiler blits the PNG; the document's `width`/`height` only mirror it (and stand in for it
 * while the bitmap has not loaded yet).
 *
 * Pure: no DOM, no Svelte.
 */
import type { LayerPatch } from './commands';
import type { GuiDocument, GuiLayer } from './model';
import { ArgbCanvas, type Rect } from './raster/canvas';
import { alphaOf } from './raster/color';
import { bareType, whole } from './raster/flatten';

export function isPaintLayer(layer: GuiLayer): boolean {
  return bareType(layer) === 'paint';
}

/** The texture reference a paint layer owns, or '' when it has none. */
export function paintReferenceOf(layer: GuiLayer): string {
  return typeof layer.image === 'string' ? layer.image : '';
}

/**
 * Where a paint layer's pixels are in GUI pixels: its `x`, `y` (0 when absent: a canvas-sized layer from before
 * the open canvas) and the bitmap's own size; the document's `width`/`height` when the bitmap is not loaded.
 */
export function paintRect(layer: GuiLayer, bitmap: ArgbCanvas | null | undefined): Rect {
  const x = whole(layer.x, 0);
  const y = whole(layer.y, 0);
  if (bitmap) return { x, y, w: bitmap.width, h: bitmap.height };
  return { x, y, w: Math.max(0, whole(layer.width, 0)), h: Math.max(0, whole(layer.height, 0)) };
}

/** The layer fields that mirror a rectangle. */
export function geometryFields(rect: Rect): LayerPatch {
  return { x: rect.x, y: rect.y, width: rect.w, height: rect.h };
}

/** The box of the pixels with alpha above 0, in the canvas's local coordinates; null when it has none. */
export function inkBounds(canvas: ArgbCanvas): Rect | null {
  let left = canvas.width;
  let top = canvas.height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < canvas.height; y++) {
    for (let x = 0; x < canvas.width; x++) {
      if (alphaOf(canvas.pixels[y * canvas.width + x]!) === 0) continue;
      if (x < left) left = x;
      if (x > right) right = x;
      if (y < top) top = y;
      if (y > bottom) bottom = y;
    }
  }
  return right < 0 ? null : { x: left, y: top, w: right - left + 1, h: bottom - top + 1 };
}

/**
 * A copy of a bitmap on a new rectangle: `from` is where the bitmap sits in GUI pixels, `to` where the copy sits. The
 * overlap is kept and the rest is transparent (an empty `to` gives an empty bitmap).
 */
export function reshape(canvas: ArgbCanvas, from: Rect, to: Rect): ArgbCanvas {
  const out = new ArgbCanvas(Math.max(0, to.w), Math.max(0, to.h));
  const left = Math.max(from.x, to.x);
  const top = Math.max(from.y, to.y);
  const right = Math.min(from.x + canvas.width, to.x + out.width);
  const bottom = Math.min(from.y + canvas.height, to.y + out.height);
  for (let y = top; y < bottom; y++) {
    for (let x = left; x < right; x++) {
      out.pixels[(y - to.y) * out.width + (x - to.x)] = canvas.pixels[(y - from.y) * canvas.width + (x - from.x)]!;
    }
  }
  return out;
}

export interface TrimmedPaint {
  layerId: string;
  reference: string;
  /** Where the trimmed layer sits (w = h = 0 when nothing is painted). */
  rect: Rect;
  /** The pixels of the trimmed layer; a 0 x 0 canvas when nothing is painted. */
  canvas: ArgbCanvas;
  /** The PNG has to be written: there are pixels, and they differ from the stored file (edited, or had a margin). */
  needsPng: boolean;
}

export interface TrimResult {
  doc: GuiDocument;
  layers: TrimmedPaint[];
}

/**
 * Every paint layer trimmed to its painted bounds, ready to save. `bitmapOf` answers for a texture reference;
 * a layer whose bitmap is not there (still loading, or its file is missing) is left exactly as it is, so a failed
 * read can never empty a layer. A layer that was never painted has a 0 x 0 bitmap. `edited` says which textures
 * changed in this session.
 */
export function trimPaintLayers(
  doc: GuiDocument,
  bitmapOf: (reference: string) => ArgbCanvas | null | undefined,
  edited: (reference: string) => boolean,
): TrimResult {
  const layers: TrimmedPaint[] = [];
  const next = doc.layers.map((layer) => {
    const reference = paintReferenceOf(layer);
    if (!isPaintLayer(layer) || !reference) return layer;
    const bitmap = bitmapOf(reference);
    if (!bitmap) return layer;
    const stored = paintRect(layer, bitmap);
    const ink = inkBounds(bitmap);
    const rect: Rect = ink
      ? { x: stored.x + ink.x, y: stored.y + ink.y, w: ink.w, h: ink.h }
      : { x: stored.x, y: stored.y, w: 0, h: 0 };
    const canvas = ink ? reshape(bitmap, stored, rect) : new ArgbCanvas(0, 0);
    const margin = bitmap.width !== rect.w || bitmap.height !== rect.h;
    layers.push({ layerId: layer.id, reference, rect, canvas, needsPng: !!ink && (margin || edited(reference)) });
    const fields = geometryFields(rect);
    const same = Object.entries(fields).every(([key, value]) => layer[key] === value);
    return same ? layer : ({ ...layer, ...fields } as GuiLayer);
  });
  return { doc: next.every((layer, index) => layer === doc.layers[index]) ? doc : { ...doc, layers: next }, layers };
}
