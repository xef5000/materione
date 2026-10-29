/**
 * Item layers and image uploads: container-only slot snapping, the compiler's rules for an item
 * layer, the pack-qualified reference a pick writes, and Customize's plan (one create/duplicate
 * operation at items/gui/<gui-path>/<layer-id>.yml). Pure.
 */
import { describe, expect, it } from 'vitest';
import type { ResourceRef } from '../src/lib/api';
import { nearestContainerCell } from '../src/lib/gui/chest';
import { chooseItemId, customFolder, customPath, isCustomized, planCustomize, resolveItem } from '../src/lib/gui/customize';
import { analyzeItems, storedItemRef } from '../src/lib/gui/items';
import type { GuiDocument, GuiLayer } from '../src/lib/gui/model';
import * as ops from '../src/lib/gui/ops';
import { uploadReference } from '../src/lib/gui/save';
import { sceneOf } from '../src/lib/gui/scene';
import { registry } from './helpers/gui-registry';

const item = (id: string, extra: Record<string, unknown> = {}): GuiLayer =>
  ({ id, type: 'item', slot: 13, item: 'minecraft:diamond_sword', ...extra }) as GuiLayer;
const doc = (rows: number, ...layers: GuiLayer[]): GuiDocument => ({ title: '', rows, layers, rest: {}, order: [] });

describe('item layers snap to the container only', () => {
  it('the nearest slot is always a chest slot, even over the player inventory', () => {
    // a point over the first inventory slot of a 3-row chest (y = 3*18 + 31 = 85) is still a container slot
    const cell = nearestContainerCell(3, 12, 90);
    expect(cell.region).toBe('container');
    expect(cell.index).toBeLessThan(27);
    expect(nearestContainerCell(3, 8 + 4 * 18 + 8, 18 + 18 + 8).index).toBe(13);
    expect(nearestContainerCell(1, 500, 500).index).toBe(8); // clamped to the last slot of a 1-row chest
  });

  it('dragging an item to a rectangle over the inventory lands it on a container slot', () => {
    const d = doc(3, item('sword'));
    const scene = sceneOf(d, registry, () => null);
    const command = ops.setRects(d, scene, { sword: { x: 8, y: 140, w: 16, h: 16 } }, 'Move layer')!;
    const moved = command.apply(d).layers[0]!;
    expect(moved.slot).toBeGreaterThanOrEqual(0);
    expect(moved.slot as number).toBeLessThan(27);
  });

  it('arrow-key nudges hop one slot and never leave the container', () => {
    const d = doc(3, item('sword', { slot: 26 }));
    const scene = sceneOf(d, registry, () => null);
    // already on the last container slot: nowhere further to go, so the nudge is a no-op (no player slot 27)
    expect(ops.nudge(d, scene, ['sword'], 1, 1)).toBeNull();
    const left = ops.nudge(d, scene, ['sword'], -1, 0)!.apply(d).layers[0]!;
    expect(left.slot).toBe(25);
  });
});

describe('what the compiler does with an item layer', () => {
  const problems = (d: GuiDocument, id: string): string[] => analyzeItems(d).get(id) ?? [];

  it('accepts a container slot with an item', () => {
    expect(analyzeItems(doc(3, item('a')))).toEqual(new Map());
  });

  it('flags a slot outside the GUI rows (the player inventory is not an item layer target)', () => {
    expect(problems(doc(3, item('a', { slot: 27 })), 'a')[0]).toContain('outside the GUI');
    expect(problems(doc(1, item('a', { slot: 9 })), 'a')[0]).toContain('1 row');
    expect(problems(doc(3, item('a', { slot: 54 })), 'a')[0]).toContain('0 to 53');
    expect(problems(doc(3, item('a', { slot: 1.5 })), 'a')[0]).toContain('whole number');
  });

  it('flags a missing item, a bad amount, and a later layer replacing an earlier one in the same slot', () => {
    expect(problems(doc(3, item('a', { item: ' ' })), 'a')[0]).toContain('choose an item');
    expect(problems(doc(3, item('a', { amount: 65 })), 'a')[0]).toContain('1 to 64');
    const clash = doc(3, item('first'), item('second'));
    expect(problems(clash, 'first')[0]).toContain('later layer "second"');
    expect(problems(clash, 'second')).toEqual([]);
    expect(analyzeItems(doc(3, item('first'), item('second', { visible: false })))).toEqual(new Map());
  });
});

describe('references', () => {
  it('writes a pack item as pack:id (a bare id would resolve in the materione namespace in game)', () => {
    expect(storedItemRef('ruby', 'sample')).toBe('sample:ruby');
    expect(storedItemRef('other:ruby', 'sample')).toBe('other:ruby');
    expect(storedItemRef('minecraft:diamond', 'sample')).toBe('minecraft:diamond');
    expect(storedItemRef('  ', 'sample')).toBe('');
  });

  it('names an uploaded texture beside the GUI, safely and uniquely', () => {
    const none = new Set<string>();
    expect(uploadReference('guis/shop/main.yml', 'My Logo!.PNG', none)).toBe('gui/shop/main/my_logo');
    expect(uploadReference('guis/shop/main.yml', '???.png', none)).toBe('gui/shop/main/image');
    const taken = new Set(['gui/shop/main/logo', 'gui/shop/main/logo_2']);
    expect(uploadReference('guis/shop/main.yml', 'logo.png', taken)).toBe('gui/shop/main/logo_3');
    expect(uploadReference('guis/menu.yml', 'a.png', none)).toBe('gui/menu/a');
  });
});

describe('Customize', () => {
  const resource = (id: string, path: string, type = 'items'): ResourceRef => ({ pack: 'sample', type, path, id });
  const env = (resources: ResourceRef[] = [], materials: string[] = ['DIAMOND_SWORD', 'APPLE']) => ({
    pack: 'sample', guiPath: 'guis/shop/main.yml', resources, materials,
  });

  it('puts the item at items/gui/<gui-path>/<layer-id>.yml', () => {
    expect(customFolder('guis/shop/main.yml')).toBe('gui/shop/main');
    expect(customPath('guis/shop/main.yml', 'sword')).toBe('items/gui/shop/main/sword.yml');
  });

  it('a vanilla base becomes a create with material:, and the layer will point at pack:id', () => {
    const plan = planCustomize(item('sword'), env());
    expect(plan).toMatchObject({
      ok: true,
      id: 'sword',
      path: 'items/gui/shop/main/sword.yml',
      reference: 'sample:sword',
      operation: { kind: 'create', type: 'items', id: 'sword', folder: 'gui/shop/main', edits: [{ field: ['sword', 'material'], value: 'DIAMOND_SWORD' }] },
    });
  });

  it('a Materione item base becomes a duplicate of the base document (items cannot be templates)', () => {
    const plan = planCustomize(item('gem', { item: 'sample:ruby' }), env([resource('ruby', 'items/ruby.yml')]));
    expect(plan).toMatchObject({ ok: true, operation: { kind: 'duplicate', fromPath: 'items/ruby.yml', id: 'gem', folder: 'gui/shop/main' } });
    // a bare reference resolves in the current pack
    expect(planCustomize(item('gem', { item: 'ruby' }), env([resource('ruby', 'items/ruby.yml')]))).toMatchObject({ ok: true });
  });

  it('refuses what it cannot copy and says why', () => {
    expect(planCustomize(item('a', { item: '' }), env())).toMatchObject({ ok: false, reason: expect.stringContaining('Choose an item') });
    expect(planCustomize(item('a', { item: 'minecraft:bedrock' }), env())).toMatchObject({ ok: false, reason: expect.stringContaining('material this server knows') });
    expect(planCustomize(item('a', { item: 'sample:gone' }), env())).toMatchObject({ ok: false, reason: expect.stringContaining('nothing to copy') });
    expect(planCustomize(item('a', { item: 'sample:crate' }), env([resource('crate', 'blocks/crate.yml', 'blocks')]))).toMatchObject({ ok: false, reason: expect.stringContaining('blocks and furniture') });
    // with no material list from the server the vanilla name is taken as written
    expect(planCustomize(item('a', { item: 'minecraft:bedrock' }), env([], []))).toMatchObject({ ok: true });
  });

  it('keeps the layer id for the file when the pack is free, else prefixes the GUI name, else numbers', () => {
    expect(chooseItemId('sword', 'guis/shop/main.yml', new Set())).toBe('sword');
    expect(chooseItemId('sword', 'guis/shop/main.yml', new Set(['sword']))).toBe('shop_main_sword');
    expect(chooseItemId('sword', 'guis/shop/main.yml', new Set(['sword', 'shop_main_sword']))).toBe('shop_main_sword_2');
    expect(chooseItemId('a', 'guis/shop/main.yml', new Set())).toBe('shop_main_a'); // ids are 2+ characters
    const clash = planCustomize(item('sword'), env([resource('sword', 'items/sword.yml')]));
    expect(clash).toMatchObject({ ok: true, id: 'shop_main_sword', path: 'items/gui/shop/main/shop_main_sword.yml' });
  });

  it('knows a customized layer: its item lives in the GUI folder and is not customized again', () => {
    const custom = resource('sword', 'items/gui/shop/main/sword.yml');
    const e = env([custom, resource('ruby', 'items/ruby.yml')]);
    expect(resolveItem('sample:sword', e)).toBe(custom);
    expect(isCustomized('sample:sword', e)).toBe(custom);
    expect(isCustomized('sample:ruby', e)).toBeUndefined();
    expect(planCustomize(item('sword', { item: 'sample:sword' }), e)).toMatchObject({ ok: false, reason: expect.stringContaining('already') });
  });
});
