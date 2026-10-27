/**
 * Template logic for the editor, kept free of Svelte so it can be unit-tested
 * (issue 154). A template is a pack-layout concept (plugin.md §3.1): a partial
 * definition under `templates/` that a resource names in `template:`. Nothing
 * here stores anything — the server derives which types a template serves and
 * who uses it (`authoring.templates`), and every function below reads those
 * answers plus the registry's own self-description.
 */
import type { ResourceRef, TemplateEntry, TypeInfo, TypeParam } from './api';
import { humanise } from './browser';
import { plainText } from './rich-text/model';

/** The field a type uses to name its template, found by its declared reference kind (never by id). */
export function templateFieldOf(schema: TypeInfo | undefined): TypeParam | undefined {
  return schema?.parameters.find((field) => field.referenceKind === 'template');
}

/** Whether resources of this type can be based on a template. */
export function acceptsTemplates(schema: TypeInfo | undefined): boolean {
  return templateFieldOf(schema) !== undefined;
}

/**
 * The schema a studio renders: the template link is a creation choice shown by
 * the "Based on" control, so the raw field is not a form control anywhere.
 * A template itself is a *partial* definition, so nothing is required of it.
 */
export function studioSchema(schema: TypeInfo | undefined, partial: boolean): TypeInfo | undefined {
  if (!schema) return undefined;
  return {
    ...schema,
    parameters: schema.parameters
      .filter((field) => field.referenceKind !== 'template')
      .map((field) => (partial && field.required ? { ...field, required: false } : field)),
  };
}

/** The reference to write in `template:`: bare inside the pack, namespaced across packs. */
export function templateRef(entry: Pick<TemplateEntry, 'pack' | 'id'>, activePack: string | null): string {
  return entry.pack === activePack ? entry.id : `${entry.pack}:${entry.id}`;
}

/** Full `pack:id` of a reference as authored from a resource of `pack`. */
export function fullTemplateId(authored: string, pack: string): string {
  return authored.includes(':') ? authored : `${pack}:${authored}`;
}

/** Templates a type can use: the ones that serve it, plus any the server could not place. */
export function templatesFor(templates: readonly TemplateEntry[], directory: string): TemplateEntry[] {
  return templates.filter((entry) => entry.serves.length === 0 || entry.serves.includes(directory));
}

/** What a template is called: its display name when it sets one, else its humanised id. */
export function templateTitle(entry: Pick<TemplateEntry, 'id' | 'definition'>): string {
  const named = entry.definition.display_name;
  const text = typeof named === 'string' ? plainText(named).trim() : '';
  return text || humanise(entry.id);
}

export interface TemplateSet {
  /** The field id the template writes. */
  field: string;
  /** What the author sees it as: the schema label, else the id. */
  label: string;
  /** Whether the schema presents it as a module (a card the author adds). */
  module: boolean;
}

/**
 * The fields (modules, where the schema calls them that) a template sets, in
 * schema order, from the schema of the type it serves. Keys the schema does
 * not know are kept last under their own id — a template can carry anything.
 */
export function templateSets(
  entry: Pick<TemplateEntry, 'definition'>,
  schema: TypeInfo | undefined,
): TemplateSet[] {
  const keys = Object.keys(entry.definition).filter((key) => key !== 'template');
  const known: TemplateSet[] = [];
  const order = new Map((schema?.parameters ?? []).map((field, index) => [field.id, index] as const));
  for (const key of keys) {
    const field = schema?.parameters.find((candidate) => candidate.id === key);
    known.push({
      field: key,
      label: field?.label || humanise(key),
      module: field?.module === true,
    });
  }
  return known.sort((a, b) =>
    (order.get(a.field) ?? Number.MAX_SAFE_INTEGER) - (order.get(b.field) ?? Number.MAX_SAFE_INTEGER)
    || a.field.localeCompare(b.field));
}

/** The material a template shows its preview icon from, when it sets one. */
export function templateMaterial(entry: Pick<TemplateEntry, 'definition'>): string | undefined {
  const material = entry.definition.material;
  return typeof material === 'string' && material.trim() ? material.trim() : undefined;
}

function haystack(entry: TemplateEntry, types: readonly TypeInfo[]): string {
  const labels = templateSets(entry, servedSchema(entry, types)).map((set) => set.label);
  return [entry.id, templateTitle(entry), ...Object.keys(entry.definition), ...labels]
    .join(' ').replaceAll('_', ' ').toLowerCase();
}

/**
 * Case-insensitive search over id, title, and the fields (as the schema labels
 * them) a template sets; best matches first. With no types the raw keys match.
 */
export function searchTemplates(
  entries: readonly TemplateEntry[],
  query: string,
  types: readonly TypeInfo[] = [],
): TemplateEntry[] {
  const needle = query.trim().toLowerCase().replaceAll('_', ' ');
  if (!needle) return [...entries];
  const scored: { entry: TemplateEntry; score: number }[] = [];
  for (const entry of entries) {
    const title = templateTitle(entry).toLowerCase();
    const id = entry.id.replaceAll('_', ' ').toLowerCase();
    let score = -1;
    if (id === needle || title === needle) score = 0;
    else if (id.startsWith(needle) || title.startsWith(needle)) score = 1;
    else if (id.includes(needle) || title.includes(needle)) score = 2;
    else if (haystack(entry, types).includes(needle)) score = 3;
    if (score >= 0) scored.push({ entry, score });
  }
  return scored
    .sort((a, b) => a.score - b.score || a.entry.id.localeCompare(b.entry.id))
    .map((hit) => hit.entry);
}

/** The schema of the first type a template serves (the one its studio edits it as). */
export function servedSchema(
  entry: Pick<TemplateEntry, 'serves'> | undefined,
  types: readonly TypeInfo[],
): TypeInfo | undefined {
  const directory = entry?.serves[0];
  return directory ? types.find((type) => type.directory === directory) : undefined;
}

/**
 * The fields offered when saving a resource as a template: everything the
 * resource sets itself, except its own identity (its name belongs to it, not
 * to what it would share). The link to its own template is kept, so the new
 * template extends the same chain.
 */
export function extractableFields(body: Record<string, unknown>): { field: string; selected: boolean }[] {
  return Object.keys(body).map((field) => ({ field, selected: field !== 'display_name' }));
}

/** One line for a value in a preview ("Forged, Hard", "durability…"). */
export function valuePreview(value: unknown): string {
  if (typeof value === 'string') return plainText(value);
  if (Array.isArray(value)) return `${value.length} entr${value.length === 1 ? 'y' : 'ies'}`;
  if (value && typeof value === 'object') return `${Object.keys(value).length} settings`;
  return String(value);
}

/** Resources that inherit a template, grouped for "used by N". */
export function usersLabel(users: readonly ResourceRef[]): string {
  return users.length === 0 ? 'Not used yet' : `Used by ${users.length}`;
}

/** What the New dialog hands back: a name, the id derived from it, and where to start from. */
export interface NewChoice {
  name: string;
  id: string;
  start:
    | { kind: 'blank' }
    | { kind: 'template'; template: TemplateEntry }
    | { kind: 'duplicate'; source: ResourceRef };
  /** For a new template: the type directory it is for. */
  serves?: string;
}

/**
 * Templates a resource may be based on: those that serve its type, minus the
 * template being edited and everything that already inherits from it (which
 * would close a cycle).
 */
export function selectableTemplates(
  templates: readonly TemplateEntry[],
  directory: string,
  self?: { pack: string; id: string },
): TemplateEntry[] {
  const blocked = new Set<string>();
  if (self) {
    const visit = (pack: string, id: string) => {
      const key = `${pack}:${id}`;
      if (blocked.has(key)) return;
      blocked.add(key);
      const entry = templates.find((candidate) => candidate.pack === pack && candidate.id === id);
      for (const user of entry?.usedBy ?? []) {
        if (user.type === 'templates') visit(user.pack, user.id);
      }
    };
    visit(self.pack, self.id);
  }
  return templatesFor(templates, directory).filter((entry) => !blocked.has(`${entry.pack}:${entry.id}`));
}
