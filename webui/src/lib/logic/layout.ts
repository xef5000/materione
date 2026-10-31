/**
 * Where the nodes sit and where the wires run (logic.md §7.2, §7.3).
 *
 * Layout is deterministic: the same logic always draws the same way, so a pack opened on two machines
 * looks alike and a position stored in the file (`node: {x, y}`, logic.md §12 decision 3) is the only
 * thing that ever moves a node. A node with a stored position goes there; every other node is placed by
 * the rule below, so a half-positioned graph stays readable.
 *
 *   - A rule is a lane. The trigger is at the left, the rule's steps run to the right along the lane.
 *   - A node's `after` continues the lane. A branch (`then`, `else`, a case) hangs below the node, and its
 *     steps run to the right on rows of their own, so the lane continues to the right of the branches.
 *   - Lanes are stacked, rules first and named flows after.
 *
 * Wires are routed orthogonally on a four-pixel grid so they read as stepped "redstone" lines.
 *
 * Pure of the DOM and of Svelte.
 */
import { portKey, targetOf, type Graph, type GraphEdge, type GraphNode } from './project';

export const NODE_W = 220;
export const HEAD = 28;
export const ROW = 28;
export const FOOT = 8;
export const GAP_X = 72;
export const GAP_Y = 28;
export const LANE_GAP = 72;
/** The wire grid: every routed corner sits on a multiple of this. */
export const GRID = 4;

export interface Point {
  x: number;
  y: number;
}

export interface Rect extends Point {
  w: number;
  h: number;
}

/** The rows a node's body shows beside its ports: a trigger lists its filters and condition under its name. */
export function bodyRows(node: GraphNode): number {
  if (node.kind !== 'trigger') return 1;
  const extras = Object.keys(node.options).length + (node.condition !== undefined ? 1 : 0);
  return 1 + Math.min(3, extras);
}

export function sizeOf(node: GraphNode): { w: number; h: number } {
  const rows = Math.max(node.ports.length, bodyRows(node));
  return { w: NODE_W, h: HEAD + rows * ROW + FOOT };
}

/** The y offset, inside the node, of the wire anchor of its output port number `index`. */
export function portOffset(index: number): number {
  return HEAD + index * ROW + ROW / 2;
}

/** The y offset of every node's input anchor (the first row, so a lane of steps runs level). */
export const INPUT_OFFSET = HEAD + ROW / 2;

const snap = (value: number): number => Math.round(value / GRID) * GRID;

export interface Layout {
  /** Where every node is drawn: its stored position, else its automatic one. */
  rects: ReadonlyMap<string, Rect>;
  /** Where the layout would put each node with no stored position (for "reset position"). */
  auto: ReadonlyMap<string, Point>;
  bounds: Rect;
}

interface Placed {
  w: number;
  h: number;
}

/** Lays the graph out. `overrides` are positions in flight (a drag), which beat the stored ones. */
export function layout(graph: Graph, overrides: ReadonlyMap<string, Point> = new Map()): Layout {
  const auto = new Map<string, Point>();

  /** The chain that hangs off `owner.port`, laid out from (x, y); its width and height. */
  const chain = (owner: string, port: string, x: number, y: number): Placed => {
    let cx = x;
    let height = 0;
    let right = x;
    let id = targetOf(graph, owner, port);
    while (id) {
      const node = graph.byId.get(id)!;
      const size = sizeOf(node);
      auto.set(id, { x: cx, y });
      const branchX = cx + NODE_W + GAP_X;
      let cursor = y + size.h + GAP_Y;
      let blockWidth = 0;
      let any = false;
      for (const next of node.ports) {
        if (next.id === 'after' || !targetOf(graph, id, next.id)) continue;
        const placed = chain(id, next.id, branchX, cursor);
        cursor += placed.h + GAP_Y;
        blockWidth = Math.max(blockWidth, placed.w);
        any = true;
      }
      const extent = any ? cursor - GAP_Y - y : size.h;
      height = Math.max(height, extent, size.h);
      right = Math.max(right, cx + NODE_W, any ? branchX + blockWidth : 0);
      cx = any ? branchX + blockWidth + GAP_X : cx + NODE_W + GAP_X;
      id = targetOf(graph, id, 'after');
    }
    return { w: Math.max(0, right - x), h: height };
  };

  let y = 0;
  const lanes = graph.nodes.filter((node) => node.kind === 'trigger' || node.kind === 'entry');
  for (const head of lanes) {
    auto.set(head.id, { x: 0, y });
    const own = sizeOf(head);
    const placed = chain(head.id, 'out', NODE_W + GAP_X, y);
    y += Math.max(own.h, placed.h) + LANE_GAP;
  }

  const rects = new Map<string, Rect>();
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const node of graph.nodes) {
    const at = overrides.get(node.id) ?? node.position ?? auto.get(node.id) ?? { x: 0, y: 0 };
    const size = sizeOf(node);
    const rect = { x: at.x, y: at.y, w: size.w, h: size.h };
    rects.set(node.id, rect);
    minX = Math.min(minX, rect.x);
    minY = Math.min(minY, rect.y);
    maxX = Math.max(maxX, rect.x + rect.w);
    maxY = Math.max(maxY, rect.y + rect.h);
  }
  const bounds = rects.size === 0 ? { x: 0, y: 0, w: 0, h: 0 } : { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
  return { rects, auto, bounds };
}

// ------------------------------------------------------------------ wires

/** Where a port's wire leaves the node (the right edge) and where a node's wire arrives (the left edge). */
export function outputAnchor(graph: Graph, rects: ReadonlyMap<string, Rect>, node: string, port: string): Point {
  const rect = rects.get(node)!;
  const index = Math.max(0, graph.byId.get(node)!.ports.findIndex((entry) => entry.id === port));
  return { x: rect.x + rect.w, y: rect.y + portOffset(index) };
}

export function inputAnchor(rects: ReadonlyMap<string, Rect>, node: string): Point {
  const rect = rects.get(node)!;
  return { x: rect.x, y: rect.y + INPUT_OFFSET };
}

/**
 * The orthogonal polyline of a wire from `from` to `to`. A wire that runs forward bends once in the gap
 * between the nodes (on a lane of its own per port, so a switch's wires do not overlap); one that has to
 * run back goes round above or below.
 */
export function route(from: Point, to: Point, lane = 0): Point[] {
  const stub = GRID * 4;
  if (to.x >= from.x + stub * 2) {
    const mid = snap(from.x + stub + lane * GRID * 2);
    if (from.y === to.y) return [from, to];
    return [from, { x: mid, y: from.y }, { x: mid, y: to.y }, to];
  }
  const around = snap(Math.max(from.y, to.y) + ROW * 2);
  return [
    from,
    { x: from.x + stub, y: from.y },
    { x: from.x + stub, y: around },
    { x: to.x - stub, y: around },
    { x: to.x - stub, y: to.y },
    to,
  ];
}

export interface Wire {
  edge: GraphEdge;
  points: Point[];
  /** The category colour of the node the wire leaves, so a lane keeps its colour. */
  source: string;
}

/** Every exec wire of the graph, routed over the layout. */
export function wires(graph: Graph, rects: ReadonlyMap<string, Rect>): Wire[] {
  return graph.edges.map((edge) => {
    const node = graph.byId.get(edge.from)!;
    const lane = Math.max(0, node.ports.findIndex((port) => port.id === edge.port));
    return {
      edge,
      points: route(outputAnchor(graph, rects, edge.from, edge.port), inputAnchor(rects, edge.to), lane),
      source: edge.from,
    };
  });
}

/** An SVG path (`M x y L x y ...`) through the points. */
export function pathOf(points: readonly Point[]): string {
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x} ${point.y}`).join(' ');
}

/** Whether the port is occupied: it already feeds a node. */
export function occupied(graph: Graph, node: string, port: string): boolean {
  return graph.leaving.has(portKey(node, port));
}
