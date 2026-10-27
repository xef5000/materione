import { describe, expect, it } from 'vitest';
import {
  assetCategory,
  bulkHeadline,
  coverageNote,
  fieldOperations,
  fuzzyScore,
  impactSummary,
  logicalAssetName,
  parseBulkResult,
  parseReferenceResult,
  rankCommands,
  recipeOperation,
  replacementValue,
  retargetOperations,
  variantPlan,
  type PaletteCommand,
  type ReferenceHit,
} from '../src/lib/bulk';
import { scanReferences } from '../src/lib/demo/references';

const gem = { pack: 'demo', path: 'assets/textures/item/gem.png' };
const ruby = { pack: 'demo', path: 'assets/textures/item/ruby.png' };

function hit(partial: Partial<ReferenceHit>): ReferenceHit {
  return { pack: 'demo', type: 'items', path: 'items/a.yml', id: 'a', field: ['texture'], value: 'item/gem', ...partial };
}

describe('asset naming', () => {
  it('derives the way documents spell an asset', () => {
    expect(logicalAssetName('assets/textures/item/gem.png')).toBe('item/gem');
    expect(logicalAssetName('assets/textures/item/gem.png', true)).toBe('item/gem.png');
    expect(assetCategory('assets/models/block/lamp.json')).toBe('models');
    expect(assetCategory('pack.yml')).toBe('');
  });

  it('keeps the spelling a hit already had when it is retargeted', () => {
    expect(replacementValue('item/gem', gem, ruby)).toBe('item/ruby');
    expect(replacementValue('item/gem.png', gem, ruby)).toBe('item/ruby.png');
    expect(replacementValue('demo:item/gem', gem, ruby)).toBe('demo:item/ruby');
    expect(replacementValue('item/gem', gem, { pack: 'other', path: ruby.path })).toBe('other:item/ruby');
  });
});

describe('retargetOperations', () => {
  it('merges several hits in one document into one operation and needs every revision', () => {
    const hits = [
      hit({ id: 'a' }),
      hit({ id: 'b', field: ['icon'] }),
      hit({ id: 'c', path: 'items/c.yml', value: 'demo:item/gem' }),
    ];
    const ops = retargetOperations(hits, gem, ruby, new Map([['items/a.yml', 4], ['items/c.yml', 9]]));
    expect(ops).toEqual([
      { kind: 'set', path: 'items/a.yml', rev: 4, edits: [
        { field: ['a', 'texture'], value: 'item/ruby' },
        { field: ['b', 'icon'], value: 'item/ruby' },
      ] },
      { kind: 'set', path: 'items/c.yml', rev: 9, edits: [{ field: ['c', 'texture'], value: 'demo:item/ruby' }] },
    ]);
    expect(() => retargetOperations(hits, gem, ruby, new Map([['items/a.yml', 4]]))).toThrow('items/c.yml');
  });
});

describe('fieldOperations', () => {
  it('sets or removes one field across targets', () => {
    const revs = new Map([['items/a.yml', 1], ['items/b.yml', 2]]);
    const targets = [{ path: 'items/a.yml', id: 'a' }, { path: 'items/b.yml', id: 'b' }];
    expect(fieldOperations(targets, ['material'], { value: 'STICK' }, revs)).toEqual([
      { kind: 'set', path: 'items/a.yml', rev: 1, edits: [{ field: ['a', 'material'], value: 'STICK' }] },
      { kind: 'set', path: 'items/b.yml', rev: 2, edits: [{ field: ['b', 'material'], value: 'STICK' }] },
    ]);
    expect(fieldOperations(targets.slice(0, 1), ['lore'], { remove: true }, revs)[0]).toMatchObject({
      edits: [{ field: ['a', 'lore'], remove: true }],
    });
  });
});

describe('variantPlan', () => {
  const source = { path: 'items/blade.yml', id: 'blade', hasDisplayName: true };

  it('makes a lossless duplicate per name with its own display name and one overridden field', () => {
    const plan = variantPlan(source, 'Red Blade = item/red\nBlue Blade', ['texture'], new Set(['blade']), 'items/swords');
    expect(plan.problems).toEqual([]);
    expect(plan.operations).toEqual([
      { kind: 'duplicate', fromPath: 'items/blade.yml', id: 'red_blade', folder: 'items/swords', edits: [
        { field: ['red_blade', 'display_name'], value: 'Red Blade' },
        { field: ['red_blade', 'texture'], value: 'item/red' },
      ] },
      { kind: 'duplicate', fromPath: 'items/blade.yml', id: 'blue_blade', folder: 'items/swords', edits: [
        { field: ['blue_blade', 'display_name'], value: 'Blue Blade' },
      ] },
    ]);
  });

  it('reports taken, repeated and unusable names instead of dropping them silently', () => {
    const plan = variantPlan(source, 'Blade\nGem\nGem\n!', null, new Set(['blade']));
    expect(plan.operations).toHaveLength(1);
    expect(plan.problems).toHaveLength(3);
  });

  it('writes a recipe whose result is the item', () => {
    expect(recipeOperation('blade_red')).toEqual({
      kind: 'create', type: 'recipes', id: 'blade_red', edits: [{ field: ['blade_red', 'result'], value: 'blade_red' }],
    });
  });
});

describe('reply parsing and wording', () => {
  it('treats missing coverage as unknown, never complete', () => {
    const parsed = parseReferenceResult({ hits: [] });
    expect(parsed.coverage.complete).toBe(false);
    expect(coverageNote(parsed.coverage)).toContain('Coverage unknown');
  });

  it('says what a partial scan means', () => {
    const base = { documentsScanned: 3, unreadable: 0, truncated: false, complete: true, notScanned: [] };
    expect(coverageNote(base)).toContain('3 documents read');
    expect(coverageNote({ ...base, truncated: true, complete: false })).toContain('hit its limit');
    expect(coverageNote({ ...base, unreadable: 2, complete: false })).toContain('2 could not be read');
  });

  it('summarises impact by type', () => {
    expect(impactSummary([])).toBe('no users found');
    expect(impactSummary([hit({}), hit({ id: 'b' }), hit({ type: 'recipes', path: 'recipes/r.yml', id: 'r' })]))
      .toBe('2 items, 1 recipe');
  });

  it('headlines a bulk result', () => {
    const result = parseBulkResult({ applied: false, documents: [
      { path: 'items/a.yml', kind: 'set', rev: 2, unchanged: false, changes: [{ field: ['a', 'x'], before: 1, after: 2 }] },
      { path: 'items/b.yml', kind: 'set', rev: 1, unchanged: true, changes: [] },
      { path: 'items/c.yml', kind: 'duplicate', rev: 1, unchanged: false, changes: [] },
    ] });
    expect(bulkHeadline(result)).toBe('2 documents, 1 field, 1 new; 1 already as requested');
  });
});

describe('scanReferences (the stand-in for the server scan)', () => {
  const documents = [
    { pack: 'demo', path: 'items/a.yml', parsed: { a: { texture: 'item/gem', note: 'item/gem for sale' } } },
    { pack: 'demo', path: 'items/b.yml', parsed: { b: { texture: 'demo:item/gem.png', tags: ['item/gem'] } } },
    { pack: 'other', path: 'items/c.yml', parsed: { c: { texture: 'item/gem' } } },
    { pack: 'other', path: 'items/d.yml', parsed: { d: { texture: 'demo:item/gem' } } },
  ];

  it('matches bare spellings inside the pack, qualified ones anywhere, and exact text only', () => {
    const result = parseReferenceResult(scanReferences(documents, {
      kind: 'asset', pack: 'demo', target: 'assets/textures/item/gem.png',
    }));
    expect(result.hits.map((entry) => `${entry.pack}/${entry.id}:${entry.field.join('.')}`).sort()).toEqual([
      'demo/a:texture', 'demo/b:tags.0', 'demo/b:texture', 'other/d:texture',
    ]);
    expect(result.coverage).toMatchObject({ documentsScanned: 4, complete: true, truncated: false });
    expect(result.coverage.notScanned.length).toBeGreaterThan(0);
  });
});

describe('command palette ranking', () => {
  const noop = () => {};
  const commands: PaletteCommand[] = [
    { id: '1', title: 'Ruby Blade', group: 'items', keywords: 'ruby_blade', run: noop },
    { id: '2', title: 'Assets', group: 'Go to', run: noop },
    { id: '3', title: 'New recipe', group: 'Create', run: noop },
  ];

  it('scores subsequences and rejects non-matches', () => {
    expect(fuzzyScore('rbl', 'Ruby Blade')).not.toBeNull();
    expect(fuzzyScore('xyz', 'Ruby Blade')).toBeNull();
    expect(fuzzyScore('', 'anything')).toBe(0);
  });

  it('puts the tighter, word-start match first and keeps the empty query stable', () => {
    expect(rankCommands('as', commands)[0]?.id).toBe('2');
    expect(rankCommands('rec', commands)[0]?.id).toBe('3');
    expect(rankCommands('', commands).map((c) => c.id)).toEqual(['1', '2', '3']);
    expect(rankCommands('zzz', commands)).toEqual([]);
  });
});
