/**
 * The icon ban (issue #149): every icon in the editor comes from the one
 * Lucide set through `<Icon name=… />`. A unicode glyph standing in for an icon
 * is invisible to screen readers, renders differently on every OS font, and
 * drifts from the rest of the UI — so it fails here, not in review.
 *
 * The check is deliberately narrow so legitimate typography stays legal:
 * `→` in a "before → after" diff, `×` in "2 × 3", `…` in "Saving…" are text.
 * What is banned is a glyph that is the whole content of a button or label
 * (the icon-only shape) or a glyph passed as a value in an icon map.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const SRC = fileURLToPath(new URL('../src', import.meta.url));

/** The glyphs the editor used as icons before the Lucide set (issue #149). */
const GLYPH_ICONS = [
  '←', '→', '↑', '↓', '↶', '↷', '↻',
  '▤', '⊞', '✕', '×', '◇', '▱', '▧', '▭', '▪', '■', '▢', '◻', '◼',
  '⬛', '⬚', '⊙', '╱', '⌫', '✏', '✎', '⚠', '▴', '▾', '▸', '▼', '⇔', '⚙', '⧉',
];
const GLYPH = `[${GLYPH_ICONS.join('')}]`;

/** A glyph as the only content of a button or label — the icon-only shape. */
const ICON_ONLY_CONTROL = new RegExp(
  // The open tag: attribute values may contain `=>` (arrow functions), never a bare `<`.
  `<(?:button|label)\\b(?:[^<>=]|=>|=(?!>))*>\\s*${GLYPH}\\s*<`,
);

/** A glyph used as a value in a script map, e.g. `save: '✎'` or `glyph: '✏'`. */
const GLYPH_VALUE = new RegExp(`['"\`]${GLYPH}['"\`]`);

function svelteFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) return svelteFiles(path);
    return path.endsWith('.svelte') ? [path] : [];
  });
}

const files = svelteFiles(SRC).map((path) => ({
  name: relative(SRC, path).replaceAll('\\', '/'),
  text: readFileSync(path, 'utf8'),
}));

describe('icon ban (#149)', () => {
  it('scans the editor components, so the ban below is not vacuous', () => {
    expect(files.length).toBeGreaterThan(30);
    expect(files.some((file) => file.name === 'lib/components/Icon.svelte')).toBe(true);
  });

  it('the patterns themselves catch the shapes they ban', () => {
    expect(ICON_ONLY_CONTROL.test('<button class="close" onclick={() => close()}>\n  ✕\n</button>')).toBe(true);
    expect(ICON_ONLY_CONTROL.test('<label class="range">\n  ⬛\n  <input type="range" />')).toBe(true);
    expect(ICON_ONLY_CONTROL.test('<button><Icon name="x" size={14} /></button>')).toBe(false);
    expect(GLYPH_VALUE.test("  save: '✎',")).toBe(true);
    expect(GLYPH_VALUE.test("<span>2 × 3</span>")).toBe(false);
  });

  it('no button or label has a glyph as its only content', () => {
    const offenders = files.filter((file) => ICON_ONLY_CONTROL.test(file.text)).map((file) => file.name);
    expect(offenders, 'use <Icon name=… label=… /> and an aria-label instead').toEqual([]);
  });

  it('no icon map assigns a glyph as a value', () => {
    const offenders = files.filter((file) => GLYPH_VALUE.test(file.text)).map((file) => file.name);
    expect(offenders, 'map to an icons.ts name instead of a glyph').toEqual([]);
  });
});
