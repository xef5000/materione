# [qa:editor] /materione editor local hands out a loopback capability URL (always-available direct mode)

**Outcome:** FAIL — expected truthy, got false [toBeTruthy] expected=true actual=false
**Test:** `editor.local-url` (auto) · **Suite:** `qa/catalog/editor.mjs`
**Recorded at:** 5a0848e9b833da0b71b8f5751b56749ed6876eb4
**Repro:** `node qa/runner.mjs run editor.local-url`

## What was expected

- operator: `toBeTruthy`
- expected: `true`
- actual: `false`

Error:

```
Error: expected truthy, got false
    at fail (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:761:11)
    at Object.toBeTruthy (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/runner.mjs:784:20)
    at Object.steps (file:///C:/Users/romde/Documents/dev/minecraft/materione/qa/catalog/editor.mjs:23:66)
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
2] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:13] [RCON Client /127.0.0.1 #207/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:13] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:13] [RCON Client /127.0.0.1 #208/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:13] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:13] [RCON Client /127.0.0.1 #209/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:13] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:13] [RCON Client /127.0.0.1 #210/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:14] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:14] [Server thread/INFO]: [Materione] MTR-0031: editor session [REDACTED] created; awaiting browser
[11:17:14] [Server thread/INFO]: [Materione] MATERIONE-EDITOR http://127.0.0.1:8092/?s=[REDACTED]#[REDACTED]
[11:17:14] [Server thread/INFO]: Editor session ready (local) — open:
[11:17:14] [Server thread/INFO]: http://127.0.0.1:8092/?s=[REDACTED]#[REDACTED]
[11:17:14] [Server thread/INFO]: The part after # is secret and stays in your browser. Approve the connection in game within 120s.
[11:17:14] [RCON Client /127.0.0.1 #211/INFO]: Thread RCON Client /127.0.0.1 shutting down
[11:17:14] [RCON Listener #1/INFO]: Thread RCON Client /127.0.0.1 started
[11:17:14] [Server thread/INFO]: [Materione] MATERIONE-VERSION 0.1.0-SNAPSHOT
[11:17:14] [RCON Client /127.0.0.1 #212/INFO]: Thread RCON Client /127.0.0.1 shutting down
```

Client log tail (harness\build\probe\client\client.log):

```
Oct 25, 2026 11:17:07 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:63193
Oct 25, 2026 11:17:07 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
Oct 25, 2026 11:17:07 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:63195
Oct 25, 2026 11:17:07 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
[11:17:07] [Render thread/INFO]: [System] [CHAT] Given world:feast_bread ×1
[11:17:09] [Render thread/INFO]: [System] [CHAT] Given sample:ruby_disc ×1
Oct 25, 2026 11:17:10 A.M. com.debugbridge.core.server.BridgeServer onOpen
INFO: [DebugBridge] Client connected: /127.0.0.1:57641
Oct 25, 2026 11:17:10 A.M. com.debugbridge.core.server.BridgeServer onClose
INFO: [DebugBridge] Client disconnected: 
[11:17:12] [Render thread/INFO]: [System] [CHAT] Given sample:ruby_sword ×1
```

Full call transcript: `qa/evidence/run-2026-10-25T15-13-52/editor.local-url\transcript.jsonl`

## Test definition

```js
id: "editor.local-url"
title: "/materione editor local hands out a loopback capability URL (always-available direct mode)"
mode: "auto"
dependsOn: []
covers: ["plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java","plugin/src/main/java/dev/materione/plugin/command/EditorCommands.java"]

steps:
async (ctx) => {
      const reply = await ctx.rcon('materione editor local');
      // Boolean form on purpose: the reply embeds the capability URL and its
      // secret fragment — assert the shape, never quote the value into a
      // dossier or the ledger.
      ctx.expect(reply.includes('Editor session ready (local)')).toBeTruthy();
      await ctx.waitForServerLog(/MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/);
    }
```

## Environment

- probe pins: Paper 26.1.2-74, DebugBridge sha1 7723f7195d…, fabric-loader 0.19.5
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: editor.local-url @ 5a0848e9b833da0b71b8f5751b56749ed6876eb4 -->
