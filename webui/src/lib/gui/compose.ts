/**
 * The composited canvas: what the player sees of the background and the smallcaps title text, in
 * compiled order (raster layers, then text; gui.md section 4). The studio draws this onto its
 * canvas and the eyedropper samples it. Smallcaps runs are drawn from the same font source the
 * compiler ships (`font/smallcaps.ts`), exactly as the client draws them; `default` runs and slot
 * items are not pixels the studio owns, so the canvas overlays them (approximate, and the real
 * item icons).
 *
 * Pure: no DOM, no Svelte.
 */
import { smallcaps } from './font/render';
import type { GuiDocument } from './model';
import type { ArgbCanvas } from './raster/canvas';
import { flattenRaster, isVisible, type RasterScene } from './raster/flatten';
import { analyzeText, colorOf, fontOf, isSkipped, isTextLayer, textOf } from './text';

export function composeCanvas(doc: GuiDocument, scene: RasterScene): ArgbCanvas {
  const canvas = flattenRaster(doc.layers, scene);
  const issues = analyzeText(doc);
  for (const layer of doc.layers) {
    if (!isVisible(layer) || !isTextLayer(layer) || isSkipped(issues.get(layer.id))) continue;
    if (fontOf(layer) !== 'smallcaps') continue;
    const x = typeof layer.x === 'number' ? Math.floor(layer.x) : 0;
    const y = typeof layer.y === 'number' ? Math.floor(layer.y) : 0;
    smallcaps.draw(canvas, x, y, textOf(layer), colorOf(layer));
  }
  return canvas;
}
