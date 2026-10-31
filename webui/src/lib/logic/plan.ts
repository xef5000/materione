/**
 * The structural edit commands of the canvas (logic.md §7.2, issue 223): insert, move, remove and connect,
 * each planned against the current graph and returned as `LogicEdit`s, or refused with a one-line reason.
 *
 * The canvas has no loose nodes. The file is a tree of lists (D1), so a node is always somewhere in one:
 * a new node is born spliced into a wire, and a wire is only ever re-attached by lifting a chain by its
 * input and dropping it on a free output port. What the rules refuse is exactly what the file cannot say:
 *
 *   - fan-out: an output port feeds one node, so dropping a second wire on an occupied port is refused;
 *   - a join: a node has one input, so a wire from another port onto a node that is already fed is refused;
 *   - a cycle: a chain cannot be attached below its own steps.
 *
 * A plan is pure data. It does not touch the document: `command.ts` turns an accepted plan into one entry
 * of the studio's history, and the same edits are what `logic.edit` replays on save (D6).
 *
 * Pure of the DOM and of Svelte.
 */
import { descendants, tailOf, targetOf, type Graph, type GraphNode, type PortInfo } from './project';
import { clone, isRecord, nodeAt, type Json, type LogicBlock } from './tree';
import type { LogicEdit } from './server';

export type Plan =
  | { ok: true; edits: LogicEdit[]; label: string; select?: string | null }
  | { ok: false; reason: string };

export const refuse = (reason: string): Plan => ({ ok: false, reason });

/** A place in a step list: the list, the index to insert at, and the node and port that feed it. */
export interface Slot {
  owner: string;
  port: string;
  list: string;
  index: number;
  /** The list is in the file; false for an `else:` that does not exist yet. */
  exists: boolean;
}

/** Names for messages: the title the canvas shows for a node. */
export type NameOf = (node: GraphNode) => string;

const quote = (text: string): string => `'${text}'`;

export function portOf(node: GraphNode, port: string): PortInfo | undefined {
  return node.ports.find((entry) => entry.id === port);
}

/** The slot a port inserts at: the head of a branch (ahead of what it feeds), or right after a node. */
export function slotOf(graph: Graph, nodeId: string, portId: string): Slot | null {
  const node = graph.byId.get(nodeId);
  const port = node && portOf(node, portId);
  if (!node || !port) return null;
  if (port.sibling) return { owner: nodeId, port: portId, list: port.list, index: (node.index ?? 0) + 1, exists: true };
  return { owner: nodeId, port: portId, list: port.list, index: 0, exists: port.exists };
}

/** The last port of a lane that is free: where "add a node" appends when nothing is selected. */
export function tipOf(graph: Graph, ownerId: string): Slot | null {
  const head = graph.byId.get(ownerId);
  if (!head) return null;
  const out = head.ports[0];
  if (!out) return null;
  let last: string | undefined = targetOf(graph, ownerId, out.id);
  if (!last) return slotOf(graph, ownerId, out.id);
  for (;;) {
    const next: string | undefined = targetOf(graph, last, 'after');
    if (!next) break;
    last = next;
  }
  return slotOf(graph, last, 'after');
}

/** The first free output port of a node, else its `after` (which splices ahead of what follows). */
export function defaultSlot(graph: Graph, nodeId: string): Slot | null {
  const node = graph.byId.get(nodeId);
  if (!node) return null;
  const order = node.ports.filter((port) => port.id !== 'after');
  const free = [...order, ...node.ports.filter((port) => port.id === 'after')].find((port) => !targetOf(graph, nodeId, port.id));
  const port = free ?? node.ports.find((entry) => entry.id === 'after') ?? node.ports[0];
  return port ? slotOf(graph, nodeId, port.id) : null;
}

// ------------------------------------------------------------------ building edits

/** The key a branch list is stored under: the last segment of its path (`then`, `else`, `require`). */
function branchKey(list: string): string {
  return list.slice(list.lastIndexOf('.') + 1);
}

/**
 * Edits that give `slot.owner` the branch list `slot.list` (empty), when the file does not have it yet.
 * The step edits cannot create a branch (a branch key is never patched), so the step is replaced by
 * itself plus the list: remove it and insert the copy at the same path, which keeps its place and its
 * `node:` position, and nests everything under it unchanged.
 */
function createBranch(block: LogicBlock, slot: Slot, graph: Graph): LogicEdit[] {
  const owner = graph.byId.get(slot.owner)!;
  const raw = nodeAt(block, owner.id);
  if (!isRecord(raw)) return [];
  const next: Json = { ...clone(raw), [branchKey(slot.list)]: [] };
  if (owner.kind === 'trigger') {
    const index = Number(owner.id.slice('behavior.'.length));
    return [
      { op: 'removeRules', indices: [index] },
      { op: 'addRule', index, rule: next },
    ];
  }
  return [
    { op: 'removeStep', path: owner.id },
    { op: 'insertStep', path: owner.id, step: next },
  ];
}

/** Inserts `step` at the slot, creating the branch list first when the file does not have it. */
export function planInsert(graph: Graph, block: LogicBlock, slot: Slot, step: unknown, label: string): Plan {
  const edits: LogicEdit[] = slot.exists ? [] : createBranch(block, slot, graph);
  if (!slot.exists && edits.length === 0) return refuse('That port cannot take a step here.');
  edits.push({ op: 'insertStep', path: `${slot.list}.${slot.index}`, step: clone(step) });
  return { ok: true, edits, label, select: `${slot.list}.${slot.index}` };
}

/** A new rule for a trigger, at the end of `behavior:`. */
export function planAddRule(block: LogicBlock, rule: Json, label: string): Plan {
  const index = block.behavior?.length ?? 0;
  return { ok: true, edits: [{ op: 'addRule', index, rule: clone(rule) }], label, select: `behavior.${index}` };
}

/** A new empty flow. */
export function planAddFlow(block: LogicBlock, base = 'flow'): Plan {
  const taken = new Set(Object.keys(block.flows ?? {}));
  let id = base;
  for (let n = 2; taken.has(id); n++) id = `${base}_${n}`;
  return { ok: true, edits: [{ op: 'addFlow', id, steps: [] }], label: `Add flow ${id}`, select: `flows.${id}` };
}

/** Removes a node with everything below its branches; the chain it was in closes up. */
export function planRemove(graph: Graph, nodeId: string, nameOf: NameOf): Plan {
  const node = graph.byId.get(nodeId);
  if (!node) return refuse('That node is no longer there.');
  const label = `Delete ${nameOf(node)}`;
  if (node.kind === 'trigger') {
    return { ok: true, edits: [{ op: 'removeRules', indices: [Number(node.id.slice('behavior.'.length))] }], label, select: null };
  }
  if (node.kind === 'entry') return { ok: true, edits: [{ op: 'removeFlow', id: node.op }], label, select: null };
  return { ok: true, edits: [{ op: 'removeStep', path: node.id }], label, select: graph.feeder.get(node.id)?.from ?? null };
}

export function planPosition(graph: Graph, nodeId: string, x: number | null, y: number | null): Plan {
  const node = graph.byId.get(nodeId);
  if (!node) return refuse('That node is no longer there.');
  if (node.kind === 'entry') return refuse('A named flow has no position of its own; it follows the flows below the rules.');
  return { ok: true, edits: [{ op: 'setNodePosition', path: node.id, x, y }], label: 'Move node', select: node.id };
}

/** Which authored key a node's primary field edits, when it has one. */
export function valueKey(node: GraphNode): string | null {
  if (node.kind === 'trigger') return 'on';
  if (node.kind === 'entry' || node.kind === 'stop' || node.kind === 'choose' || node.kind === 'require') return null;
  if (!node.op) return null;
  if (!node.authored.own || node.authored.literal) return null;
  return node.kind === 'action' ? node.op : node.kind;
}

/** Sets the node's primary value (the action's shorthand value, an `if`'s condition, a `wait`'s ticks). */
export function planSetValue(graph: Graph, nodeId: string, value: unknown, nameOf: NameOf): Plan {
  const node = graph.byId.get(nodeId);
  if (!node) return refuse('That node is no longer there.');
  const key = valueKey(node);
  if (!key) return refuse(`${nameOf(node)} has no single value to edit here.`);
  const label = `Edit ${nameOf(node)}`;
  if (node.kind === 'trigger') {
    return { ok: true, edits: [{ op: 'patchRule', index: Number(node.id.slice('behavior.'.length)), set: { on: value } }], label, select: node.id };
  }
  return { ok: true, edits: [{ op: 'patchStep', path: node.id, set: { [key]: value } }], label, select: node.id };
}

// ------------------------------------------------------------------ moving a chain

/**
 * Lifts `nodeId` and every step after it in its list, and attaches them to `slot`. This is what dragging a
 * wire by its input end onto another output port means: the node, the steps that follow it, and everything
 * hung below them travel together, and the port they left is free again.
 */
export function planMoveChain(graph: Graph, block: LogicBlock, nodeId: string, slot: Slot, nameOf: NameOf): Plan {
  const node = graph.byId.get(nodeId);
  const owner = graph.byId.get(slot.owner);
  if (!node || !owner) return refuse('That node is no longer there.');
  if (node.kind === 'trigger' || node.kind === 'entry') {
    return refuse(`${nameOf(node)} starts a flow; nothing can feed it.`);
  }
  const tail = tailOf(graph, nodeId);
  const reach = new Set<string>(tail);
  for (const id of tail) for (const below of descendants(graph, id)) reach.add(below);
  if (reach.has(slot.owner)) {
    return refuse(`That would make a loop: ${nameOf(node)} already leads to ${nameOf(owner)}.`);
  }
  const taken = targetOf(graph, slot.owner, slot.port);
  const sameSpot = slot.list === node.list && slot.index === node.index;
  if (sameSpot || (taken === nodeId && !portOf(owner, slot.port)?.sibling)) {
    return { ok: true, edits: [], label: `Move ${nameOf(node)}`, select: nodeId };
  }
  if (taken) {
    return refuse(`${portLabel(owner, slot.port, nameOf)} already feeds ${nameOf(graph.byId.get(taken)!)}; a port feeds one node.`);
  }
  const edits: LogicEdit[] = slot.exists ? [] : createBranch(block, slot, graph);
  if (!slot.exists && edits.length === 0) return refuse('That port cannot take a step here.');
  tail.forEach((_, offset) => {
    edits.push({ op: 'moveStep', from: `${node.list}.${node.index}`, to: `${slot.list}.${slot.index + offset}` });
  });
  return { ok: true, edits, label: `Move ${nameOf(node)}`, select: `${slot.list}.${slot.index}` };
}

function portLabel(node: GraphNode, port: string, nameOf: NameOf): string {
  const info = portOf(node, port);
  return `${nameOf(node)} ${quote(info?.label ?? port)}`;
}

// ------------------------------------------------------------------ wires

/**
 * A wire dragged from an output port onto a node (the gesture of every node editor), judged by the rules
 * of the file. Nothing is connected that the file could not say: the reasons are the refusals.
 */
export function planConnect(
  graph: Graph,
  block: LogicBlock,
  from: { node: string; port: string },
  to: string,
  nameOf: NameOf,
): Plan {
  const source = graph.byId.get(from.node);
  const target = graph.byId.get(to);
  if (!source || !target || !portOf(source, from.port)) return refuse('That wire has no end here.');
  if (target.kind === 'trigger' || target.kind === 'entry') {
    return refuse(`${nameOf(target)} starts a flow; nothing can feed it.`);
  }
  if (to === from.node || descendants(graph, to).has(from.node)) {
    return refuse(`That would make a loop: ${nameOf(target)} already leads to ${nameOf(source)}.`);
  }
  const taken = targetOf(graph, from.node, from.port);
  if (taken === to) return { ok: true, edits: [], label: 'Connect', select: to };
  if (taken) {
    return refuse(`${portLabel(source, from.port, nameOf)} already feeds ${nameOf(graph.byId.get(taken)!)}; fan-out is not allowed.`);
  }
  const feeder = graph.feeder.get(to);
  if (feeder) {
    return refuse(
      `${nameOf(target)} is already fed by ${nameOf(graph.byId.get(feeder.from)!)}; a node has one input (drag from its input to move it).`,
    );
  }
  const slot = slotOf(graph, from.node, from.port);
  return slot ? planMoveChain(graph, block, to, slot, nameOf) : refuse('That wire has no end here.');
}

// ------------------------------------------------------------------ a switch's cases

function replaceStep(node: GraphNode, next: Json): LogicEdit[] {
  return [
    { op: 'removeStep', path: node.id },
    { op: 'insertStep', path: node.id, step: next },
  ];
}

/** Adds a case (`if` + `then`) to the end of a switch. */
export function planAddCase(graph: Graph, block: LogicBlock, nodeId: string, nameOf: NameOf): Plan {
  const node = graph.byId.get(nodeId);
  const raw = node && nodeAt(block, nodeId);
  if (!node || node.kind !== 'choose' || !isRecord(raw) || !Array.isArray(raw.choose)) return refuse('Only a choose node has cases.');
  const next: Json = { ...clone(raw), choose: [...clone(raw.choose), { if: '${true}', then: [] }] };
  return { ok: true, edits: replaceStep(node, next), label: `Add case to ${nameOf(node)}`, select: nodeId };
}

/** Removes the last case of a switch, with the steps in it. */
export function planRemoveCase(graph: Graph, block: LogicBlock, nodeId: string, nameOf: NameOf): Plan {
  const node = graph.byId.get(nodeId);
  const raw = node && nodeAt(block, nodeId);
  if (!node || node.kind !== 'choose' || !isRecord(raw) || !Array.isArray(raw.choose) || raw.choose.length === 0) {
    return refuse('That switch has no case to remove.');
  }
  const next: Json = { ...clone(raw), choose: clone(raw.choose).slice(0, -1) };
  return { ok: true, edits: replaceStep(node, next), label: `Remove case from ${nameOf(node)}`, select: nodeId };
}
