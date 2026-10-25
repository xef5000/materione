# [qa:furniture] the shipped hitbox pair behaves: a barrier cell is real collision that stops a walking player (who crosses the anchor cell freely before the cluster exists), the interaction entity is sized exactly per the fixture (width 1.2, height 1.1, responsive) and routes clicks to the cluster, and removal releases the cell so the walk-through works again

**Outcome:** FAIL — expected 5.699999988079071 > 7.5 [toBeGreaterThan] expected=7.5 actual=5.699999988079071
**Test:** `furniture.hitboxes` (auto) · **Suite:** `qa/catalog/furniture.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run furniture.hitboxes`

## What was expected

- operator: `toBeGreaterThan`
- expected: `7.5`
- actual: `5.699999988079071`

Error:

```
Error: expected 5.699999988079071 > 7.5
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toBeGreaterThan (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:778:34)
    at Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/furniture.mjs:540:25)
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
 [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:03] [RCON Client /127.0.0.1 #300/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:03] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:03] [RCON Client /127.0.0.1 #301/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:03] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:03] [RCON Client /127.0.0.1 #302/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:03] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:04] [RCON Client /127.0.0.1 #303/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:04] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:04] [RCON Client /127.0.0.1 #304/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:04] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:05] [RCON Client /127.0.0.1 #305/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:05] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:05] [RCON Client /127.0.0.1 #306/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:05] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:05] [RCON Client /127.0.0.1 #307/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:11] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:11] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -60 7
[11:19:11] [RCON Client /127.0.0.1 #308/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:11] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:11] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -59 7
[11:19:11] [RCON Client /127.0.0.1 #309/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:11] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:11] [RCON Client /127.0.0.1 #310/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:11] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:12] [RCON Client /127.0.0.1 #311/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:12] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:12] [RCON Client /127.0.0.1 #312/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:14] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:14] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:19:14] [RCON Client /127.0.0.1 #313/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:65305
Oct 25, 2026 11:19:12 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:12 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:65307
Oct 25, 2026 11:19:12 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:13 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:62497
Oct 25, 2026 11:19:13 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:14 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:64357
Oct 25, 2026 11:19:14 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/furniture.hitboxes\transcript.jsonl`

## Test definition

```js
id: "furniture.hitboxes"
title: "the shipped hitbox pair behaves: a barrier cell is real collision that stops a walking player (who crosses the anchor cell freely before the cluster exists), the interaction entity is sized exactly per the fixture (width 1.2, height 1.1, responsive) and routes clicks to the cluster, and removal releases the cell so the walk-through works again"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["content/src/main/java/dev/materione/content/furniture/**","plugin/src/main/java/dev/materione/plugin/runtime/furniture/**","core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java","fixtures/packs/world/furnitures/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain before input work

      // --- scene: the cluster anchors at [1,-60,7]; the barrier hitbox
      // (offset [0,0,0]) occupies the anchor cell, dead in the walk path
      // from the spawn point (1,-60,3) heading south.
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      const spawn = 'tp Probe 1 -60 3';
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${light.join(' ')} minecraft:air`);

      // movement is hold-driven: walk south for 1.2s, release, let the
      // server-corrected position settle, then read the player z
      const walk = async () => {
        await ctx.exec('player.setRot(0F, 0F)\nreturn \'faced-south\'');
        await ctx.exec('mc.options.keyUp.setDown(true)\nreturn \'walking\'');
        await ctx.sleep(1200);
        await ctx.exec('mc.options.keyUp.setDown(false)\nreturn \'released\'');
        await ctx.sleep(600);
        return Number(await ctx.exec('return player.getZ()'));
      };

      // --- baseline: the empty scene is walkable — the player crosses the
      // anchor cell's z (and the interaction column) entirely
      await ctx.rcon(spawn);
      const openZ = await walk();
      ctx.expect(openZ).toBeGreaterThan(7.5);
      await ctx.rcon(spawn);

      // --- place: the barrier hitbox claims the anchor cell
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');

      // --- collision: the same walk stops at the barrier's north face
      const blockedZ = await walk();
      ctx.expect(blockedZ).toBeLessThan(6.9);
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');
      await ctx.rcon(spawn);

      // --- the interaction hitbox is sized per the fixture (width 1.2,
      // height 1.1) and responsive; its click routing to the cluster is
      // covered by furniture.storage
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=interaction,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] width`)).toContain('1.2');
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=interaction,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] height`)).toContain('1.1');
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=interaction,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] response`)).toContain('1b');

      // --- teardown: the cell releases and the path is walkable again
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:air`))
        .toBe('Test passed');
      const reopenedZ = await walk();
      ctx.expect(reopenedZ).toBeGreaterThan(7.5);
      await ctx.rcon('clear Probe');
      await ctx.rcon('kill @e[type=item]');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: furniture.hitboxes @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
