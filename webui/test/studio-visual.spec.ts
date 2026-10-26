import { describe, expect, it } from 'vitest';
import { localStudioVisual } from '../src/lib/studio-visual';
const assets = [
  { path: 'assets/textures/item/ruby.png', kind: 'png', bytes: 1 },
  { path: 'assets/textures/item/photo.jpg', kind: 'jpg', bytes: 1 },
  { path: '.materione/thumbnails/item/gem.png', kind: 'png', bytes: 1 },
];
describe('draft visual fallback', () => {
  it('uses an existing model thumbnail before an inspection texture', () => {
    expect(localStudioVisual('pack', 'item/ruby', 'pack:item/gem', assets)).toEqual({
      pack: 'pack', path: '.materione/thumbnails/item/gem.png', kind: 'model-thumbnail',
    });
  });
  it('uses the actual texture extension', () => {
    expect(localStudioVisual('pack', 'item/photo', '', assets)?.path).toBe('assets/textures/item/photo.jpg');
    expect(localStudioVisual('pack', 'item/ruby.png', '', assets)?.path).toBe('assets/textures/item/ruby.png');
  });
  it('does not invent missing assets or assume namespace names are pack folders', () => {
    expect(localStudioVisual('pack', 'item/missing', '', assets)).toBeUndefined();
    expect(localStudioVisual('pack', 'other_namespace:item/ruby', '', assets)).toBeUndefined();
    expect(localStudioVisual('pack', '', 'other_namespace:item/gem', assets)).toBeUndefined();
  });
});
