/**
 * "Customize" for an item layer (gui.md section 2): the layer gets its own ordinary item at
 * `items/gui/<gui-path>/<layer-id>.yml`, and the layer's `item:` points at it. There is no
 * GUI-local item concept (D1, D5): the item is a normal resource with the item studio, compile path
 * and auto-updater of every other.
 *
 * This module plans the item's creation as one `authoring.bulk` operation; the studio adds the
 * layer's new `item:` as a second operation of the same call, so the item and the pointer are one
 * all-or-none transaction (`GuiStudioState.customize`).
 *
 *  - A **vanilla** base (`minecraft:diamond_sword`) becomes a `create` with `material:`.
 *  - A **Materione item** base becomes a `duplicate` of the base item's document: items are not
 *    templates (`template:` resolves only against `templates/`), so a live link to an item cannot
 *    be expressed; the copy starts identical to the base, keeps the base's own `template:`, and is
 *    then the layer's to change.
 *  - Blocks and furniture cannot be customized here (no item document to copy).
 *
 * Pure: no DOM, no Svelte.
 */
import type { BulkOperation } from '../bulk';
import type { ResourceRef } from '../api';
import type { GuiLayer } from './model';
import { itemRefOf } from './items';

export interface CustomizeEnvironment {
  pack: string;
  /** The GUI document's pack-relative path, e.g. `guis/shop/main.yml`. */
  guiPath: string;
  /** Every resource of the pack (workspace.resources). */
  resources: readonly ResourceRef[];
  /** The server's vanilla material list (the item type's `material` values), uppercase. Empty = unchecked. */
  materials: readonly string[];
}

export type CustomizePlan =
  | { ok: true; id: string; path: string; folder: string; reference: string; operation: BulkOperation }
  | { ok: false; reason: string };

/** `guis/shop/main.yml` -> `shop/main`. */
export function guiStem(guiPath: string): string {
  return guiPath.replace(/^guis\//, '').replace(/\.ya?ml$/, '');
}

/** The folder under `items/` that holds a GUI's customized items: `gui/shop/main`. */
export function customFolder(guiPath: string): string {
  return `gui/${guiStem(guiPath)}`;
}

export function customPath(guiPath: string, id: string): string {
  return `items/${customFolder(guiPath)}/${id}.yml`;
}

function sanitize(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '');
}

/**
 * The item id: the layer id when the pack has no resource of that id (so the file really is
 * `items/gui/<gui-path>/<layer-id>.yml`), else prefixed with the GUI's name, else numbered. The API
 * names the file after the id, and an id is unique per pack, so a clash cannot keep the layer id.
 */
export function chooseItemId(layerId: string, guiPath: string, taken: ReadonlySet<string>): string {
  const own = sanitize(layerId);
  const gui = sanitize(guiStem(guiPath).replace(/\//g, '_'));
  const candidates = [own.length >= 2 ? own : `${gui}_${own}`, `${gui}_${own}`];
  for (const candidate of candidates) if (candidate.length >= 2 && candidate.length <= 64 && !taken.has(candidate)) return candidate;
  const stem = candidates[1]!.slice(0, 60);
  for (let n = 2; ; n++) if (!taken.has(`${stem}_${n}`)) return `${stem}_${n}`;
}

/** The resource a layer's `item:` names in this pack, if it is a pack item. */
export function resolveItem(reference: string, env: Pick<CustomizeEnvironment, 'pack' | 'resources'>): ResourceRef | undefined {
  const text = reference.trim();
  const colon = text.indexOf(':');
  const namespace = colon < 0 ? env.pack : text.slice(0, colon);
  const id = colon < 0 ? text : text.slice(colon + 1);
  return env.resources.find((resource) =>
    resource.pack === namespace && resource.id.slice(resource.id.lastIndexOf(':') + 1) === id);
}

/** Whether the reference already names an item customized for this GUI (it lives in the GUI's folder). */
export function isCustomized(reference: string, env: Pick<CustomizeEnvironment, 'pack' | 'resources' | 'guiPath'>): ResourceRef | undefined {
  const resource = resolveItem(reference, env);
  return resource && resource.type === 'items' && resource.path.startsWith(`items/${customFolder(env.guiPath)}/`) ? resource : undefined;
}

/** Plans the creation of the layer's own item; the studio adds the pointer edit to the same bulk call. */
export function planCustomize(layer: GuiLayer, env: CustomizeEnvironment): CustomizePlan {
  const reference = itemRefOf(layer).trim();
  if (!reference) return { ok: false, reason: 'Choose an item first: Customize starts from it.' };
  const taken = new Set(env.resources.map((resource) => resource.id.slice(resource.id.lastIndexOf(':') + 1)));
  const id = chooseItemId(layer.id, env.guiPath, taken);
  const folder = customFolder(env.guiPath);
  const path = customPath(env.guiPath, id);
  const base = { id, path, folder, reference: `${env.pack}:${id}` };
  if (reference.startsWith('minecraft:')) {
    const material = reference.slice('minecraft:'.length).toUpperCase();
    if (env.materials.length > 0 && !env.materials.includes(material)) {
      return { ok: false, reason: `"${reference}" is not a material this server knows.` };
    }
    return {
      ok: true,
      ...base,
      operation: { kind: 'create', type: 'items', id, folder, edits: [{ field: [id, 'material'], value: material }] },
    };
  }
  const source = resolveItem(reference, env);
  if (!source) return { ok: false, reason: `"${reference}" is not an item in this pack, so there is nothing to copy.` };
  if (source.type !== 'items') return { ok: false, reason: 'Only items and vanilla materials can be customized (blocks and furniture have no item document to copy).' };
  if (isCustomized(reference, env)) return { ok: false, reason: 'This layer already has its own item.' };
  return { ok: true, ...base, operation: { kind: 'duplicate', fromPath: source.path, id, folder } };
}
