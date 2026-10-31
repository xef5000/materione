/**
 * The Logic canvas camera. Like the Design canvas, `screen = pan + zoom * world`, and zooming keeps the
 * point under the cursor still; unlike it, the zoom is continuous between a quarter and double size,
 * because a graph is read at whatever scale shows it whole, where the pixel-art canvas stays on whole
 * numbers.
 *
 * Pure of the DOM.
 */
import type { Point, Rect } from './layout';

export const MIN_ZOOM = 0.25;
export const MAX_ZOOM = 2;
/** The ladder the zoom buttons and keys step along. */
export const ZOOM_STEPS = [0.25, 0.35, 0.5, 0.7, 1, 1.4, 2] as const;

export interface View {
  zoom: number;
  panX: number;
  panY: number;
}

export interface Size {
  w: number;
  h: number;
}

export const clampZoom = (zoom: number): number => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));

export function toWorld(view: View, point: Point): Point {
  return { x: (point.x - view.panX) / view.zoom, y: (point.y - view.panY) / view.zoom };
}

export function toScreen(view: View, point: Point): Point {
  return { x: view.panX + point.x * view.zoom, y: view.panY + point.y * view.zoom };
}

/** Sets the zoom, keeping the world point under `anchor` (screen pixels) where it is. */
export function zoomAt(view: View, zoom: number, anchor: Point): View {
  const next = clampZoom(zoom);
  const world = toWorld(view, anchor);
  return { zoom: next, panX: anchor.x - world.x * next, panY: anchor.y - world.y * next };
}

/** One step along the ladder (+1 in, -1 out) from wherever the zoom is. */
export function stepZoom(view: View, direction: 1 | -1, anchor: Point): View {
  const target = direction > 0
    ? ZOOM_STEPS.find((step) => step > view.zoom + 1e-6) ?? MAX_ZOOM
    : [...ZOOM_STEPS].reverse().find((step) => step < view.zoom - 1e-6) ?? MIN_ZOOM;
  return zoomAt(view, target, anchor);
}

/** A wheel notch or pinch: `delta` is the wheel's deltaY. */
export function wheelZoom(view: View, delta: number, anchor: Point): View {
  return zoomAt(view, view.zoom * Math.exp(-delta * 0.0015), anchor);
}

/** The view that shows `rect` whole, centered, with a margin; never closer than actual size. */
export function frameView(viewport: Size, rect: Rect, margin = 48): View {
  if (rect.w <= 0 || rect.h <= 0 || viewport.w <= 0 || viewport.h <= 0) return { zoom: 1, panX: margin, panY: margin };
  const room = { w: Math.max(1, viewport.w - margin * 2), h: Math.max(1, viewport.h - margin * 2) };
  const zoom = clampZoom(Math.min(1, room.w / rect.w, room.h / rect.h));
  return {
    zoom,
    panX: Math.round((viewport.w - rect.w * zoom) / 2 - rect.x * zoom),
    panY: Math.round((viewport.h - rect.h * zoom) / 2 - rect.y * zoom),
  };
}

/** The view panned the least that makes `rect` visible inside the viewport (a node the author just added). */
export function revealRect(view: View, viewport: Size, rect: Rect, margin = 32): View {
  const left = view.panX + rect.x * view.zoom;
  const top = view.panY + rect.y * view.zoom;
  const right = left + rect.w * view.zoom;
  const bottom = top + rect.h * view.zoom;
  let dx = 0;
  let dy = 0;
  if (left < margin) dx = margin - left;
  else if (right > viewport.w - margin) dx = viewport.w - margin - right;
  if (top < margin) dy = margin - top;
  else if (bottom > viewport.h - margin) dy = viewport.h - margin - bottom;
  return dx === 0 && dy === 0 ? view : { ...view, panX: Math.round(view.panX + dx), panY: Math.round(view.panY + dy) };
}

export function zoomLabel(zoom: number): string {
  return `${Math.round(zoom * 100)}%`;
}
