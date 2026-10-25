# [qa:furniture] seats (single + multi) on a storage-free cluster: a client right-click seats the player on the FIRST recorded seat armor stand (client vehicle type and uuid match the record), dismounting frees it, an occupied first seat is skipped in favor of the second, and dismounting leaves the player free again

**Outcome:** FAIL — expected output to contain armor_stand, got none [toContain] expected=armor_stand actual=none
**Test:** `furniture.seats` (auto) · **Suite:** `qa/catalog/furniture.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run furniture.seats`

## What was expected

- operator: `toContain`
- expected: `armor_stand`
- actual: `none`

Error:

```
Error: expected output to contain armor_stand, got none
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toContain (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:771:42)
    at sit (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/furniture.mjs:655:37)
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/furniture.mjs:660:21)
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
[11:19:15] [RCON Client /127.0.0.1 #314/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:15] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:15] [RCON Client /127.0.0.1 #315/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:15] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:15] [RCON Client /127.0.0.1 #316/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:15] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:15] [RCON Client /127.0.0.1 #317/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:16] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:16] [RCON Client /127.0.0.1 #318/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:16] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:16] [RCON Client /127.0.0.1 #319/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:16] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:17] [RCON Client /127.0.0.1 #320/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:17] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:17] [RCON Client /127.0.0.1 #321/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:27] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:27] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -60 7
[11:19:28] [RCON Client /127.0.0.1 #322/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:28] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:28] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -59 7
[11:19:28] [RCON Client /127.0.0.1 #323/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:28] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:28] [RCON Client /127.0.0.1 #324/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:28] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:28] [RCON Client /127.0.0.1 #325/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:28] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:28] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:bench world 1 -60 7
[11:19:29] [RCON Client /127.0.0.1 #326/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:29] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:29] [Server thread/INFO]: [Materione] MATERIONE-DEV-INSPECT found=false none material=barrier rawSlot=- world 1 -60 7
[11:19:29] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:bench display=1.5,-59.5,7.5 root=cb291544-8dec-4682-87c7-3eaf272ef923 world 1 -60 7
[11:19:29] [RCON Client /127.0.0.1 #327/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:29] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:29] [RCON Client /127.0.0.1 #328/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:29] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:29] [RCON Client /127.0.0.1 #329/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:19:34] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:19:34] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:19:34] [RCON Client /127.0.0.1 #330/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:52622
Oct 25, 2026 11:19:32 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:32 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:59908
Oct 25, 2026 11:19:33 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:33 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:59042
Oct 25, 2026 11:19:33 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:19:34 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:59045
Oct 25, 2026 11:19:34 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/furniture.seats\transcript.jsonl`

## Test definition

```js
id: "furniture.seats"
title: "seats (single + multi) on a storage-free cluster: a client right-click seats the player on the FIRST recorded seat armor stand (client vehicle type and uuid match the record), dismounting frees it, an occupied first seat is skipped in favor of the second, and dismounting leaves the player free again"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["content/src/main/java/dev/materione/content/furniture/**","plugin/src/main/java/dev/materione/plugin/runtime/furniture/**","core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java","fixtures/packs/world/furnitures/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: the two-seat bench at [1,-60,7]; seat A (first in the
      // record) at (1.2,-59.7,7.5), seat B at (1.8,-59.7,7.5). The click
      // goes through the barrier cell's NORTH face at pitch 22 — the
      // interaction hitbox above the cell eats every shallower ray (the
      // same occlusion the light cell produces on the workbench), and
      // onCellInteract routes on any face of the tagged cell.
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${light.join(' ')} minecraft:air`);
      await ctx.rcon(`materione dev place world:bench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:bench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });

      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:bench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];
      // two seat armor stands, in record order (A first, B second)
      const seatsReply = await ctx.rcon(
        `data get entity ${root} BukkitValues."materione:seats"`);
      const seats = seatsReply.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? [];
      ctx.expect(seats.length).toBe(2);
      const [seatA, seatB] = seats;

      // the player's vehicle: 'none' or '<type> <uuid>'
      const vehicle = () => ctx.exec(`def v = player.getVehicle()
return v == null ? 'none' : v.getType().toString() + ' ' + v.getUUID().toString()`, 15_000);

      // click the barrier's north face — the sit runs on the region thread
      const sit = async (tries = 3) => {
        for (let i = 0; i < tries; i++) {
          await ctx.exec('player.setRot(0F, 22F)\nThread.sleep(500)\ndef hit = mc.hitResult\nreturn hit.getType().toString()');
          const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${anchor[0]} || hit.getBlockPos().getY() != ${anchor[1]} || hit.getBlockPos().getZ() != ${anchor[2]} || hit.getDirection().toString() != 'north') return 'AIM-DRIFT'
def result = mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'use:' + result.toString()`, 15_000);
          if (String(reply).startsWith('use:')) {
            // the seat mount lands a beat later (region-thread teleport +
            // passenger sync)
            const deadline = Date.now() + 8000;
            for (;;) {
              const v = String(await vehicle());
              if (v !== 'none') return v;
              if (Date.now() > deadline) break;
              await ctx.sleep(400);
            }
          }
          await ctx.sleep(700);
        }
        ctx.expect(await vehicle()).toContain('armor_stand');
      };

      // --- sit #1: the first recorded seat
      await ctx.rcon('tp Probe 1 -60 3');
      const first = await sit();
      ctx.expect(first).toBe(`entity.minecraft.armor_stand ${seatA}`);

      // dismount frees the seat
      await ctx.rcon('ride Probe dismount');
      await ctx.sleep(800);
      ctx.expect(await vehicle()).toBe('none');

      // --- sit #2: an occupied first seat is skipped — a NoAI villager
      // holds seat A ("Villager started riding Armor Stand"), so the same
      // click lands the player on seat B
      await ctx.rcon('summon minecraft:villager 1.2 -58.5 7.5 {NoAI:1b,Invulnerable:1b}');
      await ctx.rcon(
        'ride @e[type=villager,distance=..2,x=1.2,y=-59.7,z=7.5,limit=1] mount '
        + '@e[type=armor_stand,distance=..1,x=1.2,y=-59.7,z=7.5,limit=1]');
      await ctx.rcon('tp Probe 1 -60 3');
      const second = await sit();
      ctx.expect(second).toBe(`entity.minecraft.armor_stand ${seatB}`);

      // --- teardown: dismount, clear the dummy, dissolve the cluster
      await ctx.rcon('ride Probe dismount');
      await ctx.sleep(800);
      ctx.expect(await vehicle()).toBe('none');
      await ctx.rcon('kill @e[type=villager]');
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon('kill @e[type=item]');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: furniture.seats @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
