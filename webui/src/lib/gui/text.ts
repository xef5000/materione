/**
 * Text layers as the studio draws them (gui.md section 4): the layer's fields, its font and colour,
 * and the measure of its run in the font's own advances. What the compiler will drop, and why, is
 * not decided here: it comes from `gui.validate` (`validation.ts`), so the studio has one set of
 * rules, the server's.
 *
 * Pure: no DOM, no Svelte.
 */
import { smallcaps } from './font/render';
import { DEFAULT_CELL_HEIGHT, measureDefault } from './font/default-font';
import type { GuiLayer } from './model';
import type { Argb } from './raster/color';
import { parseColor } from './raster/color';
import { bareType } from './raster/flatten';

export type TextFont = 'smallcaps' | 'default';

/** Vanilla container title colour (`TextComponent.DEFAULT_COLOR`). */
export const DEFAULT_TEXT_COLOR: Argb = 0xff404040;
/** The longest run the text editor takes (a field length, not a compiler rule). */
export const MAX_TEXT_LENGTH = 128;

export function isTextLayer(layer: GuiLayer): boolean {
  return bareType(layer) === 'text';
}

export function textOf(layer: GuiLayer): string {
  return typeof layer.text === 'string' ? layer.text : '';
}

/** The layer's font; an absent `font` is `smallcaps` (the component default). */
export function fontOf(layer: GuiLayer): TextFont | null {
  if (layer.font === undefined) return 'smallcaps';
  return layer.font === 'smallcaps' || layer.font === 'default' ? layer.font : null;
}

export function colorOf(layer: GuiLayer): Argb {
  return (typeof layer.color === 'string' ? parseColor(layer.color) : null) ?? DEFAULT_TEXT_COLOR;
}

/** The advance of the layer's text in its font: both exact, from the font's own pixels or the vanilla table. */
export function advanceOf(text: string, font: TextFont): number {
  return font === 'smallcaps' ? smallcaps.measure(text) : measureDefault(text);
}

/** The box a text layer occupies: its ink extent, and the height of the font's art. */
export function measureTextLayer(layer: GuiLayer): { w: number; h: number } {
  const font = fontOf(layer) ?? 'smallcaps';
  const text = textOf(layer);
  if (font === 'smallcaps') return { w: Math.max(1, smallcaps.inkExtent(text)), h: smallcaps.artRows };
  return { w: Math.max(1, measureDefault(text) - 1), h: DEFAULT_CELL_HEIGHT - 1 };
}
