# [qa:furniture] a 54-slot crate opens a 6-row "Crate" menu (90 slots) on a client right-click, a shift-clicked diamond reaches slot 0 and a cursor pickup pair places it in the LAST container slot 53 (full-grid depth), two crates of the same id hold independent grids (diamond vs iron), and both records survive materione reload with the root uuids unchanged — removal spills each grid

**Outcome:** FAIL — probe agent exec: bridge exec failed: No method 'getEntity()' on net.minecraft.world.phys.BlockHitResult
**Test:** `furniture.storage-capacity` (auto) · **Suite:** `qa/catalog/furniture.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run furniture.storage-capacity`

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
 [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:54] [RCON Client /127.0.0.1 #353/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:54] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:54] [RCON Client /127.0.0.1 #354/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:54] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:55] [RCON Client /127.0.0.1 #355/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:55] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:55] [RCON Client /127.0.0.1 #356/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:55] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:56] [RCON Client /127.0.0.1 #357/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:56] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:56] [RCON Client /127.0.0.1 #358/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:56] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:56] [RCON Client /127.0.0.1 #359/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:56] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:56] [RCON Client /127.0.0.1 #360/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:07] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:07] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world 1 -60 7
[11:20:07] [RCON Client /127.0.0.1 #361/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:07] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:07] [RCON Client /127.0.0.1 #362/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:07] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:07] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -60 11
[11:20:08] [RCON Client /127.0.0.1 #363/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:08] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:08] [RCON Client /127.0.0.1 #364/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:08] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:08] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:crate world 1 -60 7
[11:20:08] [RCON Client /127.0.0.1 #365/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:08] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:08] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:crate world 1 -60 11
[11:20:08] [RCON Client /127.0.0.1 #366/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:08] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:09] [RCON Client /127.0.0.1 #367/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:09] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:09] [RCON Client /127.0.0.1 #368/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:10] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:10] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:20:10] [RCON Client /127.0.0.1 #369/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
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
[11:20:09] [Render thread/INFO]: Resizing Dynamic Transforms UBO, capacity limit of 8 reached during a single frame. New capacity will be 16.
Oct 25, 2026 11:20:09 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:54051
Oct 25, 2026 11:20:10 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/furniture.storage-capacity\transcript.jsonl`

## Test definition

```js
id: "furniture.storage-capacity"
title: "a 54-slot crate opens a 6-row \"Crate\" menu (90 slots) on a client right-click, a shift-clicked diamond reaches slot 0 and a cursor pickup pair places it in the LAST container slot 53 (full-grid depth), two crates of the same id hold independent grids (diamond vs iron), and both records survive materione reload with the root uuids unchanged — removal spills each grid"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["content/src/main/java/dev/materione/content/furniture/**","plugin/src/main/java/dev/materione/plugin/runtime/furniture/**","core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java","fixtures/packs/world/furnitures/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: two crates of the same id, 4 blocks apart on the same
      // column so one aim geometry serves both: crate A anchors at
      // [1,-60,7] (clicked from [1,-60,5]) and crate B at [1,-60,11]
      // (clicked from [1,-60,9]) — the interaction box is always dz=2,
      // yaw 0 / pitch 24 south. No barrier hitbox: clicks target the
      // interaction entity.
      const anchorA = [1, -60, 7];
      const anchorB = [1, -60, 11];
      for (const anchor of [anchorA, anchorB]) {
        await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
        await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      }
      await ctx.rcon(`materione dev place world:crate ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:crate world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev place world:crate ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:crate world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });

      // Menu slot map for a 6-row menu (54 + 36): container 0..53, main
      // inventory 54..80, hotbar 81..89 — the given items ride hotbar 0/1
      // = menu slots 81/82.
      const menuOpen = async () => String(await ctx.exec(
        'return player.containerMenu.containerId == 0 ? "closed" : "open"'));
      const waitForMenu = async (want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          if ((await menuOpen()) === want) return;
          if (Date.now() > deadline) ctx.expect(await menuOpen()).toBe(want);
          await ctx.sleep(300);
        }
      };
      const slotName = (index) => ctx.exec(`def s = player.containerMenu.getSlot(${index}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString()`);
      const waitForSlot = async (index, want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const name = String(await slotName(index));
          if (name === want) return;
          if (Date.now() > deadline) ctx.expect(name).toBe(want);
          await ctx.sleep(300);
        }
      };
      const click = (slot, mode) => ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, 0, net.minecraft.world.inventory.ContainerInput.${mode}, player)
return 'clicked'`);
      const screenTitle = () => ctx.exec(
        "return mc.screen == null ? 'none' : mc.screen.getTitle().getString()");

      // right-click the crate's interaction entity (same geometry the
      // light-toggle test proves: box (1.5,-59.5,7.5) from an eye at
      // (1.5,-58.62,5.5), pitch 24, ~2.2 away, inside the 3.0 reach)
      const openCrate = async (anchor, tries = 3) => {
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
            if (String(reply).startsWith('interact:')) {
              await waitForMenu('open');
              return;
            }
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe('crate menu open');
      };
      const closeMenu = async () => {
        await ctx.exec('player.closeContainer()\nreturn \'closed\'');
        await waitForMenu('closed');
        await ctx.sleep(700); // MONITOR close listener writes the grid back
      };

      // --- capacity: 54 container slots + the 36-slot player inventory,
      // title from the fixture's display_name
      await ctx.rcon('tp Probe 1 -60 5');
      await ctx.rcon('give Probe minecraft:diamond 1'); // hotbar 0 → menu 81
      await openCrate(anchorA);
      ctx.expect(String(await screenTitle())).toBe('Crate');
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(90);
      // shift-click deposits into slot 0; a cursor PICKUP pair reaches the
      // last container slot (54 diamonds would be needed to fill there by
      // shift-click alone)
      await click(81, 'QUICK_MOVE');
      await waitForSlot(0, 'Diamond');
      await click(0, 'PICKUP'); // diamond → cursor
      await click(53, 'PICKUP'); // cursor → last container slot
      await waitForSlot(53, 'Diamond');
      await waitForSlot(0, 'empty');
      await closeMenu();

      // --- isolation: crate B carries its own grid from its own record
      await ctx.rcon('tp Probe 1 -60 9');
      await ctx.rcon('give Probe minecraft:iron_ingot 1'); // hotbar 0 → menu 81
      await openCrate(anchorB);
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(90);
      await click(81, 'QUICK_MOVE');
      await waitForSlot(0, 'Iron Ingot');
      await closeMenu();

      // reopen A: the diamond sits at 53 and B's iron never crossed over
      await ctx.rcon('tp Probe 1 -60 5');
      await openCrate(anchorA);
      await waitForSlot(53, 'Diamond');
      ctx.expect(String(await slotName(0))).toBe('empty');
      await closeMenu();
      // reopen B: the iron sits at 0 and A's diamond never crossed over
      await ctx.rcon('tp Probe 1 -60 9');
      await openCrate(anchorB);
      await waitForSlot(0, 'Iron Ingot');
      ctx.expect(String(await slotName(53))).toBe('empty');
      await closeMenu();

      // --- reload survival: records ride the root entities, which reload
      // never rewrites (visuals-only policy) — the root uuid must come back
      // identical, not a fresh entity with an empty record
      await ctx.rcon(`materione dev inspect ${anchorA.join(' ')} world`);
      const preReload = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:crate .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const rootA = /root=([0-9a-f-]{36}) /.exec(preReload)[1];
      await ctx.rcon('materione reload');
      // the item count itself is pinned by the harness IT — this test only
      // needs the reload-completed marker, so the count stays unmatched
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/, { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev inspect ${anchorA.join(' ')} world`);
      const postReload = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:crate .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      ctx.expect(/root=([0-9a-f-]{36}) /.exec(postReload)[1]).toBe(rootA);
      await ctx.rcon('tp Probe 1 -60 5');
      await openCrate(anchorA);
      await waitForSlot(53, 'Diamond');
      await closeMenu();
      await ctx.rcon('tp Probe 1 -60 9');
      await openCrate(anchorB);
      await waitForSlot(0, 'Iron Ingot');
      await closeMenu();

      // --- teardown: removal spills each grid beside its own cluster
      await ctx.rcon(`materione dev remove ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(
        `execute positioned ${anchorA[0] + 0.5} ${anchorA[1] + 0.5} ${anchorA[2] + 0.5}`
        + ` as @e[type=item,distance=..4] run data get entity @s Item.id`))
        .toContain('minecraft:diamond');
      await ctx.rcon('kill @e[type=item]'); // the spill + the placement-item drop
      await ctx.rcon(`materione dev remove ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(
        `execute positioned ${anchorB[0] + 0.5} ${anchorB[1] + 0.5} ${anchorB[2] + 0.5}`
        + ` as @e[type=item,distance=..4] run data get entity @s Item.id`))
        .toContain('minecraft:iron_ingot');
      await ctx.rcon('kill @e[type=item]');
      await ctx.rcon('clear Probe');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: furniture.storage-capacity @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
