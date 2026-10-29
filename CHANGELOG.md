# Changelog

All notable changes to Materione are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). While the version is 0.x, minor
releases may change file formats, settings and the public API.

## [Unreleased]

### Changed

- **The cloud editor no longer falls back to the local link on its own.** If the relay cannot be
  reached, `/materione editor` now reports `MTR-0050`, opens nothing, and points at
  `/materione editor local` with a note that the editor port must be reachable from your computer.
  The server cannot know that, and a link to an unreachable port is worse than a clear refusal.
- **Direct-mode preference is proof-gated.** A cloud editor session carries a local-listener hint
  only when you run the command from the server's own machine (or pin `editor_public_url`). The
  page uses the local listener only after your browser verifies it cryptographically (a signed,
  session-bound challenge); any other outcome stays on the cloud silently.
- `/materione editor local` now says the link only works from a computer that can reach the port.

### Added

- **GUI studio: paint, image, text and item components (gui.md §6).** The studio's text and item
  layers are real renders now, and every component has its tools. Paint layers get a brush and
  eraser (1-8 px, square or round), contiguous fill, an eyedropper that samples what the player
  sees, and a palette with recent colours; strokes are smooth at any zoom and each is one undo
  step. Image layers come from an upload (stored beside the GUI as chosen) or from the Assets
  workspace, scale with nearest neighbour, and reset to natural size. Text layers edit in place on
  the canvas, with a pixel-exact `smallcaps` preview drawn from the same font source the compiler
  ships, an "approximate preview" note for the client's own font, and the compiler's restrictions
  (reserved characters, `default` only as the last run, rows above 5) shown as warnings instead of
  being discovered in game. Item layers snap to container slots only, pick from Materione items and
  vanilla materials with their icons, and **Customize** creates an ordinary item under
  `items/gui/<gui-path>/` and opens its appearance inline, in one authoring transaction.
- **GUI runtime: open, live refresh and preview in game (gui.md §5, §7).** A compiled GUI now opens
  as a real container window with its title and its items, through the same item factory `give`
  uses. `/materione gui open <id> [player]` (permission `materione.gui.open`) opens one for a player.
  When you save a GUI in the editor, every player who has it open sees the change at once: the items
  update in place, and a new title or size re-opens the window with the cursor item kept in hand. A
  GUI that stops compiling closes for its viewers with a note. Every click inside a GUI is cancelled;
  your own inventory keeps working. The studio gets a "Preview in game" button, enabled while the
  player who opened the editor in game is online. A slot that names an item that is not live is
  reported as `MTR-0054` and left empty; a permission refusal is `MTR-0055`.
- **GUI compiler: raster, background glyph and title (gui.md §4).** A GUI now compiles into pack
  output: its visible raster layers flatten bottom to top onto one canvas (the shared vectors
  pin the integer pixel rules), the canvas becomes a background glyph in the GUI font, and the
  title is assembled around it with shift tags and offset fonts. Text runs, slot items and the
  runtime model (`CompiledGui`) come from the same pure function the pack uses. `smallcaps` is the
  first-party title font (#188): original pixel art in `fonts/smallcaps/smallcaps.txt`, read by the
  compiler and the studio alike, with an RGBA sheet and 5 px small capitals under 6 px capitals.
  `default` references the vanilla ASCII texture. A layer that cannot be drawn (a missing texture,
  text the fonts cannot draw) is skipped with `MTR-0052`; a GUI whose background codepoint block is
  used up is quarantined with `MTR-0053`.
- **GUI content type (`guis/`), first slice.** A GUI is an ordered list of layers in a chest of
  one to six rows. The seven built-in layer kinds (`panel`, `slots`, `image`, `paint`, `button`,
  `text`, `item`) are entries in a new `gui-components` registry, the same public path addons use.
  Parsing, the self-description the studio reads, and the New GUI starter (a three-row panel with
  both slot regions) ship now. Rendering to a container screen follows in a later slice. A layer
  that does not parse is skipped alone with `MTR-0052`; the rest of the GUI still parses.
- **`authoring.setFields` can carry binaries.** An optional `assets` list (path and base64 bytes,
  like `assets.put`) is committed in the same transaction as the field edits, under the model
  save-back's rollback: a stale revision or a refused path writes neither.
- `authoring.describe` lists the registered GUI components (`guiComponents`).
- Settings `editor_direct_hint` (`off` by default; `auto` or `always` opt in to the local check), `editor_public_url` (an `https://`
  reverse-proxy front for the listener) and `editor_local_listener` (`false` never opens the port).
- The Server view shows whether the session is on the cloud relay or direct, with a per-browser
  "always use the cloud relay" switch.

### Fixed

- `?mode=relay|direct` on the editor link was silently ignored (the address bar was cleared first).
- The editor file watcher no longer depends on the local listener being bound.
- **Content in subfolders goes live.** The pipeline listed each content directory non-recursively,
  so a resource in `items/weapons/sword.yml`, including one the editor created in a folder, was
  never parsed. Discovery is now recursive for every content type, sorted by path, and skips
  dot-directories such as `.materione/`. Resource identity is unchanged (the top-level keys). The
  browser, the file watcher and the boot-time reader follow the same rule, and hot reload picks up
  nested edits. (#191)
- The editor browser lists `.yml` documents only, as the pipeline always required. Its `.yaml`
  files were never loaded, so they no longer appear as resources. (#191)
- **GUI text no longer replaces vanilla text.** A pack with a GUI text layer referenced the GUI's
  ASCII offset fonts from `minecraft:default`, so unstyled text across the game (chat, item names,
  vanilla labels, toasts) rendered through GUI glyphs. Only glyph fonts join the default font now;
  GUI fonts are used through their styled keys. A server pack's `default.json` merges with
  vanilla's on the client, so nothing vanilla needed restoring. (#198)

## [0.1.0] - 2026-10-27

The first public release. Materione is one free, open-source Paper plugin, with a web
editor, that covers the custom-content stack servers usually assemble from several
plugins (ItemsAdder, Oraxen or Nexo-class content, plus glyphs, sounds and paintings).
This entry describes everything the release contains, not a diff.

### Highlights

- **Files are the source of truth.** Content lives as YAML and assets under
  `plugins/Materione/content/packs/`. Boot, `/materione reload` and editor saves all go
  through one pipeline, and an untouched file round-trips byte for byte.
- **Fail-soft.** A broken file or resource is quarantined with a stable `MTR-####`
  diagnostic. The server keeps running.
- **Custom items, blocks, furniture and recipes,** with a behavior engine
  (event, condition, action) and built-in mechanics.
- **Glyphs and emoji, custom sounds and music discs, paintings, and animated text
  effects,** all compiled into one deterministic resource pack that the plugin hosts
  itself.
- **A web editor** for every content type, with live previews, a Minecraft rich-text
  editor, templates, and embedded Blockbench. Open it with `/materione editor`, through
  the hosted relay (the default) or a local listener, end-to-end encrypted and approved
  in game.
- **Paper 1.21.4 and newer,** including the 26.x line.

### Requirements and first steps

- **Server.** Paper 1.21.4 or newer on Java 21. Spigot and CraftBukkit cannot load the
  plugin.
- **Install.** Drop `Materione-0.1.0.jar` into `plugins/` and start the server. A fresh
  install has no content.
- **Resource pack.** The plugin serves the pack itself on port `8090`. Open that port and
  set `delivery_public_host` in `plugins/Materione/settings/settings.yml` to your
  server's public address, then restart. If it stays blank, remote players are sent a
  `localhost` URL and cannot download the pack.
- **Block performance.** Set `disable-noteblock-updates`, `disable-tripwire-updates` and
  `disable-chorus-plant-updates` to `true` in `config/paper-global.yml`.
- **Permissions.** Every node defaults to operators. Grant `materione.editor` (and
  `materione.admin` to approve editor browsers) deliberately.
- **Usage statistics.** The plugin sends anonymous usage data to
  [bStats](https://bstats.org) (plugin id 34981). To opt out, set `enabled: false` in
  `plugins/bStats/config.yml`.
- **Create content.** Run `/materione editor`, approve the browser in game, and create a
  project and your first item. You can also write the pack files by hand and run
  `/materione reload`.

### Core platform and content pipeline

Materione turns the files under `plugins/Materione/content/packs/` into live server state. The files are the source of truth: boot, `/materione reload` and editor saves all run the same pipeline, and a broken file is quarantined instead of stopping the server. The limitations at the end of this section apply to 0.1.0.

#### Pack layout on disk

- **Pack folders.** Each pack is `plugins/Materione/content/packs/<pack-id>/` with a `pack.yml` that carries `id`, `version`, `schema_version` and optional `depends`. The folder name is the pack's namespace.
- **Content folders.** `items/`, `blocks/`, `furnitures/`, `recipes/`, `glyphs/`, `sounds/`, `paintings/`, `effects/` and `templates/` hold `*.yml` files. Each file holds one content type, with one top-level key per resource id.
- **Assets.** `assets/textures/`, `assets/models/`, `assets/fonts/` and `assets/sounds/` sit beside the YAML, so a pack zips and shares as one unit.
- **Id rules.** Namespaces match `[a-z0-9_-]+`, resource ids match `[a-z0-9_./-]+`, and `minecraft` is reserved.
- **Starting point.** A fresh install ships no content. Create the first pack and item in the web editor (`/materione editor`), or write the files by hand.

#### Pipeline and reload

- **One path for every trigger.** Boot, `/materione reload` and editor saves run scope, parse, validate, graph, compile (allocation included), apply and deliver, in that order.
- **Full reload.** `/materione reload` reloads every pack, reports the live item and quarantine counts, and publishes the pack to online players when its bytes change.
- **Targeted saves.** An editor save reloads the touched file; asset and model operations reload their whole pack. The saving player receives the result as a candidate, and publishing is a separate step.
- **Shadow-swap apply.** The new runtime is built in full, diffed against the live one, and swapped in. A failed swap rolls back to the previous state.
- **No-op reloads.** When the pack bytes and the runtime diff are both unchanged, nothing is published and the reload reports no content changes.
- **Pre-apply counts.** Each apply records items, blocks and furniture added, changed and removed; `/materione doctor` shows the last apply.
- **Serialized runs.** Reloads run one at a time.

#### Fail-soft and diagnostics

- **Quarantine per resource.** A YAML syntax error quarantines its file (`MTR-0007`). A broken reference or invalid item component quarantines that resource (`MTR-0010`, naming the first fatal reason). A definition the type cannot read is reported as `MTR-0008` and left out of the runtime.
- **Stable codes.** Diagnostics use `MTR-0001` through `MTR-0051`, defined in `DiagnosticCodes`. Codes are never renumbered.
- **Logged as published.** Every diagnostic reaches the server log: errors at severe, warnings at warning, the rest at info.
- **Doctor.** `/materione doctor` (permission `materione.editor`) reports platform, settings version, storage format, allocation count, live items, pack hash, last apply, staged restarts, delivery URL, registries, quarantine count and diagnostic totals.
- **Degradation.** Only an unsupported server version disables the plugin (`MTR-0001`). Every other startup phase degrades and keeps the server running.

#### Lossless files and determinism

- **Byte-identical round trips.** Loading and saving an untouched YAML file returns the same bytes, including comments, ordering, blank lines and unknown keys. A one-field edit changes one line. The test suite checks every fixture file.
- **Deterministic packs.** The same inputs produce the same `pack.zip` bytes. Golden-file tests pin the sample pack and the sample datapack.

#### Settings, storage and on-disk state

- **`settings/settings.yml`.** Written with defaults on first boot and read at boot. Keys: `delivery_host` (`0.0.0.0`), `delivery_port` (`8090`), `delivery_public_host` (blank), `editor_enabled` (`true`), `editor_host` (`127.0.0.1`), `editor_port` (`8089`), `editor_file_watcher` (`true`), `editor_mode` (`cloud`) and `relay_base_url` (`https://editor.materione.com`).
- **Broken settings.** An unparseable file is kept as `settings.yml.broken-N`, defaults are used, and `MTR-0004` is raised.
- **`storage/` (durable, back it up).** `allocation.bin`, `audit.log`, `revisions/`, `delivery/`, `staged-restart/`, `format.properties`, `editor-identity.json` (the server's editor signing key) and `editor-workstations.json`.
- **`generated/` (disposable).** `pack/pack.zip`, `fingerprints.bin` and `index/placements.jsonl`. These are regenerated or rebuilt by the next reload.
- **Allocation table.** Model ids, glyph codepoints and block states are assigned once, never shift, and stay reserved after removal as tombstones.

#### Commands and permissions

- **Commands.** `/materione version`, `doctor`, `reload`, `give <id> [player] [amount]` (amount is clamped to 1 to 2304), `browser` (player-only content browser), `dev` (world and preview tools) and `editor` (`open`, `local`, `status`, `approve`, `deny`, `revoke`, `stop`).
- **Nodes.** `materione.editor` is required for `doctor` and every `editor` action; approve, deny, revoke and stop also need `materione.admin`. `materione.pipeline` covers reload, `materione.give` covers give and browser, and `materione.dev` covers the dev tools. All default to op.
- **Console.** `give` from the console needs an explicit target player, and `browser` is player-only.

#### Server support and build

- **Floor.** Paper 1.21.4 or newer (D10). Older servers are refused at boot with `MTR-0001`. Paper 26.x passes the version probe and uses the registry seam for paintings and jukebox songs.
- **Registry seam.** On 1.21.6 and newer, paintings and jukebox songs register through Paper's registry events. On 1.21.4 and 1.21.5 they ship in a world datapack.
- **Verification.** CI boots the built jar on a pinned, checksummed Paper 1.21.4 build 232. The 26.x path is exercised only through the local QA probe.
- **Java.** Runtime Java 21. Building needs a Java 21 toolchain and, for the `modern` module, a Java 25 toolchain; Gradle's foojay resolver provisions whichever is missing.

#### Performance budgets

- **Asserted in the build.** `ContentPerfBudgetTest` fails when a full reload takes 10 s or more, a pack build 30 s or more, a no-op decision 100 ms or more, an apply swap 50 ms or more, a single-file reload 1 s or more, or a steady-state item lookup 1 microsecond or more. It runs on the 62-item fixture corpus, not on large installations.

#### Usage statistics

- **bStats.** Anonymous metrics (plugin id 34981) start after boot. Along with bStats' standard server fields, the plugin reports one custom chart, `editor_transport` (`cloud`, `local`, `disabled` or `unknown`). Details are in `docs/architecture/plugin.md` §7.2.
- **Opt-out.** Set `enabled: false` in `plugins/bStats/config.yml`. That switch is global for every plugin that uses bStats. A bStats failure is logged as a warning and never blocks startup.

#### Not yet included

- **Menus, dialogs and HUDs (M6).** The `menus/`, `dialogs/` and `huds/` folders are reserved; nothing reads them yet.
- **Addon JARs.** There is no loader for `plugins/Materione/addons/`. Plugins extend the system through the `MaterioneAPI` Bukkit service.
- **Pack management commands.** There is no `pack enable|disable|import|export`, no `reload <pack>` and no `reload settings`.
- **Delivery backends beyond self-host HTTP.** No S3/R2 backend, proxy-side dedup or obfuscation step.

#### Known limitations

- **One bad identifier stops the whole reload.** A content key or pack folder outside the id rules (for example `Bad Item:` or a folder named `MyPack`) aborts the pipeline run with `MTR-0011` instead of quarantining that resource. At boot the server then starts without pipeline content.
- **`.yaml` files are ignored.** The pipeline loads only `*.yml`. A `.yaml` file appears in the editor, never loads, and nothing is reported.
- **Reserved folders are silent.** `menus/`, `dialogs/` and `huds/` files are parsed but never compiled, with no diagnostic, until M6.
- **Packs without `pack.yml` drop silently.** Their resources are left out of the runtime with one `MTR-0013` error and are not listed as quarantined.
- **Pack identity and dependencies are not enforced.** The folder name is the namespace, the `id` in `pack.yml` is ignored, `depends` is checked nowhere, and `schema_version` has no migration steps.
- **Hand edits need a reload.** A file changed on disk is picked up only by `/materione reload`. The file watcher refreshes open editors, not the runtime.
- **`/materione reload` takes no arguments.** Extra arguments are ignored and trigger a full reload.
- **Settings need a restart.** `settings.yml` is read once at boot. The `debug` key is written to new files and read by nothing.
- **Allocation corruption is not blocked.** If `allocation.bin` cannot be replayed, compilation continues and new records are appended to the damaged file. A scratch check gave a new resource an id already in use. `/materione doctor` reports a rebuild as available, but no rebuild command exists.
- **Editor sessions are not checked per node.** `materione.edit.<pack>` is not implemented. Once a browser session is approved in game, its writes are not checked against per-pack or per-action nodes.
- **Pack delivery needs a public host.** The pack server binds `0.0.0.0:8090` over plain HTTP. With `delivery_public_host` blank, players are sent `http://localhost:8090/...`, which fails for remote players.
- **On 1.21.4 and 1.21.5 only the first world gets the content datapack.** Paintings and jukebox songs depend on that datapack, which is deployed to the first loaded world only.
- **Folia is declared, not tested.** `folia-supported: true` is set, but no CI or harness run uses Folia.
- **`logs/` is unused.** The folder is created at boot and nothing writes to it.

### Custom items, recipes and behavior

Items are YAML definitions that compile to vanilla 1.21.4+ item models and build stacks tagged with a Materione identity, so their behavior survives hoppers, anvils and crafting. Recipes (shaped, shapeless and furnace) register through Bukkit. Behavior runs on an event-condition-action (ECA) engine with six actions and five built-in mechanics. Several surfaces are deliberately partial; see Known limitations.

#### Item definitions

- **Identity.** Each item is a keyed entry in `items/*.yml` with `material` (required, a vanilla item material). Stacks carry a `materione:id` PDC tag.
- **Models.** `texture:` generates a model with parent `model_parent` (default `item/generated`); `model:` references a hand-authored model; with neither, the item renders as its vanilla material.
- **Text.** `display_name` and `lore` accept MiniMessage tags, `&` color codes and glyph tags, and render non-italic by default.
- **Templates.** `template: <id>` inherits fields from a `templates/` resource. Inheritance is single-parent and shallow: a child's `mechanics:` or `behavior:` list replaces the parent's whole list. An unknown template is reported and the item is quarantined.
- **Jukebox.** `jukebox_song:` names a sound that has a `jukebox:` section.
- **Live refresh.** Stacks in a player's own inventory are rebuilt to the current definition on join and on inventory open, close and click events (throttled to once per second per player). Durability and other volatile state survive.
- **Unknown keys** stay in the file and are ignored at runtime.

#### Built-in mechanics (`mechanics:` list)

Each entry is a single-key map, for example `- durability: {max: 500}`.

- **`commands`** (repeatable): `on_right_click` and `on_left_click` take command lists (`%player%` becomes the clicking player's name); `as_player` (default `false`) runs them as console when false.
- **`durability`**: `max` (1 or more). Counts vanilla damage events in stack PDC, cancels vanilla wear, and breaks the item at `max` uses.
- **`soulbound`**: no parameters. Keeps the item out of death drops and returns it on respawn or next join; skipped when `keepInventory` is on.
- **`food`**: `nutrition` (0 to 20), `saturation` (0 to 40), `can_always_eat` (default `false`), and `effects` as a list of `{type, amplifier, duration_ticks}` applied on consume.
- **`backpack`**: `rows` (1 to 6) and `title` (default `Backpack`). Main-hand right-click opens it; contents save when it closes.
- An unknown mechanic or invalid parameters are skipped with `MTR-0027`; the item still loads.

#### Behavior rules (`behavior:`)

- **Rule shape.** `on:` selects the event, `if:` lists conditions (all must hold), `do:` lists actions. Options: `id`, `cooldown` (milliseconds), `charges` (total uses) and `per` (`player` or `global`).
- **Item triggers.** `click:left` and `click:right` (main hand), `break` (the held tool breaks a block), `equip` and `unequip` (hotbar slot change), `drop`, `pickup`, `consume`, `damage`, and `death` (once per dropped stack). The reserved names `inv_click`, `tick`, `menu.click`, `hud.threshold`, `dialog.submit` and `custom` load but never fire.
- **Condition variables.** `player.{name, uuid, health, level, gamemode, sneaking, op, x, y, z, yaw, pitch}`, `location.{x, y, z, world}`, `world.{name, time}`, `item.{id, material, amount, durability_used, durability_max}`, `server.{online, max}`, and `vars.*` (set by `set_var` within one event).
- **Expressions.** Arithmetic `+ - * / %`, comparisons, `&& || !`, `?:`, parentheses, numbers, quoted strings, `true`, `false` and `null`. There are no function calls; division by zero yields null.
- **Actions.** `run_command` (`command`, or `{command, as: console|player}`; runs on the next tick), `message` (`text`; `target: player|actionbar|broadcast`), `sound` (`sound`, `volume`, `pitch`), `potion` (`type`; `duration` in ticks, default 200; `amplifier`, default 0), `cancel` (cancels the triggering event; see limitations), and `set_var` (`name`, `value`; scoped to one event).
- **Universal action keys.** `chance` (0 to 1) is honored. `delay` is accepted but not honored; see limitations.
- **Text.** `${expression}` interpolates values; `%placeholder%` resolves through PlaceholderAPI when it is installed.
- **Order.** Authored rules run first, then mechanic-contributed rules, in declaration order. Every matching rule runs.

#### Recipes (`recipes/*.yml`)

- **`type: shaped`** takes `shape` (rows of up to three characters, where a space is empty), `ingredients` (a map from single characters to item ids) and `result`.
- **`type: shapeless`** takes `ingredients` (a list of item ids) and `result`.
- **`type: furnace`** takes `input`, `result`, `experience` (default 0) and `cookingtime` (ticks, default 200; the key has no underscore).
- **References.** A Materione item ingredient matches only its exact stack; a `minecraft:` id means a vanilla material; a short id resolves in the pack's namespace. A result is always one item.
- **Apply.** Recipes produce no pack output. They register through Bukkit on each apply, and a bad reference quarantines only that recipe.

#### Commands

- **`/materione give <itemId> [player] [amount]`** (permission `materione.give`). Amount is clamped to 1 to 2304; overflow drops at the target's feet.
- **`/materione browser`** (permission `materione.give`, players only). A per-pack paginated grid: left-click puts one item on the cursor, shift-click gives a full stack.
- **`/materione dev stack <itemId>`** (permission `materione.dev`). Prints the stack's `item_model`.

#### Known limitations

- **`delay:` is not honored.** The value is parsed, but the platform's deferred runner ignores it, so actions run on the next tick.
- **`cancel` ignores its value.** `cancel: false` still cancels the triggering event.
- **`set_var` cannot count numerically.** `${...}` stores text, so `"${vars.count + 1}"` yields `1`, then `11`, then `111`. Bare arithmetic is stored as text, and a string ending in an operator character (for example `Hello!`) fails as an expression.
- **Failed conditions spend cooldowns and charges.** Both are consumed before `if:` is checked.
- **Silent drops.** An item without `material`, a recipe without a valid `type` or required keys, a template cycle, or a non-list `behavior:` produces no diagnostic and no content.
- **An unknown `material:` becomes `PAPER`** without a diagnostic.
- **`texture:` silently overrides `model:`** when both are set.
- **`components:` is parsed but not applied** (warning `MTR-0048`), so there are no enchantments, item flags, attributes, unbreakable or custom model data. Declarative model trees (`range_dispatch`, `select`, `condition`) are not built.
- **An unrecognized `as:` value runs as console.** Only `console` and `player` are documented.
- **Reserved events and unknown action types load silently.** Unknown rule keys are ignored, and `on:` details are case-sensitive (`click:Right` never matches).
- **No item actions beyond the six.** There is no give, take, damage, teleport, particle or menu action; use `run_command` for those.
- **Expressions** have no functions, indexing or list operations.
- **Recipes** cover only three kinds: no disable-vanilla setting, no `#tag` ingredients and no result counts.
- **Recipe skips** at apply are logged to the server console only. Recipe-book discovery covers only players online at apply.
- **Auto-refresh** covers only the player's own inventory; items in containers, on the ground or in ender chests update once moved into it. Each scan rewrites every Materione stack.
- **Anvil renames** on an item that sets `display_name` are overwritten on the next scan.
- **Durability** counts vanilla damage only, so the base material must have durability. No durability bar is shown.
- **Equip and unequip** fire only on hotbar slot changes. Click details are left and right only; there is no shift-click.
- **Cooldowns and charges** live in memory and reset on restart; `per` supports only `player` and `global`.
- **Backpack contents** live in the item and are lost with it, and the holder's `getInventory()` throws.

### Custom blocks and furniture

Custom blocks reuse a vanilla block state (note block, tripwire, chorus plant, waxed copper stairs and slabs) or a host block you choose. Furniture is a cluster of display entities. Each placed piece records its definition in the chunk it sits in, so pieces survive restarts, backups and copied worlds without a database.

#### Block techniques

- **`technique: note_block` (default)**: a full solid cube on a note block. Up to 1,099 definitions across all packs on a server.
- **`technique: string`**: a thin plant or decoration on a tripwire host. Up to 126 definitions. Tripwire cannot be waterlogged at the current API floor.
- **`technique: chorus`**: a connecting or transparent block on a chorus plant host (six connection bits). Up to 63 definitions.
- **`technique: technical`** with `host: <block id>`: uses no state slot. The placed block looks like its host. `texture` or `model` shows only on the placement item and in the editor preview.
- **`technique: stairs` and `technique: slab`**: waxed cut copper hosts. Facing, half and type stay vanilla state. Corner stair shapes render as straight stairs.

#### Placing and breaking

- **Placement items**: each block and furniture gets an item with its own id, unless `item:` names an existing Materione item (it must be an item). Other blocks use their host's item form, or paper when the host has none. Tripwire uses string.
- **Placing**: right-click a block face with the item, sneaking or not. Held vanilla blocks placed against a custom block go through the same runtime. Clicking the empty half of a custom slab turns it into a double.
- **Breaking**: survival and adventure players receive the `drops:` items. Creative and spectator players do not. Vanilla's own drop is suppressed. Break speed and tool rules come from the host block.
- **`drops:`**: item ids, meaning Materione items, other placeable blocks or furniture, or `minecraft:` items. Each entry drops once per break, so listing an entry twice drops it twice.
- **`blast_resistant: true`**: explosions leave the block in place. Otherwise an explosion removes it and nothing drops.
- **`move_policy`**: `keep` (default) moves the block with a piston and keeps its identity. `break` removes it through the normal break path, with drops.
- **`behavior:` rules**: fire on `place`, `break` and `interact`. The runtime handles right-clicks on custom blocks, so the host's own use (note tuning and playing) never runs.
- **Pick block**: picking a custom block returns its placement item.
- **Paper flags**: set `disable-noteblock-updates`, `disable-tripwire-updates` and `disable-chorus-plant-updates` to `true` in `config/paper-global.yml`. Until you do, the server logs MTR-0025 at boot and uses slower event-based fallbacks. The plugin reports the gap but does not edit that file.

#### Persistence and recovery

- **Chunk record**: each placed block stores its definition id in its chunk's data. Restarts, world copies and backups keep placed blocks.
- **Placement index**: `generated/index/placements.jsonl` speeds up sweeps. It is disposable and is rebuilt from chunk data on each chunk load and at boot.
- **Mismatch guard**: a block whose state disagrees with its record is ignored and reported as MTR-0024. `/materione dev sweep --fix` rewrites the state from the record.
- **Edits**: changing a model updates placed pieces without editing the world. Blocks follow the pack. Furniture refreshes its model when its chunk loads. Placements of a removed definition stay in the world, flagged as orphans (MTR-0026), and `dev sweep` reports them.
- **Slots**: block state slots are recorded in `storage/allocation.bin`, which is append-only. Back it up with `storage/`.

#### Furniture

- **Parts**: an item display, interaction hitboxes, barrier cells for collision, invisible seat stands, light cells and an optional floating label. Missing entity parts are rebuilt when the chunk loads.
- **Keys**: `texture` or `model`, `display_name`, `rotation` (`0` free, `45`, `90` default, `180`), `hitboxes` (`interaction` or `barrier`), `seats` (offsets), `lights` (level 1 to 15), `storage` (0 to 54 in steps of 9), `label`, `evolution` (`target` and `after` seconds from placement), `item`, `behavior`.
- **Placing**: floor only. Right-click the top face of a block. Barrier cells must be free, otherwise the player sees "Not enough space". The piece snaps to its rotation grid.
- **Clicks**: right-click runs `click` rules first. Then it opens storage if the piece has any, otherwise it seats the player on the first free seat. Attacks do not damage pieces. Sneak-attacking removes the piece and drops its storage and item.
- **Storage**: a chest-style grid, saved when the menu closes. Contents spill out when the piece is removed.
- **Evolution**: after `after` seconds the piece swaps its model, id and stage count. This happens when the piece loads (chunk load or restart), not on a timer.
- **Operators**: commands under `/materione dev` need the `materione.dev` permission.

#### Performance

- **Per event**: each event that touches a custom host (note block, tripwire, chorus plant, stairs, slab or technical host) reads one chunk-data entry. Other blocks do no lookup.
- **Chunk load**: each loaded chunk's Materione keys are scanned once, at load and at boot.
- **Furniture lookups**: clicks, removals and storage saves search nearby entities within 16 blocks, so crowded areas make them slower.

#### Known limitations

- **No hardness, best-tool, chance or silk-touch drops**: `drops:` always drops every listed item. Custom break, place and step sounds and particles are not shipped.
- **Note blocks from the 350th definition on**: their pack variants name the wrong instrument, so the placed state has no matching variant and the model does not render. The instrument table omits guitar.
- **Shaped blocks**: in a pack with a stairs or slab definition, every vanilla waxed copper stair and slab takes that custom look. A second stairs or slab definition with a different model keeps the first model and reports MTR-0020.
- **Technical blocks** look like their host block in the world.
- **Water**: furniture barrier cells replace water, and removing a piece leaves air where the water was.
- **Not yet shipped**: furniture states, connected pieces, wall and roof placement, beds, doors, jukebox furniture, shulker and ghast hitboxes, scale and brightness.
- **Evolution** changes only the model, id and stage. The target's hitboxes, seats, lights and label are not applied.
- **Explosions** destroy non-blast-resistant custom blocks with no drops.
- **Unknown keys** in block and furniture files are ignored silently. A value the parser rejects drops the definition with a generic schema error that does not name the field.
- **Creative barrier breaks**: a creative player can break a barrier cell. Nothing rebuilds it, so the piece loses that collision until it is removed.

### Resource pack, glyphs, sounds, paintings, and text effects

Materione compiles every media asset into one deterministic resource pack, hashes it, and serves it from the plugin's own HTTP listener. Glyphs and emoji, custom sounds and music discs, paintings, and GLSL text effects all compile into that pack and resolve through one text pipeline. Changes to paintings and jukebox songs take effect at the next restart; everything else applies on reload.

#### Pack generation

- **Deterministic output.** The pack is written to `plugins/Materione/generated/pack/pack.zip`. Identical content gives identical bytes, and a reload with no pack change skips apply and delivery.
- **Assets ship as-is.** Files under each pack's `assets/` folder are copied into the pack under that pack's namespace. `.bbmodel` sources are excluded.
- **Custom textures reach the atlas.** Textures outside vanilla's `item/` and `block/` folders (and outside the font, particle, entity, environment, colormap, and painting folders) are registered as single-sprite atlas sources, so models can reference them.
- **Pack format range.** The pack advertises format 46 (the 1.21.4 floor) up to the larger of 88 and the running server's own resource format, in both the legacy and the current `pack.mcmeta` schemas.
- **Text-effect overlays.** Shader overlays exist for client formats 46-62, 63-83, 84-87, and 88. Clients outside 46-88 receive a pack with no shader overlay.
- **Failed compiles keep the last good pack.** A staging failure reports MTR-0011 and leaves the previous archive in place.

#### Delivery

- **Self-hosted HTTP only.** The plugin binds `delivery_host` (default `0.0.0.0`) on `delivery_port` (default `8090`). If that port is busy, it tries up to eight higher ports and publishes the URL with the port it actually bound.
- **Public address.** `delivery_public_host` sets the host in the pack URL. When it is blank and the bind host is `0.0.0.0`, the host falls back to `localhost`, so remote players need it set to a reachable address.
- **Versioned URLs.** Each pack is served at `/pack-<hash12>/pack.zip`. The four most recent versions stay downloadable. The published version is persisted under `plugins/Materione/storage/delivery/`.
- **Join and reload.** Players receive the published pack on join. A reload that changes pack bytes publishes the new version and resends it to every online player. Players may decline; declines are logged as MTR-0015 and the server keeps running.
- **Editor previews.** A save from the web editor stages a candidate pack that only the editing player receives. Publishing promotes that exact hash to everyone.
- **Settings.** `delivery_host`, `delivery_port`, and `delivery_public_host` are the only pack-related settings. There are no `required`, `prompt`, or hosting-backend options.
- **Status.** `/materione doctor` prints the delivery URL and each player's pack acceptance.

#### Glyphs and fonts

- **Four glyph shapes.** `texture` is one bitmap. `rows` and `columns` make a sheet, read row by row. `frames` makes an animation with one codepoint per frame. `reference` plus `index` makes an alias that borrows another glyph's bitmaps and allocates nothing.
- **Durable codepoints.** Codepoints come from an allocation table that starts at U+A410 and never writes back into YAML. Retired codepoints are not reused. The usable range holds about 15,600 codepoints; exhaustion quarantines the glyph (MTR-0045).
- **Font wiring.** Glyphs without `font:` go into `<namespace>:glyphs`, which the default font references. Shift characters live in one shared font, so `<shift:N>` works in every generated font.
- **Keys.** `texture`, `font`, `ascent`, `height`, `rows`, `columns`, `frames`, `reference`, `index`, `placeholders`, `permission`, `emoji`, `tab_complete`, `shadow_color`. Glyphs render white unless the tag says `colorable`.
- **Shift tags.** `<shift:N>` moves text by exactly N pixels, up to 65,536 in either direction.

#### Chat, signs, books, and item text

- **Authored text** (item names, lore, painting titles and authors) accepts a closed MiniMessage subset: colors, decorations, gradients, rainbow, transition, reset, and font, plus Materione tags. Click, hover, insertion, selector, NBT, and translation tags are not registered.
- **Player-typed text** (chat, signs, books, anvil renames) accepts only Materione markers: `<glyph:id>` or `<g:id>`, `<shift:N>`, `<effect:pack:id>`, and emoji placeholders.
- **Emoji mode.** Set `emoji: true` and list `placeholders:` such as `":heart:"` or `"<3"`. `permission` gates each glyph in chat. `tab_complete: true` adds its placeholders to chat completion at join.
- **Pack acceptance.** Markers in chat, signs, books, and anvil renames stay literal until the player's client reports the pack loaded.

#### Text effects

- **Four kinds.** `rainbow`, `wave`, `shake`, and `pulse`, with `speed` (default 3.0), `amplitude` (default 1.0), and `colors` (up to 16 `#RRGGBB` stops, used by rainbow).
- **Use.** Wrap text as `<effect:pack:id>...</effect>` (alias `<e:...>`). Spans do not nest in player-typed text.
- **Capacity.** Each effect takes one of 254 signal colors permanently. Deleting an effect does not release its color automatically; exhaustion reports MTR-0047.
- **Check in game.** `/materione dev effect <pack>:<id> <text>` needs the `materione.dev` permission.

#### Sounds and music discs

- **Definitions.** Each `sounds/*.yml` file defines one event. Sources are `.ogg` files under `assets/sounds/`. Keys: `sounds`, `subtitle`, `volume`, `pitch`, `category`, `stream`, `weight`, `preload`, `attenuation_distance`, `replace`, `replace_vanilla`, `jukebox`.
- **Vanilla overrides.** `replace_vanilla: <event>` replaces that vanilla sound outright. The event is forced to replace, so the client does not merge it into vanilla's pool.
- **Music discs.** A sound with a `jukebox:` section (`description`, `duration`, `comparator_output`, `range`) becomes a jukebox song. An item with `jukebox_song:` plays it. The disc stays silent until the song is registered at boot.
- **Check in game.** `/materione dev sound <pack>:<id>` plays the event at the sender.

#### Paintings

- **Definitions.** `paintings/*.yml` with `texture` (required), `width` and `height` (1 to 16 blocks, default 1), `author` (default `Unknown`), `title` (default: the id; rich text allowed), and `placeable` (joins vanilla's random placement pool).
- **Exact texture size.** The PNG must be exactly 16 pixels per block on each side (MTR-0041).
- **Registration.** On Paper 1.21.6 and newer, variants register through Paper's registry events and nothing is written to the world. On 1.21.4 and 1.21.5 they ship in `materione-content.zip` in the world's `datapacks/` folder. Both paths are restart-required.
- **Check in game.** `/materione dev painting <pack>:<id>` places one variant on the wall you face.

#### Not yet included

- Obfuscation, PackSquash, and pack signing.
- External pack merge (`external_packs/`), optional extra packs, and pack layering.
- Hosting backends other than self-hosted HTTP, such as S3 or R2.
- Proxy pack deduplication and a pre-join send.
- Bedrock pack conversion.
- Animated glyph frame cycling in HUDs (M6) and custom particles (M7).

#### Known limitations

- **Sheet glyphs show only their first cell in text.** Use a `reference` glyph with `index:` to show the other cells.
- **Animated glyphs show frame 1 in text** until frame cycling arrives with M6.
- **Pack-qualified tags resolve only in authored text.** `<glyph:pack:id>` works in item names and lore but stays literal in chat, signs, and books, which accept bare ids only.
- **Anvil renames skip glyph permissions.** Any glyph resolves in an anvil rename regardless of its `permission`.
- **Authored text skips pack acceptance.** Item-name glyphs show the missing-glyph box to players who do not have the pack.
- **Emoji tab completion updates at join.** Emoji added by a reload appear for a player only after that player rejoins.
- **Placeable paintings do not join the vanilla pool on 1.21.6 and newer.** Confirmed by code reading, not yet reproduced in game.
- **Every asset is public.** Each file under `assets/` except `.bbmodel` can be downloaded by anyone with the pack URL. Nothing is obfuscated.
- **The pack listener is plain HTTP.** It has no authentication and no rate limit, and it listens on all interfaces by default.
- **A restart publishes unpublished editor saves.** The first compile after each boot always counts as a change and publishes, so a web-editor save that was never published reaches joining players after a restart.
- **Text effects animate only on clients with pack formats 46-88.** Other clients get a pack without the shader overlay, and the compile warns (MTR-0044).
- **Sound sources are not format-checked.** A non-OGG file passes validation and fails only in the client.
- **The pack description is always "Materione".** `pack.yml` cannot set it.

### Web editor: authoring content

The browser editor (Svelte 5, `webui/`) edits content through the same document operations the server uses. Each studio is generated from the content type's own schema, so the form follows the plugin's definitions. A save sends one revision-checked transaction containing only the changed fields, and fields a studio cannot draw are listed in its footer instead of being rewritten.

#### Content studios

- **Items** - a display card (rich-text name and lore, texture and model slots, a 32 px inventory preview, an enlarged view and a tooltip approximation) plus modules: base material, model style, jukebox song, abilities, and a "When… do…" rules module. Modules come from a searchable picker that offers only mechanics this server registers.
- **Blocks** - the same display + modules layout, with modules for shape (full block, slab, stairs, thin plant, directional plant, custom host), drops, explosion resistance, piston behaviour, placed-by-item, and rules.
- **Furniture** - the same layout, with modules for floating label, rotation snapping (free, 45°, 90°, 180°), hitboxes, seats, light sources, storage (9 to 54 slots), evolution ("Grows into…"), placed-by-item, and rules.
- **Recipes** - kind (shaped, shapeless, furnace), result item, a 3×3 crafting grid for shape and ingredients, and furnace input, experience and cooking time.
- **Glyphs** - basic, sprite sheet (rows and columns), animated (frames) and reference glyphs. The preview draws the texture at its declared height on a checkerboard and shows the codepoint, which the server allocates on save.
- **Sounds** - one event per file: a list of .ogg sources that can each be uploaded or replaced (8 MiB limit), an inline player for the first source, volume, pitch, subtitle and a category dropdown.
- **Paintings** - texture, width and height in blocks (the preview shows the true aspect ratio and pixel size), author, title as rich text, and a placeable flag.
- **Text effects** - rainbow, wave, shake or pulse, with speed, amplitude and rainbow colours, edited through the generic form. Animation can only be checked in game.

#### Creation and templates

- **New dialog** - a name, then Start from: Blank (curated scaffold), A template (searchable cards showing what each template sets and how many resources use it), or An existing one (a lossless copy under the new name).
- **Templates tab** - items and blocks can be based on templates. The tab creates a template for either type ("Used for"), opens it in that type's own studio, and shows which resources use it.
- **Based on** - items and blocks show a bar to change the parent, open it, or detach and keep the values. Inherited fields show "Inherited from"; overrides show "Overrides" with Reset to inherited. Inherited modules appear as dashed cards with Override here.
- **Save as template…** - from an item or block studio or the catalog menu, pick the fields to share; the new template opens in its own studio.

#### Rich text

- **In-place editing** - item and block names, lore, furniture labels, and painting titles and authors edit as Minecraft text, not as code. Obfuscated text flickers while editing and scrambles in previews; both respect reduced motion.
- **Formatting** - bold, italic, underline, strikethrough, obfuscated, the 16 named colours or a custom hex, gradients of 2 to 8 stops, glyph insertion from the pack's glyphs, and clear formatting. Shortcuts: Ctrl+B/I/U, Ctrl+Shift+X, Ctrl+Shift+K, and Ctrl+\ to clear.
- **Legacy codes and pasting** - typed or pasted `&` codes become formatting. Pasted HTML is ignored. Tags the editor does not model (`<shift>`, `<rainbow>`, click tags) are kept as chips and written back unchanged.
- **Font** - previews and the editor use Minecraftia (CC BY-SA 3.0), bundled unmodified.

#### Models and Blockbench

- **Edit in Blockbench** - an item or block model opens the pinned, unmodified Blockbench 5.1.6 build as a workbench over that entry. The entry's .bbmodel is reopened, or a starter Java model is created with its texture attached. It requires a live connection.
- **Save & apply** - writes the Java model JSON, its textures, the optional .bbmodel source, the model field and a cached preview thumbnail in one transaction. The save is refused if the entry changed while the workbench was open.
- **Import .bbmodel** - the standalone Blockbench tab under Files imports an external project and saves it as assets only, as a new item, or wired onto an existing item or block.
- **Bridge** - a script injected into the Blockbench frame exchanges messages with the editor over postMessage and exports the Java block/item model.

#### Textures, assets and previews

- **Pixel editor** - pencil, eraser, fill, pick colour, line, rectangle (optionally filled), crop with aspect locks, vertical mirror, zoom and pan, pixel grid, PNG import and export, canvas resize to 1 to 1024 px, and 40 steps of undo. Opens from a texture slot as Draw or Edit.
- **Assets workspace** - textures, models and sounds, with search and a kind filter. Selecting one lists every user and field. Replace everywhere retargets all users in one previewed bulk change; Give some users a copy uploads the copy first, then retargets the chosen users.
- **Uploads** - texture and model files up to 2 MiB; audio up to 8 MiB. A missing texture gets a repair panel with Draw it now and Choose another texture.
- **Live previews** - items, blocks and furniture with a model show an inventory-style render drawn in the browser. Other entries show their texture or a cached model thumbnail. Items without a custom visual show the vanilla icon for their material.
- **Bulk changes** - mark rows by dragging, with Space or Ctrl/Cmd+A, then Edit N marked…: pick a field, preview, and apply to every marked document together or not at all. Make variants… creates one resource per line ("Name" or "Name = value"), optionally with a recipe each. Create recipe builds a recipe whose result is the item.
- **Inline diagnostics** - field issues appear under the field with their code. Problems entries that name a resource open the studio at that field. A footer lists fields that only Advanced Files can edit.
- **Spatial editor** - seats, hitboxes and lights are dragged or nudged with arrow keys (5 cm, or 25 cm with Shift) on front and top views.

#### Not yet included

- Menus, dialogs and HUDs (planned for a later release). A disabled "GUIs" tab marks where they will appear.
- Component modules such as attack damage, glint and tint. The components block stays under Advanced and warns instead of applying.
- A model overlay in the spatial editor, and a light theme.

#### Known limitations

- **Recipe studio is shaped-first.** The 3×3 grid appears for every kind. Editing the grid on a shapeless recipe overwrites its ingredient list with a shaped map, so the recipe stops compiling. Furnace recipes show an empty grid, and recipes have no result preview.
- **Painting textures need a manual path.** A new "Draw" proposes paintings/<id>, and picker uploads land at assets/textures/<file>, but painting references resolve under textures/painting/. Until fixed, upload the PNG to assets/textures/painting/ from Files and reference it as painting/<name>.
- **JPEG textures do not work.** The picker accepts .jpg and .jpeg, but the reference drops the extension, so the studio reports the texture as missing. Use PNG.
- **Clearing a number field saves 0**, clamped to the declared range. Clearing a sound's volume silences it.
- **Sound jukebox section is Advanced-only.** Edit its description, duration and comparator output in Files; the item's jukebox song field points at the sound.
- **Templates for items and blocks only.** The editor has no template control for furniture, glyphs, sounds, paintings or text effects.
- **Previews are approximations.** The model view is a fixed orthographic GUI render with no camera. Vanilla textures show as grey checkers. Only cube, cross, slab, stair and flat item shapes draw live; other models fall back to the cached thumbnail.
- **Text is approximate.** The tooltip uses a Minecraft-style look-alike font. Shader effects (dotted underline), obfuscation and text widths need an in-game check. The glyph preview shows frame 1 only.
- **Text effects have no in-browser preview.**
- **Model save-back exports only the Java block/item format.** The item workbench cannot import another .bbmodel; use the Files tab. Model editing needs a live connection.
- **Pixel editor is single-layer** and has no copy or paste of selections.
- **Paintings and jukebox songs apply at restart.** The editor shows this as the MTR-0028 diagnostic in Problems, not as a banner beside the studio.
- **Vanilla icons need internet access** (jsDelivr). Offline editors show placeholders.

### Web editor: shell and workflow

The web editor is a workbench in the browser: a navigation rail, one full-area view at a time, and an optional Activity panel. A save writes the server's files and requests a reload. The browser keeps only unsaved drafts, and only in the tab's memory.

#### Navigation and layout

- **Rail**: an Authoring group (Start, Content, Assets, Files) and a Support group (Problems, Activity, Server). Labels collapse to icons, Problems and Activity show error counts, and Content opens first. Visited views stay mounted, so switching away does not reset an open studio.
- **Command palette (Ctrl/Cmd+K)**: go to a view, start a new resource of any enabled type, open a resource or asset of the active project, or jump to a field of the open resource. Shows up to 12 matches and has no visible button.
- **View Demo** (cloud landing page): the same editor against a recorded snapshot in the tab. Nothing is saved, and a reload starts over.

#### Content catalog

- **Type tabs**: one per content type the server registers (items, blocks, furniture, recipes, glyphs, sounds, paintings, text effects). Templates appears when a type can use one. GUIs shows as a disabled "soon" tab.
- **Rows**: one row per resource, with its name, material or summary, health, and a thumbnail when one exists. Resources that share a file each get a row. Compact grid is the default, with a dense list available; the choice is per type and remembered in this browser.
- **Search and folders**: search is case-insensitive and covers the whole type tab. It matches IDs, file paths, names and materials, and shows each match's folder. Folders can be created, renamed, moved and deleted, with breadcrumbs, Back and Up.
- **Keyboard**: arrows, Home and End move; Enter or double-click opens; F2 renames; Delete removes; Space marks; Ctrl/Cmd+A marks everything shown; typing jumps to a name; Backspace goes up a folder.
- **Context menu and bulk changes**: right-click offers open, rename, duplicate, make variants, create recipe (items), mark, move, reveal in Files, and delete. Marked rows can take one field change (or a removal), previewed before it applies to all of them.
- **New**: enter a name, then start from a blank scaffold, a template, or a copy of an existing resource. The ID is derived from the name.

#### Files and assets

- **Files**: the pack tree, with a filter. Documents open as forms, with raw YAML under Advanced. Switching between form and YAML requires Save & apply or a discard first.
- **Tabs and right-click**: open documents, asset previews, settings and the Blockbench workbench share one tab strip; unsaved tabs show a dot and ask before closing. Right-click offers New Item, Block, Furniture or Recipe scaffolds in a folder, Upload here for asset folders, and Open, Rename / Move and Delete for files.
- **Assets**: textures, models and sounds of the active project, with search and a kind filter. Selecting one lists every user (Used by). Replace everywhere and Give some users a copy are both previewed; the retarget applies all or nothing.

#### Projects and publishing

- **Projects**: the header lists the server's projects. New project asks for an ID of 2 to 32 characters (a-z, 0-9, _ and -).
- **Publish pack**: the header shows Not published, Candidate available or Published, with short SHA-256 hashes. Publish releases exactly the candidate shown; if a newer candidate has replaced it, the publish is refused.
- **Save & apply** (also Ctrl/Cmd+S): form saves send only changed fields, in one revision-checked write per document; unchanged documents are not written. Success means the files were saved and a reload was requested. The runtime result arrives in Activity as the latest server-wide reload.
- **Server view**: server version, protocol and listener state; full reload or reload of one project; settings.yml as raw YAML with a validated save and a restart note; disconnect.

#### Saving, drafts and conflicts

- **Drafts**: unsaved edits for up to 32 documents stay in tab memory across views and survive a dropped connection. They are not stored in browser storage, so a reload or closed tab discards them, and the browser warns before either.
- **Changed on disk**: a hand edit or git pull refreshes clean open tabs by itself. Tabs with unsaved edits keep them and show a changed-on-disk banner with discard and reload.
- **Conflicts**: a save based on an out-of-date file is refused and the draft is kept, never written over the newer file. Resolving it means discarding the draft and reloading, after a confirmation.
- **Reconnect**: drafts stay and a banner shows. Reconnecting to the same server re-reads the files, keeps non-overlapping pending fields as pending, and blocks overlapping ones. A different server is refused. A save whose outcome is unknown is never replayed; Reconcile server files checks it against the files.

#### Problems, Activity and audit

- **Problems**: a live feed of diagnostics (up to 200), filterable to errors or warnings. A file location opens that file in Files at the reported line and column. Fix opens the studio on the named field.
- **Start**: lists up to six errors or warnings that name a resource, your unsaved drafts, the types you can create, and the abilities and rule events this server offers.
- **Activity**: a log of saves, reloads and errors (up to 200 entries). It is hidden until opened; Clear or a disconnect empties it.
- **Audit trail**: the Server view lists the last 100 server audit entries (time, operation, target), filterable by operation, target or actor.

#### Look, motion and accessibility

- **Identity**: dark only. Navy surfaces, an orange accent, Inter for the interface, Minecraftia only in game-style text previews, and one icon set (Lucide).
- **Focus and names**: a visible 2 px focus ring; icon-only buttons carry names; status messages use live regions.
- **Reduced motion**: the operating system's reduced-motion setting turns off every transition and loop. Otherwise motion is short (120 to 320 ms) fades, dialog scaling and row moves; lists over 200 rows do not animate.
- **Shortcuts and browsers**: Ctrl/Cmd+K (palette), Ctrl/Cmd+S (save), Escape (cancel a dialog, or close a clean studio), Ctrl/Cmd+Z (undo in the pixel and rich-text editors). Supported: Chrome 133+, Safari 17+, Firefox 129+, over HTTPS or on localhost.

#### Not yet included

- A light theme (not designed yet).
- Server-side paging or filtering of resource lists for large projects (planned with relay mode).
- A "newer editor available" hint when a cached editor is older than the plugin.
- GUI content types (menus, dialogs, HUDs), the GUIs tab, planned for M6.

#### Known limitations

- **Dark theme only**, with no switch to the system theme.
- **Lists do not announce keyboard moves**: Content and Assets rows are not focusable and do not use aria-activedescendant, so screen readers may not follow arrow-key selection.
- **Dialogs and menus**: dialogs do not trap Tab or return focus; the right-click menu closes on click or blur only, not Escape, and cannot be opened from the keyboard.
- **Disconnected actions fail raw**: while disconnected, Content's create, rename, move, duplicate and delete, and the header's New project, stay enabled and show raw browser error text. Save, Reload and studio controls check the connection; these do not.
- **No request timeout**: a stalled but open connection leaves a save at "Saving..." until the connection is closed.
- **No browser feature check**: an unsupported browser fails the handshake with the raw WebCrypto error. The browser test suite runs in Chromium only.
- **Project switch**: switching projects does not prompt about unsaved Files drafts in the project you leave. Start lists unsaved changes for the active project only.
- **Undo** exists only in the rich-text and pixel editors and for reset-to-inherited. Other form edits can only be discarded before saving.
- **Delete** uses the browser's confirm dialog. There is no trash, no restore and no revision history browser.
- **Files** opens only PNG and JPEG assets on click (sounds and models do nothing), and its New menu covers items, blocks, furniture and recipes only.
- **Projects** cannot be renamed or deleted in the UI. Duplicate and Make variants apply only to single-resource documents, and bulk changes are capped at 50 documents.
- **Large projects** load in full into the browser; search and sorting run on the client.
- **Vanilla item icons** load from jsDelivr at runtime and show a placeholder when offline.
- **Memory only**: Activity, Problems and drafts are lost on reload. The audit trail loads when the Server view first opens; use refresh for newer entries.

### Editor connectivity, security and the cloud

The web editor reaches your server in one of two ways. The default is the **cloud relay**: `/materione editor` prints a link to the hosted editor at `https://editor.materione.com`, and the server exchanges encrypted messages with it over outbound HTTPS, so no inbound port is opened for the editor. The **local listener** is the alternative: an embedded web server on your machine, bound to loopback by default, that serves the same editor from the plugin jar. Both transports carry the same end-to-end encrypted protocol, and every browser must be approved in game before it can edit.

#### Opening the editor

- **`/materione editor`** opens a session in the configured mode (cloud, unless `editor_mode: local`) and prints a clickable link. Needs `materione.editor`, which operators have by default.
- **`/materione editor local`** opens the local listener regardless of mode. Needs `materione.editor`.
- **Approval prompt** goes to the console and to every online player with `materione.admin`: `[Approve]`, `[Approve & trust this browser]`, `[Deny]`. It expires after 120 seconds. The commands are `/materione editor approve <nonce> [trust]` and `/materione editor deny <nonce>`.
- **Other subcommands**: `status` shows transport state and needs `materione.editor`. `revoke` closes every session; `revoke <prefix>` or `revoke workstations` forgets trusted browsers; `stop` shuts the transport down. Revoke and stop need `materione.admin`.
- **Link lifetime**: a cloud link must be opened within one hour. Each link serves one browser connection; run `/materione editor` again to reconnect. A cloud session ends 12 hours after it was created, even while in use.
- **Fallback**: if the relay cannot be reached, `/materione editor` uses the local listener when it is running and reports `MTR-0050`.

#### Configuration

Settings live in `plugins/Materione/settings/settings.yml`. Editor keys are read at startup, so restart after changing them.

| Key | Default | Meaning |
|---|---|---|
| `editor_enabled` | `true` | Master switch for both transports. |
| `editor_mode` | `cloud` | `local` makes bare `/materione editor` use the listener; any other value means cloud. |
| `relay_base_url` | `https://editor.materione.com` | Relay origin for the API and the editor page. Point it at a self-hosted relay to change it. |
| `editor_host` | `127.0.0.1` | Listener bind address. Any non-loopback address logs `MTR-0036`. |
| `editor_port` | `8089` | Listener port. If busy, the next eight ports are tried. |
| `editor_file_watcher` | `true` | Streams hand edits on disk into open editors. |
| `editor_public_host` | empty | Address the listener advertises to the cloud editor for the same-machine shortcut. |

#### Transports

- **Cloud relay**: the server long-polls a stateless Cloudflare Worker backed by a D1 database. The same host serves the editor page. Session blobs and messages are encrypted before they leave the server.
- **Local listener**: serves the editor from the jar on `editor_host:editor_port`. While it runs, the cloud editor tries it first using the address carried in the session. By default that address is `127.0.0.1`, so the shortcut only helps a browser on the server machine.
- **Browsers**: Chrome or Edge 133 or newer, Firefox 129 or newer, Safari 17 or newer.

#### Security

- **Capability links**: the part after `#` is a random 256-bit wrapping key. Browsers do not send fragments to servers, so the relay never receives it. The code before it identifies the encrypted blob and is useless without the key.
- **Encryption and signatures**: after the handshake, every message is encrypted with AES-256-GCM, signed with Ed25519, and carries a sequence number that rejects replays, gaps and reordering. Keys are agreed with ECDH P-256 and derived with HKDF-SHA256.
- **Approval is authorization**: an approved browser can edit content, write settings, reload and publish packs. Approve only browsers you would trust with those rights.
- **Server identity**: the server keeps an Ed25519 identity in `plugins/Materione/storage/editor-identity.json`. Keep that file private. A browser that reconnects to a different server is refused, and its drafts are kept.
- **Remote access**: browsers run the editor's crypto only on `localhost` or HTTPS, so access from other machines needs a TLS reverse proxy. No setup guide exists yet.

#### Privacy

- **Local mode** sends no editing traffic beyond your own machine.
- **Stored by the relay**: encrypted session blobs (one hour), channel records with hashed tokens (up to 12 hours), and encrypted messages (up to about six hours, or until the next hourly cleanup after the channel closes).
- **Visible to the relay operator**: the handshake is not encrypted. It carries the session ID, public keys, a nonce, and a browser label (the user-agent text after its last parenthesis, up to 40 characters). The relay also sees client IP addresses, timing and message sizes. Request logs can include URL paths and query strings, which contain store codes and channel IDs. These are capabilities, not keys.
- **Never sent to the cloud**: the wrapping key and the server's private keys.
- **Log warning**: the `MATERIONE-EDITOR <url>` line in the server log contains the full link, key included. Treat `logs/latest.log` as sensitive while a session is open.
- **Browser**: vanilla item icons load from `cdn.jsdelivr.net`. The session link is removed from the address bar once it loads, and drafts stay in browser memory.

#### Not yet included

- Chunked asset uploads through the payload store, which would let large assets travel through the relay.
- Per-permission checks inside editor sessions, including a `materione.edit.<pack>` node.

#### Known limitations

- **Shared relay capacity**: the shared free relay has global caps of 20 live channels, 2 GB of stored blobs, and daily upload and write limits. Rate limits apply per IP. Channel creation and blob upload need no account, so anyone can reach these caps. Cloud sessions then fail over to the local listener or report an error.
- **Asset size in cloud mode**: each message must fit within 1 MiB after encryption, so one asset upload is limited to roughly 575 KiB. Larger uploads fail, and an oversized message can end the cloud session. Local mode accepts assets up to 2 MiB (8 MiB for `.ogg` sounds).
- **No automatic reconnect**: a transport error ends a cloud session. Run `/materione editor` again.
- **Trust this browser does not persist**: the editor creates a new key for each connection, so the approval prompt returns every time.
- **Stop does not close cloud sessions**: after `/materione editor stop`, cloud sessions can still be opened until the server restarts.
- **Self-hosting needs edits**: `cloud/editor-worker/wrangler.jsonc` contains the project's D1 database ID and route, which must be replaced for another account.
- **File watcher depends on the listener**: `editor_file_watcher` only starts when the local listener bound successfully, so a cloud-only server loses the external-edit feed if the listener fails to bind.

### Migration, integrations and compatibility

v0.1.0 ships no migration importers and one third-party integration: a reflective PlaceholderAPI hook for action text. The platform floor is Paper 1.21.4. Folia support is declared but unverified, and the 26.x line is verified locally rather than in CI. Check both on your own server before relying on them.

#### Importers

- **No importers ship in 0.1.0.** The `importers` module is an empty skeleton and is not on the plugin's classpath. No `/materione` subcommand imports anything.
- **Planned for M7.** ItemsAdder first, then Oraxen, then Nexo. Converters are designed to write the native pack layout (architecture section 7.2), so converted content would be handled like native content.
- **Manual move today.** Re-author content under `plugins/Materione/content/packs/<id>/` with a `pack.yml` and the typed content directories. Only those directories are read, so an incumbent's `configs/` folder is ignored.
- **Not an importer.** The editor's `.bbmodel` import loads a Blockbench project into the embedded editor. It does not convert incumbent content.

#### Integrations

- **PlaceholderAPI (soft hook).** Resolved by reflection, so Materione has no hard dependency on it. It expands placeholders in action text such as commands, messages and sounds. Without PlaceholderAPI, the `%` tokens pass through unchanged. No automated test covers the hook.
- **Geyser and ViaVersion.** Presence is detected and shown in `/materione doctor`. Neither changes behavior.
- **Folia.** Detected from server classes. See Compatibility.
- **Bundled libraries.** team.unnamed creative (the resource-pack emitter, MIT), its gson, and the editor's Undertow listener are shaded and relocated under `materione.libs.*`.
- **Not in 0.1.0.** ModelEngine, MythicMobs and MythicCrucible, MMOItems, Vault and economy, BlockLocker and other protection plugins, and anti-cheat bridges. No code exists for any of them; all are planned for M7.

#### Compatibility

| Platform or component | 0.1.0 status |
|---|---|
| Paper 1.21.0 to 1.21.3 | Refused. The plugin disables itself at boot (MTR-0001). |
| Paper 1.21.4 (build 232) | Supported. The only server version CI boots. |
| Paper 1.21.5 | Accepted by the probe. Paintings and songs use the world-datapack path. Not in CI. |
| Paper 1.21.6 to 1.21.11 | Accepted by the probe. Paintings and songs should use the registry-event path, detected at runtime. Not in CI. |
| Paper 26.1.x | Built against paper-api 26.1.2. Exercised in-game on 26.1.2 by the local QA suite only, not CI. |
| Paper 26.2 | Listed in the pack-format table (format 88). No server test found. |
| Folia | Declared (`folia-supported: true`). Scheduling uses Paper's region, entity and async schedulers. No Folia boot test exists. |
| Spigot, CraftBukkit | Cannot load the jar. It contains only `paper-plugin.yml`, not `plugin.yml`. |
| Other Paper forks | Not tested. |
| Java | 21 for plugin bytecode and toolchain. The 26.x seam module builds with a JVM 25 toolchain, since Paper 26.x needs Java 25. |
| Resource-pack clients | Pack declares formats 46 to 88 (1.21.4 through 26.2). Text-effect shaders are version-matched for formats 46-62, 63-83, 84-87 and 88. Other formats get a shader-free pack. |
| Geyser and Bedrock | No Bedrock behavior. Bedrock conversion is planned for M8. |
| ViaVersion | Detected and reported only. |

#### Known limitations

- **No migration.** No ItemsAdder, Oraxen or Nexo converter ships. Incumbent YAML is not read as Materione content.
- **Folia unverified.** The flag is declared, but no Folia boot test exists. Test on a Folia server before relying on it.
- **CI covers one server.** Paper 1.21.4 build 232 is the only server version CI boots. 26.x behavior is checked only by the local probe and QA suite.
- **PlaceholderAPI is narrow and untested.** It expands placeholders in action text only. The plugin declares no dependency on it, and no test covers it.
- **Older clients are not covered.** The pack's range starts at format 46 (1.21.4). Clients on older formats fall outside it, whatever ViaVersion does.
- **No Bedrock support.** Geyser is detected, not supported.
- **No protection, mob, economy or anti-cheat hooks.** All are planned for M7.
- **HUD and menu folders are reserved.** `menus/`, `dialogs/` and `huds/` are discovered by the pack layout, but nothing in the runtime reads them until M6.

### For developers: public API, build, and tooling

Materione is a Gradle multi-module Java 21 project: a Paper plugin, an `api` module that is the only public surface for other plugins (it depends on no internal module), a headless-Paper test harness, and an embedded web editor built with Node. Nothing is published to a Maven repository in 0.1.0; the release artifact is the shaded plugin jar, which contains the `api` classes.

#### Public API

- **Entry point.** `MaterioneAPI` is registered with Bukkit's ServicesManager during startup. Addons get it with `getServer().getServicesManager().load(MaterioneAPI.class)`.
- **Sub-APIs.** `authoring()` (28 operations on canonical pack files), `assets()`, `pipeline()` (reload, apply report, quarantine), `delivery()`, `runtime()` (catalog queries and world operations), `admin()` (doctor report, settings, audit, editor sessions), plus `registries()`, `diagnostics()`, `platform()`, `scheduling()` and `mechanics()`.
- **Actors and results.** Most mutating calls take an `ActorContext` (player, console, editor session, addon or MCP) and return `ApiResult<T>`. Domain errors come back as `MTR-####` diagnostics instead of exceptions.
- **Addon mechanics.** `api.mechanics().register(owner, id, factory)` opens a bounded window for a namespaced `Mechanic` factory that can contribute ECA rules, native listeners and item modifiers. The walkthrough is in `docs/architecture/mechanics.md`; an API-only example is compiled by `MechanicExtensionContractTest`.
- **Events and diagnostics.** Subscriptions exist for reloads (`pipeline().subscribeReloads`), document changes (`authoring().subscribeChanges`) and diagnostics (`diagnostics().subscribe`). Addons can publish diagnostics through the same bus.
- **Platform and scheduling.** `platform()` describes the Minecraft version, Folia, Geyser and ViaVersion presence, and `PlatformCapability` flags. `scheduling()` is the thread-hop gateway (region, entity, async and global executors).
- **Content SPI.** `ContentType`, `PackEmitter` and `Allocations` live in `dev.materione.api.content`, but no public call registers a new content type (see Known limitations).

#### Building from source

- **Requirements.** JDK 21 (Gradle's foojay resolver provisions Temurin if none is found). The Gradle 9.7.1 wrapper is committed. Node 22.17.0 and the editor's npm packages are provisioned by Gradle for `:webui`.
- **Build and unit tests.** `./gradlew build` runs unit tests, the ArchUnit rules, and the editor's Vitest and svelte-check steps.
- **Plugin jar.** `./gradlew :plugin:shadowJar` writes `plugin/build/libs/Materione-<version>-SNAPSHOT.jar`. Add `-Prelease` for the unsuffixed release jar.
- **Paper integration.** `./gradlew :harness:integrationTest` downloads the pinned Paper 1.21.4 build 232 once (sha256-verified, about 50 MB) and boots the shaded jar.
- **Browser tier.** Run `./gradlew :webui:installPlaywrightChromium` once, then `./gradlew :webui:browserTest`.

#### Quality & testing

- **Boundary rules.** Nine ArchUnit ban rules (R1–R9, in `testing/src/testFixtures`) run in 11 test classes, with a whole-jar pass in `plugin`. A violation fails the build.
- **Unit and golden tests.** 598 JUnit 5 `@Test` methods. `fixtures/` is a grow-only corpus of 167 tracked files; golden output is regenerated only with `-Dmaterione.golden.regen=true`, and the diff is reviewed.
- **Fault and budget tests.** `StagePairFaultInjectionTest` and `ContentPerfBudgetTest` in `plugin/` cover stage-boundary faults and the performance budgets in `docs/architecture/plugin.md` §9.
- **Headless Paper.** Five `@Tag("integration")` classes boot Paper 1.21.4 with the shaded jar and assert the stable `MATERIONE-*` log markers documented in `harness/README.md`.
- **Browser and worker tests.** 15 Playwright specs run the editor in Chromium against a deterministic RPC fixture; 35 Vitest files cover editor logic and protocol fixtures; the relay worker's tests run on the Workers runtime with local D1.
- **In-game QA catalog.** `qa/catalog` holds 96 tests in 13 suites. The committed ledger records passing results for 46 of them. CI checks catalog integrity and ledger drift; in-game runs are local (`qa/README.md`).
- **Agent probe loop.** `scripts/probe/probe.mjs` boots a Paper 26.1.2 server and a windowed Minecraft client so a developer or agent can capture screenshots. It is local-only and needs JDK 25, Node 22 and a local Minecraft install (`docs/probe.md`).
- **CI.** `ci.yml` runs on pushes to main, on pull requests, nightly and on demand. It covers build, the QA check, the Paper boot, the relay worker, the browser suite, and a non-blocking Windows canary (nightly or manual). `release.yml` drafts a GitHub release from a `v*` tag; `deploy.yml` is manual only.

#### Contributing & license

- **License.** Apache License 2.0 (`LICENSE`). `NOTICE` names the copyright holder.
- **Third-party code.** The jar bundles third-party Java libraries (creative, Gson, Undertow with its XNIO and JBoss/WildFly dependencies, and Adventure), all MIT or Apache-2.0 upstream. The web editor serves an unmodified Blockbench v5.1.6 build, which is GPL-3.0. A consolidated third-party notices file is not included yet.
- **Contributions.** `CONTRIBUTING.md` asks for focused pull requests with tests and docs. The PR template requires agreeing to `CLA.md` and to Apache-2.0 compatibility. CLA acceptance happens by opening a pull request; there is no CLA bot.
- **Workflow.** Work is tracked as GitHub issues under milestones, one branch per issue, and PRs reference the issue (`Closes #N`). `CLAUDE.md` holds the repository rules, including decisions D1–D11 in `docs/architecture.md`.

#### Known limitations

- **API stability.** The API is 0.x and carries no compatibility guarantee. The package docs describe it as semver'd with deprecate-then-remove, but nothing enforces that yet: there is no binary-compatibility check and no `@Deprecated` marker.
- **Addon registration.** Only mechanics have a public addon registration path. `ContentType` is in the API but nothing public registers one, and the ECA action, condition and delivery-backend registry keys are declared without any registrations.
- **Diagnostic codes.** `DiagnosticCodes` defines 51 codes, and 24 of them appear in no document. Addons publish into the same `MTR-####` space with no reserved range.
- **Distribution.** The API is not on a Maven repository, and no Javadoc or sources jar is published. Addons compile against the plugin jar.
- **Not yet included.** The MCP server, the addon SDK guide, the expression-language reference, and the schema and migration reference (listed as still to be written in `docs/architecture/README.md`).

[0.1.0]: https://github.com/xef5000/materione/releases/tag/v0.1.0
