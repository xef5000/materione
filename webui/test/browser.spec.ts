/**
 * The Content browser's listing rules: one folder at a time, folders first,
 * search that reaches the whole type, and type tabs that follow the registry.
 */
import { describe, expect, it } from 'vitest';
import {
  breadcrumbs,
  currentFolder,
  humanise,
  idFromName,
  listing,
  parentFolder,
  typeTabs,
} from '../src/lib/browser';
import type { ResourceRef, TypeInfo } from '../src/lib/api';

const resources: ResourceRef[] = [
  { pack: 'demo', type: 'items', path: 'items/ruby_sword.yml', id: 'ruby_sword' },
  { pack: 'demo', type: 'items', path: 'items/weapons/iron_dagger.yml', id: 'iron_dagger' },
  { pack: 'demo', type: 'items', path: 'items/weapons/rare/excalibur.yml', id: 'excalibur' },
  { pack: 'demo', type: 'items', path: 'items/bundle.yml', id: 'first' },
  { pack: 'demo', type: 'items', path: 'items/bundle.yml', id: 'second' },
  { pack: 'demo', type: 'blocks', path: 'blocks/ruby_ore.yml', id: 'ruby_ore' },
];

const folders = ['items', 'items/weapons', 'items/weapons/rare', 'blocks'];

describe('listing', () => {
  it('shows one folder at a time, subfolders first', () => {
    const rows = listing('items', '', resources, folders);
    expect(rows.map((row) => `${row.kind}:${row.name}`)).toEqual([
      'folder:weapons',
      'doc:first',
      'doc:ruby_sword',
    ]);
    expect(rows[0]!.count).toBe(2);
  });

  it('keeps every id declared by one file on that file s row', () => {
    const bundle = listing('items', '', resources, folders).find((row) => row.name === 'first');
    expect(bundle?.ids).toEqual(['first', 'second']);
    expect(bundle?.path).toBe('items/bundle.yml');
  });

  it('never leaks another type into the tab', () => {
    expect(listing('blocks', '', resources, folders).map((row) => row.name)).toEqual(['ruby_ore']);
  });

  it('descends into a subfolder', () => {
    const rows = listing('items', 'items/weapons', resources, folders);
    expect(rows.map((row) => `${row.kind}:${row.name}`)).toEqual(['folder:rare', 'doc:iron_dagger']);
  });

  it('searches the whole type and reports where each hit lives', () => {
    const rows = listing('items', '', resources, folders, 'excal');
    expect(rows).toHaveLength(1);
    expect(rows[0]!.name).toBe('excalibur');
    expect(rows[0]!.location).toBe('weapons/rare');
  });

  it('matches the way an author reads an id', () => {
    expect(listing('items', '', resources, folders, 'ruby sword')).toHaveLength(1);
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
    expect(tabs.filter((tab) => !tab.enabled).map((tab) => tab.directory)).toEqual([
      'sounds',
      'guis',
    ]);
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
