import { beforeEach, describe, expect, it } from 'vitest';
import { DEMO_PACK, createDemoChannel } from '../src/lib/demo';
import { loadWorkspace, resetWorkspace, session, workspace } from '../src/lib/state.svelte';

beforeEach(resetWorkspace);

describe('cloud editor demo backend', () => {
  it('serves the sample pack entirely from memory', async () => {
    const demo = createDemoChannel();
    session.channel = demo;
    await loadWorkspace();
    expect(workspace.packs).toEqual([DEMO_PACK]);
    expect(workspace.activePack).toBe(DEMO_PACK);
    const types = new Set(workspace.summaries.map((summary) => summary.type));
    expect(types).toEqual(new Set(['items', 'blocks', 'glyphs']));
    expect(workspace.summaries.filter((summary) => summary.health === 'error').map((summary) => summary.id))
      .toEqual(['coin']);
    // Live-render blocks only: the regression fixture's unknown-parent fallback is left out.
    expect(workspace.summaries.some((summary) => summary.id === 'mystery_anvil')).toBe(false);
  });

  it('applies writes to its own state and nowhere else', async () => {
    const demo = createDemoChannel();
    const before = await demo.call('browse.get', { pack: DEMO_PACK, path: 'items/weapons.yml' });
    expect(before.ok && (before.data as { rev: number }).rev).toBe(1);
    const saved = await demo.call('authoring.setFields', {
      pack: DEMO_PACK, path: 'items/weapons.yml', rev: 1,
      edits: [{ field: ['ruby_blade', 'display_name'], value: '&cRuby Blade of Dawn' }],
    });
    expect(saved.ok).toBe(true);
    const after = await demo.call('browse.get', { pack: DEMO_PACK, path: 'items/weapons.yml' });
    expect(after.ok && (after.data as { parsed: Record<string, Record<string, unknown>> }).parsed.ruby_blade?.display_name)
      .toBe('&cRuby Blade of Dawn');
    // A fresh demo starts over.
    const fresh = await createDemoChannel().call('browse.get', { pack: DEMO_PACK, path: 'items/weapons.yml' });
    expect(fresh.ok && (fresh.data as { rev: number }).rev).toBe(1);
  });
});
