/**
 * "Customize" for an item layer (gui.md section 2): the layer gets its own ordinary item at
 * `items/gui/<gui-path>/<layer-id>.yml`, and the layer's `item:` points at it. The rules of that
 * (the id, the copy or the material, the refusals) are the server's: `gui.customizeItem` decides
 * them in one transaction (gui.md §10 #2). What stays here is the studio's reading of a layer: whether
 * its item already lives in the GUI's folder, so the inspector can show it.
 *
 * Pure: no DOM, no Svelte.
 */
import type { ResourceRef } from '../api';

export interface CustomizeEnvironment {
  pack: string;
  /** The GUI document's pack-relative path, e.g. `guis/shop/main.yml`. */
  guiPath: string;
  /** Every resource of the pack (workspace.resources). */
  resources: readonly ResourceRef[];
}

/** `guis/shop/main.yml` -> `shop/main`. */
export function guiStem(guiPath: string): string {
  return guiPath.replace(/^guis\//, '').replace(/\.ya?ml$/, '');
}

/** The folder under `items/` that holds a GUI's customized items: `gui/shop/main`. */
export function customFolder(guiPath: string): string {
  return `gui/${guiStem(guiPath)}`;
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
