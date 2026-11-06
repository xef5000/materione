/**
 * The Script tab's text and the document it stands for (logic.md section 7.1, 33). The tab shows the resource's
 * `open:`, `behavior:` and `flows:` as one YAML document, and these functions split the document's fields into that
 * text, merge an edited text back, find the line a step path is on, and turn a change of the two logic blocks into
 * the `LogicEdit`s the server takes (D6: no second write path).
 *
 * Lossless by construction. Only the three keys are shown, every other key of the resource is untouched; a section
 * the text leaves equal to the document is not written; and a value is carried whole, so unknown keys inside a rule,
 * a step or the `open:` block survive. What YAML cannot carry through a JSON value is a comment inside the three
 * blocks: the server rewrites `behavior:` and `flows:` whole (logic.md section 19, decision 6), so a comment there is
 * lost on the first write, exactly as it is for a canvas edit. A comment anywhere else in the file is kept.
 *
 * Pure of the DOM and of Svelte.
 */
import { LineCounter, parseDocument, stringify } from 'yaml';
import type { LogicEdit } from '../server';
import { isRecord, type Json, type LogicBlock } from '../tree';

export const SCRIPT_KEYS = ['open', 'behavior', 'flows'] as const;
export type ScriptKey = (typeof SCRIPT_KEYS)[number];

/** The three fields the Script tab edits, as the document holds them (an absent key is absent here too). */
export interface ScriptSections {
  open?: unknown;
  behavior?: unknown;
  flows?: unknown;
}

/** A problem with the text, at a line (1-based) and column; `line` is 1 when the text has no better place. */
export interface ScriptError {
  message: string;
  line: number;
  column: number;
}

export type ParsedScript = { ok: true; sections: ScriptSections } | { ok: false; error: ScriptError };

const clone = <T>(value: T): T => (value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T));

/** The three sections a document's fields hold (references, not copies). */
export function sectionsOf(rest: Readonly<Json>): ScriptSections {
  const out: ScriptSections = {};
  for (const key of SCRIPT_KEYS) if (Object.hasOwn(rest, key)) out[key] = rest[key];
  return out;
}

/** Whether two sections are the same, key order included (the file's shape). */
export function sameSections(a: ScriptSections, b: ScriptSections): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** A stable key for the sections, to see whether the document changed under the text. */
export function sectionsKey(rest: Readonly<Json>): string {
  return JSON.stringify(sectionsOf(rest)) ?? '';
}

/** The YAML text of the sections. An empty set of sections is an empty text. */
export function formatScript(sections: ScriptSections): string {
  const entries = SCRIPT_KEYS.flatMap((key) => (Object.hasOwn(sections, key) ? [[key, sections[key]] as const] : []));
  if (entries.length === 0) return '';
  return stringify(Object.fromEntries(entries), { lineWidth: 0, indent: 2 });
}

/** The first line a key starts on, for an error about a whole section. */
function keyLine(text: string, key: string): number {
  const lines = text.split(/\r\n|\r|\n/);
  const at = lines.findIndex((line) => line.startsWith(`${key}:`));
  return at >= 0 ? at + 1 : 1;
}

/**
 * Reads the text back. A syntax error, a key the tab does not hold and a section of the wrong shape are all errors
 * with a line, and none of them returns sections, so a bad draft can never reach the document.
 */
export function parseScript(text: string): ParsedScript {
  const counter = new LineCounter();
  const doc = parseDocument(text, { lineCounter: counter, prettyErrors: false, strict: true, uniqueKeys: true });
  const failure = doc.errors[0];
  if (failure) {
    const at = counter.linePos(failure.pos[0]);
    return { ok: false, error: { message: failure.message.split('\n')[0]!, line: at.line, column: at.col } };
  }
  const value = doc.toJS({ maxAliasCount: 100 }) as unknown;
  if (value === null || value === undefined) return { ok: true, sections: {} };
  if (!isRecord(value)) return { ok: false, error: { message: 'The script is a mapping of open:, behavior: and flows:.', line: 1, column: 1 } };
  for (const key of Object.keys(value)) {
    if (!(SCRIPT_KEYS as readonly string[]).includes(key)) {
      return {
        ok: false,
        error: { message: `'${key}' is not edited here: this tab holds ${SCRIPT_KEYS.join(', ')} only. The rest of the file is untouched.`, line: keyLine(text, key), column: 1 },
      };
    }
  }
  // in the tab's fixed order, whatever order the text wrote them in; a key with nothing under it is no section
  const sections: ScriptSections = {};
  for (const key of SCRIPT_KEYS) if (value[key] !== null && value[key] !== undefined) sections[key] = value[key];
  const shape = shapeProblem(sections);
  if (shape) return { ok: false, error: { message: shape.message, line: keyLine(text, shape.key), column: 1 } };
  return { ok: true, sections };
}

function shapeProblem(sections: ScriptSections): { key: ScriptKey; message: string } | null {
  if (sections.open !== undefined && !isRecord(sections.open)) return { key: 'open', message: 'open: is a mapping (commands, args, permission, requirement, deny).' };
  if (sections.behavior !== undefined) {
    if (!Array.isArray(sections.behavior)) return { key: 'behavior', message: 'behavior: is a list of rules.' };
    const bad = sections.behavior.findIndex((rule) => !isRecord(rule));
    if (bad >= 0) return { key: 'behavior', message: `behavior: entry ${bad + 1} is not a rule (a rule is a mapping with an on: trigger).` };
    const open = sections.behavior.findIndex((rule) => isRecord(rule) && rule.do !== undefined && !Array.isArray(rule.do));
    if (open >= 0) return { key: 'behavior', message: `behavior: rule ${open + 1} has a do: that is not a list of steps.` };
  }
  if (sections.flows !== undefined) {
    if (!isRecord(sections.flows)) return { key: 'flows', message: 'flows: is a mapping of named flows, each a list of steps.' };
    const bad = Object.entries(sections.flows).find(([, steps]) => !Array.isArray(steps));
    if (bad) return { key: 'flows', message: `flows: '${bad[0]}' is not a list of steps.` };
  }
  return null;
}

/**
 * The document's fields with the sections replaced. Keys the sections lack are removed, the others keep their place,
 * and a key the document did not have is added at the end, so the rest of the resource keeps its shape.
 */
export function mergeSections(rest: Readonly<Json>, sections: ScriptSections): Json {
  const out: Json = {};
  for (const [key, value] of Object.entries(rest)) {
    if (!(SCRIPT_KEYS as readonly string[]).includes(key)) out[key] = value;
    else if (Object.hasOwn(sections, key)) out[key] = clone(sections[key as ScriptKey]);
  }
  for (const key of SCRIPT_KEYS) if (Object.hasOwn(sections, key) && !Object.hasOwn(out, key)) out[key] = clone(sections[key]);
  return out;
}

/** The logic block a set of sections holds. */
export function blockOfSections(sections: ScriptSections): LogicBlock {
  const block: LogicBlock = {};
  if (Array.isArray(sections.behavior)) block.behavior = sections.behavior;
  if (isRecord(sections.flows)) block.flows = sections.flows as Record<string, unknown[]>;
  return block;
}

// ------------------------------------------------------------------ lines

/**
 * The 1-based line a step path is on in the text, or the deepest ancestor's line when the path leads nowhere
 * (the compiler may name a place the text has only as a whole). Null when the text has no such section at all.
 */
export function lineOfPath(text: string, path: readonly string[]): number | null {
  const counter = new LineCounter();
  const doc = parseDocument(text, { lineCounter: counter, prettyErrors: false });
  for (let length = path.length; length > 0; length--) {
    const node = doc.getIn(path.slice(0, length), true) as { range?: [number, number, number] } | undefined;
    if (node?.range) return counter.linePos(node.range[0]).line;
  }
  return null;
}

// ------------------------------------------------------------------ edits

const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);

/**
 * The `LogicEdit`s that turn one logic block into another: the rules between the common head and the common tail are
 * removed and the new ones added (so a rule that did not change is never rewritten), and the flows after the common
 * head are removed and added again in order (so the order of `flows:` is exactly the text's). These are the edits the
 * server replays on save, so what the author sees is what the pipeline writes.
 */
export function planBlockChange(before: LogicBlock, after: LogicBlock): LogicEdit[] {
  const edits: LogicEdit[] = [];
  const from = before.behavior ?? [];
  const to = after.behavior ?? [];
  let head = 0;
  while (head < from.length && head < to.length && same(from[head], to[head])) head++;
  let tail = 0;
  while (tail < from.length - head && tail < to.length - head && same(from[from.length - 1 - tail], to[to.length - 1 - tail])) tail++;
  const removed = from.length - tail - head;
  if (removed > 0) edits.push({ op: 'removeRules', indices: Array.from({ length: removed }, (_, k) => head + k) });
  for (let k = 0; k < to.length - tail - head; k++) {
    edits.push({ op: 'addRule', index: head + k, rule: clone(to[head + k]) as Json });
  }

  const wasFlows = Object.entries(before.flows ?? {});
  const isFlows = Object.entries(after.flows ?? {});
  let kept = 0;
  while (kept < wasFlows.length && kept < isFlows.length && wasFlows[kept]![0] === isFlows[kept]![0] && same(wasFlows[kept]![1], isFlows[kept]![1])) kept++;
  for (const [id] of wasFlows.slice(kept)) edits.push({ op: 'removeFlow', id });
  for (const [id, steps] of isFlows.slice(kept)) edits.push({ op: 'addFlow', id, steps: clone(steps) });
  return edits;
}
