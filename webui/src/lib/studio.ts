/** Studio helpers kept out of the components so they can be unit-tested. */
import type { TypeInfo, TypeParam } from './api';

/** Widgets a studio can render and save losslessly. */
const RENDERABLE = new Set([
  'text',
  'number',
  'toggle',
  'select',
  'list',
  'texture',
  'model',
  'reference',
  'material',
  'recipe-grid',
]);

export interface StudioSection {
  id: string;
  label: string;
  fields: TypeParam[];
}

/** The YAML root belonging to a selected resource, not an arbitrary first root in a multi-resource file. */
export function rootIdForResource(resourceId: string): string {
  return resourceId.slice(resourceId.lastIndexOf(':') + 1);
}

export function rootIdForResourceSelection(
  resources: readonly { path: string; id: string }[],
  path: string,
  rootId: string,
): string | null {
  const selected = resources.find(
    (resource) => resource.path === path && rootIdForResource(resource.id) === rootId,
  );
  return selected ? rootIdForResource(selected.id) : null;
}

export type AssetSnapshot =
  | { kind: 'missing' }
  | { kind: 'present'; data: string };

export function sameAssetSnapshot(left: AssetSnapshot, right: AssetSnapshot): boolean {
  return left.kind === right.kind &&
    (left.kind === 'missing' || (right.kind === 'present' && left.data === right.data));
}

export interface ModelOwnerContext {
  pack: string;
  ownerPath: string;
  ownerId: string;
  rev: number;
  field: string[];
  modelRef: string;
  textureRef: string;
  typeDirectory: string;
}

export function sameModelOwnerContext(opened: ModelOwnerContext, current: ModelOwnerContext): boolean {
  return opened.pack === current.pack &&
    opened.ownerPath === current.ownerPath &&
    opened.ownerId === current.ownerId &&
    opened.rev === current.rev &&
    opened.field.length === current.field.length &&
    opened.field.every((part, index) => part === current.field[index]) &&
    opened.modelRef === current.modelRef &&
    opened.textureRef === current.textureRef &&
    opened.typeDirectory === current.typeDirectory;
}

const SECTION_ORDER = ['identity', 'appearance', 'audio', 'crafting', 'placement', 'gameplay', 'behavior'];

const SECTION_LABELS: Record<string, string> = {
  identity: 'Identity',
  appearance: 'Appearance',
  crafting: 'Crafting',
  placement: 'Placement',
  gameplay: 'Gameplay',
  behavior: 'Behaviour',
  audio: 'Audio',
  general: 'General',
};

/** True when the studio can both render the field and write it back safely. */
export function isRenderable(field: TypeParam): boolean {
  const widget = field.uiHint ?? 'text';
  if (widget === 'structured-list') {
    // Only with a declared shape; a free-form list of maps belongs in YAML.
    return (field.nested?.length ?? 0) > 0;
  }
  return RENDERABLE.has(widget);
}

/**
 * The sections a studio shows, in a fixed reading order: what it is, what it
 * looks like, how it plays. Fields the studio cannot render are left out —
 * they are reported instead, never silently dropped.
 */
export function sectionsOf(schema: TypeInfo | undefined): StudioSection[] {
  const groups = new Map<string, TypeParam[]>();
  for (const field of schema?.parameters ?? []) {
    if (!isRenderable(field)) continue;
    const id = field.section ?? 'general';
    if (!groups.has(id)) groups.set(id, []);
    groups.get(id)!.push(field);
  }
  return [...groups.entries()]
    .map(([id, fields]) => ({
      id,
      label: SECTION_LABELS[id] ?? id,
      fields: fields.sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
    }))
    .sort((a, b) => rank(a.id) - rank(b.id));
}

function rank(section: string): number {
  const index = SECTION_ORDER.indexOf(section);
  return index < 0 ? SECTION_ORDER.length : index;
}

/**
 * Keys present in the document that no studio control covers. They are shown
 * as an honest report ("2 fields are only editable in Advanced Files") rather
 * than being hidden — and they are never touched by a studio save.
 */
export function unrenderableKeys(
  body: Record<string, unknown>,
  schema: TypeInfo | undefined,
): string[] {
  return Object.keys(body).filter((key) => {
    const field = schema?.parameters.find((candidate) => candidate.id === key);
    return !field || !isRenderable(field);
  });
}

/** The fields whose value differs from what was loaded — a studio save's payload. */
export function changedFields(
  loaded: Record<string, unknown>,
  draft: Record<string, unknown>,
): string[] {
  return Object.keys(draft).filter(
    (key) => JSON.stringify(draft[key]) !== JSON.stringify(loaded[key]),
  );
}

/** A blank row for a structured list, built from the field's declared shape. */
export function blankRow(field: TypeParam): Record<string, unknown> {
  const row: Record<string, unknown> = {};
  for (const nested of field.nested ?? []) {
    row[nested.id] = defaultOf(nested);
  }
  return row;
}

function defaultOf(field: TypeParam): unknown {
  const fallback = field.default ?? '';
  if (field.type === 'list') {
    const parsed = fallback.replace(/[[\]\s]/g, '');
    return parsed ? parsed.split(',').map(Number) : [];
  }
  if (field.type === 'integer') return Number(fallback) || 0;
  if (field.type === 'decimal') return Number(fallback) || 0;
  if (field.type === 'boolean') return fallback === 'true';
  return fallback;
}

export interface RecipeGridModel {
  /** Nine cells, row-major; empty string means an empty slot. */
  cells: string[];
}

/**
 * A shaped recipe's rows + char→item map read as a 3×3 grid, so the studio can
 * show a crafting table instead of asking for YAML.
 */
export function gridFrom(shape: unknown, ingredients: unknown): RecipeGridModel {
  const rows = Array.isArray(shape) ? shape.map((row) => String(row)) : [];
  const map = (ingredients ?? {}) as Record<string, unknown>;
  const cells = Array.from({ length: 9 }, () => '');
  for (let row = 0; row < 3; row++) {
    const line = rows[row] ?? '';
    for (let column = 0; column < 3; column++) {
      const symbol = line[column] ?? ' ';
      if (symbol !== ' ' && map[symbol] !== undefined) {
        cells[row * 3 + column] = String(map[symbol]);
      }
    }
  }
  return { cells };
}

/**
 * The inverse: a grid becomes rows plus the smallest char→item map that
 * describes it. Symbols are assigned deterministically so saving twice
 * produces the same file.
 */
export function gridTo(cells: string[]): { shape: string[]; ingredients: Record<string, string> } {
  const symbols = new Map<string, string>();
  const alphabet = 'ABCDEFGHI';
  const rows: string[] = [];
  for (let row = 0; row < 3; row++) {
    let line = '';
    for (let column = 0; column < 3; column++) {
      const item = cells[row * 3 + column] ?? '';
      if (!item) {
        line += ' ';
        continue;
      }
      if (!symbols.has(item)) {
        symbols.set(item, alphabet[symbols.size] ?? '?');
      }
      line += symbols.get(item)!;
    }
    rows.push(line);
  }
  // Trailing all-blank rows and columns are noise in the file.
  while (rows.length > 1 && rows[rows.length - 1]!.trim() === '') rows.pop();
  const width = Math.max(...rows.map((row) => row.trimEnd().length), 1);
  const ingredients: Record<string, string> = {};
  for (const [item, symbol] of symbols) ingredients[symbol] = item;
  return { shape: rows.map((row) => row.slice(0, width)), ingredients };
}

// --- sound files -----------------------------------------------------------

/**
 * The pack asset a sound source resolves to: sources are authored relative to
 * `assets/sounds/` with an optional namespace prefix and optional `.ogg`, and
 * the validator strips the `sounds/` kind directory the same way. This is the
 * upload/replace target for the studio's sound editor.
 */
export function soundAssetPath(reference: string): string {
  let cleaned = reference.replace(/\\/g, '/');
  if (cleaned.toLowerCase().endsWith('.ogg') || cleaned.toLowerCase().endsWith('.oga')) {
    cleaned = cleaned.slice(0, -4);
  }
  const colon = cleaned.indexOf(':');
  if (colon >= 0) cleaned = cleaned.slice(colon + 1);
  return `assets/sounds/${cleaned}.ogg`;
}

/**
 * A source ref for an uploaded file's name: the basename, lowercased with
 * spaces as underscores, keeping only what the asset id grammar allows. Null
 * when nothing survives — the caller refuses the upload rather than shipping
 * a reference that can never validate.
 */
export function sanitizeSoundName(fileName: string): string | null {
  const base = fileName.replace(/\.og[ag]$/i, '').replace(/.*[/\\]/, '');
  const cleaned = base
    .toLowerCase()
    .replace(/\s+/g, '_')
    .replace(/[^a-z0-9_.-]/g, '')
    .replace(/^\.+/, '')
    .replace(/\.+$/, '');
  return cleaned || null;
}
