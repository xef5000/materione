# [qa:mechanics] crucible_blade counts vanilla uses in stack PDC while the vanilla damage bar stays at zero, the wear counter survives materione reload, and the third use breaks the blade

**Outcome:** FAIL — expected 1, got 0 [toBe] expected=1 actual=0
**Test:** `mechanics.custom-durability` (auto) · **Suite:** `qa/catalog/mechanics.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run mechanics.custom-durability`

## What was expected

- operator: `toBe`
- expected: `1`
- actual: `0`

Error:

```
Error: expected 1, got 0
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toBe (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:765:32)
    at waitForWear (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/mechanics.mjs:223:56)
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/mechanics.mjs:255:7)
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
[11:23:57] [RCON Client /127.0.0.1 #557/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:57] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:57] [RCON Client /127.0.0.1 #558/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:57] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:58] [RCON Client /127.0.0.1 #559/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:58] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:58] [RCON Client /127.0.0.1 #560/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:58] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:59] [RCON Client /127.0.0.1 #561/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:59] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:59] [RCON Client /127.0.0.1 #562/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:59] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:59] [RCON Client /127.0.0.1 #563/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:23:59] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:23:59] [RCON Client /127.0.0.1 #564/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:24:00] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:24:00] [RCON Client /127.0.0.1 #565/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:24:00] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:24:00] [RCON Client /127.0.0.1 #566/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:24:00] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:24:00] [Server thread/INFO]: [Materione] MATERIONE-GIVE-OK id=world:crucible_blade player=Probe amount=1
[11:24:01] [RCON Client /127.0.0.1 #567/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:24:01] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:24:01] [RCON Client /127.0.0.1 #568/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:24:09] [Server thread/INFO]: Villager Villager['Villager'/71, uuid='66f5e95b-84b4-4e7c-933b-5403860c6d02', l='ServerLevel[world]', x=0.50, y=-61.53, z=4.79, cpos=[0, 0], tl=151, v=true] died, message: 'Villager suffocated in a wall'
[11:24:15] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:24:15] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:24:15] [RCON Client /127.0.0.1 #569/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:52870
Oct 25, 2026 11:24:14 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:24:14 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:54804
Oct 25, 2026 11:24:14 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:24:15 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:54806
Oct 25, 2026 11:24:15 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:24:15 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:54808
Oct 25, 2026 11:24:15 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/mechanics.custom-durability\transcript.jsonl`

## Test definition

```js
id: "mechanics.custom-durability"
title: "crucible_blade counts vanilla uses in stack PDC while the vanilla damage bar stays at zero, the wear counter survives materione reload, and the third use breaks the blade"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**","plugin/src/main/java/dev/materione/plugin/runtime/behavior/**","plugin/src/main/java/dev/materione/plugin/runtime/ItemAutoUpdater.java","behavior/src/main/java/dev/materione/behavior/**","fixtures/packs/world/**"]

steps:
async (ctx) => {
      await ctx.reset();
      // Vanilla item wear only happens outside creative; clear any targets a
      // previous run left near the anchor before summoning a fresh one.
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('execute as Probe at @s run kill @e[type=villager,distance=..8]');
      await ctx.rcon('materione give world:crucible_blade Probe');
      await ctx.waitForChat(/Given world:crucible_blade/);
      await ctx.exec('player.getInventory().selected = 0');
      const held = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(held)).toBe('Crucible Blade');
      // Baseline floor: an undamaged iron sword — the mechanic must keep it
      // exactly here (event.setCancelled) while its own counter advances.
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getMaxDamage()'))).toBe('250');

      // Melee target: a static NoAI villager two blocks down the player's
      // look vector (armor stands refuse the hit on 26.1.2). The client's
      // crosshair picks it up and a real attack-key click drives the whole
      // vanilla pipeline — ServerboundAttackPacket → player.attack →
      // PlayerItemDamageEvent — exactly what a human click produces.
      await ctx.rcon(
        'execute as Probe at @s run summon minecraft:villager ^ ^ ^2 '
        + '{NoAI:1b,AbsorptionAmount:40f}');
      await ctx.sleep(800); // entity tracker + crosshair pick settle
      const aim = () => ctx.exec("return mc.hitResult.getEntity().getType().toString()");
      ctx.expect(String(await aim())).toBe('entity.minecraft.villager');

      // The wear probe: right-click air fires the click:right rule whose
      // message interpolates the live PDC counters (${item.*} surface).
      const probe = () => ctx.exec(
        'mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND); return "probed"');
      const wearCount = async () =>
        (await ctx.chat(60)).filter((l) => l.includes('blade-wear')).length;
      const baseline = await wearCount();
      const waitForWear = async (want, timeoutMs = 10_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          if ((await wearCount()) >= want) return;
          if (Date.now() > deadline) {
            ctx.expect((await wearCount()) - baseline).toBe(want - baseline);
          }
          await ctx.sleep(300);
        }
      };
      const waitForTwos = async (want, timeoutMs = 10_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const twos = (await ctx.chat(60)).filter((l) => l.includes('blade-wear 2/3')).length;
          if (twos >= want) return;
          if (Date.now() > deadline) ctx.expect(twos).toBe(want);
          await ctx.sleep(300);
        }
      };

      const hit = async () => {
        ctx.expect(String(await aim())).toBe('entity.minecraft.villager');
        // direct attack packet — the keybind click path decays over a long
        // client session (issue #63) and silently stops landing; the packet
        // is the same ServerboundInteractPacket(ATTACK) a charged click
        // produces, without the client-side sub-charge gate
        await ctx.exec(`def target = mc.hitResult.getEntity()
mc.gameMode.attack(player, target)
return 'attacked'`);
        // the server-side attack cooldown still scales damage — spacing
        // keeps it full (the villager's 40f absorption absorbs any hit)
        await ctx.sleep(1700);
      };

      // use 1: the counter appears in PDC (1/3), vanilla bar untouched
      await hit();
      ctx.expect(String(await probe())).toBe('probed');
      await waitForWear(baseline + 1);
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');

      // use 2: counter advances (2/3) — the probe re-reads it from chat
      await hit();
      ctx.expect(String(await probe())).toBe('probed');
      await waitForWear(baseline + 2);

      // reload: the auto-updater refreshes held stacks but must carry the
      // per-instance counters across — a second wear probe still reads 2/3.
      const preTwos = (await ctx.chat(60)).filter((l) => l.includes('blade-wear 2/3')).length;
      await ctx.rcon('materione reload');
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/);
      ctx.expect(String(await probe())).toBe('probed');
      await waitForTwos(preTwos + 1);

      // use 3 hits max: the blade breaks — the slot empties and no third
      // wear value can come (the PDC was cleared with the stack; the three
      // counted lines are 1/3, 2/3, and the post-reload re-read of 2/3)
      await hit();
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().isEmpty()'))).toBe('true');
      ctx.expect((await wearCount()) - baseline).toBe(3);
      ctx.expect(
        (await ctx.chat(60)).filter((l) => l.includes('blade-wear 3/3')).length,
      ).toBe(0);

      await ctx.rcon('execute as Probe at @s run kill @e[type=villager,distance=..8]');
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: mechanics.custom-durability @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
