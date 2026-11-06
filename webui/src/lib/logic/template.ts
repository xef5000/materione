/**
 * The template field's brain (logic.md section 2.5, 2.6, 7.3, 31): what `${` and `%` complete to, and what is wrong
 * with a template before the compiler is even asked.
 *
 * A value is a literal, a `${expression}` or text with `%placeholders%`. Typing `${` offers the trigger's context
 * schema, the declared variables and the expression functions with their signatures; typing `%` offers the
 * PlaceholderAPI expansions the scope knows. The same scope drives the lint, so a name the field would not offer is
 * the name it flags (D5: one schema, checking and editing). The compiler has the last word (`logic.validate`); the
 * lint is the instant, local part of it.
 *
 * Pure of the DOM and of Svelte.
 */
import type { TemplateScope } from './scope';

export type CompletionKind = 'root' | 'path' | 'function' | 'placeholder';

export interface Completion {
  /** What the list shows. */
  label: string;
  /** What replaces the typed token. */
  insert: string;
  /** Text placed after `insert` and after the caret (a closing brace for a finished path). */
  tail: string;
  kind: CompletionKind;
  /** The value's type, or a function's return type. */
  detail: string;
  description: string;
}

export interface CompletionResult {
  /** The range of text the completion replaces. */
  from: number;
  to: number;
  items: Completion[];
}

export interface TemplateOptions {
  /** The whole text is an expression (a condition row), unless it holds a `${`. */
  bare?: boolean;
}

const WORD = /[A-Za-z0-9_.]/;
const LIMIT = 12;

/** Whether `index` falls inside a quoted string of `body` (a completion there would be noise). */
function inString(body: string, index: number): boolean {
  let quote: string | null = null;
  for (let i = 0; i < index; i++) {
    const c = body[i]!;
    if (quote) {
      if (c === '\\') i++;
      else if (c === quote) quote = null;
    } else if (c === "'" || c === '"') {
      quote = c;
    }
  }
  return quote !== null;
}

/** The start of the `${` body the caret is in, or -1. In `bare` mode a text with no `${` is one body. */
function bodyStart(text: string, caret: number, bare: boolean): number {
  const open = text.lastIndexOf('${', caret - 2 >= 0 ? caret - 2 : 0);
  if (open >= 0 && open + 2 <= caret) {
    const close = text.indexOf('}', open);
    if (close < 0 || close >= caret) return open + 2;
  }
  return bare && !text.includes('${') ? 0 : -1;
}

function signature(entry: { id: string; parameters: { id: string }[]; variadic: boolean }): string {
  const names = entry.parameters.map((param) => param.id);
  return `${entry.id}(${names.join(', ')}${entry.variadic ? ', ...' : ''})`;
}

/** What to offer at the caret, or null when nothing applies (outside a template, inside a string, nothing matches). */
export function completeAt(text: string, caret: number, scope: TemplateScope, options: TemplateOptions = {}): CompletionResult | null {
  const placeholder = placeholderAt(text, caret, scope);
  if (placeholder) return placeholder;
  const start = bodyStart(text, caret, options.bare ?? false);
  if (start < 0) return null;
  const body = text.slice(start, caret);
  if (inString(body, body.length)) return null;
  let tokenStart = caret;
  while (tokenStart > start && WORD.test(text[tokenStart - 1]!)) tokenStart--;
  const token = text.slice(tokenStart, caret);
  if (/^\d/.test(token)) return null;
  const dot = token.lastIndexOf('.');
  const prefix = token.slice(0, dot + 1);
  const partial = token.slice(dot + 1).toLowerCase();
  const closed = isClosed(text, caret, start === 0 && !text.includes('${'));
  const items: Completion[] = [];
  const seen = new Set<string>();
  for (const entry of scope.paths) {
    if (!entry.path.startsWith(prefix)) continue;
    const rest = entry.path.slice(prefix.length);
    const segment = rest.split('.')[0]!;
    if (segment === '*' || !segment.toLowerCase().startsWith(partial)) continue;
    const more = rest.includes('.');
    const insert = prefix + segment + (more ? '.' : '');
    if (seen.has(insert)) continue;
    seen.add(insert);
    items.push({
      label: more ? `${prefix}${segment}.` : insert,
      insert,
      tail: more || closed ? '' : '}',
      kind: more ? 'root' : 'path',
      detail: more ? '' : entry.type,
      description: more ? '' : entry.description,
    });
  }
  if (prefix === '') {
    for (const fn of scope.functions) {
      if (!fn.id.toLowerCase().startsWith(partial)) continue;
      items.push({ label: signature(fn), insert: `${fn.id}(`, tail: '', kind: 'function', detail: fn.returns, description: fn.description });
    }
  }
  items.sort((a, b) => rank(a) - rank(b));
  const shown = items.slice(0, LIMIT);
  if (shown.length === 0) return null;
  if (shown.length === 1 && shown[0]!.insert === token) return null;
  return { from: tokenStart, to: caret, items: shown };
}

function rank(item: Completion): number {
  return item.kind === 'root' ? 0 : item.kind === 'path' ? 1 : 2;
}

/** Whether a `}` already closes the open `${` after the caret (so a finished path needs no brace of its own). */
function isClosed(text: string, caret: number, bare: boolean): boolean {
  if (bare) return true;
  const close = text.indexOf('}', caret);
  if (close < 0) return false;
  const next = text.indexOf('${', caret);
  return next < 0 || close < next;
}

/** `%token` completion: the caret sits after an opening `%` and a run of name characters. */
function placeholderAt(text: string, caret: number, scope: TemplateScope): CompletionResult | null {
  let start = caret;
  while (start > 0 && /[A-Za-z0-9_]/.test(text[start - 1]!)) start--;
  if (start === 0 || text[start - 1] !== '%') return null;
  const percents = (text.slice(0, start - 1).match(/%/g) ?? []).length;
  if (percents % 2 === 1) return null; // that % closes an earlier placeholder
  const open = text.lastIndexOf('${', start);
  if (open >= 0 && (text.indexOf('}', open) < 0 || text.indexOf('}', open) >= start)) return null; // inside an expression
  const token = text.slice(start, caret).toLowerCase();
  const items: Completion[] = scope.placeholders
    .filter((entry) => entry.token.toLowerCase().startsWith(token))
    .slice(0, LIMIT)
    .map((entry) => ({
      label: `%${entry.token}%`,
      insert: entry.token,
      tail: text[caret] === '%' ? '' : '%',
      kind: 'placeholder' as const,
      detail: '',
      description: entry.description,
    }));
  return items.length > 0 ? { from: start, to: caret, items } : null;
}

/** The text and caret after accepting a completion: the caret lands after what was added, closing brace included. */
export function applyCompletion(text: string, result: CompletionResult, item: Completion): { text: string; caret: number } {
  const head = text.slice(0, result.from) + item.insert + item.tail;
  return { text: head + text.slice(result.to), caret: head.length };
}

// ------------------------------------------------------------------ lint

export interface TemplateProblem {
  severity: 'error' | 'warn';
  message: string;
  from: number;
  to: number;
}

const KEYWORDS = new Set(['true', 'false', 'null']);

/** Replaces string literals with spaces, so a name inside a quote is never read as a path or a call. */
function blankStrings(body: string): string {
  let out = '';
  let quote: string | null = null;
  for (let i = 0; i < body.length; i++) {
    const c = body[i]!;
    if (quote) {
      out += ' ';
      if (c === '\\') {
        out += ' ';
        i++;
      } else if (c === quote) quote = null;
    } else if (c === "'" || c === '"') {
      quote = c;
      out += ' ';
    } else out += c;
  }
  return out;
}

function lintBody(body: string, offset: number, scope: TemplateScope, problems: TemplateProblem[]): void {
  const blank = blankStrings(body);
  const names = /[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_*][A-Za-z0-9_*]*)*/g;
  let found: RegExpExecArray | null;
  while ((found = names.exec(blank))) {
    const name = found[0];
    const from = offset + found.index;
    const to = from + name.length;
    if (KEYWORDS.has(name)) continue;
    if (blank[found.index - 1] === '.') continue; // the tail of a call chain, not a root
    const call = /^\s*\(/.test(blank.slice(found.index + name.length));
    if (call) {
      if (scope.functions.length > 0 && !scope.functions.some((fn) => fn.id === name)) {
        problems.push({ severity: 'warn', message: `There is no function called '${name}'.`, from, to });
      }
      continue;
    }
    const root = name.split('.')[0]!;
    if (scope.roots.size > 0 && !scope.roots.has(root)) {
      problems.push({ severity: 'warn', message: `'${root}' is not available in this rule: its trigger does not provide it.`, from, to });
    }
  }
}

/** What is wrong with a template without asking the server: an unclosed `${`, an unknown root or function. */
export function lintTemplate(text: string, scope: TemplateScope, options: TemplateOptions = {}): TemplateProblem[] {
  const problems: TemplateProblem[] = [];
  if (!text.includes('${')) {
    if (options.bare) lintBody(text, 0, scope, problems);
    return problems;
  }
  let index = 0;
  while (index < text.length) {
    const open = text.indexOf('${', index);
    if (open < 0) break;
    const close = text.indexOf('}', open + 2);
    if (close < 0) {
      problems.push({ severity: 'error', message: "This '${' is never closed with '}'.", from: open, to: text.length });
      break;
    }
    lintBody(text.slice(open + 2, close), open + 2, scope, problems);
    index = close + 1;
  }
  return problems;
}
