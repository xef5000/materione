/**
 * The client's own font, as far as the studio can know it (gui.md section 4). A `default` text
 * run references the vanilla ASCII bitmap by resource location and nothing of it is redistributed,
 * so the studio never has its pixels: the preview draws the bundled Minecraftia face and says so
 * ("approximate preview"). These advances only size the selection box; they are the widely
 * documented widths of the vanilla ASCII font, not data the compiler carries (which is why the
 * compiler restricts `default` text to the last run, see `text.ts`).
 */

/** Advances (ink width plus one) that differ from the common 6. */
const NARROW: Readonly<Record<string, number>> = {
  ' ': 4, '!': 2, '"': 5, "'": 3, '(': 5, ')': 5, '*': 5, ',': 2, '.': 2, ':': 2, ';': 2,
  '<': 5, '>': 5, '@': 7, 'I': 4, '[': 4, ']': 4, '`': 3, 'f': 5, 'i': 2, 'k': 5, 'l': 3,
  't': 4, '{': 5, '|': 2, '}': 5, '~': 7,
};

export const DEFAULT_ADVANCE = 6;
export const DEFAULT_CELL_HEIGHT = 8;

/** The vanilla advance of one character (approximate: sizes selection boxes only). */
export function defaultAdvance(char: string): number {
  return NARROW[char] ?? DEFAULT_ADVANCE;
}

export function measureDefault(text: string): number {
  let total = 0;
  for (const char of text) total += defaultAdvance(char);
  return total;
}
