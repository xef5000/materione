/**
 * The studio's document model for a GUI file (gui.md section 2). It is not a
 * second model: a document is the same `title` / `rows` / `layers` structure
 * the authoring API returns, held immutably, and every layer is the plain
 * record the YAML holds. The studio never invents fields and never drops the
 * ones it does not know (master 5.6): unknown layer fields, unknown layer
 * types and unknown top-level keys ride along untouched, and a document that
 * was never edited serializes to a value equal to what was loaded.
 */
import { containerProfile, gridRows, NEW_GUI_CONTAINER, studioDraws, supportedContainerIds, type ContainerProfile } from './containers';

export type Json = Record<string, unknown>;

/** One entry of `layers:`. `id` and `type` are the only fields the studio relies on. */
export type GuiLayer = Json & { id: string; type: string };

export interface GuiDocument {
  readonly title: string;
  /** The container profile the GUI is designed in (gui.md section 12.4): the value the file holds. */
  readonly container: string;
  /** The slot grid's rows of `container`, derived from its profile: the canvas geometry is drawn from it. */
  readonly rows: number;
  readonly layers: readonly GuiLayer[];
  /** Every other top-level key, preserved verbatim (never interpreted). */
  readonly rest: Readonly<Json>;
  /** The original top-level key order, so serializing keeps the file's shape. */
  readonly order: readonly string[];
}

/** Where the GUI's fields live inside the parsed file. */
export interface GuiLocation {
  /** Path prefix to a field: `[rootId]`. A GUI file is keyed like every other type (one resource per top-level key). */
  prefix: string[];
  body: Json;
  /** The file has no resource key (gui.md section 2's unkeyed sketch): read leniently, never written. */
  flat?: boolean;
}

export interface ParsedGui {
  doc: GuiDocument;
  location: GuiLocation;
  /** What the parser repaired for display only; nothing here is written back. */
  issues: string[];
  /**
   * Why this file cannot be edited safely here (a layer the model cannot
   * represent). Non-empty means the studio is read-only: it never rewrites what
   * it could not faithfully hold.
   */
  blocking: string[];
}

export function isRecord(value: unknown): value is Json {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function clone<T>(value: T): T {
  return value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T);
}

/**
 * Finds the GUI body in what `browse.get` returned: under the resource root
 * when the file is a mapping of ids (like every other type), or at the top
 * level when it is flat (gui.md section 2's example). The root wins when both
 * could apply.
 */
export function locateBody(parsed: unknown, rootId: string): GuiLocation | null {
  if (!isRecord(parsed)) return null;
  const rooted = parsed[rootId];
  if (isRecord(rooted) && (Array.isArray(rooted.layers) || 'container' in rooted || 'title' in rooted)) {
    return { prefix: [rootId], body: rooted };
  }
  if (Array.isArray(parsed.layers) || 'container' in parsed) return { prefix: [], body: parsed, flat: true };
  if (isRecord(rooted)) return { prefix: [rootId], body: rooted };
  const only = Object.entries(parsed).filter(([, value]) => isRecord(value));
  if (only.length === 1 && Object.keys(parsed).length === 1) {
    return { prefix: [only[0]![0]], body: only[0]![1] as Json };
  }
  return null;
}

export function parseGui(parsed: unknown, rootId: string): ParsedGui | null {
  const location = locateBody(parsed, rootId);
  if (!location) return null;
  const { body } = location;
  const issues: string[] = [];
  const blocking: string[] = [];
  if (location.flat) blocking.push('no resource key (a GUI file is keyed by its id like every other type)');
  const layers: GuiLayer[] = [];
  const seen = new Set<string>();
  if (body.layers !== undefined && !Array.isArray(body.layers)) {
    blocking.push('layers is not a list');
  }
  for (const [index, entry] of (Array.isArray(body.layers) ? body.layers : []).entries()) {
    if (!isRecord(entry) || typeof entry.type !== 'string') {
      blocking.push(`layer ${index + 1} is not a layer (it has no type)`);
      continue;
    }
    let id = typeof entry.id === 'string' && entry.id ? entry.id : '';
    if (!id || seen.has(id)) {
      blocking.push(`layer ${index + 1} has a missing or duplicate id`);
      id = uniqueId(entry.type, seen);
      layers.push({ ...clone(entry), id, type: entry.type });
    } else {
      layers.push(clone(entry) as GuiLayer);
    }
    seen.add(id);
  }
  // The container is the file's value, kept as authored. An unknown one quarantines the GUI on the server (MTR-0097),
  // so the studio says so and edits nothing; a container the studio does not draw yet is shown read-only the same way.
  const container = typeof body.container === 'string' ? body.container : '';
  const profile = containerProfile(container);
  if (!profile) {
    blocking.push(container
      ? `container ${container} is not a supported container (the supported ones: ${supportedContainerIds()})`
      : 'container is missing or not a string (the GUI needs container: chest_3 or another supported container)');
  } else if (!studioDraws(profile)) {
    blocking.push(`the studio does not draw the ${profile.id} container yet (it is opened by its inventory type)`);
  }
  const rows = profile ? gridRows(profile) : gridRows(containerProfile(NEW_GUI_CONTAINER)!);
  const { title: _title, container: _container, layers: _layers, ...rest } = body;
  return {
    doc: {
      title: typeof body.title === 'string' ? body.title : '',
      container,
      rows,
      layers,
      rest: clone(rest),
      order: Object.keys(body),
    },
    location,
    issues,
    blocking,
  };
}

/**
 * The document as the structure the API stores: the original keys in their
 * original order, with `title`, `rows` and `layers` replaced by the current
 * values. A field the file never had (a missing `title`) is not invented
 * unless it was edited to something other than its empty default.
 */
export function serializeGui(doc: GuiDocument): Json {
  const out: Json = {};
  const written = new Set<string>();
  const emit = (key: string, value: unknown): void => {
    out[key] = value;
    written.add(key);
  };
  for (const key of doc.order) {
    if (key === 'title') emit('title', doc.title);
    else if (key === 'container') emit('container', doc.container);
    else if (key === 'layers') emit('layers', clone(doc.layers));
    else if (Object.hasOwn(doc.rest, key)) emit(key, clone(doc.rest[key]));
  }
  if (!written.has('title') && doc.title !== '') emit('title', doc.title);
  if (!written.has('container') && doc.container !== '') emit('container', doc.container);
  if (!written.has('layers')) emit('layers', clone(doc.layers));
  for (const [key, value] of Object.entries(doc.rest)) if (!written.has(key)) emit(key, clone(value));
  return out;
}

/** `panel`, `panel_2`, `panel_3`, ...: the id grammar is `[a-z0-9_]+`. */
export function uniqueId(base: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  const stem = base.toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^_+|_+$/g, '') || 'layer';
  if (!used.has(stem)) return stem;
  for (let n = 2; ; n++) {
    const candidate = `${stem}_${n}`;
    if (!used.has(candidate)) return candidate;
  }
}

export function layerIndex(doc: GuiDocument, id: string): number {
  return doc.layers.findIndex((layer) => layer.id === id);
}

export function findLayer(doc: GuiDocument, id: string): GuiLayer | undefined {
  return doc.layers.find((layer) => layer.id === id);
}

/** A layer's display name: its `name`, else its id. */
export function layerLabel(layer: GuiLayer): string {
  return typeof layer.name === 'string' && layer.name.trim() ? layer.name : layer.id;
}

export function isHidden(layer: GuiLayer): boolean {
  return layer.visible === false;
}

export function isLocked(layer: GuiLayer): boolean {
  return layer.locked === true;
}

/** Same layers and fields, in the same order? (Deep, key-order-sensitive: the file's shape.) */
export function sameDocument(a: GuiDocument, b: GuiDocument): boolean {
  return a === b ||
    (a.title === b.title && a.container === b.container &&
      JSON.stringify(a.layers) === JSON.stringify(b.layers) &&
      JSON.stringify(a.rest) === JSON.stringify(b.rest));
}

/**
 * The document with another container (gui.md section 12.4): the value is set and the derived rows follow its
 * profile. Switching never edits a layer; the studio reports what no longer fits (gui.md section 12.13 item 8).
 */
export function withContainer(doc: GuiDocument, container: string): GuiDocument {
  const profile = containerProfile(container);
  return { ...doc, container, rows: profile ? gridRows(profile) : doc.rows };
}

/**
 * A starter GUI (gui.md section 11.2): a title and a container and no layers. The vanilla chest is the client's own,
 * drawn under the GUI by the studio, so a new GUI has no art and costs nothing in the pack. A new GUI starts on
 * `chest_3` (gui.md section 12.4).
 */
export function starterDocument(container: string = NEW_GUI_CONTAINER, title = ''): GuiDocument {
  return withContainer({
    title,
    container,
    rows: 0,
    layers: [],
    rest: {},
    order: ['title', 'container', 'layers'],
  }, container);
}

/** The profile of a document's container, or undefined when the studio does not know it. */
export function profileOf(doc: GuiDocument): ContainerProfile | undefined {
  return containerProfile(doc.container);
}
