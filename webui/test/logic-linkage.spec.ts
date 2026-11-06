/**
 * The Design/Logic linkage (logic.md section 7.4, 33), as pure rules: which rules a layer has, where the compiler's verdict
 * on a rule is drawn, the plan for "Add click logic", and what becomes of a deleted layer's rules (delete them, or take
 * the layer filter off), with the shared-rule case and a single undo step for the layer and its rules together.
 */
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { sampleGuiRaw } from '../src/lib/demo/gui';
import { removeLayers } from '../src/lib/gui/commands';
import { parseGui } from '../src/lib/gui/model';
import { joinWithLogic } from '../src/lib/logic/linkage/command';
import {
  affectedBy, layerFlags, layerOfNode, layersOfRule, planAddClickLogic, planLayerDeletion, ruleCounts, rulesOfLayer,
} from '../src/lib/logic/linkage/layers';
import { applyEdits, applyWithInverse, blockOf, type LogicBlock } from '../src/lib/logic/tree';
import type { NodeFlag } from '../src/lib/logic/verdicts';

const BLOCK: LogicBlock = {
  behavior: [
    { on: 'gui.click', layer: 'buy', do: [{ message: 'hi' }] },
    { on: 'gui.open', do: ['gui.refresh'] },
    { on: 'gui.click', layer: ['apple', 'coin'], do: ['gui.close'] },
    { on: 'gui.click', layer: 'buy', do: [] },
    { on: 'gui.click', layer: ' sword ', do: [] },
  ],
};

describe('which layers a rule names', () => {
  it('reads a text, a list, nothing, and something that is not a layer', () => {
    expect(layersOfRule({ layer: 'buy' })).toEqual(['buy']);
    expect(layersOfRule({ layer: ['a', 'b'] })).toEqual(['a', 'b']);
    expect(layersOfRule({ layer: ' sword ' })).toEqual(['sword']); // the filter is trimmed
    expect(layersOfRule({ on: 'gui.open' })).toEqual([]);
    expect(layersOfRule({ layer: '' })).toEqual([]);
    expect(layersOfRule({ layer: 3 })).toEqual([]);
    expect(layersOfRule({ layer: ['a', 3, '', 'c'] })).toEqual(['a', 'c']);
    expect(layersOfRule('gui.close')).toEqual([]);
    expect(layersOfRule(null)).toEqual([]);
  });

  it('finds the rules of a layer and counts them once per layer', () => {
    expect(rulesOfLayer(BLOCK, 'buy')).toEqual([0, 3]);
    expect(rulesOfLayer(BLOCK, 'coin')).toEqual([2]);
    expect(rulesOfLayer(BLOCK, 'nothing')).toEqual([]);
    expect(rulesOfLayer({}, 'buy')).toEqual([]);
    const counts = ruleCounts(BLOCK);
    expect(counts.get('buy')).toBe(2);
    expect(counts.get('apple')).toBe(1);
    expect(counts.get('coin')).toBe(1);
    expect(counts.get('sword')).toBe(1);
    expect(ruleCounts({ behavior: [{ on: 'x', layer: ['a', 'a'] }] }).get('a')).toBe(1);
  });

  it('names the layer of a trigger node, and only of a trigger node', () => {
    expect(layerOfNode(BLOCK, 'behavior.0')).toBe('buy');
    expect(layerOfNode(BLOCK, 'behavior.2')).toBe('apple');
    expect(layerOfNode(BLOCK, 'behavior.1')).toBeNull();
    expect(layerOfNode(BLOCK, 'behavior.0.do.0')).toBeNull();
    expect(layerOfNode(BLOCK, 'behavior.9')).toBeNull();
    expect(layerOfNode(BLOCK, 'flows.x')).toBeNull();
  });
});

describe('the compiler\'s verdict, drawn on the layer', () => {
  const flag = (message: string, path: string): NodeFlag => ({ severity: 'warn', message, path });

  it('puts the flags of a rule\'s nodes on the layers the rule names', () => {
    const verdicts = new Map<string, NodeFlag[]>([
      ['behavior.0.do.0', [flag('empty', 'behavior.0.do.0')]],
      ['behavior.2', [flag('shared', 'behavior.2')]],
      ['behavior.1.do.0', [flag('not a layer rule', 'behavior.1.do.0')]],
      ['behavior.10.do.0', [flag('a different rule', 'behavior.10.do.0')]],
    ]);
    const flags = layerFlags(BLOCK, verdicts);
    expect(flags.get('buy')!.map((entry) => entry.message)).toEqual(['empty']);
    expect(flags.get('apple')!.map((entry) => entry.message)).toEqual(['shared']);
    expect(flags.get('coin')!.map((entry) => entry.message)).toEqual(['shared']);
    expect(flags.get('buy')![0]!.path).toBe('behavior.0.do.0'); // the step path stays on the flag
    expect(flags.has('sword')).toBe(false);
    expect(flags.size).toBe(3); // the rule with no layer filter belongs to no layer
  });

  it('does not mistake behavior.1 for behavior.10', () => {
    const flags = layerFlags(
      { behavior: [{ on: 'x' }, { on: 'x', layer: 'a' }, ...Array.from({ length: 8 }, () => ({ on: 'x' })), { on: 'x', layer: 'b' }] },
      new Map([['behavior.10.do.0', [flag('on 10', 'behavior.10.do.0')]]]),
    );
    expect(flags.has('a')).toBe(false);
    expect(flags.has('b')).toBe(true);
  });
});

describe('Add click logic', () => {
  it('plans a gui.click rule filtered to the layer, at the end, and selects its trigger', () => {
    const plan = planAddClickLogic(BLOCK, 'sword', 'Sword');
    expect(plan.ok).toBe(true);
    if (!plan.ok) return;
    expect(plan.edits).toEqual([{ op: 'addRule', index: 5, rule: { on: 'gui.click', layer: 'sword', do: [] } }]);
    expect(plan.select).toBe('behavior.5');
    expect(plan.label).toBe('Add click logic to Sword');
    expect(applyEdits(BLOCK, plan.edits).behavior!.at(-1)).toEqual({ on: 'gui.click', layer: 'sword', do: [] });
  });

  it('starts the first rule of a GUI with none', () => {
    const plan = planAddClickLogic({}, 'buy');
    expect(plan.ok && plan.select).toBe('behavior.0');
    expect(plan.ok && applyEdits({}, plan.edits).behavior).toEqual([{ on: 'gui.click', layer: 'buy', do: [] }]);
  });

  it('refuses without a layer', () => {
    expect(planAddClickLogic(BLOCK, '').ok).toBe(false);
  });
});

describe('deleting layers that have rules', () => {
  it('says which rules go with them and which are shared with a layer that stays', () => {
    expect(affectedBy(BLOCK, ['buy'])).toEqual({ exclusive: [0, 3], shared: [] });
    expect(affectedBy(BLOCK, ['apple'])).toEqual({ exclusive: [], shared: [2] });
    expect(affectedBy(BLOCK, ['apple', 'coin'])).toEqual({ exclusive: [2], shared: [] });
    expect(affectedBy(BLOCK, ['frame'])).toEqual({ exclusive: [], shared: [] });
  });

  it('delete: removes the rules filtered only to the deleted layers, and takes a deleted layer off a shared one', () => {
    const edits = planLayerDeletion(BLOCK, ['buy', 'apple'], 'delete');
    const after = applyEdits(BLOCK, edits).behavior!;
    expect(after).toEqual([
      { on: 'gui.open', do: ['gui.refresh'] },
      { on: 'gui.click', layer: ['coin'], do: ['gui.close'] },
      { on: 'gui.click', layer: ' sword ', do: [] },
    ]);
  });

  it('detach: keeps every rule and takes the deleted layers off the filters', () => {
    const edits = planLayerDeletion(BLOCK, ['buy', 'apple'], 'detach');
    const after = applyEdits(BLOCK, edits).behavior!;
    expect(after).toEqual([
      { on: 'gui.click', do: [{ message: 'hi' }] },
      { on: 'gui.open', do: ['gui.refresh'] },
      { on: 'gui.click', layer: ['coin'], do: ['gui.close'] },
      { on: 'gui.click', do: [] },
      { on: 'gui.click', layer: ' sword ', do: [] },
    ]);
    expect(edits.some((edit) => edit.op === 'removeRules')).toBe(false);
  });

  it('keeps unknown keys of a rule it detaches', () => {
    const block: LogicBlock = { behavior: [{ on: 'gui.click', layer: 'buy', mystery: { a: 1 }, do: [] }] };
    expect(applyEdits(block, planLayerDeletion(block, ['buy'], 'detach')).behavior).toEqual([{ on: 'gui.click', mystery: { a: 1 }, do: [] }]);
  });

  it('plans nothing for a layer no rule names', () => {
    expect(planLayerDeletion(BLOCK, ['frame'], 'delete')).toEqual([]);
    expect(planLayerDeletion(BLOCK, ['frame'], 'detach')).toEqual([]);
  });

  it('every plan has an inverse that restores the block (the undo of the deletion)', () => {
    for (const choice of ['delete', 'detach'] as const) {
      const edits = planLayerDeletion(BLOCK, ['buy', 'apple'], choice);
      const { block, inverse } = applyWithInverse(BLOCK, edits);
      expect(applyEdits(block, inverse).behavior).toEqual(BLOCK.behavior);
    }
  });
});

describe('the layer and its rules as one history entry', () => {
  function document() {
    const parsed = parse(sampleGuiRaw()) as Record<string, Record<string, unknown>>;
    parsed.main!.behavior = [
      { on: 'gui.click', layer: 'buy', do: [{ message: 'hi' }] },
      { on: 'gui.click', layer: 'sword', do: [] },
    ];
    return parseGui(parsed, 'main')!.doc;
  }

  it('applies the layer removal and the rule removal together, and reverts both', () => {
    const doc = document();
    const design = removeLayers(doc, ['buy'], 'Delete layer');
    const joined = joinWithLogic(doc, 'Delete layer and its rules', design, planLayerDeletion(blockOf(doc.rest), ['buy'], 'delete'));
    expect(joined.ok).toBe(true);
    if (!joined.ok || !joined.command) return;
    expect(joined.command.label).toBe('Delete layer and its rules');
    const after = joined.command.apply(doc);
    expect(after.layers.map((layer) => layer.id)).not.toContain('buy');
    expect(after.rest.behavior).toEqual([{ on: 'gui.click', layer: 'sword', do: [] }]);
    const back = joined.command.revert(after);
    expect(back.layers).toEqual(doc.layers);
    expect(back.rest.behavior).toEqual(doc.rest.behavior);
    // the logic half is what the studio replays on save
    expect((joined.command as unknown as { logic: { edits: { op: string }[] } }).logic.edits.map((edit) => edit.op)).toEqual(['removeRules']);
  });

  it('with no rules to change it is just the layer\'s own command', () => {
    const doc = document();
    const design = removeLayers(doc, ['frame'], 'Delete layer');
    const joined = joinWithLogic(doc, 'x', design, []);
    expect(joined).toEqual({ ok: true, command: design });
  });

  it('a refused logic edit refuses the whole deletion', () => {
    const doc = document();
    const joined = joinWithLogic(doc, 'x', removeLayers(doc, ['buy']), [{ op: 'removeRules', indices: [9] }]);
    expect(joined.ok).toBe(false);
  });
});
