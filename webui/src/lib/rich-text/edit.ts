/**
 * Editing operations on a rich text document. Pure: each takes a state and
 * returns the next one, so the contenteditable component only translates
 * browser input into these calls and renders the result.
 */
import {
  applyLegacyCode, cleanStyle, DECORATIONS, isNewline, legacyCodeAt, NEWLINE, parseLine, sameStyle, serializeLine,
  splitLines, styleKey,
  type Cell, type Decoration, type TextStyle,
} from './model';

/** Cell offsets; `anchor` is where the selection started, `focus` where it ends. */
export interface Selection {
  anchor: number;
  focus: number;
}

export interface EditorState {
  cells: Cell[];
  selection: Selection;
  /** The style the next typed text takes, set by a shortcut or code with a collapsed caret. */
  pending?: TextStyle;
}

export type FormatOp =
  | { kind: 'toggle'; decoration: Decoration }
  | { kind: 'color'; color: string }
  | { kind: 'gradient'; stops: string[] }
  | { kind: 'clear' };

export const selectionStart = (selection: Selection) => Math.min(selection.anchor, selection.focus);
export const selectionEnd = (selection: Selection) => Math.max(selection.anchor, selection.focus);
export const collapsed = (selection: Selection) => selection.anchor === selection.focus;
const caret = (offset: number): Selection => ({ anchor: offset, focus: offset });

function clamp(state: EditorState): EditorState {
  const max = state.cells.length;
  const fit = (value: number) => Math.max(0, Math.min(max, value));
  return { ...state, selection: { anchor: fit(state.selection.anchor), focus: fit(state.selection.focus) } };
}

/** The style text typed at `offset` takes: the cell before it on the same line, else the one after. */
export function styleAt(cells: readonly Cell[], offset: number): TextStyle {
  const before = cells[offset - 1];
  if (before && !isNewline(before)) return cleanStyle(before.style);
  const after = cells[offset];
  if (after && !isNewline(after)) return cleanStyle(after.style);
  return {};
}

/** Replaces the selection with `inserted`; the caret lands after it. */
export function replaceSelection(state: EditorState, inserted: readonly Cell[]): EditorState {
  const start = selectionStart(state.selection);
  const end = selectionEnd(state.selection);
  const cells = [...state.cells.slice(0, start), ...inserted, ...state.cells.slice(end)];
  return { cells, selection: caret(start + inserted.length) };
}

/**
 * Cells for plain text typed or pasted at the caret. Legacy codes in it
 * become formatting (master §5.7); MiniMessage-looking text stays literal.
 */
export function cellsFromText(text: string, base: TextStyle, multiline: boolean): Cell[] {
  const normalized = text.replace(/\r\n?/g, '\n').replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, '');
  const lines = multiline ? normalized.split('\n') : [normalized.replace(/\n+/g, ' ')];
  const cells: Cell[] = [];
  lines.forEach((line, index) => {
    if (index > 0) cells.push(NEWLINE);
    let style = cleanStyle(base);
    for (let at = 0; at < line.length;) {
      const code = legacyCodeAt(line, at);
      if (code) {
        style = applyLegacyCode(style, code.code);
        at += code.length;
        continue;
      }
      const ch = String.fromCodePoint(line.codePointAt(at)!);
      cells.push({ kind: 'char', ch, style });
      at += ch.length;
    }
  });
  return cells;
}

/**
 * Typing. A legacy code completed by this keystroke (`&l`, `&#rrggbb`) is
 * removed and becomes the style of what is typed next, like Ctrl+B.
 */
export function insertText(state: EditorState, text: string, multiline: boolean): EditorState {
  const start = selectionStart(state.selection);
  const base = state.pending ?? styleAt(state.cells, start);
  const next = replaceSelection(state, cellsFromText(text, base, multiline));
  const offset = next.selection.focus;
  // the line's text up to the caret, atoms as a placeholder that is never a code
  let lineStart = offset;
  while (lineStart > 0 && !isNewline(next.cells[lineStart - 1])) lineStart--;
  const before = next.cells.slice(lineStart, offset).map((cell) => (cell.kind === 'char' ? cell.ch : '\u0000')).join('');
  for (const length of [8, 2]) {
    const at = before.length - length;
    const code = at >= 0 ? legacyCodeAt(before, at) : undefined;
    if (code && code.length === length) {
      const cells = [...next.cells.slice(0, offset - length), ...next.cells.slice(offset)];
      const style = applyLegacyCode(styleAt(next.cells, offset - length), code.code);
      return { cells, selection: caret(offset - length), pending: cleanStyle(style) };
    }
  }
  return next;
}

export type DeleteUnit = 'char' | 'word' | 'line';

function boundary(cells: readonly Cell[], from: number, direction: -1 | 1, unit: DeleteUnit): number {
  const at = (offset: number) => cells[direction < 0 ? offset - 1 : offset];
  const inside = (offset: number) => (direction < 0 ? offset > 0 : offset < cells.length);
  if (unit === 'char') return inside(from) ? from + direction : from;
  let offset = from;
  if (unit === 'line') {
    while (inside(offset) && !isNewline(at(offset))) offset += direction;
    return offset === from && inside(offset) ? offset + direction : offset;
  }
  const space = (cell: Cell | undefined) => cell?.kind === 'char' && /\s/.test(cell.ch) && cell.ch !== '\n';
  if (inside(offset) && isNewline(at(offset))) return offset + direction;
  while (inside(offset) && space(at(offset))) offset += direction;
  if (inside(offset) && at(offset)?.kind !== 'char') return offset + direction;
  while (inside(offset) && at(offset)?.kind === 'char' && !space(at(offset)) && !isNewline(at(offset))) offset += direction;
  return offset;
}

/** Backspace / Delete, by character, word or line; a selection is deleted whole. */
export function deleteContent(state: EditorState, direction: -1 | 1, unit: DeleteUnit = 'char'): EditorState {
  if (!collapsed(state.selection)) return replaceSelection(state, []);
  const from = state.selection.focus;
  const to = boundary(state.cells, from, direction, unit);
  if (to === from) return state;
  return replaceSelection({ ...state, selection: { anchor: Math.min(from, to), focus: Math.max(from, to) } }, []);
}

function restyle(style: TextStyle, op: FormatOp, add: boolean): TextStyle {
  switch (op.kind) {
    case 'toggle': {
      const next = { ...style };
      if (add) next[op.decoration] = true;
      else delete next[op.decoration];
      return cleanStyle(next);
    }
    case 'color': {
      const next: TextStyle = { ...style, color: op.color };
      delete next.gradient;
      return cleanStyle(next);
    }
    case 'gradient': {
      const next: TextStyle = { ...style, gradient: [...op.stops] };
      delete next.color;
      return cleanStyle(next);
    }
    case 'clear':
      // the effect span is authored in Files; clearing the look keeps it
      return style.effect ? { effect: style.effect } : {};
  }
}

/** Applies a format to the selection, or to the pending style when the caret is collapsed. */
export function format(state: EditorState, op: FormatOp): EditorState {
  const start = selectionStart(state.selection);
  const end = selectionEnd(state.selection);
  if (start === end) {
    const current = state.pending ?? styleAt(state.cells, start);
    const add = op.kind !== 'toggle' || !current[op.decoration];
    return { ...state, pending: restyle(current, op, add) };
  }
  const targets = state.cells.slice(start, end).filter((cell) => !isNewline(cell));
  const add = op.kind !== 'toggle' || !targets.every((cell) => cell.style[op.decoration]);
  const cells = state.cells.map((cell, index) =>
    index < start || index >= end || isNewline(cell) ? cell : { ...cell, style: restyle(cell.style, op, add) });
  return { cells, selection: state.selection };
}

/** What the toolbar shows as active for the current selection. */
export function activeStyle(state: EditorState): TextStyle {
  const start = selectionStart(state.selection);
  const end = selectionEnd(state.selection);
  if (start === end) return state.pending ?? styleAt(state.cells, start);
  const styles = state.cells.slice(start, end).filter((cell) => !isNewline(cell)).map((cell) => cleanStyle(cell.style));
  if (styles.length === 0) return {};
  const out: TextStyle = {};
  for (const decoration of DECORATIONS) if (styles.every((style) => style[decoration])) out[decoration] = true;
  const first = styles[0]!;
  if (first.color && styles.every((style) => style.color === first.color)) out.color = first.color;
  if (first.gradient && styles.every((style) => styleKey({ gradient: style.gradient }) === styleKey({ gradient: first.gradient }))) {
    out.gradient = first.gradient;
  }
  return out;
}

/** Inserts a glyph chip at the caret with the surrounding style. */
export function insertGlyph(state: EditorState, id: string): EditorState {
  const style = state.pending ?? styleAt(state.cells, selectionStart(state.selection));
  return replaceSelection(state, [{ kind: 'glyph', id, colorable: false, style }]);
}

/** The selection as a stored fragment (one line per line), for copy and rich paste. */
export function selectedFragment(state: EditorState): { plain: string; rich: string } {
  const cells = state.cells.slice(selectionStart(state.selection), selectionEnd(state.selection));
  const lines = splitLines(cells);
  return {
    plain: lines.map((line) => line.map((cell) => (cell.kind === 'char' ? cell.ch : '')).join('')).join('\n'),
    rich: lines.map(serializeLine).join('\n'),
  };
}

/** Cells for a rich fragment produced by `selectedFragment`. */
export function cellsFromFragment(rich: string, multiline: boolean): Cell[] {
  const lines = rich.split('\n');
  const used = multiline ? lines : [lines.join(' ')];
  return used.flatMap((line, index) => (index === 0 ? parseLine(line) : [NEWLINE, ...parseLine(line)]));
}

export function selectAll(state: EditorState): EditorState {
  return { ...state, selection: { anchor: 0, focus: state.cells.length } };
}

export function withSelection(state: EditorState, selection: Selection): EditorState {
  const moved = selection.anchor !== state.selection.anchor || selection.focus !== state.selection.focus;
  return clamp({ cells: state.cells, selection, pending: moved ? undefined : state.pending });
}

export function sameCells(a: readonly Cell[], b: readonly Cell[]): boolean {
  if (a.length !== b.length) return false;
  return a.every((cell, index) => {
    const other = b[index]!;
    if (cell.kind !== other.kind || !sameStyle(cell.style, other.style)) return false;
    if (cell.kind === 'char') return other.kind === 'char' && cell.ch === other.ch;
    if (cell.kind === 'glyph') return other.kind === 'glyph' && cell.id === other.id && cell.colorable === other.colorable;
    return other.kind === 'tag' && cell.raw === other.raw;
  });
}

/**
 * Undo and redo over whole states. Consecutive typing within a short pause
 * is one step, like a word processor; every format, delete or paste is its own.
 */
export class History {
  private undoStack: EditorState[] = [];
  private redoStack: EditorState[] = [];
  private lastKind = '';
  private lastTime = 0;

  constructor(private readonly limit = 200, private readonly coalesceMs = 800) {}

  record(before: EditorState, kind: string, now: number = Date.now()): void {
    const coalesce = kind === 'type' && this.lastKind === 'type' && now - this.lastTime < this.coalesceMs;
    this.lastKind = kind;
    this.lastTime = now;
    this.redoStack = [];
    if (coalesce) return;
    this.undoStack.push(before);
    if (this.undoStack.length > this.limit) this.undoStack.shift();
  }

  /** Breaks typing coalescing (caret moved, focus left). */
  seal(): void {
    this.lastKind = '';
  }

  undo(current: EditorState): EditorState | undefined {
    const previous = this.undoStack.pop();
    if (!previous) return;
    this.redoStack.push(current);
    this.lastKind = '';
    return previous;
  }

  redo(current: EditorState): EditorState | undefined {
    const next = this.redoStack.pop();
    if (!next) return;
    this.undoStack.push(current);
    this.lastKind = '';
    return next;
  }

  get canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0;
  }
}
