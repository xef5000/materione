/**
 * The canvas camera: nearest-neighbour zoom from 1x to 16x and a pan, both in
 * screen pixels. `screen = pan + zoom * gui`. Zooming always keeps the GUI
 * point under the cursor still, so wheel and pinch zoom feel anchored.
 */
export const MIN_ZOOM = 1;
export const MAX_ZOOM = 16;
/** The ladder the zoom buttons and the wheel step along: whole numbers keep every pixel crisp. */
export const ZOOM_STEPS = [1, 2, 3, 4, 5, 6, 8, 10, 12, 16] as const;
/** The pixel grid appears from this zoom up (gui.md section 6). */
export const GRID_ZOOM = 8;

export interface View {
  zoom: number;
  panX: number;
  panY: number;
}

export interface Size {
  w: number;
  h: number;
}

export interface Point {
  x: number;
  y: number;
}

export function clampZoom(zoom: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));
}

export function toGui(view: View, point: Point): Point {
  return { x: (point.x - view.panX) / view.zoom, y: (point.y - view.panY) / view.zoom };
}

export function toScreen(view: View, point: Point): Point {
  return { x: view.panX + point.x * view.zoom, y: view.panY + point.y * view.zoom };
}

/** Sets the zoom, keeping the GUI point under `anchor` (screen pixels) where it is. */
export function zoomTo(view: View, zoom: number, anchor: Point): View {
  const next = clampZoom(zoom);
  const gui = toGui(view, anchor);
  return { zoom: next, panX: anchor.x - gui.x * next, panY: anchor.y - gui.y * next };
}

/** One step along the ladder (direction +1 in, -1 out), from wherever the zoom currently is. */
export function stepZoom(view: View, direction: 1 | -1, anchor: Point): View {
  const target = direction > 0
    ? ZOOM_STEPS.find((step) => step > view.zoom + 1e-6) ?? MAX_ZOOM
    : [...ZOOM_STEPS].reverse().find((step) => step < view.zoom - 1e-6) ?? MIN_ZOOM;
  return zoomTo(view, target, anchor);
}

/** Continuous zoom for a trackpad pinch or a ctrl+wheel: `delta` is the wheel's deltaY. */
export function pinchZoom(view: View, delta: number, anchor: Point): View {
  return zoomTo(view, view.zoom * Math.exp(-delta * 0.01), anchor);
}

/** The biggest whole zoom at which the content fits the viewport with a margin, centered. */
export function fitView(viewport: Size, content: Size, margin = 24): View {
  const room = {
    w: Math.max(1, viewport.w - margin * 2),
    h: Math.max(1, viewport.h - margin * 2),
  };
  const zoom = clampZoom(Math.max(1, Math.floor(Math.min(room.w / content.w, room.h / content.h))));
  return centered(viewport, content, zoom);
}

export function centered(viewport: Size, content: Size, zoom: number): View {
  return {
    zoom,
    panX: Math.round((viewport.w - content.w * zoom) / 2),
    panY: Math.round((viewport.h - content.h * zoom) / 2),
  };
}

/** Pans, keeping at least `keep` screen pixels of the content inside the viewport so it cannot be lost. */
export function panBy(view: View, dx: number, dy: number, viewport: Size, content: Size, keep = 64): View {
  const width = content.w * view.zoom;
  const height = content.h * view.zoom;
  const clampAxis = (pan: number, span: number, room: number): number =>
    Math.min(room - Math.min(keep, span), Math.max(Math.min(keep, span) - span, pan));
  return {
    zoom: view.zoom,
    panX: clampAxis(view.panX + dx, width, viewport.w),
    panY: clampAxis(view.panY + dy, height, viewport.h),
  };
}

export function zoomLabel(zoom: number): string {
  return `${Math.round(zoom * 100)}%`;
}
