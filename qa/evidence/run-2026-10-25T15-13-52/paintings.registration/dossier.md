# [qa:paintings] the pack's three painting variants are composed through Paper's registry events at bootstrap (boot marker paintings=3 mode=registry, doctor reports registry content current with nothing deployed) and reach the client's synced painting-variant registry with their authored size, asset_id, author and title — sunrise 2×1, moonrise 1×2, panorama 3×2, an unregistered id resolves to nothing and a vanilla variant stays vanilla; /materione dev painting places the custom variants on a real wall (the entity's variant reads back — the driver used to set the NMS direction inverted so every placement failed no-space, #101), refuses a variant that doesn't fit, reports unknown variants and answers a floor aim with the wall hint

**Outcome:** FAIL — expected 0,-60,7 north, got 0,-60,5 up [toBe] expected=0,-60,7 north actual=0,-60,5 up
**Test:** `paintings.registration` (auto) · **Suite:** `qa/catalog/paintings.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run paintings.registration`

## What was expected

- operator: `toBe`
- expected: `0,-60,7 north`
- actual: `0,-60,5 up`

Error:

```
Error: expected 0,-60,7 north, got 0,-60,5 up
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toBe (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:765:32)
    at file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/paintings.mjs:44:31
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async aimAt (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/paintings.mjs:36:3)
    at async Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/paintings.mjs:103:7)
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
127.0.0.1 started
[11:25:01] [RCON Client /127.0.0.1 #621/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:01] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:01] [RCON Client /127.0.0.1 #622/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:01] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:01] [RCON Client /127.0.0.1 #623/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:01] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:01] [RCON Client /127.0.0.1 #624/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:02] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:02] [RCON Client /127.0.0.1 #625/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:02] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:02] [RCON Client /127.0.0.1 #626/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:02] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:03] [RCON Client /127.0.0.1 #627/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:03] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:03] [RCON Client /127.0.0.1 #628/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:03] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:03] [Server thread/INFO]: [Materione] MATERIONE-DOCTOR-OK
[11:25:03] [RCON Client /127.0.0.1 #629/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:04] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:04] [RCON Client /127.0.0.1 #630/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:04] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:04] [RCON Client /127.0.0.1 #631/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:04] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:04] [RCON Client /127.0.0.1 #632/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:04] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:05] [RCON Client /127.0.0.1 #633/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:25:06] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:25:06] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:25:06] [RCON Client /127.0.0.1 #634/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:63673
Oct 25, 2026 11:25:00 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:25:00 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:63675
Oct 25, 2026 11:25:00 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:25:03 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:56789
Oct 25, 2026 11:25:04 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:25:05 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:52510
Oct 25, 2026 11:25:06 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/paintings.registration\transcript.jsonl`

## Test definition

```js
id: "paintings.registration"
title: "the pack's three painting variants are composed through Paper's registry events at bootstrap (boot marker paintings=3 mode=registry, doctor reports registry content current with nothing deployed) and reach the client's synced painting-variant registry with their authored size, asset_id, author and title — sunrise 2×1, moonrise 1×2, panorama 3×2, an unregistered id resolves to nothing and a vanilla variant stays vanilla; /materione dev painting places the custom variants on a real wall (the entity's variant reads back — the driver used to set the NMS direction inverted so every placement failed no-space, #101), refuses a variant that doesn't fit, reports unknown variants and answers a floor aim with the wall hint"
mode: "auto"
dependsOn: []
covers: ["content/src/main/java/dev/materione/content/paintings/**","modern/src/main/java/dev/materione/modern/**","plugin/src/main/java/dev/materione/plugin/command/**","fixtures/packs/sample/paintings/**"]

steps:
async (ctx) => {
      await ctx.reset();

      // --- registry mode is the live seam: composed at bootstrap, nothing
      // deployed, doctor honest about it
      ctx.expect(
        await ctx.serverLogLine(/MATERIONE-REGISTRY-OK paintings=3 songs=\d+ mode=registry/, { sinceTest: false }),
      ).toBeTruthy();
      const doctor = await ctx.rcon('materione doctor');
      ctx.expect(doctor).toContain('registry: paintings/jukebox registered at boot (content ');
      ctx.expect(doctor).toContain('— current');
      ctx.expect(doctor.includes('RESTART REQUIRED')).toBe(false);

      // --- the client's synced registry carries the authored properties
      const read = await ctx.exec(REGISTRY_READ, 20_000);
      ctx.expect(read).toContain(
        'sample:sunrise size=2x1 asset=sample:sunrise author=Materione title=Sunrise');
      ctx.expect(read).toContain(
        'sample:moonrise size=1x2 asset=sample:moonrise author=Materione title=Moonrise');
      ctx.expect(read).toContain(
        'sample:panorama size=3x2 asset=sample:panorama author=Materione title=Panorama');
      ctx.expect(read).toContain('sample:nope=MISSING'); // registration is real
      ctx.expect(read).toMatch(/minecraft:alban size=1x1 asset=minecraft:alban/); // vanilla stays vanilla

      // --- scene: a wall south of the anchor with clear hanging space
      await ctx.rcon('kill @e[type=painting]');
      await ctx.rcon('fill -2 -61 7 2 -58 7 minecraft:smooth_stone');
      await ctx.rcon('fill -2 -61 6 2 -58 6 minecraft:air');
      await ctx.rcon('tp Probe 0 -60 2');
      await ctx.sleep(600);

      // --- dev painting places each custom variant; the placed entity's
      // variant reads back through the server
      await aimAt(ctx, 0, 12, [0, -60, 7], 'north');
      await devPainting(ctx, 'sample:sunrise');
      await ctx.waitForChat(/dev painting: placed sample:sunrise/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING ok=true key=sample:sunrise/)).toBeTruthy();
      ctx.expect(await ctx.rcon('data get entity @e[type=painting,x=0.5,y=-59.5,z=6.5,distance=..2,limit=1] variant'))
        .toContain('sample:sunrise');

      await aimAt(ctx, -26, 13, [2, -60, 7], 'north');
      await devPainting(ctx, 'sample:moonrise');
      await ctx.waitForChat(/dev painting: placed sample:moonrise/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING ok=true key=sample:moonrise/)).toBeTruthy();

      // --- a variant that doesn't fit the aim: the sunrise painting already
      // occupies the moonrise aim's hanging cell
      await aimAt(ctx, 0, 12, [0, -60, 7], 'north');
      await devPainting(ctx, 'sample:panorama');
      await ctx.waitForChat(/doesn't fit on that wall/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING failed reason=no-space key=sample:panorama/))
        .toBeTruthy();

      // --- an unregistered id answers with the restart honesty, not a crash
      await devPainting(ctx, 'sample:nope');
      await ctx.waitForChat(/is not registered/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING failed reason=unknown-variant key=sample:nope/))
        .toBeTruthy();

      // --- floor/ceiling aims are refused with the wall hint (a non-
      // horizontal face used to crash the command outright, #101)
      await aimAt(ctx, 0, 28, [0, -61, 5], 'up');
      await devPainting(ctx, 'sample:sunrise');
      await ctx.waitForChat(/look at a wall \(not the floor or ceiling\)/);

      await ctx.rcon('kill @e[type=painting]');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: paintings.registration @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
