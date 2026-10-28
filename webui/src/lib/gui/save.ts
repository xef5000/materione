/**
 * Saving a GUI (gui.md sections 6 and 7): one authoring transaction carrying
 * the fields that changed and the dirty pixel assets, committed atomically by
 * the server with rollback. Only changed top-level fields are sent (`title`,
 * `rows`, `layers`), so an untouched document sends nothing and a lossless
 * round trip stays byte-identical.
 *
 * The wire shape is `ops.setFields(channel, pack, path, rev, edits, assets)` in
 * api.ts: `assets` is `[{ path, data }]`, `path` pack-relative under `assets/`
 * and `data` standard base64, committed by the server with the edits or not at all.
 */
import { ops, type AssetUpload, type FieldEditPayload, type RpcChannel } from '../api';
import type { GuiDocument } from './model';

export type SaveAsset = AssetUpload;

export interface SavePlan {
  edits: FieldEditPayload[];
  assets: SaveAsset[];
}

export function isEmptyPlan(plan: SavePlan): boolean {
  return plan.edits.length === 0 && plan.assets.length === 0;
}

/** `gui/shop/main/art` -> `assets/textures/gui/shop/main/art.png` (textures/ is implied by references). */
export function textureAssetPath(reference: string): string {
  const colon = reference.indexOf(':');
  return `assets/textures/${colon >= 0 ? reference.slice(colon + 1) : reference}.png`;
}

/** The texture reference a paint layer of `<guis/shop/main.yml>` named `art` owns: `gui/shop/main/art`. */
export function paintReference(documentPath: string, layerId: string): string {
  const withoutRoot = documentPath.replace(/^guis\//, '').replace(/\.ya?ml$/, '');
  return `gui/${withoutRoot}/${layerId}`;
}

/**
 * The changed fields, and the dirty assets that the document still refers to
 * (an asset made for a layer that was since deleted or undone is never sent).
 */
export function planSave(
  prefix: readonly string[],
  loaded: GuiDocument,
  current: GuiDocument,
  dirtyAssets: ReadonlyMap<string, string>,
): SavePlan {
  const edits: FieldEditPayload[] = [];
  const set = (key: string, value: unknown): void => {
    edits.push({ field: [...prefix, key], value });
  };
  if (loaded.title !== current.title) set('title', current.title);
  if (loaded.rows !== current.rows) set('rows', current.rows);
  const layersChanged = JSON.stringify(loaded.layers) !== JSON.stringify(current.layers);
  if (layersChanged) set('layers', current.layers);

  const referenced = new Set(
    current.layers.flatMap((layer) => (typeof layer.image === 'string' ? [layer.image] : [])),
  );
  const assets: SaveAsset[] = [];
  for (const [reference, bytes] of dirtyAssets) {
    if (referenced.has(reference)) assets.push({ path: textureAssetPath(reference), data: bytes });
  }
  return { edits, assets };
}

/** Sends the plan as one `authoring.setFields` call (a pixels-only save has empty `edits`). */
export function sendSave(channel: RpcChannel, pack: string, path: string, rev: number, plan: SavePlan) {
  return ops.setFields(channel, pack, path, rev, plan.edits, plan.assets);
}

export function base64Of(bytes: Uint8Array): string {
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}
