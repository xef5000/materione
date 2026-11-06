/**
 * Draws the Design tab's composited GUI (the same raster the canvas shows, gui.md section 4) into a small canvas: the
 * whole of it for the mini preview, or one layer's rectangle for a thumbnail. Nearest-neighbour, so a 16-pixel
 * button stays crisp.
 */
import { toImageData } from '../../../gui/browser-image';
import type { Rect } from '../../../gui/raster/canvas';
import type { GuiStudioState } from '../../../gui/studio.svelte';

/** Paints `source` (a rectangle of the composite, or all of it) centred in `target` at the biggest whole-pixel-friendly scale that fits. */
export function paintComposite(
  target: HTMLCanvasElement,
  studio: GuiStudioState,
  source: Rect | null,
): { scale: number; offsetX: number; offsetY: number } | null {
  const context = target.getContext('2d');
  if (!context) return null;
  context.clearRect(0, 0, target.width, target.height);
  const composite = studio.composite;
  const from = source ?? { x: 0, y: 0, w: composite.width, h: composite.height };
  if (from.w <= 0 || from.h <= 0) return null;
  const scratch = document.createElement('canvas');
  scratch.width = composite.width;
  scratch.height = composite.height;
  scratch.getContext('2d')?.putImageData(toImageData(composite), 0, 0);
  const scale = Math.min(target.width / from.w, target.height / from.h);
  const width = from.w * scale;
  const height = from.h * scale;
  const offsetX = (target.width - width) / 2;
  const offsetY = (target.height - height) / 2;
  context.imageSmoothingEnabled = false;
  context.drawImage(scratch, from.x, from.y, from.w, from.h, offsetX, offsetY, width, height);
  return { scale, offsetX, offsetY };
}
