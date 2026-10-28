import { describe, expect, it } from 'vitest';
import { assetChoices, freshTextureReference, matchesChoice, normalizedReference } from '../src/lib/asset-choice';

const assets = [
  { path: 'assets/textures/items/ruby.png', kind: 'png', bytes: 1 },
  { path: 'assets/textures/gem.jpg', kind: 'jpg', bytes: 1 },
  { path: 'assets/textures/items/notes.txt', kind: 'txt', bytes: 1 },
  { path: 'assets/models/items/ruby.json', kind: 'json', bytes: 1 },
  { path: 'assets/models/sword.json', kind: 'json', bytes: 1 },
  { path: '.materione/thumbnails/items/ruby.png', kind: 'png', bytes: 1 },
];

describe('asset choices', () => {
  it('offers textures as extensionless paths and models as pack references, sorted by folder', () => {
    expect(assetChoices(assets, 'p', 'texture').map((c) => [c.stored, c.dir, c.base])).toEqual([
      ['gem', '', 'gem'],
      ['items/ruby', 'items', 'ruby'],
    ]);
    expect(assetChoices(assets, 'p', 'model').map((c) => c.stored)).toEqual(['p:sword', 'p:items/ruby']);
  });

  it('compares references regardless of the pack prefix', () => {
    expect(normalizedReference('p:items/ruby', 'p', 'texture')).toBe('items/ruby');
    expect(normalizedReference('items/ruby', 'p', 'texture')).toBe('items/ruby');
    expect(normalizedReference('sword', 'p', 'model')).toBe('p:sword');
    expect(normalizedReference('', 'p', 'model')).toBe('');
  });

  it('searches the whole path', () => {
    const [choice] = assetChoices(assets, 'p', 'texture').slice(1);
    expect(matchesChoice(choice!, 'ITEMS/ru')).toBe(true);
    expect(matchesChoice(choice!, 'gem')).toBe(false);
    expect(matchesChoice(choice!, '  ')).toBe(true);
  });

  it('proposes a texture path nothing uses yet', () => {
    expect(freshTextureReference(assets, 'items', 'ruby')).toBe('items/ruby_2');
    expect(freshTextureReference(assets, 'items', 'topaz')).toBe('items/topaz');
  });
});
