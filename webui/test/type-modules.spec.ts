/**
 * Display + modules for every content type (issue #153). The schemas below are
 * the real ones: the plugin's golden snapshot records them from the Java
 * registry, so these tests pin what the editor derives from the actual
 * self-description — never from a copy that could drift.
 */
import { describe, expect, it } from 'vitest';
import golden from '../../fixtures/golden/editor-demo-snapshot.json';
import type { ResourceRef, TypeInfo } from '../src/lib/api';
import {
  categoriesOf,
  defaultFor,
  displayFieldsOf,
  extraFieldsOf,
  moduleCatalog,
  presenceOf,
  usesModules,
} from '../src/lib/modules';
import {
  countedItems,
  expandCounted,
  itemChoices,
  itemLabel,
  matchItemChoices,
  opaqueEntries,
  packRef,
  parseItemRef,
  summaryFor,
} from '../src/lib/items';
import { kindOf, rowsOf, snap, toOffset } from '../src/lib/spatial';
import { isRenderable } from '../src/lib/studio';

const types = golden.types as unknown as TypeInfo[];

function typeNamed(name: string): TypeInfo {
  const found = types.find((type) => type.name === name);
  if (!found) throw new Error(`no ${name} type in the golden snapshot`);
  return found;
}

describe('block modules derive from the registry self-description', () => {
  const block = typeNamed('block');
  const catalog = moduleCatalog(block, null);

  it('declares the issue\'s block modules, in schema order, and nothing hard-coded', () => {
    expect(usesModules(block)).toBe(true);
    expect(catalog.map((module) => module.id)).toEqual([
      'technique', 'drops', 'blast_resistant', 'move_policy', 'item', 'behavior',
    ]);
    expect(catalog.map((module) => module.label)).toEqual([
      'Shape', 'Drops', 'Explosion resistant', 'When pushed by pistons', 'Placed by item', 'When… do…',
    ]);
  });

  it('keeps the host block and template as advanced fields, never modules', () => {
    const host = block.parameters.find((param) => param.id === 'host');
    expect(host?.advanced).toBe(true);
    expect(catalog.some((module) => module.id === 'host')).toBe(false);
  });

  it('puts the name and the texture/model slots on the display card', () => {
    const display = displayFieldsOf(block);
    expect(display.name?.id).toBe('display_name');
    expect(display.slots.map((slot) => slot.id)).toEqual(['texture', 'model']);
    expect(extraFieldsOf(block)).toEqual([]);
  });

  it('labels the shape choices without renaming the stored values', () => {
    const shape = block.parameters.find((param) => param.id === 'technique')!;
    expect(shape.options?.map((option) => option.value).sort()).toEqual([...(shape.allowedValues ?? [])].sort());
    expect(shape.options?.find((option) => option.value === 'note_block')?.label).toBe('Full block');
  });

  it('adds a switch module as "on" and a labelled enum with its default', () => {
    const explosion = catalog.find((module) => module.id === 'blast_resistant')!.field!;
    expect(defaultFor(explosion)).toBe(true);
    const pistons = catalog.find((module) => module.id === 'move_policy')!.field!;
    expect(defaultFor(pistons)).toBe('keep');
  });

  it('reads module presence from the keys the file actually has', () => {
    const lamp = { technique: 'slab', drops: ['ruby'] };
    expect(presenceOf(catalog[0]!, lamp).present).toBe(true);
    expect(presenceOf(catalog.find((module) => module.id === 'blast_resistant')!, lamp).present).toBe(false);
  });

  it('every block field is renderable, so no key is silently unreachable', () => {
    for (const param of block.parameters) {
      expect(isRenderable(param), `${param.id} (${param.uiHint})`).toBe(true);
    }
  });
});

describe('furniture modules derive from the registry self-description', () => {
  const furniture = typeNamed('furniture');
  const catalog = moduleCatalog(furniture, null);

  it('declares seats, hitbox, light, storage, rotation, evolution and behaviour', () => {
    expect(catalog.map((module) => module.id)).toEqual([
      'label', 'rotation', 'hitboxes', 'seats', 'lights', 'storage', 'evolution', 'item', 'behavior',
    ]);
    expect(catalog.find((module) => module.id === 'evolution')?.label).toBe('Grows into…');
    expect(catalog.find((module) => module.id === 'lights')?.label).toBe('Light source');
  });

  it('rotation is a set of labelled numbers; storage carries its range and unit', () => {
    const rotation = catalog.find((module) => module.id === 'rotation')!.field!;
    expect(rotation.options?.map((option) => option.value)).toEqual(['0', '45', '90', '180']);
    expect(defaultFor(rotation)).toBe(90);
    const storage = catalog.find((module) => module.id === 'storage')!.field!;
    expect([storage.min, storage.max, storage.unit]).toEqual([9, 54, 'slots']);
  });

  it('seats, hitboxes and lights use the spatial widget and read their kind from the shape', () => {
    const spatial = Object.fromEntries(
      catalog.filter((module) => module.field?.uiHint === 'spatial-list')
        .map((module) => [module.id, kindOf(module.field!)]),
    );
    expect(spatial).toEqual({ hitboxes: 'box', seats: 'seat', lights: 'light' });
  });

  it('is fully renderable too', () => {
    for (const param of furniture.parameters) {
      expect(isRenderable(param), `${param.id} (${param.uiHint})`).toBe(true);
    }
  });

  it('groups the picker categories in reading order', () => {
    expect(categoriesOf(catalog)).toEqual(['appearance', 'placement', 'gameplay', 'behaviour']);
  });
});

describe('item references', () => {
  const resources: ResourceRef[] = [
    { pack: 'mine', type: 'items', path: 'items/ruby.yml', id: 'ruby' },
    { pack: 'mine', type: 'items', path: 'items/gems.yml', id: 'mine:sapphire' },
    { pack: 'mine', type: 'blocks', path: 'blocks/lamp.yml', id: 'lamp' },
    { pack: 'other', type: 'items', path: 'items/ore.yml', id: 'ore' },
    { pack: 'mine', type: 'recipes', path: 'recipes/r.yml', id: 'r' },
  ];
  const itemType: TypeInfo = {
    name: 'item', directory: 'items', displayName: 'Item', description: '',
    parameters: [{ id: 'material', type: 'enum', required: true, allowedValues: ['DIAMOND', 'STICK'] }],
  };

  it('reads bare, namespaced and vanilla references', () => {
    expect(parseItemRef('ruby', 'mine')).toEqual({ vanilla: false, namespace: 'mine', id: 'ruby' });
    expect(parseItemRef('other:ore', 'mine').namespace).toBe('other');
    expect(parseItemRef('minecraft:stick', 'mine').vanilla).toBe(true);
    expect(itemLabel('minecraft:diamond_sword', 'mine')).toBe('Diamond Sword');
  });

  it('writes bare ids inside the pack and namespaced ones across packs', () => {
    expect(packRef(resources[0]!, 'mine')).toBe('ruby');
    expect(packRef(resources[1]!, 'mine')).toBe('sapphire');
    expect(packRef(resources[3]!, 'mine')).toBe('other:ore');
  });

  it('offers pack items, blocks and furniture, then vanilla; placement links stay pack items', () => {
    const all = itemChoices({ pack: 'mine', resources, types: [itemType] });
    expect(all.map((choice) => choice.value)).toEqual([
      'ruby', 'sapphire', 'lamp', 'other:ore', 'minecraft:diamond', 'minecraft:stick',
    ]);
    const placement = itemChoices({ pack: 'mine', resources, types: [itemType], packOnly: true });
    expect(placement.map((choice) => choice.value)).toEqual(['ruby', 'sapphire', 'other:ore']);
  });

  it('searches label and reference with every word required', () => {
    const all = itemChoices({ pack: 'mine', resources, types: [itemType] });
    expect(matchItemChoices(all, 'diam').map((choice) => choice.value)).toEqual(['minecraft:diamond']);
    expect(matchItemChoices(all, 'minecraft stick').map((choice) => choice.value)).toEqual(['minecraft:stick']);
    expect(matchItemChoices(all, 'nothing')).toEqual([]);
  });

  it('finds the summary that carries a pack item\'s visual', () => {
    const summaries = [{
      ...resources[0]!, name: 'Ruby', health: 'ok' as const, material: 'EMERALD',
    }];
    expect(summaryFor(summaries, 'ruby', 'mine')?.material).toBe('EMERALD');
    expect(summaryFor(summaries, 'minecraft:ruby', 'mine')).toBeUndefined();
  });

  it('turns a repeated reference into a count and back, keeping first-seen order', () => {
    const counted = countedItems(['ruby', 'minecraft:diamond', 'ruby']);
    expect(counted).toEqual([{ value: 'ruby', count: 2 }, { value: 'minecraft:diamond', count: 1 }]);
    expect(expandCounted(counted)).toEqual(['ruby', 'ruby', 'minecraft:diamond']);
    expect(countedItems(undefined)).toEqual([]);
  });

  it('keeps entries the chips cannot show', () => {
    const list = ['ruby', { item: 'ruby', chance: 0.5 }];
    expect(countedItems(list)).toEqual([{ value: 'ruby', count: 1 }]);
    expect(opaqueEntries(list)).toEqual([{ item: 'ruby', chance: 0.5 }]);
  });
});

describe('spatial rows', () => {
  it('reads rows as authored, filling the type\'s defaults', () => {
    const rows = rowsOf([{ offset: [0.5, 1], width: 2 }, 'junk']);
    expect(rows[0]).toMatchObject({ offset: [0.5, 1, 0], width: 2, height: 1, level: null });
    expect(rows[1]!.offset).toEqual([0, 0, 0]);
  });

  it('snaps to 5 cm and always writes three numbers', () => {
    expect(snap(0.52)).toBe(0.5);
    expect(snap(-0.13)).toBe(-0.15);
    expect(toOffset([0.51])).toEqual([0.5, 0, 0]);
  });
});
