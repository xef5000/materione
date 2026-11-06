/**
 * The Recipes panel's pure rules (logic.md section 7.5, 33), against the real vocabulary the plugin records: reading the
 * registry, the recipe parameters as the inspector's typed fields, the starting values, the stand-ins used to find out
 * whether the server can run a recipe, and the plan that inserts the expanded rules as one undo step.
 */
import { describe, expect, it } from 'vitest';
import snapshot from '../../fixtures/golden/editor-demo-snapshot.json';
import { baseKind } from '../src/lib/logic/params';
import {
  missingRequired, paramInfo, planInsertRecipe, readRecipes, sendable, standIns, startingValues,
} from '../src/lib/logic/recipes/recipes';
import { logicExpand } from '../src/lib/logic/server';
import { applyEdits } from '../src/lib/logic/tree';
import { readVocabulary } from '../src/lib/logic/vocabulary';
import type { RpcChannel } from '../src/lib/api';

const vocabulary = readVocabulary(snapshot.describe.behavior)!;
const recipes = vocabulary.recipes;
const pay = recipes.get('pay_to_receive')!;

describe('reading the registry', () => {
  it('lists the eight v1 recipes the plugin registers, with their typed parameters', () => {
    expect([...recipes.keys()].sort()).toEqual([
      'back_button', 'close_button', 'confirm_dialog', 'paged_player_list', 'pay_to_receive', 'server_selector', 'toggle_setting', 'welcome_message',
    ]);
    expect(recipes.has('trash_slot')).toBe(false); // blocked on #248, not registered (logic.md section 30)
    expect(pay.displayName).toBe('Pay to receive');
    expect(pay.params.map((param) => [param.name, param.type, param.required])).toEqual([
      ['layer', 'string', true], ['price', 'number', true], ['currency', 'string', false],
      ['item', 'string', true], ['amount', 'integer', false], ['message', 'string', false],
    ]);
    expect(pay.params.find((param) => param.name === 'price')!.min).toBe(0.01);
    expect(recipes.get('toggle_setting')!.params.find((param) => param.type === 'variable')).toBeDefined();
    expect(pay.available).toBe(true);
  });

  it('a server that lists none, or an entry that says it is unavailable, is read as such', () => {
    expect(readRecipes(undefined).size).toBe(0);
    expect(readRecipes([{ displayName: 'no id' }]).size).toBe(0);
    const read = readRecipes([{ id: 'x', displayName: 'X', description: 'd', params: [], available: false, unavailableReason: 'no economy' }]);
    expect(read.get('x')).toMatchObject({ available: false, reason: 'no economy' });
  });
});

describe('a recipe parameter as a typed field', () => {
  it('draws each kind with the inspector\'s own field kinds', () => {
    const byName = (name: string) => paramInfo(pay.params.find((param) => param.name === name)!);
    expect(baseKind(byName('price'))).toBe('number');
    expect(baseKind(byName('amount'))).toBe('number');
    expect(byName('amount')).toMatchObject({ type: 'integer', min: 1, max: 2304, default: '1' });
    expect(baseKind(byName('item'))).toBe('template');
    expect(baseKind(paramInfo(recipes.get('toggle_setting')!.params.find((param) => param.type === 'variable')!))).toBe('variable');
    expect(baseKind(paramInfo({ name: 'on', type: 'boolean', required: false, description: '' }))).toBe('boolean');
  });

  it('offers the GUI\'s layers for a parameter that names a layer, and only then', () => {
    const layers = ['frame', 'buy', 'sword'];
    const layer = paramInfo(pay.params[0]!, layers);
    expect(baseKind(layer)).toBe('enum');
    expect(layer.allowedValues).toEqual(layers);
    expect(baseKind(paramInfo(pay.params[0]!, []))).toBe('template'); // no layers known: free text
    expect(baseKind(paramInfo(pay.params.find((param) => param.name === 'item')!, layers))).toBe('template');
    const list = recipes.get('paged_player_list')!;
    for (const name of ['list', 'previous', 'next']) expect(baseKind(paramInfo(list.params.find((param) => param.name === name)!, layers))).toBe('enum');
  });
});

describe('the values of the form', () => {
  it('start from the defaults, and the layer selected in Design', () => {
    expect(startingValues(pay)).toEqual({ amount: 1, message: '<red>You cannot afford that.' });
    expect(startingValues(pay, 'buy')).toMatchObject({ layer: 'buy' });
    expect(startingValues(recipes.get('welcome_message')!, 'buy')).toEqual({}); // it has no layer parameter
  });

  it('say which required ones are blank, and leave blank optional ones out of the request', () => {
    expect(missingRequired(pay, startingValues(pay))).toEqual(['layer', 'price', 'item']);
    const values = { ...startingValues(pay, 'buy'), price: 250, item: 'minecraft:diamond', currency: '' };
    expect(missingRequired(pay, values)).toEqual([]);
    expect(sendable(pay, values)).toEqual({ layer: 'buy', price: 250, item: 'minecraft:diamond', amount: 1, message: '<red>You cannot afford that.' });
    expect(missingRequired(pay, { ...values, price: 0 })).toEqual([]); // zero is a value; the server checks the range
  });
});

describe('the stand-ins that find out whether the server can run a recipe', () => {
  it('fill each required parameter with a value of its kind, and give up on a variable', () => {
    expect(standIns(pay)).toEqual({ layer: 'stand_in', price: 1, item: 'stand_in' });
    expect(standIns(recipes.get('close_button')!)).toEqual({ layer: 'stand_in' });
    expect(standIns(recipes.get('toggle_setting')!)).toBeNull();
  });
});

describe('inserting the expanded rules', () => {
  const block = { behavior: [{ on: 'gui.open', do: [] }] };

  it('appends every rule at the end as one plan, selects the first, and is one undo step', () => {
    const rules = [{ on: 'gui.click', layer: 'a', do: ['gui.back'] }, { on: 'gui.click', layer: 'b', do: [] }];
    const plan = planInsertRecipe(block, rules, 'Paged player list');
    expect(plan.ok).toBe(true);
    if (!plan.ok) return;
    expect(plan.edits.map((edit) => edit.op)).toEqual(['addRule', 'addRule']);
    expect(plan.select).toBe('behavior.1');
    expect(plan.label).toBe('Insert Paged player list');
    expect(applyEdits(block, plan.edits).behavior).toEqual([{ on: 'gui.open', do: [] }, ...rules]);
  });

  it('refuses an expansion with no rules, and copies the rules it inserts', () => {
    expect(planInsertRecipe(block, [], 'X').ok).toBe(false);
    const rule = { on: 'gui.open', do: [{ message: 'a' }] };
    const plan = planInsertRecipe({}, [rule], 'X');
    rule.do.push({ message: 'changed later' });
    expect(plan.ok && applyEdits({}, plan.edits).behavior).toEqual([{ on: 'gui.open', do: [{ message: 'a' }] }]);
  });
});

describe('logic.instantiate with its warnings', () => {
  const channel = (reply: unknown) => ({ call: async () => reply }) as unknown as RpcChannel;

  it('answers the rules and the warnings of the expansion', async () => {
    const expanded = await logicExpand(channel({
      op: 'logic.instantiate', id: '1', ok: true,
      data: { rules: [{ on: 'gui.open' }], warnings: [{ code: 'MTR-0088', severity: 'warn', message: 'uses take_money' }] },
    }), 'pay_to_receive', {});
    expect(expanded.rules).toHaveLength(1);
    expect(expanded.warnings[0]!.code).toBe('MTR-0088');
  });

  it('an old reply with no warnings has none, and a refusal rejects', async () => {
    expect((await logicExpand(channel({ op: 'x', id: '1', ok: true, data: { rules: [] } }), 'x')).warnings).toEqual([]);
    await expect(logicExpand(channel({ op: 'x', id: '1', ok: false, error: { code: 'MTR-0086', message: 'price is required' } }), 'x')).rejects.toThrow(/price is required/);
  });
});
