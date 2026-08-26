import { describe, expect, it } from 'vitest';
import { buildTree, filterTree } from '../src/lib/tree';
import type { AssetRef, ResourceRef } from '../src/lib/api';

const resource = (path: string, id: string): ResourceRef => ({
  pack: 'demo',
  type: path.split('/')[0]!,
  path,
  id,
});

const asset = (path: string, bytes = 100): AssetRef => ({
  path,
  bytes,
  kind: path.slice(path.lastIndexOf('.') + 1),
});

describe('buildTree', () => {
  it('groups multi-resource files and nests directories', () => {
    const tree = buildTree(
      [
        resource('items/gem.yml', 'ruby'),
        resource('items/gem.yml', 'emerald'),
        resource('items/swords/longsword.yml', 'longsword'),
        resource('recipes/ruby_sword.yml', 'ruby_sword'),
      ],
      [],
    );

    const names = tree.map((node) => node.name);
    expect(names).toEqual(['items', 'recipes']);

    const items = tree[0]!;
    expect(items.children.map((child) => child.name)).toEqual(['gem.yml', 'swords']);
    const gem = items.children[0]!;
    expect(gem.kind).toBe('doc');
    expect(gem.resourceIds).toEqual(['emerald', 'ruby']);

    const swords = items.children[1]!;
    expect(swords.children[0]!.path).toBe('items/swords/longsword.yml');
  });

  it('merges assets into the same tree under assets/', () => {
    const tree = buildTree(
      [resource('blocks/lamp.yml', 'lamp')],
      [asset('assets/textures/block/lamp.png'), asset('assets/models/lamp.json')],
    );

    const assetsRoot = tree.find((node) => node.name === 'assets');
    expect(assetsRoot).toBeDefined();
    const textures = assetsRoot!.children.find((child) => child.name === 'textures');
    const block = textures!.children.find((child) => child.name === 'block');
    const lampPng = block!.children.find((child) => child.name === 'lamp.png');
    expect(lampPng).toMatchObject({ kind: 'asset', assetKind: 'png', bytes: 100 });
  });

  it('orders type dirs canonically with assets after them', () => {
    const tree = buildTree([], [asset('assets/a.png')]);
    // No documents: only the assets branch exists.
    expect(tree.map((node) => node.name)).toEqual(['assets']);
  });
});

describe('filterTree', () => {
  const tree = buildTree([resource('items/gem.yml', 'ruby_gem')], []);

  it('keeps ancestors of matches', () => {
    const filtered = filterTree(tree, 'gem');
    expect(filtered).toHaveLength(1);
    expect(filtered[0]!.name).toBe('items');
    expect(filtered[0]!.children[0]!.name).toBe('gem.yml');
  });

  it('matches on resource ids as well as file names', () => {
    expect(filterTree(tree, 'ruby_gem')).toHaveLength(1);
    expect(filterTree(tree, 'zzz-nothing')).toHaveLength(0);
  });

  it('returns everything untouched for a blank query', () => {
    expect(filterTree(tree, '   ')).toBe(tree);
  });
});
