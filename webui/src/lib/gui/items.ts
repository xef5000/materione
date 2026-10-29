/**
 * Item layers as the compiler will treat them (`ItemComponent`, `GuiCompiler.collectSlots`): one
 * real item in one chest slot, a Materione item (`pack:id`) or a vanilla material
 * (`minecraft:diamond_sword`). The studio states each rule the compiler enforces where the author is
 * looking: a slot outside the container, a missing item, an out-of-range amount, and an earlier
 * layer in the same slot that a later one replaces.
 *
 * Pure: no DOM, no Svelte.
 */
import { isHidden, type GuiDocument, type GuiLayer } from './model';
import { bareType } from './raster/flatten';

export const MAX_AMOUNT = 64;

export function isItemLayer(layer: GuiLayer): boolean {
  return bareType(layer) === 'item';
}

export function itemRefOf(layer: GuiLayer): string {
  return typeof layer.item === 'string' ? layer.item : '';
}

export function amountOf(layer: GuiLayer): number {
  return typeof layer.amount === 'number' && Number.isFinite(layer.amount) ? Math.round(layer.amount) : 1;
}

/**
 * The reference to store for a pick. A bare id would resolve in the `materione` namespace in game
 * (`ItemComponent`), not in the pack being edited, so a pack item is always written `pack:id`.
 */
export function storedItemRef(picked: string, pack: string): string {
  const text = picked.trim();
  if (!text) return '';
  return text.includes(':') ? text : `${pack}:${text}`;
}

export function isVanillaRef(ref: string): boolean {
  return ref.trim().startsWith('minecraft:');
}

/** What the compiler does with every visible item layer: one message per problem, by layer id. */
export function analyzeItems(doc: Pick<GuiDocument, 'layers' | 'rows'>): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const add = (id: string, message: string): void => {
    out.set(id, [...(out.get(id) ?? []), message]);
  };
  const last = new Map<number, GuiLayer>();
  const valid: GuiLayer[] = [];
  for (const layer of doc.layers) {
    if (isHidden(layer) || !isItemLayer(layer)) continue;
    const slot = layer.slot;
    if (typeof slot !== 'number' || !Number.isInteger(slot) || slot < 0 || slot > 53) {
      add(layer.id, 'the slot must be a whole number from 0 to 53');
      continue;
    }
    if (slot >= doc.rows * 9) {
      add(layer.id, `slot ${slot} is outside the GUI's ${doc.rows} row${doc.rows === 1 ? '' : 's'}: items fill the chest's own slots, not the player inventory`);
      continue;
    }
    if (!itemRefOf(layer).trim()) {
      add(layer.id, 'choose an item: the layer has none');
      continue;
    }
    if (layer.amount !== undefined && (amountOf(layer) < 1 || amountOf(layer) > MAX_AMOUNT)) {
      add(layer.id, `the amount must be from 1 to ${MAX_AMOUNT}`);
      continue;
    }
    valid.push(layer);
  }
  for (const layer of valid) {
    const earlier = last.get(layer.slot as number);
    if (earlier) add(earlier.id, `slot ${layer.slot} is filled by the later layer "${layer.id}", which replaces this one`);
    last.set(layer.slot as number, layer);
  }
  return out;
}
