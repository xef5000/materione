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
  script (`.toString()`), never raw enums/objects. Reflection on a wrapped
  value (`mc.level.getClass()`) reflects the bridge wrapper, not the
  Minecraft object — enumerate the mapped CLASS instead:
  `Class.forName('net.minecraft.client.multiplayer.ClientLevel').getMethods()`.
- **Client light reads (26.1.2)**:
  `mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, new net.minecraft.core.BlockPos(x, y, z))`
  returns the block-light layer the client's light engine received — the
  programmatic proxy for "this light source actually lights the scene".
  `LightLayer` lives in `world.level` on 26.1.2, NOT `world.level.lighting`
  (that package surfaces the "No such property: net" failure). A placed
  `minecraft:light[level=n]` reads back `n` within ~1.5s of the block
  landing.
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
- **Input-path reliability (26.1.2 probe client)**: across a long client
  session, bare-swing packets (`player.swing` → LEFT_CLICK_AIR) and
  attack-key presses on entities stop reaching the server after earlier
  tests in the same session ran block placements/mining — while dig-path
  clicks (attack key on a block → LEFT_CLICK_BLOCK) and right-click uses
  stay reliable. Drive left-click interactions through a momentary ground
  click, and run input-heavy tests early in the session (the runner
  executes tests in catalog order unless selectors say otherwise).
  Crosshair state also leaks across tests: aim atomically before every
  click/hold whose meaning depends on the target.
- **Client-side render truth**: `mc.level.getBlockState(pos).toString()` reads
  the CLIENT's local state (`Block{minecraft:x}[prop=val,…]`) — the server
  never resends a block whose state it did not change, so client/server state
  agreement (asserted separately against the same state key) is the
  programmatic proxy for "the resource pack still renders this block": an
  unmapped state renders the missing model with no server-side trace. State
  keys must match the client's alphabetical property order; the host name and
  bracket content are best asserted as two separate `contains` (`Block{…}`
  breaks a naive `host[props]` substring).
- **Tool-gated hosts out-mine the hold**: hosts like bone block (~10s bare
  hand, ~0.4s with a diamond pickaxe) never finish inside a `mineAway` hold
  bare-handed — the runtime's break pipeline only fires when the vanilla
  break completes, so the block stays and every retry burns its inspect
  timeout. `give Probe` the correct tool (and re-select slot 0) before a
  mining hold on any host whose material names a tool.
- **Direct-packet clicks for placement tests**: `keyUse.click` builds the
  use packet from the crosshair at frame-processing time, so a physical
  desktop nudge between the verified readback and the packet silently
  redirects or drops a placement. When the click must be deterministic,
  send it from the verified hit:
  `mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, mc.hitResult)`
  (a real `ServerboundUseItemOnPacket`; the reply is the client-side
  `InteractionResult`, so `Pass` on a denied placement is expected — assert
  the server-side outcome, not the reply).
- **Non-targetable blocks cap the aim column**: a furniture cluster's
  `minecraft:light` cell (like structure void: no collision, not
  selectable) sits directly above the barrier, so from outside the
  crosshair can never reach the barrier's top face — shallow angles enter
  the light column and read MISS, steep angles cross over onto distant
  ground. The interaction hitbox above a barrier cell occludes the same
  way even without a light. Any cell-with-cell-above geometry hides its
  top face the same way; aim for side faces or drive the seam through
  `dev place`.
- **Seats need a storage-free cluster**: the shared click priority is
  ECA → storage → seat, so a furniture with `storage:` never sits —
  seat coverage needs a storage-free fixture (the corpus's `bench`).
  `ride <target> dismount` / `ride <rider> mount <vehicle>` drive
  dismount/occupancy from the console; the seat's live `Passengers` NBT
  serializes full passenger compounds (truncation-prone) — assert the
  seat outcome via the rider's vehicle, not the NBT.
- **Entity asserts and client entity lookups**: `execute if entity`
  appends the match count ("Test passed. Count: 1" — plain "Test failed"
  when nothing matches), so composition asserts can require exact counts.
  The client-side mirror of a server-known entity is
  `mc.level.getEntity(java.util.UUID.fromString('<uuid>'))` → null or the
  entity (`getType().toString()` is the namespaced form
  `entity.minecraft.item_display`); `getBlockX/Y/Z` floor the position
  (an entity at y=-59.5 reads block y=-60). Server-side entity ids are
  NOT in `data get entity` NBT — address entities by UUID selectors.
- **Movement is hold-state, actions are click-counts**: walking/vehicle
  keys drive through `mc.options.keyUp.setDown(true)` (poll each tick),
  while use/attack fire on `click(key)` — the same setDown on an action
  key does nothing. The server-corrected position needs a settle beat
  after releasing before `player.getZ()` reflects the collision stop
  (a barrier face stops the player at cell-edge minus the 0.3 radius).
  NBT scalars read back suffixed (`1.2f`, `1b`) — assert with
  `toContain`, not equality.
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
  Two different NBT roots carry PDC: **entities** (and chunks) under
  `BukkitValues."materione:key"`, **item components** under
  `...custom_data.PublicBukkitValues."materione:key"` — the split cost a
  QA run to find. Targeting an entity by UUID fails with "No entity was
  found" when its chunk is unloaded (a bare `probe up` with no client keeps
  only the spawn area loaded) — `forceload add <x> <z>` first.
- **Container slot maps shift with menu height**: container slots are
  `0..N-1`, then main inventory `N..N+26`, hotbar `N+27..N+35` (the 2-row
  backpack's 45–53 hotbar is the N=18 case; a 9-slot furniture storage's
  hotbar is 36–44). `EntityType#toString()` is the namespaced id form
  (`entity.minecraft.interaction`), never the enum name — match with
  `contains`, not equality.
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
