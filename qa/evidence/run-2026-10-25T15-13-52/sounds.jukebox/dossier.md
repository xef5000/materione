# [qa:sounds] a music disc plays the pack's jukebox song end to end in a real jukebox: the ruby disc carries the jukebox_playable component resolved against the generated datapack (song sample:anthem, description "Ruby Anthem", length 90s, comparator output 7), inserting it through the client flips the jukebox to has_record=true and plays sample:ui/anthem through the sound engine, the authored comparator_output reaches the redstone channel (the wire behind the comparator reads exactly 7), and an empty-hand click ejects the disc — record gone, wire back to 0, playback stopped, the disc back in the inventory

**Outcome:** FAIL — expected output to contain Ruby disc, got  [toContain] expected=Ruby disc actual=
**Test:** `sounds.jukebox` (auto) · **Suite:** `qa/catalog/sounds.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run sounds.jukebox`

## What was expected

- operator: `toContain`
- expected: `Ruby disc`
- actual: ``

Error:

```
Error: expected output to contain Ruby disc, got 
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toContain (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:771:42)
    at Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/sounds.mjs:258:31)
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async withTimeout (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:585:12)
    at async runTest (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:504:5)
    at async cmdRun (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:441:24)
```

## Evidence

Screenshots (JPEG, read directly from disk):

- `qa/evidence/run-2026-10-25T15-13-52/sounds.jukebox\jukebox-playing.jpg`

Chat history (newest last):

```
(no chat captured)
```

Server log tail (harness\build\probe\server\logs\latest.log):

```
127.0.0.1 started
[11:26:35] [RCON Client /127.0.0.1 #694/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:35] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:35] [RCON Client /127.0.0.1 #695/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:35] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:35] [RCON Client /127.0.0.1 #696/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:36] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:36] [RCON Client /127.0.0.1 #697/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:36] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:36] [RCON Client /127.0.0.1 #698/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:36] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:37] [RCON Client /127.0.0.1 #699/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:37] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:37] [RCON Client /127.0.0.1 #700/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:37] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:37] [RCON Client /127.0.0.1 #701/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:38] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:38] [Server thread/INFO]: [Materione] MATERIONE-GIVE-OK id=sample:ruby_disc player=Probe amount=1
[11:26:38] [RCON Client /127.0.0.1 #702/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:38] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:38] [RCON Client /127.0.0.1 #703/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:38] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:39] [RCON Client /127.0.0.1 #704/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:39] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:39] [RCON Client /127.0.0.1 #705/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:52] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:52] [RCON Client /127.0.0.1 #706/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:26:56] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:26:56] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:26:57] [RCON Client /127.0.0.1 #707/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:61634
Oct 25, 2026 11:26:51 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:26:51 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:56076
Oct 25, 2026 11:26:51 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:26:52 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:56078
Oct 25, 2026 11:26:52 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:26:53 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:53747
Oct 25, 2026 11:26:56 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/sounds.jukebox\transcript.jsonl`

## Test definition

```js
id: "sounds.jukebox"
title: "a music disc plays the pack's jukebox song end to end in a real jukebox: the ruby disc carries the jukebox_playable component resolved against the generated datapack (song sample:anthem, description \"Ruby Anthem\", length 90s, comparator output 7), inserting it through the client flips the jukebox to has_record=true and plays sample:ui/anthem through the sound engine, the authored comparator_output reaches the redstone channel (the wire behind the comparator reads exactly 7), and an empty-hand click ejects the disc — record gone, wire back to 0, playback stopped, the disc back in the inventory"
mode: "auto"
dependsOn: ["sounds.replace-vanilla"]
covers: ["content/src/main/java/dev/materione/content/sounds/**","content/src/main/java/dev/materione/content/items/**","platform/src/main/java/dev/materione/platform/items/**","fixtures/packs/sample/sounds/**","fixtures/packs/sample/items/**"]

steps:
async (ctx) => {
      const spot = { x: 2, y: -60, z: 3 };
      await ctx.reset();
      await ctx.rcon('materione give sample:ruby_disc Probe 1');

      // --- the disc's component, resolved against the loaded datapack song
      const comp = await ctx.exec(`def st = mc.player.getInventory().getItem(0)
def dc = Class.forName('net.minecraft.core.component.DataComponents')
def comp = st.get(dc.getField('JUKEBOX_PLAYABLE').get(null))
return comp == null ? 'NONE' : comp.toString()`, 15_000);
      ctx.expect(String(comp)).toContain('sample:anthem');
      ctx.expect(String(comp)).toContain('description=literal{Ruby Anthem}');
      ctx.expect(String(comp)).toContain('lengthInSeconds=90.0');
      ctx.expect(String(comp)).toContain('comparatorOutput=7');

      // --- the stage: jukebox, comparator reading it, wire as the readout
      // (the comparator's cached NBT output lags — the wire's client state is
      // the honest channel)
      await ctx.rcon('setblock 2 -60 3 minecraft:jukebox');
      await ctx.rcon('setblock 2 -60 4 minecraft:comparator[mode=compare,facing=north]');
      await ctx.rcon('setblock 2 -60 5 minecraft:redstone_wire');

      // --- insert through the client: disc selected, useItemOn
      await ctx.exec(`def inv = mc.player.getInventory()
for (int i = 0; i < 9; i++) { if (inv.getItem(i).getHoverName().getString().contains('Ruby disc')) { inv.selected = i; break } }
def player = mc.player
def hit = new net.minecraft.world.phys.BlockHitResult(
    new net.minecraft.world.phys.Vec3(2.0, -59.5, 3.5),
    net.minecraft.core.Direction.WEST,
    new net.minecraft.core.BlockPos(2, -60, 3), false)
mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'inserted'`, 15_000);

      const state = async () => ctx.exec(`def jukebox = mc.level.getBlockState(new net.minecraft.core.BlockPos(2, -60, 3))
def wire = mc.level.getBlockState(new net.minecraft.core.BlockPos(2, -60, 5))
return jukebox.toString() + ' ' + wire.toString()`, 15_000);
      const deadline = Date.now() + 8_000;
      let now = await state();
      for (;;) {
        if (String(now).includes('has_record=true') && String(now).includes('power=7')) break;
        if (Date.now() > deadline) break;
        await ctx.sleep(400);
        now = await state();
      }
      ctx.expect(String(now)).toContain('has_record=true');
      ctx.expect(String(now)).toContain('power=7'); // the authored comparator_output
      ctx.expect(await waitForActiveSound(ctx, 'sample:ui/anthem', 4_000)).toBe(true);
      ctx.expect(await ctx.shot('jukebox-playing')).toBeTruthy();

      // --- eject with an empty hand (the disc left the hand on insert),
      // late enough that the sound is fully live — an eject that races the
      // engine's queue can leave the young instance playing its course
      await ctx.sleep(1_500);
      await ctx.exec(`def player = mc.player
def hit = new net.minecraft.world.phys.BlockHitResult(
    new net.minecraft.world.phys.Vec3(2.0, -59.5, 3.5),
    net.minecraft.core.Direction.WEST,
    new net.minecraft.core.BlockPos(2, -60, 3), false)
mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'ejected'`, 15_000);
      let after = await state();
      const ejectDeadline = Date.now() + 6_000;
      for (;;) {
        if (String(after).includes('has_record=false') && String(after).includes('power=0')) break;
        if (Date.now() > ejectDeadline) break;
        await ctx.sleep(400);
        after = await state();
      }
      ctx.expect(String(after)).toContain('has_record=false');
      ctx.expect(String(after)).toContain('power=0');
      // the streamed jukebox instance retires off-thread once the eject
      // broadcast lands — poll, don't race it
      const stillPlaying = await waitForActiveSound(ctx, 'sample:ui/anthem', 10_000);
      ctx.expect(stillPlaying).toBe(false);

      // --- the popped disc returns to the inventory
      await ctx.rcon('tp Probe 1.5 -60 3.5');
      await ctx.sleep(1_200);
      const inv = await ctx.exec(`def out = []
def playerInv = mc.player.getInventory()
for (int i = 0; i < 46; i++) {
  def st = playerInv.getItem(i)
  if (!st.isEmpty()) out.add(st.getHoverName().getString())
}
return out.join(' | ')`, 15_000);
      ctx.expect(String(inv)).toContain('Ruby disc');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: sounds.jukebox @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
