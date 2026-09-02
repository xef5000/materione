// Parent-side save-back math for the Blockbench bridge (web.md §6): texture
// mapping onto uploaded targets and model normalization must be exact — the
// exported model is what the client downloads, and a wrong texture ref means
// a purple-black cube in game. Pure functions, pinned here.
import { describe, expect, it } from 'vitest';
import {
  dataUrlToBase64,
  defaultTextureTarget,
  isBridgeFrame,
  normalizeModel,
  sanitizeId,
  type SaveTexture,
} from '../src/lib/blockbench/protocol';

describe('bridge frames', () => {
  it('accepts marked messages and rejects everything else', () => {
    expect(isBridgeFrame({ __materione: true, type: 'ready' })).toBe(true);
    expect(isBridgeFrame({ type: 'ready' })).toBe(false);
    expect(isBridgeFrame(null)).toBe(false);
    expect(isBridgeFrame('ready')).toBe(false);
  });
});

describe('texture targets', () => {
  it('defaults to the java link, namespace dropped', () => {
    const texture: SaveTexture = { id: 'ruby', link: 'sample:item/ruby', name: 'ruby.png', dataUrl: '' };
    expect(defaultTextureTarget(texture)).toBe('item/ruby');
  });

  it('falls back to an item/ path when the link is a bare ref', () => {
    const texture: SaveTexture = { id: 'gem', link: '#gem', name: 'gem.png', dataUrl: '' };
    expect(defaultTextureTarget(texture)).toBe('item/gem');
  });

  it('strips data url prefixes down to base64', () => {
    expect(dataUrlToBase64('data:image/png;base64,QUJD')).toBe('QUJD');
    expect(dataUrlToBase64('QUJD')).toBe('QUJD');
  });

  it('sanitizes project names into safe ids', () => {
    expect(sanitizeId('Fancy Chair!')).toBe('fancy_chair');
    expect(sanitizeId('///')).toBe('model');
  });
});

describe('normalizeModel', () => {
  it('rewrites texture refs to pack-namespaced targets', () => {
    const model = JSON.stringify({
      textures: { particle: 'sample:item/ruby', layer0: 'item/ruby', other: '#other' },
      elements: [],
    });
    const result = normalizeModel(model, 'sample', [{ link: 'sample:item/ruby', target: 'item/gem' }]);
    const parsed = JSON.parse(result.model);
    expect(parsed.textures.particle).toBe('sample:item/gem');
    expect(parsed.textures.layer0).toBe('sample:item/gem');
    expect(parsed.textures.other).toBe('#other'); // self-refs stay
    expect(result.unresolved).toEqual([]);
  });

  it('reports dangling texture links instead of failing silently', () => {
    const model = JSON.stringify({ textures: { layer0: 'block/missing' } });
    const result = normalizeModel(model, 'sample', []);
    expect(result.unresolved).toEqual(['block/missing']);
  });

  it('leaves unparseable models untouched', () => {
    const result = normalizeModel('not json', 'sample', []);
    expect(result.model).toBe('not json');
    expect(result.unresolved).toEqual([]);
  });
});
