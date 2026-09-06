/**
 * The Content browser's pure logic: turning the flat resource/folder listings
 * from the server into what one folder of one content type looks like. Kept
 * free of Svelte so it can be pinned by unit tests.
 */
import type { ResourceRef, TypeInfo } from './api';

export interface BrowserEntry {
  kind: 'folder' | 'doc';
  /** What the row shows: folder name, or the document's id. */
  name: string;
  /** Pack-relative path of the folder or file. */
  path: string;
  /** Representative resource id ('' for folders). */
  id: string;
  /** Every resource id in the file — a file may declare several. */
  ids: string[];
  /** For folders, how many documents live inside (recursively). */
  count: number;
  /** Folder segments between the current folder and the hit, for search results. */
  location: string;
}

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
const ORDER = ['items', 'blocks', 'furnitures', 'recipes', 'templates', 'glyphs'];

const LABELS: Record<string, { label: string; singular: string }> = {
  items: { label: 'Items', singular: 'item' },
  blocks: { label: 'Blocks', singular: 'block' },
  furnitures: { label: 'Furniture', singular: 'furniture' },
  recipes: { label: 'Recipes', singular: 'recipe' },
  templates: { label: 'Templates', singular: 'template' },
  glyphs: { label: 'Glyphs', singular: 'glyph' },
};

/** Types not built yet, shown so the shape of the product is visible. */
const PLANNED: TypeTab[] = [
  { directory: 'sounds', label: 'Sounds', singular: 'sound', enabled: false, note: 'coming in M4' },
  { directory: 'guis', label: 'GUIs', singular: 'GUI', enabled: false, note: 'coming in M5' },
];

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
  directories.sort((a, b) => {
    const left = ORDER.indexOf(a);
    const right = ORDER.indexOf(b);
    if (left < 0 && right < 0) return a.localeCompare(b);
    if (left < 0) return 1;
    if (right < 0) return -1;
    return left - right;
  });
  return [
    ...directories.map((directory) => ({
      directory,
      label: LABELS[directory]?.label ?? titleCase(directory),
      singular: LABELS[directory]?.singular ?? directory.replace(/s$/, ''),
      enabled: true,
    })),
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
 * What one folder contains: its subfolders first, then its documents. With a
 * search term the whole type is searched instead and results carry the folder
 * they were found in, so searching never hides a match behind navigation.
 */
export function listing(
  type: string,
  folder: string,
  resources: ResourceRef[],
  folders: string[],
  search = '',
): BrowserEntry[] {
  const here = currentFolder(type, folder);
  const prefix = here + '/';
  const documents = new Map<string, BrowserEntry>();
  const query = search.trim().toLowerCase();

  for (const resource of resources) {
    if (resource.type !== type || !resource.path.startsWith(prefix)) continue;
    const rest = resource.path.slice(prefix.length);
    const inThisFolder = !rest.includes('/');
    if (!query && !inThisFolder) continue;
    if (query && !matches(resource, query)) continue;
    const existing = documents.get(resource.path);
    if (existing) {
      existing.ids.push(resource.id);
      continue;
    }
    documents.set(resource.path, {
      kind: 'doc',
      name: resource.id,
      path: resource.path,
      id: resource.id,
      ids: [resource.id],
      count: 0,
      location: rest.includes('/') ? rest.slice(0, rest.lastIndexOf('/')) : '',
    });
  }

  const entries: BrowserEntry[] = [];
  if (!query) {
    for (const path of folders) {
      if (!path.startsWith(prefix) || path.slice(prefix.length).includes('/')) continue;
      entries.push({
        kind: 'folder',
        name: path.slice(prefix.length),
        path,
        id: '',
        ids: [],
        count: resources.filter((resource) => resource.path.startsWith(path + '/')).length,
        location: '',
      });
    }
    entries.sort((a, b) => a.name.localeCompare(b.name));
  }

  return [...entries, ...[...documents.values()].sort((a, b) => a.name.localeCompare(b.name))];
}

function matches(resource: ResourceRef, query: string): boolean {
  return (
    resource.id.toLowerCase().includes(query)
    || resource.path.toLowerCase().includes(query)
    || resource.id.replaceAll('_', ' ').toLowerCase().includes(query)
  );
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
