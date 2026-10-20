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
  landing. With two sources near each other, the flood dominates: a
  level-15 cell reads 13 on a cell two manhattan steps away, masking that
  cell's own level — pick near-tied levels (e.g. 8 and 7) so each cell
  reads at its own value and a wrong level fails the client read too.
- **Direct entity-interact packet (26.1.2)**: the right-click-on-entity
  equivalent of `useItemOn` is 4-ary —
  `mc.gameMode.interact(player, entity, entityHitResult, hand)` (the
  1.20-style 3-ary overload does not exist). Pass the bridge-wrapped
  `mc.hitResult` uncast: an explicit `(EntityHitResult)` cast throws
  "Cannot cast GroovyJavaObject" — Groovy's runtime dispatch unwraps
  arguments on invocation, it just cannot convert them up front.
- **Simulating clicks**: `keyMapping.setDown(true)` does nothing — keybinds
  fire on click counts. Use `mc.options.keyAttack.click(mc.options.keyAttack.getDefaultKey())`
  (same shape for `keyUse`). The crosshair decides the target, so summon the
  target with `execute as Probe at @s run summon ... ^ ^ ^2` — carets share
  the player's rotation, guaranteeing alignment. Attacks on armor stands
  never land on 26.1.2 (vanilla-irrelevant quirk); use a `NoAI` villager,
  and space swings past the weapon's full attack cooldown.
- **Crosshair-driven block work**: aim with `player.setRot(yaw, pitch)` (it
  persists between exec calls; positive pitch is down), then verify and act
  in the SAME Groovy call — the physical desktop mouse can nudge the
  camera between calls, and survival block reach is 4.5 (a target just past
  it reads as a silent MISS). Verify the aim with a fresh
  `mc.player.pick(20.0D, 1.0F, false)` — the `mc.hitResult` field is a
  render-frame cache and can report a stale MISS even when the crosshair
  genuinely holds the target (`getType()`/`getBlockPos()`/`getDirection()`).
  `setDown(true)` on `keyAttack` does drive
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
- **Client registry reads (26.1.2)**:
  `mc.level.registryAccess().@target.lookupOrThrow(<Registries constant>)`
  resolves a synced registry (`Registries` lives in
  `net.minecraft.core.registries`); the registry ITERATES values (not
  holders) and `get(identifier)` returns `Optional` — 26.x shapes getters as
  Optionals too (`PaintingVariant.author()`/`title()` are
  `Optional<Component>`, `assetId()` is plain). Holder `.tags()` is always
  empty client-side for painting variants (the tag data never syncs) —
  placeable-pool questions are answered behaviorally (vanilla item placement)
  or server-side, not by reading tags on the client. Dev commands travel as
  raw packets (`mc.getConnection().@target.sendCommand(...)`, #96).
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
  45–53 to the hotbar). `QUICK_MOVE` always fills the FIRST empty container
  slot; to reach a specific slot, a `PICKUP` pair moves through the cursor:
  click the source slot (stack → cursor), then the target slot (cursor →
  placed). Close with `player.closeContainer()` — that one sends
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
  hotbar is 36–44; a 54-slot crate's is 81–89). `EntityType#toString()` is
  the namespaced id form
  (`entity.minecraft.interaction`), never the enum name — match with
  `contains`, not equality.
- **Furnace menus put the hotbar at 30+** (3 container + 27 main slots, no
  3×3 grid in front of the inventory — the crafting menu's 37+ is its
  result+grid offset, not a constant). The furnace's data slots
  (`AbstractFurnaceMenu.data`) are a private SUPERCLASS field — reach them
  with `getDeclaredField('data')` on the menu's superclass (the bridge's
  `.@` direct access does not walk superclasses) — and they are STICKY:
  burnTotal/cookTotal keep their last synced values even when nothing
  burns (a never-lit furnace still reports the fuel's 1600), so only
  litTime > 0 proves ignition, and cookProgress resets to 0 the tick a
  smelt completes — completion is proven by the result slot, never the
  progress. Under a loaded runner session the cook can finish well after
  an impatient wall-clock window would give up (the server side is
  provably done — `data get block` shows the output — while the menu view
  still lags): poll with a generous deadline and poke a no-op PICKUP
  click on an empty main-inventory slot each pass — the click makes the
  server re-broadcast the container, so a stale client view cannot
  outlive the poll.
- **Stale recipe fixtures flip recipe matching**: the server-side packs
  dir accumulates every fixture ever synced (sync never prunes), and
  leftover diagnostic recipes keep matching live grids — two rubies also
  matched a stale `[paper, paper]` recipe (the ruby is paper-typed) and
  intermittently won registration order, so a crafting test predicted the
  WRONG result in some runs and passed in others. `probe up` wipes the
  world but NOT `plugins/Materione/content/packs/`: prune it whenever
  fixtures were ever added-and-removed, and treat an unexpected boot-log
  recipe count (`N recipe(s) applied`) as a sync-health check.
- **sounds.json properties live on the entries, and cross-pack pools merge
  by default**: the client reads volume/pitch/weight/stream/preload/
  attenuation_distance off the per-entry objects — event-level `stream` is
  silently dropped (#94). Entries from ALL packs concatenate into the same
  event key; only event-level `replace:true` replaces the pool — without
  it a `replace_vanilla` override just adds a second weighted source (#95).
  Verify pool contents by sampling `getSound(RandomSource)` many times and
  collecting distinct locations.
- **Sound file resource ids carry a `sounds/` prefix**: a sounds.json
  source `sample:ui/level_up` maps to `assets/sample/sounds/ui/level_up.ogg`,
  i.e. resource id `sample:sounds/ui/level_up.ogg` — `getResource` with the
  bare source path misses.
- **Real playback reads**: `SoundEngine.instanceBySource` (private field,
  via `getDeclaredField` on `getSoundManager().@target.soundEngine`) holds
  the active `SoundInstance`s — `inst.getSound().getLocation()` names the
  playing source. Stops can RACE queued instances (the sound starts after
  the stop already swept the map), so stop-and-check in a bounded loop.
  Short sounds (<1s) are easy to miss entirely — assert playback on a long
  one (the sample anthem) and stop it after.
- **Typed `/materione` arguments never reach the server**: the synced
  client tree collapses everything after the literal into ONE
  `StringArgumentType.string()` node, so the chat GUI rejects any
  multi-word or colon-bearing argument client-side (#96). Send dev
  commands as raw packets instead:
  `mc.getConnection().@target.sendCommand('materione dev sound sample:anthem')`
  — the server handler executes and the client hears it.
- **Comparator output: read a wire's client state, not the NBT** — the
  comparator's cached `OutputSignal` block-entity field does not track the
  live value under setblock-driven tests. Orientation is subtle: a
  comparator's `facing` names its INPUT side's neighbor... in practice
  compose as `jukebox at z`, `comparator[facing=north] at z+1`, wire at
  z+2 — the wire's `power` in the client block state is the honest
  end-to-end channel (a playing disc's authored comparator output lands
  there within a second).
- **Jukebox discs through a real client**: hold the disc in the main hand
  and `useItemOn` the jukebox to insert (`has_record=true` in the client
  block state); an empty-hand `useItemOn` ejects — the disc pops as an item
  entity AT the jukebox (tp adjacent to vacuum it). Two timing traps: eject
  late enough that the sound is fully live (an eject racing the engine's
  queue can leave the young instance playing its course — wait for the
  active instance, then ~1.5s), and the ejected instance retires off-thread
  (poll ~10s). Upstream: a jukebox-song event authored `stream: true` keeps
  playing past eject (#98) — non-streamed events stop instantly.
- **Forcing a chunk-load replay** (furniture evolution applies only in
  `EntityAddToWorld`, never on a timer): tp the player ~400 blocks away and
  hold — Paper keeps player-less chunks ~60s, so sleep a fixed 120s before
  tp-ing back (the fixed sleep removes unload-latency variance from the
  schedule; poll `execute if entity` to confirm the unload). Probes of the
  replay windows then read the root's `materione:furniture-id` PDC record.
  Gamerule notes on 26.x: the query/set form needs the namespaced id
  (`gamerule minecraft:random_tick_speed`), bare `gamerule` listing is gone,
  and `spawnChunkRadius` no longer exists — distance is the only unload
  lever.
- **Rotation tests**: the furniture placement yaw IS the look yaw, so stand
  where the desired yaw's ray actually hits the ground cell (e.g. yaw 90
  means standing east, looking west). Snap results normalize into the
  entity record: a snapped 180 reads back `[-180.0f, 0.0f]`. Doubles in
  `Pos`-style readbacks print full precision (`1.7999999999999998`) —
  round in the Groovy probe (`Math.round(x * 100.0) / 100.0`) before
  asserting. Each `materione give` lands in the FIRST empty hotbar slot,
  so select by scanning, never by fixed index.
- **`waitForServerLog` scans since test start** and returns the first
  match — when a test inspects several clusters, anchor the regex to THIS
  one (the inspect lines carry `display=<x.5>,<y.5>,<z.5>`), or an earlier
  cluster's line answers instantly with the wrong uuid.
- **Glyph/font acceptance**: real player chat is the decorate surface —
  `mc.getConnection().sendChat(':placeholder:')` (the `/say` console path
  bypasses `AsyncChatDecorateEvent`). The decorated echo lands in
  `chatHistory` with the substituted codepoint as a raw character — scan
  lines for `codePointAt >= 0x2000` to find it (the allocation window is
  42000+). The client font binding reads via `mc.font.width(str)` (an int
  argument like `Font.getGlyph(int)` trips the bridge's Integer boxing):
  a bound bitmap glyph measures its compiled advance, the missing-glyph
  box measures 6. The runner's `expect` has no negation and no
  `toBeGreaterThanOrEqual` — precompute booleans/derived values in the
  test and assert with `toBe`.
- **Glyph tags take bare ids** (`<glyph:heart>`, `<g:heart:colorable>`) —
  the tag parser splits on `:` and its second slot is the `colorable`
  flag, so a namespaced tag like `<glyph:sample:heart>` silently stays
  literal (lookup key becomes `sample`). The `reference:` *field* in
  glyph YAML takes full ids. Sheet cells are reached only through a
  reference alias's `index` (`2` or `1..4`, 1-based row-major); the
  sheet's own tag/placeholder renders cell 1 — and author `index: 2` as
  a number or string, both parse (#81). Absolute cell codepoints differ
  between a fresh allocation table and a long-lived server (runs hand
  out in definition-scan order, durably) — assert the run's shape
  (count, consecutiveness), never absolute values. Grid composition
  (a wide `rows: 1` strip) chains the cell tags with NO separators —
  flush-ness is assertable by slicing the chat line at the first high
  codepoint and checking the four cells are adjacent. Alias surfaces
  resolve one level only: a dangling target or an alias→alias chain
  quarantines at pipeline validate (UNRESOLVED_REFERENCE), so broken
  aliases never reach the runtime — their rejection is pinned by unit
  test, the healthy corpus must stay quarantine-free. Permission-gated
  aliases deny per alias (`deop Probe` → tag literal, `op Probe` →
  resolves; restore op BEFORE the asserts — the async decorate settles
  ~seconds after sendChat, so sleep before restoring). Shift tags
  (`<shift:N>`) render U+E101..U+E11A space-provider characters —
  codepoint-scanner helpers must treat that window separately from the
  glyph window (42000+, below U+E101). The client font engine measures
  the composed run at exactly N pixels (`mc.font.width` over
  `new String(new int[] {…}, 0, n)` — build multi-char strings from an
  int array; `toChars` is single-codepoint), which is the exact
  discriminator: advance sum, never the missing-glyph box. Emoji
  surfaces: the join-time custom chat completions read back from the
  client at `mc.getConnection().suggestionsProvider.
  customCompletionSuggestions` (26.1.2; the connection object is a
  DebugBridge wrapper — property access forwards, `.@target` reaches
  the raw object) — the list is built at JOIN, so permission-filtering
  asserts need `ctx.rejoin()` after `deop`/`op`; per-message decoration
  permission is live and needs no rejoin. Plain (colon-less)
  placeholders resolve as substrings of literal runs (#79) — beware
  collisions when authoring them (`<u` matches inside `<unknown:x>`).
  Effect surfaces: the injected shader reads back client-side from the
  resource manager (`mc.getResourceManager()` over
  `Class.forName('net.minecraft.resources.Identifier')` — 26.x renamed
  ResourceLocation; `readAllBytes().@target` unwraps the bridge's byte
  array before `new String(bytes, UTF_8)`) — `minecraft:shaders/core/
  rendertype_text.vsh` resolving WITH the materione marker proves the
  overlay is active for the client's format. Effect spans close with
  `</e>`/`</effect>` — the id lives on the open tag only. The animation
  itself is judged by eyes on two frames ~700ms apart (mode visual).
  Fixture sync
  at `probe up` never prunes: deleting a fixture from the tree leaves
  the stale copy server-side — remove it under
  `harness/build/probe/server/plugins/Materione/content/packs/` too.
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
