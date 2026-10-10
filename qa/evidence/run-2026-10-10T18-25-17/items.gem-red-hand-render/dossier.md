# [qa:items] corpus:gem_red renders its custom model + texture in hand and hotbar (no checkerboard, no missing model)

**Outcome:** FAIL — visual verdict by agent:session-qa (test items.gem-red-hand-render, run run-2026-10-10T18-25-17)
**Verdict note:** CONFIRMED BROKEN: corpus:gem_red renders as missing-texture (magenta/black checkerboard) in the hotbar and as a giant missing-model black/magenta shape in first person. The pack-defined display name 'Red Gem' DOES reach the client, so item metadata + give logic work — the failure is resource-pack-level. ROOT CAUSE (client log): 'Caught error loading resourcepacks, removing all selected resourcepacks' after 'Couldn't compile vertex shader (minecraft:core/rendertype_text): ERROR: 6:21: assign : l-value required Position (cannot modify an attribute)'. The delivered pack's materione_26_1 overlay overrides assets/minecraft/shaders/core/rendertype_text.vsh with a generated hook (materionePreTransform) whose text-effect wobble does 'Position.y += sin(...)' — a direct assignment to the 'in vec3 Position' ATTRIBUTE, which GLSL forbids. 26.1.2's text/gui_text/text_polygon_offset pipelines all reference this vertex shader, so one bad compile fails ALL required shader programs and the client drops the ENTIRE pack (vanilla fallback) — every custom asset then renders checkerboarded. The 1_21_4/1_21_6/26_2 overlays should be audited for the same attribute-mutation pattern. Fix direction: copy Position into a local (vec3 mtrPosition), mutate the local in the hook, and use it for gl_Position in the generated vsh template.
**Repro:** `node qa/runner.mjs run items.gem-red-hand-render`

## What was expected

A visual pass (see the test definition for the eyes criterion).

## Evidence

- `qa/evidence/run-2026-10-10T18-25-17/items.gem-red-hand-render\held.jpg`
- `qa/evidence/run-2026-10-10T18-25-17/items.gem-red-hand-render\hotbar.jpg`

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

<!-- qa-test: items.gem-red-hand-render @ 6af794b8e2716a4a252b864976f368764d08cac2 -->
