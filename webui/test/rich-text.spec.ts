/**
 * The rich text model (master §5.7). The fixture file is shared with the
 * plugin's MaterioneTextTest, so a stored string means the same thing to the
 * editor and to the game.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse as parseYaml } from 'yaml';
import { gradientColor, hexOf } from '../src/lib/rich-text/colors';
import {
  activeStyle, cellsFromFragment, cellsFromText, deleteContent, format, History, insertGlyph, insertText,
  selectedFragment, selectAll, withSelection, type EditorState,
} from '../src/lib/rich-text/edit';
import {
  canonicalLine, documentOf, parseLine, plainText, runsOf, serializeLine, storedValue, type Cell,
} from '../src/lib/rich-text/model';

const FIXTURES = fileURLToPath(new URL('../../fixtures', import.meta.url));

interface Fixture {
  name: string;
  stored: string;
  canonical?: string;
  runs: unknown[];
}

const shared = JSON.parse(readFileSync(join(FIXTURES, 'text', 'rich-text.json'), 'utf8')) as {
  cases: Fixture[];
  untouched: string[];
};

const state = (stored: string, anchor = 0, focus = anchor): EditorState => ({
  cells: parseLine(stored),
  selection: { anchor, focus },
});
const typeText = (start: EditorState, text: string, multiline = false) =>
  [...text].reduce((current, ch) => insertText(current, ch, multiline), start);
const stored = (current: EditorState) => serializeLine(current.cells);

describe('shared rich text fixture', () => {
  it('covers every style the toolbar can produce', () => {
    const runs = shared.cases.flatMap((fixture) => fixture.runs as Record<string, unknown>[]);
    for (const key of ['color', 'gradient', 'bold', 'italic', 'underline', 'strikethrough', 'obfuscated', 'glyph', 'effect', 'tag']) {
      expect(runs.some((run) => key in run), key).toBe(true);
    }
  });

  for (const fixture of shared.cases) {
    it(`${fixture.name}: parses to its runs and writes the canonical form`, () => {
      expect(runsOf(parseLine(fixture.stored))).toEqual(fixture.runs);
      const canonical = fixture.canonical ?? fixture.stored;
      expect(serializeLine(parseLine(fixture.stored))).toBe(canonical);
      // the canonical form means the same and is a fixed point
      expect(runsOf(parseLine(canonical))).toEqual(fixture.runs);
      expect(canonicalLine(canonical)).toBe(canonical);
    });
  }
});

describe('lossless round-trip of untouched values (golden)', () => {
  function corpusStrings(): { single: string[]; lines: string[][] } {
    const single: string[] = [];
    const lines: string[][] = [];
    const visit = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) visit(path);
        else if (/\.ya?ml$/.test(name)) {
          let doc: unknown;
          try {
            doc = parseYaml(readFileSync(path, 'utf8'));
          } catch {
            continue;
          }
          if (!doc || typeof doc !== 'object') continue;
          for (const resource of Object.values(doc as Record<string, unknown>)) {
            if (!resource || typeof resource !== 'object') continue;
            const fields = resource as Record<string, unknown>;
            for (const key of ['display_name', 'label', 'title', 'author']) if (typeof fields[key] === 'string') single.push(fields[key] as string);
            if (Array.isArray(fields.lore) && fields.lore.every((line) => typeof line === 'string')) lines.push(fields.lore as string[]);
          }
        }
      }
    };
    visit(join(FIXTURES, 'packs'));
    return { single, lines };
  }

  it('gives back the exact original string for every untouched single-line value', () => {
    // names, furniture labels, painting titles and authors: every formatted-text field in the corpus
    const { single } = corpusStrings();
    expect(single.length).toBeGreaterThan(40);
    expect(single.some((value) => value.includes('&'))).toBe(true);
    for (const value of [...shared.untouched, ...single]) {
      const document = documentOf(value, false);
      expect(document, value).not.toBeNull();
      expect(storedValue(document!, false, value)).toBe(value);
    }
  });

  it('gives back the exact original list for untouched lore', () => {
    const { lines } = corpusStrings();
    for (const value of [...lines, shared.untouched, ['&cBurns with fire', '', '&9  keeps spaces  ', '']]) {
      expect(storedValue(documentOf(value, true)!, true, value)).toBe(value);
    }
  });

  it('keeps absent values absent until the author types', () => {
    expect(storedValue(documentOf(undefined, false)!, false, undefined)).toBeUndefined();
    expect(storedValue(documentOf(undefined, true)!, true, undefined)).toBeUndefined();
    expect(storedValue(parseLine('x'), false, undefined)).toBe('x');
  });

  it('returns the original bytes when an edit ends where it started', () => {
    const original = '&6Ruby &lSword';
    let current = state(original, 4);
    current = typeText(current, 'XY');
    expect(storedValue(current.cells, false, original)).toBe('<gold>RubyXY <b>Sword');
    current = deleteContent(deleteContent(current, -1), -1);
    expect(storedValue(current.cells, false, original)).toBe(original);
  });

  it('rewrites only the lore lines whose look changed', () => {
    const original = ['&cBurns', '&9  spaces  ', '<bold>Old</bold>'];
    const document = documentOf(original, true)!;
    // bold the whole second line
    const lineStart = document.findIndex((cell) => cell.kind === 'char' && cell.ch === '\n') + 1;
    const next = format({ cells: document, selection: { anchor: lineStart, focus: lineStart + 10 } }, { kind: 'toggle', decoration: 'bold' });
    expect(storedValue(next.cells, true, original)).toEqual(['&cBurns', '<blue><b>  spaces  ', '<bold>Old</bold>']);
    // inserting a line above keeps every original line's bytes
    const inserted = insertText({ cells: document, selection: { anchor: 0, focus: 0 } }, 'New\n', true);
    expect(storedValue(inserted.cells, true, original)).toEqual(['<red>New', ...original]);
  });

  it('refuses shapes that are not plain text lines', () => {
    expect(documentOf({ text: 'x' }, false)).toBeNull();
    expect(documentOf('first\nsecond', false)).toBeNull();
    expect(documentOf(['ok', 3], true)).toBeNull();
    expect(documentOf('not a list', true)).toBeNull();
  });
});

describe('typing and formatting', () => {
  it('turns a typed legacy code into the style of what comes next', () => {
    let current = typeText(state(''), 'Ruby &lSword');
    expect(stored(current)).toBe('Ruby <b>Sword');
    current = typeText(state(''), '&6Gold &#12abefHex');
    expect(stored(current)).toBe('<gold>Gold </gold><#12abef>Hex');
    // a color code clears decorations, like the game
    current = typeText(state(''), '&lLoud&cRed');
    expect(stored(current)).toBe('<b>Loud</b><red>Red');
    // not a code: stays literal
    expect(stored(typeText(state(''), 'Salt & Pepper &z'))).toBe('Salt & Pepper &z');
  });

  it('converts legacy codes in pasted text but keeps tags literal', () => {
    const cells = cellsFromText('&cHot <b>not bold</b>\r\nnext\u0007', {}, false);
    expect(serializeLine(cells)).toBe('<red>Hot \\<b>not bold\\</b> next');
    const lines = cellsFromText('one\r\ntwo', { bold: true }, true);
    expect(runsOf(lines)).toEqual([{ text: 'one', bold: true }, { text: '\n' }, { text: 'two', bold: true }]);
  });

  it('toggles decorations on a selection and on the next typed text', () => {
    let current = format(state('Ruby Sword', 0, 4), { kind: 'toggle', decoration: 'bold' });
    expect(stored(current)).toBe('<b>Ruby</b> Sword');
    current = format(current, { kind: 'toggle', decoration: 'bold' });
    expect(stored(current)).toBe('Ruby Sword');
    // mixed selection: the first toggle adds to all of it
    current = format(state('<b>Ru</b>by', 0, 4), { kind: 'toggle', decoration: 'bold' });
    expect(stored(current)).toBe('<b>Ruby');
    // collapsed caret: Ctrl+B then typing
    current = format(state('Ruby ', 5), { kind: 'toggle', decoration: 'italic' });
    current = typeText(current, 'Sword');
    expect(stored(current)).toBe('Ruby <i>Sword');
  });

  it('applies every decoration, colors, gradients and clear', () => {
    let current = state('Ruby Sword', 0, 10);
    for (const decoration of ['bold', 'italic', 'underline', 'strikethrough', 'obfuscated'] as const) {
      current = format(current, { kind: 'toggle', decoration });
    }
    expect(stored(current)).toBe('<b><i><u><st><obf>Ruby Sword');
    current = format(current, { kind: 'color', color: 'red' });
    expect(stored(current)).toBe('<red><b><i><u><st><obf>Ruby Sword');
    current = format(current, { kind: 'gradient', stops: ['#f9731e', '#ffd27a', 'white'] });
    expect(stored(current)).toBe('<gradient:#f9731e:#ffd27a:white><b><i><u><st><obf>Ruby Sword');
    expect(activeStyle(current)).toMatchObject({ gradient: ['#f9731e', '#ffd27a', 'white'], bold: true });
    current = format(current, { kind: 'clear' });
    expect(stored(current)).toBe('Ruby Sword');
  });

  it('keeps a gradient continuous across bold inside it', () => {
    let current = format(state('Ruby Sword', 0, 10), { kind: 'gradient', stops: ['#f9731e', '#ffd27a'] });
    current = format(withSelection(current, { anchor: 5, focus: 10 }), { kind: 'toggle', decoration: 'bold' });
    expect(stored(current)).toBe('<gradient:#f9731e:#ffd27a>Ruby <b>Sword');
  });

  it('clearing keeps an effect span authored in Files', () => {
    const current = format(state('<effect:sample:rainbow><b>Hot', 0, 3), { kind: 'clear' });
    expect(stored(current)).toBe('<effect:sample:rainbow>Hot');
  });

  it('types with the style of the text before the caret', () => {
    expect(stored(typeText(state('<red>Ruby', 4), ' Sword'))).toBe('<red>Ruby Sword');
    // at a line start, the text after decides
    expect(stored(typeText(state('<red>Ruby', 0), 'A '))).toBe('<red>A Ruby');
    // inside a gradient, typing extends it
    expect(stored(typeText(state('<gradient:red:blue>Ruby', 2), 'XX'))).toBe('<gradient:red:blue>RuXXby');
  });

  it('keeps text that looks like markup literal and escaped', () => {
    const current = typeText(state(''), '<img src=x onerror=alert(1)> a\\b');
    expect(stored(current)).toBe('\\<img src=x onerror=alert(1)> a\\\\b');
    expect(plainText(stored(current))).toBe('<img src=x onerror=alert(1)> a\\b');
  });

  it('single-line documents turn Enter into nothing and pasted lines into spaces', () => {
    expect(stored(insertText(state('ab', 1), '\n', false))).toBe('a b');
    expect(serializeLine(insertText(state('ab', 1), '\n', true).cells)).toBe('ab');
    expect(insertText(state('ab', 1), '\n', true).cells).toHaveLength(3);
  });

  it('deletes by character, word and line', () => {
    expect(stored(deleteContent(state('Ruby Sword', 10), -1))).toBe('Ruby Swor');
    expect(stored(deleteContent(state('Ruby Sword', 10), -1, 'word'))).toBe('Ruby ');
    expect(stored(deleteContent(state('Ruby Sword  ', 12), -1, 'word'))).toBe('Ruby ');
    expect(stored(deleteContent(state('Ruby Sword', 0), 1, 'word'))).toBe(' Sword');
    expect(stored(deleteContent(state('Ruby Sword', 7), -1, 'line'))).toBe('ord');
    expect(stored(deleteContent(state('Ruby', 0), -1))).toBe('Ruby');
    expect(stored(deleteContent(state('Ruby Sword', 2, 7), -1))).toBe('Ruord');
  });

  it('inserts glyph chips with the surrounding style and keeps them as tags', () => {
    const current = insertGlyph(state('<gold>Gem ', 4), 'heart');
    expect(stored(current)).toBe('<gold>Gem <glyph:heart>');
    expect(runsOf(current.cells).at(-1)).toEqual({ glyph: 'heart', color: 'gold' });
  });

  it('copies a selection as plain text and as a rich fragment', () => {
    const fragment = selectedFragment(state('<red>Ruby <b>Sword', 2, 7));
    expect(fragment).toEqual({ plain: 'by Sw', rich: '<red>by <b>Sw' });
    expect(serializeLine(cellsFromFragment(fragment.rich, false))).toBe('<red>by <b>Sw');
    expect(selectAll(state('abc')).selection).toEqual({ anchor: 0, focus: 3 });
  });

  it('moving the caret drops a pending style', () => {
    const pending = format(state('ab', 1), { kind: 'toggle', decoration: 'bold' });
    expect(pending.pending).toEqual({ bold: true });
    expect(withSelection(pending, { anchor: 2, focus: 2 }).pending).toBeUndefined();
  });
});

describe('history', () => {
  it('coalesces a burst of typing into one undo step and redoes it', () => {
    const history = new History();
    let current = state('');
    for (const [index, ch] of [...'abc'].entries()) {
      history.record(current, 'type', 1000 + index * 100);
      current = insertText(current, ch, false);
    }
    history.record(current, 'format', 2000);
    const formatted = format({ ...current, selection: { anchor: 0, focus: 3 } }, { kind: 'toggle', decoration: 'bold' });
    let undone = history.undo(formatted)!;
    expect(stored(undone)).toBe('abc');
    undone = history.undo(undone)!;
    expect(stored(undone)).toBe('');
    expect(history.undo(undone)).toBeUndefined();
    const redone = history.redo(history.redo(undone)!)!;
    expect(stored(redone)).toBe('<b>abc');
  });

  it('starts a new step after a pause or a seal', () => {
    const history = new History();
    const empty = state('');
    history.record(empty, 'type', 0);
    history.record(state('a'), 'type', 5000);
    history.seal();
    history.record(state('ab'), 'type', 5001);
    expect(stored(history.undo(state('abc'))!)).toBe('ab');
    expect(stored(history.undo(state('ab'))!)).toBe('a');
    expect(stored(history.undo(state('a'))!)).toBe('');
  });
});

describe('colors and plain text', () => {
  it('lands gradient ends exactly on the first and last stop', () => {
    expect(gradientColor(['#ff0000', '#0000ff'], 0, 5)).toBe('#ff0000');
    expect(gradientColor(['#ff0000', '#0000ff'], 4, 5)).toBe('#0000ff');
    expect(gradientColor(['#ff0000', '#00ff00', '#0000ff'], 2, 5)).toBe('#00ff00');
    expect(gradientColor(['red', 'blue'], 0, 1)).toBe('#ff5555');
    expect(hexOf('grey')).toBe('#aaaaaa');
    expect(hexOf('nope')).toBeUndefined();
  });

  it('reads the text a player sees', () => {
    expect(plainText('&6Ruby &lSword')).toBe('Ruby Sword');
    expect(plainText('<gradient:#f9731e:#ffd27a>Ruby</gradient> <glyph:heart> \\<3')).toBe('Ruby  <3');
    expect(plainText('<rainbow>Pride</rainbow>')).toBe('Pride');
  });

  it('never throws on malformed input', () => {
    for (const text of ['<', '>', '<<>>', '</b>', '<gradient:nothex>x', '&', '&x&1', '\\', '<b', "<hover:'unclosed>x"]) {
      const cells: Cell[] = parseLine(text);
      expect(Array.isArray(cells)).toBe(true);
    }
    expect(serializeLine(parseLine('<gradient:nothex>x'))).toBe('<gradient:nothex>x');
  });
});
