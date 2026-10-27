/**
 * Module derivation (issue #152): the catalog a module-based studio shows is
 * derived from the type's self-description plus the server's mechanic
 * self-descriptions — never a hand-written editor list. These tests pin that
 * derivation, the display-card split, and presence detection against the same
 * draft shapes a save writes.
 */
import { describe, expect, it } from 'vitest';
import {
  advancedFieldsOf,
  categoriesOf,
  categoryLabel,
  defaultFor,
  displayFieldsOf,
  extraFieldsOf,
  moduleCatalog,
  presenceOf,
  usesModules,
} from '../src/lib/modules';
import type { TypeInfo, TypeParam } from '../src/lib/api';
import type { BehaviorVocabulary } from '../src/lib/behavior';

function param(id: string, extra: Partial<TypeParam> = {}): TypeParam {
  return { id, type: 'string', required: false, ...extra };
}

/** The item type as the registry self-describes it after issue #152. */
function itemSchema(): TypeInfo {
  return {
    name: 'item',
    directory: 'items',
    displayName: 'Item',
    description: '',
    parameters: [
      param('display_name', { uiHint: 'formatted-text' }),
      param('lore', { type: 'list', uiHint: 'formatted-lines' }),
      param('material', {
        type: 'enum', required: true, uiHint: 'material', allowedValues: ['NETHERITE_SWORD'],
        label: 'Base material',
        module: true, category: 'identity', icon: 'box', summary: 'The vanilla item this builds on',
      }),
      param('template', { advanced: true, uiHint: 'reference' }),
      param('texture', { uiHint: 'texture' }),
      param('model', { uiHint: 'model' }),
      param('model_parent', {
        default: 'item/generated', module: true, category: 'appearance', icon: 'layers',
      }),
      param('components', { type: 'raw', advanced: true, uiHint: 'advanced' }),
      param('mechanics', {
        type: 'list', uiHint: 'mechanics', module: true, category: 'behaviour', icon: 'zap',
      }),
      param('behavior', {
        type: 'list', uiHint: 'behavior-rules', module: true, category: 'behaviour',
        icon: 'activity', label: 'When… do…',
      }),
      param('jukebox_song', {
        uiHint: 'reference', module: true, category: 'gameplay', icon: 'volume-2',
      }),
    ],
  };
}

const vocabulary: BehaviorVocabulary = {
  events: [],
  actions: [],
  ruleOptions: [],
  actionOptions: [],
  mechanics: [
    {
      id: 'materione:soulbound', displayName: 'Soulbound', description: 'Stays with its owner',
      repeatable: false, registeredBy: 'Materione', parameters: [],
    },
    {
      id: 'materione:backpack', displayName: 'Backpack', description: 'Travelling container',
      repeatable: false, registeredBy: 'Materione', parameters: [param('rows', { type: 'integer' })],
    },
    {
      id: 'example:charge', displayName: 'Charge', description: 'Stores charge',
      repeatable: true, registeredBy: 'ExampleAddon', parameters: [],
    },
  ],
};

describe('usesModules', () => {
  it('a type joins the module layout only when the schema declares a module', () => {
    expect(usesModules(itemSchema())).toBe(true);
    expect(usesModules({ ...itemSchema(), parameters: [param('display_name')] })).toBe(false);
    expect(usesModules(undefined)).toBe(false);
  });

  it('a module the studio cannot render is not a module', () => {
    const schema = itemSchema();
    schema.parameters = schema.parameters.map((field) =>
      field.id === 'model_parent' ? { ...field, uiHint: 'advanced' } : field);
    expect(usesModules(schema)).toBe(true); // other modules remain
    expect(moduleCatalog(schema, null).some((module) => module.id === 'model_parent')).toBe(false);
  });
});

describe('moduleCatalog', () => {
  it('derives one module per schema field and per registered mechanic', () => {
    const catalog = moduleCatalog(itemSchema(), vocabulary);
    expect(catalog.map((module) => module.id)).toEqual([
      'material',
      'model_parent',
      'mechanic:materione:soulbound',
      'mechanic:materione:backpack',
      'mechanic:example:charge',
      'behavior',
      'jukebox_song',
    ]);
  });

  it('field modules carry the declared label, category, icon and summary', () => {
    const material = moduleCatalog(itemSchema(), vocabulary).find((module) => module.id === 'material');
    expect(material).toMatchObject({
      kind: 'field', label: 'Base material', category: 'identity', icon: 'box',
      summary: 'The vanilla item this builds on', required: true,
    });
  });

  it('a mechanics source expands into modules labelled by the registry, not the field', () => {
    const backpack = moduleCatalog(itemSchema(), vocabulary).find((module) => module.id === 'mechanic:materione:backpack');
    expect(backpack).toMatchObject({
      kind: 'mechanic', label: 'Backpack', category: 'behaviour', sourceField: 'mechanics',
    });
  });

  it('a behavior-rules field is one When… do… module and is never required', () => {
    const rules = moduleCatalog(itemSchema(), vocabulary).find((module) => module.id === 'behavior');
    expect(rules).toMatchObject({ kind: 'rules', label: 'When… do…', required: false });
  });

  it('without a vocabulary there are no mechanic modules (an older jar)', () => {
    const catalog = moduleCatalog(itemSchema(), null);
    expect(catalog.some((module) => module.kind === 'mechanic')).toBe(false);
    expect(catalog.map((module) => module.id)).toEqual(
      ['material', 'model_parent', 'behavior', 'jukebox_song']);
  });

  it('falls back to the section when no category is declared, and to the description for the summary', () => {
    const catalog = moduleCatalog({
      name: 'x', displayName: 'X', description: '',
      parameters: [param('glint', { module: true, description: 'Shiny', section: 'gameplay' })],
    }, null);
    expect(catalog[0]).toMatchObject({ category: 'gameplay', summary: 'Shiny', icon: 'sparkles' });
  });

  it('an icon the registry does not name falls back to the category icon', () => {
    const catalog = moduleCatalog({
      name: 'x', displayName: 'X', description: '',
      parameters: [param('glint', { module: true, category: 'appearance', icon: 'not-an-icon' })],
    }, null);
    expect(catalog[0]!.icon).toBe('image');
  });
});

describe('the display card and the leftovers', () => {
  it('the display card takes the rich-text name, the lore and the texture/model slots', () => {
    const display = displayFieldsOf(itemSchema());
    expect(display.name?.id).toBe('display_name');
    expect(display.lore?.id).toBe('lore');
    expect(display.slots.map((slot) => slot.id)).toEqual(['texture', 'model']);
  });

  it('display fields are never modules, and modules are never display fields', () => {
    const schema = itemSchema();
    const display = displayFieldsOf(schema);
    expect(display.name?.module).toBeFalsy();
    expect(moduleCatalog(schema, null).some((module) => module.id === 'texture')).toBe(false);
  });

  it('advanced fields stay reachable through the disclosure and are not modules', () => {
    const advanced = advancedFieldsOf(itemSchema());
    expect(advanced.map((field) => field.id)).toEqual(['template']);
  });

  it('renderable leftovers keep their classic controls under the modules', () => {
    const schema = itemSchema();
    schema.parameters.push(param('power', { type: 'decimal' }));
    expect(extraFieldsOf(schema).map((field) => field.id)).toEqual(['power']);
  });
});

describe('presenceOf', () => {
  it('a field module is present when the key exists', () => {
    const material = moduleCatalog(itemSchema(), null)[0]!;
    expect(presenceOf(material, {}).present).toBe(false);
    expect(presenceOf(material, { material: 'NETHERITE_SWORD' }).present).toBe(true);
  });

  it('a mechanic module matches authored entries by full id, short or qualified', () => {
    const catalog = moduleCatalog(itemSchema(), vocabulary);
    const backpack = catalog.find((module) => module.id === 'mechanic:materione:backpack')!;
    expect(presenceOf(backpack, { mechanics: [{ backpack: { rows: 3 } }] }).present).toBe(true);
    expect(presenceOf(backpack, { mechanics: [{ backpack: {} }, { backpack: {} }] }).entries).toHaveLength(2);
    expect(presenceOf(backpack, { mechanics: [{ soulbound: {} }] }).present).toBe(false);

    const charge = catalog.find((module) => module.id === 'mechanic:example:charge')!;
    expect(presenceOf(charge, { mechanics: [{ 'example:charge': { capacity: 5 } }] }).present).toBe(true);
  });

  it('the rules module is present only when a rule exists', () => {
    const rules = moduleCatalog(itemSchema(), null).find((module) => module.id === 'behavior')!;
    expect(presenceOf(rules, {}).present).toBe(false);
    expect(presenceOf(rules, { behavior: [] }).present).toBe(false);
    expect(presenceOf(rules, { behavior: [{ on: 'click:right' }] }).present).toBe(true);
  });
});

describe('defaultFor', () => {
  it('writes the declared default, then the first allowed value', () => {
    expect(defaultFor(param('model_parent', { type: 'string', default: 'item/generated' })))
      .toBe('item/generated');
    expect(defaultFor(param('material', { type: 'enum', allowedValues: ['A', 'B'] }))).toBe('A');
  });

  it('a reference with nothing to write opens an empty card instead of a placeholder', () => {
    expect(defaultFor(param('jukebox_song', { uiHint: 'reference' }))).toBeUndefined();
  });
});

describe('categoriesOf', () => {
  it('categories read in a fixed order and unknown ones sort last', () => {
    const catalog = moduleCatalog(itemSchema(), vocabulary);
    expect(categoriesOf(catalog)).toEqual(['identity', 'appearance', 'gameplay', 'behaviour']);
    expect(categoriesOf([
      { id: 'x', label: 'X', summary: '', category: 'media', icon: 'circle', kind: 'field' },
      { id: 'y', label: 'Y', summary: '', category: 'appearance', icon: 'circle', kind: 'field' },
    ])).toEqual(['appearance', 'media']);
    expect(categoryLabel('behaviour')).toBe('Behaviour');
    expect(categoryLabel('media')).toBe('media');
  });
});
