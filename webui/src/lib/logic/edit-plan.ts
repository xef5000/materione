/**
 * The inspector's edits as plans (logic.md section 7.3, 31): each field change becomes the `LogicEdit`s the server
 * already takes, so a value typed in the inspector travels the same pipeline as a wire dragged on the canvas (D6) and
 * lands in the same history (section 26, decision 4). Nothing here knows about Svelte or the DOM.
 *
 * A step's own value (`sound: x`, `if: cond`, `wait: 20`) is patched with `patchStep`; a branch key never is
 * (section 19, decision 3), so a `choose` case is rewritten by replacing the step with a copy that holds the change,
 * which keeps its place, its `node:` position and everything under it (section 26, decision 10). A step written as a
 * bare name (`- gui.close`) has nothing to patch and gets the same replacement.
 *
 * Pure of the DOM and of Svelte.
 */
import { refuse, replaceStep, type NameOf, type Plan } from './plan';
import type { Graph, GraphNode } from './project';
import { clone, isRecord, nodeAt, type Json, type LogicBlock } from './tree';

const FLOW_ID = /^[A-Za-z0-9_.-]+$/;

function ruleIndex(node: GraphNode): number {
  return Number(node.id.slice('behavior.'.length));
}

function found(graph: Graph, id: string): GraphNode | null {
  return graph.byId.get(id) ?? null;
}

/** The key a step keeps its own value under: an action's id, or the control kind. */
function ownKey(node: GraphNode): string {
  return node.kind === 'action' ? node.op : node.kind;
}

/** Sets (or, with `undefined`, removes) a key of a step that is not a branch: `chance`, `delay`, `with`, an unknown key. */
export function planStepKey(graph: Graph, id: string, key: string, value: unknown, nameOf: NameOf): Plan {
  const node = found(graph, id);
  if (!node) return refuse('That node is no longer there.');
  const label = `Edit ${nameOf(node)}`;
  if (node.kind === 'trigger') {
    const edit = value === undefined ? { unset: [key] } : { set: { [key]: value } };
    return { ok: true, edits: [{ op: 'patchRule', index: ruleIndex(node), ...edit }], label, select: node.id };
  }
  if (node.kind === 'entry') return refuse('A named flow has no settings besides its name.');
  if (node.authored.literal) {
    if (value === undefined) return { ok: true, edits: [], label, select: node.id };
    return { ok: true, edits: replaceStep(node, { [ownKey(node)]: {}, [key]: clone(value) }), label, select: node.id };
  }
  const edit = value === undefined ? { unset: [key] } : { set: { [key]: value } };
  return { ok: true, edits: [{ op: 'patchStep', path: node.id, ...edit }], label, select: node.id };
}

/**
 * Sets a step's own value: an action's payload, an `if`'s condition, a `wait`'s ticks, a `call`'s flow. A step written
 * as a bare name becomes a mapping that holds the value.
 */
export function planStepValue(graph: Graph, id: string, value: unknown, nameOf: NameOf): Plan {
  const node = found(graph, id);
  if (!node) return refuse('That node is no longer there.');
  if (node.kind === 'trigger' || node.kind === 'entry') return refuse(`${nameOf(node)} has no single value.`);
  const label = `Edit ${nameOf(node)}`;
  const key = ownKey(node);
  if (node.authored.literal) {
    return { ok: true, edits: replaceStep(node, { [key]: clone(value) }), label, select: node.id };
  }
  return { ok: true, edits: [{ op: 'patchStep', path: node.id, set: { [key]: clone(value) } }], label, select: node.id };
}

/** The condition of a rule (`if:`), an `if` step, or one case of a `choose`; `undefined` clears a rule's. */
export function planCondition(
  graph: Graph,
  block: LogicBlock,
  id: string,
  condition: unknown,
  nameOf: NameOf,
  caseIndex?: number,
): Plan {
  const node = found(graph, id);
  if (!node) return refuse('That node is no longer there.');
  const label = `Edit condition of ${nameOf(node)}`;
  if (node.kind === 'trigger') {
    const edit = condition === undefined ? { unset: ['if'] } : { set: { if: clone(condition) } };
    return { ok: true, edits: [{ op: 'patchRule', index: ruleIndex(node), ...edit }], label, select: node.id };
  }
  if (node.kind === 'if') {
    return { ok: true, edits: [{ op: 'patchStep', path: node.id, set: { if: condition === undefined ? '${true}' : clone(condition) } }], label, select: node.id };
  }
  if (node.kind === 'choose' && caseIndex !== undefined) {
    const raw = nodeAt(block, node.id);
    if (!isRecord(raw) || !Array.isArray(raw.choose) || !isRecord(raw.choose[caseIndex])) return refuse('That case is no longer there.');
    const cases = raw.choose.map((entry, index) =>
      index === caseIndex && isRecord(entry) ? { ...entry, if: condition === undefined ? '${true}' : clone(condition) } : entry,
    );
    const next: Json = { ...clone(raw), choose: clone(cases) };
    return { ok: true, edits: replaceStep(node, next), label, select: node.id };
  }
  return refuse(`${nameOf(node)} has no condition.`);
}

/** Renames a named flow; every `call:` of it is rewritten with it (section 19, decision 5). */
export function planRenameFlow(graph: Graph, id: string, to: string): Plan {
  const node = found(graph, id);
  if (!node || node.kind !== 'entry') return refuse('That is not a named flow.');
  const name = to.trim();
  if (!name) return refuse('A flow needs a name.');
  if (!FLOW_ID.test(name)) return refuse('A flow name is letters, digits and _ . - only.');
  if (name === node.op) return { ok: true, edits: [], label: `Rename flow ${node.op}`, select: id };
  if (graph.byId.has(`flows.${name}`)) return refuse(`A flow named '${name}' already exists.`);
  return { ok: true, edits: [{ op: 'renameFlow', from: node.op, to: name }], label: `Rename flow ${node.op}`, select: `flows.${name}` };
}
