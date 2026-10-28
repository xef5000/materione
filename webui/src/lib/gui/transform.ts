/**
 * Selection transforms: moving a group, dragging one of the eight resize
 * handles, and the bounding box of a multi-selection. All in whole GUI pixels.
 */
import type { Rect } from './raster/canvas';

export type Handle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w';

export const HANDLES: readonly Handle[] = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];

/** Where on a rectangle a handle sits, as fractions of its width and height. */
export const HANDLE_ANCHOR: Record<Handle, { x: 0 | 0.5 | 1; y: 0 | 0.5 | 1 }> = {
  nw: { x: 0, y: 0 },
  n: { x: 0.5, y: 0 },
  ne: { x: 1, y: 0 },
  e: { x: 1, y: 0.5 },
  se: { x: 1, y: 1 },
  s: { x: 0.5, y: 1 },
  sw: { x: 0, y: 1 },
  w: { x: 0, y: 0.5 },
};

export const HANDLE_CURSOR: Record<Handle, string> = {
  nw: 'nwse-resize',
  se: 'nwse-resize',
  ne: 'nesw-resize',
  sw: 'nesw-resize',
  n: 'ns-resize',
  s: 'ns-resize',
  e: 'ew-resize',
  w: 'ew-resize',
};

export function unionOf(rects: readonly Rect[]): Rect | null {
  if (!rects.length) return null;
  const left = Math.min(...rects.map((r) => r.x));
  const top = Math.min(...rects.map((r) => r.y));
  const right = Math.max(...rects.map((r) => r.x + r.w));
  const bottom = Math.max(...rects.map((r) => r.y + r.h));
  return { x: left, y: top, w: right - left, h: bottom - top };
}

export interface ResizeOptions {
  /** Smallest width/height a resize may produce. */
  min?: number;
  /** Keep the starting aspect ratio (shift). */
  keepAspect?: boolean;
  /** Resize around the center (alt). */
  fromCenter?: boolean;
}

/**
 * Drags `handle` of `start` by (dx, dy). The opposite edge (or the center)
 * stays put, the size never drops under `min`, and the result is whole pixels.
 */
export function resizeRect(start: Rect, handle: Handle, dx: number, dy: number, options: ResizeOptions = {}): Rect {
  const min = options.min ?? 1;
  const anchor = HANDLE_ANCHOR[handle];
  const movesX = anchor.x !== 0.5;
  const movesY = anchor.y !== 0.5;
  // signed growth along each axis: dragging the east edge right grows, the west edge right shrinks
  const growX = movesX ? (anchor.x === 1 ? dx : -dx) * (options.fromCenter ? 2 : 1) : 0;
  const growY = movesY ? (anchor.y === 1 ? dy : -dy) * (options.fromCenter ? 2 : 1) : 0;
  let w = Math.max(min, Math.round(start.w + growX));
  let h = Math.max(min, Math.round(start.h + growY));
  if (options.keepAspect && start.w > 0 && start.h > 0) {
    const ratio = start.w / start.h;
    if (movesX && movesY) {
      // follow whichever axis the pointer moved further, proportionally
      if ((w - start.w) / start.w >= (h - start.h) / start.h) h = Math.max(min, Math.round(w / ratio));
      else w = Math.max(min, Math.round(h * ratio));
    } else if (movesX) {
      h = Math.max(min, Math.round(w / ratio));
    } else {
      w = Math.max(min, Math.round(h * ratio));
    }
  }
  const cx = start.x + start.w / 2;
  const cy = start.y + start.h / 2;
  const x = options.fromCenter
    ? Math.round(cx - w / 2)
    : anchor.x === 0 ? start.x + start.w - w : anchor.x === 1 ? start.x : movesX ? start.x : Math.round(cx - w / 2);
  const y = options.fromCenter
    ? Math.round(cy - h / 2)
    : anchor.y === 0 ? start.y + start.h - h : anchor.y === 1 ? start.y : movesY ? start.y : Math.round(cy - h / 2);
  return { x, y, w, h };
}

/**
 * Maps a rectangle resize onto each member of a group: every member keeps its
 * relative position and size inside the group's box, scaled to the new box.
 */
export function scaleWithin(member: Rect, from: Rect, to: Rect): Rect {
  const sx = from.w === 0 ? 1 : to.w / from.w;
  const sy = from.h === 0 ? 1 : to.h / from.h;
  return {
    x: Math.round(to.x + (member.x - from.x) * sx),
    y: Math.round(to.y + (member.y - from.y) * sy),
    w: Math.max(1, Math.round(member.w * sx)),
    h: Math.max(1, Math.round(member.h * sy)),
  };
}

export function rectsEqual(a: Rect, b: Rect): boolean {
  return a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h;
}

export function normalizeRect(a: { x: number; y: number }, b: { x: number; y: number }): Rect {
  return { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(a.x - b.x), h: Math.abs(a.y - b.y) };
}
