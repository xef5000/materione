/**
 * The authored logic of one resource and the structural edits on it (logic.md §2, §8, issue 223).
 *
 * The file is the source of truth (D1): a resource's logic is the `behavior:` list of rules and the
 * `flows:` map of named step lists, plain JSON values exactly as the file parses. The canvas draws a
 * projection of this tree and never keeps a second copy; every change is a `LogicEdit`, the same closed
 * set `logic.edit` takes, applied here to the working copy so the canvas shows the result at once and
 * the save replays the very same edits on the server (D6: one pipeline).
 *
 * `applyEdit` mirrors the server's editor (`LogicEditor`, logic.md §19): paths are step paths
 * (`behavior.2.do.0.then.1`, `flows.purchase.1`), an insertion path names the index it inserts at, and a
 * move is a removal followed by an insertion whose target is read against the tree without the step.
 * It checks the shape (paths, lists, one kind per step) but not the registries: whether an action id
 * exists is the server's call, made on save.
 *
 * Pure of the DOM and of Svelte.
 */
import type { LogicEdit } from './server';

export type Json = Record<string, unknown>;

/** The two keys of a resource that hold logic. Every other key of the resource is not this module's. */
export interface LogicBlock {
  behavior?: unknown[];
  flows?: Record<string, unknown[]>;
}

/** The control kinds (logic.md §2.2); every other step key is an action id. */
export const CONTROL_KINDS = ['if', 'require', 'choose', 'wait', 'stop', 'call'] as const;
const CONTROL = new Set<string>(CONTROL_KINDS);
/** Keys that belong to a step's shape rather than naming an action. */
const SHAPE = new Set(['then', 'else', 'require', 'choose', 'with', 'if', 'wait', 'stop', 'call', 'chance', 'delay', 'node']);
/** The keys whose lists hold steps. */
const STEP_LIST_KEYS = new Set(['do', 'then', 'else', 'require']);
/** The branch keys of a step. */
export const BRANCH_KEYS = ['then', 'else', 'require', 'choose'] as const;

/** A refused edit: the step path it is about and a one-line reason (the server's refusals, mirrored). */
export class LogicEditRefused extends Error {
  constructor(
    readonly path: string,
    message: string,
  ) {
    super(message);
    this.name = 'LogicEditRefused';
  }
}

export function isRecord(value: unknown): value is Json {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function clone<T>(value: T): T {
  return value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T);
}

/** The logic a document's top-level fields hold (references, not copies). */
export function blockOf(rest: Readonly<Json>): LogicBlock {
  const block: LogicBlock = {};
  if (Array.isArray(rest.behavior)) block.behavior = rest.behavior;
  if (isRecord(rest.flows)) block.flows = rest.flows as Record<string, unknown[]>;
  return block;
}

/** Why the logic cannot be drawn faithfully (a `behavior:` that is not a list, a flow that is not a list); empty when it can. */
export function blockProblems(rest: Readonly<Json>): string[] {
  const problems: string[] = [];
  if (rest.behavior !== undefined && !Array.isArray(rest.behavior)) problems.push('behavior: is not a list of rules');
  if (rest.flows !== undefined && !isRecord(rest.flows)) problems.push('flows: is not a mapping of named flows');
  if (Array.isArray(rest.behavior)) {
    rest.behavior.forEach((rule, index) => {
      if (!isRecord(rule)) problems.push(`behavior.${index} is not a rule`);
      else if (rule.do !== undefined && !Array.isArray(rule.do)) problems.push(`behavior.${index}.do is not a list of steps`);
    });
  }
  if (isRecord(rest.flows)) {
    for (const [id, steps] of Object.entries(rest.flows)) {
      if (!Array.isArray(steps)) problems.push(`flows.${id} is not a list of steps`);
    }
  }
  return problems;
}

// ------------------------------------------------------------------ steps as authored

/** A step's kind as the canvas names it: a control kind, else the action id; `''` for a step that names none. */
export function stepKind(step: unknown): string {
  if (typeof step === 'string') return step;
  if (!isRecord(step)) return '';
  for (const key of Object.keys(step)) if (CONTROL.has(key)) return key;
  for (const key of Object.keys(step)) if (!SHAPE.has(key)) return key;
  return '';
}

export function isControl(kind: string): boolean {
  return CONTROL.has(kind);
}

/** A step's own value (the kind's key), as authored: `undefined` for a bare name. */
export function stepValue(step: unknown): unknown {
  if (!isRecord(step)) return undefined;
  return step[stepKind(step)];
}

/** `{x, y}` from a `node:` hint, or null when the step has none (or a malformed one). */
export function positionOf(raw: unknown): { x: number; y: number } | null {
  if (!isRecord(raw)) return null;
  const node = raw.node;
  if (isRecord(node) && typeof node.x === 'number' && typeof node.y === 'number' && Number.isFinite(node.x) && Number.isFinite(node.y)) {
    return { x: node.x, y: node.y };
  }
  return null;
}

// ------------------------------------------------------------------ paths

export function segments(path: string): string[] {
  if (!path || !path.trim()) throw new LogicEditRefused(path, 'a step path is required (behavior.0.do.0, flows.name.0, ...)');
  const parts = path.trim().split('.');
  if (parts.some((part) => part === '')) throw new LogicEditRefused(path, `the step path '${path}' has an empty segment`);
  return parts;
}

function child(node: unknown, segment: string, path: string): unknown {
  if (Array.isArray(node)) {
    const index = indexOf(segment, path);
    if (index >= node.length) {
      throw new LogicEditRefused(path, `index ${segment} is past the end (the list has ${node.length} entries)`);
    }
    return node[index];
  }
  if (isRecord(node)) {
    if (!Object.hasOwn(node, segment)) throw new LogicEditRefused(path, `there is no '${segment}' at this point of the path`);
    return node[segment];
  }
  throw new LogicEditRefused(path, `'${segment}' leads nowhere: the node is neither a list nor a mapping`);
}

function indexOf(segment: string, path: string): number {
  if (!/^\d+$/.test(segment)) throw new LogicEditRefused(path, `'${segment}' is not a list index`);
  return Number(segment);
}

export function resolve(root: Json, parts: readonly string[], path: string): unknown {
  let current: unknown = root;
  for (const part of parts) current = child(current, part, path);
  return current;
}

function listAt(root: Json, parts: readonly string[], path: string): unknown[] {
  const node = resolve(root, parts, path);
  if (!Array.isArray(node)) throw new LogicEditRefused(path, `'${parts.join('.')}' is not a list`);
  return node;
}

/** Whether the list at `parent` holds steps: a flow body, or a `do`, `then`, `else` or `require` list. */
export function holdsSteps(parent: readonly string[]): boolean {
  if (parent.length === 2 && parent[0] === 'flows') return true;
  return parent.length > 0 && STEP_LIST_KEYS.has(parent[parent.length - 1]!);
}

interface Slot {
  list: unknown[];
  index: number;
}

function insertion(root: Json, path: string, allowEnd: boolean): Slot {
  const parts = segments(path);
  const parent = parts.slice(0, -1);
  const index = indexOf(parts[parts.length - 1]!, path);
  if (parent.length === 0 || (parent.length === 1 && parent[0] === 'behavior')) {
    throw new LogicEditRefused(path, 'rules are added and moved with the rule edits, not as steps');
  }
  if (!holdsSteps(parent)) throw new LogicEditRefused(path, `'${parent.join('.')}' does not hold steps`);
  const list = listAt(root, parent, path);
  const limit = allowEnd ? list.length : list.length - 1;
  if (index > limit) throw new LogicEditRefused(path, `step index ${index} is outside 0..${limit}`);
  return { list, index };
}

function existing(root: Json, path: string): Slot {
  const parts = segments(path);
  const parent = parts.slice(0, -1);
  if (parent.length === 0 || (parent.length === 1 && parent[0] === 'behavior')) {
    throw new LogicEditRefused(path, `'${path}' is a rule, not a step`);
  }
  if (!holdsSteps(parent)) throw new LogicEditRefused(path, `'${parent.join('.')}' does not hold steps`);
  const index = indexOf(parts[parts.length - 1]!, path);
  const list = listAt(root, parent, path);
  if (index >= list.length) throw new LogicEditRefused(path, `no step at index ${index} (the list has ${list.length})`);
  return { list, index };
}

/** The step (or rule) a path names, or undefined when it leads nowhere. */
export function nodeAt(block: LogicBlock, path: string): unknown {
  try {
    return resolve(block as Json, segments(path), path);
  } catch {
    return undefined;
  }
}

// ------------------------------------------------------------------ applying edits

function checkStep(step: unknown, path: string): void {
  if (typeof step === 'string') {
    if (!step.trim()) throw new LogicEditRefused(path, 'a bare step name must not be blank');
    return;
  }
  if (!isRecord(step)) throw new LogicEditRefused(path, 'a step is a mapping or a bare name');
  const control = Object.keys(step).filter((key) => CONTROL.has(key));
  if (control.length > 1) throw new LogicEditRefused(path, `a step names one kind, not [${control.join(', ')}]`);
  if (control.length === 1) {
    const kind = control[0]!;
    if (kind === 'if' && !Array.isArray(step.then)) throw new LogicEditRefused(path, 'an if step needs a then: list of steps');
    if (kind === 'require' && !Array.isArray(step.require)) throw new LogicEditRefused(path, 'a require step needs a require: list of steps');
    if (kind === 'choose' && !Array.isArray(step.choose)) throw new LogicEditRefused(path, 'a choose step needs a choose: list of cases');
    return;
  }
  const actions = Object.keys(step).filter((key) => !SHAPE.has(key));
  if (actions.length === 0) throw new LogicEditRefused(path, 'the step names no kind: give it an action, or if, require, choose, wait, stop or call');
  if (actions.length > 1) throw new LogicEditRefused(path, `a step names one action, not [${actions.join(', ')}]`);
}

const FLOW_ID = /^[A-Za-z0-9_.-]+$/;

function rulesOf(root: Json, create: boolean): unknown[] {
  if (root.behavior === undefined && create) root.behavior = [];
  if (!Array.isArray(root.behavior)) throw new LogicEditRefused('behavior', 'the resource has no behavior: list');
  return root.behavior;
}

function ruleAt(root: Json, index: number): Json {
  const rule = rulesOf(root, false)[index];
  if (!isRecord(rule)) throw new LogicEditRefused(`behavior.${index}`, `there is no rule ${index}`);
  return rule;
}

function flowsOf(root: Json, create: boolean): Record<string, unknown[]> {
  if (root.flows === undefined && create) root.flows = {};
  if (!isRecord(root.flows)) throw new LogicEditRefused('flows', 'the resource has no flows: mapping');
  return root.flows as Record<string, unknown[]>;
}

function checkFlowId(id: string, path: string): void {
  if (!FLOW_ID.test(id)) throw new LogicEditRefused(path, `a flow id is letters, digits and _ . - only, not '${id}'`);
}

function checkRule(rule: Json, path: string): void {
  if (typeof rule.on !== 'string' || !rule.on.trim()) throw new LogicEditRefused(path, 'a rule needs an on: trigger');
}

function rewriteCalls(steps: unknown, from: string, to: string): void {
  if (!Array.isArray(steps)) return;
  for (const step of steps) {
    if (!isRecord(step)) continue;
    if (Object.hasOwn(step, 'call')) {
      if (step.call === from) step.call = to;
      else if (isRecord(step.call) && step.call.flow === from) step.call.flow = to;
    }
    for (const key of ['then', 'else', 'require']) rewriteCalls(step[key], from, to);
    if (Array.isArray(step.choose)) for (const each of step.choose) if (isRecord(each)) rewriteCalls(each.then, from, to);
  }
}

function applyOne(root: Json, edit: LogicEdit): void {
  switch (edit.op) {
    case 'addRule': {
      const rules = rulesOf(root, true);
      if (edit.index < 0 || edit.index > rules.length) {
        throw new LogicEditRefused(`behavior.${edit.index}`, `rule index ${edit.index} is outside 0..${rules.length}`);
      }
      checkRule(edit.rule, `behavior.${edit.index}`);
      rules.splice(edit.index, 0, clone(edit.rule));
      return;
    }
    case 'removeRules': {
      const rules = rulesOf(root, false);
      const seen = new Set<number>();
      for (const index of edit.indices) {
        if (index < 0 || index >= rules.length) throw new LogicEditRefused(`behavior.${index}`, `there is no rule ${index}`);
        if (seen.has(index)) throw new LogicEditRefused(`behavior.${index}`, `rule ${index} is named twice`);
        seen.add(index);
      }
      for (const index of [...seen].sort((a, b) => b - a)) rules.splice(index, 1);
      return;
    }
    case 'reorderRules': {
      const rules = rulesOf(root, false);
      if (edit.order.length !== rules.length) {
        throw new LogicEditRefused('behavior', `reorder names ${edit.order.length} rules; there are ${rules.length}`);
      }
      const seen = new Set<number>();
      const next: unknown[] = [];
      for (const index of edit.order) {
        if (index < 0 || index >= rules.length || seen.has(index)) {
          throw new LogicEditRefused('behavior', `reorder must name every rule index once; ${index} is out of place`);
        }
        seen.add(index);
        next.push(rules[index]);
      }
      rules.splice(0, rules.length, ...next);
      return;
    }
    case 'patchRule': {
      const path = `behavior.${edit.index}`;
      const rule = ruleAt(root, edit.index);
      for (const key of Object.keys(edit.set ?? {})) {
        if (key === 'do' || key === 'node') {
          throw new LogicEditRefused(`${path}.${key}`, `'${key}' is not patched on a rule; ${key === 'do' ? 'edit its steps' : 'use setNodePosition'}`);
        }
      }
      for (const key of edit.unset ?? []) {
        if (key === 'do' || key === 'node' || key === 'on') {
          throw new LogicEditRefused(`${path}.${key}`, `'${key}' cannot be removed from a rule this way`);
        }
      }
      const next: Json = { ...rule };
      for (const [key, value] of Object.entries(edit.set ?? {})) {
        if (value === null || value === undefined) delete next[key];
        else next[key] = clone(value);
      }
      for (const key of edit.unset ?? []) delete next[key];
      checkRule(next, path);
      for (const key of Object.keys(rule)) delete rule[key];
      Object.assign(rule, next);
      return;
    }
    case 'insertStep': {
      const at = insertion(root, edit.path, true);
      checkStep(edit.step, edit.path);
      at.list.splice(at.index, 0, clone(edit.step));
      return;
    }
    case 'patchStep': {
      const at = existing(root, edit.path);
      const node = at.list[at.index];
      if (!isRecord(node)) throw new LogicEditRefused(edit.path, 'a bare step has no fields to patch; replace it with a mapping');
      for (const key of [...Object.keys(edit.set ?? {}), ...(edit.unset ?? [])]) {
        if ((BRANCH_KEYS as readonly string[]).includes(key)) {
          throw new LogicEditRefused(`${edit.path}.${key}`, `'${key}' is edited through the step edits, not patched`);
        }
      }
      const next: Json = { ...node };
      for (const [key, value] of Object.entries(edit.set ?? {})) {
        if (value === null || value === undefined) delete next[key];
        else next[key] = clone(value);
      }
      for (const key of edit.unset ?? []) delete next[key];
      checkStep(next, edit.path);
      for (const key of Object.keys(node)) delete node[key];
      Object.assign(node, next);
      return;
    }
    case 'removeStep': {
      const at = existing(root, edit.path);
      at.list.splice(at.index, 1);
      return;
    }
    case 'moveStep': {
      if (edit.from === edit.to) {
        existing(root, edit.from);
        return;
      }
      if (edit.to.startsWith(`${edit.from}.`)) {
        throw new LogicEditRefused(edit.to, `a step cannot move into its own subtree ('${edit.from}')`);
      }
      const source = existing(root, edit.from);
      const [moving] = source.list.splice(source.index, 1);
      try {
        const target = insertion(root, edit.to, true);
        target.list.splice(target.index, 0, moving);
      } catch (refused) {
        source.list.splice(source.index, 0, moving); // a refused move leaves the working copy as it was
        throw refused;
      }
      return;
    }
    case 'setNodePosition': {
      const parts = segments(edit.path);
      const node = resolve(root, parts, edit.path);
      if (!isRecord(node)) throw new LogicEditRefused(edit.path, 'only rules and steps have a position');
      if (parts.length === 2 && parts[0] === 'flows') throw new LogicEditRefused(edit.path, 'a named flow has no position of its own');
      if (edit.x === null && edit.y === null) {
        delete node.node;
        return;
      }
      if (edit.x === null || edit.y === null || !Number.isFinite(edit.x) || !Number.isFinite(edit.y)) {
        throw new LogicEditRefused(edit.path, 'a position needs finite x and y, or neither to clear it');
      }
      node.node = { x: edit.x, y: edit.y };
      return;
    }
    case 'addFlow': {
      const path = `flows.${edit.id}`;
      checkFlowId(edit.id, path);
      const flows = flowsOf(root, true);
      if (Object.hasOwn(flows, edit.id)) throw new LogicEditRefused(path, `a flow named '${edit.id}' already exists`);
      edit.steps.forEach((step, index) => checkStep(step, `${path}.${index}`));
      flows[edit.id] = clone(edit.steps);
      return;
    }
    case 'removeFlow': {
      const flows = flowsOf(root, false);
      if (!Object.hasOwn(flows, edit.id)) throw new LogicEditRefused(`flows.${edit.id}`, `there is no flow named '${edit.id}'`);
      delete flows[edit.id];
      if (Object.keys(flows).length === 0) delete root.flows;
      return;
    }
    case 'renameFlow': {
      const flows = flowsOf(root, false);
      if (!Object.hasOwn(flows, edit.from)) throw new LogicEditRefused(`flows.${edit.from}`, `there is no flow named '${edit.from}'`);
      checkFlowId(edit.to, `flows.${edit.to}`);
      if (Object.hasOwn(flows, edit.to)) throw new LogicEditRefused(`flows.${edit.to}`, `a flow named '${edit.to}' already exists`);
      const renamed: Record<string, unknown[]> = {};
      for (const [key, steps] of Object.entries(flows)) renamed[key === edit.from ? edit.to : key] = steps;
      root.flows = renamed;
      if (Array.isArray(root.behavior)) {
        for (const rule of root.behavior) if (isRecord(rule)) rewriteCalls(rule.do, edit.from, edit.to);
      }
      for (const steps of Object.values(renamed)) rewriteCalls(steps, edit.from, edit.to);
      return;
    }
  }
}

/**
 * The block after the edits, in order; the input is not touched. A refused edit throws
 * `LogicEditRefused` and nothing is returned, exactly as the server refuses the whole request.
 */
export function applyEdits(block: LogicBlock, edits: readonly LogicEdit[]): LogicBlock {
  const root = clone(block) as Json;
  for (const edit of edits) applyOne(root, edit);
  const out: LogicBlock = {};
  if (root.behavior !== undefined) out.behavior = root.behavior as unknown[];
  if (root.flows !== undefined) out.flows = root.flows as Record<string, unknown[]>;
  return out;
}

// ------------------------------------------------------------------ inverses

function positionEdit(path: string, raw: unknown): LogicEdit {
  const at = positionOf(raw);
  return { op: 'setNodePosition', path, x: at?.x ?? null, y: at?.y ?? null };
}

function patchInverse(before: Json, set: Json | undefined, unset: readonly string[] | undefined): { set: Json; unset: string[] } {
  const inverseSet: Json = {};
  const inverseUnset: string[] = [];
  for (const key of [...Object.keys(set ?? {}), ...(unset ?? [])]) {
    if (Object.hasOwn(before, key)) inverseSet[key] = clone(before[key]);
    else inverseUnset.push(key);
  }
  return { set: inverseSet, unset: inverseUnset };
}

/**
 * The edits that undo `edit` when applied to the state after it, computed against the state before it.
 * (`inverse(edit)` is a list: removing several rules is undone by adding each back.) Undoing after a
 * save replays these on the server, so applying `edit` then its inverse restores the block (a key that
 * a patch removed comes back at the end of its mapping, which is the only difference in the bytes).
 */
export function invertEdit(block: LogicBlock, edit: LogicEdit): LogicEdit[] {
  const root = block as Json;
  switch (edit.op) {
    case 'addRule':
      return [{ op: 'removeRules', indices: [edit.index] }];
    case 'removeRules': {
      const rules = rulesOf(root, false);
      return [...new Set(edit.indices)]
        .sort((a, b) => a - b)
        .map((index): LogicEdit => ({ op: 'addRule', index, rule: clone(rules[index]) as Json }));
    }
    case 'reorderRules': {
      const inverse = new Array<number>(edit.order.length);
      edit.order.forEach((from, to) => (inverse[from] = to));
      return [{ op: 'reorderRules', order: inverse }];
    }
    case 'patchRule': {
      const inverse = patchInverse(ruleAt(root, edit.index), edit.set, edit.unset);
      return [{ op: 'patchRule', index: edit.index, set: inverse.set, unset: inverse.unset }];
    }
    case 'insertStep':
      return [{ op: 'removeStep', path: edit.path }];
    case 'removeStep': {
      const at = existing(root, edit.path);
      return [{ op: 'insertStep', path: edit.path, step: clone(at.list[at.index]) }];
    }
    case 'moveStep':
      return edit.from === edit.to ? [] : [{ op: 'moveStep', from: edit.to, to: edit.from }];
    case 'patchStep': {
      const at = existing(root, edit.path);
      const node = at.list[at.index];
      const inverse = patchInverse(isRecord(node) ? node : {}, edit.set, edit.unset);
      return [{ op: 'patchStep', path: edit.path, set: inverse.set, unset: inverse.unset }];
    }
    case 'setNodePosition':
      return [positionEdit(edit.path, resolve(root, segments(edit.path), edit.path))];
    case 'addFlow':
      return [{ op: 'removeFlow', id: edit.id }];
    case 'removeFlow': {
      const flows = flowsOf(root, false);
      return [{ op: 'addFlow', id: edit.id, steps: clone(flows[edit.id] ?? []) }];
    }
    case 'renameFlow':
      return [{ op: 'renameFlow', from: edit.to, to: edit.from }];
  }
}

/** Applies the edits and returns the result with the edits that take it back (in the order to replay them). */
export function applyWithInverse(
  block: LogicBlock,
  edits: readonly LogicEdit[],
): { block: LogicBlock; inverse: LogicEdit[] } {
  let current = block;
  const inverse: LogicEdit[][] = [];
  for (const edit of edits) {
    inverse.push(invertEdit(current, edit));
    current = applyEdits(current, [edit]);
  }
  return { block: current, inverse: inverse.reverse().flat() };
}
