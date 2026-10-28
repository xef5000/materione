/**
 * Everything the studio needs to know about where layers are and in what order
 * they reach the screen: bounds for selection and snapping, hit testing, the
 * compiled (not list) order, and the badges that say when the two disagree
 * (gui.md section 4: "what you see is what the player gets").
 *
 * Pure: no DOM, no Svelte. Issue 187 plugs the text and item layers in by
 * replacing `measureTextLayer` and by registering real painters; nothing here
 * changes.
 */
import { canvasSize, cellsOf, itemRect, slotCell, wellRect } from './chest';
import { isHidden, isLocked, type GuiDocument, type GuiLayer } from './model';
import type { ArgbCanvas, Rect } from './raster/canvas';
import { bareType, regionOf, whole } from './raster/flatten';
import { componentOf, type GuiRegistry, type RenderTarget } from './registry';

export interface Scene {
  rows: number;
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
  const { width, height } = canvasSize(doc.rows);
  return { rows: doc.rows, width, height, registry, image };
}

/** Text layers are measured by issue 187's font; until then a coarse box stands in. */
export function measureTextLayer(layer: GuiLayer): { w: number; h: number } {
  const text = typeof layer.text === 'string' ? layer.text : '';
  const longest = text.split('\n').reduce((n, line) => Math.max(n, line.length), 0);
  const lines = Math.max(1, text.split('\n').length);
  return { w: Math.max(6, longest * 6), h: lines * 9 };
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
  if (bareType(layer) === 'slots') return union(cellsOf(scene.rows, regionOf(layer)).map(wellRect));
  if (bareType(layer) === 'paint') return { x: 0, y: 0, w: scene.width, h: scene.height };
  if (target === 'slot' && typeof layer.slot === 'number') {
    const cell = slotCell(scene.rows, layer.slot);
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

/** A layer the player-visible raster does not contain: drawn as a marked placeholder until issue 187 renders it. */
export interface Placeholder {
  layer: GuiLayer;
  target: Exclude<RenderTarget, 'raster'>;
  rect: Rect;
}

export function placeholders(doc: GuiDocument, scene: Scene): Placeholder[] {
  const out: Placeholder[] = [];
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
      const hit = cellsOf(scene.rows, regionOf(layer)).some((cell) => {
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

/** Layers whose bounds intersect a marquee rectangle (unlocked and visible; slots and paint by their own rules). */
export function layersInRect(doc: GuiDocument, scene: Scene, marquee: Rect): GuiLayer[] {
  return compiledOrder(doc, scene).filter((layer) => {
    if (isLocked(layer) || bareType(layer) === 'paint') return false;
    const touches = (bounds: Rect): boolean => bounds.x < marquee.x + marquee.w && bounds.x + bounds.w > marquee.x &&
      bounds.y < marquee.y + marquee.h && bounds.y + bounds.h > marquee.y;
    // slot wells count one by one (their bounding box is the whole screen) and only when the marquee covers one
    if (bareType(layer) === 'slots') {
      return cellsOf(scene.rows, regionOf(layer)).some((cell) => {
        const well = wellRect(cell);
        return well.x >= marquee.x && well.y >= marquee.y && well.x + well.w <= marquee.x + marquee.w &&
          well.y + well.h <= marquee.y + marquee.h;
      });
    }
    const bounds = layerBounds(layer, scene);
    // the backdrop (a layer filling the whole canvas) is what you marquee over, never part of the result
    if (!bounds || (bounds.x <= 0 && bounds.y <= 0 && bounds.x + bounds.w >= scene.width && bounds.y + bounds.h >= scene.height)) return false;
    return touches(bounds);
  });
}
