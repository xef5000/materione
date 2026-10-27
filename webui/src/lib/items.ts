/**
 * Item references as the smart inputs see them (issue 153): a value is a bare
 * pack id (`ruby`), a namespaced one (`other:ruby`), or a vanilla item
 * (`minecraft:stick`). Pure helpers so the picker, the drops chips and the
 * recipe slots share one reading of a reference, and the tests can pin it.
 */
import type { ResourceRef, ResourceSummary, TypeInfo } from './api';
import { materialLabel } from './materials';

export interface ItemChoice {
  /** The YAML value a pick writes. */
  value: string;
  label: string;
  group: 'pack' | 'vanilla';
  /** Pack content type the choice comes from ("items", "blocks", …) or "vanilla". */
  directory: string;
}

export interface ParsedItemRef {
  vanilla: boolean;
  /** Namespace as written, or the pack's own for a bare reference. */
  namespace: string;
  id: string;
}

/** Pack directories whose entries can be dropped, crafted or placed as items. */
const ITEM_LIKE = ['items', 'blocks', 'furnitures'] as const;

export function parseItemRef(value: string, pack: string): ParsedItemRef {
  const text = value.trim();
  const colon = text.indexOf(':');
  const namespace = colon < 0 ? pack : text.slice(0, colon);
  const id = colon < 0 ? text : text.slice(colon + 1);
  return { vanilla: namespace === 'minecraft', namespace, id };
}

/** `minecraft:diamond_sword` → "Diamond Sword"; `ruby_sword` → "Ruby Sword". */
export function itemLabel(value: string, pack: string): string {
  const parsed = parseItemRef(value, pack);
  return parsed.id ? materialLabel(parsed.id) : '';
}

/** The vanilla item behind a registry material name (`DIAMOND` → `minecraft:diamond`). */
export function vanillaRefOf(material: string): string {
  return `minecraft:${material.toLowerCase()}`;
}

/** The material list the server publishes on the item type's `material` parameter. */
export function vanillaMaterials(types: readonly TypeInfo[]): string[] {
  const item = types.find((type) => type.directory === 'items' || type.name === 'item');
  return item?.parameters.find((param) => param.id === 'material')?.allowedValues ?? [];
}

/**
 * The reference to write when a pack's resource is picked: bare inside the
 * pack that is being edited (what authors write by hand), namespaced from
 * another pack.
 */
export function packRef(resource: ResourceRef, currentPack: string): string {
  const bare = resource.id.slice(resource.id.lastIndexOf(':') + 1);
  return resource.pack === currentPack ? bare : `${resource.pack}:${bare}`;
}

/**
 * Everything an item picker offers: this pack's items (plus its blocks and
 * furniture when `packOnly` is off, since drops and ingredients accept them),
 * then the server's vanilla materials. `packOnly` limits it to resources that
 * can own a placement link.
 */
export function itemChoices(options: {
  pack: string;
  resources: readonly ResourceRef[];
  types: readonly TypeInfo[];
  packOnly?: boolean;
}): ItemChoice[] {
  const directories: readonly string[] = options.packOnly ? ['items'] : ITEM_LIKE;
  const out: ItemChoice[] = [];
  for (const resource of options.resources) {
    const directory = resource.path.split('/')[0] ?? '';
    if (!directories.includes(directory)) continue;
    out.push({
      value: packRef(resource, options.pack),
      label: materialLabel(resource.id.slice(resource.id.lastIndexOf(':') + 1)),
      group: 'pack',
      directory,
    });
  }
  if (!options.packOnly) {
    for (const material of vanillaMaterials(options.types)) {
      out.push({
        value: vanillaRefOf(material),
        label: materialLabel(material),
        group: 'vanilla',
        directory: 'vanilla',
      });
    }
  }
  return out;
}

/** Search words match the label and the raw reference, all of them required. */
export function matchItemChoices(choices: readonly ItemChoice[], query: string): ItemChoice[] {
  const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  return choices.filter((choice) => {
    const haystack = `${choice.label} ${choice.value}`.toLowerCase();
    return words.every((word) => haystack.includes(word));
  });
}

/** The summary that carries a pack item's visual, for the chip's icon. */
export function summaryFor(
  summaries: readonly ResourceSummary[],
  value: string,
  pack: string,
): ResourceSummary | undefined {
  const parsed = parseItemRef(value, pack);
  if (parsed.vanilla) return undefined;
  return summaries.find((entry) =>
    entry.pack === parsed.namespace &&
    entry.id.slice(entry.id.lastIndexOf(':') + 1) === parsed.id &&
    ITEM_LIKE.some((directory) => entry.path.startsWith(`${directory}/`)));
}

// --- counted lists --------------------------------------------------------

export interface CountedItem {
  value: string;
  count: number;
}

/**
 * A `drops:` list read as counted chips. The format has no count: a count is
 * the same reference repeated, and each repeat is its own drop. Order of
 * first appearance is kept so the chips match the file.
 */
export function countedItems(list: unknown): CountedItem[] {
  if (!Array.isArray(list)) return [];
  const out: CountedItem[] = [];
  for (const entry of list) {
    if (typeof entry !== 'string') continue;
    const existing = out.find((item) => item.value === entry);
    if (existing) existing.count += 1;
    else out.push({ value: entry, count: 1 });
  }
  return out;
}

/** The inverse of {@link countedItems}: each item written `count` times, grouped. */
export function expandCounted(items: readonly CountedItem[]): string[] {
  return items.flatMap((item) => Array.from({ length: Math.max(0, item.count) }, () => item.value));
}

/** Non-string entries (a hand-authored map) are not chips; callers keep them as written. */
export function opaqueEntries(list: unknown): unknown[] {
  return Array.isArray(list) ? list.filter((entry) => typeof entry !== 'string') : [];
}
