/**
 * Everything the studio needs to know about where layers are and in what order
 * they reach the screen: bounds for selection and snapping, hit testing, the
 * compiled (not list) order, and the badges that say when the two disagree
 * (gui.md section 4: "what you see is what the player gets").
 *
 * Pure: no DOM, no Svelte. Text layers are measured by the font (`text.ts`);
 * text and item layers are real renders (the canvas draws smallcaps pixels
 * through `compose.ts` and item icons from the item pipeline), so this module
 * only says where they are and in what order.
 */
import { canvasSize, cellsOf, itemRect, slotCell, wellRect, type ContainerProfile } from './containers';
import { drawingProfileOf, isHidden, isLocked, type GuiDocument, type GuiLayer } from './model';
import type { ArgbCanvas, Rect } from './raster/canvas';
import { bareType, regionOf, whole } from './raster/flatten';
import { componentOf, type GuiRegistry, type RenderTarget } from './registry';
import { measureTextLayer } from './text';
import { isEmptyRect } from './open-canvas';
import { paintReferenceOf, paintRect } from './paint-geometry';

export { measureTextLayer };

export interface Scene {
  /** The container profile the screen is drawn in (gui.md section 12.5.1). */
  container: ContainerProfile;
  width: number;
  height: number;
  registry: GuiRegistry | null;
  image(reference: string): ArgbCanvas | null;
}

export function sceneOf(
  doc: GuiDocument,
  registry: GuiRegistry | null,
  image: (reference: string) => ArgbCanvas | null,
): Scene {
  const container = drawingProfileOf(doc);
  const { width, height } = canvasSize(container);
  return { container, width, height, registry, image };
}

export function renderTargetOf(layer: GuiLayer, scene: Pick<Scene, 'registry'>): RenderTarget {
  return componentOf(scene.registry, layer.type)?.target ?? 'raster';
}

function union(rects: Rect[]): Rect | null {
  if (!rects.length) return null;
  const left = Math.min(...rects.map((r) => r.x));
  const top = Math.min(...rects.map((r) => r.y));
  const right = Math.max(...rects.map((r) => r.x + r.w));
  const bottom = Math.max(...rects.map((r) => r.y + r.h));
  return { x: left, y: top, w: right - left, h: bottom - top };
}

/**
 * The GUI-pixel rectangle a layer occupies, or null when it has none (an item
 * with a bad slot, a layer with no geometry at all).
 */
export function layerBounds(layer: GuiLayer, scene: Scene): Rect | null {
  const target = renderTargetOf(layer, scene);
  if (bareType(layer) === 'slots') return union(cellsOf(scene.container, regionOf(layer)).map(wellRect));
  if (bareType(layer) === 'paint') {
    // a paint layer owns its geometry (gui.md section 11.2): its x, y and the bitmap's own size; none while it holds no pixel
    const bitmap = paintReferenceOf(layer) ? scene.image(paintReferenceOf(layer)) : null;
    const rect = paintRect(layer, bitmap);
    return isEmptyRect(rect) ? null : rect;
  }
  if (target === 'slot' && typeof layer.slot === 'number') {
    const cell = slotCell(scene.container, layer.slot);
    return cell ? itemRect(cell) : null;
  }
  const x = whole(layer.x, Number.NaN);
  const y = whole(layer.y, Number.NaN);
  if (Number.isNaN(x) || Number.isNaN(y)) return null;
  if (target === 'text') return { x, y, ...measureTextLayer(layer) };
  const natural = bareType(layer) === 'image' && typeof layer.image === 'string' ? scene.image(layer.image) : null;
  return {
    x,
    y,
    w: Math.max(0, whole(layer.width, natural?.width ?? 16)),
    h: Math.max(0, whole(layer.height, natural?.height ?? 16)),
  };
}

const TARGET_RANK: Record<RenderTarget, number> = { raster: 0, text: 1, slot: 2 };

/** Visible layers in the order the player sees them, bottom to top: by target, then by list position. */
export function compiledOrder(doc: GuiDocument, scene: Pick<Scene, 'registry'>): GuiLayer[] {
  return doc.layers
    .map((layer, index) => ({ layer, index, rank: TARGET_RANK[renderTargetOf(layer, scene)] }))
    .filter(({ layer }) => !isHidden(layer))
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .map(({ layer }) => layer);
}

export interface OrderBadge {
  /** 1-based position among visible layers in compiled order. */
  position: number;
  /** Where the list says it is, 1-based among visible layers. */
  listed: number;
  /** `under`: listed above others it renders below; `over`: the reverse. */
  direction: 'under' | 'over';
  /** The layers it swaps sides with. */
  with: string[];
  label: string;
}

/**
 * A layer's list position cannot be honored when a layer of another render
 * target sits on the wrong side of it: raster is always under text, text under
 * slot items. Those layers get a badge; the rest render where they are listed.
 */
export function orderBadges(doc: GuiDocument, scene: Pick<Scene, 'registry'>): Map<string, OrderBadge> {
  const visible = doc.layers.filter((layer) => !isHidden(layer));
  const compiled = compiledOrder(doc, scene);
  const rank = (layer: GuiLayer): number => TARGET_RANK[renderTargetOf(layer, scene)];
  const badges = new Map<string, OrderBadge>();
  visible.forEach((layer, listIndex) => {
    const under: string[] = [];
    const over: string[] = [];
    visible.forEach((other, otherIndex) => {
      if (other === layer) return;
      // listed below `other` but of a higher target: renders over it
      if (otherIndex > listIndex && rank(other) < rank(layer)) over.push(other.id);
      // listed above `other` but of a lower target: renders under it
      if (otherIndex < listIndex && rank(other) > rank(layer)) under.push(other.id);
    });
    if (!under.length && !over.length) return;
    const direction = under.length ? 'under' : 'over';
    const others = under.length ? under : over;
    badges.set(layer.id, {
      position: compiled.indexOf(layer) + 1,
      listed: listIndex + 1,
      direction,
      with: others,
      label: direction === 'under'
        ? `Renders under ${others.length === 1 ? others[0] : `${others.length} layers`} despite being listed above it`
        : `Renders over ${others.length === 1 ? others[0] : `${others.length} layers`} despite being listed below it`,
    });
  });
  return badges;
}

/** A layer outside the background raster (title text, a slot item): the canvas draws it on top of the art. */
export interface Overlay {
  layer: GuiLayer;
  target: Exclude<RenderTarget, 'raster'>;
  rect: Rect;
}

export function overlays(doc: GuiDocument, scene: Scene): Overlay[] {
  const out: Overlay[] = [];
  for (const layer of compiledOrder(doc, scene)) {
    const target = renderTargetOf(layer, scene);
    if (target === 'raster') continue;
    const rect = layerBounds(layer, scene);
    if (rect) out.push({ layer, target, rect });
  }
  return out;
}

/**
 * The topmost unlocked, visible layer under a GUI-pixel point, in compiled
 * order. Slot layers are hit on their wells (not their gaps), paint layers
 * only on a non-transparent pixel, everything else on its rectangle.
 */
export function hitTest(
  doc: GuiDocument,
  scene: Scene,
  point: { x: number; y: number },
  paintAlpha?: (layer: GuiLayer, x: number, y: number) => boolean,
): GuiLayer | null {
  const order = compiledOrder(doc, scene);
  for (let index = order.length - 1; index >= 0; index--) {
    const layer = order[index]!;
    if (isLocked(layer)) continue;
    if (bareType(layer) === 'slots') {
      const hit = cellsOf(scene.container, regionOf(layer)).some((cell) => {
        const well = wellRect(cell);
        return point.x >= well.x && point.x < well.x + well.w && point.y >= well.y && point.y < well.y + well.h;
      });
      if (hit) return layer;
      continue;
    }
    if (bareType(layer) === 'paint') {
      if (paintAlpha?.(layer, Math.floor(point.x), Math.floor(point.y))) return layer;
      continue;
    }
    const bounds = layerBounds(layer, scene);
    if (!bounds) continue;
    if (point.x >= bounds.x && point.x < bounds.x + bounds.w && point.y >= bounds.y && point.y < bounds.y + bounds.h) {
      return layer;
    }
  }
  return null;
}

/** Whether a rectangle covers the whole screen: a frame, the backdrop a drag on it marquee-selects over. */
export function coversScreen(bounds: Rect, scene: Pick<Scene, 'width' | 'height'>): boolean {
  return bounds.x <= 0 && bounds.y <= 0 && bounds.x + bounds.w >= scene.width && bounds.y + bounds.h >= scene.height;
}

/**
 * Layers whose bounds intersect a marquee rectangle (unlocked and visible; slots and paint by their own rules). A layer
 * that covers the whole screen is the backdrop you marquee over, never part of the result; the open space outside the
 * screen has no layer under it, so a marquee there selects what it touches.
 */
export function layersInRect(
  doc: GuiDocument,
  scene: Scene,
  marquee: Rect,
  /** Whether a paint layer has a painted pixel inside a GUI-pixel rectangle; without it paint layers are never selected by a marquee. */
  paintTouches?: (layer: GuiLayer, area: Rect) => boolean,
): GuiLayer[] {
  return compiledOrder(doc, scene).filter((layer) => {
    if (isLocked(layer)) return false;
    if (bareType(layer) === 'paint') return paintTouches?.(layer, marquee) ?? false;
    const touches = (bounds: Rect): boolean => bounds.x < marquee.x + marquee.w && bounds.x + bounds.w > marquee.x &&
      bounds.y < marquee.y + marquee.h && bounds.y + bounds.h > marquee.y;
    // slot wells count one by one (their bounding box is the whole screen) and only when the marquee covers one
    if (bareType(layer) === 'slots') {
      return cellsOf(scene.container, regionOf(layer)).some((cell) => {
        const well = wellRect(cell);
        return well.x >= marquee.x && well.y >= marquee.y && well.x + well.w <= marquee.x + marquee.w &&
          well.y + well.h <= marquee.y + marquee.h;
      });
    }
    const bounds = layerBounds(layer, scene);
    // the backdrop (a layer covering the whole screen) is what you marquee over, never part of the result
    if (!bounds || coversScreen(bounds, scene)) return false;
    return touches(bounds);
  });
}
