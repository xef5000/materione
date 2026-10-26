/**
 * The Content browser's listing rules: one folder at a time, folders first,
 * search that reaches the whole type, and type tabs that follow the registry.
 */
import { describe, expect, it } from 'vitest';
import {
  breadcrumbs,
  currentFolder,
  getCatalogPreferences,
  humanise,
  idFromName,
  listing,
  materialDisplayName,
  parentFolder,
  resourceIdentity,
  saveCatalogPreferences,
  typeTabs,
} from '../src/lib/browser';
import type { ResourceRef, ResourceSummary, TypeInfo } from '../src/lib/api';

const resources: ResourceRef[] = [
  { pack: 'demo', type: 'items', path: 'items/ruby_sword.yml', id: 'ruby_sword' },
  { pack: 'demo', type: 'items', path: 'items/weapons/iron_dagger.yml', id: 'iron_dagger' },
  { pack: 'demo', type: 'items', path: 'items/weapons/rare/excalibur.yml', id: 'excalibur' },
  { pack: 'demo', type: 'items', path: 'items/bundle.yml', id: 'first' },
  { pack: 'demo', type: 'items', path: 'items/bundle.yml', id: 'second' },
  { pack: 'demo', type: 'blocks', path: 'blocks/ruby_ore.yml', id: 'ruby_ore' },
];

const folders = ['items', 'items/weapons', 'items/weapons/rare', 'blocks'];

class MemoryStorage {
  private values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

describe('listing', () => {
  it('shows one folder at a time, subfolders first', () => {
    const rows = listing('items', '', resources, folders);
    expect(rows.map((row) => `${row.kind}:${row.name}`)).toEqual([
      'folder:weapons',
      'doc:First',
      'doc:Ruby sword',
      'doc:Second',
    ]);
    expect(rows[0]!.count).toBe(2);
  });

  it('keeps shared-file roots as distinct resource rows and preserves their file identity', () => {
    const bundle = listing('items', '', resources, folders)
      .filter((row) => row.path === 'items/bundle.yml');
    expect(bundle.map((row) => row.id)).toEqual(['first', 'second']);
    expect(bundle.map((row) => row.ids)).toEqual([['first', 'second'], ['first', 'second']]);
    expect(bundle[0]?.key).not.toBe(bundle[1]?.key);
    expect(bundle[1]?.resource).toMatchObject({ id: 'second', path: 'items/bundle.yml' });
  });

  it('never leaks another type into the tab', () => {
    expect(listing('blocks', '', resources, folders).map((row) => row.name)).toEqual(['Ruby ore']);
  });

  it('descends into a subfolder', () => {
    const rows = listing('items', 'items/weapons', resources, folders);
    expect(rows.map((row) => `${row.kind}:${row.name}`)).toEqual(['folder:rare', 'doc:Iron dagger']);
  });

  it('searches the whole type and reports where each hit lives', () => {
    const rows = listing('items', '', resources, folders, 'excal');
    expect(rows).toHaveLength(1);
    expect(rows[0]!.name).toBe('Excalibur');
    expect(rows[0]!.location).toBe('weapons/rare');
  });

  it('matches the way an author reads an id', () => {
    expect(listing('items', '', resources, folders, 'ruby sword')).toHaveLength(1);
  });

  it('searches a server-provided human name and keeps the exact resource root', () => {
    const ruby = resources[0]!;
    const summaries: ResourceSummary[] = [{
      ...ruby,
      name: 'Ember Blade',
      material: 'Netherite sword',
      health: 'ok',
    }];
    const rows = listing('items', '', resources, folders, 'ember blade', summaries);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ id: 'ruby_sword', name: 'Ember Blade', summary: summaries[0] });
  });

  it('shows and searches formatted names without legacy color codes', () => {
    const ruby = resources[0]!;
    const summaries: ResourceSummary[] = [{
      ...ruby,
      name: '&aEmber &lBlade',
      material: 'minecraft:netherite_sword',
      health: 'ok',
    }];
    const rows = listing('items', '', resources, folders, 'ember blade', summaries);
    expect(rows[0]?.name).toBe('Ember Blade');
    expect(materialDisplayName(summaries[0]!.material!)).toBe('Netherite Sword');
    expect(listing('items', '', resources, folders, 'netherite sword', summaries))
      .toHaveLength(1);
  });

  it('uses pack, path and id together as a stable resource identity', () => {
    expect(resourceIdentity({
      pack: 'demo', path: 'items/bundle.yml', id: 'first',
    })).not.toBe(resourceIdentity({
      pack: 'demo', path: 'items/bundle.yml', id: 'second',
    }));
  });
});

describe('navigation', () => {
  it('treats an empty selection as the type root', () => {
    expect(currentFolder('items', '')).toBe('items');
    expect(currentFolder('items', 'items/weapons')).toBe('items/weapons');
  });

  it('walks up to the root and stops there', () => {
    expect(parentFolder('items', 'items/weapons/rare')).toBe('items/weapons');
    expect(parentFolder('items', 'items/weapons')).toBe('items');
    expect(parentFolder('items', 'items')).toBe('');
  });

  it('labels the root crumb with the type name', () => {
    expect(breadcrumbs('items', 'items/weapons')).toEqual([
      { label: 'Items', folder: 'items' },
      { label: 'weapons', folder: 'items/weapons' },
    ]);
  });
});

describe('typeTabs', () => {
  it('follows the registry and puts everyday types first', () => {
    const types: TypeInfo[] = [
      { name: 'recipe', directory: 'recipes', displayName: 'Recipe', description: '', parameters: [] },
      { name: 'item', directory: 'items', displayName: 'Item', description: '', parameters: [] },
      { name: 'exotic', directory: 'exotics', displayName: 'Exotic', description: '', parameters: [] },
    ];
    const tabs = typeTabs(types);
    expect(tabs.filter((tab) => tab.enabled).map((tab) => tab.directory)).toEqual([
      'items',
      'recipes',
      'exotics',
    ]);
    expect(tabs.filter((tab) => !tab.enabled).map((tab) => tab.directory)).toEqual(['guis']);
  });

  it('replaces a planned placeholder once the type registers', () => {
    const types: TypeInfo[] = [
      { name: 'sound', directory: 'sounds', displayName: 'Sound', description: '', parameters: [] },
    ];
    const tabs = typeTabs(types);
    const soundTabs = tabs.filter((tab) => tab.directory === 'sounds');
    expect(soundTabs).toHaveLength(1);
    expect(soundTabs[0]).toMatchObject({ label: 'Sounds', enabled: true });
  });

  it('replaces a planned placeholder once the type registers', () => {
    const types: TypeInfo[] = [
      { name: 'sound', directory: 'sounds', displayName: 'Sound', description: '', parameters: [] },
    ];
    const tabs = typeTabs(types);
    const soundTabs = tabs.filter((tab) => tab.directory === 'sounds');
    expect(soundTabs).toHaveLength(1);
    expect(soundTabs[0]).toMatchObject({ label: 'Sounds', enabled: true });
  });

  it('names a registered type it has never seen', () => {
    const tabs = typeTabs([
      { name: 'exotic', directory: 'exotics', displayName: 'Exotic', description: '', parameters: [] },
    ]);
    expect(tabs[0]).toMatchObject({ label: 'Exotics', singular: 'exotic', enabled: true });
  });
});

describe('names', () => {
  it('reads ids as words', () => {
    expect(humanise('ruby_sword')).toBe('Ruby sword');
  });

  it('derives a legal id from anything typed', () => {
    expect(idFromName('  Ruby Sword! ')).toBe('ruby_sword');
    expect(idFromName('***')).toBe('');
  });
});

describe('catalog preferences', () => {
  it('stores only per-type presentation choices and leaves other types at defaults', () => {
    const storage = new MemoryStorage();
    saveCatalogPreferences('items', { layout: 'list', showTechnicalColumns: true }, storage);

    expect(getCatalogPreferences('items', storage)).toEqual({
      layout: 'list',
      showTechnicalColumns: true,
    });
    expect(getCatalogPreferences('recipes', storage)).toEqual({
      layout: 'grid',
      showTechnicalColumns: false,
    });
    const stored = JSON.parse(storage.getItem('materione.content-catalog.preferences.v1')!);
    expect(stored).toEqual({
      items: { layout: 'list', showTechnicalColumns: true },
    });
  });

  it('recovers from malformed preference storage without affecting catalog content', () => {
    const storage = new MemoryStorage();
    storage.setItem('materione.content-catalog.preferences.v1', '{');
    expect(getCatalogPreferences('items', storage)).toEqual({
      layout: 'grid',
      showTechnicalColumns: false,
    });
  });
});
