/**
 * Live inventory-style previews of Java block/item models (pure part).
 *
 * The GUI view is orthographic, so every element face projects to a
 * parallelogram and an affine canvas transform maps its UV rectangle exactly —
 * no 3D engine is needed. This module resolves a model (parent chain,
 * texture variables, display.gui) and turns it into sorted, shaded screen
 * faces; `model-preview.ts` paints them. Vanilla parents are Materione's own
 * minimal shape definitions below, not copies of Mojang's files.
 */

export type Vec3 = [number, number, number];
export type Direction = 'down' | 'up' | 'north' | 'south' | 'west' | 'east';

export interface ModelFace {
  texture?: string;
  uv?: number[];
  rotation?: number;
}

export interface ModelElement {
  from?: number[];
  to?: number[];
  rotation?: { origin?: number[]; axis?: string; angle?: number; rescale?: boolean };
  shade?: boolean;
  faces?: Partial<Record<Direction, ModelFace>>;
}

export interface DisplayTransform {
  rotation?: number[];
  translation?: number[];
  scale?: number[];
}

export interface ModelJson {
  parent?: string;
  textures?: Record<string, unknown>;
  elements?: ModelElement[];
  display?: Record<string, DisplayTransform | undefined>;
  gui_light?: string;
}

/** A texture the painter must load: a pack asset, or the neutral placeholder. */
export type TextureSource = { pack: string; path: string } | { placeholder: true };

export interface ResolvedModel {
  /** 'elements' draws 3D faces; 'flat' stacks item layers (item/generated). */
  kind: 'elements' | 'flat';
  elements: ModelElement[];
  textures: Record<string, string>;
  gui: DisplayTransform;
  shaded: boolean;
}

export interface ScreenFace {
  /** Screen corners TL, TR, BR, BL in canvas pixels. */
  corners: [number, number][];
  /** Canvas transform (a, b, c, d, e, f) mapping 16×16 UV space onto the face. */
  transform: [number, number, number, number, number, number];
  texture: TextureSource;
  shade: number;
  depth: number;
}

export interface ModelLoader {
  /** Reads a pack model JSON; rejects when missing or unreadable. */
  readModel(pack: string, path: string): Promise<ModelJson>;
}

export const MAX_PARENT_DEPTH = 16;
export const MAX_ELEMENTS = 512;

const BLOCK_GUI: DisplayTransform = { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] };
const FLAT_PARENTS = new Set(['item/generated', 'item/handheld', 'item/handheld_rod', 'item/handheld_mace', 'builtin/generated']);

function face(texture: string, uv?: number[]): ModelFace {
  return uv ? { texture, uv } : { texture };
}

function box(from: Vec3, to: Vec3, faces: Partial<Record<Direction, ModelFace>>): ModelElement {
  return { from, to, faces };
}

const CUBE_FACES = (): Partial<Record<Direction, ModelFace>> => ({
  down: face('#down'), up: face('#up'), north: face('#north'),
  south: face('#south'), west: face('#west'), east: face('#east'),
});

const SIDES = ['north', 'south', 'west', 'east'] as const;

/** The vanilla parents Materione generates or that hand-authored models commonly use. */
export const BUILTIN_PARENTS: Record<string, ModelJson> = {
  'block/block': { display: { gui: BLOCK_GUI } },
  'block/cube': { parent: 'block/block', elements: [box([0, 0, 0], [16, 16, 16], CUBE_FACES())] },
  'block/cube_all': {
    parent: 'block/cube',
    textures: { particle: '#all', down: '#all', up: '#all', north: '#all', south: '#all', west: '#all', east: '#all' },
  },
  'block/cube_column': {
    parent: 'block/cube',
    textures: { particle: '#side', down: '#end', up: '#end', north: '#side', south: '#side', west: '#side', east: '#side' },
  },
  'block/cube_bottom_top': {
    parent: 'block/cube',
    textures: { particle: '#side', down: '#bottom', up: '#top', north: '#side', south: '#side', west: '#side', east: '#side' },
  },
  'block/orientable': {
    parent: 'block/cube',
    textures: { particle: '#front', down: '#top', up: '#top', north: '#front', south: '#side', west: '#side', east: '#side' },
  },
  // No parent and no display.gui, as in the game: the planes face the camera head-on.
  'block/cross': {
    elements: [
      {
        from: [0.8, 0, 8], to: [15.2, 16, 8],
        rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true },
        shade: false,
        faces: { north: face('#cross', [0, 0, 16, 16]), south: face('#cross', [0, 0, 16, 16]) },
      },
      {
        from: [8, 0, 0.8], to: [8, 16, 15.2],
        rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true },
        shade: false,
        faces: { west: face('#cross', [0, 0, 16, 16]), east: face('#cross', [0, 0, 16, 16]) },
      },
    ],
  },
  'block/slab': {
    parent: 'block/block',
    elements: [box([0, 0, 0], [16, 8, 16], {
      down: face('#bottom', [0, 0, 16, 16]), up: face('#top', [0, 0, 16, 16]),
      ...Object.fromEntries(SIDES.map((side) => [side, face('#side', [0, 8, 16, 16])])),
    })],
  },
  'block/slab_top': {
    parent: 'block/block',
    elements: [box([0, 8, 0], [16, 16, 16], {
      down: face('#bottom', [0, 0, 16, 16]), up: face('#top', [0, 0, 16, 16]),
      ...Object.fromEntries(SIDES.map((side) => [side, face('#side', [0, 0, 16, 8])])),
    })],
  },
  'block/stairs': {
    parent: 'block/block',
    display: { gui: { rotation: [30, 135, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] } },
    elements: [
      box([0, 0, 0], [16, 8, 16], {
        down: face('#bottom'), up: face('#top'),
        north: face('#side'), south: face('#side'), west: face('#side'), east: face('#side'),
      }),
      box([8, 8, 0], [16, 16, 16], {
        up: face('#top'), north: face('#side'), south: face('#side'), west: face('#side'), east: face('#side'),
      }),
    ],
  },
};

/** Split `ns:path` (no namespace means `minecraft`, as in the game). */
export function parseRef(ref: string): { namespace: string; path: string } | undefined {
  const trimmed = ref.trim();
  const colon = trimmed.indexOf(':');
  const namespace = colon < 0 ? 'minecraft' : trimmed.slice(0, colon);
  const path = colon < 0 ? trimmed : trimmed.slice(colon + 1);
  if (!/^[a-z0-9_.-]+$/.test(namespace) || !/^[a-z0-9_./-]+$/.test(path)
      || path.includes('..') || path.startsWith('/')) {
    return undefined;
  }
  return { namespace, path };
}

/**
 * Walk the parent chain: child textures win, the nearest `elements` and
 * `display.gui` win. An unknown vanilla parent ends the chain (its
 * geometry is unknowable here); a model that then has neither elements nor
 * item layers cannot be drawn and rejects, so the caller falls back.
 */
export async function resolveModel(
  root: ModelJson,
  packs: readonly string[],
  loader: ModelLoader,
): Promise<ResolvedModel> {
  const textures: Record<string, string> = {};
  let elements: ModelElement[] | undefined;
  let gui: DisplayTransform | undefined;
  let guiLight: string | undefined;
  let flat = false;
  let current: ModelJson | undefined = root;

  for (let depth = 0; current && depth <= MAX_PARENT_DEPTH; depth++) {
    for (const [key, value] of Object.entries(current.textures ?? {})) {
      if (typeof value === 'string' && !(key in textures)) textures[key] = qualifyTexture(value);
    }
    if (!elements && Array.isArray(current.elements)) elements = current.elements;
    if (!gui && current.display?.gui) gui = current.display.gui;
    if (!guiLight && typeof current.gui_light === 'string') guiLight = current.gui_light;
    if (!current.parent) break;

    const parent = parseRef(current.parent);
    if (!parent) throw new Error(`Unsafe parent reference: ${current.parent}`);
    if (parent.namespace === 'minecraft') {
      if (FLAT_PARENTS.has(parent.path)) {
        flat = true;
        break;
      }
      current = BUILTIN_PARENTS[parent.path];
      continue;
    }
    if (!packs.includes(parent.namespace)) throw new Error(`Unknown pack in parent: ${current.parent}`);
    current = await loader.readModel(parent.namespace, `assets/models/${parent.path}.json`);
  }

  if (elements && elements.length) {
    return {
      kind: 'elements',
      elements: elements.slice(0, MAX_ELEMENTS),
      textures,
      gui: gui ?? {},
      // Items inherit "front" lighting from item/generated; blocks default to "side".
      shaded: (guiLight ?? (flat ? 'front' : 'side')) === 'side',
    };
  }
  if (flat && Object.keys(textures).some((key) => /^layer\d+$/.test(key))) {
    return { kind: 'flat', elements: [], textures, gui: {}, shaded: false };
  }
  throw new Error('The model has no elements this preview can draw.');
}

/** Keep `#var` references; a bare texture ref means `minecraft:`, as in the game. */
function qualifyTexture(value: string): string {
  return value.startsWith('#') || value.includes(':') ? value : `minecraft:${value}`;
}

/** Follow `#var` chains to a texture source; vanilla and unknown refs use the placeholder. */
export function textureSource(
  ref: string | undefined,
  textures: Record<string, string>,
  packs: readonly string[],
): TextureSource {
  let value = ref;
  for (let hops = 0; value?.startsWith('#') && hops < 16; hops++) {
    value = textures[value.slice(1)];
  }
  if (!value || value.startsWith('#')) return { placeholder: true };
  const parsed = parseRef(value);
  if (!parsed || parsed.namespace === 'minecraft' || !packs.includes(parsed.namespace)) {
    return { placeholder: true };
  }
  const path = parsed.path.endsWith('.png') ? parsed.path : `${parsed.path}.png`;
  return { pack: parsed.namespace, path: `assets/textures/${path}` };
}

/** Item layers in draw order (layer0 first). */
export function flatLayers(model: ResolvedModel, packs: readonly string[]): TextureSource[] {
  return Object.keys(model.textures)
    .filter((key) => /^layer\d+$/.test(key))
    .sort((a, b) => Number(a.slice(5)) - Number(b.slice(5)))
    .map((key) => textureSource(`#${key}`, model.textures, packs));
}

const NORMALS: Record<Direction, Vec3> = {
  down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0],
};

/** Face corners TL, TR, BR, BL as seen from outside the element. */
function faceCorners(direction: Direction, f: Vec3, t: Vec3): Vec3[] {
  switch (direction) {
    case 'north': return [[t[0], t[1], f[2]], [f[0], t[1], f[2]], [f[0], f[1], f[2]], [t[0], f[1], f[2]]];
    case 'south': return [[f[0], t[1], t[2]], [t[0], t[1], t[2]], [t[0], f[1], t[2]], [f[0], f[1], t[2]]];
    case 'west': return [[f[0], t[1], f[2]], [f[0], t[1], t[2]], [f[0], f[1], t[2]], [f[0], f[1], f[2]]];
    case 'east': return [[t[0], t[1], t[2]], [t[0], t[1], f[2]], [t[0], f[1], f[2]], [t[0], f[1], t[2]]];
    case 'up': return [[f[0], t[1], f[2]], [t[0], t[1], f[2]], [t[0], t[1], t[2]], [f[0], t[1], t[2]]];
    case 'down': return [[f[0], f[1], t[2]], [t[0], f[1], t[2]], [t[0], f[1], f[2]], [f[0], f[1], f[2]]];
  }
}

/** The game's automatic UV when a face omits `uv`. */
export function defaultUv(direction: Direction, f: Vec3, t: Vec3): [number, number, number, number] {
  switch (direction) {
    case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
    case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
    case 'west': return [f[2], 16 - t[1], t[2], 16 - f[1]];
    case 'east': return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
    case 'up': return [f[0], f[2], t[0], t[2]];
    case 'down': return [f[0], 16 - t[2], t[0], 16 - f[2]];
  }
}

type Mat3 = [Vec3, Vec3, Vec3];

function multiply(a: Mat3, b: Mat3): Mat3 {
  const out: Mat3 = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (let row = 0; row < 3; row++) {
    for (let col = 0; col < 3; col++) {
      out[row]![col] = a[row]![0] * b[0]![col]! + a[row]![1] * b[1]![col]! + a[row]![2] * b[2]![col]!;
    }
  }
  return out;
}

function apply(m: Mat3, v: Vec3): Vec3 {
  return [
    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
  ];
}

function rotationAbout(axis: string, degrees: number): Mat3 {
  const r = (degrees * Math.PI) / 180;
  const c = Math.cos(r);
  const s = Math.sin(r);
  if (axis === 'x') return [[1, 0, 0], [0, c, -s], [0, s, c]];
  if (axis === 'y') return [[c, 0, s], [0, 1, 0], [-s, 0, c]];
  return [[c, -s, 0], [s, c, 0], [0, 0, 1]];
}

/** display.gui rotation: X·Y·Z applied to the vector (Z first), as the game composes it. */
export function guiRotation(rotation: number[] | undefined): Mat3 {
  const [x = 0, y = 0, z = 0] = rotation ?? [];
  return multiply(multiply(rotationAbout('x', x), rotationAbout('y', y)), rotationAbout('z', z));
}

function vec(value: number[] | undefined, fallback: Vec3): Vec3 {
  return Array.isArray(value) && value.length === 3 && value.every((n) => Number.isFinite(n))
    ? [value[0]!, value[1]!, value[2]!] : fallback;
}

/** Inventory GUI shading: top 1.0, left side 0.8, right side 0.6 for a default cube. */
export function guiShade(normal: Vec3): number {
  return Math.min(1, Math.max(0.45, 0.787 + 0.246 * normal[1] - 0.1414 * normal[0]));
}

/** Project a resolved element model to painter-ordered faces on a `size`-pixel canvas. */
export function projectFaces(model: ResolvedModel, packs: readonly string[], size: number): ScreenFace[] {
  const display = guiRotation(model.gui.rotation);
  const scale = vec(model.gui.scale, [1, 1, 1]);
  const translation = vec(model.gui.translation, [0, 0, 0]);
  const unit = size / 16;
  const faces: ScreenFace[] = [];

  for (const element of model.elements) {
    const from = vec(element.from, [0, 0, 0]);
    const to = vec(element.to, [16, 16, 16]);
    const rotation = element.rotation;
    const axis = rotation?.axis ?? 'y';
    const angle = Number(rotation?.angle ?? 0);
    const local = rotation && Number.isFinite(angle) && angle !== 0 ? rotationAbout(axis, angle) : undefined;
    const origin = vec(rotation?.origin, [8, 8, 8]);
    const rescale = rotation?.rescale && local ? 1 / Math.cos((Math.abs(angle) * Math.PI) / 180) : 1;

    const place = (point: Vec3): Vec3 => {
      let p: Vec3 = [point[0] - origin[0], point[1] - origin[1], point[2] - origin[2]];
      if (local) {
        p = apply(local, p);
        if (rescale !== 1) {
          p = [axis === 'x' ? p[0] : p[0] * rescale, axis === 'y' ? p[1] : p[1] * rescale, axis === 'z' ? p[2] : p[2] * rescale];
        }
      }
      const centered: Vec3 = [
        (p[0] + origin[0] - 8) * scale[0],
        (p[1] + origin[1] - 8) * scale[1],
        (p[2] + origin[2] - 8) * scale[2],
      ];
      const v = apply(display, centered);
      return [v[0] + translation[0], v[1] + translation[1], v[2] + translation[2]];
    };

    for (const [direction, spec] of Object.entries(element.faces ?? {}) as [Direction, ModelFace | undefined][]) {
      if (!spec || !(direction in NORMALS)) continue;
      let normal = NORMALS[direction];
      if (local) normal = apply(local, normal);
      normal = apply(display, normal);
      if (normal[2] <= 1e-6) continue; // facing away from the camera

      const world = faceCorners(direction, from, to).map(place);
      const corners = world.map(([x, y]) => [size / 2 + x * unit, size / 2 - y * unit] as [number, number]);
      const transform = uvTransform(corners, faceUv(direction, spec, from, to), spec.rotation ?? 0);
      if (!transform) continue;
      const length = Math.hypot(normal[0], normal[1], normal[2]) || 1;
      faces.push({
        corners,
        transform,
        texture: textureSource(spec.texture, model.textures, packs),
        shade: model.shaded && element.shade !== false
          ? guiShade([normal[0] / length, normal[1] / length, normal[2] / length]) : 1,
        depth: world.reduce((sum, point) => sum + point[2], 0) / 4,
      });
    }
  }
  return faces.sort((a, b) => a.depth - b.depth);
}

function faceUv(direction: Direction, spec: ModelFace, from: Vec3, to: Vec3): [number, number, number, number] {
  const uv = spec.uv;
  if (Array.isArray(uv) && uv.length === 4 && uv.every((n) => Number.isFinite(n))) {
    return [uv[0]!, uv[1]!, uv[2]!, uv[3]!];
  }
  return defaultUv(direction, from, to);
}

/**
 * Affine map from UV space to the face's screen parallelogram. The face's
 * `rotation` turns the texture clockwise by moving which UV corner lands on
 * the face's top-left. Zero-width UVs sample a sliver instead of vanishing.
 */
export function uvTransform(
  corners: [number, number][],
  uv: [number, number, number, number],
  rotation: number,
): ScreenFace['transform'] | undefined {
  let [u0, v0, u1, v1] = uv;
  if (u0 === u1) u1 = u0 + 0.01;
  if (v0 === v1) v1 = v0 + 0.01;
  const quad: [number, number][] = [[u0, v0], [u1, v0], [u1, v1], [u0, v1]];
  const turns = ((Math.round(rotation / 90) % 4) + 4) % 4;
  const at = (corner: number) => quad[(corner - turns + 4) % 4]!;
  const q0 = at(0);
  const q1 = at(1);
  const q3 = at(3);
  const [p0, p1, , p3] = corners as [[number, number], [number, number], [number, number], [number, number]];
  const du1 = [q1[0] - q0[0], q1[1] - q0[1]];
  const du3 = [q3[0] - q0[0], q3[1] - q0[1]];
  const det = du1[0]! * du3[1]! - du3[0]! * du1[1]!;
  if (Math.abs(det) < 1e-9) return undefined;
  const dp1 = [p1[0] - p0[0], p1[1] - p0[1]];
  const dp3 = [p3[0] - p0[0], p3[1] - p0[1]];
  // A = [dp1 dp3] · inverse([du1 du3])
  const inv = [du3[1]! / det, -du3[0]! / det, -du1[1]! / det, du1[0]! / det];
  const a = dp1[0]! * inv[0]! + dp3[0]! * inv[2]!;
  const c = dp1[0]! * inv[1]! + dp3[0]! * inv[3]!;
  const b = dp1[1]! * inv[0]! + dp3[1]! * inv[2]!;
  const d = dp1[1]! * inv[1]! + dp3[1]! * inv[3]!;
  return [a, b, c, d, p0[0] - (a * q0[0] + c * q0[1]), p0[1] - (b * q0[0] + d * q0[1])];
}
