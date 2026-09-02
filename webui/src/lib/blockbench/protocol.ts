/**
 * Parent side of the Blockbench bridge (web.md §6, architecture.md §9.3):
 * the message shapes crossing the iframe boundary plus the pure save-back
 * math — texture mapping and model normalization — pinned by vitest.
 */

/** Marker present on every message in both directions. */
export const BRIDGE_MARK = '__materione';

export interface SaveTexture {
  id: string;
  /** Blockbench's javaTextureLink(), e.g. `item/ruby` or `sample:block/x`. */
  link: string;
  name: string;
  /** PNG data URL. */
  dataUrl: string;
}

export interface SaveData {
  projectName: string;
  /** Java block/item model JSON. */
  model: string;
  textures: SaveTexture[];
  /** The .bbmodel project source (JSON string). */
  project: string;
}

export interface TextureTarget {
  link: string;
  /** Extension-less texture path under assets/textures/, e.g. `item/ruby`. */
  target: string;
}

/** True when the data looks like a bridge message (marker + string type). */
export function isBridgeFrame(data: unknown): data is Record<string, unknown> & { type: string } {
  return (
    typeof data === 'object' &&
    data !== null &&
    (data as Record<string, unknown>)[BRIDGE_MARK] === true &&
    typeof (data as Record<string, unknown>).type === 'string'
  );
}

/** Strips the `data:image/png;base64,` prefix down to raw base64. */
export function dataUrlToBase64(dataUrl: string): string {
  const comma = dataUrl.indexOf(',');
  return comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl;
}

/** A texture's default upload target: namespace dropped, path kept. */
export function defaultTextureTarget(save: SaveTexture): string {
  const withoutNamespace = save.link.includes(':')
    ? save.link.slice(save.link.indexOf(':') + 1)
    : save.link;
  const stripped = withoutNamespace.replace(/^#/, '').replace(/^\/+/, '');
  if (stripped === '') {
    return 'item/' + save.id;
  }
  // a bare ref like `#gem` names no directory — default to item/
  return stripped.includes('/') ? stripped : 'item/' + stripped;
}

/** Extension-safe id for model/source path prefills. */
export function sanitizeId(name: string): string {
  const clean = name.toLowerCase().replace(/[^a-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '');
  return clean || 'model';
}

/**
 * Rewrites the exported model's texture references onto the uploaded
 * targets as `<pack>:<target>` refs (Materione's model texture convention).
 * A reference matches a target when it equals the texture's link, its
 * namespaced form, or the texture id. Returns the normalized model JSON
 * plus the links left dangling (no target chosen) for the UI to warn.
 */
export function normalizeModel(
  modelJson: string,
  pack: string,
  targets: TextureTarget[],
): { model: string; unresolved: string[] } {
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(modelJson) as Record<string, unknown>;
  } catch {
    return { model: modelJson, unresolved: [] };
  }
  const byKey = new Map<string, string>();
  for (const target of targets) {
    // javaTextureLink() carries a namespace only when the texture set one;
    // the exported model may reference either form, so both map
    const bare = target.link.includes(':')
      ? target.link.slice(target.link.indexOf(':') + 1)
      : target.link;
    for (const key of new Set([target.link, bare, `#${target.link}`, `#${bare}`])) {
      if (key && key !== '#') {
        byKey.set(key, `${pack}:${target.target}`);
      }
    }
  }
  const textures = parsed['textures'];
  const unresolved = new Set<string>();
  if (textures && typeof textures === 'object') {
    for (const [key, value] of Object.entries(textures as Record<string, unknown>)) {
      if (typeof value !== 'string') continue;
      const mapped = byKey.get(value);
      if (mapped) {
        (textures as Record<string, unknown>)[key] = mapped;
      } else if (!value.startsWith('#') && !value.startsWith(pack + ':')) {
        unresolved.add(value);
      }
    }
  }
  return { model: JSON.stringify(parsed), unresolved: [...unresolved] };
}
