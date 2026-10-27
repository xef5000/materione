/**
 * The Content browser's pure logic: turning the flat resource/folder listings
 * from the server into resource rows within a folder of one content type. Kept
 * free of Svelte so it can be pinned by unit tests.
 */
import type { ResourceRef, ResourceSummary, TypeInfo } from './api';
import { plainText } from './rich-text/model';
import { materialLabel } from './materials';

export interface BrowserEntry {
  kind: 'folder' | 'doc';
  /** Stable selection identity; documents are one row per resource, not per file. */
  key: string;
  /** What the row shows: folder name, or the resource's human-readable name. */
  name: string;
  /** Pack-relative path of the folder or file. */
  path: string;
  /** Resource id ('' for folders). */
  id: string;
  /** Every resource id in the file, for explicit file-scoped actions. */
  ids: string[];
  /** For folders, how many documents live inside (recursively). */
  count: number;
  /** Folder segments between the current folder and the hit, for search results. */
  location: string;
  /** The exact root represented by this row (absent for folders). */
  resource?: ResourceRef;
  /** Server-computed catalog metadata; never inferred from document reads. */
  summary?: ResourceSummary;
}

export type CatalogLayout = 'grid' | 'list';

export interface CatalogPreferences {
  layout: CatalogLayout;
  showTechnicalColumns: boolean;
}

export interface PreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const PREFERENCES_KEY = 'materione.content-catalog.preferences.v1';
const DEFAULT_PREFERENCES: CatalogPreferences = {
  layout: 'grid',
  showTechnicalColumns: false,
};

export interface TypeTab {
  /** Pack directory: the id the server speaks ("items"). */
  directory: string;
  label: string;
  /** Singular noun for the "+ New …" button. */
  singular: string;
  enabled: boolean;
  /** Why a tab is disabled, shown as its tooltip. */
  note?: string;
}

/** Display order for the tabs an author reaches for most. */
const ORDER = ['items', 'blocks', 'furnitures', 'recipes', 'templates', 'glyphs', 'sounds', 'paintings', 'effects'];

const LABELS: Record<string, { label: string; singular: string }> = {
  items: { label: 'Items', singular: 'item' },
  blocks: { label: 'Blocks', singular: 'block' },
  furnitures: { label: 'Furniture', singular: 'furniture' },
  recipes: { label: 'Recipes', singular: 'recipe' },
  templates: { label: 'Templates', singular: 'template' },
  glyphs: { label: 'Glyphs', singular: 'glyph' },
  sounds: { label: 'Sounds', singular: 'sound' },
  paintings: { label: 'Paintings', singular: 'painting' },
  effects: { label: 'Text effects', singular: 'effect' },
};

/** Types not built yet, shown so the shape of the product is visible. */
const PLANNED: TypeTab[] = [
  { directory: 'guis', label: 'GUIs', singular: 'GUI', enabled: false, note: 'coming in M5' },
];

function byOrder(a: string, b: string): number {
  const left = ORDER.indexOf(a);
  const right = ORDER.indexOf(b);
  if (left < 0 && right < 0) return a.localeCompare(b);
  if (left < 0) return 1;
  if (right < 0) return -1;
  return left - right;
}

/**
 * The horizontal type tabs, derived from the registry rather than hardcoded:
 * a type that registers itself shows up here without a UI change (D5).
 */
export function typeTabs(types: TypeInfo[]): TypeTab[] {
  const directories: string[] = [];
  for (const type of types) {
    const directory = type.directory ?? '';
    if (directory && !directories.includes(directory)) {
      directories.push(directory);
    }
  }
  directories.sort(byOrder);
  // Templates are a pack-layout concept, not a registered type (plugin.md §3.1):
  // their tab appears whenever a registered type can be based on one.
  const takesTemplates = types.some((type) =>
    type.parameters.some((field) => field.referenceKind === 'template'));
  if (takesTemplates && !directories.includes('templates')) {
    directories.push('templates');
    directories.sort(byOrder);
  }
  return [
    ...directories.map((directory) => ({
      directory,
      label: LABELS[directory]?.label ?? titleCase(directory),
      singular: LABELS[directory]?.singular ?? directory.replace(/s$/, ''),
      enabled: true,
    })),
    // planned tabs that no registered type covers yet — once a type registers,
    // its real tab replaces the placeholder
    ...PLANNED.filter((tab) => !directories.includes(tab.directory)),
  ];
}

/** The folder actually browsed: an empty selection means the type root. */
export function currentFolder(type: string, folder: string): string {
  return folder && folder.startsWith(type) ? folder : type;
}

/** Path segments as clickable crumbs, starting at the type root. */
export function breadcrumbs(type: string, folder: string): { label: string; folder: string }[] {
  const segments = currentFolder(type, folder).split('/');
  return segments.map((segment, index) => ({
    label: index === 0 ? LABELS[segment]?.label ?? titleCase(segment) : segment,
    folder: segments.slice(0, index + 1).join('/'),
  }));
}

/** The folder one level up, or '' when already at the type root. */
export function parentFolder(type: string, folder: string): string {
  const here = currentFolder(type, folder);
  if (here === type) return '';
  return here.slice(0, here.lastIndexOf('/'));
}

/**
 * What one folder contains: its subfolders first, then one row per resource.
 * With a search term the whole type is searched instead and results carry the
 * folder they were found in, so searching never hides a match behind navigation.
 */
export function listing(
  type: string,
  folder: string,
  resources: ResourceRef[],
  folders: string[],
  search = '',
  summaries: ResourceSummary[] = [],
): BrowserEntry[] {
  const here = currentFolder(type, folder);
  const prefix = here + '/';
  const query = search.trim().toLowerCase();
  const typeResources = resources.filter(
    (resource) => resource.type === type && resource.path.startsWith(`${type}/`),
  );
  const idsByFile = new Map<string, string[]>();
  for (const resource of typeResources) {
    const fileKey = fileIdentity(resource);
    const ids = idsByFile.get(fileKey) ?? [];
    if (!ids.includes(resource.id)) ids.push(resource.id);
    idsByFile.set(fileKey, ids);
  }
  const summariesByResource = new Map(
    summaries.map((summary) => [resourceIdentity(summary), summary] as const),
  );
  const documents = new Map<string, BrowserEntry>();

  for (const resource of typeResources) {
    if (!resource.path.startsWith(prefix)) continue;
    const rest = resource.path.slice(prefix.length);
    const inThisFolder = !rest.includes('/');
    if (!query && !inThisFolder) continue;
    const summary = summariesByResource.get(resourceIdentity(resource));
    if (query && !matches(resource, summary, query)) continue;
    const key = resourceIdentity(resource);
    if (documents.has(key)) continue;
    const summaryName = typeof summary?.name === 'string'
      ? plainText(summary.name).trim()
      : '';
    documents.set(key, {
      kind: 'doc',
      key,
      name: summaryName || humanise(resource.id),
      path: resource.path,
      id: resource.id,
      ids: idsByFile.get(fileIdentity(resource)) ?? [resource.id],
      count: 0,
      location: rest.includes('/') ? rest.slice(0, rest.lastIndexOf('/')) : '',
      resource,
      summary,
    });
  }

  const entries: BrowserEntry[] = [];
  if (!query) {
    for (const path of folders) {
      if (!path.startsWith(prefix) || path.slice(prefix.length).includes('/')) continue;
      const documentsInside = new Set(
        typeResources
          .filter((resource) => resource.path.startsWith(path + '/'))
          .map(fileIdentity),
      );
      entries.push({
        kind: 'folder',
        name: path.slice(prefix.length),
        key: `folder:${path}`,
        path,
        id: '',
        ids: [],
        count: documentsInside.size,
        location: '',
      });
    }
    entries.sort((a, b) => a.name.localeCompare(b.name));
  }

  return [
    ...entries,
    ...[...documents.values()].sort((a, b) =>
      a.name.localeCompare(b.name) || a.id.localeCompare(b.id) || a.path.localeCompare(b.path)),
  ];
}

function matches(resource: ResourceRef, summary: ResourceSummary | undefined, query: string): boolean {
  return (
    searchable(resource.id).includes(query)
    || searchable(resource.path).includes(query)
    || searchable(humanise(resource.id)).includes(query)
    || searchable(typeof summary?.name === 'string' ? summary.name : '').includes(query)
    || searchable(typeof summary?.material === 'string' ? summary.material : '').includes(query)
    || searchable(typeof summary?.material === 'string' ? materialDisplayName(summary.material) : '')
      .includes(query)
  );
}

function searchable(value: string): string {
  return plainText(value).replaceAll('_', ' ').toLowerCase();
}

export function materialDisplayName(material: string): string {
  const plain = plainText(material);
  const id = plain.slice(plain.lastIndexOf(':') + 1);
  return materialLabel(id || plain);
}

/** A resource identity stays distinct when several roots share one file. */
export function resourceIdentity(resource: Pick<ResourceRef, 'pack' | 'path' | 'id'>): string {
  return JSON.stringify([resource.pack, resource.path, resource.id]);
}

function fileIdentity(resource: Pick<ResourceRef, 'pack' | 'path'>): string {
  return JSON.stringify([resource.pack, resource.path]);
}

/** "ruby_sword" reads as "Ruby sword" in a list of sixty. */
export function humanise(id: string): string {
  const spaced = id.replaceAll('_', ' ').trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function titleCase(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Turns a typed name into a legal resource id, or '' when nothing survives. */
export function idFromName(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 48);
}

/** Read only presentation preferences; content remains server/filesystem-owned (D1). */
export function getCatalogPreferences(
  type: string,
  storage: PreferenceStorage | null = browserStorage(),
): CatalogPreferences {
  if (!storage) return { ...DEFAULT_PREFERENCES };
  try {
    const saved = parsePreferences(storage.getItem(PREFERENCES_KEY));
    return normalizePreferences(saved[type]);
  } catch {
    return { ...DEFAULT_PREFERENCES };
  }
}

/** Persist only layout/technical-column choices, scoped to a registered type. */
export function saveCatalogPreferences(
  type: string,
  preferences: CatalogPreferences,
  storage: PreferenceStorage | null = browserStorage(),
): CatalogPreferences {
  const normalized = normalizePreferences(preferences);
  if (!storage) return normalized;
  try {
    const raw = storage.getItem(PREFERENCES_KEY);
    const saved = parsePreferences(raw);
    saved[type] = normalized;
    storage.setItem(PREFERENCES_KEY, JSON.stringify(saved));
  } catch {
    // Storage can be unavailable in private or constrained browser contexts.
  }
  return normalized;
}

function parsePreferences(raw: string | null): Record<string, Partial<CatalogPreferences>> {
  if (!raw) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, Partial<CatalogPreferences>>
      : {};
  } catch {
    return {};
  }
}

function normalizePreferences(
  value: Partial<CatalogPreferences> | undefined,
): CatalogPreferences {
  return {
    layout: value?.layout === 'list' ? 'list' : 'grid',
    showTechnicalColumns: value?.showTechnicalColumns === true,
  };
}

function browserStorage(): PreferenceStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}
