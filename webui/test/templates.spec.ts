import { describe, expect, it } from 'vitest';
import type { TemplateEntry, TypeInfo, TypeParam } from '../src/lib/api';
import { typeTabs } from '../src/lib/browser';
import { inheritedModules, moduleCatalog } from '../src/lib/modules';
import {
  acceptsTemplates,
  extractableFields,
  searchTemplates,
  selectableTemplates,
  servedSchema,
  studioSchema,
  templateRef,
  templateSets,
  templateTitle,
  templatesFor,
} from '../src/lib/templates';
import { detached, startingFields, templateCatalog } from '../src/lib/demo/templates';
import type { TemplateDocument } from '../src/lib/demo/templates';

function field(id: string, extra: Partial<TypeParam> = {}): TypeParam {
  return { id, type: 'string', required: false, ...extra };
}

const item: TypeInfo = {
  name: 'item',
  directory: 'items',
  displayName: 'Item',
  description: '',
  parameters: [
    field('material', { required: true, label: 'Base material', module: true, icon: 'box', uiHint: 'material' }),
    field('template', { type: 'reference', referenceKind: 'template', advanced: true, uiHint: 'reference' }),
    field('display_name', { label: 'Display name', uiHint: 'formatted-text' }),
    field('lore', { type: 'list', label: 'Lore', uiHint: 'formatted-lines' }),
    field('model_parent', { label: 'Model style', module: true, icon: 'layers' }),
  ],
};
const block: TypeInfo = {
  name: 'block',
  directory: 'blocks',
  displayName: 'Block',
  description: '',
  parameters: [field('technique'), field('template', { referenceKind: 'template' })],
};
const recipe: TypeInfo = {
  name: 'recipe', directory: 'recipes', displayName: 'Recipe', description: '', parameters: [field('type')],
};

function entry(id: string, serves: string[], definition: Record<string, unknown>, pack = 'demo'): TemplateEntry {
  return { pack, path: `templates/${id}.yml`, id, definition, serves, usedBy: [] };
}

describe('template-capable types', () => {
  it('finds the template field by its declared reference kind, not its name', () => {
    expect(acceptsTemplates(item)).toBe(true);
    expect(acceptsTemplates(recipe)).toBe(false);
    expect(acceptsTemplates({ ...recipe, parameters: [field('base', { referenceKind: 'template' })] })).toBe(true);
  });

  it('never renders the raw template field, and requires nothing of a template itself', () => {
    expect(studioSchema(item, false)?.parameters.map((p) => p.id)).not.toContain('template');
    const partial = studioSchema(item, true)!;
    expect(partial.parameters.every((p) => !p.required)).toBe(true);
    // the real schema is untouched: one schema source (D5)
    expect(item.parameters.find((p) => p.id === 'material')?.required).toBe(true);
  });

  it('adds a Templates tab only when a registered type can use one', () => {
    expect(typeTabs([item, recipe]).map((tab) => tab.directory)).toContain('templates');
    expect(typeTabs([recipe]).map((tab) => tab.directory)).not.toContain('templates');
    const order = typeTabs([item, recipe]).filter((tab) => tab.enabled).map((tab) => tab.directory);
    expect(order).toEqual(['items', 'recipes', 'templates']);
  });
});

describe('choosing a template', () => {
  const base = entry('base_item', ['items'], { material: 'PAPER', lore: ['x'], display_name: 'Ruby Base', future: 1 });
  const lamp = entry('lamp_base', ['blocks'], { technique: 'slab' });
  const mystery = entry('mystery', [], { zzz: 1 });
  const all = [base, lamp, mystery];

  it('filters to the type being created, keeping templates the server could not place', () => {
    expect(templatesFor(all, 'items').map((t) => t.id)).toEqual(['base_item', 'mystery']);
    expect(templatesFor(all, 'blocks').map((t) => t.id)).toEqual(['lamp_base', 'mystery']);
    expect(templatesFor(all, 'recipes').map((t) => t.id)).toEqual(['mystery']);
  });

  it('writes a bare id inside the pack and a namespaced one across packs', () => {
    expect(templateRef(base, 'demo')).toBe('base_item');
    expect(templateRef(base, 'other')).toBe('demo:base_item');
  });

  it('titles a template by its own display name, else its id', () => {
    expect(templateTitle(base)).toBe('Ruby Base');
    expect(templateTitle(lamp)).toBe('Lamp base');
  });

  it('searches id, title and the fields a template sets, best match first', () => {
    expect(searchTemplates(all, 'lamp').map((t) => t.id)).toEqual(['lamp_base']);
    expect(searchTemplates(all, 'technique').map((t) => t.id)).toEqual(['lamp_base']);
    // the labels the schema gives a template's fields match too
    expect(searchTemplates(all, 'base material', [item, block]).map((t) => t.id)).toEqual(['base_item']);
    expect(searchTemplates(all, 'RUBY').map((t) => t.id)).toEqual(['base_item']);
    expect(searchTemplates(all, 'base').map((t) => t.id)).toEqual(['base_item', 'lamp_base']);
    expect(searchTemplates(all, '  ')).toHaveLength(3);
    expect(searchTemplates(all, 'nothing like it')).toEqual([]);
  });

  it('lists what a template sets in schema order, labelled by the schema, unknown keys last', () => {
    const sets = templateSets(base, item);
    expect(sets.map((s) => s.field)).toEqual(['material', 'display_name', 'lore', 'future']);
    expect(sets.map((s) => s.label)).toEqual(['Base material', 'Display name', 'Lore', 'Future']);
    expect(sets.filter((s) => s.module).map((s) => s.field)).toEqual(['material']);
  });

  it('edits a template as the first type it serves', () => {
    expect(servedSchema(base, [item, block])?.name).toBe('item');
    expect(servedSchema(mystery, [item, block])).toBeUndefined();
  });

  it('cannot base a template on itself or on anything that inherits from it', () => {
    const parent = entry('parent', ['items'], { material: 'PAPER' });
    const child: TemplateEntry = { ...entry('child', ['items'], { template: 'parent' }), usedBy: [] };
    parent.usedBy = [{ pack: 'demo', type: 'templates', path: 'templates/child.yml', id: 'child' }];
    const grandchild = entry('grandchild', ['items'], { template: 'child' });
    child.usedBy = [{ pack: 'demo', type: 'templates', path: 'templates/grandchild.yml', id: 'grandchild' }];
    const pool = [parent, child, grandchild];
    expect(selectableTemplates(pool, 'items', { pack: 'demo', id: 'parent' })).toEqual([]);
    expect(selectableTemplates(pool, 'items', { pack: 'demo', id: 'grandchild' }).map((t) => t.id))
      .toEqual(['parent', 'child']);
    expect(selectableTemplates(pool, 'items').map((t) => t.id)).toEqual(['parent', 'child', 'grandchild']);
  });

  it('offers everything but the resource name when saving as a template', () => {
    expect(extractableFields({ display_name: 'A', material: 'B', template: 'base' })).toEqual([
      { field: 'display_name', selected: false },
      { field: 'material', selected: true },
      { field: 'template', selected: true },
    ]);
  });
});

describe('inherited modules', () => {
  const catalog = moduleCatalog(item, null);

  it('shows the modules a resource gets from its template and does not set itself', () => {
    const shown = inheritedModules(
      catalog,
      { template: 'base_item', display_name: 'Ruby Dagger' },
      { material: 'PAPER', model_parent: 'item/handheld', lore: ['x'] },
      { material: 'demo:base_item', model_parent: 'demo:base_item', lore: 'demo:base_item' },
    );
    // lore is on the display card (its own provenance line), not a module card
    expect(shown.map((module) => module.field)).toEqual(['material', 'model_parent']);
    expect(shown[0]).toMatchObject({ label: 'Base material', source: 'demo:base_item', summary: 'PAPER', value: 'PAPER' });
  });

  it('stops showing a module the resource overrides', () => {
    const shown = inheritedModules(catalog, { material: 'IRON_SWORD' }, { material: 'PAPER' }, { material: 'demo:base' });
    expect(shown).toEqual([]);
  });
});

describe('template operations of the in-tab servers', () => {
  function doc(path: string, parsed: Record<string, unknown>, pack = 'demo'): [string, TemplateDocument] {
    return [`${pack}/${path}`, { pack, path, rev: 1, raw: '', parsed }];
  }
  const documents = new Map<string, TemplateDocument>([
    doc('templates/base_item.yml', { base_item: { material: 'PAPER', lore: ['Forged'], future_field: 'keep' } }),
    doc('templates/blade.yml', { blade: { template: 'base_item', material: 'DIAMOND_SWORD' } }),
    doc('items/ruby_sword.yml', { ruby_sword: { template: 'blade', display_name: 'Ruby Sword' } }),
  ]);

  it('starts a resource as the link and its name, and refuses an unknown template', () => {
    expect(startingFields(documents, 'demo', 'items', 'ruby_dagger', 'Ruby Dagger', 'base_item', ''))
      .toEqual({ fields: { template: 'base_item', display_name: 'Ruby Dagger' } });
    expect(startingFields(documents, 'demo', 'items', 'x', 'X', 'nope', ''))
      .toEqual({ error: 'unknown template demo:nope' });
    expect(startingFields(documents, 'demo', 'templates', 'lamp', 'Lamp', '', 'blocks'))
      .toEqual({ fields: { technique: 'note_block' } });
  });

  it('derives who a template serves and who uses it', () => {
    const catalogue = templateCatalog(documents, [item, block]);
    const base = catalogue.find((row) => row.id === 'base_item')!;
    expect(base.serves).toEqual(['items']);
    expect((base.usedBy as unknown[]).length).toBe(1); // blade inherits it
    const blade = catalogue.find((row) => row.id === 'blade')!;
    expect(blade.parent).toBe('demo:base_item');
    expect(blade.usedBy).toEqual([{ pack: 'demo', type: 'items', path: 'items/ruby_sword.yml', id: 'ruby_sword' }]);
  });

  it('detaches by laying the chain down parent-first, own values winning', () => {
    const result = detached(documents, 'demo', { template: 'blade', display_name: 'Ruby Sword', lore: ['Mine'] });
    expect(result).toEqual({
      fields: { display_name: 'Ruby Sword', lore: ['Mine'], material: 'DIAMOND_SWORD', future_field: 'keep' },
    });
    expect(Object.keys('fields' in result ? result.fields : {})).not.toContain('template');
    expect(detached(documents, 'demo', { material: 'X' })).toEqual({ error: 'not based on a template' });
    expect(detached(documents, 'demo', { template: 'ghost' })).toEqual({
      error: 'template does not resolve: unknown template demo:ghost',
    });
  });
});
