/**
 * The node inspector's rules (logic.md section 7.3, 31): which field a parameter is drawn as, how a changed value is
 * written back, how the condition stack edits the authored condition, what the template field completes and flags, and
 * the plans an inspector edit becomes. All of it is pure and runs against the recorded vocabulary (the real one the
 * plugin's golden test writes), so the inspector is tested against what a server actually describes.
 */
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import snapshot from '../../fixtures/golden/editor-demo-snapshot.json';
import { attribute, worstOf } from '../src/lib/logic/diagnose';
import {
  addChild, convert, freshCondition, getAt, moveBy, pushRoot, removeAt, rowKindOf, setAt, setCount, setGroupOp, shapeOf,
  viewOf, wrap,
} from '../src/lib/logic/condition';
import { buildCommand } from '../src/lib/logic/command';
import { planCondition, planRenameFlow, planStepKey, planStepValue } from '../src/lib/logic/edit-plan';
import { baseKind, kindFor, numberValue, readPayload, unknownKeys, writeParam } from '../src/lib/logic/params';
import type { NameOf, Plan } from '../src/lib/logic/plan';
import { project } from '../src/lib/logic/project';
import { scopeFor } from '../src/lib/logic/scope';
import { applyCompletion, completeAt, lintTemplate } from '../src/lib/logic/template';
import { applyEdits, blockOf, type LogicBlock } from '../src/lib/logic/tree';
import { declarationEdits, declarationsOf, declareVariable, keyProblem, readPath, variableFiles } from '../src/lib/logic/variables';
import { readVocabulary, type ParamInfo } from '../src/lib/logic/vocabulary';

const vocabulary = readVocabulary(snapshot.describe.behavior)!;
const name: NameOf = (node) => node.op || node.id;
const yaml = (text: string): LogicBlock => blockOf((parse(text) as Record<string, Record<string, unknown>>).r!);

function ok(plan: Plan): Extract<Plan, { ok: true }> {
  if (!plan.ok) throw new Error(`refused: ${plan.reason}`);
  return plan;
}

const param = (id: string, extra: Partial<ParamInfo> = {}): ParamInfo => ({ id, type: 'string', required: false, ...extra });

describe('the vocabulary the inspector reads', () => {
  it('reads the registries added since the Logic tab: list sources, item sources, input methods and takesElse', () => {
    expect([...vocabulary.listSources.keys()]).toEqual(['online_players', 'pack_items', 'static', 'variable']);
    expect(vocabulary.listSources.get('online_players')!.entry.map((variable) => variable.path)).toEqual(['entry.name', 'entry.uuid']);
    expect([...vocabulary.itemSources.keys()]).toEqual(['head', 'item', 'player_slot']);
    expect(vocabulary.itemSources.get('player_slot')!.shorthand).toBe('slot');
    expect(vocabulary.actions.get('prompt')!.takesElse).toBe(true);
    expect(vocabulary.actions.get('message')!.takesElse).toBe(false);
  });

  it('lists an unavailable input method with the server\'s reason', () => {
    const sign = vocabulary.inputMethods.get('sign')!;
    expect(sign.available).toBe(false);
    expect(sign.reason).toContain('openVirtualSign');
    expect(vocabulary.inputMethods.get('chat')!.available).toBe(true);
    expect(vocabulary.inputMethods.get('anvil')!.closesWindow).toBe(true);
  });

  it('carries the trigger context schema, the function signatures and the widget hints', () => {
    const click = vocabulary.triggers.get('gui.click')!;
    expect(click.context.map((variable) => variable.path)).toContain('click.type');
    expect(click.context.find((variable) => variable.path === 'gui.args.*')!.type).toBe('string');
    expect(vocabulary.functions.find((fn) => fn.id === 'contains')!.parameters.length).toBeGreaterThan(0);
    expect(vocabulary.actions.get('give_item')!.parameters[0]!.uiHint).toBe('item');
    expect(vocabulary.actions.get('sound')!.parameters[0]!.uiHint).toBe('sound');
    expect(vocabulary.actions.get('set_var')!.parameters[0]!.uiHint).toBe('variable');
    expect(vocabulary.actions.get('prompt')!.parameters[0]!.uiHint).toBe('input-method');
    expect(vocabulary.ruleOptions.map((option) => option.id)).toContain('cooldown');
    expect(vocabulary.actionOptions.map((option) => option.id)).toEqual(['chance', 'delay']);
  });
});

describe('which field a parameter is drawn as', () => {
  it('follows the widget hint, then the allowed values, then the type', () => {
    expect(baseKind(param('item', { uiHint: 'item' }))).toBe('item');
    expect(baseKind(param('sound', { uiHint: 'sound' }))).toBe('sound');
    expect(baseKind(param('var', { uiHint: 'variable' }))).toBe('variable');
    expect(baseKind(param('method', { uiHint: 'input-method', allowedValues: ['chat'] }))).toBe('inputMethod');
    expect(baseKind(param('color', { uiHint: 'color' }))).toBe('color');
    expect(baseKind(param('op', { allowedValues: ['a', 'b'] }))).toBe('enum');
    expect(baseKind(param('on', { type: 'boolean' }))).toBe('boolean');
    expect(baseKind(param('n', { type: 'integer' }))).toBe('number');
    expect(baseKind(param('x', { type: 'decimal' }))).toBe('number');
    expect(baseKind(param('l', { type: 'list' }))).toBe('list');
    expect(baseKind(param('r', { type: 'raw' }))).toBe('raw');
    expect(baseKind(param('gui', { type: 'reference' }))).toBe('reference');
    expect(baseKind(param('text'))).toBe('template');
  });

  it('every parameter of the real vocabulary gets a field', () => {
    for (const entry of [...vocabulary.actions.values(), ...vocabulary.conditions.values()]) {
      for (const each of entry.parameters) expect(baseKind(each), `${entry.id}.${each.id}`).toBeTruthy();
    }
    for (const trigger of vocabulary.triggers.values()) for (const each of trigger.filters) expect(baseKind(each)).toBeTruthy();
  });

  it('a value the field cannot hold falls back to one that can, and is never lost', () => {
    const amount = param('amount', { type: 'integer' });
    expect(kindFor(amount, 5).kind).toBe('number');
    expect(kindFor(amount, '5').kind).toBe('number');
    expect(kindFor(amount, '${price}').kind).toBe('template');
    expect(kindFor(param('on', { type: 'boolean' }), 'true').kind).toBe('boolean');
    expect(kindFor(param('on', { type: 'boolean' }), '${x}').kind).toBe('template');
    expect(kindFor(param('op', { allowedValues: ['a'] }), 'zzz').kind).toBe('template');
    expect(kindFor(param('layer'), ['a', 'b'])).toEqual({ kind: 'list', element: 'template' });
    expect(kindFor(param('item', { uiHint: 'item' }), ['a'])).toEqual({ kind: 'list', element: 'item' });
    expect(kindFor(param('text'), { odd: true }).kind).toBe('raw');
    expect(kindFor(param('x', { uiHint: 'made-up-hint' }), 'v').kind).toBe('template'); // an unknown hint is plain text
  });

  it('numbers keep the digits, clamp to the range and hand an expression on as text', () => {
    const amount = param('amount', { type: 'integer', min: 1, max: 64 });
    expect(numberValue('12', amount)).toBe(12);
    expect(numberValue('999', amount)).toBe(64);
    expect(numberValue('0', amount)).toBe(1);
    expect(numberValue('2.6', amount)).toBe(3);
    expect(numberValue('', amount)).toBeUndefined();
    expect(numberValue('${price}', amount)).toBe('${price}');
  });
});

describe('writing a parameter back into the step', () => {
  it('a scalar that still holds only the shorthand stays a scalar', () => {
    expect(writeParam('a', 'sound', 'sound', 'b')).toBe('b');
  });

  it('another parameter turns the scalar into a mapping that keeps the shorthand', () => {
    expect(writeParam('entity.x', 'sound', 'volume', 2)).toEqual({ sound: 'entity.x', volume: 2 });
  });

  it('a mapping stays a mapping, key order kept, and an unknown key is untouched', () => {
    const out = writeParam({ sound: 's', volume: 1, mystery: { a: 1 } }, 'sound', 'volume', 3) as Record<string, unknown>;
    expect(Object.keys(out)).toEqual(['sound', 'volume', 'mystery']);
    expect(out.mystery).toEqual({ a: 1 });
    expect(writeParam({ sound: 's' }, 'sound', 'sound', 'b')).toEqual({ sound: 'b' });
  });

  it('removing a parameter drops its key; a bare step gets the compact form for its shorthand', () => {
    expect(writeParam({ sound: 's', volume: 1 }, 'sound', 'volume', undefined)).toEqual({ sound: 's' });
    expect(writeParam(undefined, 'sound', 'sound', 'x')).toBe('x');
    expect(writeParam(undefined, 'sound', 'volume', 2)).toEqual({ volume: 2 });
  });

  it('reads a payload as the compiler does', () => {
    expect(readPayload('x', 'sound')).toEqual({ sound: 'x' });
    expect(readPayload({ sound: 'x' }, 'sound')).toEqual({ sound: 'x' });
    expect(readPayload(undefined, 'sound')).toEqual({});
    expect(unknownKeys({ sound: 'x', extra: 1 }, vocabulary.actions.get('sound')!.parameters)).toEqual(['extra']);
  });
});

describe('the condition stack edits the authored value', () => {
  it('reads every shape of the grammar', () => {
    expect(shapeOf('${a}')).toEqual({ t: 'expr', text: '${a}' });
    expect(shapeOf(true)).toEqual({ t: 'bool', value: true });
    expect(shapeOf([1, 2])).toMatchObject({ t: 'group', op: 'all', form: 'list' });
    expect(shapeOf({ any: [] })).toMatchObject({ t: 'group', op: 'any', form: 'map' });
    expect(shapeOf({ not: 'x' })).toEqual({ t: 'not', child: 'x' });
    expect(shapeOf({ at_least: { count: 2, of: ['a'] } })).toEqual({ t: 'atLeast', count: 2, items: ['a'] });
    expect(shapeOf({ has_money: 250 })).toEqual({ t: 'pred', id: 'has_money', payload: 250 });
    expect(shapeOf({ a: 1, b: 2 }).t).toBe('raw');
    expect(shapeOf({ at_least: 3 }).t).toBe('raw');
  });

  const root = { all: [{ has_money: 250 }, { any: ['${a}', { not: { has_item: 'x' } }] }] };

  it('draws the tree with a path for every node', () => {
    const view = viewOf(root);
    expect(view.children).toHaveLength(2);
    expect(view.children[1]!.children[1]!.path).toEqual(['all', 1, 'any', 1]);
    expect(view.children[1]!.children[1]!.children[0]!.path).toEqual(['all', 1, 'any', 1, 'not']);
    expect(getAt(root, ['all', 1, 'any', 0])).toBe('${a}');
  });

  it('adds, removes and reorders inside groups without touching the rest', () => {
    const added = addChild(root, [], '${b}') as typeof root;
    expect(added.all).toHaveLength(3);
    expect(root.all).toHaveLength(2); // the input is never mutated
    const removed = removeAt(root, ['all', 0]) as typeof root;
    expect(removed.all).toHaveLength(1);
    expect(removeAt(root, [])).toBeUndefined();
    const moved = moveBy(root, ['all', 1], -1) as typeof root;
    expect(moved.all[0]).toEqual(root.all[1]);
    expect(moveBy(root, ['all', 0], -1)).toBe(root);
  });

  it('removing the only child of a not takes the not with it', () => {
    const tree = { all: [{ not: 'x' }, 'y'] };
    expect(removeAt(tree, ['all', 0, 'not'])).toEqual({ all: ['y'] });
  });

  it('wraps a condition in a composite and switches all and any', () => {
    expect(wrap('x', [], 'not')).toEqual({ not: 'x' });
    expect(wrap('x', [], 'any')).toEqual({ any: ['x'] });
    expect(wrap('x', [], 'at_least')).toEqual({ at_least: { count: 1, of: ['x'] } });
    expect(setGroupOp(['a', 'b'], [], 'any')).toEqual({ any: ['a', 'b'] });
    expect(setGroupOp({ all: ['a'] }, [], 'any')).toEqual({ any: ['a'] });
    expect(setGroupOp(['a'], [], 'all')).toEqual(['a']);
    expect(setCount({ at_least: { count: 1, of: ['a', 'b'] } }, [], 3)).toEqual({ at_least: { count: 3, of: ['a', 'b'] } });
  });

  it('a stack grows from one condition to a list that means all', () => {
    expect(pushRoot(undefined, 'a')).toBe('a');
    expect(pushRoot('a', 'b')).toEqual(['a', 'b']);
    expect(pushRoot(['a', 'b'], 'c')).toEqual(['a', 'b', 'c']);
    expect(pushRoot({ any: ['a'] }, 'b')).toEqual([{ any: ['a'] }, 'b']); // the old meaning is kept
  });

  it('changing a row\'s kind starts the new kind fresh; all and any keep their children', () => {
    expect(convert({ all: ['a', 'b'] }, [], 'any', vocabulary)).toEqual({ any: ['a', 'b'] });
    expect(convert('x', [], 'bool', vocabulary)).toBe(true);
    expect(convert('x', [], 'not', vocabulary)).toEqual({ not: '${true}' });
    const predicate = freshCondition('predicate', vocabulary, 'has_permission') as Record<string, unknown>;
    expect(Object.keys(predicate)).toEqual(['has_permission']);
    expect(rowKindOf(shapeOf(predicate))).toBe('predicate');
  });

  it('a new predicate starts with its entry\'s starting payload (the shorthand parameter)', () => {
    expect(freshCondition('predicate', vocabulary, 'has_money')).toEqual({ has_money: 1 });
    expect(freshCondition('predicate', vocabulary, 'in_gui')).toEqual({ in_gui: '' });
    expect(setAt({ all: ['a'] }, ['all', 0], { has_money: 5 })).toEqual({ all: [{ has_money: 5 }] });
  });
});

describe('template completion', () => {
  const block = yaml(`r:\n  behavior:\n    - on: gui.click\n      do:\n        - set_var: {var: picked, value: 1}\n        - message: ""\n`);
  const graph = project(block);
  const declared = declarationsOf('shop', 'variables/shop.yml', { coins: { scope: 'player', type: 'number', default: 0, description: 'Shop coins' } });
  const scope = scopeFor(graph, block, graph.byId.get('behavior.0.do.1')!, vocabulary, declared);
  const complete = (text: string, caret = text.length) => completeAt(text, caret, scope);

  it('typing ${ offers the trigger context roots and the functions', () => {
    const result = complete('${')!;
    const inserts = result.items.map((item) => item.insert);
    expect(inserts).toContain('player.');
    expect(inserts).toContain('click.');
    expect(inserts).toContain('vars.');
    expect(result.items.some((item) => item.kind === 'function')).toBe(true);
    expect(result.from).toBe(2);
  });

  it('completes the next segment of a path from the schema, with its type', () => {
    const result = complete('${player.na')!;
    expect(result.items.map((item) => item.insert)).toEqual(['player.name']);
    expect(result.items[0]!.detail).toBe('string');
    expect(result.items[0]!.tail).toBe('}'); // a finished path closes the ${
    expect(applyCompletion('${player.na', result, result.items[0]!)).toEqual({ text: '${player.name}', caret: '${player.name}'.length });
  });

  it('does not add a second closing brace', () => {
    const text = '${player.na}';
    const result = complete(text, '${player.na'.length)!;
    expect(result.items[0]!.tail).toBe('');
  });

  it('offers declared variables under vars.<scope>.<pack>.<key> and the locals the flow wrote', () => {
    const player = complete('${vars.player.shop.')!;
    expect(player.items.map((item) => item.insert)).toEqual(['vars.player.shop.coins']);
    expect(complete('${vars.')!.items.map((item) => item.insert)).toContain('vars.picked');
    expect(complete('${vars.')!.items.map((item) => item.insert)).toContain('vars.player.');
  });

  it('offers function names with their signatures, and inserts the open parenthesis', () => {
    const result = complete('${con')!;
    const contains = result.items.find((item) => item.insert === 'contains(')!;
    expect(contains.kind).toBe('function');
    expect(contains.label).toMatch(/^contains\(.*\)$/);
    expect(applyCompletion('${con', result, contains).text).toBe('${contains(');
  });

  it('knows the end of an expression and the inside of a string', () => {
    expect(complete('${player.name} then ')).toBeNull();
    expect(complete("${contains('pl")).toBeNull();
    expect(complete('plain text')).toBeNull();
    expect(complete('${1')).toBeNull();
  });

  it('% completes from the expansions the scope knows', () => {
    const result = complete('Balance %materione_var')!;
    expect(result.items.map((item) => item.insert)).toEqual(['materione_var_shop_coins']);
    expect(result.items[0]!.tail).toBe('%');
    expect(complete('%materione_g')!.items.map((item) => item.insert)).toEqual(['materione_gui_open', 'materione_gui_last']);
    expect(complete('100% sure')).toBeNull();
    expect(complete('%vault_eco_balance% and %')!.items.length).toBeGreaterThan(0); // a closed pair, then a new one
  });

  it('a bare expression row completes without a ${', () => {
    const bare = completeAt('player.le', 9, scope, { bare: true })!;
    expect(bare.items.map((item) => item.insert)).toEqual(['player.level']);
    expect(completeAt('player.le', 9, scope)).toBeNull();
  });

  it('a rule about a list layer also reads the entry fields its list source declares', () => {
    const listed = yaml(`r:
  behavior:
    - on: gui.click
      layer: players
      do:
        - message: ""
`);
    const g = project(listed);
    const layers = [{ id: 'players', type: 'list', source: 'online_players' }, { id: 'other', type: 'list', source: 'pack_items' }];
    const withList = scopeFor(g, listed, g.byId.get('behavior.0.do.0')!, vocabulary, [], 'gui', layers);
    expect(withList.paths.map((entry) => entry.path)).toEqual(expect.arrayContaining(['entry.name', 'entry.uuid', 'entry.*']));
    const without = scopeFor(g, listed, g.byId.get('behavior.0.do.0')!, vocabulary, [], 'gui', []);
    expect(without.paths.map((entry) => entry.path)).not.toContain('entry.name');
  });

  it('a flow reads the context of the rules that call it, and args', () => {
    const withFlow = yaml(`r:\n  behavior:\n    - on: gui.click\n      do:\n        - call: {flow: buy, with: {price: 5}}\n  flows:\n    buy:\n      - message: x\n`);
    const g = project(withFlow);
    const flowScope = scopeFor(g, withFlow, g.byId.get('flows.buy.0')!, vocabulary, []);
    const paths = flowScope.paths.map((entry) => entry.path);
    expect(paths).toContain('args.price');
    expect(paths).toContain('args.*');
    expect(paths).toContain('click.type');
  });
});

describe('template lint', () => {
  const block = yaml(`r:\n  behavior:\n    - on: gui.click\n      do:\n        - message: ""\n`);
  const graph = project(block);
  const scope = scopeFor(graph, block, graph.byId.get('behavior.0.do.0')!, vocabulary, []);

  it('flags an unclosed ${', () => {
    const [problem] = lintTemplate('hello ${player.name', scope);
    expect(problem).toMatchObject({ severity: 'error', from: 6 });
  });

  it('flags an unknown root and an unknown function, and nothing the schema offers', () => {
    expect(lintTemplate('${player.name} has ${click.type}', scope)).toEqual([]);
    const unknown = lintTemplate('${bogus.value > 1}', scope);
    expect(unknown).toHaveLength(1);
    expect(unknown[0]!.message).toContain("'bogus'");
    expect(lintTemplate('${nope(1)}', scope)[0]!.message).toContain("'nope'");
    expect(lintTemplate("${contains(player.name, 'x')}", scope)).toEqual([]);
  });

  it('ignores names inside strings and literals', () => {
    expect(lintTemplate("${player.name == 'bogus.thing'}", scope)).toEqual([]);
    expect(lintTemplate('${true && false}', scope)).toEqual([]);
    expect(lintTemplate('plain text with no template', scope)).toEqual([]);
  });

  it('lints a whole bare expression, and stays quiet with no vocabulary', () => {
    expect(lintTemplate('bogus > 1', scope, { bare: true })).toHaveLength(1);
    expect(lintTemplate('bogus > 1', scope)).toEqual([]);
    expect(lintTemplate('${bogus}', { ...scope, roots: new Set() })).toEqual([]);
  });
});

describe('declared variables', () => {
  it('reads the declarations of a variables document and ignores what is not one', () => {
    const found = declarationsOf('shop', 'variables/shop.yml', {
      coins: { scope: 'player', type: 'number', default: 5, description: 'Shop coins' },
      season: { scope: 'server', type: 'string' },
      broken: { scope: 'galaxy', type: 'number' },
      note: 'text',
    });
    expect(found.map((variable) => variable.id)).toEqual(['shop:coins', 'shop:season']);
    expect(found[1]!.default).toBe('');
    expect(readPath(found[0]!)).toBe('vars.player.shop.coins');
  });

  it('names what makes a key unusable', () => {
    const existing = declarationsOf('shop', 'variables/shop.yml', { coins: { scope: 'player', type: 'number' } });
    expect(keyProblem('', existing, 'shop')).toBeTruthy();
    expect(keyProblem('Coins', existing, 'shop')).toContain('lowercase');
    expect(keyProblem('player', existing, 'shop')).toContain('scope');
    expect(keyProblem('coins', existing, 'shop')).toContain('already declared');
    expect(keyProblem('coins', existing, 'other')).toBeNull();
    expect(keyProblem('gems', existing, 'shop')).toBeNull();
  });

  it('writes a declaration as one field edit and finds the variables files', () => {
    expect(declarationEdits({ key: 'gems', scope: 'player', type: 'number', description: 'Gems' })).toEqual([
      { field: ['gems'], value: { scope: 'player', type: 'number', default: 0, description: 'Gems' } },
    ]);
    expect(declarationEdits({ key: 'vip', scope: 'player', type: 'boolean' })[0]).toEqual({
      field: ['vip'], value: { scope: 'player', type: 'boolean', default: false },
    });
    expect(
      variableFiles([
        { pack: 'shop', type: 'variables', path: 'variables/shop.yml', id: 'coins' },
        { pack: 'shop', type: 'variables', path: 'variables/shop.yml', id: 'gems' },
        { pack: 'shop', type: 'items', path: 'items/a.yml', id: 'a' },
      ]),
    ).toHaveLength(1);
  });
});

describe('inspector edits become the same edits the canvas makes', () => {
  const block = yaml(`r:
  behavior:
    - on: gui.click
      layer: buy
      do:
        - sound: block.chest.open
        - gui.close
        - choose:
            - if: "\${a}"
              then:
                - message: one
          else:
            - message: none
        - prompt: {text: hi, var: session.q}
          else:
            - message: gave up
  flows:
    f:
      - stop
`);
  const graph = project(block, { takesElse: (action) => action === 'prompt' });

  it('a payload edit is a patchStep of the action\'s own key', () => {
    const plan = ok(planStepValue(graph, 'behavior.0.do.0', { sound: 'x', volume: 2 }, name));
    expect(plan.edits).toEqual([{ op: 'patchStep', path: 'behavior.0.do.0', set: { sound: { sound: 'x', volume: 2 } } }]);
    const after = applyEdits(block, plan.edits);
    expect((after.behavior![0] as any).do[0]).toEqual({ sound: { sound: 'x', volume: 2 } });
  });

  it('a bare step gets a mapping by replacement, in the same place', () => {
    const plan = ok(planStepValue(graph, 'behavior.0.do.1', { gui: 'x' }, name));
    expect(plan.edits).toEqual([
      { op: 'removeStep', path: 'behavior.0.do.1' },
      { op: 'insertStep', path: 'behavior.0.do.1', step: { 'gui.close': { gui: 'x' } } },
    ]);
    const chance = ok(planStepKey(graph, 'behavior.0.do.1', 'chance', 0.5, name));
    expect(applyEdits(block, chance.edits).behavior).toBeDefined();
    expect((applyEdits(block, chance.edits).behavior![0] as any).do[1]).toEqual({ 'gui.close': {}, chance: 0.5 });
  });

  it('an option is set or removed on the step; a rule\'s keys go through patchRule', () => {
    expect(ok(planStepKey(graph, 'behavior.0.do.0', 'delay', 10, name)).edits).toEqual([
      { op: 'patchStep', path: 'behavior.0.do.0', set: { delay: 10 } },
    ]);
    expect(ok(planStepKey(graph, 'behavior.0.do.0', 'delay', undefined, name)).edits).toEqual([
      { op: 'patchStep', path: 'behavior.0.do.0', unset: ['delay'] },
    ]);
    expect(ok(planStepKey(graph, 'behavior.0', 'layer', 'sell', name)).edits).toEqual([
      { op: 'patchRule', index: 0, set: { layer: 'sell' } },
    ]);
    const after = applyEdits(block, ok(planStepKey(graph, 'behavior.0', 'cooldown', 500, name)).edits);
    expect(Object.keys(after.behavior![0] as object)).toEqual(['on', 'layer', 'do', 'cooldown']);
  });

  it('a rule condition is set and cleared; an if step never loses its condition; a case is replaced in place', () => {
    expect(ok(planCondition(graph, block, 'behavior.0', { has_money: 5 }, name)).edits).toEqual([
      { op: 'patchRule', index: 0, set: { if: { has_money: 5 } } },
    ]);
    expect(ok(planCondition(graph, block, 'behavior.0', undefined, name)).edits).toEqual([
      { op: 'patchRule', index: 0, unset: ['if'] },
    ]);
    const swapped = ok(planCondition(graph, block, 'behavior.0.do.2', ['${a}', '${b}'], name, 0));
    expect(swapped.edits).toHaveLength(2);
    const after = applyEdits(block, swapped.edits);
    const choose = (after.behavior![0] as any).do[2];
    expect(choose.choose[0]).toEqual({ if: ['${a}', '${b}'], then: [{ message: 'one' }] });
    expect(choose.else).toEqual([{ message: 'none' }]); // what hangs below the step travels with the replacement
  });

  it('every inspector edit builds a history command that takes itself back', () => {
    const doc = { rest: { behavior: block.behavior, flows: block.flows } } as never;
    const built = buildCommand(doc, planStepValue(graph, 'behavior.0.do.0', 'changed', name));
    expect(built.ok && built.command).toBeTruthy();
    if (built.ok && built.command) {
      const done = built.command.apply(doc) as { rest: LogicBlock };
      expect((done.rest.behavior![0] as any).do[0]).toEqual({ sound: 'changed' });
      expect(built.command.revert(done as never)).toEqual(doc);
    }
  });

  it('renames a flow through the rename edit, refusing a taken or invalid name', () => {
    expect(ok(planRenameFlow(graph, 'flows.f', 'g')).edits).toEqual([{ op: 'renameFlow', from: 'f', to: 'g' }]);
    expect(planRenameFlow(graph, 'flows.f', 'bad name').ok).toBe(false);
    expect(planRenameFlow(graph, 'flows.f', '').ok).toBe(false);
    expect(ok(planRenameFlow(graph, 'flows.f', 'f')).edits).toEqual([]);
  });

  it('projects an action that takes an else as a branch and rebuilds it byte for byte', () => {
    const prompt = graph.byId.get('behavior.0.do.3')!;
    expect(prompt.ports.map((port) => port.id)).toEqual(['after', 'else']);
    expect(graph.byId.get('behavior.0.do.3.else.0')!.op).toBe('message');
    expect(graph.byId.get('behavior.0.do.3.else.0')!.owner).toBe('behavior.0');
    // without the vocabulary the same file still projects and rebuilds: the else: rides in the node's own keys
    const plain = project(block);
    expect(plain.byId.get('behavior.0.do.3')!.ports.map((port) => port.id)).toEqual(['after']);
    expect(plain.byId.has('behavior.0.do.3.else.0')).toBe(false);
  });
});

describe('diagnostics land on the field they name', () => {
  const flags = [
    { severity: 'warn' as const, message: 'MTR-0057: a step failed', path: 'behavior.0.do.1' },
    { severity: 'error' as const, message: "MTR-0057: 'amount' must be above 0", path: 'behavior.0.do.1' },
    { severity: 'warn' as const, message: 'unknown root', path: 'behavior.0.do.1.if' },
    { severity: 'info' as const, message: 'no path' },
  ];

  it('splits a node\'s flags into its fields\' and its own', () => {
    const split = attribute('behavior.0.do.1', flags, ['amount', 'if', 'item']);
    expect(split.node.map((flag) => flag.message)).toEqual(['MTR-0057: a step failed', 'no path']);
    expect(split.fields.get('amount')!.map((flag) => flag.severity)).toEqual(['error']);
    expect(split.fields.get('if')!).toHaveLength(1);
    expect(worstOf(split.fields.get('amount')!)).toBe('error');
    expect(worstOf([])).toBeNull();
  });

  it('a path that goes on into something that is not a field of the node stays on the node', () => {
    const split = attribute('behavior.0.do.1', [{ severity: 'warn', message: 'a branch problem', path: 'behavior.0.do.1.then.0' }], ['amount']);
    expect(split.fields.size).toBe(0);
    expect(split.node).toHaveLength(1);
  });
});

describe('declaring a variable goes through the ordinary authoring ops', () => {
  /** A channel that answers like a server: it records every call. */
  function channel(documents: Record<string, { rev: number; parsed: unknown }>) {
    const calls: { op: string; payload: Record<string, unknown> }[] = [];
    return {
      calls,
      async call(op: string, payload: Record<string, unknown> = {}) {
        calls.push({ op, payload });
        const key = `${payload.pack}/${payload.path}`;
        if (op === 'browse.get') return { ok: true as const, op, id: '1', data: documents[key] ?? {} };
        if (op === 'edit.create') {
          documents[key] = { rev: 1, parsed: { [String(payload.id)]: { scope: 'player', type: 'number', default: 0 } } };
          return { ok: true as const, op, id: '1', data: { rev: 1 } };
        }
        return { ok: true as const, op, id: '1', data: { rev: 2 } };
      },
    };
  }

  it('writes into the pack variables file that exists with one setFields', async () => {
    const server = channel({ 'shop/variables/shop.yml': { rev: 4, parsed: { coins: { scope: 'player', type: 'number' } } } });
    const made = await declareVariable(server as never, 'shop', { key: 'gems', scope: 'server', type: 'number' }, [
      { pack: 'shop', type: 'variables', path: 'variables/shop.yml', id: 'coins' },
    ]);
    expect(made.id).toBe('shop:gems');
    expect(server.calls.map((call) => call.op)).toEqual(['browse.get', 'authoring.setFields']);
    expect(server.calls[1]!.payload).toMatchObject({ pack: 'shop', path: 'variables/shop.yml', rev: 4 });
  });

  it('creates variables/<pack>.yml first when the pack has none, then sets what was asked', async () => {
    const server = channel({});
    await declareVariable(server as never, 'shop', { key: 'vip', scope: 'player', type: 'boolean', description: 'VIP' }, []);
    expect(server.calls.map((call) => call.op)).toEqual(['edit.create', 'authoring.setFields']);
    expect(server.calls[0]!.payload).toEqual({ pack: 'shop', path: 'variables/shop.yml', id: 'vip' });
    expect(server.calls[1]!.payload.edits).toEqual([
      { field: ['vip', 'scope'], value: 'player' },
      { field: ['vip', 'type'], value: 'boolean' },
      { field: ['vip', 'default'], value: false },
      { field: ['vip', 'description'], value: 'VIP' },
    ]);
  });

  it('refuses a key the file already declares, before writing anything', async () => {
    const server = channel({ 'shop/variables/shop.yml': { rev: 1, parsed: { coins: {} } } });
    await expect(
      declareVariable(server as never, 'shop', { key: 'coins', scope: 'player', type: 'number' }, [
        { pack: 'shop', type: 'variables', path: 'variables/shop.yml', id: 'coins' },
      ]),
    ).rejects.toThrow('already declared');
    expect(server.calls.map((call) => call.op)).toEqual(['browse.get']);
  });
});
