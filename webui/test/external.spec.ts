// The external-edit feed's decision core (web.md §4.2 "document.changed"):
// hand edits pushed by the plugin's file watcher must refresh the shown
// pack's tree, flag only the open tab for the changed file, and never be
// silently dropped. Pure reducer, pinned here; wiring lives in App.svelte.
import { describe, expect, it } from 'vitest';
import { documentChangedEffect } from '../src/lib/external';

describe('documentChangedEffect', () => {
  it('refreshes the tree when the changed pack is the active one', () => {
    const effect = documentChangedEffect(
      { pack: 'sample', path: 'items/gem.yml', deleted: false },
      'sample',
      [],
    );
    expect(effect.refreshPack).toBe(true);
    expect(effect.staleKeys).toEqual([]);
    expect(effect.log).toBe('sample/items/gem.yml changed on disk');
  });

  it('does not refresh for a pack that is not shown', () => {
    const effect = documentChangedEffect(
      { pack: 'other', path: 'blocks/lamp.yml', deleted: false },
      'sample',
      [],
    );
    expect(effect.refreshPack).toBe(false);
  });

  it('flags an open tab for the changed file', () => {
    const effect = documentChangedEffect(
      { pack: 'sample', path: 'items/gem.yml', deleted: false },
      'sample',
      ['sample/items/gem.yml', 'sample/items/other.yml'],
    );
    expect(effect.staleKeys).toEqual(['sample/items/gem.yml']);
  });

  it('reports deletions as deleted', () => {
    const effect = documentChangedEffect(
      { pack: 'sample', path: 'items/gone.yml', deleted: true },
      'sample',
      ['sample/items/gone.yml'],
    );
    expect(effect.log).toBe('sample/items/gone.yml deleted on disk');
    expect(effect.staleKeys).toEqual(['sample/items/gone.yml']);
  });
});
