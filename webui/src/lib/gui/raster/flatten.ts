/**
 * Raster flattening (gui.md section 4, step 1): the layers whose render target
 * is `RASTER` are drawn bottom to top onto one canvas-sized ARGB canvas. This
 * is the function the studio previews with and the Java compiler is tested
 * against (fixtures/gui/raster/); it knows nothing about Svelte or the DOM.
 *
 * Each layer is painted onto its own transparent canvas and then composited
 * onto the accumulator with the layer's opacity, so `opacity` is a true group
 * opacity and a layer's own pixels never blend with each other twice.
 *
 * A component that wants to render into the raster registers a painter in
 * `RASTER_PAINTERS`; the built-ins below are panel, slots, image, paint and
 * button. `text` and `item` are not raster components (their targets are the
 * title and the container slots) and are skipped here.
 */
import { paintInset, paintRaised, BUTTON_EDGE, PANEL_EDGE, type BevelStyle } from './bevel';
import { blitPaint, blitScaled } from './blit';
import { ArgbCanvas } from './canvas';
import { parseColor, type Argb } from './color';
import { cellsOf, wellRect, type SlotRegion } from '../chest';

export type Layer = Record<string, unknown> & { id?: unknown; type?: unknown };

/** What a raster layer can read besides its own fields. */
export interface RasterScene {
  width: number;
  height: number;
  rows: number;
  /** The decoded bitmap behind an `image:` reference, or null when it is unavailable. */
  image(reference: string): ArgbCanvas | null;
}

export type RasterPainter = (layer: Layer, scene: RasterScene, out: ArgbCanvas) => void;

/** Vanilla colors for the bevel styles; a `style` block overrides field by field. */
export const RAISED_DEFAULT: BevelStyle = {
  fill: 0xffc6c6c6,
  highlight: 0xffffffff,
  shadow: 0xff555555,
  outline: 0xff000000,
};

export const SLOT_DEFAULT: BevelStyle = {
  fill: 0xff8b8b8b,
  highlight: 0xffffffff,
  shadow: 0xff373737,
  outline: 0xff000000,
};

/** A layer's component name without the built-in namespace (`materione:panel` is `panel`). */
export function bareType(layer: Layer): string {
  const type = typeof layer.type === 'string' ? layer.type : '';
  return type.startsWith('materione:') ? type.slice('materione:'.length) : type;
}

export function whole(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.floor(value) : fallback;
}

export function styleOf(layer: Layer, base: BevelStyle): BevelStyle {
  const raw = layer.style;
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return base;
  const block = raw as Record<string, unknown>;
  const pick = (key: keyof BevelStyle): Argb => {
    const value = block[key];
    return (typeof value === 'string' ? parseColor(value) : null) ?? base[key];
  };
  return { fill: pick('fill'), highlight: pick('highlight'), shadow: pick('shadow'), outline: pick('outline') };
}

export function opacityOf(layer: Layer): number {
  return Math.min(100, Math.max(0, whole(layer.opacity, 100)));
}

export function regionOf(layer: Layer): SlotRegion {
  return layer.region === 'player' || layer.region === 'all' ? layer.region : 'container';
}

function rectOf(layer: Layer): { x: number; y: number; w: number; h: number } {
  return {
    x: whole(layer.x, 0),
    y: whole(layer.y, 0),
    w: Math.max(0, whole(layer.width, 0)),
    h: Math.max(0, whole(layer.height, 0)),
  };
}

export const RASTER_PAINTERS: Record<string, RasterPainter> = {
  panel(layer, _scene, out) {
    const { x, y, w, h } = rectOf(layer);
    paintRaised(out, x, y, w, h, styleOf(layer, RAISED_DEFAULT), PANEL_EDGE);
  },
  button(layer, _scene, out) {
    const { x, y, w, h } = rectOf(layer);
    paintRaised(out, x, y, w, h, styleOf(layer, RAISED_DEFAULT), BUTTON_EDGE);
  },
  slots(layer, scene, out) {
    const style = styleOf(layer, SLOT_DEFAULT);
    for (const cell of cellsOf(scene.rows, regionOf(layer))) {
      const well = wellRect(cell);
      paintInset(out, well.x, well.y, well.w, well.h, style);
    }
  },
  image(layer, scene, out) {
    const reference = typeof layer.image === 'string' ? layer.image : '';
    const source = reference ? scene.image(reference) : null;
    if (!source) return;
    const { x, y } = rectOf(layer);
    const w = whole(layer.width, source.width);
    const h = whole(layer.height, source.height);
    blitScaled(out, source, x, y, w, h);
  },
  paint(layer, scene, out) {
    const reference = typeof layer.image === 'string' ? layer.image : '';
    const source = reference ? scene.image(reference) : null;
    if (source) blitPaint(out, source);
  },
};

export function isVisible(layer: Layer): boolean {
  return layer.visible !== false;
}

/** Paints one layer onto a fresh transparent canvas; null when it is not a raster component. */
export function paintRasterLayer(layer: Layer, scene: RasterScene): ArgbCanvas | null {
  const painter = RASTER_PAINTERS[bareType(layer)];
  if (!painter) return null;
  const out = new ArgbCanvas(scene.width, scene.height);
  painter(layer, scene, out);
  return out;
}

/** Bottom-to-top flatten of every visible raster layer, honoring each layer's opacity. */
export function flattenRaster(layers: readonly Layer[], scene: RasterScene): ArgbCanvas {
  const canvas = new ArgbCanvas(scene.width, scene.height);
  for (const layer of layers) {
    if (!isVisible(layer)) continue;
    const painted = paintRasterLayer(layer, scene);
    if (painted) canvas.compositeLayer(painted, opacityOf(layer));
  }
  return canvas;
}
