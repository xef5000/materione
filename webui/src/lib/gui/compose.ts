/**
 * The composited canvas: what the player sees of the background and the smallcaps title text, in
 * compiled order (raster layers, then text; gui.md section 4). The studio draws this onto its
 * canvas and the eyedropper samples it. Smallcaps runs are drawn from the same font source the
 * compiler ships (`font/smallcaps.ts`), exactly as the client draws them; `default` runs and slot
 * items are not pixels the studio owns, so the canvas overlays them (approximate, and the real
 * item icons).
 *
 * Which text layers the compiler drops is the server's verdict, passed in as the ids to leave out.
 *
 * Pure: no DOM, no Svelte.
 */
import { smallcaps } from './font/render';
import type { GuiDocument } from './model';
import type { ArgbCanvas } from './raster/canvas';
import { flattenRaster, isVisible, type RasterScene } from './raster/flatten';
import { colorOf, fontOf, isTextLayer, textOf } from './text';

export function composeCanvas(doc: GuiDocument, scene: RasterScene, skipped: ReadonlySet<string>): ArgbCanvas {
  const canvas = flattenRaster(doc.layers, scene);
  for (const layer of doc.layers) {
    if (!isVisible(layer) || !isTextLayer(layer) || skipped.has(layer.id)) continue;
    if (fontOf(layer) !== 'smallcaps') continue;
    const x = typeof layer.x === 'number' ? Math.floor(layer.x) : 0;
    const y = typeof layer.y === 'number' ? Math.floor(layer.y) : 0;
    smallcaps.draw(canvas, x, y, textOf(layer), colorOf(layer));
  }
  return canvas;
}
