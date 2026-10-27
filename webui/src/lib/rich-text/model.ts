/**
 * The rich text document model (master §5.7, web.md "Rich text").
 *
 * A line of player-visible text is a list of cells: one per character, glyph
 * or kept tag, each with a full style. Formatting a selection restyles cells;
 * there is no tree to keep balanced. `parseLine` reads the stored form (a
 * MiniMessage subset, with legacy `&`/`§` codes accepted as input) and
 * `serializeLine` writes the canonical MiniMessage form. Both mirror the
 * plugin's MaterioneText; `fixtures/text/rich-text.json` keeps them honest.
 */
import { namedColor } from './colors';

export interface TextStyle {
  /** A named color (`red`) or `#rrggbb`. Exclusive with `gradient`. */
  color?: string;
  /** Two or more stops, each a named color or `#rrggbb`. */
  gradient?: string[];
  bold?: true;
  italic?: true;
  underline?: true;
  strikethrough?: true;
  obfuscated?: true;
  /** A shader effect reference as written (`sample:rainbow`). */
  effect?: string;
}

export type Decoration = 'bold' | 'italic' | 'underline' | 'strikethrough' | 'obfuscated';
export const DECORATIONS: readonly Decoration[] = ['bold', 'italic', 'underline', 'strikethrough', 'obfuscated'];

export type Cell =
  /** One code point. `\n` separates lines in a multi-line document. */
  | { kind: 'char'; ch: string; style: TextStyle }
  | { kind: 'glyph'; id: string; colorable: boolean; style: TextStyle }
  /** A tag the editor does not model (`<shift:4>`, `<rainbow>`), kept exactly as written. */
  | { kind: 'tag'; raw: string; style: TextStyle };

const DECORATION_TAGS: Record<string, Decoration> = {
  b: 'bold', bold: 'bold',
  i: 'italic', italic: 'italic', em: 'italic',
  u: 'underline', underlined: 'underline',
  st: 'strikethrough', strikethrough: 'strikethrough',
  obf: 'obfuscated', obfuscated: 'obfuscated',
};
const SHORT_TAG: Record<Decoration, string> = {
  bold: 'b', italic: 'i', underline: 'u', strikethrough: 'st', obfuscated: 'obf',
};
const LEGACY_DECORATIONS: Record<string, Decoration> = {
  k: 'obfuscated', l: 'bold', m: 'strikethrough', n: 'underline', o: 'italic',
};
const LEGACY_COLORS = '0123456789abcdef';

/** A style with only its set keys, so equal styles compare equal. */
export function cleanStyle(style: TextStyle): TextStyle {
  const out: TextStyle = {};
  if (style.effect) out.effect = style.effect;
  if (style.gradient && style.gradient.length >= 2) out.gradient = [...style.gradient];
  else if (style.color) out.color = style.color;
  for (const decoration of DECORATIONS) if (style[decoration]) out[decoration] = true;
  return out;
}

export function styleKey(style: TextStyle): string {
  return JSON.stringify(cleanStyle(style));
}

export function sameStyle(a: TextStyle, b: TextStyle): boolean {
  return styleKey(a) === styleKey(b);
}

/** Normalizes a color as the parser understands it: a canonical name or lowercase `#rrggbb`. */
export function normalizeColor(raw: string): string | undefined {
  if (/^#[0-9a-f]{6}$/i.test(raw)) return raw.toLowerCase();
  return namedColor(raw.toLowerCase())?.name;
}

// --- parsing -----------------------------------------------------------------

/** What an open tag contributes; legacy frames are the ones a `&`-code opened. */
interface Frame {
  close: string;
  legacy: boolean;
  apply: (style: TextStyle) => TextStyle;
}

function fold(frames: readonly Frame[]): TextStyle {
  return cleanStyle(frames.reduce<TextStyle>((style, frame) => frame.apply(style), {}));
}

const withColor = (color: string) => (style: TextStyle): TextStyle => {
  const next = { ...style, color };
  delete next.gradient;
  return next;
};
const withGradient = (gradient: string[]) => (style: TextStyle): TextStyle => {
  const next = { ...style, gradient };
  delete next.color;
  return next;
};
const withDecoration = (decoration: Decoration) => (style: TextStyle): TextStyle => ({ ...style, [decoration]: true });
// an effect colors its text with the shader signal: an outer color no longer shows
const withEffect = (effect: string) => (style: TextStyle): TextStyle => {
  const next = { ...style, effect };
  delete next.color;
  delete next.gradient;
  return next;
};

/** The `>` that ends the tag opening at `start`, honoring quoted arguments; -1 when none. */
function tagEnd(text: string, start: number): number {
  let quote = '';
  for (let at = start + 1; at < text.length; at++) {
    const c = text[at]!;
    if (quote) {
      if (c === '\\') at++;
      else if (c === quote) quote = '';
    } else if (c === "'" || c === '"') quote = c;
    else if (c === '<') return -1;
    else if (c === '>') return at;
  }
  return -1;
}

function splitArgs(body: string): string[] {
  const out: string[] = [];
  let current = '';
  let quote = '';
  for (let at = 0; at < body.length; at++) {
    const c = body[at]!;
    if (quote) {
      if (c === quote) quote = '';
      else current += c;
    } else if (c === "'" || c === '"') quote = c;
    else if (c === ':') {
      out.push(current);
      current = '';
    } else current += c;
  }
  out.push(current);
  return out;
}

/** A legacy code at `at` (`&c`, `&#rrggbb`, `&x&r&r&g&g&b&b`, `§` alike), or undefined. */
export function legacyCodeAt(text: string, at: number): { code: string; length: number } | undefined {
  const marker = text[at];
  if ((marker !== '&' && marker !== '§') || at + 1 >= text.length) return;
  const next = text[at + 1]!;
  if (next === '#') {
    const hex = text.slice(at + 2, at + 8);
    return /^[0-9a-f]{6}$/i.test(hex) ? { code: `#${hex.toLowerCase()}`, length: 8 } : undefined;
  }
  if (next.toLowerCase() === 'x' && at + 14 <= text.length) {
    let hex = '';
    for (let i = at + 2; i < at + 14; i += 2) {
      if (text[i] !== marker) return;
      hex += text[i + 1];
    }
    if (/^[0-9a-f]{6}$/i.test(hex)) return { code: `#${hex.toLowerCase()}`, length: 14 };
    return;
  }
  const code = next.toLowerCase();
  return /^[0-9a-fk-or]$/.test(code) ? { code, length: 2 } : undefined;
}

/** The legacy meaning of one code applied to a style (used for codes typed into the editor too). */
export function applyLegacyCode(style: TextStyle, code: string): TextStyle {
  const keep: TextStyle = style.effect ? { effect: style.effect } : {};
  if (code === 'r') return keep;
  if (code.startsWith('#')) return { ...keep, color: code };
  const index = LEGACY_COLORS.indexOf(code);
  if (index >= 0) return { ...keep, color: namedColorByCode(code) };
  const decoration = LEGACY_DECORATIONS[code];
  return decoration ? cleanStyle({ ...style, [decoration]: true }) : style;
}

function namedColorByCode(code: string): string {
  const names = ['black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray',
    'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple', 'yellow', 'white'];
  return names[LEGACY_COLORS.indexOf(code)]!;
}

/** What an open tag does, or undefined when the editor keeps it as written. */
function openFrame(name: string, args: string[]): Frame | undefined {
  const decoration = DECORATION_TAGS[name];
  if (decoration && args.length === 0) return { close: name, legacy: false, apply: withDecoration(decoration) };
  const named = args.length === 0 ? normalizeColor(name) : undefined;
  if (named) return { close: name, legacy: false, apply: withColor(named) };
  if ((name === 'color' || name === 'colour' || name === 'c') && args.length === 1) {
    const color = normalizeColor(args[0]!);
    if (color) return { close: name, legacy: false, apply: withColor(color) };
  }
  if (name === 'gradient' && args.length >= 2) {
    const stops = args.map(normalizeColor);
    if (stops.every((stop): stop is string => stop !== undefined)) {
      return { close: name, legacy: false, apply: withGradient(stops) };
    }
  }
  if ((name === 'effect' || name === 'e') && args.length >= 1 && args.every((arg) => /^[a-z0-9_.\-/]+$/i.test(arg))) {
    return { close: name, legacy: false, apply: withEffect(args.join(':')) };
  }
  return undefined;
}

/** Parses one stored line. Never throws: anything unrecognised is kept as text or as a tag cell. */
export function parseLine(text: string): Cell[] {
  const cells: Cell[] = [];
  let frames: Frame[] = [];
  const push = (cell: Cell) => cells.push(cell);
  const char = (ch: string) => push({ kind: 'char', ch, style: fold(frames) });

  for (let at = 0; at < text.length;) {
    const c = text[at]!;
    if (c === '\\' && (text[at + 1] === '<' || text[at + 1] === '\\')) {
      char(text[at + 1]!);
      at += 2;
      continue;
    }
    const legacy = legacyCodeAt(text, at);
    if (legacy) {
      const first = frames.findIndex((frame) => frame.legacy);
      const code = legacy.code;
      if (code === 'r' || code.startsWith('#') || LEGACY_COLORS.includes(code)) {
        if (first >= 0) frames = frames.slice(0, first);
        if (code !== 'r') {
          const color = code.startsWith('#') ? code : namedColorByCode(code);
          frames.push({ close: color, legacy: true, apply: withColor(color) });
        }
      } else {
        const decoration = LEGACY_DECORATIONS[code]!;
        if (!frames.some((frame) => frame.legacy && frame.close === SHORT_TAG[decoration])) {
          frames.push({ close: SHORT_TAG[decoration], legacy: true, apply: withDecoration(decoration) });
        }
      }
      at += legacy.length;
      continue;
    }
    if (c === '<') {
      const end = tagEnd(text, at);
      const body = end > at ? text.slice(at + 1, end) : '';
      const closing = body.startsWith('/');
      const [rawName = '', ...args] = splitArgs(closing ? body.slice(1) : body);
      const name = rawName.toLowerCase();
      if (end > at && /^[!?#]?[a-z0-9_-]+$/.test(name)) {
        const raw = text.slice(at, end + 1);
        at = end + 1;
        if (closing) {
          let index = frames.length - 1;
          while (index >= 0 && frames[index]!.close !== name) index--;
          if (index >= 0) frames = frames.slice(0, index);
          else push({ kind: 'tag', raw, style: fold(frames) });
          continue;
        }
        if (name === 'reset' && args.length === 0) {
          frames = [];
          continue;
        }
        if ((name === 'glyph' || name === 'g') && args.length >= 1) {
          const colorable = args.length > 1 && args.at(-1)!.toLowerCase() === 'colorable';
          const id = (colorable ? args.slice(0, -1) : args).join(':');
          if (id && /^[a-z0-9_.\-/:]+$/.test(id)) {
            push({ kind: 'glyph', id, colorable, style: fold(frames) });
            continue;
          }
        }
        const frame = openFrame(name, args);
        if (frame) frames.push(frame);
        else push({ kind: 'tag', raw, style: fold(frames) });
        continue;
      }
    }
    const codePoint = String.fromCodePoint(text.codePointAt(at)!);
    char(codePoint);
    at += codePoint.length;
  }
  return cells;
}

// --- serialising -----------------------------------------------------------------

interface OpenTag {
  key: string;
  open: string;
  close: string;
}

function tagsFor(style: TextStyle): OpenTag[] {
  const tags: OpenTag[] = [];
  if (style.effect) tags.push({ key: `e:${style.effect}`, open: `<effect:${style.effect}>`, close: '</effect>' });
  if (style.gradient) {
    const open = `<gradient:${style.gradient.join(':')}>`;
    tags.push({ key: open, open, close: '</gradient>' });
  } else if (style.color) {
    tags.push({ key: `c:${style.color}`, open: `<${style.color}>`, close: `</${style.color}>` });
  }
  for (const decoration of DECORATIONS) {
    if (style[decoration]) {
      const tag = SHORT_TAG[decoration];
      tags.push({ key: tag, open: `<${tag}>`, close: `</${tag}>` });
    }
  }
  return tags;
}

function escapeText(ch: string): string {
  return ch === '<' || ch === '\\' ? `\\${ch}` : ch;
}

/**
 * The canonical stored form of one line: tags open in a fixed order (effect,
 * gradient or color, then decorations), close only when the style changes, and
 * are left open at the end. A glyph is written `<glyph:id>`.
 */
export function serializeLine(cells: readonly Cell[]): string {
  let out = '';
  const open: OpenTag[] = [];
  for (const cell of cells) {
    if (cell.kind === 'char' && cell.ch === '\n') continue;
    const wanted = tagsFor(cleanStyle(cell.style));
    let common = 0;
    while (common < open.length && common < wanted.length && open[common]!.key === wanted[common]!.key) common++;
    while (open.length > common) out += open.pop()!.close;
    for (const tag of wanted.slice(common)) {
      out += tag.open;
      open.push(tag);
    }
    if (cell.kind === 'char') out += escapeText(cell.ch);
    else if (cell.kind === 'glyph') out += `<glyph:${cell.id}${cell.colorable ? ':colorable' : ''}>`;
    else out += cell.raw;
  }
  return out;
}

/** The canonical form of a stored line (what the editor would write for the same look). */
export function canonicalLine(stored: string): string {
  return serializeLine(parseLine(stored));
}

// --- documents and values ---------------------------------------------------------

export const NEWLINE: Cell = { kind: 'char', ch: '\n', style: {} };

export function isNewline(cell: Cell | undefined): boolean {
  return cell?.kind === 'char' && cell.ch === '\n';
}

/** Splits a document into its lines (always at least one). */
export function splitLines(cells: readonly Cell[]): Cell[][] {
  const lines: Cell[][] = [[]];
  for (const cell of cells) {
    if (isNewline(cell)) lines.push([]);
    else lines.at(-1)!.push(cell);
  }
  return lines;
}

/** A stored value as a document, or null when its shape is not text (a map, a nested list). */
export function documentOf(value: unknown, multiline: boolean): Cell[] | null {
  if (value === undefined || value === null) return [];
  if (!multiline) return typeof value === 'string' && !/[\r\n]/.test(value) ? parseLine(value) : null;
  if (!Array.isArray(value) || !value.every((line) => typeof line === 'string' && !/[\r\n]/.test(line))) return null;
  return value.flatMap((line, index) => (index === 0 ? parseLine(line) : [NEWLINE, ...parseLine(line)]));
}

/**
 * The value to store for a document, keeping the author's bytes wherever the
 * look did not change (master §5.6): a line that means what an original line
 * meant is written back exactly as it was, and an unchanged document returns
 * `original` itself.
 */
export function storedValue(cells: readonly Cell[], multiline: boolean, original: unknown): unknown {
  const originals = multiline
    ? (Array.isArray(original) ? original.filter((line): line is string => typeof line === 'string') : [])
    : typeof original === 'string' ? [original] : [];
  const byCanonical = new Map<string, string>();
  for (const line of originals) if (!byCanonical.has(canonicalLine(line))) byCanonical.set(canonicalLine(line), line);
  const lines = splitLines(cells).map((line) => {
    const canonical = serializeLine(line);
    return byCanonical.get(canonical) ?? canonical;
  });
  if (!multiline) {
    const [line = ''] = lines;
    if (original === undefined || original === null) return line === '' ? original : line;
    return line === original ? original : line;
  }
  const next = cells.length === 0 ? [] : lines;
  if (Array.isArray(original) && original.length === next.length && next.every((line, index) => line === original[index])) {
    return original;
  }
  if ((original === undefined || original === null) && next.length === 0) return original;
  return next;
}

/** The text a player reads, without formatting (summaries, search, labels). */
export function plainText(stored: string): string {
  return parseLine(stored).map((cell) => (cell.kind === 'char' ? cell.ch : '')).join('');
}

/** Runs of adjacent same-style text, for tests and fixtures. */
export interface Run extends TextStyle {
  text?: string;
  glyph?: string;
  colorable?: true;
  tag?: string;
}

export function runsOf(cells: readonly Cell[]): Run[] {
  const runs: Run[] = [];
  for (const cell of cells) {
    const style = cleanStyle(cell.style);
    const last = runs.at(-1);
    if (cell.kind === 'char' && last?.text !== undefined && styleKey(last) === styleKey(style)) {
      last.text += cell.ch;
    } else if (cell.kind === 'char') runs.push({ text: cell.ch, ...style });
    else if (cell.kind === 'glyph') runs.push({ glyph: cell.id, ...(cell.colorable ? { colorable: true } : {}), ...style });
    else runs.push({ tag: cell.raw, ...style });
  }
  return runs;
}

