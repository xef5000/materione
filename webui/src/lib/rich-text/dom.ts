/**
 * Draws a rich text document as DOM, and maps between DOM positions and cell
 * offsets. The editor and the read-only previews share it, so what the author
 * edits is drawn exactly like the preview. Text goes in through text nodes,
 * never markup, so stored text cannot inject HTML.
 */
import { gradientColor, hexOf } from './colors';
import { cleanStyle, splitLines, type Cell, type TextStyle } from './model';

export interface RenderOptions {
  /** A data URL for a glyph's texture, when one is loaded. */
  glyphImage?: (id: string) => string | undefined;
}

const codePoints = (text: string) => [...text].length;

/** `<shift:4>` → `shift 4`, `</rainbow>` → `/rainbow`: a short label for a kept tag. */
export function tagLabel(raw: string): string {
  return raw.replace(/^<|>$/g, '').replace(/:/g, ' ').trim() || raw;
}

function gradientKey(style: TextStyle): string {
  return style.gradient ? JSON.stringify([style.effect ?? '', style.gradient]) : '';
}

/** The color each cell is drawn in: its own, a gradient position, or the default (undefined). */
export function cellColors(line: readonly Cell[]): (string | undefined)[] {
  const colors: (string | undefined)[] = new Array(line.length).fill(undefined);
  for (let at = 0; at < line.length;) {
    const style = line[at]!.style;
    const key = gradientKey(style);
    if (!key) {
      colors[at] = style.color ? hexOf(style.color) : undefined;
      at++;
      continue;
    }
    let end = at;
    while (end < line.length && gradientKey(line[end]!.style) === key) end++;
    for (let index = at; index < end; index++) colors[index] = gradientColor(style.gradient!, index - at, end - at);
    at = end;
  }
  return colors;
}

function classes(style: TextStyle): string {
  const out = ['mc-run'];
  if (style.bold) out.push('mc-b');
  if (style.italic) out.push('mc-i');
  if (style.underline) out.push('mc-u');
  if (style.strikethrough) out.push('mc-s');
  if (style.obfuscated) out.push('mc-obf');
  if (style.effect) out.push('mc-effect');
  return out.join(' ');
}

function chip(cell: Exclude<Cell, { kind: 'char' }>, start: number, color: string | undefined, options: RenderOptions): HTMLElement {
  const element = document.createElement('span');
  element.contentEditable = 'false';
  element.dataset.start = String(start);
  element.dataset.length = '1';
  if (cell.kind === 'glyph') {
    element.className = `mc-chip mc-glyph${cell.colorable ? ' mc-colorable' : ''}`;
    element.dataset.glyph = cell.id;
    element.title = `Glyph ${cell.id}`;
    const image = options.glyphImage?.(cell.id);
    if (image) {
      const img = document.createElement('img');
      img.src = image;
      img.alt = cell.id;
      img.draggable = false;
      element.append(img);
    } else {
      element.textContent = cell.id;
    }
    if (cell.colorable && color) element.style.color = color;
  } else {
    element.className = 'mc-chip mc-tag';
    element.title = cell.raw;
    element.textContent = tagLabel(cell.raw);
  }
  return element;
}

/** Replaces `root`'s children with the document: one block per line, runs and chips inside. */
export function renderDocument(root: HTMLElement, cells: readonly Cell[], options: RenderOptions = {}): void {
  const fragment = document.createDocumentFragment();
  let offset = 0;
  for (const line of splitLines(cells)) {
    const block = document.createElement('div');
    block.className = 'mc-line';
    block.dataset.start = String(offset);
    block.dataset.length = String(line.length);
    const colors = cellColors(line);
    let run: HTMLSpanElement | undefined;
    let runKey = '';
    line.forEach((cell, index) => {
      const start = offset + index;
      const color = colors[index];
      if (cell.kind !== 'char') {
        run = undefined;
        block.append(chip(cell, start, color, options));
        return;
      }
      const style = cleanStyle(cell.style);
      // gradient characters each carry their own color, so they never share a run
      const key = style.gradient ? '' : JSON.stringify([style, color]);
      if (run && key && key === runKey) {
        run.firstChild!.textContent += cell.ch;
        run.dataset.length = String(Number(run.dataset.length) + 1);
        return;
      }
      run = document.createElement('span');
      run.className = classes(style);
      if (color) run.style.color = color;
      if (style.effect) run.title = `Shader effect ${style.effect}: animates in game`;
      run.dataset.start = String(start);
      run.dataset.length = '1';
      run.append(document.createTextNode(cell.ch));
      runKey = key;
      block.append(run);
    });
    if (line.length === 0) block.append(document.createElement('br'));
    fragment.append(block);
    offset += line.length + 1;
  }
  root.replaceChildren(fragment);
}

const startOf = (element: Element) => Number((element as HTMLElement).dataset.start ?? 0);
const lengthOf = (element: Element) => Number((element as HTMLElement).dataset.length ?? 0);

/** The cell offset of a DOM position inside `root`, or null when it is outside. */
export function offsetFromDom(root: HTMLElement, node: Node | null, offset: number): number | null {
  if (!node || !root.contains(node)) return null;
  const lines = [...root.children].filter((child) => child.classList.contains('mc-line'));
  const total = lines.length === 0 ? 0 : startOf(lines.at(-1)!) + lengthOf(lines.at(-1)!);
  if (node === root) {
    const line = lines[offset];
    return line ? startOf(line) : total;
  }
  const element = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
  if (!element) return null;
  const chipElement = element.closest('.mc-chip');
  if (chipElement && root.contains(chipElement)) {
    return startOf(chipElement) + (node === chipElement && offset > 0 ? 1 : 0);
  }
  if (node.nodeType === Node.TEXT_NODE) {
    const run = element.closest('.mc-run');
    if (!run) return null;
    return startOf(run) + codePoints((node as Text).data.slice(0, offset));
  }
  if (element.classList.contains('mc-run')) return startOf(element) + (offset > 0 ? lengthOf(element) : 0);
  const line = element.closest('.mc-line');
  if (!line) return total;
  if (element === line) {
    const child = line.childNodes[offset];
    if (child instanceof HTMLElement && child.dataset.start !== undefined) return startOf(child);
    return offset === 0 ? startOf(line) : startOf(line) + lengthOf(line);
  }
  return startOf(line);
}

/** The DOM position for a cell offset: inside a text node where possible. */
export function domFromOffset(root: HTMLElement, offset: number): { node: Node; offset: number } {
  const lines = [...root.children].filter((child) => child.classList.contains('mc-line')) as HTMLElement[];
  const line = lines.find((candidate) => offset <= startOf(candidate) + lengthOf(candidate)) ?? lines.at(-1);
  if (!line) return { node: root, offset: 0 };
  const children = [...line.children] as HTMLElement[];
  for (const [index, child] of children.entries()) {
    if (child.classList.contains('mc-run')) {
      const start = startOf(child);
      if (offset >= start && offset <= start + lengthOf(child)) {
        const text = child.firstChild as Text;
        const units = [...text.data].slice(0, offset - start).join('').length;
        return { node: text, offset: units };
      }
    } else if (child.classList.contains('mc-chip') && offset === startOf(child)) {
      return { node: line, offset: index };
    }
  }
  const content = children.filter((child) => child.tagName !== 'BR').length;
  return { node: line, offset: content };
}
