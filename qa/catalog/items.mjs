// Seed suite: content rendering as seen by a real client (issue #38).
// These are visual tests — the steps stage content and capture screenshots;
// the verdict needs eyes (the reporting agent's vision or the user's) and is
// closed with `node qa/runner.mjs mark <id> pass|fail`.

import { test } from './_lib.mjs';

export default [
  test({
    id: 'items.gem-red-hand-render',
    title: 'corpus:gem_red renders its custom model + texture in hand and hotbar (no checkerboard, no missing model)',
    mode: 'visual',
    covers: [
      'content/**',
      'pack/**',
      'delivery/**',
      'modern/**',
      'fixtures/packs/corpus/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give corpus:gem_red Probe');
      await ctx.waitForChat(/Given corpus:gem_red/);
      // Hold the item (first slot — the inventory was just cleared), confirm
      // the client sees the pack-defined name, then capture. Indexed Groovy
      // only: DebugBridge wraps collections, closures do not resolve.
      await ctx.exec('player.getInventory().selected = 0');
      const heldName = await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()',
      );
      ctx.expect(String(heldName)).toBe('Red Gem');
      await ctx.sleep(600);
      await ctx.shot('held');
      // Wide view of the HUD for the hotbar render.
      await ctx.shot('hotbar');
      ctx.needsEyes(
        'the corpus:gem_red model + texture are visible in the held item and the hotbar slot — not the vanilla base item, not a missing-model cube, not a checkerboard texture',
      );
    },
  }),
  test({
    id: 'items.ruby-sword-3d-hand-render',
    title: 'sample:ruby_sword renders its hand-authored 3D model (blade/guard/hilt cubes) in hand and hotbar',
    mode: 'visual',
    covers: [
      'content/**',
      'pack/**',
      'delivery/**',
      'modern/**',
      'fixtures/packs/sample/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give sample:ruby_sword Probe');
      await ctx.waitForChat(/Given sample:ruby_sword/);
      // Hold the item and confirm the pack-defined name, then capture. The
      // model is the elements-bearing fixture (4 cubes, ruby blade vs dark
      // hilt), so the eyes criterion is the 3D silhouette, not a flat sprite.
      await ctx.exec('player.getInventory().selected = 0');
      const heldName = await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()',
      );
      ctx.expect(String(heldName)).toBe('Ruby Sword');
      await ctx.sleep(600);
      await ctx.shot('held');
      // Wide view of the HUD for the hotbar render.
      await ctx.shot('hotbar');
      ctx.needsEyes(
        'the ruby sword renders as the hand-authored 3D model — a distinct blade, tip, guard and hilt built from cubes, red blade against the dark guard/hilt, angled like a tool in the hotbar slot; not a flat 2D sprite, not the vanilla netherite sword, not a missing-model cube, not a checkerboard',
      );
    },
  }),
];
