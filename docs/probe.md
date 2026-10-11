# The agent probe loop

A local, agent-driven debug loop: boot a **Paper 26.x probe server** and a
**real windowed Minecraft client** joined to it, stage a scene through the
server console, and capture the client's framebuffer so an agent session can
*see* the feature it is debugging. Born from issue #34: unit/golden/harness
tiers prove logic, but glyph tofu, checkerboarded models, and invisible
paintings only ever showed in a real client — until now, that real client was
a human.

The stack rides the **26.x seam** (`paper-probe-*` pins in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) — the same Paper
build the scratch server runs) because the client-side bridge mod has no
1.21.4 build. It is a **dev-machine tool, not CI**: CI keeps guarding the
1.21.4 floor via the [`:harness`](../harness/README.md) integration tests.

## What it is

`scripts/probe/probe.mjs` — one zero-dependency Node script (Node ≥ 22):

| Layer | Mechanism |
|---|---|
| Server | Pinned Paper 26.1.2 (sha256-verified), offline mode, fresh superflat world per boot, **RCON** for the console |
| Determinism | Peaceful, fixed seed, `spawn_*`/`advance_*` gamerules off, `random_tick_speed 0`, frozen noon — flat plain, no mobs, identical lighting every run |
| Client | Real Minecraft 26.1.2 (reuses the local vanilla launcher install: jar, libraries, assets), fabric-loader via [DebugBridge](https://github.com/use-ai-for-mc/debugbridge) (MIT, sha1-pinned), windowed so the GPU renders for real |
| Server control | `probe rcon <cmd>` — the same console a human types into |
| Client control | DebugBridge localhost WebSocket (9876): `joinServer` (pre-accepts the resource pack), `screenshot`, `screenInspect`, `chatHistory`, `snapshot`, Groovy `execute` as the escape hatch |
| Vision | `probe shot` writes a JPEG; the agent `Read`s it |

Materione-side, nothing changes: the plugin jar is the normal `:plugin:shadowJar`,
content is the normal fixture corpus (`fixtures/packs`), delivery is the normal
join-time resource-pack send, and the boot markers are the same ones
[`harness/README.md`](../harness/README.md) documents — including
`MATERIONE-REGISTRY-OK mode=registry`, making this the first automated exercise
of the `:modern` registry seam.

## Prerequisites (one-time)

- **JDK ≥ 25** anywhere on the machine (`~/.jdks/*` and `~/.gradle/jdks/*` are
  scanned; or `--java`/`MTR_PROBE_JAVA`). Paper 26.x and the 26.x client need it.
- **Node ≥ 22** (built-in WebSocket).
- **A vanilla launcher install of Minecraft 26.1.2** (`%APPDATA%/.minecraft`
  by default; `--vanilla-dir` to point elsewhere). The probe reuses its
  client jar, libraries, and assets — it never touches the launcher install.

## The recipe

```bash
node scripts/probe/probe.mjs boot     # up + client + join + sanity shot
```

or step by step (what `boot` does):

```bash
node scripts/probe/probe.mjs up       # fresh world, plugin jar, gamerules; waits for MATERIONE-BOOT-OK
node scripts/probe/probe.mjs client   # launches the windowed client; waits for the bridge port
node scripts/probe/probe.mjs join     # joins 127.0.0.1:25599, pack pre-accepted, waits in-world
node scripts/probe/probe.mjs shot     # first capture lands in harness/build/probe/shots/
```

Then iterate:

```bash
# stage the scene (server side)
node scripts/probe/probe.mjs rcon "materione give corpus:gem_red Probe"
node scripts/probe/probe.mjs rcon "materione dev place sample:amethyst_lamp 0 -58 0"
node scripts/probe/probe.mjs rcon "tp Probe 5 -59 5"

# or client side, as the player
node scripts/probe/probe.mjs command "/materione browser"

# look
node scripts/probe/probe.mjs shot --out shots/gem-check.jpg   # then Read the file
node scripts/probe/probe.mjs state       # position/health/dimension
node scripts/probe/probe.mjs screen      # what GUI is open + container slots
node scripts/probe/probe.mjs chat        # recent client-side chat (action bars, toasts echo here)

# arbitrary code inside the client JVM (Groovy; mc/player/level pre-bound)
node scripts/probe/probe.mjs exec "return player.blockPosition().toShortString()"

# done
node scripts/probe/probe.mjs quit && node scripts/probe/probe.mjs down
```

The client window pops up on the desktop while it runs — that is expected
(real rendering is the point). The window can be covered or minimized-adjacent;
avoid *minimizing* it (the framebuffer stops updating).

## Agent mode (`probe.mjs agent`)

The [QA suite](../qa/README.md) does not shell out per call — it drives a
**long-lived agent process**: `node scripts/probe/probe.mjs agent` speaks
JSONL over stdio (requests `{id, cmd, args}`, responses
`{id, ok, result|error}`; human progress goes to stderr). One process holds
the whole session state — server boot, client launch, join — so a QA run
boots once, not per test. Commands: `up`, `down`, `client`, `join`, `quit`,
`rcon`, `exec`, `command`, `chat`, `state`, `screen`, `shot`, `status`,
`ping`. Failures are answers (`ok: false`), not crashes: the agent survives a
broken environment and lets the runner decide what that means.

## When to use it

- An issue says "the glyph renders as tofu" / "the item is checkerboarded" /
  "the painting is invisible": reproduce it in the loop, `shot`, fix, `shot`
  again — without asking the reporter for another screenshot.
- Verification before closing any fix whose failure mode is *visual*.
- First automated coverage of the 26.x registry path (`mode=registry`),
  including painting/song registration — previously manual scratch-server work
  only.

When *not*: logic, wire formats, persistence, and pack bytes are covered by
unit/golden/`:harness` tiers — don't boot the probe for what the harness
already asserts.

## Notes & gotchas

- **Fresh world per `up`** — no leftovers from last session (pass
  `--keep-world` to debug across restarts).
- Everything lives under `harness/build/probe/` (a build dir): a killed
  process or a full-disk purge can never dirty the repo.
- DebugBridge gates `joinServer`/`quit`/`runCommand` behind
  `session_control_enabled`/`run_command_enabled` in the client's
  `config/debugbridge.json`; the script writes that config (loopback-only
  bridge, its security model is documented in the mod's README).
- DebugBridge's manifest pins `minecraft = 26.1` exactly; the script relaxes
  that to `>=26.1` via fabric-loader's dependency-override file
  (`config/fabric_loader_dependencies.json`) so the pinned jar stays
  byte-identical while running on 26.1.2.
- fabric-loader does no native handling — the script extracts the
  `natives-windows` jars and sets `-Dorg.lwjgl.librarypath` (what the vanilla
  launcher does silently).
- Screenshots are JPEG (bridge format), sized from the window
  (`--width`/`--height` at launch, default 1280×720). For pixel-exact PNG use
  Groovy: `exec "return net.minecraft.client.ScreenshotRecorder.takeScreenshot(mc).asPNGFile(mc, 'probe-shot')"`
  (write to the game dir, then copy).
- 26.x renamed the gamerules (1.21.11 registry rework): `advance_time`,
  `advance_weather`, `spawn_mobs`, `random_tick_speed`, … `doFireTick` is gone
  entirely.
- **Groovy exec quirks (26.1.2)**: results come back wrapped
  (`GroovyJavaObject`) — direct method calls and `isEmpty()`/`get(i)` work,
  but Groovy collection methods (`find {}`, `each`, `for (x in list)`) see
  the wrapper, not the elements, and blow up; `instanceof` and `String(...)`
  also fail on wrapped values (use `getClass().getSimpleName()` and
  `.toString()`); fully-qualified class literals resolve via the mapped name
  (`net.minecraft.world.entity.decoration.ArmorStand` works — if the package
  is wrong Groovy reports "No such property: net"). Return strings from the
  script (`.toString()`), never raw enums/objects.
- **Simulating clicks**: `keyMapping.setDown(true)` does nothing — keybinds
  fire on click counts. Use `mc.options.keyAttack.click(mc.options.keyAttack.getDefaultKey())`
  (same shape for `keyUse`). The crosshair decides the target, so summon the
  target with `execute as Probe at @s run summon ... ^ ^ ^2` — carets share
  the player's rotation, guaranteeing alignment. Attacks on armor stands
  never land on 26.1.2 (vanilla-irrelevant quirk); use a `NoAI` villager,
  and space swings past the weapon's full attack cooldown.
- **Crosshair-driven block work**: aim with `player.setRot(yaw, pitch)` (it
  persists between exec calls; positive pitch is down), then verify
  `mc.hitResult` (a `BlockHitResult`: `getType()`/`getBlockPos()`/`getDirection()`)
  and act in the SAME Groovy call — the physical desktop mouse can nudge the
  camera between calls, and survival block reach is 4.5 (a target just past
  it reads as a silent MISS). `setDown(true)` on `keyAttack` does drive
  continuous mining; `mc.gameMode.useItemOn(player, hand, hitResult)` is the
  direct-packet alternative (26.1.2 signature takes a `BlockHitResult`).
  Server-side blockstate asserts go through `execute if block <pos>
  <block>[prop=val,…]` — `data get block` fails on non-block-entities like
  note blocks — and the async region-thread replies of `/materione dev
  inspect` land in the server log (MATERIONE-DEV-INSPECT), not the RCON
  response. `chatHistory` is newest-first: count lines, don't index.
- **Inventory/GUI automation (26.1.2)**: the container-click API renamed —
  drive real clicks with `mc.gameMode.handleContainerInput(menu.containerId,
  slot, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)`
  (the old `handleInventoryMouseClick`/`ClickType` pair is gone; a 2-row menu
  maps container slots 0–17 to the top, 18–44 to the main inventory and
  45–53 to the hotbar). Close with `player.closeContainer()` — that one sends
  the close packet; `clientSideCloseContainer()` does not. The open menu's
  title lives on the screen, not the menu: `mc.screen.getTitle().getString()`.
  Open/closed state reads off the menu id (`player.containerMenu.containerId
  == 0` is closed) — closeContainer() resets `containerMenu` to the player's
  own `inventoryMenu`, so the field is never null and null-checking it never
  sees a close.
- **`data get` truncates long byte arrays**: a full
  `SelectedItem.components."minecraft:custom_data"` dump cuts off partway
  through a byte-array value, so later PDC keys never print — read targeted
  paths instead (`..."minecraft:custom_data".PublicBukkitValues."materione:key"`).
  A missing path answers `Found no elements matching <path>` (echoing the
  path text — don't assert presence by matching the key name in that reply).
- If CI ever needs this, the pattern extends to Linux runners with Xvfb —
  deliberately out of scope for now.

## Bumping the probe pins

Same procedure as the harness pin
([`docs/architecture/build.md`](architecture/build.md) § bump), but 26.x and
with a prefix:

```bash
./gradlew :harness:printLatestPaperPin -PpaperVersion=26.1.2 -PpinPrefix=paper-probe
```

DebugBridge: take the newest `debugbridge-26.1-x.y.z.jar` covering the pinned
MC version from its [releases](https://github.com/use-ai-for-mc/debugbridge/releases)
(or Modrinth), update `probe-debugbridge-url`/`-sha1` in the catalog. The
fabric-loader version (`probe-fabric-loader`) tracks the latest stable from
`meta.fabricmc.net/v2/versions/loader`.
