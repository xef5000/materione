/**
 * The graph projection (logic.md §7.2): rules and steps project to a graph and back without losing a
 * byte (master §5.6), over every logic fixture in the repository and the studio's own; the layout is
 * deterministic and honours a stored position; every node has at most one input and every port at most
 * one wire, by construction.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { layout, NODE_W, route, sizeOf, wires } from '../src/lib/logic/layout';
import { project, unproject, descendants, tailOf, targetOf } from '../src/lib/logic/project';
import { blockOf, blockProblems, isRecord, type LogicBlock } from '../src/lib/logic/tree';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));

function yamlFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return yamlFiles(path);
    return /\.ya?ml$/.test(name) ? [path] : [];
  });
}

interface Case {
  name: string;
  block: LogicBlock;
}

/** Every resource body in the repository's fixtures and the studio's own that holds behavior or flows. */
function cases(): Case[] {
  const files = [...yamlFiles(join(ROOT, 'fixtures')), ...yamlFiles(fileURLToPath(new URL('./fixtures/logic', import.meta.url)))];
  const out: Case[] = [];
  for (const file of files) {
    let parsed: unknown;
    try {
      parsed = parse(readFileSync(file, 'utf8'));
    } catch {
      continue; // a fixture that is not YAML on purpose (a fault corpus)
    }
    if (!isRecord(parsed)) continue;
    for (const [id, body] of Object.entries(parsed)) {
      if (!isRecord(body) || (body.behavior === undefined && body.flows === undefined)) continue;
      out.push({ name: `${relative(ROOT, file).replaceAll('\\', '/')}#${id}`, block: blockOf(body) });
    }
  }
  return out;
}

const all = cases();

describe('projection round trip', () => {
  it('finds the fixtures, not an empty directory', () => {
    expect(all.length).toBeGreaterThanOrEqual(10);
    expect(all.some((entry) => entry.name.endsWith('shop.yml#shop'))).toBe(true);
    expect(all.some((entry) => entry.name.includes('guilogic'))).toBe(true);
  });

  it.each(all.map((entry) => [entry.name, entry] as const))('%s: project then unproject is the same tree, key order included', (_name, entry) => {
    const graph = project(entry.block);
    const back = unproject(graph);
    expect(JSON.stringify(back)).toBe(JSON.stringify(entry.block));
    expect(back).toEqual(entry.block);
  });

  it.each(all.map((entry) => [entry.name, entry] as const))('%s: no join, no fan-out, no cycle', (_name, entry) => {
    const graph = project(entry.block);
    const fed = new Set<string>();
    const fromPort = new Set<string>();
    for (const edge of graph.edges) {
      expect(fed.has(edge.to), `${edge.to} is fed twice`).toBe(false);
      fed.add(edge.to);
      const key = `${edge.from}|${edge.port}`;
      expect(fromPort.has(key), `${key} feeds two nodes`).toBe(false);
      fromPort.add(key);
    }
    for (const node of graph.nodes) expect(descendants(graph, node.id).has(node.id)).toBe(false);
    // every step is reachable from exactly one lane head
    const heads = graph.nodes.filter((node) => node.kind === 'trigger' || node.kind === 'entry');
    const reached = new Set<string>(heads.map((head) => head.id));
    for (const head of heads) for (const id of descendants(graph, head.id)) reached.add(id);
    expect(reached.size).toBe(graph.nodes.length);
  });

  it('the shop flow projects to the nodes and ports of logic.md §7.2', () => {
    const shop = all.find((entry) => entry.name.endsWith('shop.yml#shop'))!;
    const graph = project(shop.block);
    const byOp = (op: string) => graph.nodes.filter((node) => node.op === op);
    expect(graph.byId.get('behavior.0')!.kind).toBe('trigger');
    const branch = graph.byId.get('behavior.0.do.0')!;
    expect(branch.kind).toBe('if');
    expect(branch.ports.map((port) => port.id)).toEqual(['after', 'then', 'else']);
    expect(targetOf(graph, branch.id, 'then')).toBe('behavior.0.do.0.then.0');
    expect(targetOf(graph, branch.id, 'else')).toBe('behavior.0.do.0.else.0');
    expect(targetOf(graph, branch.id, 'after')).toBe('behavior.0.do.1');
    expect(graph.byId.get('behavior.0.do.1')!.op).toBe('gui.refresh'); // a bare name is an action node
    const guard = graph.byId.get('behavior.1.do.0')!;
    expect(guard.kind).toBe('require');
    expect(guard.ports.map((port) => port.id)).toEqual(['after', 'steps', 'failed']);
    const choose = graph.byId.get('behavior.1.do.1')!;
    expect(choose.kind).toBe('choose');
    expect(choose.ports.map((port) => port.id)).toEqual(['after', 'case:0', 'case:1', 'else']);
    expect(targetOf(graph, choose.id, 'case:0')).toBe('behavior.1.do.1.choose.0.then.0');
    expect(targetOf(graph, choose.id, 'case:1')).toBeUndefined(); // an empty case is a free port
    expect(byOp('wait')).toHaveLength(2);
    expect(graph.byId.get('flows.purchase')!.kind).toBe('entry');
    expect(graph.byId.get('flows.empty')!.kind).toBe('entry');
    expect(tailOf(graph, 'behavior.1.do.2')).toEqual(['behavior.1.do.2', 'behavior.1.do.3', 'behavior.1.do.4']);
  });

  it('a rule keeps its filters as options and its condition apart', () => {
    const shop = all.find((entry) => entry.name.endsWith('shop.yml#shop'))!;
    const graph = project(shop.block);
    expect(graph.byId.get('behavior.0')!.options).toEqual({ layer: 'buy', click: ['left', 'shift_left'], id: 'buy_sword', cooldown: 500 });
    expect(graph.byId.get('behavior.1')!.condition).toBe('${player.level >= 10}');
    expect(graph.byId.get('behavior.1')!.position).toEqual({ x: 40, y: 400 });
  });

  it('blocks that cannot be drawn faithfully are reported', () => {
    expect(blockProblems({ behavior: 'x' })).toEqual(['behavior: is not a list of rules']);
    expect(blockProblems({ behavior: [{ on: 'a', do: 'x' }], flows: { f: 1 } })).toEqual([
      'behavior.0.do is not a list of steps',
      'flows.f is not a list of steps',
    ]);
    expect(blockProblems({ behavior: [{ on: 'a', do: [] }] })).toEqual([]);
  });
});

describe('layout', () => {
  const shop = all.find((entry) => entry.name.endsWith('shop.yml#shop'))!;

  it('is deterministic', () => {
    const a = layout(project(shop.block));
    const b = layout(project(structuredClone(shop.block)));
    expect([...a.rects]).toEqual([...b.rects]);
  });

  it('a stored position wins, and only for that node', () => {
    const graph = project(shop.block);
    const result = layout(graph);
    expect(result.rects.get('behavior.1')).toMatchObject({ x: 40, y: 400 });
    expect(result.rects.get('flows.purchase.1')).toMatchObject({ x: 500, y: 700 });
    expect(result.auto.get('behavior.1')).not.toEqual({ x: 40, y: 400 });
    // its chain is still where the layout put it
    expect(result.rects.get('behavior.1.do.0')).toEqual(layout(project({ ...shop.block, behavior: shop.block.behavior!.map((rule, index) => (index === 1 ? { ...(rule as object), node: undefined } : rule)) })).rects.get('behavior.1.do.0'));
  });

  it('a drag in flight beats the stored position', () => {
    const graph = project(shop.block);
    const result = layout(graph, new Map([['behavior.1', { x: 8, y: 12 }]]));
    expect(result.rects.get('behavior.1')).toMatchObject({ x: 8, y: 12 });
  });

  it('automatic placement never overlaps two nodes (fixtures without stored positions)', () => {
    for (const entry of all) {
      const graph = project(entry.block);
      if (graph.nodes.some((node) => node.position)) continue;
      const rects = [...layout(graph).rects.entries()];
      for (let i = 0; i < rects.length; i++) {
        for (let j = i + 1; j < rects.length; j++) {
          const [idA, a] = rects[i]!;
          const [idB, b] = rects[j]!;
          const apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y;
          expect(apart, `${entry.name}: ${idA} overlaps ${idB}`).toBe(true);
        }
      }
    }
  });

  it('a lane runs left to right and branches hang below', () => {
    const graph = project(shop.block);
    const { rects } = layout(graph);
    const trigger = rects.get('behavior.0')!;
    const branch = rects.get('behavior.0.do.0')!;
    const thenHead = rects.get('behavior.0.do.0.then.0')!;
    const next = rects.get('behavior.0.do.1')!;
    expect(branch.x).toBeGreaterThan(trigger.x + trigger.w);
    expect(branch.y).toBe(trigger.y);
    expect(thenHead.y).toBeGreaterThan(branch.y + sizeOf(graph.byId.get('behavior.0.do.0')!).h - 1);
    expect(next.y).toBe(branch.y); // the lane continues level
    expect(next.x).toBeGreaterThan(thenHead.x + NODE_W);
  });

  it('wires are orthogonal polylines on the grid, one per edge', () => {
    const graph = project(shop.block);
    const { rects } = layout(graph);
    const all = wires(graph, rects);
    expect(all).toHaveLength(graph.edges.length);
    for (const wire of all) {
      for (let i = 1; i < wire.points.length; i++) {
        const a = wire.points[i - 1]!;
        const b = wire.points[i]!;
        expect(a.x === b.x || a.y === b.y, `${wire.edge.id} has a diagonal`).toBe(true);
      }
    }
  });

  it('a wire that runs back goes round instead of through the node', () => {
    const points = route({ x: 400, y: 100 }, { x: 100, y: 100 });
    expect(points.length).toBeGreaterThan(4);
    expect(points[points.length - 1]).toEqual({ x: 100, y: 100 });
  });
});
