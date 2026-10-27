/**
 * Pure seams for the Assets workspace, bulk authoring and the command palette
 * (web.md §6). Nothing here talks to a server: it builds the operations the
 * `authoring.bulk` / `authoring.references` RPCs take, reads their replies
 * defensively, and ranks palette commands. The server owns every guarantee
 * (revision checks, all-or-none writes, the reference scan); this module only
 * shapes requests and words the answer honestly.
 */
import type { FieldEditPayload } from './api';
import { idFromName } from './browser';

// ------------------------------------------------------------------ wire shapes

export interface ReferenceHit {
  pack: string;
  type: string;
  path: string;
  id: string;
  /** Field path inside the resource root. */
  field: string[];
  value: string;
}

export interface ReferenceCoverage {
  documentsScanned: number;
  unreadable: number;
  truncated: boolean;
  complete: boolean;
  notScanned: string[];
}

export interface ReferenceResult {
  hits: ReferenceHit[];
  coverage: ReferenceCoverage;
}

export type BulkOperation =
  | { kind: 'set'; path: string; rev: number; edits: FieldEditPayload[] }
  | { kind: 'create'; type: string; id: string; name?: string; folder?: string; edits?: FieldEditPayload[] }
  | { kind: 'duplicate'; fromPath: string; id: string; name?: string; folder?: string; edits?: FieldEditPayload[] };

export interface BulkFieldChange {
  field: string[];
  before: unknown;
  after: unknown;
}

export interface BulkDocument {
  path: string;
  kind: 'set' | 'create' | 'duplicate';
  rev: number;
  unchanged: boolean;
  changes: BulkFieldChange[];
}

export interface BulkResult {
  applied: boolean;
  documents: BulkDocument[];
}

/** The server's cap per bulk call (AuthoringApi.MAX_BULK_OPERATIONS). */
export const MAX_BULK_OPERATIONS = 50;

function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String) : [];
}

export function parseReferenceResult(data: unknown): ReferenceResult {
  const body = record(data);
  const coverage = record(body.coverage);
  return {
    hits: (Array.isArray(body.hits) ? body.hits : []).map((entry) => {
      const hit = record(entry);
      return {
        pack: String(hit.pack ?? ''),
        type: String(hit.type ?? ''),
        path: String(hit.path ?? ''),
        id: String(hit.id ?? ''),
        field: strings(hit.field),
        value: String(hit.value ?? ''),
      };
    }),
    coverage: {
      documentsScanned: Number(coverage.documentsScanned ?? 0),
      unreadable: Number(coverage.unreadable ?? 0),
      truncated: coverage.truncated === true,
      // Absent coverage is unknown coverage, never "complete".
      complete: coverage.complete === true,
      notScanned: strings(coverage.notScanned),
    },
  };
}

export function parseBulkResult(data: unknown): BulkResult {
  const body = record(data);
  return {
    applied: body.applied === true,
    documents: (Array.isArray(body.documents) ? body.documents : []).map((entry) => {
      const doc = record(entry);
      return {
        path: String(doc.path ?? ''),
        kind: (['set', 'create', 'duplicate'].includes(String(doc.kind)) ? doc.kind : 'set') as BulkDocument['kind'],
        rev: Number(doc.rev ?? 0),
        unchanged: doc.unchanged === true,
        changes: (Array.isArray(doc.changes) ? doc.changes : []).map((change) => {
          const item = record(change);
          return { field: strings(item.field), before: item.before ?? null, after: item.after ?? null };
        }),
      };
    }),
  };
}

// ------------------------------------------------------------------- references

/** Drops `assets/<category>/` and, when asked, the extension: the way documents spell an asset. */
export function logicalAssetName(assetPath: string, keepExtension = false): string {
  const rest = assetPath.replace(/^assets\//, '');
  const slash = rest.indexOf('/');
  const logical = slash < 0 ? rest : rest.slice(slash + 1);
  return keepExtension ? logical : logical.replace(/\.[A-Za-z0-9]+$/, '');
}

/** The category folder under `assets/` ("textures", "models", "sounds", …), or ''. */
export function assetCategory(assetPath: string): string {
  const match = /^assets\/([^/]+)\//.exec(assetPath);
  return match?.[1] ?? '';
}

/** One sentence on what a reference answer covers — never "unused" from an incomplete scan. */
export function coverageNote(coverage: ReferenceCoverage): string {
  const read = `${coverage.documentsScanned} ${coverage.documentsScanned === 1 ? 'document' : 'documents'} read`;
  if (coverage.truncated) {
    return `Partial: ${read}, then the search hit its limit. More users may exist.`;
  }
  if (coverage.unreadable > 0) {
    return `Partial: ${read}; ${coverage.unreadable} could not be read, so they may use this too.`;
  }
  if (!coverage.complete) {
    return `Coverage unknown: ${read}. Treat an empty list as "not found", not "unused".`;
  }
  return `${read}. Texts in other asset files (models, sounds) are not searched.`;
}

/** "3 items, 1 recipe" — the shape of the impact, by type directory. */
export function impactSummary(hits: ReferenceHit[]): string {
  const byType = new Map<string, Set<string>>();
  for (const hit of hits) {
    const set = byType.get(hit.type) ?? new Set<string>();
    set.add(`${hit.path}#${hit.id}`);
    byType.set(hit.type, set);
  }
  if (byType.size === 0) return 'no users found';
  return [...byType.entries()]
    .map(([type, ids]) => `${ids.size} ${ids.size === 1 ? singular(type) : type}`)
    .join(', ');
}

function singular(type: string): string {
  if (type === 'furnitures') return 'furniture';
  return type.endsWith('s') ? type.slice(0, -1) : type;
}

/** The text a hit should carry once it points at `toAsset`, in the same spelling it had. */
export function replacementValue(
  value: string,
  fromAsset: { pack: string; path: string },
  toAsset: { pack: string; path: string },
): string {
  const qualifiedPrefix = `${fromAsset.pack}:`;
  const qualified = value.startsWith(qualifiedPrefix);
  const body = qualified ? value.slice(qualifiedPrefix.length) : value;
  const hadAssetPrefix = body.startsWith('assets/');
  const hadExtension = /\.[A-Za-z0-9]+$/.test(body);
  let next = hadAssetPrefix
    ? toAsset.path
    : logicalAssetName(toAsset.path, hadExtension);
  if (qualified || toAsset.pack !== fromAsset.pack) {
    next = `${toAsset.pack}:${next}`;
  }
  return next;
}

/**
 * Set operations that point every hit at another asset: one operation per
 * document (several roots or fields in a file share it). `revs` maps document
 * path to the revision it was read at; a missing revision is a refusal, never
 * a guess.
 */
export function retargetOperations(
  hits: ReferenceHit[],
  fromAsset: { pack: string; path: string },
  toAsset: { pack: string; path: string },
  revs: ReadonlyMap<string, number>,
): BulkOperation[] {
  const byPath = new Map<string, FieldEditPayload[]>();
  for (const hit of hits) {
    const edits = byPath.get(hit.path) ?? [];
    edits.push({ field: [hit.id, ...hit.field], value: replacementValue(hit.value, fromAsset, toAsset) });
    byPath.set(hit.path, edits);
  }
  return setOperations(byPath, revs);
}

function setOperations(
  byPath: Map<string, FieldEditPayload[]>,
  revs: ReadonlyMap<string, number>,
): BulkOperation[] {
  return [...byPath.entries()].map(([path, edits]) => {
    const rev = revs.get(path);
    if (rev === undefined) throw new Error(`The revision of ${path} is unknown; reload and try again.`);
    return { kind: 'set', path, rev, edits };
  });
}

// ------------------------------------------------------------------ bulk builders

export interface BulkTarget {
  path: string;
  id: string;
}

/** Sets one field on every target: the 'change twenty related items' case. */
export function fieldOperations(
  targets: BulkTarget[],
  field: string[],
  edit: { remove: true } | { value: unknown },
  revs: ReadonlyMap<string, number>,
): BulkOperation[] {
  const byPath = new Map<string, FieldEditPayload[]>();
  for (const target of targets) {
    const edits = byPath.get(target.path) ?? [];
    const path = [target.id, ...field];
    edits.push('remove' in edit ? { field: path, remove: true } : { field: path, value: edit.value });
    byPath.set(target.path, edits);
  }
  return setOperations(byPath, revs);
}

export interface VariantPlan {
  operations: BulkOperation[];
  problems: string[];
}

/**
 * Variants of one resource from a list of names (one per line): each is a
 * lossless copy with its own id and display name, optionally with one field
 * changed (`field = value` per line, e.g. `Red Blade = red_blade_texture`).
 */
export function variantPlan(
  source: { path: string; id: string; hasDisplayName: boolean },
  lines: string,
  field: string[] | null,
  existingIds: ReadonlySet<string>,
  folder?: string,
): VariantPlan {
  const operations: BulkOperation[] = [];
  const problems: string[] = [];
  const seen = new Set<string>();
  for (const raw of lines.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const [namePart, ...rest] = line.split('=');
    const name = (namePart ?? '').trim();
    const value = rest.join('=').trim();
    const id = idFromName(name);
    if (id.length < 2) {
      problems.push(`"${line}": use at least two letters or numbers in the name`);
      continue;
    }
    if (existingIds.has(id) || seen.has(id)) {
      problems.push(`"${name}": the id ${id} is already taken`);
      continue;
    }
    seen.add(id);
    const edits: FieldEditPayload[] = [];
    if (source.hasDisplayName) edits.push({ field: [id, 'display_name'], value: name });
    if (field && value) edits.push({ field: [id, ...field], value });
    operations.push({
      kind: 'duplicate', fromPath: source.path, id, ...(folder ? { folder } : {}), edits,
    });
  }
  if (operations.length > MAX_BULK_OPERATIONS) {
    problems.push(`at most ${MAX_BULK_OPERATIONS} variants at a time`);
  }
  return { operations, problems };
}

/** A new recipe whose result is the item, born in the same change as anything else in the batch. */
export function recipeOperation(itemId: string, namespace?: string): BulkOperation {
  return {
    kind: 'create',
    type: 'recipes',
    id: itemId,
    edits: [{ field: [itemId, 'result'], value: namespace ? `${namespace}:${itemId}` : itemId }],
  };
}

// ----------------------------------------------------------------- impact wording

export function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '(not set)';
  if (typeof value === 'string') return value === '' ? '(empty)' : value;
  return JSON.stringify(value);
}

export function bulkHeadline(result: BulkResult): string {
  const changed = result.documents.filter((doc) => !doc.unchanged);
  const fields = changed.reduce((total, doc) => total + doc.changes.length, 0);
  const created = changed.filter((doc) => doc.kind !== 'set').length;
  const parts = [`${changed.length} ${changed.length === 1 ? 'document' : 'documents'}`, `${fields} ${fields === 1 ? 'field' : 'fields'}`];
  if (created > 0) parts.push(`${created} new`);
  const same = result.documents.length - changed.length;
  return `${parts.join(', ')}${same > 0 ? `; ${same} already as requested` : ''}`;
}

// ------------------------------------------------------------------ command palette

export interface PaletteCommand {
  id: string;
  title: string;
  /** Right-aligned context ("Go to", "Item", "Field"). */
  group: string;
  keywords?: string;
  run: () => void;
}

/** Subsequence score: higher is better, null when `query` is not a subsequence. */
export function fuzzyScore(query: string, text: string): number | null {
  const needle = query.trim().toLowerCase();
  if (!needle) return 0;
  const haystack = text.toLowerCase();
  let score = 0;
  let from = 0;
  let previous = -2;
  for (const char of needle) {
    const at = haystack.indexOf(char, from);
    if (at < 0) return null;
    score += at === previous + 1 ? 3 : 1;
    if (at === 0 || /[\s_:/-]/.test(haystack[at - 1] ?? '')) score += 2;
    previous = at;
    from = at + 1;
  }
  return score - Math.floor(haystack.length / 20);
}

export function rankCommands(query: string, commands: PaletteCommand[], limit = 12): PaletteCommand[] {
  if (!query.trim()) return commands.slice(0, limit);
  return commands
    .map((command) => ({
      command,
      score: fuzzyScore(query, `${command.title} ${command.keywords ?? ''}`),
    }))
    .filter((entry): entry is { command: PaletteCommand; score: number } => entry.score !== null)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((entry) => entry.command);
}
