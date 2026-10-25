# [qa:furniture] furniture placement is limited to floor clicks: a workbench placement item right-clicked on a wall face places and consumes nothing, a floor click places the cluster (barrier + light cell) and consumes exactly one item, and the space pre-check denies an occupied anchor at the operator seam (dev place answers no-space, cluster root unchanged) — the deny is unreachable from real-client clicks because the cluster light cell caps the aim column

**Outcome:** FAIL — expected use-clicked on [1,-61,7] up, got {"type":"BLOCK","x":1,"y":-60,"z":6,"dir":"north"} [toBe] expected=use-clicked on [1,-61,7] up actual={"type":"BLOCK","x":1,"y":-60,"z":6,"dir":"north"}
**Test:** `furniture.limited-placing` (auto) · **Suite:** `qa/catalog/furniture.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run furniture.limited-placing`

## What was expected

- operator: `toBe`
- expected: `use-clicked on [1,-61,7] up`
- actual: `{"type":"BLOCK","x":1,"y":-60,"z":6,"dir":"north"}`

Error:

```
Error: expected use-clicked on [1,-61,7] up, got {"type":"BLOCK","x":1,"y":-60,"z":6,"dir":"north"}
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toBe (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:765:32)
    at clickFace (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/furniture.mjs:335:42)
    at async Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/furniture.mjs:349:7)
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
[11:17:52] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:53] [RCON Client /127.0.0.1 #255/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:53] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:53] [RCON Client /127.0.0.1 #256/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:53] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:53] [RCON Client /127.0.0.1 #257/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:53] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:54] [RCON Client /127.0.0.1 #258/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:54] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:54] [RCON Client /127.0.0.1 #259/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:54] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:54] [RCON Client /127.0.0.1 #260/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:54] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:55] [RCON Client /127.0.0.1 #261/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:05] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:05] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -60 7
[11:18:05] [RCON Client /127.0.0.1 #262/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:05] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:05] [Server thread/INFO]: [Materione] MATERIONE-DEV-FURNITURE-REMOVE ok=false not-furniture world 1 -59 7
[11:18:05] [RCON Client /127.0.0.1 #263/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:05] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:06] [RCON Client /127.0.0.1 #264/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:06] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:06] [RCON Client /127.0.0.1 #265/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:06] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:06] [RCON Client /127.0.0.1 #266/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:06] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:07] [RCON Client /127.0.0.1 #267/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:07] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:07] [RCON Client /127.0.0.1 #268/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:07] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:07] [Server thread/INFO]: [Materione] MATERIONE-GIVE-OK id=world:workbench player=Probe amount=1
[11:18:07] [RCON Client /127.0.0.1 #269/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:07] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:07] [RCON Client /127.0.0.1 #270/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:09] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:09] [RCON Client /127.0.0.1 #271/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:18:21] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:18:21] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:18:22] [RCON Client /127.0.0.1 #272/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:50064
Oct 25, 2026 11:18:16 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:18:16 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:65328
Oct 25, 2026 11:18:17 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:18:18 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:52062
Oct 25, 2026 11:18:19 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:18:20 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:63977
Oct 25, 2026 11:18:21 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/furniture.limited-placing\transcript.jsonl`

## Test definition

```js
id: "furniture.limited-placing"
title: "furniture placement is limited to floor clicks: a workbench placement item right-clicked on a wall face places and consumes nothing, a floor click places the cluster (barrier + light cell) and consumes exactly one item, and the space pre-check denies an occupied anchor at the operator seam (dev place answers no-space, cluster root unchanged) — the deny is unreachable from real-client clicks because the cluster light cell caps the aim column"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["content/src/main/java/dev/materione/content/furniture/**","plugin/src/main/java/dev/materione/plugin/runtime/furniture/**","core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java","fixtures/packs/world/furnitures/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      const parseHit = (s) => {
        const m = /^(\w+) (-?\d+),(-?\d+),(-?\d+) (\w+)$/.exec(String(s));
        if (!m) return null;
        return { type: m[1], x: Number(m[2]), y: Number(m[3]), z: Number(m[4]), dir: m[5] };
      };
      const onTarget = (hit, cell) => hit && hit.type === 'BLOCK'
        && hit.x === cell[0] && hit.y === cell[1] && hit.z === cell[2];

      // --- scene: the floor cell [1,-61,7] (solid ground, top face at y=-60)
      // carries the allowed placement; the stone bump at [0,-60,5] offers the
      // denied wall face (its north side). The player stands one block east
      // (1,-60,3): the x=1.5 sight line to the floor clears the bump, while
      // the bump's north face stays clickable with a slight west yaw.
      const ground = [1, -61, 7];
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      const bump = [0, -60, 5];
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      for (const cell of [anchor, light, bump]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }
      await ctx.rcon(`setblock ${bump.join(' ')} minecraft:stone`);
      ctx.expect(await ctx.rcon(`execute if block ${ground.join(' ')} minecraft:air`))
        .toBe('Test failed'); // the ground is solid

      // the synthetic furniture placement item: paper material, the §13 seam
      await ctx.rcon('materione give world:workbench Probe');
      await ctx.waitForChat(/Given world:workbench/);
      await ctx.rcon('tp Probe 1 -60 3');
      await ctx.exec('player.getInventory().selected = 0');

      const stackCount = async () => Number(await ctx.exec(`def n = 0
for (int i = 0; i < 36; i++) {
  if (!player.getInventory().items.get(i).isEmpty()) n = n + 1
}
return n`));
      const aim = (yaw, pitch) => ctx.exec(`player.setRot(${yaw}F, ${pitch}F)
Thread.sleep(500)
def hit = mc.hitResult
def t = hit.getType().toString()
if (t == 'BLOCK') return 'BLOCK ' + hit.getBlockPos().getX() + ',' + hit.getBlockPos().getY() + ',' + hit.getBlockPos().getZ() + ' ' + hit.getDirection().toString()
return t`, 15_000);
      // Aim at `cell`'s face `dir` and send the use — all atomic with re-aim
      // retries. The use goes through gameMode.useItemOn (a real
      // ServerboundUseItemOnPacket built from the verified hitResult):
      // keyUse.click is equally real but builds the packet from the crosshair
      // at frame-processing time, so a desktop nudge between the readback and
      // the packet redirects or drops the click (observed once as a silently
      // vanished floor placement).
      const clickFace = async (cell, dir, yaw, pitch, tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          const hit = parseHit(await aim(yaw, pitch));
          last = hit;
          if (hit && hit.type === 'BLOCK' && hit.dir === dir && onTarget(hit, cell)) {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${cell[0]} || hit.getBlockPos().getY() != ${cell[1]} || hit.getBlockPos().getZ() != ${cell[2]} || hit.getDirection().toString() != '${dir}') return 'AIM-DRIFT'
def result = mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'use:' + result.toString()`, 15_000);
            if (String(reply).startsWith('use:')) return;
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(JSON.stringify(last)).toBe(`use-clicked on ${JSON.stringify(cell)} ${dir}`);
      };

      // --- scene A: a wall face is denied — nothing places, nothing consumes.
      // Yaw 30 / pitch 33 puts the crosshair on the bump's north face (yaw 18
      // grazed the east face corner); the placement handler returns before
      // any placement on a non-UP face and the paper item has no vanilla
      // use. The vanilla anchor for such a click would be [0,-60,4] — it
      // must stay air.
      await clickFace(bump, 'north', 30, 33);
      ctx.expect(await ctx.rcon('execute if block 0 -60 4 minecraft:air')).toBe('Test passed');
      ctx.expect(await stackCount()).toBe(1);

      // --- scene B: the floor is allowed — cluster spawns, one item consumed
      await clickFace(ground, 'up', 0, 22);
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${light.join(' ')} minecraft:light[level=14]`))
        .toBe('Test passed');
      ctx.expect(await stackCount()).toBe(0); // the paper was consumed
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];

      // --- scene C: the space pre-check denies an occupied anchor. The deny
      // is only reachable at the operator seam: the cluster's own light cell
      // (non-targetable) caps the column above the barrier, so a real client
      // can never aim an UP-face click whose candidate anchor is claimed —
      // the crosshair either misses entirely (shallow) or lands on distant
      // ground (steep), both verified in the aim sweep. The dev driver is
      // the documented harness path for this seam.
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=false no-space world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 20_000 });
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect2 = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      ctx.expect(/root=([0-9a-f-]{36}) /.exec(inspect2)[1]).toBe(root);

      // --- teardown
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`setblock ${bump.join(' ')} minecraft:air`);
      await ctx.rcon('clear Probe');
      await ctx.rcon('kill @e[type=item]');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: furniture.limited-placing @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
