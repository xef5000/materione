/**
 * The structural edits (logic.md §8): the local application mirrors the server's editor, every edit has an
 * inverse, the planner turns canvas gestures into edits and refuses what the file cannot say with a
 * one-line reason, and the log keeps the pending edits in step with the shared undo history.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { sampleGuiRaw } from '../src/lib/demo/gui';
import { buildCommand, isLogicCommand, withBlock, type LogicCommand } from '../src/lib/logic/command';
import { LogicLog } from '../src/lib/logic/log';
import {
  defaultSlot, planAddCase, planAddFlow, planAddRule, planConnect, planInsert, planMoveChain, planPosition,
  planRemove, planRemoveCase, planSetValue, slotOf, tipOf, valueKey, type NameOf, type Plan,
} from '../src/lib/logic/plan';
import { project } from '../src/lib/logic/project';
import type { LogicEdit } from '../src/lib/logic/server';
import { applyEdits, applyWithInverse, blockOf, LogicEditRefused, type LogicBlock } from '../src/lib/logic/tree';
import { parseGui } from '../src/lib/gui/model';

const yaml = (text: string): LogicBlock => blockOf((parse(text) as Record<string, Record<string, unknown>>).r!);
const name: NameOf = (node) => node.op || node.id;

const FLOW = `
r:
  behavior:
    - on: gui.open
      do:
        - sound: a
        - if: "\${x}"
          then:
            - message: yes
          else:
            - message: no
        - gui.close
    - on: gui.click
      do: []
  flows:
    f:
      - stop
`;

function ok(plan: Plan): Extract<Plan, { ok: true }> {
  if (!plan.ok) throw new Error(`refused: ${plan.reason}`);
  return plan;
}

function reason(plan: Plan): string {
  if (plan.ok) throw new Error('expected a refusal');
  return plan.reason;
}

describe('applyEdits mirrors the server editor', () => {
  it('the golden edit frame: a position and an insertion land as the server answered', () => {
    const golden = JSON.parse(readFileSync(new URL('../../fixtures/protocol/editor-logic-v1.json', import.meta.url), 'utf8'));
    const before: LogicBlock = { behavior: [{ on: 'click:right', do: [{ give: 'one' }, 'stop'] }] };
    const edits = golden.edit.request.payload.edits as LogicEdit[];
    const after = applyEdits(before, edits);
    expect(after).toEqual({
      behavior: [{ on: 'click:right', node: { x: 30, y: 40 }, do: [{ give: 'front' }, { give: 'one' }, 'stop'] }],
    });
    expect(before.behavior![0]).toEqual({ on: 'click:right', do: [{ give: 'one' }, 'stop'] }); // the input is untouched
  });

  it('inserts at an index, removes, and moves with the target read after the removal', () => {
    const block = yaml(FLOW);
    const inserted = applyEdits(block, [{ op: 'insertStep', path: 'behavior.0.do.1', step: { wait: 5 } }]);
    expect((inserted.behavior![0] as any).do[1]).toEqual({ wait: 5 });
    const removed = applyEdits(block, [{ op: 'removeStep', path: 'behavior.0.do.0' }]);
    expect((removed.behavior![0] as any).do).toHaveLength(2);
    // moving the first step to index 1 leaves it second: `to` is read against the list without it
    const moved = applyEdits(block, [{ op: 'moveStep', from: 'behavior.0.do.0', to: 'behavior.0.do.1' }]);
    expect((moved.behavior![0] as any).do.map((s: any) => (typeof s === 'string' ? s : Object.keys(s)[0]))).toEqual(['if', 'sound', 'gui.close']);
    const across = applyEdits(block, [{ op: 'moveStep', from: 'behavior.0.do.2', to: 'behavior.1.do.0' }]);
    expect((across.behavior![1] as any).do).toEqual(['gui.close']);
  });

  it('refuses what the server refuses, with the path', () => {
    const block = yaml(FLOW);
    const refused = (edit: LogicEdit) => {
      try {
        applyEdits(block, [edit]);
      } catch (error) {
        expect(error).toBeInstanceOf(LogicEditRefused);
        return error as LogicEditRefused;
      }
      throw new Error('not refused');
    };
    expect(refused({ op: 'insertStep', path: 'behavior.0.do.9', step: 'stop' }).message).toContain('outside 0..3');
    expect(refused({ op: 'insertStep', path: 'behavior.0', step: 'stop' }).message).toContain('rules are added');
    expect(refused({ op: 'removeStep', path: 'behavior.0.do.7' }).path).toBe('behavior.0.do.7');
    expect(refused({ op: 'moveStep', from: 'behavior.0.do.1', to: 'behavior.0.do.1.then.0' }).message).toContain('own subtree');
    expect(refused({ op: 'insertStep', path: 'behavior.0.do.0', step: { if: 'x' } }).message).toContain('then: list');
    expect(refused({ op: 'insertStep', path: 'behavior.0.do.0', step: { a: 1, b: 2 } }).message).toContain('one action');
    expect(refused({ op: 'patchStep', path: 'behavior.0.do.1', set: { then: [] } }).message).toContain('step edits');
    expect(refused({ op: 'setNodePosition', path: 'flows.f', x: 1, y: 2 }).message).toContain('only rules and steps');
    expect(refused({ op: 'insertStep', path: 'behavior.0.do.1.else.9', step: 'stop' }).path).toBe('behavior.0.do.1.else.9');
  });

  it('a refused move puts the step back', () => {
    const block = yaml(FLOW);
    expect(() => applyEdits(block, [{ op: 'moveStep', from: 'behavior.0.do.0', to: 'behavior.1.nowhere.0' }])).toThrow(LogicEditRefused);
    expect((block.behavior![0] as any).do).toHaveLength(3);
  });

  it('flows: add, rename (calls follow), remove', () => {
    let block = yaml(`${FLOW.trimEnd()}\n`);
    block = applyEdits(block, [{ op: 'insertStep', path: 'behavior.1.do.0', step: { call: 'f' } }]);
    block = applyEdits(block, [{ op: 'renameFlow', from: 'f', to: 'g' }]);
    expect((block.behavior![1] as any).do[0]).toEqual({ call: 'g' });
    expect(Object.keys(block.flows!)).toEqual(['g']);
    block = applyEdits(block, [{ op: 'removeFlow', id: 'g' }]);
    expect(block.flows).toBeUndefined();
  });
});

describe('every edit has an inverse', () => {
  const edits: LogicEdit[][] = [
    [{ op: 'insertStep', path: 'behavior.0.do.1', step: { wait: 5 } }],
    [{ op: 'removeStep', path: 'behavior.0.do.1' }],
    [{ op: 'moveStep', from: 'behavior.0.do.0', to: 'behavior.1.do.0' }],
    [{ op: 'moveStep', from: 'behavior.0.do.1.then.0', to: 'behavior.0.do.1.else.1' }],
    [{ op: 'patchStep', path: 'behavior.0.do.0', set: { sound: 'b', chance: 50 } }],
    [{ op: 'setNodePosition', path: 'behavior.0', x: 1, y: 2 }],
    [{ op: 'setNodePosition', path: 'behavior.1', x: 5, y: 6 }, { op: 'setNodePosition', path: 'behavior.1', x: 7, y: 8 }],
    [{ op: 'addRule', index: 1, rule: { on: 'gui.close', do: [] } }],
    [{ op: 'removeRules', indices: [0, 1] }],
    [{ op: 'reorderRules', order: [1, 0] }],
    [{ op: 'patchRule', index: 0, set: { cooldown: 5 } }],
    [{ op: 'addFlow', id: 'h', steps: ['stop'] }],
    [{ op: 'removeFlow', id: 'f' }],
    [{ op: 'renameFlow', from: 'f', to: 'z' }],
    [
      { op: 'removeStep', path: 'behavior.0.do.1' },
      { op: 'insertStep', path: 'behavior.0.do.1', step: { if: 'y', then: [], else: [] } },
      { op: 'moveStep', from: 'behavior.0.do.2', to: 'behavior.0.do.1.then.0' },
    ],
  ];

  it.each(edits.map((list, index) => [index, list] as const))('edit batch %i applies and takes itself back', (_index, list) => {
    const block = yaml(FLOW);
    const { block: after, inverse } = applyWithInverse(block, list);
    expect(after).not.toEqual(block);
    expect(applyEdits(after, inverse)).toEqual(block);
  });
});

describe('planning gestures against the graph', () => {
  const block = yaml(FLOW);
  const graph = project(block);

  it('slots: a branch inserts at its head, `after` right behind the node', () => {
    expect(slotOf(graph, 'behavior.0.do.1', 'then')).toMatchObject({ list: 'behavior.0.do.1.then', index: 0, exists: true });
    expect(slotOf(graph, 'behavior.0.do.0', 'after')).toMatchObject({ list: 'behavior.0.do', index: 1 });
    expect(slotOf(graph, 'behavior.0', 'out')).toMatchObject({ list: 'behavior.0.do', index: 0 });
    expect(tipOf(graph, 'behavior.0')).toMatchObject({ list: 'behavior.0.do', index: 3 });
    expect(tipOf(graph, 'behavior.1')).toMatchObject({ list: 'behavior.1.do', index: 0 });
    expect(defaultSlot(graph, 'behavior.0.do.2')).toMatchObject({ list: 'behavior.0.do', index: 3 });
  });

  it('inserts into a free port and splices into an occupied one', () => {
    const free = ok(planInsert(graph, block, slotOf(graph, 'behavior.1', 'out')!, { sound: '' }, 'Add Sound'));
    expect(free.edits).toEqual([{ op: 'insertStep', path: 'behavior.1.do.0', step: { sound: '' } }]);
    expect(free.select).toBe('behavior.1.do.0');
    const splice = ok(planInsert(graph, block, slotOf(graph, 'behavior.0.do.0', 'after')!, { wait: 5 }, 'Add Wait'));
    expect(splice.edits[0]).toEqual({ op: 'insertStep', path: 'behavior.0.do.1', step: { wait: 5 } });
  });

  it('writing to a branch the file does not have yet replaces the step with itself plus the list', () => {
    const noElse = yaml(`r:\n  behavior:\n    - on: gui.open\n      do:\n        - if: x\n          then: [stop]\n          node: {x: 4, y: 8}\n`);
    const g = project(noElse);
    const plan = ok(planInsert(g, noElse, slotOf(g, 'behavior.0.do.0', 'else')!, 'gui.close', 'Add Close'));
    expect(plan.edits).toEqual([
      { op: 'removeStep', path: 'behavior.0.do.0' },
      { op: 'insertStep', path: 'behavior.0.do.0', step: { if: 'x', then: ['stop'], node: { x: 4, y: 8 }, else: [] } },
      { op: 'insertStep', path: 'behavior.0.do.0.else.0', step: 'gui.close' },
    ]);
    const after = applyEdits(noElse, plan.edits);
    expect((after.behavior![0] as any).do[0].else).toEqual(['gui.close']);
    expect(project(after).byId.get('behavior.0.do.0.else.0')!.op).toBe('gui.close');
  });

  it('adds a rule at the end and a flow with a free name', () => {
    expect(ok(planAddRule(block, { on: 'gui.close', do: [] }, 'Add')).edits).toEqual([
      { op: 'addRule', index: 2, rule: { on: 'gui.close', do: [] } },
    ]);
    expect(ok(planAddFlow(block)).edits).toEqual([{ op: 'addFlow', id: 'flow', steps: [] }]);
    expect(ok(planAddFlow(yaml(FLOW), 'f')).edits).toEqual([{ op: 'addFlow', id: 'f_2', steps: [] }]);
  });

  it('removes a step (selecting what fed it), a rule and a flow', () => {
    expect(ok(planRemove(graph, 'behavior.0.do.1', name))).toMatchObject({
      edits: [{ op: 'removeStep', path: 'behavior.0.do.1' }],
      select: 'behavior.0.do.0',
    });
    expect(ok(planRemove(graph, 'behavior.1', name)).edits).toEqual([{ op: 'removeRules', indices: [1] }]);
    expect(ok(planRemove(graph, 'flows.f', name)).edits).toEqual([{ op: 'removeFlow', id: 'f' }]);
  });

  it('positions a node; a flow entry has none of its own', () => {
    expect(ok(planPosition(graph, 'behavior.0.do.0', 12, 16)).edits).toEqual([
      { op: 'setNodePosition', path: 'behavior.0.do.0', x: 12, y: 16 },
    ]);
    expect(reason(planPosition(graph, 'flows.f', 1, 2))).toContain('no position of its own');
  });

  it('edits a primary value: an action, a condition, a trigger', () => {
    expect(ok(planSetValue(graph, 'behavior.0.do.0', 'z', name)).edits).toEqual([
      { op: 'patchStep', path: 'behavior.0.do.0', set: { sound: 'z' } },
    ]);
    expect(ok(planSetValue(graph, 'behavior.0.do.1', '${y}', name)).edits).toEqual([
      { op: 'patchStep', path: 'behavior.0.do.1', set: { if: '${y}' } },
    ]);
    expect(ok(planSetValue(graph, 'behavior.1', 'gui.tick', name)).edits[0]).toMatchObject({ op: 'patchRule', index: 1 });
    expect(valueKey(graph.byId.get('behavior.0.do.2')!)).toBeNull(); // a bare step
    expect(reason(planSetValue(graph, 'behavior.0.do.2', 'x', name))).toContain('no single value');
  });

  describe('moving a chain', () => {
    it('lifts the node and the steps behind it, and they land in order', () => {
      const plan = ok(planMoveChain(graph, block, 'behavior.0.do.1', slotOf(graph, 'behavior.1', 'out')!, name));
      expect(plan.edits).toEqual([
        { op: 'moveStep', from: 'behavior.0.do.1', to: 'behavior.1.do.0' },
        { op: 'moveStep', from: 'behavior.0.do.1', to: 'behavior.1.do.1' },
      ]);
      const after = applyEdits(block, plan.edits);
      expect((after.behavior![0] as any).do).toHaveLength(1);
      expect((after.behavior![1] as any).do).toHaveLength(2);
      expect(plan.select).toBe('behavior.1.do.0');
    });

    it('into a branch the file does not have yet', () => {
      const lonely = yaml('r:\n  behavior:\n    - on: gui.open\n      do:\n        - if: x\n          then: [stop]\n        - gui.close\n');
      const g = project(lonely);
      const plan = ok(planMoveChain(g, lonely, 'behavior.0.do.1', slotOf(g, 'behavior.0.do.0', 'else')!, name));
      expect(plan.edits).toEqual([
        { op: 'removeStep', path: 'behavior.0.do.0' },
        { op: 'insertStep', path: 'behavior.0.do.0', step: { if: 'x', then: ['stop'], else: [] } },
        { op: 'moveStep', from: 'behavior.0.do.1', to: 'behavior.0.do.0.else.0' },
      ]);
      const after = applyEdits(lonely, plan.edits);
      expect((after.behavior![0] as any).do).toEqual([{ if: 'x', then: ['stop'], else: ['gui.close'] }]);
    });

    it('dropping a chain where it already is changes nothing', () => {
      expect(ok(planMoveChain(graph, block, 'behavior.0.do.1', slotOf(graph, 'behavior.0.do.0', 'after')!, name)).edits).toEqual([]);
    });

    it('refuses a port that is taken (fan-out) and a loop (cycle)', () => {
      expect(reason(planMoveChain(graph, block, 'behavior.0.do.2', slotOf(graph, 'behavior.0.do.0', 'after')!, name)))
        .toBe("sound 'after' already feeds if; a port feeds one node.");
      expect(reason(planMoveChain(graph, block, 'behavior.0.do.1', slotOf(graph, 'behavior.0.do.1.then.0', 'after')!, name)))
        .toBe('That would make a loop: if already leads to message.');
      expect(reason(planMoveChain(graph, block, 'behavior.0', slotOf(graph, 'behavior.1', 'out')!, name))).toContain('starts a flow');
    });
  });

  describe('connecting a wire from an output port to a node', () => {
    it('refuses a join: a node has one input', () => {
      expect(reason(planConnect(graph, block, { node: 'behavior.1', port: 'out' }, 'behavior.0.do.2', name)))
        .toBe('gui.close is already fed by if; a node has one input (drag from its input to move it).');
    });

    it('refuses fan-out: a port feeds one node', () => {
      expect(reason(planConnect(graph, block, { node: 'behavior.0.do.1', port: 'then' }, 'behavior.0.do.2', name)))
        .toBe("if 'then' already feeds message; fan-out is not allowed.");
    });

    it('refuses a cycle', () => {
      expect(reason(planConnect(graph, block, { node: 'behavior.0.do.1.then.0', port: 'after' }, 'behavior.0.do.1', name)))
        .toBe('That would make a loop: if already leads to message.');
      expect(reason(planConnect(graph, block, { node: 'behavior.0.do.0', port: 'after' }, 'behavior.0.do.0', name))).toContain('loop');
    });

    it('refuses to feed a trigger', () => {
      expect(reason(planConnect(graph, block, { node: 'behavior.1', port: 'out' }, 'behavior.0', name))).toContain('starts a flow');
    });

    it('connecting what is already connected is a no-op', () => {
      expect(ok(planConnect(graph, block, { node: 'behavior.0.do.0', port: 'after' }, 'behavior.0.do.1', name)).edits).toEqual([]);
    });
  });

  describe('a switch', () => {
    const switchBlock = yaml(`r:\n  behavior:\n    - on: gui.open\n      do:\n        - choose:\n            - if: a\n              then: [stop]\n`);
    const sg = project(switchBlock);

    it('adds and removes a case by replacing the step', () => {
      const add = ok(planAddCase(sg, switchBlock, 'behavior.0.do.0', name));
      const after = applyEdits(switchBlock, add.edits);
      expect(project(after).byId.get('behavior.0.do.0')!.ports.map((p) => p.id)).toEqual(['after', 'case:0', 'case:1', 'else']);
      const removed = ok(planRemoveCase(project(after), after, 'behavior.0.do.0', name));
      expect(applyEdits(after, removed.edits)).toEqual(switchBlock);
      expect(reason(planAddCase(graph, block, 'behavior.0.do.0', name))).toContain('Only a choose');
    });
  });
});

describe('the history command', () => {
  const doc = () => parseGui(parse(sampleGuiRaw()), 'main')!.doc;

  it('applies and reverts the document exactly, key order included', () => {
    const base = withBlock(doc(), { behavior: [{ on: 'gui.open', do: ['gui.close'] }] });
    const built = buildCommand(base, ok(planInsert(project(blockOf(base.rest)), blockOf(base.rest), slotOf(project(blockOf(base.rest)), 'behavior.0', 'out')!, { sound: 'x' }, 'Add Sound')));
    if (!built.ok || !built.command) throw new Error('not built');
    const after = built.command.apply(base);
    expect((after.rest.behavior as any)[0].do).toEqual([{ sound: 'x' }, 'gui.close']);
    expect(JSON.stringify(built.command.revert(after))).toBe(JSON.stringify(base));
    expect(isLogicCommand(built.command)).toBe(true);
    expect(built.command.logic.inverse).toEqual([{ op: 'removeStep', path: 'behavior.0.do.0' }]);
  });

  it('adds the key to a document that had no logic, and takes it away again on revert', () => {
    const base = doc();
    expect('behavior' in base.rest).toBe(false);
    const block = blockOf(base.rest);
    const built = buildCommand(base, ok(planAddRule(block, { on: 'gui.open', do: [] }, 'Add rule')));
    if (!built.ok || !built.command) throw new Error('not built');
    const after = built.command.apply(base);
    expect(after.rest.behavior).toEqual([{ on: 'gui.open', do: [] }]);
    expect(JSON.stringify(built.command.revert(after))).toBe(JSON.stringify(base));
  });

  it('an empty plan builds no command, and a refused one says why', () => {
    expect(buildCommand(doc(), { ok: true, edits: [], label: 'x' })).toEqual({ ok: true, command: null });
    expect(buildCommand(doc(), { ok: false, reason: 'nope' })).toEqual({ ok: false, reason: 'nope' });
    const bad = buildCommand(doc(), { ok: true, edits: [{ op: 'removeStep', path: 'behavior.0.do.0' }], label: 'x' });
    expect(bad.ok).toBe(false);
  });
});

describe('the pending log follows the undo history', () => {
  function command(edits: LogicEdit[], inverse: LogicEdit[]): LogicCommand {
    return { label: 'x', logic: { edits, inverse }, apply: (d) => d, revert: (d) => d };
  }
  const insert = (n: number): LogicEdit => ({ op: 'insertStep', path: `behavior.0.do.${n}`, step: 'stop' });
  const remove = (n: number): LogicEdit => ({ op: 'removeStep', path: `behavior.0.do.${n}` });

  it('an unsaved command that is undone leaves nothing to send', () => {
    const log = new LogicLog();
    const a = command([insert(0)], [remove(0)]);
    log.executed(a);
    expect(log.edits()).toEqual([insert(0)]);
    log.undone(a);
    expect(log.pending).toBe(false);
    log.redone(a);
    expect(log.edits()).toEqual([insert(0)]);
  });

  it('a saved command that is undone sends its inverse, and redo sends it again', () => {
    const log = new LogicLog();
    const a = command([insert(0)], [remove(0)]);
    log.executed(a);
    log.saved();
    expect(log.pending).toBe(false);
    log.undone(a);
    expect(log.edits()).toEqual([remove(0)]);
    log.redone(a);
    expect(log.edits()).toEqual([remove(0), insert(0)]);
    log.saved();
    log.undone(a);
    expect(log.edits()).toEqual([remove(0)]);
  });

  it('a drag of one node folds its positions, but never across another edit', () => {
    const log = new LogicLog();
    const move = (x: number): LogicCommand => command([{ op: 'setNodePosition', path: 'behavior.0', x, y: 0 }], []);
    log.executed(move(1));
    log.executed(move(2));
    log.executed(command([insert(0)], [remove(0)]));
    log.executed(move(3));
    expect(log.edits()).toEqual([
      { op: 'setNodePosition', path: 'behavior.0', x: 2, y: 0 },
      insert(0),
      { op: 'setNodePosition', path: 'behavior.0', x: 3, y: 0 },
    ]);
  });

  it('says so when it falls out of step', () => {
    const log = new LogicLog();
    log.executed(command([insert(0)], [remove(0)]));
    log.undone(command([insert(1)], [remove(1)]));
    expect(log.desynced).toBe(true);
    expect(log.pending).toBe(true);
    log.saved();
    expect(log.desynced).toBe(false);
  });
});
