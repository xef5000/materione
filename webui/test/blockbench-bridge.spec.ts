// Parent-side save-back math for the Blockbench bridge (web.md §6): texture
// mapping onto uploaded targets and model normalization must be exact — the
// exported model is what the client downloads, and a wrong texture ref means
// a purple-black cube in game. Pure functions, pinned here.
import { describe, expect, it } from 'vitest';
import {
  MODEL_FORMAT,
  dataUrlToBase64,
  defaultTextureTarget,
  isBridgeFrame,
  modelTargetsFor,
  normalizeModel,
  sanitizeId,
  synthesiseProject,
  thumbnailPath,
  type SaveTexture,
} from '../src/lib/blockbench/protocol';

/** Deterministic ids, so a synthesised project can be asserted exactly. */
function uuids() {
  let next = 0;
  return () => `uuid-${++next}`;
}

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


describe('synthesiseProject', () => {
  it('opens straight into the entry, with no start screen and no format picker', () => {
    const project = JSON.parse(
      synthesiseProject({ name: 'ruby_sword', typeDirectory: 'items', uuid: uuids() }),
    );
    expect(project.meta.model_format).toBe(MODEL_FORMAT);
    expect(project.name).toBe('ruby_sword');
    expect(project.elements).toHaveLength(1);
    expect(project.outliner).toEqual(['uuid-1']);
  });

  it('starts an item as a thin plate and a block as a full cube', () => {
    const item = JSON.parse(
      synthesiseProject({ name: 'a', typeDirectory: 'items', uuid: uuids() }),
    );
    const block = JSON.parse(
      synthesiseProject({ name: 'a', typeDirectory: 'blocks', uuid: uuids() }),
    );
    expect(item.elements[0].to[2] - item.elements[0].from[2]).toBe(1);
    expect(block.elements[0].to).toEqual([16, 16, 16]);
  });

  it('attaches the entry’s texture so the model is recognisable immediately', () => {
    const project = JSON.parse(
      synthesiseProject({
        name: 'ruby',
        typeDirectory: 'items',
        texture: { link: 'item/ruby', dataUrl: 'data:image/png;base64,AAA' },
        uuid: uuids(),
      }),
    );
    expect(project.textures).toHaveLength(1);
    expect(project.textures[0].source).toBe('data:image/png;base64,AAA');
    expect(project.textures[0].folder).toBe('item');
    expect(project.elements[0].faces.north.texture).toBe(0);
  });

  it('leaves faces untextured when the entry has no texture yet', () => {
    const project = JSON.parse(
      synthesiseProject({ name: 'a', typeDirectory: 'blocks', uuid: uuids() }),
    );
    expect(project.textures).toEqual([]);
    expect(project.elements[0].faces.up.texture).toBeNull();
  });
});

describe('modelTargetsFor', () => {
  it('derives every path from the owning entry, so saving needs no dialog', () => {
    expect(modelTargetsFor('demo', 'ruby_sword')).toEqual({
      modelPath: 'assets/models/ruby_sword.json',
      sourcePath: 'assets/models/source/ruby_sword.bbmodel',
      modelRef: 'demo:ruby_sword',
    });
  });

  it('keeps an existing reference, because renaming it would break the pack', () => {
    expect(modelTargetsFor('demo', 'ruby_sword', 'demo:weapons/ruby')).toEqual({
      modelPath: 'assets/models/weapons/ruby.json',
      sourcePath: 'assets/models/source/weapons/ruby.bbmodel',
      modelRef: 'demo:weapons/ruby',
    });
  });

  it('respects a reference owned by another pack', () => {
    expect(modelTargetsFor('demo', 'x', 'shared:props/chair').modelRef).toBe('shared:props/chair');
  });

  it('sanitises an id that is not path-safe', () => {
    expect(modelTargetsFor('demo', 'Ruby Sword!').modelPath).toBe('assets/models/ruby_sword.json');
  });
});

describe('thumbnailPath', () => {
  it('caches renders as authoring metadata, never inside the shipped pack', () => {
    expect(thumbnailPath('demo:weapons/ruby')).toBe('.materione/thumbnails/weapons/ruby.png');
  });
});
