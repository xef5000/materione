import { describe, expect, it } from 'vitest';
import { editableLegacyLines, editableLegacyText, formatLegacySelection, legacyLines, parseLegacyText, plainLegacyText } from '../src/lib/legacy-text';

describe('legacy item text', () => {
  it('keeps plain HTML and unsupported authoring tokens literal', () => {
    const source = '<img src=x onerror=alert(1)> <glyph:crest> &z &#bad';
    expect(plainLegacyText(source)).toBe(source);
    expect(parseLegacyText(source)).toEqual([expect.objectContaining({ text: source, italic: false })]);
  });

  it('recognizes case-insensitive colors/decorations without changing the source', () => {
    const source = '&6Gold &LBold &oItalic&r plain';
    expect(parseLegacyText(source)).toEqual([
      expect.objectContaining({ text: 'Gold ', color: '6', bold: false }),
      expect.objectContaining({ text: 'Bold ', color: '6', bold: true }),
      expect.objectContaining({ text: 'Italic', color: '6', bold: true, italic: true }),
      expect.objectContaining({ text: ' plain', bold: false, italic: false }),
    ]);
    expect(parseLegacyText(source).at(-1)?.color).toBeUndefined();
    expect(source).toBe('&6Gold &LBold &oItalic&r plain');
  });

  it('colors reset previous decorations like the legacy serializer', () => {
    const [segment] = parseLegacyText('&l&n&cRed');
    expect(segment).toMatchObject({ color: 'c', bold: false, underline: false });
  });

  it('recognizes both existing RGB representations', () => {
    expect(parseLegacyText('&#12AB34One &x&A&B&C&D&E&FTwo')).toEqual([
      expect.objectContaining({ color: '#12ab34', text: 'One ' }),
      expect.objectContaining({ color: '#abcdef', text: 'Two' }),
    ]);
  });

  it('retains obfuscated text rather than inventing randomized client rendering', () => {
    expect(parseLegacyText('&ksecret')[0]).toMatchObject({ text: 'secret', obfuscated: true });
  });

  it('preserves blank lines, trailing lines, whitespace and unsupported codes', () => {
    const lines = [' &aFirst ', '', '<effect:glow>Last</effect>', ''];
    expect(legacyLines(editableLegacyLines(lines)!)).toEqual(lines);
    expect(legacyLines('')).toEqual([]);
    expect(editableLegacyLines([''])).toBe('');
  });

  it('refuses to flatten structured or embedded-newline lore', () => {
    expect(editableLegacyLines([{ text: 'unknown' }])).toBeNull();
    expect(editableLegacyLines(['first\nsecond'])).toBeNull();
    expect(editableLegacyLines([3])).toBeNull();
    expect(editableLegacyText({ text: 'unknown' })).toBeNull();
    expect(editableLegacyText('first\nsecond')).toBeNull();
    expect(editableLegacyText('&6 Name ')).toBe('&6 Name ');
  });

  it('inserts a code at the caret without rewriting unsupported source', () => {
    expect(formatLegacySelection('&zSword', 2, 2, 'L')).toEqual({
      text: '&z&lSword', start: 4, end: 4,
    });
  });

  it('restores following style when formatting a selection', () => {
    const edited = formatLegacySelection('&aRed blue', 2, 5, 'l');
    expect(edited).toEqual({ text: '&a&lRed&r&a blue', start: 4, end: 7 });
    expect(parseLegacyText(edited.text).at(-1)).toMatchObject({ text: ' blue', color: 'a', bold: false });
  });

  it('restores the style at the end, not just the beginning of a selection', () => {
    const raw = '&aRed &bblue rest';
    const edited = formatLegacySelection(raw, 2, 12, 'l');
    expect(parseLegacyText(edited.text).at(-1)).toMatchObject({ text: ' rest', color: 'b', bold: false });
    expect(edited.text).toContain('&b&lblue');
  });

  it('applies a selected color/reset even when the text already contains known codes', () => {
    const source = '&6Sword &z<glyph:crest>';
    expect(formatLegacySelection(source, 0, source.length, 'b').text).toBe('&bSword &z<glyph:crest>');
    expect(formatLegacySelection('&6&lSword', 0, 9, 'r').text).toBe('&rSword');
    expect(parseLegacyText(formatLegacySelection('&6Sword', 0, 7, 'l').text)[0]).toMatchObject({
      color: '6', bold: true, text: 'Sword',
    });
  });

  it('formats each lore line independently without deleting blank lines', () => {
    const edited = formatLegacySelection('First\n\nLast', 0, 11, 'l', true);
    expect(legacyLines(edited.text)).toEqual(['&lFirst', '', '&lLast']);
    expect(edited).toMatchObject({ start: 2, end: 15 });
  });

  it('does not carry the previous lore line color into a selected line', () => {
    const raw = '&cFirst\nSecond rest';
    const edited = formatLegacySelection(raw, 8, 14, 'l', true);
    expect(edited.text).toBe('&cFirst\n&lSecond&r rest');
  });

  it('rejects controls outside the supported format instead of injecting them', () => {
    expect(() => formatLegacySelection('text', 0, 0, '" onclick="')).toThrow('Unsupported');
  });
});
