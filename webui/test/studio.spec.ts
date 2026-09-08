/**
 * The studio's pure rules: which fields it may render, which it must hand to
 * Advanced Files untouched, what a save actually sends, and the crafting grid
 * round-trip that keeps the YAML canonical.
 */
import { describe, expect, it } from 'vitest';
import {
  blankRow,
  changedFields,
  gridFrom,
  gridTo,
  isRenderable,
  sanitizeSoundName,
  sectionsOf,
  soundAssetPath,
  unrenderableKeys,
} from '../src/lib/studio';
import type { TypeInfo, TypeParam } from '../src/lib/api';

function param(id: string, extra: Partial<TypeParam> = {}): TypeParam {
  return { id, type: 'string', required: false, ...extra };
}

const itemType: TypeInfo = {
  name: 'item',
  directory: 'items',
  displayName: 'Item',
  description: '',
  parameters: [
    param('display_name', { section: 'identity', order: 10, uiHint: 'text' }),
    param('material', { section: 'identity', order: 20, uiHint: 'select' }),
    param('texture', { section: 'appearance', order: 10, uiHint: 'texture' }),
    param('lore', { type: 'list', section: 'gameplay', order: 10, uiHint: 'list' }),
    param('mechanics', { type: 'raw', section: 'behavior', order: 10, uiHint: 'advanced' }),
  ],
};

describe('isRenderable', () => {
  it('accepts the widgets the studio has controls for', () => {
    expect(isRenderable(param('a', { uiHint: 'text' }))).toBe(true);
    expect(isRenderable(param('a', { uiHint: 'texture' }))).toBe(true);
    expect(isRenderable(param('a', { uiHint: 'recipe-grid' }))).toBe(true);
  });

  it('refuses free-form widgets that YAML owns', () => {
    expect(isRenderable(param('a', { uiHint: 'advanced' }))).toBe(false);
  });

  it('renders a structured list only when the shape is declared', () => {
    expect(isRenderable(param('hitboxes', { uiHint: 'structured-list' }))).toBe(false);
    expect(
      isRenderable(
        param('hitboxes', { uiHint: 'structured-list', nested: [param('width')] }),
      ),
    ).toBe(true);
  });
});

describe('sectionsOf', () => {
  it('groups by registry section, in reading order, sorted by declared order', () => {
    const sections = sectionsOf(itemType);
    expect(sections.map((section) => section.id)).toEqual([
      'identity',
      'appearance',
      'gameplay',
    ]);
    expect(sections[0]!.fields.map((field) => field.id)).toEqual(['display_name', 'material']);
  });

  it('drops nothing silently — unrenderable fields are simply absent', () => {
    const rendered = sectionsOf(itemType).flatMap((section) => section.fields.map((f) => f.id));
    expect(rendered).not.toContain('mechanics');
  });

  it('survives a type with no schema', () => {
    expect(sectionsOf(undefined)).toEqual([]);
  });
});

describe('unrenderableKeys', () => {
  it('reports document keys the studio cannot edit, known or unknown', () => {
    const body = { display_name: 'Ruby', mechanics: {}, future_component: 1 };
    expect(unrenderableKeys(body, itemType)).toEqual(['mechanics', 'future_component']);
  });

  it('reports every key when the type has no schema', () => {
    expect(unrenderableKeys({ a: 1 }, undefined)).toEqual(['a']);
  });
});

describe('changedFields', () => {
  it('sends only what actually changed', () => {
    const loaded = { display_name: 'Ruby', material: 'STICK', lore: ['a'] };
    const draft = { display_name: 'Ruby Sword', material: 'STICK', lore: ['a'] };
    expect(changedFields(loaded, draft)).toEqual(['display_name']);
  });

  it('treats deep-equal structures as unchanged, so an untouched save is a no-op', () => {
    const loaded = { hitboxes: [{ width: 1, offset: [0, 0, 0] }] };
    const draft = { hitboxes: [{ width: 1, offset: [0, 0, 0] }] };
    expect(changedFields(loaded, draft)).toEqual([]);
  });

  it('notices a newly added field', () => {
    expect(changedFields({}, { texture: 'item/ruby' })).toEqual(['texture']);
  });
});

describe('blankRow', () => {
  it('builds a row from the declared shape and its defaults', () => {
    const field = param('hitboxes', {
      uiHint: 'structured-list',
      nested: [
        param('type', { default: 'interaction' }),
        param('offset', { type: 'list', default: '[0, 0.5, 0]' }),
        param('width', { type: 'decimal', default: '1' }),
      ],
    });
    expect(blankRow(field)).toEqual({ type: 'interaction', offset: [0, 0.5, 0], width: 1 });
  });
});

describe('recipe grid', () => {
  it('reads shape + ingredients as a 3x3 grid', () => {
    const { cells } = gridFrom(['A A', ' B '], { A: 'demo:ruby', B: 'STICK' });
    expect(cells).toEqual([
      'demo:ruby',
      '',
      'demo:ruby',
      '',
      'STICK',
      '',
      '',
      '',
      '',
    ]);
  });

  it('writes the smallest shape that describes the grid', () => {
    const cells = ['demo:ruby', '', 'demo:ruby', '', 'STICK', '', '', '', ''];
    expect(gridTo(cells)).toEqual({
      shape: ['A A', ' B '],
      ingredients: { A: 'demo:ruby', B: 'STICK' },
    });
  });

  it('round-trips, so opening and saving a recipe does not rewrite it', () => {
    const shape = ['A A', ' B '];
    const ingredients = { A: 'demo:ruby', B: 'STICK' };
    const again = gridTo(gridFrom(shape, ingredients).cells);
    expect(again.shape).toEqual(shape);
    expect(again.ingredients).toEqual(ingredients);
  });

  it('keeps one empty row for an empty grid rather than emitting nothing', () => {
    expect(gridTo(Array.from({ length: 9 }, () => ''))).toEqual({
      shape: [' '],
      ingredients: {},
    });
  });

  it('ignores symbols the ingredient map does not define', () => {
    expect(gridFrom(['XXX'], {}).cells.every((cell) => cell === '')).toBe(true);
  });
});

describe('soundAssetPath', () => {
  it('lands a bare source under assets/sounds with its extension restored', () => {
    expect(soundAssetPath('ui/anthem.ogg')).toBe('assets/sounds/ui/anthem.ogg');
    expect(soundAssetPath('ui/anthem')).toBe('assets/sounds/ui/anthem.ogg');
  });

  it('strips a namespace prefix, which the validator also ignores', () => {
    expect(soundAssetPath('sample:ui/anthem')).toBe('assets/sounds/ui/anthem.ogg');
  });

  it('normalizes backslashes and the .oga variant', () => {
    expect(soundAssetPath('ui\\anthem.OGA')).toBe('assets/sounds/ui/anthem.ogg');
  });
});

describe('sanitizeSoundName', () => {
  it('reduces an uploaded file name to a valid source ref', () => {
    expect(sanitizeSoundName('My Sound.ogg')).toBe('my_sound');
    expect(sanitizeSoundName('C:\\Music\\level-up.OGG')).toBe('level-up');
  });

  it('takes the basename and drops characters the asset grammar refuses', () => {
    expect(sanitizeSoundName('boom!.ogg')).toBe('boom');
    expect(sanitizeSoundName('a//b.ogg')).toBe('b');
    expect(sanitizeSoundName('v1.2.ogg')).toBe('v1.2');
  });

  it('refuses a name with nothing left', () => {
    expect(sanitizeSoundName('???')).toBeNull();
    expect(sanitizeSoundName('.ogg')).toBeNull();
  });
});
