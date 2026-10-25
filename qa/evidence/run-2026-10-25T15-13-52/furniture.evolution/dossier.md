# [qa:furniture] evolution stages apply lazily on chunk-load replay from absolute placement timestamps: a replay before 180s keeps the cluster, a replay in the [180s, 330s) window applies exactly world:sprout (the furthest DUE step — bloom at 330s is listed on the same definition but is not yet eligible), and a replay past 330s applies world:bloom — all in place (root uuids and member records unchanged, the deposited diamond rides along, the menu title tracks the current stage) — and removal dissolves the evolved cluster

**Outcome:** FAIL — probe agent exec: bridge exec failed: No method 'getEntity()' on net.minecraft.world.phys.BlockHitResult
**Test:** `furniture.evolution` (auto) · **Suite:** `qa/catalog/furniture.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run furniture.evolution`

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
[11:20:12] [RCON Client /127.0.0.1 #376/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:12] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:12] [RCON Client /127.0.0.1 #377/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:18] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:18] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world 1 -60 7
[11:20:18] [RCON Client /127.0.0.1 #378/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:18] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:18] [RCON Client /127.0.0.1 #379/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:18] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:18] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world 1 -60 11
[11:20:19] [RCON Client /127.0.0.1 #380/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:19] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:19] [RCON Client /127.0.0.1 #381/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:19] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:19] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:seedling world 1 -60 7
[11:20:19] [RCON Client /127.0.0.1 #382/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:19] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:19] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:seedling world 1 -60 11
[11:20:19] [RCON Client /127.0.0.1 #383/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:19] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:19] [Server thread/INFO]: [Materione] MATERIONE-DEV-INSPECT found=false none material=air rawSlot=- world 1 -60 7
[11:20:20] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:seedling display=1.5,-59.5,7.5 root=f5ae9329-8f1a-42b3-b6b4-9f27e4376b1e world 1 -60 7
[11:20:20] [RCON Client /127.0.0.1 #384/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:20] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:20] [Server thread/INFO]: [Materione] MATERIONE-DEV-INSPECT found=false none material=air rawSlot=- world 1 -60 11
[11:20:20] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:seedling display=1.5,-59.5,11.5 root=725e350e-04a2-427d-8db2-bbfc3e8f43f1 world 1 -60 11
[11:20:20] [RCON Client /127.0.0.1 #385/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:20] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:20] [RCON Client /127.0.0.1 #386/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:20] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:20] [RCON Client /127.0.0.1 #387/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:20] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:21] [RCON Client /127.0.0.1 #388/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:21] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:21] [RCON Client /127.0.0.1 #389/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:21] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:21] [RCON Client /127.0.0.1 #390/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:20:22] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:20:22] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:20:22] [RCON Client /127.0.0.1 #391/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
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
Oct 25, 2026 11:20:21 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:59831
Oct 25, 2026 11:20:22 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/furniture.evolution\transcript.jsonl`

## Test definition

```js
id: "furniture.evolution"
title: "evolution stages apply lazily on chunk-load replay from absolute placement timestamps: a replay before 180s keeps the cluster, a replay in the [180s, 330s) window applies exactly world:sprout (the furthest DUE step — bloom at 330s is listed on the same definition but is not yet eligible), and a replay past 330s applies world:bloom — all in place (root uuids and member records unchanged, the deposited diamond rides along, the menu title tracks the current stage) — and removal dissolves the evolved cluster"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["content/src/main/java/dev/materione/content/furniture/**","plugin/src/main/java/dev/materione/plugin/runtime/furniture/**","core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java","fixtures/packs/world/furnitures/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain

      // --- scene: two seedlings of the same chain, 4 blocks apart on one
      // column so every chunk cycle serves both: A at [1,-60,7] (clicked
      // from [1,-60,5]), B at [1,-60,11] (clicked from [1,-60,9]). Stage
      // clocks run from PLACEMENT (absolute) and a replay only happens on
      // a chunk reload. A cycle is deterministic: 120s away (Paper holds
      // player chunks ~60s after the last viewer, so the column surely
      // unloads) + tp back = the replay lands ~125s after the cycle
      // starts. The schedule pins three windows: cycle 1 starts ~40s
      // (replay ~165 < 180 → nothing due), cycle 2 starts ~165 (replay
      // ~290: sprout due, bloom not yet), cycle 3 starts ~330 (replay
      // ~455: bloom).
      const t0 = Date.now();
      const elapsed = () => (Date.now() - t0) / 1000;
      const idleUntil = async (seconds) => {
        while (elapsed() < seconds) await ctx.sleep(2_000);
      };
      const anchorA = [1, -60, 7];
      const anchorB = [1, -60, 11];
      for (const anchor of [anchorA, anchorB]) {
        await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
        await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      }
      await ctx.rcon(`materione dev place world:seedling ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:seedling world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev place world:seedling ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:seedling world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });

      // roots and their member records — the in-place claim compares both
      const inspectRoot = async (anchor) => {
        await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
        const line = await ctx.waitForServerLog(
          /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=\S+ .+ root=[0-9a-f-]{36} /,
          { timeoutMs: 20_000 });
        return /root=([0-9a-f-]{36}) /.exec(line)[1];
      };
      const rootA = await inspectRoot(anchorA);
      const rootB = await inspectRoot(anchorB);
      const idOf = (root) => ctx.rcon(
        `data get entity ${root} BukkitValues."materione:furniture-id"`);
      const membersOf = (root) => ctx.rcon(
        `data get entity ${root} BukkitValues."materione:members"`);
      ctx.expect(await idOf(rootA)).toContain('world:seedling');
      ctx.expect(await idOf(rootB)).toContain('world:seedling');
      const membersA = await membersOf(rootA);

      // the deposited diamond must ride every later transform
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
      const screenTitle = () => ctx.exec(
        "return mc.screen == null ? 'none' : mc.screen.getTitle().getString()");
      const openCluster = async (anchor, wantTitle, tries = 3) => {
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
              const title = String(await screenTitle());
              if (title === wantTitle) return;
              ctx.expect(title).toBe(wantTitle); // opened the WRONG stage
            }
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe(`menu open with title ${wantTitle}`);
      };
      const closeMenu = async () => {
        await ctx.exec('player.closeContainer()\nreturn \'closed\'');
        await waitForMenu('closed');
        await ctx.sleep(700); // MONITOR close listener writes the grid back
      };

      await ctx.rcon('tp Probe 1 -60 5');
      await ctx.rcon('give Probe minecraft:diamond 1'); // hotbar 0 → menu 36
      await openCluster(anchorA, 'Seedling');
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(45);
      await ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, 36, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'clicked'`);
      await waitForSlot(0, 'Diamond');
      await closeMenu();

      // one chunk cycle: walk far enough that the origin column unloads
      // (Paper holds player chunks ~60s after the last viewer — the fixed
      // 120s sleep removes the latency variance), return, wait for the
      // replay — EntityAddToWorld re-registers the root and applies the
      // due stages ~125s after the cycle started
      const replayCycle = async () => {
        const rootPresent = () => ctx.rcon(
          `execute if entity @e[type=item_display,x=${anchorA[0] + 0.5},`
          + `y=${anchorA[1] + 0.5},z=${anchorA[2] + 0.5},distance=..3]`);
        await ctx.rcon('tp Probe 400 -60 400');
        await ctx.sleep(120_000); // Paper's ~60s hold on player-less chunks
        const unloadDeadline = Date.now() + 60_000; // insurance if it grows
        while (Date.now() < unloadDeadline) {
          if ((await rootPresent()) === 'Test failed') break;
          await ctx.sleep(5_000);
        }
        await ctx.rcon('tp Probe 1 -60 5');
        const backDeadline = Date.now() + 30_000;
        for (;;) {
          if ((await rootPresent()) === 'Test passed. Count: 1') break;
          if (Date.now() > backDeadline) {
            ctx.expect(await rootPresent()).toBe('Test passed. Count: 1');
          }
          await ctx.sleep(1_000);
        }
        await ctx.sleep(2_000); // replayLater runs a tick after the load
      };

      // --- cycle 1 (replay ~165s elapsed): before the 180s stage — nothing due
      await idleUntil(40);
      await replayCycle();
      ctx.expect(await idOf(rootA)).toContain('world:seedling');
      ctx.expect(await idOf(rootB)).toContain('world:seedling');

      // --- cycle 2 (replay ~290s elapsed): on the seedling list only
      // sprout is due — the furthest DUE step applies, not the last listed
      ctx.expect(await idOf(rootA)).toContain('world:seedling');
      await replayCycle();
      ctx.expect(await idOf(rootA)).toContain('world:sprout');
      ctx.expect(await idOf(rootB)).toContain('world:sprout');

      // --- cycle 3 (replay ~455s elapsed): past 330s → bloom on both
      await idleUntil(330);
      await replayCycle();
      ctx.expect(await idOf(rootA)).toContain('world:bloom');
      ctx.expect(await idOf(rootB)).toContain('world:bloom');
      // the transform kept the root entity, its member record and the
      // stored grid; only the visual (item_model) and id changed
      ctx.expect(await membersOf(rootA)).toBe(membersA);
      ctx.expect(await ctx.rcon(
        `data get entity ${rootA} item.components."minecraft:item_model"`))
        .toContain('world:bloom_model');
      const clientEntity = (uuid) => ctx.exec(`def e = mc.level.getEntity(java.util.UUID.fromString('${uuid}'))
return e == null ? 'null' : e.getType().toString() + '@' + e.getBlockX() + ',' + e.getBlockY() + ',' + e.getBlockZ()`, 15_000);
      ctx.expect(await clientEntity(rootA)).toBe(
        `entity.minecraft.item_display@${anchorA[0]},${anchorA[1]},${anchorA[2]}`);

      // --- the evolved cluster still serves its storage, titled by the
      // current stage, with the deposited diamond intact
      await ctx.rcon('tp Probe 1 -60 5');
      await openCluster(anchorA, 'Bloom');
      await waitForSlot(0, 'Diamond');
      await closeMenu();

      // --- teardown: the evolved cluster dissolves like any other
      await ctx.rcon(`materione dev remove ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev remove ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon('kill @e[type=item]'); // the spills + placement-item drops
      await ctx.rcon('clear Probe');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: furniture.evolution @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
