/**
 * Item layers as the studio reads and writes them: the layer's item reference and its amount. Which
 * slots, items and amounts the compiler accepts is decided by the server (`gui.validate`), not here.
 *
 * Pure: no DOM, no Svelte.
 */
import type { GuiLayer } from './model';

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
