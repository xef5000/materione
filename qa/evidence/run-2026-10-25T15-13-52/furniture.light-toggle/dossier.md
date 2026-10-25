# [qa:furniture] light cells carry per-cell levels (minecraft:light[level=8] above the anchor, [level=7] east — exact predicates reject the neighbor values, and the client engine reads each cell at its own level, 8 and 7, since the 8-cell floods only 6 into the 7-cell) and a right-click on the cluster falls through the empty ECA→storage→seat priority chain as a silent no-op that changes neither cell — toggleable lights are not built (the only light writes are placement and record-driven removal), and dev remove clears both cells

**Outcome:** FAIL — probe agent exec: bridge exec failed: No method 'getEntity()' on net.minecraft.world.phys.BlockHitResult
**Test:** `furniture.light-toggle` (auto) · **Suite:** `qa/catalog/furniture.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run furniture.light-toggle`

## What was expected

This was a thrown error, not an assertion failure.

Error:

```
Error: probe agent exec: bridge exec failed: No method 'getEntity()' on net.minecraft.world.phys.BlockHitResult
    at Interface.<anonymous> (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:615:19)
    at Interface.emit (node:events:509:28)
    at [_onLine] [as _onLine] (node:internal/readline/interface:465:12)
    at [_normalWrite] [as _normalWrite] (node:internal/readline/interface:647:22)
    at Socket.ondata (node:internal/readline/interface:263:23)
    at Socket.emit (node:events:509:28)
    at addChunk (node:internal/streams/readable:563:12)
    at readableAddChunkPushByteMode (node:internal/streams/readable:514:3)
    at Readable.push (node:internal/streams/readable:394:5)
    at Pipe.onStreamRead (node:internal/stream_base_commons:189:23)
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
[11:19:36] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:36] [RCON Client /127.0.0.1 #336/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:36] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:37] [RCON Client /127.0.0.1 #337/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:37] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:37] [RCON Client /127.0.0.1 #338/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:47] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:47] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world 1 -60 7
[11:19:47] [RCON Client /127.0.0.1 #339/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:47] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:48] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -60 7
[11:19:48] [RCON Client /127.0.0.1 #340/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:48] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:48] [RCON Client /127.0.0.1 #341/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:48] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:48] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -59 7
[11:19:48] [RCON Client /127.0.0.1 #342/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:48] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:49] [RCON Client /127.0.0.1 #343/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:49] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:49] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 2 -60 7
[11:19:49] [RCON Client /127.0.0.1 #344/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:49] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:49] [RCON Client /127.0.0.1 #345/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:49] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:50] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:lamp world 1 -60 7
[11:19:50] [RCON Client /127.0.0.1 #346/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:50] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:50] [RCON Client /127.0.0.1 #347/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:50] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:50] [RCON Client /127.0.0.1 #348/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:50] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:50] [RCON Client /127.0.0.1 #349/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:50] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:51] [RCON Client /127.0.0.1 #350/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:53] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:53] [RCON Client /127.0.0.1 #351/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:54] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:54] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:19:54] [RCON Client /127.0.0.1 #352/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:52676
Oct 25, 2026 11:19:52 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:52 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:52678
Oct 25, 2026 11:19:52 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:52 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:52680
Oct 25, 2026 11:19:53 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:53 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:52683
Oct 25, 2026 11:19:54 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/furniture.light-toggle\transcript.jsonl`

## Test definition

```js
id: "furniture.light-toggle"
title: "light cells carry per-cell levels (minecraft:light[level=8] above the anchor, [level=7] east — exact predicates reject the neighbor values, and the client engine reads each cell at its own level, 8 and 7, since the 8-cell floods only 6 into the 7-cell) and a right-click on the cluster falls through the empty ECA→storage→seat priority chain as a silent no-op that changes neither cell — toggleable lights are not built (the only light writes are placement and record-driven removal), and dev remove clears both cells"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["content/src/main/java/dev/materione/content/furniture/**","plugin/src/main/java/dev/materione/plugin/runtime/furniture/**","core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java","fixtures/packs/world/furnitures/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: anchor [1,-60,7]; the definition's lights are [0,1,0]
      // level 8 (yaw-invariant → [1,-59,7]) and [1,0,0] level 7 (yaw 0 →
      // the east cell [2,-60,7]). The near-tied levels keep each cell's OWN
      // level client-visible: the 8-cell floods only 6 into the 7-cell, so
      // the client reads 8 and 7 — a level-15 sibling would dominate the
      // pair (its flood reaches 13 two steps away) and mask the 7. No
      // barrier hitbox: the anchor cell stays air and every click targets
      // the interaction entity.
      const anchor = [1, -60, 7];
      const cellA = [1, -59, 7]; // level 8, above
      const cellB = [2, -60, 7]; // level 7, east
      const shared = [2, -59, 7]; // adjacent to both: max(7, 6)
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      for (const cell of [anchor, cellA, cellB]) {
        await ctx.rcon(`materione dev remove ${cell.join(' ')} world`);
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }

      // baseline: the scene is dark
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(0);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(0);

      // --- place: both cells land at their per-cell levels — a wrong-but-
      // close level on either cell must fail the predicate
      await ctx.rcon(`materione dev place world:lamp ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:lamp world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:light[level=8]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:light[level=7]`))
        .toBe('Test failed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:light[level=7]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:light[level=8]`))
        .toBe('Test failed');

      // each cell reads at its OWN level on the client — the exactness the
      // block predicates pinned server-side is what the engine received
      await ctx.sleep(1500); // light packets → client engine
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(8);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(7);
      ctx.expect(await blockLightAt(ctx, shared)).toBe(7);

      // --- the toggle question: a right-click falls through the priority
      // chain (no ECA rules, storage 0, no seats) and must leave every cell
      // exactly as placed. The click is a real ServerboundInteractPacket
      // built from the verified entity hit — the same packet path the
      // storage and seats tests click through, aimed here at the lamp's
      // interaction entity (player at [1,-60,5]: eye (1.5,-58.62,5.5), box
      // (1.5,-59.5,7.5) spans y -60..-59, pitch 24 enters its north face
      // ~2.2 away, inside the 3.0 entity reach).
      await ctx.rcon('tp Probe 1 -60 5');
      const vehicle = () => ctx.exec(`def v = player.getVehicle()
return v == null ? 'none' : v.getType().toString()`, 15_000);
      const clickLamp = async (tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          last = String(await ctx.exec(`player.setRot(0F, 24F)
Thread.sleep(500)
def hit = mc.hitResult
return hit.getType().toString() + ' ' + (hit.getEntity() == null ? '-' : hit.getEntity().getType().toString())`, 15_000));
          if (last === 'ENTITY entity.minecraft.interaction') {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'ENTITY' || hit.getEntity() == null || hit.getEntity().getType().toString() != 'entity.minecraft.interaction') return 'AIM-DRIFT'
def result = mc.gameMode.interact(player, hit.getEntity(), hit, net.minecraft.world.InteractionHand.MAIN_HAND)
return 'interact:' + result.toString()`, 15_000);
            if (String(reply).startsWith('interact:')) return;
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe('interact sent on entity.minecraft.interaction');
      };
      await clickLamp();
      await ctx.sleep(1000); // any region-thread reaction would land by now
      ctx.expect(await vehicle()).toBe('none'); // no seat consumed the click
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.containerId'))).toBe(0); // no storage opened
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:light[level=8]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:light[level=7]`))
        .toBe('Test passed');
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(8);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(7);

      // --- teardown: the light record drives removal — both cells release
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:air`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:air`))
        .toBe('Test passed');
      await ctx.sleep(1500);
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(0);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(0);
      await ctx.rcon('kill @e[type=item]'); // the placement-item drop from remove
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: furniture.light-toggle @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
