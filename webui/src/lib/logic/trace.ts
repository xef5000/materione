/**
 * The live trace as the canvas reads it (logic.md section 7.6, 32): the server streams what a run did, by step path,
 * and this maps the paths onto the nodes the canvas draws. A path names a node the same way a diagnostic does
 * (`verdicts.ts`): the deepest node whose id is the path or a prefix of it. A node that ran lights up and so does the
 * wire that fed it; a node nothing reached stays as it is (dim, while a run is on screen); a failed action carries its
 * reason.
 *
 * The server owns what happened; this module only folds a stream of entries into a picture, so it keeps no history
 * beyond the run on screen and never touches the document. Pure of the DOM and of Svelte.
 */
import type { Graph } from './project';
import { isRecord } from './tree';

/** The kinds the server sends (`TraceKind.wireName()`). An unknown kind is kept and drawn as a plain visit. */
export type TraceKind =
  | 'run-started' | 'ran' | 'failed' | 'skipped' | 'branch' | 'require-failed' | 'called'
  | 'suspended' | 'resumed' | 'run-ended';

/** One entry of a `push.logic.trace` frame. */
export interface TraceEntry {
  run: number;
  rule: string;
  /** The step path (`behavior.0.do.1.then.0`), the one the diagnostics use. */
  path: string;
  kind: TraceKind | string;
  detail: string;
  /** Epoch milliseconds, on the server. */
  at: number;
}

/** A `push.logic.trace` payload. */
export interface TracePush {
  resource: string;
  seq: number;
  entries: TraceEntry[];
  /** How many entries the server dropped before this frame (its rate limit, a run's step cap, a full queue). */
  dropped: number;
}

/** What a node did in the run on screen. */
export type NodeState = 'ran' | 'failed' | 'skipped' | 'waiting';

export interface NodeTrace {
  state: NodeState;
  /** The reason a node failed or was skipped, the branch an `if` took, the flow a `call` entered. */
  detail: string;
  /** The order the node was reached in, from 1, so the wires can light one after the other. */
  order: number;
}

/** The run on screen. */
export interface TraceView {
  nodes: ReadonlyMap<string, NodeTrace>;
  /** Entries the server dropped since the picture was cleared. */
  dropped: number;
  /** The highest `order` handed out. */
  steps: number;
  /** The last run that changed the picture, or null. */
  run: number | null;
}

export const emptyTrace = (): TraceView => ({ nodes: new Map(), dropped: 0, steps: 0, run: null });

/** Whether a picture shows any run (an empty one dims nothing). */
export const hasRun = (view: TraceView): boolean => view.nodes.size > 0;

const MAX_ENTRIES = 512;
const MAX_TEXT = 400;

/**
 * Reads a `push.logic.trace` payload, or null when it is not one. The studio trusts the server for what happened and
 * nothing for its shape: a frame it cannot read is dropped whole rather than half-drawn.
 */
export function parseTracePush(payload: unknown): TracePush | null {
  if (!isRecord(payload) || typeof payload.resource !== 'string' || !Array.isArray(payload.entries)) return null;
  const entries: TraceEntry[] = [];
  for (const raw of payload.entries.slice(0, MAX_ENTRIES)) {
    if (!isRecord(raw) || typeof raw.path !== 'string' || typeof raw.kind !== 'string') continue;
    entries.push({
      run: typeof raw.run === 'number' ? raw.run : 0,
      rule: typeof raw.rule === 'string' ? raw.rule : '',
      path: raw.path,
      kind: raw.kind,
      detail: typeof raw.detail === 'string' ? raw.detail.slice(0, MAX_TEXT) : '',
      at: typeof raw.at === 'number' ? raw.at : 0,
    });
  }
  return {
    resource: payload.resource,
    seq: typeof payload.seq === 'number' ? payload.seq : 0,
    entries,
    dropped: typeof payload.dropped === 'number' && payload.dropped > 0 ? Math.floor(payload.dropped) : 0,
  };
}

/** The node a step path is about: the deepest node whose id is the path or a prefix of it. */
export function nodeOfPath(graph: Graph, path: string): string | null {
  let best: string | null = null;
  for (const node of graph.nodes) {
    if ((path === node.id || path.startsWith(`${node.id}.`)) && (best === null || node.id.length > best.length)) best = node.id;
  }
  return best;
}

/**
 * The GUI's id as the server names it (`pack:name`). The resource list gives it that way; a connection that hands out the
 * bare name gets the pack put in front, so the subscription always names one GUI.
 */
export function traceResourceOf(resource: { pack: string; id: string }): string {
  return resource.id.includes(':') ? resource.id : `${resource.pack}:${resource.id}`;
}

const under = (id: string, root: string): boolean => id === root || id.startsWith(`${root}.`);

/**
 * Folds entries into the picture. A rule starting clears its own lane (the previous run of that rule leaves), and a
 * `call` clears the flow it enters; everything else lands on the node its path names. A failure is never overwritten
 * by a later success on the same node, because the node's job is to show what went wrong.
 */
export function applyEntries(view: TraceView, graph: Graph, entries: readonly TraceEntry[], dropped = 0): TraceView {
  const nodes = new Map(view.nodes);
  let steps = view.steps;
  let run = view.run;
  const note = (id: string, state: NodeState, detail: string): void => {
    const was = nodes.get(id);
    if (was?.state === 'failed' && state !== 'failed') return;
    nodes.set(id, { state, detail: detail || was?.detail || '', order: was ? was.order : ++steps });
  };
  for (const entry of entries) {
    run = entry.run;
    if (entry.kind === 'run-started') {
      for (const id of [...nodes.keys()]) if (under(id, entry.path)) nodes.delete(id);
    } else if (entry.kind === 'called') {
      for (const id of [...nodes.keys()]) if (under(id, `flows.${entry.detail}`)) nodes.delete(id);
    }
    const id = nodeOfPath(graph, entry.path);
    if (!id) continue;
    switch (entry.kind) {
      case 'failed':
      case 'require-failed':
        note(id, 'failed', entry.detail);
        break;
      case 'skipped':
        if (!nodes.has(id)) note(id, 'skipped', entry.detail);
        break;
      case 'suspended':
        note(id, 'waiting', entry.detail);
        break;
      case 'run-ended':
        // the end of a run is about the rule; it only speaks up when the run did not finish cleanly
        if (/^(ABORTED|CANCELLED|DROPPED)/.test(entry.detail)) note(id, 'failed', entry.detail);
        break;
      default:
        note(id, 'ran', entry.detail);
    }
  }
  return { nodes, dropped: view.dropped + dropped, steps, run };
}

/** The wires a run went along: every wire that feeds a node which ran (or failed, or is waiting). */
export function litEdges(graph: Graph, view: TraceView): Map<string, number> {
  const out = new Map<string, number>();
  for (const edge of graph.edges) {
    const target = view.nodes.get(edge.to);
    if (target && target.state !== 'skipped') out.set(edge.id, target.order);
  }
  return out;
}
