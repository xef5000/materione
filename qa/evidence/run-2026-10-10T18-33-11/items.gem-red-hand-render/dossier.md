# [qa:items] corpus:gem_red renders its custom model + texture in hand and hotbar (no checkerboard, no missing model)

**Outcome:** FAIL — visual verdict by agent:session-qa (test items.gem-red-hand-render, run run-2026-10-10T18-33-11)
**Verdict note:** Still broken on main 7047df0 — identical checkerboard/missing-model render; see #39 for the full root-cause analysis (generated text-effect shader mutates the Position attribute, invalid GLSL, client drops the whole pack).
**Repro:** `node qa/runner.mjs run items.gem-red-hand-render`

## What was expected

the corpus:gem_red model + texture are visible in the held item and the hotbar slot — not the vanilla base item, not a missing-model cube, not a checkerboard texture

## Evidence

- `qa\results\run-2026-10-10T18-33-11\items.gem-red-hand-render\held.jpg`
- `qa\results\run-2026-10-10T18-33-11\items.gem-red-hand-render\hotbar.jpg`

## Test definition

```js
id: "items.gem-red-hand-render"
title: "corpus:gem_red renders its custom model + texture in hand and hotbar (no checkerboard, no missing model)"
mode: "visual"
dependsOn: ["commands.give-basic"]
covers: ["content/**","pack/**","delivery/**","modern/**","fixtures/packs/corpus/**"]

steps:
async (ctx) => {
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
    }
```

<!-- qa-test: items.gem-red-hand-render @ 7047df050c8ee77c3d96863fc26843a5c663eccc -->
