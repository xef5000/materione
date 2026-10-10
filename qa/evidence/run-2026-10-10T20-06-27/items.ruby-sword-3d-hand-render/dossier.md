# [qa:items] sample:ruby_sword renders its hand-authored 3D model (blade/guard/hilt cubes) in hand and hotbar

**Outcome:** FAIL — visual verdict by agent:session-qa (test items.ruby-sword-3d-hand-render, run run-2026-10-10T20-06-27)
**Verdict note:** CONFIRMED BROKEN: hotbar slot renders magenta/black checkerboard and the held item is a giant checkerboard cuboid (missing model). Client log: 'Unable to bake item model: sample:ruby_sword' — IllegalStateException 'Multiple atlases used in model, expected minecraft:textures/atlas/items.png, but also got minecraft:textures/atlas/blocks.png' (CuboidItemModelWrapper.validateAtlasUsage). Root cause: the pack compiler emits single-source atlas entries for custom-dir textures (sample:obsidian/axe) into atlases/blocks.json (blocks atlas), while item/-convention textures (sample:item/ruby) are gathered by vanilla's items atlas on 26.x — and 26.1.2 forbids one item model from spanning two atlases. Single-atlas models work (corpus gems are blocks-only and render); ANY model mixing the two conventions breaks. Fix direction: on the 26.x+ rungs route item-model sprite single-sources into atlases/items.json (or both atlases), keeping blocks.json for block-model sprites.
**Repro:** `node qa/runner.mjs run items.ruby-sword-3d-hand-render`

## What was expected

the ruby sword renders as the hand-authored 3D model — a distinct blade, tip, guard and hilt built from cubes, ruby-red blade against a dark guard/hilt, angled like a tool in the hotbar slot; not a flat 2D sprite, not the vanilla netherite sword, not a missing-model cube, not a checkerboard

## Evidence

- (none)

## Test definition

```js
id: "items.ruby-sword-3d-hand-render"
title: "sample:ruby_sword renders its hand-authored 3D model (blade/guard/hilt cubes) in hand and hotbar"
mode: "visual"
dependsOn: ["commands.give-basic"]
covers: ["content/**","pack/**","delivery/**","modern/**","fixtures/packs/sample/**"]

steps:
async (ctx) => {
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
        'the ruby sword renders as the hand-authored 3D model — a distinct blade, tip, guard and hilt built from cubes, ruby-red blade against a dark guard/hilt, angled like a tool in the hotbar slot; not a flat 2D sprite, not the vanilla netherite sword, not a missing-model cube, not a checkerboard',
      );
    }
```

<!-- qa-test: items.ruby-sword-3d-hand-render @ 8529f3ce86d2a58c773617c9b628e27ca1257012 -->
