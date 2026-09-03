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
 * The pinned build has exactly one Java format — "Java Block/Item",
 * `java_block` — and writes `.bbmodel` files at format version 5.0. We
 * synthesise projects at the same version so the vendored build loads them
 * without an upgrade prompt; Blockbench only warns about files from a *newer*
 * version, so a pin bump cannot break this.
 */
export const MODEL_FORMAT = 'java_block';
export const BBMODEL_VERSION = '5.0';

export interface SynthesisOptions {
  /** Owning resource id — becomes the project name. */
  name: string;
  /** Where the content lives ("items", "blocks", "furnitures"). */
  typeDirectory: string;
  /** Texture link and PNG data URL to attach, when the owner has one. */
  texture?: { link: string; dataUrl: string };
  /** Injected so tests are deterministic. */
  uuid?: () => string;
}

/**
 * A minimal, valid `.bbmodel` for an entry that has never been modelled.
 *
 * This is what removes the start screen: opening "Edit model" must drop the
 * author straight into their own item, not into a format picker. Items start
 * as a thin plate carrying their texture (the familiar way to build a custom
 * item model); blocks and furniture start as a full cube.
 */
export function synthesiseProject(options: SynthesisOptions): string {
  const uuid = options.uuid ?? (() => crypto.randomUUID());
  const flat = options.typeDirectory === 'items';
  const from = flat ? [0, 0, 7.5] : [0, 0, 0];
  const to = flat ? [16, 16, 8.5] : [16, 16, 16];
  const uv: [number, number, number, number] = [0, 0, 16, 16];
  const faceNames = ['north', 'east', 'south', 'west', 'up', 'down'] as const;
  const faces: Record<string, { uv: number[]; texture: number | null }> = {};
  for (const face of faceNames) {
    faces[face] = { uv: [...uv], texture: options.texture ? 0 : null };
  }
  const elementId = uuid();
  const project = {
    meta: {
      format_version: BBMODEL_VERSION,
      model_format: MODEL_FORMAT,
      box_uv: false,
    },
    name: options.name,
    model_identifier: options.name,
    resolution: { width: 16, height: 16 },
    elements: [
      {
        name: options.name,
        box_uv: false,
        rescale: false,
        locked: false,
        from,
        to,
        autouv: 0,
        color: 0,
        origin: [8, 8, 8],
        faces,
        type: 'cube',
        uuid: elementId,
      },
    ],
    outliner: [elementId],
    textures: options.texture
      ? [
          {
            path: '',
            name: `${options.name}.png`,
            folder: options.typeDirectory === 'items' ? 'item' : 'block',
            namespace: '',
            id: '0',
            particle: true,
            render_mode: 'default',
            visible: true,
            mode: 'bitmap',
            saved: false,
            uuid: uuid(),
            relative_path: '',
            source: options.texture.dataUrl,
          },
        ]
      : [],
  };
  return JSON.stringify(project);
}

/**
 * Everything a one-click save needs, derived from the owning resource rather
 * than asked for in a dialog: where the compiled model goes, where the
 * `.bbmodel` source is kept, and the `<pack>:<path>` reference the entry's
 * `model:` field will carry.
 */
export interface ModelTargets {
  modelPath: string;
  sourcePath: string;
  modelRef: string;
}

export function modelTargetsFor(pack: string, ownerId: string, existingRef = ''): ModelTargets {
  // An entry that already points somewhere keeps pointing there; renaming a
  // model behind the author's back would break every reference to it.
  const reference = existingRef.includes(':')
    ? existingRef.slice(existingRef.indexOf(':') + 1)
    : existingRef;
  const relative = reference.trim() || sanitizeId(ownerId);
  const namespace = existingRef.includes(':') ? existingRef.slice(0, existingRef.indexOf(':')) : pack;
  return {
    modelPath: `assets/models/${relative}.json`,
    sourcePath: `assets/models/source/${relative}.bbmodel`,
    modelRef: `${namespace}:${relative}`,
  };
}

/** Where a model's cached render lives — authoring metadata, never shipped. */
export function thumbnailPath(modelRef: string): string {
  const relative = modelRef.includes(':') ? modelRef.slice(modelRef.indexOf(':') + 1) : modelRef;
  return `.materione/thumbnails/${relative}.png`;
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
