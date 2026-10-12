// Furniture suite: display-entity clusters as seen by a real player on a
// real server. Auto mode — the runner judges pass/fail from assertions.
//
// Tests here are dev-driven (`materione dev place/remove/inspect` falls
// through from the block runtime to the furniture runtime when the id is
// not a block); the placement-item seam is covered client-side by the
// blocks suite. The furniture markers carry the MATERIONE-DEV-FURNITURE-*
// prefix and land in the server log — region-thread replies never ride the
// RCON response.

import { test } from './_lib.mjs';

// The client's block-light layer at a cell — the emission proxy: a placed
// `minecraft:light[level=n]` cell only lights the scene if the client's
// light engine received the level, so 14 at the cell (13 one step away) is
// "this furniture actually emits", not just "a light block was set".
// LightLayer lives in world.level on 26.1.2 (not world.level.lighting — a
// wrong package surfaces as Groovy "No such property: net").
const blockLightAt = (ctx, cell) => ctx.exec(
  `return mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, `
  + `new net.minecraft.core.BlockPos(${cell[0]}, ${cell[1]}, ${cell[2]}))`, 15_000);

export default [
  test({
    id: 'furniture.light-emission',
    title: "world:workbench (lights: level 14 above the anchor) spawns exactly a minecraft:light[level=14] cell, the client's block-light layer reads 14 at the cell and 13 one step east (baseline 0 before, 0 again after dev remove turns the cell back to air), and dev inspect pins the cluster on the anchor cell",
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain; no crosshair work here

      // --- scene: anchor [0,-60,7]; the definition's light offset [0,1,0] is
      // yaw-invariant, so the cell lands at [0,-59,7]. dev place uses yaw 0.
      const anchor = [0, -60, 7];
      const lightCell = [0, -59, 7];
      const east = [1, -59, 7];
      // idempotent reruns: both dev removes are fail-soft (not-custom →
      // not-furniture) and the light cell's cluster pointer would resolve
      // the cluster too, so clean both cells
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${lightCell.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${lightCell.join(' ')} minecraft:air`);

      // baseline: fresh superflat has no block-light sources around the scene
      ctx.expect(await blockLightAt(ctx, lightCell)).toBe(0);
      ctx.expect(await blockLightAt(ctx, east)).toBe(0);

      // --- place: the dev driver spawns the full cluster (root display,
      // barrier + interaction hitboxes, seat, label, storage, light cell)
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });

      // the light cell is exactly the definition's level — a wrong-but-close
      // level (13/15) must fail the predicate
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:light[level=14]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:light[level=15]`))
        .toBe('Test failed');

      // emission, as the client's light engine received it
      await ctx.sleep(1500); // light packets → client engine
      ctx.expect(await blockLightAt(ctx, lightCell)).toBe(14);
      ctx.expect(await blockLightAt(ctx, east)).toBe(13);

      // the cluster owns the anchor: display-entity furniture leaves the
      // anchor block air and rides a chunk-PDC pointer, so the blocks
      // inspect answers "none" and the furniture inspect pins the id
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench /,
        { timeoutMs: 20_000 });

      // --- teardown: cluster removal clears the light cell and the dark
      // baseline returns (dev remove drops the placement item; killed below)
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:air`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:light`))
        .toBe('Test failed');
      await ctx.sleep(1500);
      ctx.expect(await blockLightAt(ctx, lightCell)).toBe(0);
      ctx.expect(await blockLightAt(ctx, east)).toBe(0);
      await ctx.rcon('kill @e[type=item]'); // the placement-item drop from remove
    },
  }),
];
