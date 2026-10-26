import { describe, expect, it } from 'vitest';
import {
  actionView,
  activationOf,
  addMechanic,
  authoredMechanicName,
  blankRule,
  canAddMechanic,
  fullMechanicId,
  mechanicEntries,
  presetsFor,
  removeAt,
  replaceAt,
  ruleSentence,
  setMechanicParam,
  setRuleKey,
  withConditions,
  writeAction,
  type BehaviorVocabulary,
  type MechanicInfo,
} from '../src/lib/behavior';

const vocabulary: BehaviorVocabulary = {
  events: [
    { id: 'click', displayName: 'Click', description: 'The player clicks with the item', details: ['right', 'left'], firedBy: ['item', 'furniture'] },
    { id: 'place', displayName: 'Place', description: 'The block is placed', details: [], firedBy: ['block', 'furniture'] },
    { id: 'tick', displayName: 'Tick', description: 'Reserved; not fired yet', details: [], firedBy: [] },
  ],
  actions: [
    { id: 'message', displayName: 'Send message', description: 'Shows text', shorthand: 'text',
      parameters: [{ id: 'text', type: 'string', required: true }, { id: 'target', type: 'enum', required: false, allowedValues: ['player', 'actionbar'] }] },
    { id: 'run_command', displayName: 'Run command', shorthand: 'command', parameters: [{ id: 'command', type: 'string', required: true }] },
  ],
  ruleOptions: [{ id: 'cooldown', type: 'integer', required: false }],
  actionOptions: [{ id: 'chance', type: 'decimal', required: false }],
  mechanics: [
    { id: 'materione:soulbound', displayName: 'Soulbound', description: 'Kept on death', repeatable: false, registeredBy: 'Materione', activation: 'When the owner dies' },
    { id: 'materione:commands', displayName: 'Commands', description: 'Runs commands', repeatable: true, registeredBy: 'Materione' },
    { id: 'example:charge', displayName: 'Charge', description: 'Addon', repeatable: false, registeredBy: 'ExampleAddon' },
  ],
};

const soulbound = vocabulary.mechanics[0] as MechanicInfo;
const commands = vocabulary.mechanics[1] as MechanicInfo;
const charge = vocabulary.mechanics[2] as MechanicInfo;

describe('mechanics (abilities) over the existing mechanics: list', () => {
  it('aliases unqualified names to materione: only and writes addons qualified', () => {
    expect(fullMechanicId('food')).toBe('materione:food');
    expect(fullMechanicId('example:charge')).toBe('example:charge');
    expect(authoredMechanicName('materione:food')).toBe('food');
    expect(authoredMechanicName('example:charge')).toBe('example:charge');
  });

  it('reads map, bare-name and unreadable entries without dropping any', () => {
    const entries = mechanicEntries([{ durability: { max: 20 } }, 'soulbound', { a: 1, b: 2 }]);
    expect(entries.map((entry) => [entry.name, entry.form])).toEqual([
      ['durability', 'map'], ['soulbound', 'name'], ['', 'invalid'],
    ]);
  });

  it('edits one parameter and keeps unknown parameters and other entries exactly', () => {
    const before = [{ unknown_addon: { secret: 1 } }, { durability: { max: 20, extra: 'kept' } }];
    const after = setMechanicParam(before, 1, 'max', 40);
    expect(after).toEqual([{ unknown_addon: { secret: 1 } }, { durability: { max: 40, extra: 'kept' } }]);
    expect(before[1]).toEqual({ durability: { max: 20, extra: 'kept' } });
    expect(setMechanicParam(after, 1, 'max', '')).toEqual([{ unknown_addon: { secret: 1 } }, { durability: { extra: 'kept' } }]);
  });

  it('refuses a second copy of a non-repeatable ability but allows repeatable ones', () => {
    const list = addMechanic(undefined, soulbound);
    expect(list).toEqual([{ soulbound: {} }]);
    expect(canAddMechanic(list, soulbound)).toBe(false);
    expect(() => addMechanic(list, soulbound)).toThrow(/only be added once/);
    expect(addMechanic(addMechanic(list, commands), commands)).toHaveLength(3);
    expect(addMechanic([], charge)).toEqual([{ 'example:charge': {} }]);
  });

  it('treats a short and a qualified built-in name as the same ability', () => {
    expect(canAddMechanic([{ 'materione:soulbound': {} }], soulbound)).toBe(false);
    expect(removeAt([{ a: {} }, { b: {} }], 0)).toEqual([{ b: {} }]);
  });
});

describe('behavior rules over the existing on/if/do format', () => {
  const rule = {
    on: 'click:right',
    if: 'player.health > 0',
    cooldown: 3000,
    custom_key: 'kept',
    do: [{ message: '&aHi' }, { run_command: { command: 'say hi' }, chance: 0.5 }],
  };

  it('reads a rule as one sentence from the server vocabulary', () => {
    expect(ruleSentence(rule, vocabulary)).toBe(
      'On right click, if player.health > 0: send message “&aHi”, then run command “say hi” 50% of the time (at most every 3 s)',
    );
  });

  it('explains activation from the event table and flags events the host never fires', () => {
    expect(activationOf(rule, vocabulary, 'item')).toEqual({ fires: true, text: 'The player clicks with the item (right only).' });
    expect(activationOf({ on: 'place' }, vocabulary, 'item').fires).toBe(false);
    expect(activationOf({ on: 'place' }, vocabulary, 'item').text).toMatch(/not fired for item/);
    expect(activationOf({ on: 'tick' }, vocabulary, 'item').text).toMatch(/reserved/);
    expect(activationOf({ on: 'click:middle' }, vocabulary, 'item').fires).toBe(false);
    expect(activationOf({ on: 'teleport' }, vocabulary, 'item').text).toMatch(/does not know/);
  });

  it('expands a scalar shorthand and writes it back unchanged', () => {
    const view = actionView({ message: '&aHi' }, vocabulary);
    expect(view).toMatchObject({ type: 'message', params: { text: '&aHi' }, scalar: true, valid: true });
    expect(writeAction(view, vocabulary)).toEqual({ message: '&aHi' });
    expect(writeAction({ ...view, params: { text: 'x', target: 'actionbar' } }, vocabulary))
      .toEqual({ message: { text: 'x', target: 'actionbar' } });
  });

  it('keeps the map form, options and unknown params of a structured action', () => {
    const view = actionView({ run_command: { command: 'say hi', as: 'player', extra: 1 }, chance: 0.5 }, vocabulary);
    expect(view.options).toEqual({ chance: 0.5 });
    expect(writeAction(view, vocabulary)).toEqual({ run_command: { command: 'say hi', as: 'player', extra: 1 }, chance: 0.5 });
    expect(actionView({ message: 'a', sound: 'b' }, vocabulary).valid).toBe(false);
  });

  it('edits rule keys and conditions without touching unknown keys', () => {
    expect(setRuleKey(rule, 'cooldown', '')).not.toHaveProperty('cooldown');
    expect(setRuleKey(rule, 'cooldown', 500).custom_key).toBe('kept');
    expect(withConditions(rule, ['a > 1']).if).toBe('a > 1');
    expect(withConditions(rule, ['a > 1', ' b ']).if).toEqual(['a > 1', 'b']);
    expect(withConditions(rule, ['  '])).not.toHaveProperty('if');
    expect(replaceAt([rule, { on: 'drop' }], 1, { on: 'pickup' })).toEqual([rule, { on: 'pickup' }]);
  });

  it('offers presets only for events the host fires and actions the server registers', () => {
    expect(presetsFor(vocabulary, 'item').map((preset) => preset.id)).toEqual(['click-message', 'click-command']);
    expect(presetsFor(vocabulary, 'block').map((preset) => preset.id)).toEqual(['place-message']);
    expect(presetsFor(null, 'item')).toEqual([]);
    expect(presetsFor(vocabulary, 'item')[0]!.rule).toEqual({ on: 'click:right', do: [{ message: '&aHello!' }] });
    expect(blankRule(vocabulary, 'block')).toEqual({ on: 'place', do: [{ message: { text: 'Hello!' } }] });
  });
});
