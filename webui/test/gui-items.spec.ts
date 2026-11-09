/**
 * Item layers and image uploads: container-only slot snapping, the pack-qualified reference a pick
 * writes, the names an upload takes, and how a layer's customized item is recognised. The compiler's
 * rules for an item layer, and Customize's own rules (the id, the link or the material), are the
 * server's (gui.validate, gui.customizeItem; pinned by the Java tests). Pure.
 */
import { describe, expect, it } from 'vitest';
import type { ResourceRef } from '../src/lib/api';
import { containerProfile, nearestContainerCell } from '../src/lib/gui/containers';
import { customFolder, isCustomized, resolveItem } from '../src/lib/gui/customize';
import { storedItemRef } from '../src/lib/gui/items';
import type { GuiDocument, GuiLayer } from '../src/lib/gui/model';
import * as ops from '../src/lib/gui/ops';
import { uploadReference } from '../src/lib/gui/save';
import { sceneOf } from '../src/lib/gui/scene';
import { registry } from './helpers/gui-registry';

const item = (id: string, extra: Record<string, unknown> = {}): GuiLayer =>
  ({ id, type: 'item', slot: 13, item: 'minecraft:diamond_sword', ...extra }) as GuiLayer;
const profile = (id: string) => containerProfile(id)!;
const doc = (container: string, ...layers: GuiLayer[]): GuiDocument => ({ title: '', container, layers, rest: {}, order: [] });
const resource = (id: string, path: string, type = 'items'): ResourceRef => ({ pack: 'sample', type, path, id, name: id } as ResourceRef);

describe('item layers snap to the container only', () => {
  it('the nearest slot is always a container slot, even over the player inventory', () => {
    // a point over the first inventory slot of a 3-row chest (y = 3*18 + 31 = 85) is still a container slot
    const cell = nearestContainerCell(profile('chest_3'), 12, 90);
    expect(cell.region).toBe('container');
    expect(cell.index).toBeLessThan(27);
    expect(nearestContainerCell(profile('chest_3'), 8 + 4 * 18 + 8, 18 + 18 + 8).index).toBe(13);
    expect(nearestContainerCell(profile('chest_1'), 500, 500).index).toBe(8); // clamped to the last slot of a 1-row chest
  });

  it('the hopper has five slots and the dispenser a 3 x 3 grid: the nearest slot is theirs, never the player\'s', () => {
    const hopper = profile('hopper');
    expect(nearestContainerCell(hopper, 500, 20).index).toBe(4); // clamped to the last of five slots
    expect(nearestContainerCell(hopper, 12, 60).index).toBe(0); // over the player's inventory (y = 51...), still slot 0
    const dispenser = profile('dispenser');
    expect(nearestContainerCell(dispenser, 62 + 18 + 8, 17 + 18 + 8).index).toBe(4); // the middle of the grid
    expect(nearestContainerCell(dispenser, 500, 500).index).toBe(8);
  });

  it('an item dragged onto a hopper lands on one of its five slots; a nudge never leaves them', () => {
    const d = doc('hopper', item('sword', { slot: 0 }));
    const scene = sceneOf(d, registry, () => null);
    const moved = ops.setRects(d, scene, { sword: { x: 400, y: 20, w: 16, h: 16 } }, 'Move layer')!.apply(d).layers[0]!;
    expect(moved.slot).toBe(4); // a far point lands on the last of the five slots
    const last = doc('hopper', item('sword', { slot: 4 }));
    expect(ops.nudge(last, sceneOf(last, registry, () => null), ['sword'], 1, 0)).toBeNull();
    expect(ops.nudge(last, sceneOf(last, registry, () => null), ['sword'], -1, 0)!.apply(last).layers[0]!.slot).toBe(3);
  });

  it('dragging an item to a rectangle over the inventory lands it on a container slot', () => {
    const d = doc('chest_3', item('sword'));
    const scene = sceneOf(d, registry, () => null);
    const command = ops.setRects(d, scene, { sword: { x: 8, y: 140, w: 16, h: 16 } }, 'Move layer')!;
    const moved = command.apply(d).layers[0]!;
    expect(moved.slot).toBeGreaterThanOrEqual(0);
    expect(moved.slot as number).toBeLessThan(27);
  });

  it('arrow-key nudges hop one slot and never leave the container', () => {
    const d = doc('chest_3', item('sword', { slot: 26 }));
    const scene = sceneOf(d, registry, () => null);
    // already on the last container slot: nowhere further to go, so the nudge is a no-op (no player slot 27)
    expect(ops.nudge(d, scene, ['sword'], 1, 1)).toBeNull();
    const left = ops.nudge(d, scene, ['sword'], -1, 0)!.apply(d).layers[0]!;
    expect(left.slot).toBe(25);
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

describe('a layer that already has its own item', () => {
  it('is recognised by the folder its item lives in, and only there', () => {
    const e = { pack: 'sample', guiPath: 'guis/shop/main.yml', resources: [] as ResourceRef[] };
    expect(customFolder(e.guiPath)).toBe('gui/shop/main');
    const custom = resource('sword', 'items/gui/shop/main/sword.yml');
    const withCustom = { ...e, resources: [custom, resource('ruby', 'items/ruby.yml')] };
    expect(resolveItem('sample:sword', withCustom)).toBe(custom);
    expect(isCustomized('sample:sword', withCustom)).toBe(custom);
    expect(isCustomized('sample:ruby', withCustom)).toBeUndefined();
    expect(isCustomized('minecraft:diamond_sword', withCustom)).toBeUndefined();
  });
});
