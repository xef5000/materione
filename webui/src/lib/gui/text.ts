/**
 * Text layers as the compiler will treat them (gui.md section 4, decisions of issue 184). A text
 * layer is title text, not baked pixels, and the compiler drops a run it cannot place; the studio
 * states each of those rules where the author is looking instead of letting them be discovered in
 * game. This module mirrors `GuiCompiler.textProblem` and the title assembler's pen rule:
 *
 *  - text may not contain `<`, `\`, `&` or the section sign (MiniMessage / legacy-code syntax);
 *  - both fonts draw printable ASCII only;
 *  - `default`-font text must start on row 5 or lower (its glyph cell is 8 px high);
 *  - the compiler does not know the vanilla font's widths, so after a `default` run the pen is
 *    unknown: only the LAST text run of a title may be `default`, and every run after one is skipped.
 *
 * Pure: no DOM, no Svelte.
 */
import { smallcaps } from './font/render';
import { DEFAULT_CELL_HEIGHT, measureDefault } from './font/default-font';
import { isHidden, layerLabel, type GuiDocument, type GuiLayer } from './model';
import type { Argb } from './raster/color';
import { parseColor } from './raster/color';
import { bareType } from './raster/flatten';

export type TextFont = 'smallcaps' | 'default';

/** Vanilla container title colour (`TextComponent.DEFAULT_COLOR`). */
export const DEFAULT_TEXT_COLOR: Argb = 0xff404040;
export const MAX_TEXT_LENGTH = 128;
export const RESERVED_TEXT = '<\\&§';
/** A default-font glyph is 8 px high; the offset font's ascent is `13 - y`, which must not exceed it. */
export const DEFAULT_MIN_ROW = 13 - DEFAULT_CELL_HEIGHT;

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

/** The advance of the layer's text in its font (smallcaps exact, default approximate). */
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

export interface TextIssue {
  /** `skip`: the compiler drops this run (MTR-0052). `note`: it compiles, with a consequence worth knowing. */
  severity: 'skip' | 'note';
  message: string;
}

/** The first character a font cannot draw or the title syntax reserves, or null. */
export function offendingCharacter(text: string, font: TextFont): { char: string; reserved: boolean } | null {
  for (const char of text) {
    if (RESERVED_TEXT.includes(char)) return { char, reserved: true };
    const code = char.codePointAt(0)!;
    if (font === 'smallcaps' ? !smallcaps.supports(code) : code < 0x20 || code > 0x7e) return { char, reserved: false };
  }
  return null;
}

function ownProblems(layer: GuiLayer): string[] {
  const problems: string[] = [];
  const text = textOf(layer);
  const font = fontOf(layer);
  if (!text.trim()) problems.push('empty text is skipped');
  if (text.length > MAX_TEXT_LENGTH) problems.push(`text is longer than ${MAX_TEXT_LENGTH} characters`);
  if (font === null) {
    problems.push('font must be smallcaps or default');
    return problems;
  }
  const bad = offendingCharacter(text, font);
  if (bad?.reserved) problems.push('text may not contain <, \\, & or §');
  else if (bad) problems.push(`the ${font} font draws printable ASCII only (not "${bad.char}")`);
  if (font === 'default' && typeof layer.y === 'number' && layer.y < DEFAULT_MIN_ROW) {
    problems.push(`default-font text must start on row ${DEFAULT_MIN_ROW} or lower, because its glyphs are ${DEFAULT_CELL_HEIGHT} px high`);
  }
  if (typeof layer.color === 'string' && parseColor(layer.color) === null) problems.push('colour must be #RRGGBB or #AARRGGBB');
  for (const axis of ['x', 'y'] as const) {
    const value = layer[axis];
    if (value !== undefined && (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 512)) {
      problems.push(`${axis} must be a whole number from 0 to 512`);
    }
  }
  return problems;
}

/**
 * What the compiler will do with every visible text layer, in the compiler's own order (list order):
 * the layers it skips with the reason, and the `default` run that silently blocks the runs after it.
 */
export function analyzeText(doc: Pick<GuiDocument, 'layers'>): Map<string, TextIssue[]> {
  const out = new Map<string, TextIssue[]>();
  const add = (layer: GuiLayer, issue: TextIssue): void => {
    out.set(layer.id, [...(out.get(layer.id) ?? []), issue]);
  };
  const runs: GuiLayer[] = [];
  for (const layer of doc.layers) {
    if (isHidden(layer) || !isTextLayer(layer)) continue;
    const problems = ownProblems(layer);
    problems.forEach((message) => add(layer, { severity: 'skip', message }));
    if (!problems.length) runs.push(layer);
  }
  let blocker: GuiLayer | null = null;
  for (const layer of runs) {
    if (blocker) {
      add(layer, {
        severity: 'skip',
        message: `follows the default-font text "${layerLabel(blocker)}", whose width the compiler cannot measure; place it before that text, or use smallcaps`,
      });
      continue;
    }
    if (fontOf(layer) === 'default') {
      blocker = layer;
      const later = runs.slice(runs.indexOf(layer) + 1);
      if (later.length) {
        add(layer, {
          severity: 'note',
          message: `default-font text must be the last text layer: ${later.length === 1 ? 'the text layer listed above it is' : `${later.length} text layers listed above it are`} skipped in game`,
        });
      }
    }
  }
  return out;
}

export function isSkipped(issues: readonly TextIssue[] | undefined): boolean {
  return !!issues?.some((issue) => issue.severity === 'skip');
}
