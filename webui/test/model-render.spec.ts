import { describe, expect, it, vi } from 'vitest';
import {
  defaultUv,
  flatLayers,
  guiShade,
  parseRef,
  projectFaces,
  resolveModel,
  textureSource,
  uvTransform,
  type ModelJson,
  type ModelLoader,
} from '../src/lib/model-render';

const PACKS = ['demo'];

function loader(models: Record<string, ModelJson> = {}): ModelLoader & { readModel: ReturnType<typeof vi.fn> } {
  return {
    readModel: vi.fn(async (pack: string, path: string) => {
      const model = models[`${pack}/${path}`];
      if (!model) throw new Error(`missing ${pack}/${path}`);
      return model;
    }),
  };
}

describe('model resolution', () => {
  it('resolves a generated cube_all through the built-in parents', async () => {
    const model = await resolveModel({ parent: 'block/cube_all', textures: { all: 'demo:block/marble' } }, PACKS, loader());
    expect(model.kind).toBe('elements');
    expect(model.elements).toHaveLength(1);
    expect(model.gui.rotation).toEqual([30, 225, 0]);
    expect(model.shaded).toBe(true);
    expect(textureSource('#north', model.textures, PACKS)).toEqual({ pack: 'demo', path: 'assets/textures/block/marble.png' });
  });

  it('reads pack parents through the loader and lets child textures win', async () => {
    const models = loader({
      'demo/assets/models/base.json': { parent: 'minecraft:block/cube_all', textures: { all: 'demo:block/a' } },
    });
    const model = await resolveModel({ parent: 'demo:base', textures: { all: 'demo:block/b' } }, PACKS, models);
    expect(models.readModel).toHaveBeenCalledWith('demo', 'assets/models/base.json');
    expect(model.textures.all).toBe('demo:block/b');
  });

  it('keeps hand-authored elements and inherits the stairs pose', async () => {
    const model = await resolveModel({ parent: 'block/stairs', textures: { side: 'demo:s', top: 'demo:s', bottom: 'demo:s' } }, PACKS, loader());
    expect(model.elements).toHaveLength(2);
    expect(model.gui.rotation).toEqual([30, 135, 0]);
  });

  it('draws item/generated models as flat, unshaded layers in order', async () => {
    const model = await resolveModel({ parent: 'minecraft:item/handheld', textures: { layer1: 'demo:item/b', layer0: 'demo:item/a' } }, PACKS, loader());
    expect(model.kind).toBe('flat');
    expect(model.shaded).toBe(false);
    expect(flatLayers(model, PACKS)).toEqual([
      { pack: 'demo', path: 'assets/textures/item/a.png' },
      { pack: 'demo', path: 'assets/textures/item/b.png' },
    ]);
  });

  it('rejects models whose geometry lives in an unknown vanilla parent', async () => {
    await expect(resolveModel({ parent: 'minecraft:block/anvil', textures: { body: 'demo:x' } }, PACKS, loader()))
      .rejects.toThrow(/no elements/);
  });

  it('rejects unsafe or unknown-pack parents', async () => {
    await expect(resolveModel({ parent: 'demo:../escape' }, PACKS, loader())).rejects.toThrow(/Unsafe/);
    await expect(resolveModel({ parent: 'other:base' }, PACKS, loader())).rejects.toThrow(/Unknown pack/);
  });

  it('stops on parent cycles instead of looping', async () => {
    const models = loader({ 'demo/assets/models/loop.json': { parent: 'demo:loop' } });
    await expect(resolveModel({ parent: 'demo:loop' }, PACKS, models)).rejects.toThrow(/no elements/);
    expect(models.readModel.mock.calls.length).toBeLessThanOrEqual(17);
  });
});

describe('texture references', () => {
  it('uses the placeholder for vanilla, unknown-pack and dangling references', () => {
    expect(textureSource('minecraft:block/stone', {}, PACKS)).toEqual({ placeholder: true });
    expect(textureSource('block/stone', {}, PACKS)).toEqual({ placeholder: true });
    expect(textureSource('other:block/x', {}, PACKS)).toEqual({ placeholder: true });
    expect(textureSource('#missing', {}, PACKS)).toEqual({ placeholder: true });
    expect(textureSource('#a', { a: '#a' }, PACKS)).toEqual({ placeholder: true });
  });

  it('parses namespaced references like the game', () => {
    expect(parseRef('block/stone')).toEqual({ namespace: 'minecraft', path: 'block/stone' });
    expect(parseRef('demo:block/x')).toEqual({ namespace: 'demo', path: 'block/x' });
    expect(parseRef('demo:/etc')).toBeUndefined();
    expect(parseRef('Demo:x')).toBeUndefined();
  });
});

describe('projection', () => {
  it('shows a cube as top, left and right faces with inventory shading', async () => {
    const model = await resolveModel({ parent: 'block/cube_all', textures: { all: 'demo:t' } }, PACKS, loader());
    const faces = projectFaces(model, PACKS, 128);
    expect(faces).toHaveLength(3);
    const shades = faces.map((face) => Number(face.shade.toFixed(2))).sort();
    expect(shades).toEqual([0.6, 0.8, 1]);
    // The cube fits the canvas: 16 units × 0.625 × √2 wide ≈ 113 px of 128.
    const xs = faces.flatMap((face) => face.corners.map(([x]) => x));
    expect(Math.max(...xs) - Math.min(...xs)).toBeCloseTo(113.1, 0);
  });

  it('orders faces back to front for painting', async () => {
    const model = await resolveModel({ parent: 'block/stairs', textures: { side: 'demo:t', top: 'demo:t', bottom: 'demo:t' } }, PACKS, loader());
    const depths = projectFaces(model, PACKS, 128).map((face) => face.depth);
    expect(depths).toEqual([...depths].sort((a, b) => a - b));
  });

  it('honours shade: false on elements', () => {
    const faces = projectFaces({
      kind: 'elements', textures: {}, gui: {}, shaded: true,
      elements: [{ from: [0, 0, 8], to: [16, 16, 8], shade: false, faces: { south: { texture: '#x' } } }],
    }, PACKS, 64);
    expect(faces).toHaveLength(1);
    expect(faces[0]!.shade).toBe(1);
  });

  it('matches the game shading anchors', () => {
    expect(guiShade([0, 0.866, 0.5])).toBeCloseTo(1, 2);
    expect(guiShade([-0.707, -0.354, 0.612])).toBeCloseTo(0.8, 2);
    expect(guiShade([0.707, -0.354, 0.612])).toBeCloseTo(0.6, 2);
  });
});

describe('uv mapping', () => {
  const square: [number, number][] = [[0, 0], [16, 0], [16, 16], [0, 16]];

  it('maps an unrotated full face to identity', () => {
    expect(uvTransform(square, [0, 0, 16, 16], 0)).toEqual([1, 0, 0, 1, 0, 0]);
  });

  it('turns the texture clockwise for rotation 90', () => {
    const [a, b, c, d, e, f] = uvTransform(square, [0, 0, 16, 16], 90)!;
    // uv (0,0) lands on the face's top-right corner.
    expect([a * 0 + c * 0 + e, b * 0 + d * 0 + f]).toEqual([16, 0]);
  });

  it('handles flipped and zero-width uvs', () => {
    const [a] = uvTransform(square, [16, 0, 0, 16], 0)!;
    expect(a).toBe(-1);
    expect(uvTransform(square, [4, 0, 4, 16], 0)).toBeDefined();
  });

  it('derives the default uv from element bounds', () => {
    expect(defaultUv('up', [2, 0, 4], [14, 8, 12])).toEqual([2, 4, 14, 12]);
    expect(defaultUv('north', [2, 0, 4], [14, 8, 12])).toEqual([2, 8, 14, 16]);
  });
});
