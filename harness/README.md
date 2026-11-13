# Harness — headless Paper integration testing

Boots a real (pinned) Paper server in a temp workspace, installs the built
plugin jar, drives the console, and asserts **stable log markers**. The
harness is deliberately product-independent: it knows nothing about
Materione's internals, only these markers.

## Stable log markers (test API — treat as contract)

| Marker | Emitted when |
|---|---|
| `MATERIONE-BOOT-OK <version>` | bootstrap phases completed (even degraded) |
| `MATERIONE-CONTENT-OK items=<n> defs=<n> pack=<hash12\|noop>` | content pipeline ran to apply (M1) |
| `MATERIONE-DELIVERY-OK <url>` | the pack HTTP backend serves the live pack |
| `MATERIONE-PACKFORMAT detected=<n\|none> advertising=<min>..<max>` | server-reported resource-pack format read from the server jar's version.json |
| `MATERIONE-VERSION <version>` | `/materione version` executed |
| `MATERIONE-DOCTOR-OK` | `/materione doctor` completed |
| `MATERIONE-RELOAD-OK items=<n>` | `/materione reload` completed (live item count) |
| `MATERIONE-BYE` | clean disable (shutdown reached the end) |
| `MATERIONE-DEV-PLACE ok=<bool> <status>` | `/materione dev place` landed (M2 world runtime) |
| `MATERIONE-DEV-REMOVE ok=<bool> <status>` | `/materione dev remove` landed |
| `MATERIONE-DEV-INSPECT found=<bool> <block\|none …>` | `/materione dev inspect` answered |
| `MATERIONE-DEV-SWEEP indexed=<n> …` | `/materione dev sweep` validated the index |
| `MATERIONE-DEV-FURNITURE-PLACE ok=<bool> <status>` | furniture cluster placed via the `dev place` fallback |
| `MATERIONE-DEV-FURNITURE-REMOVE ok=<bool> <status>` | furniture cluster removed via the `dev remove` fallback |
| `MATERIONE-DEV-FURNITURE-INSPECT found=<bool> <status>` | furniture cluster anchored at the inspected cell |
| `MATERIONE-GIVE-OK id=<id> player=<name> amount=<n>` | `/materione give` delivered (console senders only) |
| `MATERIONE-GIVE-DENIED reason=player-required` | console give without a target player |
| `MATERIONE-GIVE-FAILED reason=no-such-player\|no-content` | give could not resolve its target |
| `MATERIONE-BROWSER-DENIED reason=player-only\|denied\|usage\|not_live\|cancelled\|failed` | `/materione browse` (alias `browser`) from the console (`player-only`), or the GUI it opens refused (the opener's own outcome) |
| `MATERIONE-DEV-SOUND key=<pack>:<sound>` | `/materione dev sound` dispatched an in-game play (M4) |
| `MATERIONE-DEV-SOUND-DENIED reason=player-required` | `/materione dev sound` from the console (the client resolves the key, so a player is required) |
| `MATERIONE-STACK id=<id> item_model=<key\|none>` | `dev stack` built a live item's stack; the component names the item definition (`<id>`), never the pack model — `none` for material-only items |
| `MATERIONE-GUI-OPENED id=<id> player=<name> container=<id> slots=<n>` | a live GUI's window was opened for a player (`/materione gui open`, or the editor's preview) |
| `MATERIONE-GUI-REFRESH changed=<n> removed=<n> players=<n>` | a swap changed or removed live GUIs; the online players' windows are brought up to date (gui.md §5) |
| `MATERIONE-GUI-REFRESH-CLOSED id=<id> player=<name>` | a viewer's window was closed because its GUI is no longer live |
| `MATERIONE-GUI-DENIED reason=player-required` | `/materione gui open` from the console without naming a player |
| `MATERIONE-GUI-FAILED reason=not-live\|no-such-player\|refused` | `/materione gui open` could not resolve the GUI, the player, or the open was refused |
| `MATERIONE-GUI-FAILED reason=window gui=<id> player=<name> detail=<why>` | the server refused or could not open a GUI's window after its session was created; the session ended (#214) |
| `MATERIONE-OPEN-TREE declared=<n> registered=<n>` | the server's COMMANDS event registered the open commands from the built registries (#215); printed at start and on each server reload that fires the event |
| `MATERIONE-OPEN-TREE reload aliases=[<a>, …]` | a content swap asked for a server data reload, because an open command was missing from the tree or had a new argument shape (logic.md §23, decision 15). Never printed for an unchanged boot |
| `MATERIONE-OPEN-TREE reload-deferred aliases=[<a>, …] reason=rate-limit` | such a request fell inside the five-second window after the previous reload; one scheduled retry applies the latest stale aliases after the window without another content swap (#246) |
| `MATERIONE-OPEN-COMMAND alias=<alias> outcome=opened\|refused\|usage\|console` | a GUI's open command (`open: commands:`, #215) ran: `opened` or `refused` for a player (the refusal's reason is in chat), `usage` for an argument that does not read as its type, `console` for a console sender, which has no window |

The `guilogic` fixture pack (#214) announces its own opens and closes on the console as
`say MATERIONE-GUILOGIC-OPEN <gui> ...`, `MATERIONE-GUILOGIC-PONG slot=<n>` and
`MATERIONE-GUILOGIC-CLOSE <gui> reason=<reason>`; those lines come from the pack's rules, not the plugin, and
only the QA catalog's `guilogic` suite reads them.

The standard scenario boots with the fixture packs under `fixtures/packs/`
filtered to sample + corpus + nested (4 + 50 items, plus the nested pack's
item under `items/weapons/`, #191), so the expected live count is **58**; the
hot-reload scenarios below move it to 59 and back. The world
smoke between doctor and reload force-loads chunk (0, 0) — a headless boot
has no players to keep chunks warm — then drives `dev place → inspect →
sweep → remove` against `sample:amethyst_lamp` at `0 200 0`, the shaped
stair/slab pair at `1/2 200 …`, and finally a `sample:chair` furniture
cluster at `3 200 3` (placed and removed through the same dev commands).

The **world-persistence scenario** (`WorldPersistenceRunner`,
`PaperWorldPersistenceIT`) is the M2 exit gate: it boots with all three
fixture packs (**57** items live) and, across five boots in four
workspaces, proves a placed world — one block per state technique, the
shaped pair, and a furniture cluster with seat, storage, light, label and
evolution — survives `reload`, a full restart, and a copy of the `world/`
folder into a fresh workspace. It also carries the M2 fault injections:
an item file with a broken rule condition and an empty rule chain
(MTR-0021/0022 land, everything else stays live), and the corruption
pair — a block is placed, the disposable allocation table deleted plus a
first-sorting definition added, and the reboot's rebuilt table hands the
state slot to another id; the chunk-PDC record wins (MTR-0024 mismatch on
inspect, `drifted=1` + targeted resync on sweep).

## Running

```bash
./gradlew :harness:integrationTest          # full boot + command + stop cycle
./gradlew :harness:downloadPaperServer      # pinned jar (sha256-verified) into Gradle cache
./gradlew :harness:printLatestPaperPin -PpaperVersion=1.21.4   # bump procedure
```

The boots use the CI ports (server 25599, editor 8089, delivery 8090). When another server on the
machine holds them, pass free ones: `-Pharness.serverPort=25611 -Pharness.editorPort=8111
-Pharness.deliveryPort=8112`. The editor and delivery ports are written into the workspace's
`settings.yml` only when overridden; the defaults are untouched.

The server jar is pinned by absolute URL + sha256 in
`gradle/libs.versions.toml` (the old PaperMC v2 download API is dead; the
harness makes zero API calls at test time). CI caches the Gradle-cache
directory keyed on the catalog hash.

Manually debugging a boot (also from IntelliJ — `application` run config on
`dev.materione.harness.PaperHarnessRunner`): the runner preserves the whole
workspace under `harness/build/work/<scenario>/` including `stdout.log` and
`logs/latest.log`.

### Opt-in rich-title floor test

The headless protocol test for #217 uses the pinned Paper **1.21.4** server and
a disposable test-only observer plugin. It is skipped unless explicitly enabled;
the ordinary integration task does not install or run the protocol client. With
Node 22 or newer and the ports reserved for this run:

```powershell
Push-Location harness\protocol
npm.cmd ci --ignore-scripts
Pop-Location
.\gradlew.bat :harness:integrationTest --tests dev.materione.harness.PaperRichTitleFloorIT '-Pharness.richTitleFloor=true'
```

`harness/protocol/package-lock.json` pins the public `minecraft-protocol`
1.68.0 (BSD-3-Clause) and `minecraft-data` 3.119.0 (MIT) client dependencies.
The lockfile also pins their transitive dependencies; `npm.cmd ci --ignore-scripts`
installs them only into the ignored `harness/protocol/node_modules`. The
scenario installs no helper in the
product jar. It checks component font data and full inventory packets from
real production GUI updates, server-native menu/id and session identity, clock,
open/close events, absence of clientbound close-window packets, cursor, input and
visibility-dependent hitboxes. The runner
prints SHA-256 fingerprints of the Paper, product and observer jars, fixture and
lockfile and preserves the server and bot logs in `harness/build/work/rich-title-floor/`.
This is protocol and server-state evidence only: it does **not** show vanilla
client screen reconstruction, pixels, flicker or physical mouse drag. Those
remain separate real-client checks on the 26.x probe.
For an explicitly staged local 26.x probe, the same test-only observer supports
`floorprobe observe Probe` followed by `floorprobe observed Probe`: it reports
server menu identity, session, clock, item state and visible coverage without
seeding or altering the player's items. This does not itself simulate a drag.

The client assertions and the library's real 1.21.4 packet codec can be checked
without booting a server:

```powershell
node --test harness\protocol\floor-bot.test.mjs
.\gradlew.bat :harness:richTitleProbeJar :harness:testClasses '-Pharness.richTitleFloor=true'
```

## Scenarios

- `PaperBootstrapIT` — the M1 exit gate, live on Paper: boot with the
  fixture corpus (58 items), then the hot-reload sequence —
  1. no-op reload (nothing changed since boot),
  2. add `gem_extra` to the corpus pack → reload → 59 items,
  3. drop a syntactically broken item file → reload → still 59 items and
     `MTR-0007` in the log (fault injection, fail-soft D6),
  4. remove both files → reload → back to 58,
  5. nested (#191): add `nested_gem` under `nested/items/weapons/` → reload →
     59 items; a broken file beside it → still 59 and `MTR-0007`; remove both →
     back to 58.
- `PaperFailSoftIT` — writes a deliberately broken `settings/settings.yml`
  before boot; the server must STILL boot and pass doctor (fail-soft proof,
  master D6), with `MTR-0004` reported in the log.
- `PaperGuiRuntimeIT` — the M6 runtime gate (gui.md §5, §7, issue 185): a GUI authored into the sample
  pack goes live at boot; the console's `gui open` refuses correctly (no player to show it to, an
  unknown player, a GUI that is not live); the editor's `runtime.previewTarget` reports no player and
  `runtime.openGui` refuses with `MTR-0009` for a console session; then the editor's own saves drive the
  live refresh — a changed title is `changed=1` within the 2 s live-loop budget, a schema break is
  `removed=1` and the GUI stops resolving, and a slot naming an item that is not live is `MTR-0054`
  while the GUI stays live. Headless: no client joins, so the window itself is judged by the unit tiers
  and the QA catalog (`gui.runtime-*`).
- `PaperContainerWindowIT` — the window type and top size of a hopper (5 slots, `minecraft:hopper`) and a
  dispenser (9 slots, `minecraft:generic_3x3`) created through the legacy
  `createInventory(holder, InventoryType, Component)` path on the 1.21.4 floor (gui.md §12.2.6, #289). A
  test-only plugin (`harness/src/containerProbe`, built by `:harness:containerProbeJar`, never shipped) makes the
  windows and logs `MATERIONE-CTPROBE describe …`; headless, so the screens themselves are the 26.x probe's
  evidence (`fixtures/gui/containers/`).
- `PaperEditorHandshakeIT` — drives the real local handshake and editor mutations,
  including rapid glyph texture/create/save requests. Before comparing allocated glyph
  metadata with the compiled font, a queued console reload acts as a completion barrier:
  older `push.reload` events and allocation visibility alone do not prove that save's
  archive is ready. Failures include the server log tail in the assertion summary.
- `PaperInputDurabilityIT` — the platform probes of
  [`gui-input-durability.md`](../docs/architecture/gui-input-durability.md) §11 (row R, #306). Opt-in
  (`-Pharness.inputDurability=true`; each scenario boots the server two to five times). It runs on the **crash rig**
  (`CrashRig`): a workspace that can be booted, killed and booted again on the same world, with the player's `.dat`
  readable from the harness (`PlayerDat`, a minimal gzip-NBT reader). The player is a **fake-connection player**: a real
  `ServerPlayer` placed by `PlayerList.placeNewPlayer` over a `Connection` on a netty `EmbeddedChannel`, driven by the
  test-only `durabilityProbe` plugin (`harness/src/durabilityProbe`, `:harness:durabilityProbeJar`, never shipped; every
  answer is one `MATERIONE-DPROBE <stage> …` line). Two tests: the save paths (`saveData`, quit, shutdown, autosave) and
  reload (P4), and the death matrix, a window released at `InventoryCloseEvent` versus in `PlayerDeathEvent` under both
  `keepInventory` values (P3). The kill rows of its first version (P2) are `PaperEscrowCrashIT`'s now.
- `PaperEscrowCrashIT` — the **kill-boundary test** of the crash-safe input slots (row H, #313; rows K1 to K23 of
  [`gui-input-durability.md`](../docs/architecture/gui-input-durability.md) §5.10). The product's own runtime on a real
  Paper 1.21.4: the `guilogic:escrow` GUI (a one-row chest, `durable: required`), the `guilogic:consume` GUI (the same,
  with two buttons whose flows run `custody.consume`, #248) and `guilogic:trash` (the built-in recipe `trash_slot`), a
  fake player that logs in through the
  real pre-login preload (the probe fires `AsyncPlayerPreLoginEvent` from a plain thread) and the real join handler and
  plays with real click packets, and a **production crash gate**: `CrashGate` (`plugin/.../escrow/gate/`) names points
  inside the escrow code (`account-written`, `click-applied`, `release-done`, `recovery-before-commit`, `recovery-done`,
  `death-released`, `claim-done`, and for the exit and the barrier `exit-begun`, `exit-applied`, `ledger-before-append`,
  `ledger-after-append`, `barrier-saved`) and, **only when the server JVM has `-Dmaterione.escrow.gate=<anything>`**, parks
  the thread that reaches the armed name after logging `MATERIONE-ESCROW-GATE <name>`; without the property the call is one
  constant branch the JIT removes (read once into a `static final`, unit-tested and ban-tested). The rig starts the server
  with the property set to `off` and arms a gate from inside (`dprobe arm`) just before the step that reaches it; the
  harness sees the marker, `destroyForcibly`s the process tree (a `kill -9` at that instant), reads the `.dat` and the
  ledger itself, boots the **same world**, logs the player in again and asserts what it holds (G1 conservation, G2 no
  resurrection, G4 no world write, I6 idempotent recovery). A row that needs no mid-step kill kills at `claim-done`, which
  `/materione claim` reaches without touching any state. **Tier 2 and the barrier are the product end to end since #248:**
  a click on a button of `guilogic:consume` runs the real flow, so K10 to K14, K17 and K23 kill the real exit between its
  steps. The two ledger gates park the writer's **IO thread** (the server keeps ticking), so a row can `dprobe save` or
  `dprobe leave` while the exit is stopped half-way; `exit-begun`, `exit-applied` and `barrier-saved` park the main thread.
  What is still crafted is a file state a kill *inside a write* would leave (the harness's own reader and writer of the two
  formats, `EscrowFiles`): K3 and K4 (inside the platform's own save), the torn INTENT of K10c and the torn DONE of K17
  (cut from the real record), and K23b's disk that cannot save (the `.dat_old` the server renames onto is made a non-empty
  directory, so the player save does not land and the barrier's read-back finds the older snapshot).

  | Run | Rows | Boots | When |
  |---|---|---|---|
  | `PaperEscrowCrashIT.coreRows` | K1 (killed inside the step), K2, K5, K11 (a real consume), K18 | 6 (about 2 min) | **default** `:harness:integrationTest` |
  | `tier1Rows` | K1 (after the step), K6, K7, K8a/b/c | 7 | `-Pharness.inputDurability=true` |
  | `deathAndDisableRows` | K9a to d, K20a/b | 7 | opt-in |
  | `ledgerRows` | K10a/b/c, K11 (rolled back, G3), K12, K13 | 7 | opt-in |
  | `exitRows` | K14a/b, K15/K16, K17, KT (the trash slot, a composition) | 6 | opt-in |
  | `cursorAndFileStateRows` | K21a/b, K22a/b, K3, K4 | 7 | opt-in |
  | `k19AnAutosaveNeverSeesHalfAClick` | K19, Paper autosave on every tick | 2 | opt-in |
  | `k23TheBarrier` | K23a (kill between `saveData()` and the read-back), K23b (older snapshot), K23c (effect after the proof), K23d (disk recovers) | 5 | opt-in |

  ```bash
  ./gradlew :harness:integrationTest --tests dev.materione.harness.PaperEscrowCrashIT                                  # the core rows
  ./gradlew :harness:integrationTest --tests dev.materione.harness.PaperEscrowCrashIT -Pharness.inputDurability=true   # all of them
  ```

  Each boot is about 25 s, so the opt-in groups are minutes, not seconds, and stay out of the default run. A boot that
  follows a kill is the check of the row it follows and the start of the next, so a group of N rows costs N+1 boots.
- `FoliaInputDurabilityIT` — probe P1 / F12 on a **Folia** server. Opt-in (`-Pharness.folia=true`): it fetches the
  sha256-pinned Folia jar (`folia-harness-*` in `gradle/libs.versions.toml`, `:harness:downloadFoliaServer`, verified by
  the same task as the Paper pin) into the Gradle cache and needs `npm ci --ignore-scripts` in `harness/protocol`
  (the player is the real protocol client `idle-bot.mjs`: Folia's join is region-scheduled and a fake connection
  does not reproduce it). A `folia-supported` probe plugin (`harness/src/foliaProbe`) runs a two-write step on the
  player's entity scheduler while the autosave runs every tick and the harness reads the `.dat` in a loop, with an
  off-thread `saveData` loop as the positive control. Not a Folia port of the harness: the Folia platform axis for the
  existing ITs and the QA suite is #314.

## Related: the agent probe loop (`scripts/probe/`, `docs/probe.md`)

The tiers above are headless — the opt-in protocol client does not render, so visual failure modes
(tofu glyphs, checkerboarded models) stay invisible to them. The **probe
loop** complements them locally: a second Paper pin (`paper-probe-*`, the
26.x seam) plus a real windowed client joined via the DebugBridge bridge mod,
giving an agent session RCON scene control and framebuffer screenshots. Not
CI; prerequisites and the full recipe live in [`docs/probe.md`](../docs/probe.md).

## Related: the in-game QA suite (`qa/`, `qa/README.md`)

Where the probe loop is a *debugging* tool (reproduce one reported failure),
the **QA suite** is the systematic layer on top of it: a committed test
catalog (`qa/catalog/`), a ledger of what passed at which commit
(`qa/ledger/`), dependency-ordered execution that blocks dependents of a
failure, and failure dossiers that file as GitHub issues. It rides the same
26.x probe locally — never CI (which runs only the cheap catalog `check`).
Charter and commands: [`qa/README.md`](../qa/README.md).
