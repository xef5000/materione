import { describe, expect, it } from 'vitest';
import { searchItems, selectableItems } from '../src/lib/templates';
import { itemLinks } from '../src/lib/demo/templates';
import type { TemplateDocument } from '../src/lib/demo/templates';

// The picker offers items too (plugin.md section 3.1): never the item itself, never a choice that
// closes a loop, never an id a template also has, and nothing at all to a resource that is not an item.
describe('items in the template picker', () => {
  const resource = (id: string, type = 'items') => ({ pack: 'demo', type, path: `${type}/${id}.yml`, id });
  const resources = [
    resource('sword'), resource('sword_plus'), resource('sword_plus_plus'), resource('stick'),
    resource('blade'), resource('lamp', 'blocks'),
  ];
  const summaries = [{ pack: 'demo', path: 'items/stick.yml', id: 'stick', name: 'Plain Stick' }];
  const templates = [{ pack: 'demo', id: 'blade' }];
  // sword_plus_plus -> sword_plus -> sword: choosing either for `sword` would close a loop
  const links = [
    { pack: 'demo', id: 'sword_plus', parent: 'demo:sword' },
    { pack: 'demo', id: 'sword_plus_plus', parent: 'demo:sword_plus' },
  ];

  it('offers the pack\'s other items with their names, and never a block', () => {
    const offered = selectableItems(resources, summaries, [], [], { pack: 'demo', id: 'sword' }, 'demo');
    expect(offered.map((choice) => choice.id)).toEqual(['blade', 'stick', 'sword_plus', 'sword_plus_plus']);
    expect(offered.find((choice) => choice.id === 'stick')?.title).toBe('Plain Stick');
    expect(offered.find((choice) => choice.id === 'lamp')).toBeUndefined();
  });

  it('never offers the item itself, an item that would close a loop, or an id a template also has', () => {
    const offered = selectableItems(resources, summaries, templates, links, { pack: 'demo', id: 'sword' }, 'demo');
    expect(offered.map((choice) => choice.id)).toEqual(['stick']); // not sword, not its descendants, not blade
    // a descendant may take an ancestor: that is a shorter link than the one it has
    const fromLeaf = selectableItems(resources, summaries, templates, links, { pack: 'demo', id: 'sword_plus_plus' }, 'demo');
    expect(fromLeaf.map((choice) => choice.id)).toEqual(['stick', 'sword', 'sword_plus']);
  });

  it('a chain that runs into a template ends there and cannot loop', () => {
    const through = [{ pack: 'demo', id: 'stick', parent: 'demo:blade' }];
    const offered = selectableItems(resources, summaries, templates, through, { pack: 'demo', id: 'sword' }, 'demo');
    expect(offered.map((choice) => choice.id)).toContain('stick');
  });

  it('offers nothing to a resource that is not an item, and nothing from another pack', () => {
    expect(selectableItems(resources, summaries, templates, links, undefined, 'demo')).toEqual([]);
    expect(selectableItems(resources, summaries, [], [], { pack: 'demo', id: 'sword' }, 'other')).toEqual([]);
  });

  it('searches by id and title', () => {
    const offered = selectableItems(resources, summaries, [], [], { pack: 'demo', id: 'sword' }, 'demo');
    expect(searchItems(offered, 'plain').map((choice) => choice.id)).toEqual(['stick']);
    expect(searchItems(offered, 'sword plus').map((choice) => choice.id)).toEqual(['sword_plus', 'sword_plus_plus']);
    expect(searchItems(offered, '')).toHaveLength(offered.length);
  });

  it('the in-tab servers list every item\'s link as a full id, items only', () => {
    const documents = new Map<string, TemplateDocument>([
      ['demo/items/a.yml', { pack: 'demo', path: 'items/a.yml', rev: 1, raw: '', parsed: { a: { template: 'b' } } }],
      ['demo/items/b.yml', { pack: 'demo', path: 'items/b.yml', rev: 1, raw: '', parsed: { b: { material: 'STICK' } } }],
      ['demo/blocks/c.yml', { pack: 'demo', path: 'blocks/c.yml', rev: 1, raw: '', parsed: { c: { template: 'b' } } }],
    ]);
    expect(itemLinks(documents)).toEqual([{ pack: 'demo', id: 'a', parent: 'demo:b' }]);
  });
});
