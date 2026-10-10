# [qa:mechanics] storm_wand left-click runs console + player commands on every swing; the behavior rule is suppressed inside its cooldown window

**Outcome:** FAIL — expected 1, got 0 [toBe] expected=1 actual=0
**Test:** `mechanics.commands-and-cooldown` (auto) · **Suite:** `qa/catalog/mechanics.mjs`
**Recorded at:** 6262d533553a7e345c02a4d17b85a19203053a43
**Repro:** `node qa/runner.mjs run mechanics.commands-and-cooldown`

## What was expected

- operator: `toBe`
- expected: `1`
- actual: `0`

Error:

```
Error: expected 1, got 0
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toBe (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:765:32)
    at waitForCount (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/mechanics.mjs:49:33)
    at process.processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/mechanics.mjs:61:7)
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
[19:13:17] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:17] [RCON Client /127.0.0.1 #15/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:17] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:17] [RCON Client /127.0.0.1 #16/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:17] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:17] [RCON Client /127.0.0.1 #17/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:17] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:18] [RCON Client /127.0.0.1 #18/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:18] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:18] [RCON Client /127.0.0.1 #19/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:18] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:19] [RCON Client /127.0.0.1 #20/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:19] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:19] [RCON Client /127.0.0.1 #21/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:19] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:19] [RCON Client /127.0.0.1 #22/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:20] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:20] [Server thread/INFO]: [Materione] MATERIONE-GIVE-OK id=world:storm_wand player=Probe amount=1
[19:13:20] [Server thread/INFO]: Probe has made the advancement [Into Fire]
[19:13:20] [RCON Client /127.0.0.1 #23/INFO]: Thread RCON Client /127.0.0.1 shutting down
[19:13:22] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[19:13:25] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[19:13:25] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[19:13:25] [RCON Client /127.0.0.1 #24/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
INFO: [DebugBridge] Client connected: /127.0.0.1:53454
Oct 10, 2026 7:13:24 P.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 10, 2026 7:13:25 P.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:53456
Oct 10, 2026 7:13:25 P.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 10, 2026 7:13:25 P.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:53458
Oct 10, 2026 7:13:25 P.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 10, 2026 7:13:25 P.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:53460
Oct 10, 2026 7:13:25 P.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
```

Full call transcript: `qa/evidence/run-2026-10-10T23-12-41/mechanics.commands-and-cooldown\transcript.jsonl`

## Test definition

```js
id: "mechanics.commands-and-cooldown"
title: "storm_wand left-click runs console + player commands on every swing; the behavior rule is suppressed inside its cooldown window"
mode: "auto"
dependsOn: ["commands.give-basic"]
covers: ["plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**","plugin/src/main/java/dev/materione/plugin/runtime/behavior/**","behavior/src/main/java/dev/materione/behavior/**","fixtures/packs/world/**"]

steps:
async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give world:storm_wand Probe');
      await ctx.waitForChat(/Given world:storm_wand/);
      await ctx.exec('player.getInventory().selected = 0');
      const held = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(held)).toBe('Storm Wand');

      // A client-side swing sends the swing packet the server turns into
      // LEFT_CLICK_AIR — no input injection needed. One swing drives three
      // surfaces: the console tellraw (commands mechanic, %player%
      // substituted), the player-executed version command (as_player — the
      // MATERIONE-VERSION log marker proves the dispatch), and the behavior
      // rule whose message is gated by its 3s cooldown.
      const counts = async () => {
        const lines = await ctx.chat(60);
        return {
          sparks: lines.filter((l) => l.includes('The wand sparks.')).length,
          crackles: lines.filter((l) => l.includes('The wand crackles.')).length,
        };
      };
      const waitForCount = async (name, want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const c = await counts();
          if (c[name] >= want) {
            return c;
          }
          if (Date.now() > deadline) {
            // assertion failure carries both counters into the dossier
            ctx.expect(c[name]).toBe(want);
          }
          await ctx.sleep(300);
        }
      };

      const baseline = await counts();

      // swing 1: cooldown fresh — every surface fires
      await ctx.exec('player.swing(player.getUsedItemHand())');
      await waitForCount('sparks', baseline.sparks + 1);
      await ctx.waitForServerLog(/MATERIONE-VERSION/);
      await waitForCount('crackles', baseline.crackles + 1, 3000);

      // swing 2 immediately: the mechanics fire again, the rule is suppressed
      await ctx.exec('player.swing(player.getUsedItemHand())');
      await waitForCount('sparks', baseline.sparks + 2);
      const suppressed = await counts();
      ctx.expect(suppressed.crackles - baseline.crackles).toBe(1);

      // after the 3s window the rule fires again
      await ctx.sleep(3300);
      await ctx.exec('player.swing(player.getUsedItemHand())');
      await waitForCount('sparks', baseline.sparks + 3);
      await waitForCount('crackles', baseline.crackles + 2, 3000);
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: mechanics.commands-and-cooldown @ 6262d533553a7e345c02a4d17b85a19203053a43 -->
