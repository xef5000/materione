/**
 * Conditions as the condition stack edits them (logic.md section 2.3, 22): an expression string, a boolean, a
 * registered predicate (`has_money: 250`), a list (which is `all`), or a composite (`all`, `any`, `not`,
 * `at_least`). The stack edits the authored value itself, by path, and every operation returns the whole new value:
 * there is no second model, and a piece the editor does not understand stays exactly as the file has it.
 *
 * The grammar is the compiler's (`ConditionCompiler`): a mapping has exactly one key, which is a composite or a
 * predicate; a list is `all`; `at_least` is `{count, of: [...]}`. The composite names are reserved, so a predicate is
 * never one of them.
 *
 * Pure of the DOM and of Svelte.
 */
import { isRecord } from './tree';
import { defaultConditionPayload, type ConditionEntry, type Vocabulary } from './vocabulary';

/** A path into a condition: a mapping key or a list index. */
export type CPath = readonly (string | number)[];

export const COMPOSITES = ['all', 'any', 'not', 'at_least'] as const;
const RESERVED = new Set<string>(COMPOSITES);

export type CondShape =
  | { t: 'expr'; text: string }
  | { t: 'bool'; value: boolean }
  | { t: 'group'; op: 'all' | 'any'; form: 'list' | 'map'; items: unknown[] }
  | { t: 'not'; child: unknown }
  | { t: 'atLeast'; count: number; items: unknown[] }
  | { t: 'pred'; id: string; payload: unknown }
  | { t: 'raw'; value: unknown };

/** What a piece of a condition is. Anything that is not a condition the compiler accepts is `raw`, and is kept. */
export function shapeOf(raw: unknown): CondShape {
  if (typeof raw === 'string') return { t: 'expr', text: raw };
  if (typeof raw === 'boolean') return { t: 'bool', value: raw };
  if (Array.isArray(raw)) return { t: 'group', op: 'all', form: 'list', items: raw };
  if (isRecord(raw)) {
    const keys = Object.keys(raw);
    if (keys.length !== 1) return { t: 'raw', value: raw };
    const key = keys[0]!;
    const value = raw[key];
    if ((key === 'all' || key === 'any') && Array.isArray(value)) return { t: 'group', op: key, form: 'map', items: value };
    if (key === 'not') return { t: 'not', child: value };
    if (key === 'at_least') {
      if (isRecord(value) && typeof value.count === 'number' && Array.isArray(value.of)) {
        return { t: 'atLeast', count: value.count, items: value.of };
      }
      return { t: 'raw', value: raw };
    }
    if (RESERVED.has(key)) return { t: 'raw', value: raw };
    return { t: 'pred', id: key, payload: value };
  }
  return { t: 'raw', value: raw };
}

/** One node of the condition tree as the stack draws it. */
export interface CondView {
  path: CPath;
  shape: CondShape;
  children: CondView[];
}

/** The tree of a condition value, each node with the path that reaches it. An empty value has no tree. */
export function viewOf(raw: unknown, path: CPath = []): CondView {
  const shape = shapeOf(raw);
  let children: CondView[] = [];
  if (shape.t === 'group') {
    const base = shape.form === 'list' ? path : [...path, shape.op];
    children = shape.items.map((item, index) => viewOf(item, [...base, index]));
  } else if (shape.t === 'not') {
    children = [viewOf(shape.child, [...path, 'not'])];
  } else if (shape.t === 'atLeast') {
    children = shape.items.map((item, index) => viewOf(item, [...path, 'at_least', 'of', index]));
  }
  return { path, shape, children };
}

// ------------------------------------------------------------------ immutable path edits

export function getAt(root: unknown, path: CPath): unknown {
  let current = root;
  for (const segment of path) {
    if (Array.isArray(current) && typeof segment === 'number') current = current[segment];
    else if (isRecord(current) && typeof segment === 'string') current = current[segment];
    else return undefined;
  }
  return current;
}

/** The value with the piece at `path` replaced (every container on the way copied; key order kept). */
export function setAt(root: unknown, path: CPath, value: unknown): unknown {
  if (path.length === 0) return value;
  const [head, ...rest] = path as [string | number, ...(string | number)[]];
  if (Array.isArray(root) && typeof head === 'number') {
    const next = [...root];
    next[head] = setAt(root[head], rest, value);
    return next;
  }
  if (isRecord(root) && typeof head === 'string') {
    return { ...root, [head]: setAt(root[head], rest, value) };
  }
  return root;
}

/** The container (list) a node sits in and its index there, or null for the root or an only child (a `not`'s). */
function slotOf(path: CPath): { list: CPath; index: number } | null {
  const last = path[path.length - 1];
  if (typeof last !== 'number') return null;
  return { list: path.slice(0, -1), index: last };
}

function listAt(root: unknown, path: CPath): unknown[] | null {
  const found = getAt(root, path);
  return Array.isArray(found) ? found : null;
}

/**
 * The value without the node at `path`. The root becomes `undefined` (no condition); a child leaves its group;
 * the only child of a `not` takes the `not` with it.
 */
export function removeAt(root: unknown, path: CPath): unknown {
  if (path.length === 0) return undefined;
  const slot = slotOf(path);
  if (!slot) return removeAt(root, path.slice(0, -1)); // a not's child: the not goes
  const list = listAt(root, slot.list);
  if (!list) return root;
  const next = list.filter((_, index) => index !== slot.index);
  return setAt(root, slot.list, next);
}

/** The value with `node` added to the end of the group (or `at_least` list) at `path`. */
export function addChild(root: unknown, path: CPath, node: unknown): unknown {
  const shape = shapeOf(getAt(root, path));
  if (shape.t === 'group') {
    const list = shape.form === 'list' ? path : [...path, shape.op];
    return setAt(root, list, [...shape.items, node]);
  }
  if (shape.t === 'atLeast') return setAt(root, [...path, 'at_least', 'of'], [...shape.items, node]);
  return root;
}

/** The value with the node at `path` moved by `delta` places within its group; unchanged when it cannot move. */
export function moveBy(root: unknown, path: CPath, delta: number): unknown {
  const slot = slotOf(path);
  const list = slot && listAt(root, slot.list);
  if (!slot || !list) return root;
  const to = slot.index + delta;
  if (to < 0 || to >= list.length) return root;
  const next = [...list];
  const [moving] = next.splice(slot.index, 1);
  next.splice(to, 0, moving);
  return setAt(root, slot.list, next);
}

/** The value with the node at `path` wrapped in a composite (it becomes the composite's first child). */
export function wrap(root: unknown, path: CPath, kind: 'all' | 'any' | 'not' | 'at_least'): unknown {
  const node = getAt(root, path);
  const wrapped =
    kind === 'not' ? { not: node } : kind === 'at_least' ? { at_least: { count: 1, of: [node] } } : { [kind]: [node] };
  return setAt(root, path, wrapped);
}

/** The value with a group at `path` switched between `all` and `any` (a list becomes `{any: [...]}`); its children stay. */
export function setGroupOp(root: unknown, path: CPath, op: 'all' | 'any'): unknown {
  const shape = shapeOf(getAt(root, path));
  if (shape.t !== 'group') return root;
  if (shape.form === 'list' && op === 'all') return root;
  return setAt(root, path, { [op]: shape.items });
}

/** The value with an `at_least` at `path` requiring `count` of its conditions. */
export function setCount(root: unknown, path: CPath, count: number): unknown {
  const current = getAt(root, path);
  if (!isRecord(current) || !isRecord(current.at_least)) return root;
  return setAt(root, path, { ...current, at_least: { ...current.at_least, count } });
}

// ------------------------------------------------------------------ the kinds a row can be

export type RowKind = 'predicate' | 'expression' | 'bool' | 'all' | 'any' | 'not' | 'at_least';

export function rowKindOf(shape: CondShape): RowKind | 'raw' {
  switch (shape.t) {
    case 'pred':
      return 'predicate';
    case 'expr':
      return 'expression';
    case 'bool':
      return 'bool';
    case 'group':
      return shape.op;
    case 'not':
      return 'not';
    case 'atLeast':
      return 'at_least';
    default:
      return 'raw';
  }
}

/** A new condition of a kind. A predicate starts with its entry's starting payload; a composite starts empty. */
export function freshCondition(kind: RowKind, vocabulary: Vocabulary | null, predicate?: string): unknown {
  switch (kind) {
    case 'expression':
      return '${true}';
    case 'bool':
      return true;
    case 'all':
    case 'any':
      return { [kind]: [] };
    case 'not':
      return { not: '${true}' };
    case 'at_least':
      return { at_least: { count: 1, of: [] } };
    case 'predicate': {
      const entry = (predicate ? vocabulary?.conditions.get(predicate) : undefined) ?? [...(vocabulary?.conditions.values() ?? [])].find((c) => c.available);
      if (!entry) return { has_permission: '' };
      return { [entry.id]: defaultConditionPayload(entry) };
    }
  }
}

/**
 * The value after switching the node at `path` to another kind. Switching between `all` and `any` keeps the
 * children; any other switch starts the new kind fresh (the old content is not guessable).
 */
export function convert(root: unknown, path: CPath, kind: RowKind, vocabulary: Vocabulary | null, predicate?: string): unknown {
  const shape = shapeOf(getAt(root, path));
  if (shape.t === 'group' && (kind === 'all' || kind === 'any')) return setGroupOp(root, path, kind);
  return setAt(root, path, freshCondition(kind, vocabulary, predicate));
}

/** The predicate entry a node names, when the vocabulary knows it. */
export function predicateEntry(vocabulary: Vocabulary | null, id: string): ConditionEntry | undefined {
  return vocabulary?.conditions.get(id);
}

/**
 * Adds a condition to a stack: the first becomes the value, a second turns a lone condition into a list (which is
 * `all`), later ones join that list or the root `all`.
 */
export function pushRoot(root: unknown, node: unknown): unknown {
  if (root === undefined || root === null || root === '') return node;
  const shape = shapeOf(root);
  if (shape.t === 'group' && shape.op === 'all') return addChild(root, [], node);
  return [root, node]; // anything else is one condition of the new all, so the meaning of the old one is kept
}
