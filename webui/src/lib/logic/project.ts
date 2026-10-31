/**
 * The graph projection (logic.md §7.2): the rules and steps of the file as nodes, ports and exec edges.
 *
 * It is a projection and not a model. The canvas holds no state of its own: `project(block)` is
 * recomputed from the authored tree after every edit, and `unproject(graph)` rebuilds the very same tree,
 * key order and unknown fields included, so loading and saving an untouched logic block is byte-identical
 * (the lossless rule of master §5.6). A node therefore carries the authored map it came from, minus the
 * lists its ports feed.
 *
 * The shape is the structured flow of §2: a trigger node per rule, an action node per action step, a
 * branch node per `if`, a guard node per `require`, a switch node per `choose`, flow nodes for `wait`,
 * `stop` and `call`, and a flow-entry node per named flow. Every node has one input and every port feeds
 * at most one node, so the graph has no joins, no fan-out and no cycle by construction.
 *
 * Pure of the DOM and of Svelte.
 */
import { isRecord, positionOf, stepKind, type Json, type LogicBlock } from './tree';

export type NodeKind = 'trigger' | 'entry' | 'action' | 'if' | 'require' | 'choose' | 'wait' | 'stop' | 'call';

/** One output port: where a chain of steps hangs off a node. */
export interface PortInfo {
  /** `out` (a trigger or flow entry), `after`, `then`, `else`, `steps`, `failed` or `case:<n>`. */
  id: string;
  label: string;
  /** The path of the step list this port feeds; the first step of the list is the port's target. */
  list: string;
  /** `after` feeds the node's next sibling: the insertion index is the node's index + 1. */
  sibling: boolean;
  /** The list is in the file. When it is not (an `if` with no `else:`), writing to the port creates it. */
  exists: boolean;
}

export interface GraphNode {
  /** The authored path: `behavior.0`, `behavior.0.do.1`, `flows.purchase`, `flows.purchase.0`. */
  id: string;
  kind: NodeKind;
  /** The trigger selector, the action id, the control kind or the flow id. */
  op: string;
  /** The nearest rule (`behavior.N`) or flow (`flows.id`) the node belongs to. */
  owner: string;
  /** The step list the node sits in, and its index there (steps only). */
  list?: string;
  index?: number;
  /** The kind's own value as authored (`sound: x` -> `x`, `if: cond` -> `cond`, `wait: 20` -> `20`). */
  value: unknown;
  /** A rule's `if:` as authored. */
  condition?: unknown;
  /** The other keys the node shows as options (`chance`, `delay`, `with`, a rule's `click`/`layer`). */
  options: Json;
  position: { x: number; y: number } | null;
  /** Output ports, top to bottom: the sequence continues from the first one. */
  ports: PortInfo[];
  /** What it takes to rebuild the node's authored map (see `unproject`). */
  authored: Authored;
}

interface Authored {
  /** The step was not a mapping (a bare name such as `- gui.close`) and is rebuilt as it was. */
  literal?: { value: unknown };
  /** The authored map with the lists the ports carry left out. */
  own?: Json;
  /** The authored key order, including the keys of the port lists. */
  keys?: string[];
  /** The kind's branch keys that are ports of this node: key -> port id. */
  branches?: Record<string, string>;
  /** A switch's cases, when `choose:` is a list: a well-formed case's other keys (its steps ride its port), or the case as authored. */
  cases?: { own: Json; keys: string[]; malformed?: unknown }[];
}

export interface GraphEdge {
  /** `from`.`port` -> `to`, as a stable key. */
  id: string;
  from: string;
  port: string;
  to: string;
}

export interface Graph {
  nodes: GraphNode[];
  edges: GraphEdge[];
  /** Every node by id. */
  byId: ReadonlyMap<string, GraphNode>;
  /** The edge that feeds a node (every node but a trigger or a flow entry has one). */
  feeder: ReadonlyMap<string, GraphEdge>;
  /** The edge that leaves a port, by `node|port`. */
  leaving: ReadonlyMap<string, GraphEdge>;
}

export const portKey = (node: string, port: string): string => `${node}|${port}`;

const STEP_KINDS = new Set(['if', 'require', 'choose', 'wait', 'stop', 'call']);

function nodeKindOf(kind: string): NodeKind {
  return STEP_KINDS.has(kind) ? (kind as NodeKind) : 'action';
}

/** The port ids a kind's branch keys map to. */
function branchPorts(kind: NodeKind): Record<string, string> {
  if (kind === 'if') return { then: 'then', else: 'else' };
  if (kind === 'require') return { require: 'steps', else: 'failed' };
  if (kind === 'choose') return { else: 'else' };
  return {};
}

// ------------------------------------------------------------------ projecting

class Builder {
  readonly nodes: GraphNode[] = [];
  readonly edges: GraphEdge[] = [];

  edge(from: string, port: string, to: string): void {
    this.edges.push({ id: `${portKey(from, port)}>${to}`, from, port, to });
  }

  /** The steps of one list: the first hangs off `owner.port`, each next one off the previous `after`. */
  chain(steps: unknown[], list: string, owner: string, port: string, ownerOf: string): void {
    let previous: { id: string; port: string } = { id: owner, port };
    steps.forEach((step, index) => {
      const node = this.step(step, list, index, ownerOf);
      this.edge(previous.id, previous.port, node.id);
      previous = { id: node.id, port: 'after' };
    });
  }

  step(raw: unknown, list: string, index: number, owner: string): GraphNode {
    const id = `${list}.${index}`;
    const kind = nodeKindOf(stepKind(raw));
    const op = kind === 'action' ? stepKind(raw) : kind;
    const map = isRecord(raw) ? raw : null;
    const branches = branchPorts(kind);
    const ports: PortInfo[] = [{ id: 'after', label: 'after', list, sibling: true, exists: true }];
    const authored: Authored = map ? { keys: Object.keys(map) } : { literal: { value: raw } };
    const own: Json = {};
    const owned: Record<string, string> = {};
    const lists: [string, string, unknown[]][] = []; // port id, list path, steps
    if (map) {
      for (const [key, value] of Object.entries(map)) {
        const port = branches[key];
        if (port && Array.isArray(value)) {
          owned[key] = port;
          lists.push([port, `${id}.${key}`, value]);
        } else if (!(kind === 'choose' && key === 'choose' && Array.isArray(value))) {
          own[key] = value;
        }
      }
    }
    if (kind === 'if') {
      ports.push(this.branchPort(map, 'then', 'then', 'then', id));
      ports.push(this.branchPort(map, 'else', 'else', 'else', id));
    } else if (kind === 'require') {
      ports.push(this.branchPort(map, 'require', 'steps', 'steps', id));
      ports.push(this.branchPort(map, 'else', 'failed', 'failed', id));
    } else if (kind === 'choose') {
      const cases = map && Array.isArray(map.choose) ? map.choose : null;
      if (cases) authored.cases = [];
      (cases ?? []).forEach((entry, c) => {
        if (isRecord(entry) && Array.isArray(entry.then)) {
          const caseOwn: Json = {};
          for (const [key, value] of Object.entries(entry)) if (key !== 'then') caseOwn[key] = value;
          authored.cases!.push({ own: caseOwn, keys: Object.keys(entry) });
          ports.push({ id: `case:${c}`, label: `case ${c + 1}`, list: `${id}.choose.${c}.then`, sibling: false, exists: true });
          lists.push([`case:${c}`, `${id}.choose.${c}.then`, entry.then]);
        } else {
          authored.cases!.push({ own: {}, keys: [], malformed: entry });
        }
      });
      ports.push(this.branchPort(map, 'else', 'else', 'else', id));
    }
    authored.own = map ? own : undefined;
    authored.branches = owned;
    const node: GraphNode = {
      id,
      kind,
      op,
      owner,
      list,
      index,
      value: map ? map[stepKind(map)] : undefined,
      options: map ? optionsOf(map, kind === 'choose' ? new Set(['choose']) : new Set([stepKind(map)])) : {},
      position: positionOf(map),
      ports,
      authored,
    };
    this.nodes.push(node);
    for (const [port, path, steps] of lists) this.chain(steps, path, id, port, owner);
    return node;
  }

  private branchPort(map: Json | null, key: string, id: string, label: string, nodeId: string): PortInfo {
    return { id, label, list: `${nodeId}.${key}`, sibling: false, exists: !!map && Array.isArray(map[key]) };
  }
}

/** The authored keys that are neither the kind, a branch nor the position: the node's options. */
function optionsOf(map: Json, skip: ReadonlySet<string>): Json {
  const out: Json = {};
  for (const [key, value] of Object.entries(map)) {
    if (skip.has(key) || key === 'node' || key === 'then' || key === 'else' || key === 'require' || key === 'choose') continue;
    out[key] = value;
  }
  return out;
}

/** The graph of a logic block: rules first, in order, then the named flows, in order. */
export function project(block: LogicBlock): Graph {
  const builder = new Builder();
  (block.behavior ?? []).forEach((rule, index) => {
    const id = `behavior.${index}`;
    const map = isRecord(rule) ? rule : {};
    const hasDo = Array.isArray(map.do);
    const authored: Authored = { own: {}, keys: Object.keys(map), branches: hasDo ? { do: 'out' } : {} };
    const options: Json = {};
    for (const [key, value] of Object.entries(map)) {
      if (key !== 'do' || !hasDo) authored.own![key] = value;
      if (key !== 'do' && key !== 'on' && key !== 'if' && key !== 'node') options[key] = value;
    }
    builder.nodes.push({
      id,
      kind: 'trigger',
      op: typeof map.on === 'string' ? map.on : '',
      owner: id,
      value: map.on,
      condition: map.if,
      options,
      position: positionOf(map),
      ports: [{ id: 'out', label: 'do', list: `${id}.do`, sibling: false, exists: hasDo }],
      authored,
    });
    if (hasDo) builder.chain(map.do as unknown[], `${id}.do`, id, 'out', id);
  });
  for (const [flow, steps] of Object.entries(block.flows ?? {})) {
    const id = `flows.${flow}`;
    builder.nodes.push({
      id,
      kind: 'entry',
      op: flow,
      owner: id,
      value: flow,
      options: {},
      position: null,
      ports: [{ id: 'out', label: 'steps', list: id, sibling: false, exists: Array.isArray(steps) }],
      authored: { own: {}, keys: [], branches: {} },
    });
    if (Array.isArray(steps)) builder.chain(steps, id, id, 'out', id);
  }
  return finish(builder);
}

function finish(builder: Builder): Graph {
  const byId = new Map(builder.nodes.map((node) => [node.id, node]));
  const feeder = new Map<string, GraphEdge>();
  const leaving = new Map<string, GraphEdge>();
  for (const edge of builder.edges) {
    feeder.set(edge.to, edge);
    leaving.set(portKey(edge.from, edge.port), edge);
  }
  return { nodes: builder.nodes, edges: builder.edges, byId, feeder, leaving };
}

// ------------------------------------------------------------------ unprojecting

/**
 * The logic block a graph came from. Every list is rebuilt by walking the edges from its port, and every
 * map by its authored key order, so `unproject(project(block))` equals `block` exactly.
 */
export function unproject(graph: Graph): LogicBlock {
  const chain = (node: string, port: string): unknown[] => {
    const steps: unknown[] = [];
    let next = graph.leaving.get(portKey(node, port))?.to;
    const seen = new Set<string>();
    while (next && !seen.has(next)) {
      seen.add(next);
      steps.push(step(graph.byId.get(next)!));
      next = graph.leaving.get(portKey(next, 'after'))?.to;
    }
    return steps;
  };
  const step = (node: GraphNode): unknown => {
    const { authored } = node;
    if (authored.literal) return authored.literal.value;
    const out: Json = {};
    for (const key of authored.keys ?? []) {
      const port = authored.branches?.[key];
      if (port) out[key] = chain(node.id, port);
      else if (key === 'choose' && authored.cases) out[key] = cases(node);
      else if (authored.own && Object.hasOwn(authored.own, key)) out[key] = authored.own[key];
    }
    return out;
  };
  const cases = (node: GraphNode): unknown[] =>
    (node.authored.cases ?? []).map((entry, index) => {
      if (entry.malformed !== undefined) return entry.malformed;
      const out: Json = {};
      for (const key of entry.keys) out[key] = key === 'then' ? chain(node.id, `case:${index}`) : entry.own[key];
      return out;
    });
  const behavior: unknown[] = [];
  const flows: Record<string, unknown[]> = {};
  let hasBehavior = false;
  let hasFlows = false;
  for (const node of graph.nodes) {
    if (node.kind === 'trigger') {
      hasBehavior = true;
      const out: Json = {};
      for (const key of node.authored.keys ?? []) {
        if (key === 'do' && node.authored.branches?.do) out.do = chain(node.id, 'out');
        else if (node.authored.own && Object.hasOwn(node.authored.own, key)) out[key] = node.authored.own[key];
      }
      behavior.push(out);
    } else if (node.kind === 'entry') {
      hasFlows = true;
      flows[node.op] = chain(node.id, 'out');
    }
  }
  const block: LogicBlock = {};
  if (hasBehavior) block.behavior = behavior;
  if (hasFlows) block.flows = flows;
  return block;
}

// ------------------------------------------------------------------ queries

/** The id of the node a port feeds, or undefined when the port is free. */
export function targetOf(graph: Graph, node: string, port: string): string | undefined {
  return graph.leaving.get(portKey(node, port))?.to;
}

/** The nodes below `id` (everything its ports feed, transitively), not including `id`. */
export function descendants(graph: Graph, id: string): Set<string> {
  const out = new Set<string>();
  const queue = [id];
  while (queue.length) {
    const current = queue.pop()!;
    const node = graph.byId.get(current);
    if (!node) continue;
    for (const port of node.ports) {
      const next = targetOf(graph, current, port.id);
      if (next && !out.has(next)) {
        out.add(next);
        queue.push(next);
      }
    }
  }
  return out;
}

/** The steps that follow `id` in its own list (its `after` chain), in order, `id` first. */
export function tailOf(graph: Graph, id: string): string[] {
  const out: string[] = [];
  let next: string | undefined = id;
  while (next && !out.includes(next)) {
    out.push(next);
    next = targetOf(graph, next, 'after');
  }
  return out;
}

/** A short name for a node, for messages. */
export function describe(node: GraphNode): string {
  if (node.kind === 'trigger') return node.op || 'rule without a trigger';
  if (node.kind === 'entry') return `flow ${node.op}`;
  return node.op || 'step with no kind';
}
