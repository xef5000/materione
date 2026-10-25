# [qa:glyphs] GLSL text effects live: the client's active resource manager serves the version-matched injected rendertype_text vertex shader under the vanilla name (materione marker present, the client's own pack format baked into the generated include) with exactly four signal-keyed animation branches — shake/wave in pre-transform, pulse/rainbow in post-transform; <e:…> spans resolve in real chat; the two-frame evidence shows the four effect words animating differently between frames

**Outcome:** FAIL — expected output to contain materione, got #version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in… [toContain] expected=materione actual=#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in…
**Test:** `glyphs.effects` (visual) · **Suite:** `qa/catalog/glyphs.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run glyphs.effects`

## What was expected

- operator: `toContain`
- expected: `materione`
- actual: `#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in…`

Error:

```
Error: expected output to contain materione, got #version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in…
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toContain (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:771:42)
    at Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/glyphs.mjs:586:34)
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async withTimeout (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:585:12)
    at async runTest (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:504:5)
    at async cmdRun (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:441:24)
```

## Evidence

Screenshots (JPEG, read directly from disk):

- (none captured)

Chat history (newest last):

```
(no chat captured)
```

Server log tail (harness\build\probe\server\logs\latest.log):

```
ient /127.0.0.1 started
[11:22:45] [RCON Client /127.0.0.1 #486/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:45] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:46] [RCON Client /127.0.0.1 #487/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:46] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:46] [RCON Client /127.0.0.1 #488/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:46] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:46] [RCON Client /127.0.0.1 #489/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:46] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:47] [RCON Client /127.0.0.1 #490/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:47] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:47] [RCON Client /127.0.0.1 #491/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:47] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:47] [RCON Client /127.0.0.1 #492/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:47] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:48] [RCON Client /127.0.0.1 #493/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:22:53] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:22:53] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:22:53] [RCON Client /127.0.0.1 #494/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
[11:22:39] [Render thread/WARN]: Duplicate sprite sample:gui/spark_2 from atlas minecraft:textures/atlas/items.png, already defined in atlas minecraft:textures/atlas/blocks.png. This will be rejected in a future version
[11:22:39] [Render thread/WARN]: Duplicate sprite sample:gui/spark_1 from atlas minecraft:textures/atlas/items.png, already defined in atlas minecraft:textures/atlas/blocks.png. This will be rejected in a future version
[11:22:40] [Render thread/INFO]: Resizing Chunk Sections UBO, capacity limit of 4 reached during a single frame. New capacity will be 8.
[11:22:40] [Render thread/INFO]: Resizing Chunk Sections UBO, capacity limit of 8 reached during a single frame. New capacity will be 16.
[11:22:40] [Render thread/INFO]: Resizing Chunk Sections UBO, capacity limit of 16 reached during a single frame. New capacity will be 32.
Oct 25, 2026 11:22:42 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:22:45 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:65344
Oct 25, 2026 11:22:45 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:22:53 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:57845
Oct 25, 2026 11:22:53 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/glyphs.effects\transcript.jsonl`

## Test definition

```js
id: "glyphs.effects"
title: "GLSL text effects live: the client's active resource manager serves the version-matched injected rendertype_text vertex shader under the vanilla name (materione marker present, the client's own pack format baked into the generated include) with exactly four signal-keyed animation branches — shake/wave in pre-transform, pulse/rainbow in post-transform; <e:…> spans resolve in real chat; the two-frame evidence shows the four effect words animating differently between frames"
mode: "visual"
dependsOn: ["glyphs.emoji"]
covers: ["content/src/main/java/dev/materione/content/effects/**","core/src/main/java/dev/materione/core/runtime/RuntimeEffects.java","pack/src/main/java/dev/materione/pack/compile/TextEffectShaders.java","platform/src/main/java/dev/materione/platform/text/GlyphText.java","plugin/src/main/java/dev/materione/plugin/runtime/text/**","fixtures/packs/sample/effects/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot settle; the pack loads at join

      // the client resource manager serves the INJECTED shader under the
      // vanilla name — the overlay is active for this client's format, not
      // the shader-free base layer
      const shader = await ctx.exec(`def rm = mc.getResourceManager()
def idc = Class.forName('net.minecraft.resources.Identifier')
def rl = idc.fromNamespaceAndPath('minecraft', 'shaders/core/rendertype_text.vsh')
def opt = rm.getResource(rl)
if (!opt.isPresent()) return 'NOT PRESENT'
return new String(opt.get().open().readAllBytes().@target, java.nio.charset.StandardCharsets.UTF_8)`, 20_000);
      ctx.expect(String(shader)).toContain('materione');

      // the generated include: the client's own pack format baked (84 = the
      // 26.1 rung this probe client resolves to), one branch per effect keyed
      // on its signal color — shake + wave pre-transform, pulse + rainbow post
      const include = await ctx.exec(`def rm = mc.getResourceManager()
def idc = Class.forName('net.minecraft.resources.Identifier')
def rl = idc.fromNamespaceAndPath('minecraft', 'shaders/include/materione_text_effects.glsl')
return new String(rm.getResource(rl).get().open().readAllBytes().@target, java.nio.charset.StandardCharsets.UTF_8)`, 20_000);
      const inc = String(include);
      ctx.expect(inc).toContain('MATERIONE_PACK_FORMAT 84');
      ctx.expect(inc).toContain('materionePreTransform');
      ctx.expect(inc).toContain('materionePostTransform');
      ctx.expect(inc).toContain('mtrHash'); // shake's jitter
      ctx.expect(inc).toContain('MTR_TAU'); // wave/pulse timing
      ctx.expect(inc).toContain('0.166667'); // the rainbow gradient chain
      let branchCount = 0;
      let at = 0;
      while ((at = inc.indexOf('if (mtrSignal(', at)) >= 0) {
        branchCount += 1;
        at += 1;
      }
      ctx.expect(branchCount).toBe(4); // one per effect (the helper definition doesn't match)

      // the spans resolve in real chat (the decorate path; the colors
      // themselves are the shader's business — judged by eyes below).
      // Close tags are </e>/</effect> — the id lives on the open tag only
      await ctx.exec(`mc.getConnection().sendChat('A <e:rainbow>rainbow</e> <e:wave>wave</e> <e:shake>shake</e> <e:pulse>pulse</e> B')
return 'sent'`, 15_000);
      const deadline = Date.now() + 15_000;
      let line = null;
      for (;;) {
        const lines = await ctx.chat(12);
        line = lines.find((l) => l.includes('rainbow') && l.includes('pulse')) ?? null;
        if (line) break;
        if (Date.now() > deadline) break;
        await ctx.sleep(400);
      }
      ctx.expect(line).toBeTruthy();
      ctx.expect(line.includes('<e:')).toBe(false); // tags consumed
      ctx.expect(line.includes('</e')).toBe(false);

      // the animation itself: two frames 700ms apart — judged by eyes
      await ctx.sleep(700);
      await ctx.shot('effects-frame-a');
      await ctx.sleep(700);
      await ctx.shot('effects-frame-b');
      ctx.needsEyes('the four effect words (rainbow, wave, shake, pulse) render animated by the injected text shader — rainbow cycling hues, wave rippling vertically, shake jittering, pulse fading — visibly DIFFERENT between the two frames while the plain A/B words stay static, and no unshaded fallback (the effect words are not plain solid magenta-family signal text)');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: glyphs.effects @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
