/**
 * Saving a GUI (gui.md sections 6 and 7): one authoring transaction carrying
 * the fields that changed and the dirty pixel assets, committed atomically by
 * the server with rollback. Only changed top-level fields are sent (`title`,
 * `rows`, `layers`), so an untouched document sends nothing and a lossless
 * round trip stays byte-identical.
 *
 * `authoring.setFields` takes an optional `assets` list as of issue 183
 * (`assets: [{ path, bytes }]`, `bytes` base64). That op and its protocol
 * types belong to issue 183; this adapter calls the documented shape through the
 * channel's generic `call`, so the editor's own protocol files stay untouched.
 */
import type { FieldEditPayload, RpcChannel } from '../api';
import type { GuiDocument } from './model';

export interface SaveAsset {
  /** Pack-relative asset path, `assets/textures/<ref>.png`. */
  path: string;
  /** Base64 of the PNG. */
  bytes: string;
}

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
    if (referenced.has(reference)) assets.push({ path: textureAssetPath(reference), bytes });
  }
  // The op wants at least one edit; a pixels-only save rewrites `layers` with the value it already has.
  if (!edits.length && assets.length) set('layers', current.layers);
  return { edits, assets };
}

export function saveRequest(pack: string, path: string, rev: number, plan: SavePlan): Record<string, unknown> {
  return { pack, path, rev, edits: plan.edits, ...(plan.assets.length ? { assets: plan.assets } : {}) };
}

/** Sends the plan as one `authoring.setFields` call. */
export function sendSave(channel: RpcChannel, pack: string, path: string, rev: number, plan: SavePlan) {
  return channel.call('authoring.setFields', saveRequest(pack, path, rev, plan));
}

export function base64Of(bytes: Uint8Array): string {
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}
