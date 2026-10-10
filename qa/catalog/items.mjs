// Seed suite: content rendering as seen by a real client (issue #38).
// These are visual tests — the steps stage content and capture screenshots;
// the verdict needs eyes (the reporting agent's vision or the user's) and is
// closed with `node qa/runner.mjs mark <id> pass|fail`.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// The probe server's working copy of the sample pack. The auto-updater test
// stages config changes HERE (never the repo fixture); probe `up` re-copies
// fixtures on every boot, so the workspace self-heals regardless.
const SERVER_ITEM_COPY = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione',
  'content', 'packs', 'sample', 'items', 'templated_ruby.yml');

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
  test({
    id: 'items.template-inheritance',
    title: 'sample:templated_ruby gets its material from the base_item template and its own display name in game',
    mode: 'auto',
    covers: [
      'content/**',
      'fixtures/packs/sample/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give sample:templated_ruby Probe');
      await ctx.waitForChat(/Given sample:templated_ruby/);
      // The material is INHERITED (base_item says PAPER; the definition does
      // not restate it) while the display name is the definition's own — one
      // item proving both merge directions reach the live runtime. The
      // bridge exposes NMS ItemStacks (getType() is the Bukkit method), so
      // the material reads as its stable description id. Indexed Groovy
      // only: closures do not resolve.
      await ctx.exec('player.getInventory().selected = 0');
      const materialId = await ctx.exec(
        'return player.getInventory().items.get(0).getItem().getDescriptionId()',
      );
      ctx.expect(String(materialId)).toBe('item.minecraft.paper');
      const heldName = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()',
      );
      ctx.expect(String(heldName)).toBe('Templated Ruby');
    },
  }),
  test({
    id: 'items.auto-updater-refresh',
    title: 'sample:templated_ruby held by a mid-session player picks up a config change (display name) on the next inventory scan',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/ItemAutoUpdater.java',
      'platform/src/main/java/dev/materione/platform/items/MaterioneStacks.java',
      'core/src/main/java/dev/materione/core/runtime/RuntimeItem.java',
      'fixtures/packs/sample/**',
    ],
    dependsOn: ['items.template-inheritance'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give sample:templated_ruby Probe');
      await ctx.waitForChat(/Given sample:templated_ruby/);
      await ctx.exec('player.getInventory().selected = 0');
      const before = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(before)).toBe('Templated Ruby');

      // Stage the config change in the SERVER's content copy (the repo
      // fixture is never touched) and hot-reload it into the runtime.
      const original = fs.readFileSync(SERVER_ITEM_COPY, 'utf8');
      fs.writeFileSync(
        SERVER_ITEM_COPY, original.replace('Templated Ruby', 'Updated Ruby'));
      try {
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.sleep(700); // the scheduled apply swap lands after the marker

        // Rejoin: the join-time scan is the updater's first-class trigger
        // (the throttled inventory-event path is the other; unit-covered).
        await ctx.rejoin();
        await ctx.sleep(700); // the join-time scan runs on the player's thread

        const after = await ctx.exec(
          'return player.getInventory().items.get(0).getHoverName().getString()');
        ctx.expect(String(after)).toBe('Updated Ruby');
      } finally {
        // Restore the server copy and the runtime no matter how the
        // assertions went — later tests must see the shipped definition.
        fs.writeFileSync(SERVER_ITEM_COPY, original);
        try { await ctx.rcon('materione reload'); } catch (e) {
          ctx.log('restore reload failed: ' + (e?.message ?? e));
        }
      }
    },
  }),
];
