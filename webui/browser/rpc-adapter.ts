import type { AssetRef, ResourceRef, ResourceSummary, RpcChannel, TypeInfo } from '../src/lib/api';
import type { PushFrame } from '../src/lib/events';
import type { Response } from '../src/lib/protocol/wire';
import type { BrowserTestControls, FixtureAuditEntry, RecordedRpcCall, RecordedWrite } from './contract';

export interface FixtureDocument {
  pack: string;
  path: string;
  rev: number;
  raw: string;
  parsed: Record<string, Record<string, unknown>>;
}

export interface FixtureAsset {
  path: string;
  kind: string;
  data: string;
  bytes: number;
}

interface FieldEdit {
  field: string[];
  value: unknown;
}

const PACK = 'fixture_pack';
const PIXEL_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1WYAAAAASUVORK5CYII=';
// The existing 8x8 sample pack texture, not a Minecraft asset.
const GLYPH_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEUlEQVR4nGM4oaGBFTEMLQkAgl1GAWqNFmsAAAAASUVORK5CYII=';

const ITEM_TYPE: TypeInfo = {
  name: 'item',
  directory: 'items',
  displayName: 'Item',
  description: 'Deterministic browser regression resources.',
  parameters: [
    {
      id: 'display_name',
      type: 'string',
      required: false,
      label: 'Display name',
      section: 'identity',
      order: 0,
      uiHint: 'formatted-text',
      example: 'Ruby sword',
    },
    {
      id: 'material',
      type: 'enum',
      required: true,
      label: 'Base material',
      section: 'identity',
      order: 10,
      uiHint: 'material',
      allowedValues: ['DIAMOND_SWORD', 'BLAZE_ROD', 'STICK'],
      example: 'NETHERITE_SWORD',
    },
    {
      id: 'template',
      type: 'reference',
      required: false,
      label: 'Template',
      section: 'identity',
      order: 20,
      advanced: true,
      uiHint: 'reference',
      referenceKind: 'template',
    },
    {
      id: 'texture',
      type: 'reference',
      required: false,
      label: 'Texture',
      section: 'appearance',
      order: 10,
      uiHint: 'texture',
      referenceKind: 'texture',
      example: 'item/my_texture',
    },
    {
      id: 'model',
      type: 'reference',
      required: false,
      label: '3D model',
      section: 'appearance',
      order: 20,
      advanced: false,
      uiHint: 'model',
      referenceKind: 'model',
    },
    {
      id: 'model_parent',
      type: 'string',
      required: false,
      default: 'item/generated',
      label: 'Model style',
      section: 'appearance',
      order: 30,
      advanced: true,
      uiHint: 'text',
      example: 'item/generated',
    },
    {
      id: 'lore',
      type: 'list',
      required: false,
      label: 'Lore',
      section: 'gameplay',
      order: 10,
      uiHint: 'formatted-lines',
      example: 'A very special item',
    },
    {
      id: 'components',
      type: 'raw',
      required: false,
      label: 'Vanilla components',
      section: 'gameplay',
      order: 90,
      advanced: true,
      uiHint: 'advanced',
    },
    {
      id: 'jukebox_song',
      type: 'reference',
      required: false,
      label: 'Jukebox song',
      section: 'gameplay',
      order: 20,
      advanced: true,
      uiHint: 'reference',
      referenceKind: 'sound',
    },
    {
      id: 'mechanics',
      type: 'list',
      required: false,
      label: 'Mechanics',
      section: 'behavior',
      order: 10,
      uiHint: 'structured-list',
      referenceKind: 'mechanic',
    },
    {
      id: 'power',
      type: 'decimal',
      required: false,
      label: 'Power',
      section: 'gameplay',
      example: '1.5',
    },
    {
      id: 'custom_model_data',
      type: 'integer',
      required: false,
      label: 'Custom model data',
      section: 'advanced',
      advanced: true,
    },
  ],
};

const BLOCK_TYPE: TypeInfo = {
  name: 'block',
  directory: 'blocks',
  displayName: 'Blocks',
  description: 'A second registered type for per-type catalog preferences.',
  parameters: [
    {
      id: 'display_name',
      type: 'string',
      required: true,
      label: 'Display name',
      section: 'identity',
      uiHint: 'formatted-text',
    },
  ],
};

const GLYPH_TYPE: TypeInfo = {
  name: 'glyph',
  directory: 'glyphs',
  displayName: 'Glyph',
  description: 'A glyph with a source texture and a glyph with a missing texture.',
  parameters: [
    {
      id: 'texture',
      type: 'reference',
      required: true,
      label: 'Texture',
      section: 'appearance',
      uiHint: 'texture',
      referenceKind: 'texture',
    },
  ],
};

const RESOURCES: ResourceRef[] = [
  { pack: PACK, type: 'items', path: 'items/ruby_blade.yml', id: 'ruby_blade' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_a' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_b' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_c' },
  { pack: PACK, type: 'blocks', path: 'blocks/ruby_crate.yml', id: 'ruby_crate' },
  { pack: PACK, type: 'glyphs', path: 'glyphs/icons.yml', id: 'heart_icon' },
  { pack: PACK, type: 'glyphs', path: 'glyphs/icons.yml', id: 'missing_icon' },
];

/**
 * Opt-in (`?modelGallery`) blocks with live-render specs, kept out of the
 * default fixture so existing specs see the same catalog and RPC calls.
 */
const CRATE_PNG = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAnklEQVR4nGOIMZH5TwlmABHTYgz+H+kJAuNNVQ5wNj4M0gM3ACaATSE2A2Fq4QbAFOEyBBm3eMnB2XADNlQ4wTHIkHlpBihiyHLIfBQvYHMisjewuQ7FAHS/ImvAZiCGAdgCD6QRX7hgDQN0P8P8jS1cMFyA7A18XoCpJRiIuAzEGoj4FGJLA1jDAF8awJYWqJeU0Z2GDyMne7gBlGAAtARNyxEyZukAAAAASUVORK5CYII=';
function lampModel(crateTexture: string) {
  return {
  parent: 'minecraft:block/block',
  textures: { wood: crateTexture, stone: 'minecraft:block/stone', particle: crateTexture },
  elements: [
    { from: [2, 0, 2], to: [14, 4, 14], faces: Object.fromEntries(
      ['down', 'up', 'north', 'south', 'west', 'east'].map((side) => [side, { texture: '#stone' }])) },
    { from: [6, 4, 6], to: [10, 12, 10], faces: Object.fromEntries(
      ['up', 'north', 'south', 'west', 'east'].map((side) => [side, { texture: '#wood' }])) },
    { from: [4, 12, 4], to: [12, 16, 12], rotation: { origin: [8, 14, 8], axis: 'y', angle: 22.5 },
      faces: Object.fromEntries(
        ['down', 'up', 'north', 'south', 'west', 'east'].map((side) => [side, { texture: '#wood' }])) },
  ],
  };
}

interface GalleryEntry { ref: ResourceRef; name: string; render: NonNullable<ResourceSummary['render']> }

/** The live-render blocks for `pack`; `includeFallback` adds the unknown-parent block. */
function galleryEntries(pack: string, includeFallback = true): GalleryEntry[] {
  const PACK = pack;
  const CRATE_TEXTURE = `${pack}:block/crate`;
  const entries: GalleryEntry[] = [
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_cube' },
    name: 'Crate Cube',
    render: { pack: PACK, model: { parent: 'block/cube_all', textures: { all: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_stairs' },
    name: 'Crate Stairs',
    render: { pack: PACK, model: { parent: 'block/stairs',
      textures: { side: CRATE_TEXTURE, top: CRATE_TEXTURE, bottom: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_slab' },
    name: 'Crate Slab',
    render: { pack: PACK, model: { parent: 'block/slab',
      textures: { side: CRATE_TEXTURE, top: CRATE_TEXTURE, bottom: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_sprout' },
    name: 'Crate Sprout',
    render: { pack: PACK, model: { parent: 'block/cross', textures: { cross: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'stone_lamp' },
    name: 'Stone Lamp',
    render: { pack: PACK, path: 'assets/models/block/stone_lamp.json' },
  },
  ];
  if (includeFallback) entries.push(
  {
    // Geometry lives in a vanilla parent the editor cannot know: falls back.
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'mystery_anvil' },
    name: 'Mystery Anvil',
    render: { pack: PACK, model: { parent: 'minecraft:block/anvil', textures: { body: CRATE_TEXTURE } } },
  });
  return entries;
}

const DIAGNOSTICS: Record<string, unknown>[] = [
  {
    code: 'MTR-9001',
    severity: 'ERROR',
    message: 'Fixture diagnostic with a stable source.',
    resource: 'fixture_pack:ruby_blade',
    location: 'C:\\server\\plugins\\Materione\\content\\packs\\fixture_pack\\items\\ruby_blade.yml:3:3',
    hint: 'This entry is only returned by the browser fixture.',
  },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function numberValue(value: unknown, fallback = Number.NaN): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry: unknown) => typeof entry === 'string');
}

function parseEdits(value: unknown): FieldEdit[] | null {
  if (!Array.isArray(value)) return null;
  const edits: FieldEdit[] = [];
  for (const entry of value) {
    if (!isRecord(entry) || !stringArray(entry.field) || entry.field.length < 2) return null;
    edits.push({ field: entry.field, value: entry.value });
  }
  return edits;
}

function parseScalar(value: string): unknown {
  const trimmed = value.trim();
  if (trimmed === 'true') return true;
  if (trimmed === 'false') return false;
  if (trimmed === 'null' || trimmed === '~') return null;
  if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith('"') || trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      return parsed;
    } catch {
      return trimmed;
    }
  }
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1).replaceAll("''", "'");
  }
  return trimmed;
}

function parseFixtureYaml(raw: string): Record<string, Record<string, unknown>> {
  const parsed: Record<string, Record<string, unknown>> = {};
  let root: string | null = null;
  let listField: string | null = null;

  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const rootMatch = /^([A-Za-z0-9_-]+):\s*$/.exec(line);
    if (rootMatch) {
      root = rootMatch[1] ?? null;
      if (root) parsed[root] = {};
      listField = null;
      continue;
    }
    const fieldMatch = /^  ([A-Za-z0-9_-]+):(?:\s*(.*))?$/.exec(line);
    if (root && fieldMatch) {
      const key = fieldMatch[1];
      const scalar = fieldMatch[2] ?? '';
      if (!key) continue;
      if (!scalar) {
        parsed[root]![key] = [];
        listField = key;
      } else {
        parsed[root]![key] = parseScalar(scalar);
        listField = null;
      }
      continue;
    }
    const itemMatch = /^    -\s*(.*)$/.exec(line);
    if (root && listField && itemMatch) {
      const list = parsed[root]![listField];
      if (Array.isArray(list)) list.push(parseScalar(itemMatch[1] ?? ''));
    }
  }
  return parsed;
}

function yamlScalar(value: unknown): string {
  if (typeof value === 'string') {
    if (/^[A-Za-z0-9_./:@+-]+$/.test(value) && !/^(true|false|null)$/i.test(value)) {
      return value;
    }
    return JSON.stringify(value);
  }
  if (value === null) return 'null';
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function serializeFixtureYaml(parsed: Record<string, Record<string, unknown>>): string {
  const lines: string[] = [];
  for (const [root, fields] of Object.entries(parsed)) {
    lines.push(`${root}:`);
    for (const [key, value] of Object.entries(fields)) {
      if (Array.isArray(value)) {
        lines.push(`  ${key}:`);
        for (const entry of value) lines.push(`    - ${yamlScalar(entry)}`);
      } else {
        lines.push(`  ${key}: ${yamlScalar(value)}`);
      }
    }
  }
  return `${lines.join('\n')}\n`;
}

function assignPath(target: Record<string, unknown>, path: string[], value: unknown): boolean {
  if (path.length === 0) return false;
  let current = target;
  for (const segment of path.slice(0, -1)) {
    const child = current[segment];
    if (!isRecord(child)) return false;
    current = child;
  }
  const last = path[path.length - 1];
  if (!last) return false;
  current[last] = structuredClone(value);
  return true;
}

function makeDocument(
  path: string,
  rev: number,
  parsed: Record<string, Record<string, unknown>>,
  pack = PACK,
): FixtureDocument {
  return { pack, path, rev, parsed, raw: serializeFixtureYaml(parsed) };
}

function initialDocuments(): Map<string, FixtureDocument> {
  const blade = makeDocument('items/ruby_blade.yml', 7, {
    ruby_blade: {
      display_name: 'Ruby Blade',
      material: 'DIAMOND_SWORD',
      model: '',
      lore: ['A deterministic browser fixture'],
      power: 1,
      custom_model_data: 41,
      future_flag: 'preserved',
    },
  });
  blade.raw = blade.raw.replaceAll('\n', '\r\n');
  const shared = makeDocument('items/shared_gems.yml', 3, {
    ruby_a: {
      display_name: 'Crimson Jewel',
      material: 'BLAZE_ROD',
      lore: [],
      future_flag: 'left-root',
    },
    ruby_b: {
      display_name: 'Ruby B',
      material: 'CUSTOM_FUTURE_MATERIAL',
      lore: ['One intact line', 'second\nembedded line'],
      future_flag: 'right-root',
    },
    ruby_c: {
      display_name: 'Malformed Lore',
      material: 'STICK',
      lore: ['One intact line', 42],
      future_flag: 'malformed-lore-root',
    },
  });
  const crate = makeDocument('blocks/ruby_crate.yml', 2, {
    ruby_crate: {
      display_name: 'Ruby Crate',
      future_flag: 'block-root',
    },
  });
  const glyphs = makeDocument('glyphs/icons.yml', 1, {
    heart_icon: { texture: 'gui/heart', height: 8, ascent: 8 },
    missing_icon: { texture: 'gui/missing', height: 8, ascent: 8 },
  });
  const documents = new Map<string, FixtureDocument>();
  documents.set(`${PACK}/${blade.path}`, blade);
  documents.set(`${PACK}/${shared.path}`, shared);
  documents.set(`${PACK}/${crate.path}`, crate);
  documents.set(`${PACK}/${glyphs.path}`, glyphs);
  return documents;
}

function assetPath(pack: string, path: string): string {
  return `${pack}/${path}`;
}

function isWriteOperation(op: string): boolean {
  return (
    op.startsWith('edit.') ||
    op === 'authoring.commit' ||
    op === 'authoring.setFields' ||
    op === 'authoring.saveModel' ||
    op === 'authoring.createResource' ||
    op === 'authoring.duplicateResource' ||
    op === 'assets.put' ||
    op === 'assets.delete' ||
    op === 'assets.move' ||
    op === 'delivery.publish' ||
    op === 'admin.settings.put'
  );
}

/**
 * Everything the adapter serves. The regression seed below is what every
 * browser spec asserts against; the cloud editor's demo (`src/lib/demo.ts`)
 * builds its own sample pack on the same in-browser backend (web.md §6, cloud landing).
 */
export interface FixtureSeed {
  pack: string;
  types: TypeInfo[];
  resources: ResourceRef[];
  documents: Map<string, FixtureDocument>;
  assets: Map<string, FixtureAsset>;
  renders: Map<string, NonNullable<ResourceSummary['render']>>;
  /** Static visual asset path per resource id. */
  visuals: Map<string, string>;
  /** Server-projected names for roots without a display_name. */
  names: Map<string, string>;
  /** Resources whose health is `error`, with their message. */
  failures: Map<string, string>;
  diagnostics: Record<string, unknown>[];
}

export function regressionSeed(): FixtureSeed {
  return {
    pack: PACK,
    types: [ITEM_TYPE, BLOCK_TYPE, GLYPH_TYPE],
    resources: [...RESOURCES],
    documents: initialDocuments(),
    assets: new Map([
      [
        assetPath(PACK, 'assets/textures/items/ruby_blade.png'),
        {
          path: 'assets/textures/items/ruby_blade.png',
          kind: 'png',
          data: PIXEL_PNG,
          bytes: 68,
        },
      ],
      [
        assetPath(PACK, 'assets/textures/gui/heart.png'),
        { path: 'assets/textures/gui/heart.png', kind: 'png', data: GLYPH_PNG, bytes: 74 },
      ],
    ]),
    renders: new Map(),
    visuals: new Map([
      ['ruby_blade', 'assets/textures/items/ruby_blade.png'],
      ['heart_icon', 'assets/textures/gui/heart.png'],
    ]),
    names: new Map([['heart_icon', 'Heart Icon'], ['missing_icon', 'Missing Icon']]),
    failures: new Map([['missing_icon', 'Texture not found: gui/missing']]),
    diagnostics: DIAGNOSTICS,
  };
}

/** Adds the live-render block gallery (its crate texture and lamp model included) to `seed`. */
export function addModelGallery(seed: FixtureSeed, includeFallback = true): void {
  const pack = seed.pack;
  const gallery: Record<string, Record<string, unknown>> = {};
  for (const entry of galleryEntries(pack, includeFallback)) {
    seed.resources.push(entry.ref);
    seed.renders.set(entry.ref.id, entry.render);
    gallery[entry.ref.id] = { display_name: entry.name };
  }
  seed.documents.set(`${pack}/blocks/gallery.yml`, makeDocument('blocks/gallery.yml', 1, gallery, pack));
  seed.assets.set(assetPath(pack, 'assets/textures/block/crate.png'),
    { path: 'assets/textures/block/crate.png', kind: 'png', data: CRATE_PNG, bytes: 214 });
  const model = new TextEncoder().encode(JSON.stringify(lampModel(`${pack}:block/crate`)));
  seed.assets.set(assetPath(pack, 'assets/models/block/stone_lamp.json'), {
    path: 'assets/models/block/stone_lamp.json', kind: 'json',
    data: btoa(String.fromCharCode(...model)), bytes: model.length,
  });
}

/** A seed document for alternative seeds such as the demo pack. */
export function seedDocument(
  pack: string,
  path: string,
  rev: number,
  parsed: Record<string, Record<string, unknown>>,
): FixtureDocument {
  return makeDocument(path, rev, parsed, pack);
}

/** A seed asset entry (base64 data) for alternative seeds. */
export function seedAsset(pack: string, path: string, data: string): [string, FixtureAsset] {
  return [assetPath(pack, path), {
    path, kind: path.split('.').pop() ?? 'bin', data, bytes: Math.floor((data.length * 3) / 4),
  }];
}

/** The regression fixture's item type, for seeds that extend it. */
export function fixtureItemType(): TypeInfo {
  return structuredClone(ITEM_TYPE);
}

/** The regression fixture's block and glyph types, for seeds that reuse them. */
export function fixtureSupportTypes(): TypeInfo[] {
  return [structuredClone(BLOCK_TYPE), structuredClone(GLYPH_TYPE)];
}

/** The regression fixture's sample glyph texture (8x8), for seeds. */
export const SAMPLE_GLYPH_PNG = GLYPH_PNG;

export class DeterministicRpcAdapter implements RpcChannel, BrowserTestControls {
  readonly #pack: string;
  readonly #types: TypeInfo[];
  readonly #documents: Map<string, FixtureDocument>;
  readonly #calls: RecordedRpcCall[] = [];
  readonly #assets: Map<string, FixtureAsset>;
  readonly #writes: RecordedWrite[] = [];
  #auditEntries: FixtureAuditEntry[] = [];
  #pushHandler: ((frame: PushFrame) => void) | null = null;
  #disconnectHandler: (() => void) | null = null;
  #nextResponseId = 0;
  #nextWriteDelay = 0;
  #conflictNext = false;
  #disconnectAfterNextWrite = false;
  #disconnected = false;
  #candidateSha256 = 'a'.repeat(64);
  #publishedSha256 = '0'.repeat(64);
  #summaryError: boolean;
  readonly #resources: ResourceRef[];
  readonly #renders: Map<string, NonNullable<ResourceSummary['render']>>;
  readonly #visuals: Map<string, string>;
  readonly #names: Map<string, string>;
  readonly #failures: Map<string, string>;
  readonly #diagnostics: Record<string, unknown>[];

  constructor(options: { summaryError?: boolean; modelGallery?: boolean; seed?: FixtureSeed } = {}) {
    this.#summaryError = options.summaryError ?? false;
    const seed = options.seed ?? regressionSeed();
    if (options.modelGallery) addModelGallery(seed);
    this.#pack = seed.pack;
    this.#types = seed.types;
    this.#resources = seed.resources;
    this.#documents = seed.documents;
    this.#assets = seed.assets;
    this.#renders = seed.renders;
    this.#visuals = seed.visuals;
    this.#names = seed.names;
    this.#failures = seed.failures;
    this.#diagnostics = seed.diagnostics;
  }

  setPushHandler(handler: (frame: PushFrame) => void): void {
    this.#pushHandler = handler;
  }

  setDisconnectHandler(handler: () => void): void {
    this.#disconnectHandler = handler;
  }

  async call(op: string, payload: Record<string, unknown> = {}): Promise<Response> {
    if (this.#disconnected) throw new Error('fixture channel is disconnected');
    this.#calls.push({ op, payload: structuredClone(payload) });
    const write: RecordedWrite | null = isWriteOperation(op)
      ? {
          op,
          payload: structuredClone(payload),
          outcome: 'pending',
        }
      : null;
    if (write) this.#writes.push(write);

    if (write && this.#nextWriteDelay > 0) {
      const delay = this.#nextWriteDelay;
      this.#nextWriteDelay = 0;
      await new Promise((resolve) => setTimeout(resolve, delay));
    }

    if (write && this.#conflictNext) {
      this.#conflictNext = false;
      write.outcome = 'conflict';
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }

    const response = this.dispatch(op, payload);
    if (write) {
      write.outcome = response.ok ? 'saved' : response.error.code === 'MTR-0034' ? 'conflict' : 'rejected';
      if (response.ok && this.#disconnectAfterNextWrite) {
        this.#disconnectAfterNextWrite = false;
        this.#disconnected = true;
        write.outcome = 'response-lost';
        this.#disconnectHandler?.();
        throw new Error('connection closed after the write was submitted');
      }
    }
    return response;
  }

  calls(): readonly RecordedRpcCall[] {
    return structuredClone(this.#calls);
  }

  writes(): readonly RecordedWrite[] {
    return structuredClone(this.#writes);
  }

  clearWrites(): void {
    this.#writes.length = 0;
  }

  delayNextWrite(milliseconds: number): void {
    this.#nextWriteDelay = Math.max(0, Math.min(milliseconds, 10_000));
  }

  conflictNextWrite(): void {
    this.#conflictNext = true;
  }

  externalEdit(pack: string, path: string): void {
    const document = this.#documents.get(`${pack}/${path}`);
    if (!document) throw new Error('fixture document does not exist');
    const root = Object.keys(document.parsed)[0];
    if (!root) throw new Error('fixture document has no resource root');
    const body = document.parsed[root];
    if (!body) throw new Error('fixture root is missing');
    body.display_name = `${stringValue(body.display_name, root)} (external edit)`;
    document.rev += 1;
    document.raw = serializeFixtureYaml(document.parsed);
    this.emitPush('push.document.changed', { pack, path, deleted: false });
  }

  disconnectAfterNextWrite(): void {
    this.#disconnectAfterNextWrite = true;
  }

  simulateDisconnect(): void {
    this.#disconnected = true;
    this.#disconnectHandler?.();
  }

  setCandidateSha256(value: string): void {
    this.#candidateSha256 = value;
  }

  deliveryState(): { candidateSha256: string; publishedSha256: string } {
    return {
      candidateSha256: this.#candidateSha256,
      publishedSha256: this.#publishedSha256,
    };
  }

  emitPush(op: string, payload: Record<string, unknown>): void {
    this.#pushHandler?.({ op, payload: structuredClone(payload) });
  }

  emitReload(payload: Record<string, unknown>): void {
    this.emitPush('push.reload', payload);
  }

  setAuditEntries(entries: readonly FixtureAuditEntry[]): void {
    this.#auditEntries = structuredClone([...entries]);
  }

  private success(op: string, data: unknown): Response {
    return {
      op,
      id: `fixture-${++this.#nextResponseId}`,
      ok: true,
      data,
    };
  }

  private failure(op: string, code: string, message: string): Response {
    return {
      op,
      id: `fixture-${++this.#nextResponseId}`,
      ok: false,
      error: { code, message },
    };
  }

  private dispatch(op: string, payload: Record<string, unknown>): Response {
    switch (op) {
      case 'session.info':
        return this.success(op, {
          serverVersion: '1.21.4',
          minecraftVersion: '1.21.4',
          protocolVersion: 1,
          editorRunning: true,
          editorPort: 8089,
        });
      case 'browse.packs':
        return this.success(op, { packs: [this.#pack] });
      case 'browse.types':
        return this.success(op, {
          types: structuredClone(this.#types),
        });
      case 'browse.resources': {
        const pack = stringValue(payload.pack);
        return this.success(op, {
          resources: this.#resources.filter((resource) => resource.pack === pack).map((resource) =>
            structuredClone(resource),
          ),
        });
      }
      case 'authoring.summaries':
        return this.summaries(op, payload);
      case 'browse.folders':
        return this.success(op, { folders: [] });
      case 'browse.get':
        return this.getDocument(op, payload);
      case 'edit.put':
        return this.putDocument(op, payload);
      case 'authoring.setFields':
        return this.setFields(op, payload);
      case 'authoring.saveModel':
        return this.saveModel(op, payload);
      case 'assets.list':
        return this.listAssets(op, payload);
      case 'assets.get':
        return this.getAsset(op, payload);
      case 'assets.put':
        return this.putAsset(op, payload);
      case 'assets.delete':
        return this.deleteAsset(op, payload);
      case 'assets.move':
        return this.moveAsset(op, payload);
      case 'admin.diagnostics.recent':
        return this.success(op, { diagnostics: structuredClone(this.#diagnostics) });
      case 'delivery.status':
        return this.success(op, {
          candidateSha256: this.#candidateSha256,
          publishedSha256: this.#publishedSha256,
        });
      case 'delivery.publish':
        return this.publish(op, payload);
      case 'pipeline.reload':
        return this.success(op, { accepted: true, itemsLive: 1, quarantined: 0 });
      case 'validate.dryRun':
        return this.success(op, { valid: true, diagnostics: [] });
      case 'admin.audit.recent':
        return this.success(op, { entries: structuredClone(this.#auditEntries) });
      case 'admin.settings.get':
        return this.success(op, { raw: 'editor:\n  enabled: true\n', sha256: 'fixture-settings' });
      case 'admin.settings.put':
        return this.success(op, { sha256: 'fixture-settings-updated' });
      default:
        return this.failure(op, 'MTR-0001', `unsupported fixture operation: ${op}`);
    }
  }

  private summaries(op: string, payload: Record<string, unknown>): Response {
    if (this.#summaryError) {
      return this.failure(op, 'MTR-0001', 'deterministic resource summary read failure');
    }
    const pack = stringValue(payload.pack);
    const type = stringValue(payload.type);
    const query = stringValue(payload.query).replaceAll('_', ' ').trim().toLowerCase();
    const all: ResourceSummary[] = [];
    for (const resource of this.#resources) {
      if (resource.pack !== pack || (type && resource.type !== type)) continue;
      const document = this.#documents.get(`${resource.pack}/${resource.path}`);
      const fields = document?.parsed[resource.id];
      if (!fields) continue;
      const material = stringValue(fields.material);
      const visualPath = this.#visuals.get(resource.id);
      const visual: ResourceSummary['visual'] = visualPath
        ? { pack: this.#pack, path: visualPath, kind: 'texture' }
        : undefined;
      const failure = this.#failures.get(resource.id);
      const summary: ResourceSummary = {
        ...resource,
        name: this.#names.get(resource.id) ?? stringValue(fields.display_name, resource.id),
        ...(material ? { material } : {}),
        ...(visual ? { visual } : {}),
        ...(this.#renders.has(resource.id) ? { render: this.#renders.get(resource.id) } : {}),
        health: failure ? 'error' : 'unknown',
        ...(failure ? { healthMessage: failure } : {}),
      };
      const searchable = [summary.id, summary.path, summary.name, summary.material ?? '']
        .join(' ')
        .replaceAll('_', ' ')
        .toLowerCase();
      if (query && !searchable.includes(query)) continue;
      all.push(summary);
    }
    const offset = numberValue(payload.offset, 0);
    const limit = numberValue(payload.limit, 100);
    if (!Number.isInteger(offset) || offset < 0
        || !Number.isInteger(limit) || limit < 1 || limit > 200) {
      return this.failure(op, 'MTR-0012', 'summary page has invalid offset or limit');
    }
    const start = Math.min(offset, all.length);
    const end = Math.min(start + limit, all.length);
    return this.success(op, {
      resources: all.slice(start, end),
      total: all.length,
      ...(end < all.length ? { nextOffset: end } : {}),
    });
  }

  private getDocument(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    return this.success(op, {
      pack: document.pack,
      path: document.path,
      rev: document.rev,
      raw: document.raw,
      parsed: structuredClone(document.parsed),
      computed: {},
    });
  }

  private putDocument(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    if (numberValue(payload.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }
    const raw = stringValue(payload.content);
    const parsed = parseFixtureYaml(raw);
    if (Object.keys(parsed).length === 0) {
      return this.failure(op, 'MTR-0012', 'fixture YAML has no resource root');
    }
    document.raw = raw;
    document.parsed = parsed;
    document.rev += 1;
    return this.success(op, {
      rev: document.rev,
      raw: document.raw,
      parsed: structuredClone(document.parsed),
    });
  }

  private setFields(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    if (numberValue(payload.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }
    const edits = parseEdits(payload.edits);
    if (!edits) return this.failure(op, 'MTR-0012', 'fixture field edits are malformed');
    const parsed = structuredClone(document.parsed);
    for (const edit of edits) {
      const root = edit.field[0];
      if (!root || !parsed[root]) {
        return this.failure(op, 'MTR-0009', 'fixture resource root not found');
      }
      if (!assignPath(parsed[root], edit.field.slice(1), edit.value)) {
        return this.failure(op, 'MTR-0012', 'fixture field path is malformed');
      }
    }
    document.parsed = parsed;
    document.rev += edits.length > 0 ? 1 : 0;
    document.raw = serializeFixtureYaml(document.parsed);
    return this.success(op, { rev: document.rev });
  }

  private saveModel(op: string, payload: Record<string, unknown>): Response {
    const assets = Array.isArray(payload.assets) ? payload.assets : [];
    const stagedAssets: { pack: string; path: string; data: string }[] = [];
    for (const asset of assets) {
      if (!isRecord(asset)) return this.failure(op, 'MTR-0012', 'fixture model asset is malformed');
      const path = stringValue(asset.path);
      const data = stringValue(asset.data);
      if (!path) return this.failure(op, 'MTR-0012', 'fixture model asset path is missing');
      stagedAssets.push({ pack: stringValue(payload.pack, this.#pack), path, data });
    }

    const ownerPath = stringValue(payload.ownerPath);
    const document = ownerPath
      ? this.#documents.get(`${stringValue(payload.pack, this.#pack)}/${ownerPath}`)
      : undefined;
    if (ownerPath && !document) return this.failure(op, 'MTR-0009', 'fixture model owner not found');
    if (document && numberValue(payload.rev, document.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }

    const field = stringArray(payload.field) ? payload.field : [];
    const modelRef = stringValue(payload.modelRef);
    let updatedDocument: Record<string, Record<string, unknown>> | null = null;
    if (document && field.length > 1) {
      const root = field[0];
      if (!root || !document.parsed[root]) {
        return this.failure(op, 'MTR-0009', 'fixture model resource root not found');
      }
      updatedDocument = structuredClone(document.parsed);
      if (!assignPath(updatedDocument[root]!, field.slice(1), modelRef)) {
        return this.failure(op, 'MTR-0012', 'fixture model field path is malformed');
      }
    }

    for (const asset of stagedAssets) this.storeAsset(asset.pack, asset.path, asset.data);
    if (!document) return this.success(op, { rev: 0 });

    if (updatedDocument) {
      document.parsed = updatedDocument;
      document.rev += 1;
      document.raw = serializeFixtureYaml(document.parsed);
    }
    return this.success(op, { rev: document.rev });
  }

  private listAssets(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const assets: AssetRef[] = [...this.#assets.values()]
      .filter((asset) => this.#assets.has(assetPath(pack, asset.path)))
      .map((asset) => ({ path: asset.path, kind: asset.kind, bytes: asset.bytes }));
    return this.success(op, { assets });
  }

  private getAsset(op: string, payload: Record<string, unknown>): Response {
    const asset = this.#assets.get(
      assetPath(stringValue(payload.pack), stringValue(payload.path)),
    );
    if (!asset) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    return this.success(op, { path: asset.path, data: asset.data, bytes: asset.bytes });
  }

  private putAsset(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const path = stringValue(payload.path);
    if (!pack || !path) return this.failure(op, 'MTR-0012', 'fixture asset path is missing');
    const asset = this.storeAsset(pack, path, stringValue(payload.data));
    return this.success(op, { path: asset.path, bytes: asset.bytes });
  }

  private deleteAsset(op: string, payload: Record<string, unknown>): Response {
    const key = assetPath(stringValue(payload.pack), stringValue(payload.path));
    if (!this.#assets.delete(key)) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    return this.success(op, { deleted: true });
  }

  private moveAsset(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const from = stringValue(payload.from);
    const to = stringValue(payload.to);
    const oldKey = assetPath(pack, from);
    const asset = this.#assets.get(oldKey);
    if (!asset) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    this.#assets.delete(oldKey);
    this.#assets.set(assetPath(pack, to), { ...asset, path: to });
    return this.success(op, { from, to });
  }

  private publish(op: string, payload: Record<string, unknown>): Response {
    const expected = stringValue(payload.expectedCandidateSha256);
    if (!expected || expected !== this.#candidateSha256) {
      return this.failure(op, 'MTR-0042', 'candidate hash changed; refresh status before publishing');
    }
    this.#publishedSha256 = expected;
    this.#candidateSha256 = '';
    return this.success(op, { publishedSha256: this.#publishedSha256 });
  }

  private findDocument(payload: Record<string, unknown>): FixtureDocument | undefined {
    return this.#documents.get(
      `${stringValue(payload.pack)}/${stringValue(payload.path)}`,
    );
  }

  private storeAsset(pack: string, path: string, data: string): FixtureAsset {
    const asset: FixtureAsset = {
      path,
      kind: path.split('.').pop() ?? 'bin',
      data,
      bytes: Math.floor((data.length * 3) / 4),
    };
    this.#assets.set(assetPath(pack, path), asset);
    return asset;
  }
}
