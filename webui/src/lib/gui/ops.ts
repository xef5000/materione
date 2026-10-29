/**
 * Layer operations as command builders: what the toolbar, the layers panel,
 * the canvas and the keyboard ask for, expressed once. Each takes the document
 * it will run on and returns an invertible command (or null for a no-op), so
 * the studio's only job is to hand the result to the history.
 */
import { nearestContainerCell } from './chest';
import {
  addLayer,
  compose,
  moveLayers,
  patchLayers,
  removeLayers,
  type Command,
  type LayerPatch,
} from './commands';
import {
  findLayer,
  isLocked,
  layerIndex,
  layerLabel,
  uniqueId,
  type GuiDocument,
  type GuiLayer,
} from './model';
import type { Rect } from './raster/canvas';
import { layerBounds, type Scene } from './scene';
import { componentOf, newLayer, transformability, type GuiComponentInfo } from './registry';

function infoFor(scene: Scene, layer: GuiLayer): GuiComponentInfo | undefined {
  return componentOf(scene.registry, layer.type);
}

/** The patch that puts a layer at `rect`: x/y always for movable layers, width/height only for resizable ones; items by slot. */
export function patchForRect(layer: GuiLayer, rect: Rect, scene: Scene): LayerPatch | null {
  const can = transformability(infoFor(scene, layer), layer);
  if (can.slotBound) {
    const cell = nearestContainerCell(scene.rows, rect.x + rect.w / 2, rect.y + rect.h / 2);
    return layer.slot === cell.index ? null : { slot: cell.index };
  }
  if (!can.movable) return null;
  const patch: LayerPatch = { x: Math.round(rect.x), y: Math.round(rect.y) };
  if (can.resizable) {
    patch.width = Math.max(1, Math.round(rect.w));
    patch.height = Math.max(1, Math.round(rect.h));
  }
  return patch;
}

/** Moves or resizes layers to new rectangles (by id), as one step. */
export function setRects(
  doc: GuiDocument,
  scene: Scene,
  rects: Record<string, Rect>,
  label: string,
  coalesce?: string,
): Command | null {
  const patches: Record<string, LayerPatch> = {};
  for (const [id, rect] of Object.entries(rects)) {
    const layer = findLayer(doc, id);
    if (!layer || isLocked(layer)) continue;
    const patch = patchForRect(layer, rect, scene);
    if (patch) patches[id] = patch;
  }
  return patchLayers(doc, patches, label, coalesce);
}

/**
 * Moves layers by whole pixels (arrow-key nudge). Free layers shift by the
 * delta; slot-bound items hop one slot per step along the 18 px grid, in the
 * direction of the delta.
 */
export function nudge(
  doc: GuiDocument,
  scene: Scene,
  ids: readonly string[],
  dx: number,
  dy: number,
): Command | null {
  const rects: Record<string, Rect> = {};
  for (const id of ids) {
    const layer = findLayer(doc, id);
    const bounds = layer ? layerBounds(layer, scene) : null;
    if (!layer || !bounds) continue;
    const can = transformability(infoFor(scene, layer), layer);
    if (can.slotBound) {
      rects[id] = { ...bounds, x: bounds.x + Math.sign(dx) * 18, y: bounds.y + Math.sign(dy) * 18 };
    } else {
      rects[id] = { ...bounds, x: bounds.x + dx, y: bounds.y + dy };
    }
  }
  return setRects(doc, scene, rects, 'Nudge', `nudge:${ids.join(',')}`);
}

export function setVisible(doc: GuiDocument, id: string, visible: boolean): Command | null {
  return patchLayers(doc, { [id]: { visible: visible ? undefined : false } }, visible ? 'Show layer' : 'Hide layer');
}

export function setLocked(doc: GuiDocument, id: string, locked: boolean): Command | null {
  return patchLayers(doc, { [id]: { locked: locked ? true : undefined } }, locked ? 'Lock layer' : 'Unlock layer');
}

/** An empty name clears `name` (the layers panel falls back to the id). */
export function rename(doc: GuiDocument, id: string, name: string): Command | null {
  return patchLayers(doc, { [id]: { name: name.trim() ? name.trim() : undefined } }, 'Rename layer');
}

export function setField(
  doc: GuiDocument,
  id: string,
  field: string,
  value: unknown,
  label = 'Edit layer',
  coalesce?: string,
): Command | null {
  return patchLayers(doc, { [id]: { [field]: value } }, label, coalesce);
}

/** Sets one field on several layers (a multi-selection inspector edit), as one step. */
export function setFieldOn(
  doc: GuiDocument,
  ids: readonly string[],
  field: string,
  value: unknown,
  label = 'Edit layers',
  coalesce?: string,
): Command | null {
  const patches: Record<string, LayerPatch> = {};
  for (const id of ids) patches[id] = { [field]: value };
  return patchLayers(doc, patches, label, coalesce);
}

export function deleteLayers(doc: GuiDocument, ids: readonly string[]): Command | null {
  const removable = ids.filter((id) => {
    const layer = findLayer(doc, id);
    return layer !== undefined && !isLocked(layer);
  });
  return removeLayers(doc, removable, removable.length === 1 ? 'Delete layer' : 'Delete layers');
}

/** Copies layers just above their originals (ids made unique), nudged 4 px so the copy shows. */
export function duplicateLayers(doc: GuiDocument, scene: Scene, ids: readonly string[]): { command: Command | null; created: string[] } {
  const taken = new Set(doc.layers.map((layer) => layer.id));
  const created: string[] = [];
  const steps: Command[] = [];
  const sources = doc.layers.filter((layer) => ids.includes(layer.id));
  // insert from the top down so earlier insertions do not shift later indices
  for (const source of [...sources].reverse()) {
    const id = uniqueId(`${source.id}_copy`, taken);
    taken.add(id);
    created.unshift(id);
    const can = transformability(infoFor(scene, source), source);
    const copy: GuiLayer = { ...source, id, name: `${layerLabel(source)} copy` };
    if (can.movable && !can.slotBound && typeof source.x === 'number' && typeof source.y === 'number') {
      copy.x = source.x + 4;
      copy.y = source.y + 4;
    }
    delete copy.locked;
    steps.push(addLayer(doc, copy, layerIndex(doc, source.id) + 1, 'Duplicate layer'));
  }
  return { command: compose(sources.length === 1 ? 'Duplicate layer' : 'Duplicate layers', steps), created };
}

/** Raise/lower by one, or to the front/back, keeping the selection's relative order. */
export function restack(
  doc: GuiDocument,
  ids: readonly string[],
  where: 'forward' | 'backward' | 'front' | 'back',
): Command | null {
  const selected = doc.layers.filter((layer) => ids.includes(layer.id));
  if (!selected.length) return null;
  const rest = doc.layers.filter((layer) => !ids.includes(layer.id));
  const firstIndex = doc.layers.findIndex((layer) => ids.includes(layer.id));
  const lastIndex = doc.layers.length - 1 - [...doc.layers].reverse().findIndex((layer) => ids.includes(layer.id));
  const before = (index: number): number => doc.layers.slice(0, index).filter((layer) => !ids.includes(layer.id)).length;
  let to: number;
  if (where === 'front') to = rest.length;
  else if (where === 'back') to = 0;
  else if (where === 'forward') to = Math.min(rest.length, before(lastIndex) + 1);
  else to = Math.max(0, before(firstIndex) - 1);
  return moveLayers(doc, ids, to, `Move layer ${where === 'front' ? 'to front' : where === 'back' ? 'to back' : where}`);
}

/** Where a palette click puts a new layer: centered on the canvas, on whole pixels. */
export function placementFor(layer: GuiLayer, scene: Scene): LayerPatch {
  const probe = layerBounds({ ...layer, x: 0, y: 0 } as GuiLayer, scene);
  if (!probe) return {};
  if (typeof layer.x !== 'number' && typeof layer.y !== 'number') return {};
  return {
    x: Math.max(0, Math.round((scene.width - probe.w) / 2)),
    y: Math.max(0, Math.round((scene.height - probe.h) / 2)),
  };
}

/** A palette click: the component's layer centered on the canvas, added on top. */
export function addComponent(doc: GuiDocument, scene: Scene, info: GuiComponentInfo): { command: Command; layer: GuiLayer } {
  const base = newLayer(info, doc.layers.map((layer) => layer.id));
  const layer = { ...base, ...placementFor(base, scene) } as GuiLayer;
  return { command: addLayer(doc, layer, doc.layers.length, `Add ${info.displayName}`), layer };
}
