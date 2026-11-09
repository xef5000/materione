# GUI builder — design-first v1 (M6)

> **Scope.** The `gui` content type, its component registry, how a layered design compiles
> into a vanilla container screen, the runtime that opens it, and the editor studio that
> makes designing it feel like a small, Minecraft-specialised Figma/Photoshop. It refines
> [`runtime-systems.md`](runtime-systems.md) §8.1 (the menu model) and owns the M6 roadmap
> bullet "menu builder". **Logic is out of v1**: click bindings, pagination and search are
> layered on later through the same component seam (§8) — v1 makes *designing* excellent.
>
> Traceability: files canonical (D1) — a GUI is a YAML file plus PNG assets, nothing else;
> one pipeline (D6) — the studio writes files, the pipeline compiles and applies them;
> registries with self-description (D5) — components are a registry, the studio's inspector
> is derived from it; fail-soft — a bad layer quarantines that layer, never the server.

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **GUI** | One `guis/**/*.yml` resource: a container screen design. Its `container:` names the profile it is drawn on: a chest of 1–6 rows, a hopper or a dispenser in v1 (§12). |
| **Canvas** | The full container screen in GUI pixels: `176 × (114 + rows·18)` — top container *and* the player-inventory area, exactly the area of the vanilla screen. *Replaced by the open canvas of §11 (#274): the compiler (#276) flattens over the art cap, so art may extend past the screen; the studio (#277) and the starter GUI (#278) follow.* The `176 × (114 + rows·18)` is the chest's; the container profile (§12.5.1) states the screen size of every container (a hopper's is 176 × 133, a dispenser's 176 × 166). |
| **Layer** | One entry in the GUI's ordered `layers:` list (bottom → top). Every layer is an instance of one component. |
| **Component** | A registered layer kind (`gui-components` registry): `panel`, `slots`, `image`, `paint`, `button`, `text`, `item` in v1. Addons register more through the same path. |
| **Render target** | Where a component's output physically lands in the client: the **raster** background, the **title text**, or a container **slot** (§4). |

---

## 2. File format

```yaml
# content/packs/<pack>/guis/shop.yml
shop:                          # one resource per key (master §7.2); the key is the GUI id
  title: Shop                  # accessibility/fallback title (narration, title-less clients)
  container: chest_6           # a container profile (§12.4): chest_1 .. chest_6, hopper or dispenser; a chest of 6 rows is chest_6
  layers:                      # bottom → top
    - id: frame                # stable, unique within the GUI; [a-z0-9_]+
      type: panel              # component name in the gui-components registry
      name: Frame              # optional display name in the layers panel
      x: 0
      y: 0
      width: 176
      height: 222
      corners: vanilla         # square (absent: the 1-px cut of earlier panels) | vanilla (the chest's corners)
    - id: wells
      type: slots
      region: container        # container | player | all
    - id: art
      type: paint              # pixel layer, backed by a canvas-sized PNG asset
      image: gui/shop/main/art # textures/ implied, like every other texture reference
      opacity: 100
    - id: buy
      type: button
      x: 115
      y: 70
      width: 52
      height: 20
      style: { fill: "#C6C6C6", highlight: "#FFFFFF", shadow: "#555555", outline: "#000000" }
    - id: heading
      type: text
      x: 8
      y: 6
      text: "Shop"
      font: smallcaps          # smallcaps | default
      color: "#404040"
    - id: sword
      type: item
      slot: 13
      item: shop:gui/main/sword # a Materione item or a vanilla material (minecraft:diamond_sword)
      amount: 1
```

Common layer fields, owned by the gui type and not by components: `id`, `type`, `name`,
`visible` (default `true`; hidden layers are kept in the file but not compiled), `locked`
(editor-only affordance, preserved), `opacity` (0–100, raster components only), and `visible_if`
(a condition that shows supported item, raster or text layers per render, logic.md §6.3–§6.4).
Everything else belongs to the component's schema.

Raster layers also accept `materione:overlay_frames: {every: 4, layers: [{x: 0}, {x: 8}]}`, where each
entry patches component fields over that layer and `every` is a positive tick count. Dynamic
visuals allocate a glyph for each raster frame and assemble raster first, then text, in authored
order within each target, matching static compilation and the studio. The namespaced field is
an opt-in; a component declaring that field retains it, and its own `frames` is untouched.
The authoring preview displays the first raster frame without evaluating viewer conditions;
the live title is per viewer. Conditional or animated input slots are rejected.

**Item layers can be dynamic (logic.md §6.3, §24, #216).** An `item` layer takes `item:` (a plain
id, a template such as `${entry.item}`, or one named source such as `head: {player: "${player.name}"}`
or `player_slot: main_hand`) or `frames: {every, items}` for an animation. Its `name` (the item's display
name), `lore` with `lore_mode`, `amount` and `glint` are templates. A GUI may set `refresh: <ticks>`, the
clock that re-renders its dynamic layers; `gui.refresh` and reloads always update the window. A layer
with a condition, a template, a viewer-bound source or an animation is dynamic; the rest are resolved
once per window. Unknown fields are preserved losslessly (master
§5.6); an unknown `type` quarantines that one layer (`MTR-` code assigned by the
implementing PR, documented in plugin.md §5) and the rest of the GUI still compiles.

**Customized items live next to the GUI as ordinary items.** When the studio customizes an
item layer's appearance, it creates a normal item resource at
`items/gui/<gui-path>/<layer-id>.yml` (a vanilla base becomes `material:`; a Materione base is
**linked**: the new item is `template: <base>` and nothing else, so it follows the base and overrides only
what the designer changes there, plugin.md §3.1) and points the layer's `item:` at it. There is no
"GUI-local item" concept: the item type, its studio, its compile path and its auto-updater are reused as
they are (D1, D5).

**Paint layers own one PNG** at `textures/<image>.png`, RGBA, holding exactly the layer's painted pixels: the layer
carries `x`, `y`, `width` and `height` like an `image` (§11.2), and a layer with no painted pixel has `width: 0`,
`height: 0` and no file. The studio writes it through the asset store; the compiler reads it like any other texture.
(Layers from before the open canvas have no box and a canvas-sized PNG; the studio opens them and trims them on the next save.)

**Logic lives beside the layers.** A GUI resource may also carry `behavior:` (rules fired by
`gui.opening`, `gui.open`, `gui.close`, `gui.click`, `gui.click_outside` and `gui.tick`) and `flows:`
(named flows the rules call), exactly as an item does. They are part of the gui type's
self-description and are preserved losslessly; the layer editor never rewrites them. The model is
[`logic.md`](logic.md) §6 and §21.

---

## 3. Abstractions — what lives where

```
api/   dev.materione.api.gui      GuiComponent (SPI), GuiComponentConfiguration,
                                  RenderTarget, RasterCanvas, TitleSink, SlotSink,
                                  TextureSource (compile-time pixels), RegistryKeys.GUI_COMPONENTS
content/ dev.materione.content.gui GuiType (ContentType), GuiDefinition, GuiLayer,
                                  built-in components (Panel/Slots/Image/Paint/Button/Text/Item)
content/ dev.materione.content.gui.raster
                                  ArgbCanvas (RasterCanvas impl), Bevel painter, PngEncoder
plugin/  dev.materione.plugin.gui  GuiRuntime (opens/refreshes on the PluginGui kit),
                                  RuntimeGuis view, /materione gui command
webui/   src/lib/gui/             document model, history, TS raster (mirror), studio views
fixtures/gui/                     golden YAML, golden pack output, shared raster vectors
```

Why these cuts:

- **Components are an API SPI, not a sealed hierarchy.** The component list is explicitly
  non-exhaustive; addons will want their own (a progress bar, a player head grid). The SPI
  mirrors `Mechanic` (mechanics.md): `name()`, `describe()` → `SelfDescription` (the studio
  builds the inspector from it — no editor-side component list, same rule as item modules),
  `target()`, and `parse(GuiComponentConfiguration)` → an immutable, pure compiled layer
  (`IllegalArgumentException` rejects, the pipeline diagnoses and skips that layer).
- **Compiled layers render through narrow sinks, never through Bukkit or the pack emitter
  directly.** `RasterCanvas` (pixel ops: blit, fill, bevel, alpha-composite, all in integer
  GUI pixels), `TitleSink` (positioned styled text runs), `SlotSink` (slot → item reference +
  amount). The gui type owns ordering, flattening, glyph allocation and font emission;
  components only describe their output. This keeps components testable without a server
  and keeps the version-sensitive parts (fonts, shifts, inventories) in one place.
- **`api` gets interfaces only**; implementations (`ArgbCanvas`, the PNG encoder, the title
  assembler) live in `content` and stay Bukkit-free so the compiler and goldens run headless
  (ban rules unchanged; no new module).
- **The runtime does not know about components.** It receives a compiled `RuntimeGui`
  (title component + slot contents + container profile) from the shadow swap and renders it through the
  existing `PluginGui` kit (runtime-systems §8.1). Adding a component never touches
  `plugin/`.
- **The studio does not invent a model.** It edits the same `layers:` list, gets component
  schemas from the registry description, and saves through the authoring API (§7).

---

## 4. Compilation — from layers to a screen

A vanilla container screen offers three surfaces a server can drive, and every component
targets exactly one:

| Target | Physical surface | Components (v1) |
|---|---|---|
| `RASTER` | Pixels baked into a background bitmap glyph placed in the **title** with shift tags, covering the vanilla screen texture | `panel`, `slots`, `image`, `paint`, `button` |
| `TEXT` | Styled title text positioned with shift tags and per-offset fonts | `text` |
| `SLOT` | A real item in a container slot | `item`; `list` (a page of entries per viewer, logic.md §6.7, §28) |

**Z-order is honored within a target and fixed across targets** by the client's draw order
(raster under text; slot items relative to the title is the open question in §9). The studio
preview renders the *compiled* order, not the list order, and badges a layer whose list
position cannot be honored — what you see is what the player gets.

Steps (in `GuiCompiler.compile`, deterministic per D7; `GuiType.compile` and the runtime call
the same function, so the pack and the running server cannot disagree):

1. **Flatten raster layers** bottom → top (`GuiFlatten.flatten`, §11). Each visible raster layer
   paints at its authored position onto its own transparent canvas covering the **art cap**
   (`x −424..599`, `y −243..780`, §11.2); that canvas is source-over composited onto the output
   with the layer's `opacity` (a group opacity, so a layer's own pixels never blend with each
   other). Pixel rules are below. Hidden layers are skipped. A layer whose component throws (a
   missing texture, say) draws nothing and is reported as `MTR-0052`; a layer that paints past
   the cap is clipped by it and reported as `MTR-0090`; the others still draw. A GUI with
   dynamic visuals (step 7) does not flatten: it has no static background. (The public
   `GuiCompiler.flatten(width, height, …)` is the same function over a screen-sized rectangle;
   the shared raster vectors pin it.)
2. **Cut and encode** (`GuiArt.cut`, §11.3). The flattened raster is cut into tiles of at most
   256 × 256 on a grid anchored at the painted bounds' top-left; a fully transparent tile is
   dropped and each other tile is cropped to its ink box. Each tile is encoded with the in-repo
   `PngEncoder` (RGBA8, the three chunks `IHDR`, `IDAT`, `IEND`, filter type 0, deflate level 6)
   and written at `assets/<ns>/textures/font/materione/gui/<id>.png` for the first tile (grid
   row 0, column 0) and `…/gui_tile/<hex id>/<row>_<col>.png` for the others. Bytes are stable
   for a given JDK; the goldens also compare decoded pixels, so a deflate change cannot hide a
   pixel regression. **A GUI that paints no pixel has no tile: no PNG, no provider, no codepoint,
   no glyph.**
3. **Tile glyphs.** Each tile's durable allocation of kind `gui-background` (value `n`, like
   `glyph-codepoint`) maps to codepoint `U+E200 + n − 1`, so the block is `U+E200`–`U+F8FF`
   (BMP only, above the glyph range and the shift block). The first tile keeps the GUI's own
   allocation, so an existing GUI keeps its codepoint; the other tiles append `tile/<row>/<col>`
   to the identity (`GuiOverlayKeys`). Each provider lives in the GUI font `<ns>:gui`:
   `{type: bitmap, file: <ns>:font/materione/gui/<id>.png, ascent: a, height: H, chars: [cp]}`
   with `a = 13 − ty` and `H = max(th, a)` (§11.3 rule 3: the PNG is padded with transparent rows
   below the art when the tile ends above row 13). The bitmap is one cell, so its scale is 1;
   `a` puts the tile's first row on screen row `ty` (see *Title geometry*). Every provider is
   checked against the client's limits before it is emitted (`MTR-0091`, §11.3 rule 4).
4. **Title.** One MiniMessage string, built by `GuiTitle` (the exact arithmetic is below and in
   §11.3 rule 5): per tile `<shift:tx−pen><font:<ns>:gui><#FFFFFF>{cp}</font>`, one shift back to
   the origin at the end of the art, then per text run
   `<shift:Δ><font:<ns>:gui/<font>_y<y>><#RRGGBB>text</font>`. A screen-sized background is still
   `<shift:-8>…<shift:8−advance>`. The art is white so no text colour tints it. The runtime model
   carries the title; nothing else is sent. A GUI with dynamic visuals starts with an empty
   title, and the rest is assembled per render (step 7).
5. **Offset fonts**, generated once per `(font, y)` actually used, merged per pack like glyph
   fonts (identical providers from several GUIs are emitted once):
   - `<ns>:gui/smallcaps_y<Y>` = `[space {" ": 4}, {type: bitmap, file: <ns>:font/materione/smallcaps.png,
     ascent: 13−Y, height: 16, chars: 6×16 grid}]`;
   - `<ns>:gui/default_y<Y>` = `[space {" ": 4}, {type: bitmap, file: minecraft:font/ascii.png,
     ascent: 13−Y, height: 8, chars: the vanilla 16×16 ASCII grid, printable cells only}]`.
   The vanilla ASCII texture is referenced by resource location and never shipped. Offset fonts
   never join `minecraft:default` (#198): they map plain ASCII, and the title reaches them only
   through these styled keys.
6. **Slot table.** `slot → (item reference, amount)`, sorted by slot. A later `item` layer in
   the same slot replaces an earlier one, and the earlier layer is reported (`MTR-0052`).
   References resolve at apply time like recipe results: a missing item is a diagnostic and an
   empty slot, never a crash.
7. **Dynamic visuals (#217; logic.md §6.4, §27).** A GUI takes this path when
   `GuiVisualCompiler.needed` holds: a visible raster layer has `visible_if` or `frames`, or a
   visible text layer has `visible_if` or a template. Its title cannot be fixed at compile time,
   so the compiler emits pieces for the window to assemble per render instead:
   - **There is no static background.** `GuiCompiler.compile` skips step 1's flatten, so no PNG,
     provider, codepoint or glyph is emitted for it and the title starts empty (§11.3 rule 6).
   - **Each visible raster layer is its own overlay glyph**, one per frame (a layer without
     `frames` has one). Static layers are included, so authored order holds between conditional
     and static pieces. Each frame is flattened over the art cap, cut into tiles and cropped
     exactly as in steps 1 to 3 (a frame that paints nothing contributes nothing, not even an
     allocation); a layer past the cap is reported once as `MTR-0090`. Each tile's codepoint is a
     further allocation of kind `gui-background`, keyed by the identity
     `minecraft:materione_gui_overlay/<hex namespace>/<hex gui>/<hex layer>/<frame>` for the
     frame's first tile and `…/<frame>/tile/<row>/<col>` for the others
     (`GuiOverlayKeys.allocation`), so it is durable like the background's and never reused. Its
     texture is `assets/<ns>/textures/font/materione/gui_overlay/<hex gui>/<hex layer>/<frame>.png`
     (`…/<frame>/<row>_<col>.png` for the other tiles), with a bitmap provider in `<ns>:gui` as in
     step 3. Exhausting the block quarantines the GUI with `MTR-0053`, as for the background.
   - **Each visible text layer is a piece.** A templated run is expanded and laid out again per
     render with the widths of §9.4. A rendered value that breaks the text rules (*Text
     characters*, below) is not drawn and reports `MTR-0073`.
   - **`TitleRenderer` assembles the title per render**, in layer order: each piece whose
     `visible_if` holds. A raster piece is its frame's tiles, each ending with the pen back at the
     title origin (§11.3 rule 5), so pieces assemble in any order. A false condition hides its layer; a failing one
     hides it and reports `MTR-0073`. A layer's click footprint is its visible frame's, not the
     authored one.
   - **Only a changed title is sent.** The window compares the rendered string with the one it
     last sent and sends nothing when they match. Held-cursor deferral is in logic.md §6.4.

**Title geometry (verified against the 26.1.2 client jar).** The arithmetic below is read from
the client's bytecode, not from memory; the probe loop (`docs/probe.md`) still has to confirm
it on screen (§9.1). It is the **chest's** geometry: the pen origin 8 and the label row 6 are
`titleLabelX` and `titleLabelY` of the chest screen. Other containers have their own (the anvil's x is 60,
the dispenser's is centred by the client); §12.5.3 (design) makes both parameters of a container profile
and keeps this arithmetic unchanged for the chest.

- *Draw origin.* `AbstractContainerScreen.extractLabels` draws the title at `(titleLabelX,
  titleLabelY) = (8, 6)`, relative to the screen's top-left, in colour `0xFF404040`.
- *Vertical.* A bitmap glyph's top is `GlyphBitmap.getTop() = 7 − ascent` above its draw row,
  and a text instance's row is the draw row unchanged. The glyph top is therefore
  `6 + 7 − ascent = 13 − ascent` on screen. A glyph that must start on screen row `t` has
  `ascent = 13 − t`: the background (row 0) is 13, a run on row `y` is `13 − y`.
- *Horizontal.* The pen starts at column 8. The background is drawn from pen position −8, which
  is screen column 0. A bitmap advance is `round(actualWidth · scale) + 1`, where `actualWidth`
  is the rightmost column holding any pixel with alpha ≠ 0, plus one
  (`BitmapProvider.getActualGlyphWidth`). With scale 1 the advance is `inkWidth + 1`, so after
  the background the pen is at `−8 + advance`, and the shift back is `8 − advance`.
- *The first character.* The client draws the first character of a string 1 px left
  (`PreparedTextBuilder.accept`, index 0). The title always starts with `<shift:-8>`, a glyph
  with no ink, so no visible glyph is affected.
- *Glyph sizing.* A bitmap provider requires `ascent ≤ height` (`BitmapProvider.Definition`),
  and every provider uses `height = cell height`, so its scale is 1 and nothing is resampled.
  The small-caps cell is 16 px (rows ≥ −3 are drawable); the vanilla ASCII cell is 8 px, so a
  `default` run must start on row 5 or lower.
- *Text advances.* Every run's advance is known exactly, so any run may follow any other. A
  `smallcaps` run's advance is the sum of its characters' advances, from the font's own pixels.
  A `default` run's advance is the sum of the vanilla ASCII advances, the 95 numbers of §9.4
  (`fonts/vanilla-ascii/advances.txt`, since #202). Before #202 a `default` run made the pen
  unknown and only the last run could be `default`; that restriction is gone.
- *Text characters.* Printable ASCII only (`0x20`–`0x7E`). `<`, `\`, `&` and `§` are rejected,
  since they are MiniMessage and legacy-code syntax.

**Pixel rules.** The exact integer rules live in `fixtures/gui/raster/README.md` and are
implemented by `ArgbCanvas`, `Bevels`, `Blit` and `GuiCompiler.flatten`; the vectors are the
contract, and `RasterVectorsTest` asserts every vector pixel for pixel (both sides compare
`0x00000000` for alpha 0). In short: source-over is `den = 255·sa + da·(255 − sa)`,
`outA = ⌊(den + 127)/255⌋`, `outC = ⌊(2·(sc·sa·255 + dc·da·(255 − sa)) + den)/(2·den)⌋`, with
`sa ≤ 255` taken as the source outright when it is opaque or the destination transparent;
opacity is `sa = ⌊(alpha·o + 50)/100⌋`; images sample `⌊(2i+1)·sw/(2w)⌋` (nearest neighbour);
`paint` layers copy 1:1 at the origin and never scale.

**Bevel painter (shared by `panel`, `button`, `slots`).** One routine paints the
Minecraft-style bevel from a style `{fill, highlight, shadow, outline}`, so a panel is a
large button and a slot well is an inset button:

- *raised* (panel, button): 1 px `outline` ring with transparent corners; inside it, top and
  left 1 px edges in `highlight`, bottom and right in `shadow`, the two off-diagonal corner
  pixels in `fill`, interior `fill`. Panels use a 2 px highlight/shadow edge. A panel's
  `corners` field picks the corner: `square` (the rule above, the absent default) or `vanilla`,
  the chest's own rounded corners pixel for pixel, with a square bottom (§11.6, #278).
- *inset* (slot wells, 18 × 18): top/left edge in `shadow`, bottom/right in `highlight`,
  interior `fill` — no outline.

The exact pixel rules are pinned by vectors in `fixtures/gui/raster/` (input JSON + expected
PNG). **Both the Java compiler and the TypeScript studio renderer test against the same
vectors**, the protocol-fixture pattern (web.md): the studio's preview is not an
approximation of the compiled background, it is the same function.

The vectors' [README](../../fixtures/gui/raster/README.md) is the normative statement of the
rules, including what the prose above leaves to arithmetic: each raster layer is painted on its
own transparent canvas and composited with its `opacity` (group opacity); source-over is integer
straight-alpha with round-half-up; a raised bevel's "off-diagonal corners" are `edge x edge`
blocks (so a 2 px panel has 2 x 2 blocks of `fill`); image blits sample the destination pixel's
center (`floor((2i+1) * sourceWidth / (2 * width))`); a `paint` bitmap is copied 1:1 at the
origin; alpha 0 is always stored as `0x00000000`; colors are `#RRGGBB` or `#AARRGGBB` (alpha first, as the component descriptions say); `style` fields fall back per field to the
vanilla defaults (`slots` use `#8B8B8B` / `#FFFFFF` / `#373737`). Changing a rule means amending
that README, `generate-vectors.mjs` and both implementations together.

**Fonts for text layers.** `smallcaps` is the first-party title font (release artwork, #188):
original pixel art drawn for this repository, in one text file, `fonts/smallcaps/smallcaps.txt`.
The Java compiler (`SmallcapsFont`) and the studio both read that file, so the studio preview is
the compiled sheet. Capitals are 6 px tall, lowercase is a 5 px small capital on the same baseline,
one row below the baseline holds descenders, and a space advances 3 px. Each advance is the
client's bitmap rule applied to the glyph's own pixels; the table is pinned by
`fixtures/gui/smallcaps/advances.txt`, which both implementations test. The sheet is **RGBA**
(opaque white ink, transparent background): the client tints the ink with the text colour, and a
grayscale sheet loads as black cells (§9.5). Licence and metrics: `fonts/smallcaps/README.md`.
`default` uses the client's own font: offset providers reference the vanilla bitmap textures by
resource location (nothing redistributed); the studio preview of `default` is approximate and
says so. Non-ASCII in `default` is a documented v1 limitation: the table covers printable ASCII
only (§9.4).

---

## 5. Runtime (v1: open, render, refresh — no logic)

- `RuntimeGuis` is a shadow-swap view (runtime-systems §1) of compiled GUIs.
- `GuiRuntime.open(player, id)` builds a `PluginGui` with the compiled title and slot items;
  every click inside the GUI is cancelled in v1 (the existing router already scopes this to
  the GUI's own slots).
- **A GUI can ship inside the plugin.** The plugin's own pack `materione` (master §7.3) holds GUIs that are loaded, compiled
  and opened like any other; the content browser `materione:browser` is the first (runtime-systems §8.3, logic.md §35). They
  are read-only, so the studio does not list them among the files it edits, and a pack in `content/packs` cannot take their id.
- `/materione gui open <id> [player]` (permission `materione.gui.open`) and the API op
  `RuntimeApi` → GUI open (api.md §3.7) are the two ways in for v1; the studio's "Preview in
  game" calls the API op for the session's approved player.
- **Live refresh:** after an apply that changes a GUI, players currently viewing it get it
  re-opened in place with the new title and slots (the in-game half of the live loop).
- The pack must be accepted for the design to show; without it the player sees the plain
  title on a vanilla chest — degraded, never broken.

**As built (issue 185: open, live refresh, preview in game).**

- *The view.* `RuntimeGuis` (plugin `gui/`) is a shadow-swap view (runtime-systems §1), published with
  the other registries under its class key. The shadow builder compiles every GUI through the same
  `GuiCompiler` the pack compile runs, with the GUI's durable background codepoint and its own pack's
  textures, and resolves each slot against the shadow's items (placement items included) at apply
  time. An item that is not live is `MTR-0054`: the slot stays empty and the rest of the GUI shows. A
  GUI that does not parse, or has no codepoint, is absent from the view, which is what "not live"
  means. The view lives in `plugin/`, not `core/`, because it holds `CompiledGui` (content depends on
  core) and resolves `minecraft:` ids to materials.
- *Open.* `GuiRuntime` implements `RuntimeApi.GuiRuntimeApi` on the `PluginGui` kit. The window is a
  `GuiMenu` (holder identity; the router never routes by title), titled with
  `MaterioneText.parseData` so the glyph and text tags come through as the compiler laid them out
  (no emoji replacement). Permission, online player and "GUI is live" are checked on the caller's
  thread and refuse at once; the window is built and opened on the viewer's region thread. Items come
  from the item factory (`MaterioneStacks.build`, amount clamped to the stack size). Since #214 the window
  is opened by `GuiOpener`, the one open path, which creates the player's `GuiSession` (arguments, session
  variables, back stack) and fires `gui.opening` and `gui.open` (logic.md §6.1, §21); `GuiRuntime` is the
  API facade in front of it, and `GuiMenu` is the window holder that knows its session.
  When an initial open is deferred through a click, it opens only the same immutable live definition
  that passed admission and `gui.opening`. If a swap replaces or removes that definition before display,
  the pending open fails closed, reports `MATERIONE-GUI-FAILED` and tells the viewer to retry; it does
  not rerun admission or opening rules. A new live view invalidates the pending open even if its authored
  GUI looks unchanged. A superseding session is never closed by the stale callback.
- *Clicks.* `GuiListener` routes three targets: the GUI's own slots and the outside of the window are
  cancelled, and the viewer's inventory stays live except the two actions that would move items into or
  out of the GUI (shift-move, double-click collection). The table is `GuiListener.route`, unit-tested.
  Since #218 the table has input-slot rows (logic.md §25): a click on an input slot is checked against
  `gui.slot_change` and the slot's `accept:` before the server applies it, shift-moves and double-click
  collection into and out of input slots are planned and applied by the input rules, and
  `player_inventory: locked` cancels every viewer click.
  The GUI's handler was empty in v1; since #214 it hands the click to the click router, and a click
  outside the window is offered to the GUI as `gui.click_outside` (logic.md §21 decision 11). The table
  itself is unchanged.
- *Command.* `/materione gui open <id> [player]`, permission `materione.gui.open` (default op). A
  qualified id must be live; a bare value must match one live GUI. A console sender names a player.
  Markers: `MATERIONE-GUI-OPENED`, `MATERIONE-GUI-DENIED`, `MATERIONE-GUI-FAILED`.
- *Live refresh.* After every successful swap, `PipelineService.observeSwaps` runs on the global
  thread (after recipes; a rolled-back apply notifies nobody). `GuiRefreshPlan` compares the two views:
  a GUI whose compiled view differs (container, title, or an item's definition hash in a slot) is CHANGED,
  and one no longer live is REMOVED. Each viewer's region thread then acts on its own window
  only when it shows a changed or removed GUI (since #214 the viewers are the open sessions, and the
  session carries on through a rewrite or a re-open: arguments, variables and the back stack survive,
  logic.md §21 decision 17):
  - *items only* (same container and title): the slots are rewritten in place and the window stays;
  - *title or container changed*: a new window is opened. The cursor item is lifted off the old window
    first and put back on the new one, so closing the old window does not return it to the inventory.
    The player's own inventory is never touched;
  - *no longer live*: the window is closed and the viewer gets a chat note.

  `MATERIONE-GUI-REFRESH changed=<n> removed=<n> players=<n>` is logged for every swap that changed a
  GUI. A failing observer is reported (`PIPELINE_STAGE_FAILED`) and never rolls the apply back.
- *Preview in game.* The editor's `runtime.openGui` and `runtime.previewTarget` ops (`web/rpc/ops/
  RuntimeOps`, §7) open the GUI for the session's owner: the player who opened the session in game.
  A console session has no owner and is refused (`MTR-0009`); nothing is guessed. The studio shows
  the "Preview in game" button only when the connected jar advertises the `gui` capability, and sends
  neither op otherwise. The button is disabled, with the reason as its tooltip, while no owner is
  online. A dirty studio still previews, and says in the tooltip that it shows the saved version.
- *What is not asserted headlessly.* No client joins the harness, so the window itself (its title
  component and its stacks) is covered by the unit tiers (`RuntimeGuisTest`, `GuiTitleTest`) and the
  visual QA entry `gui.runtime-visual`. `GuiRuntimeRunner` (`PaperGuiRuntimeIT`) proves the seams on Paper:
  boot, the command's refusals, the editor's refusals, the save-to-refresh path within the live-loop
  budget, retirement, and the missing-item diagnostic.

---

## 6. Editor studio — the design experience

The studio opens from Content like every other studio but owns the whole main area: it is a
canvas tool, not a form.

```
┌ toolbar: select · hand · brush · eraser · fill · picker │ zoom │ undo/redo │ preview ┐
├ components ┬──────────────── canvas (pixel-perfect) ────────────────┬ inspector    ┤
│ palette    │  nearest-neighbour zoom 1–16×, pan, slot grid overlay,   │ (registry-   │
│ + layers   │  pixel grid at ≥ 8×, snapping (pixel, slot, siblings),   │  derived +   │
│ panel      │  selection with resize handles, multi-select, nudge      │  bespoke     │
│ (reorder,  │                                                          │  controls)   │
│ hide, lock,│                                                          │              │
│ rename)    │                                                          │              │
└────────────┴──────────────────────────────────────────────────────────┴──────────────┘
```

- **Truthful preview**: the canvas renders through the shared raster (§4) plus the
  first-party font, in compiled target order.
- **Layers**: drag to reorder, visibility, lock, rename, duplicate, delete; a new GUI starts
  with no layers: the title and the container, nothing else (§11.2, built in #278). The vanilla chest is the client's own, and the studio draws the owner's reference art under it (§11, #277). A new **panel** starts with `corners: vanilla`. Other containers (§12.7, design) draw their own screen under the same canvas, from a container profile.
- **Components palette**: add any registered component; unknown addon components get a
  schema-derived inspector automatically.
- **Paint tools** on `paint` layers reuse the `pixels.ts` engine (brush, eraser, fill,
  picker, palette); `image` layers come from upload or the Assets workspace.
- **Item layers** snap to container slots; picker for Materione items and vanilla
  materials; "Customize" creates the item file per §2 and opens its appearance inline.
- **History**: a command stack (every edit is an invertible command) — undo/redo across
  property, transform, paint-stroke and layer-structure edits.
- **Save** writes the YAML `layers` (lossless, changed fields only) and any dirty paint PNGs
  in **one** authoring transaction with rollback (§7); then the normal reload applies it.

**As built (issue 186: canvas, layers, transforms, history).** The studio lives in
`webui/src/lib/gui/` (pure TS, no DOM) and `webui/src/lib/studio/gui/` (Svelte), opened from
Content for any `guis/**` file.

| Piece | Where | Notes |
|---|---|---|
| Raster mirror | `gui/raster/` | `ArgbCanvas`, `paintRaised`/`paintInset`, `blitScaled`, `flattenRaster`; the canvas preview calls the very function the vectors test |
| Document model | `gui/model.ts` | the API's own `title`/`container`/`layers` structure, immutable; unknown fields, layer types and top-level keys ride along; a file with a layer the model cannot hold (no type, missing or duplicate id) opens **read-only** instead of being rewritten. The file is keyed by its resource id (`<id>:` then the fields) and field paths are `[id, 'layers']`; an unkeyed file is read for display only and never written |
| History | `gui/commands.ts`, `gui/history.ts` | every edit is an invertible command (patch, insert, remove, reorder, document fields, composite); drags commit one command on release; nudges and scrubs coalesce within 900 ms; undoing back to the saved baseline makes the document clean |
| Canvas rules | `gui/scene.ts`, `snap.ts`, `transform.ts`, `viewport.ts` | bounds, hit testing, snapping (canvas edges and middle, slot grid, sibling edges and centers; whole pixels only), 8-handle resize, zoom 1-16x anchored at the cursor |
| Compiled order | `gui/scene.ts` | raster layers, then text, then slot items, each in list order; a layer listed on the wrong side of a layer of another target carries a `#n` badge. The relative order of title text and slot items is still the open question of section 9.1, so the studio states the assumption in one place (`TARGET_RANK`) |
| Registry | `gui/registry.ts` | palette, inspector fields, defaults for a new layer and what may be dragged or resized are all derived from the component descriptions (`guiComponentsOf` over `authoring.describe`; `x`/`y`/`width`/`height` fields mean movable and resizable, `slot` means slot-bound; a layer's `type:` matches the registry id with `materione:` optional); no component list lives in the studio. Cosmetic icons and the bevel style's four colors (a raw map in the schema) are the only studio-side knowledge |
| Save | `gui/save.ts` | `authoring.setFields` with the changed top-level fields (`title`, `container`, `layers`, and `open` since #225) and `assets: [{ path, data }]` (base64 PNG, via `ops.setFields`) for pixel assets a layer still refers to; a pixels-only save sends empty `edits` |

Shortcuts: `V` select, `H` hand, `Space` (hold) pan, `0` fit, `1` 100%, `+`/`-` zoom,
`Ctrl+Z` / `Ctrl+Shift+Z` undo/redo, `Ctrl+D` duplicate, `Del` delete, `Ctrl+A` all, `Ctrl+]`/`[`
raise/lower, `F2` rename, `Enter` edit the selected text in place, arrows nudge (`Shift` = 10 px),
`Ctrl+S` save, and the paint tools below.

**As built (issue 187: paint, image, text and item components).** Text and item layers are real
renders now (the placeholders are gone), and the paint, image, text and item components each have
the tools a designer expects.

| Piece | Where | Notes |
|---|---|---|
| Paint tools | `gui/paint.ts`, `gui/pixel-command.ts`, `studio/gui/PaintOptions.svelte` | brush and eraser (1-8 px, square or round), contiguous fill (tolerance 0), eyedropper (samples the **composited** art first, as the player sees it, then the vanilla chest under it; §10 *Decisions taken in #277*), palette with recent colours and hex input (persisted in the browser, never in the file). The drawing rules are `pixels.ts`'s (stamp, Bresenham line, exact-match flood fill) run on a working RGBA copy of the layer's `ArgbCanvas`; `Brush` gained an optional `shape` for the round footprint (additive: the texture editor is unchanged). A stroke joins every pointer event, coalesced ones included, with a line, so a fast drag leaves no gaps at any zoom; Shift-click draws a straight line from the last stroke's end; Alt picks a colour from any paint tool |
| Strokes are commands | `GuiHistory` | a stroke, fill or clear is **one** `PixelCommand` carrying a patch (touched pixels, before and after) rather than a copy of the canvas. A stroke inside the layer's box does not change the document, so the history tracks the newest applied pixel command by identity: a stroke makes the studio dirty, undoing back to the saved state makes it clean, and undoing past a save, or painting something else after it, never looks clean. Since #277 the patch is kept in GUI pixels (not as indices into a bitmap) together with the layer's box before and after, because the bitmap is reshaped between a stroke and its undo (see *As built (issue 277)* below) |
| Paint target | `GuiStudioState` | the selected paint layer, else the topmost visible unlocked one, else a new `Paint` layer is created by the first stroke (one more undo step); a locked or hidden target is explained in the status bar, never painted. A paint layer is no longer tied to the rows (#277: it owns its box) |
| Image layers | `studio/gui/ImageExtras.svelte`, `ImageChooser.svelte` | add by **upload** (file dialog, or drop a file on the canvas) or **from the Assets workspace** (the item studio's explorer). An upload is stored at `textures/gui/<gui-path>/<file>.png` with the file's own bytes (a PNG is never re-encoded; other formats the browser can read are converted) and rides the layout transaction as an `assets` entry; moving and resizing are the canvas's (nearest neighbour), with **Natural size** and 1x-4x scale buttons |
| Text layers | `gui/font/`, `gui/text.ts`, `gui/compose.ts`, `studio/gui/TextEditOverlay.svelte` | **smallcaps is pixel-exact**: the canvas draws each glyph from the shared font source `fonts/smallcaps/smallcaps.txt` (section 4, *Fonts*, parsed by `font/smallcaps.ts`; `font/render.ts` draws it), at the pen the compiler computes, tinted by the colour; the browser spec compares every ink pixel against glyphs parsed independently from that file. The studio also draws the vanilla **Inventory** label as a faint ghost at (8, height - 94), since the client draws it over the background (section 9.2). **Double-click** (or `Enter`) edits in place: a hidden input takes the keyboard, the canvas keeps drawing the real font, and the overlay draws only caret and selection from the font's own advances; typing previews live without touching the history, `Enter`/leaving commits one undo step, `Escape` takes it back. `default` is drawn with the bundled Minecraftia and says **Approximate preview**; its advances are the client's own widths (`fonts/vanilla-ascii/advances.txt`, §9.4), so the pen and the selection box are exact |
| Compiler rules, shown | `gui/validation.ts`, `gui/server.ts` (since #204, §10 #8) | the verdict is the server's: `gui.validate` answers for the document as shown, debounced as you edit, and each diagnostic lands on the layer it names. The studio holds no copy of the rules, so the list below is what the server enforces, stated where the author is looking: an orange `!` flag on the canvas, a warning icon in the layers panel and the reason in the inspector. Text: reserved characters, non-ASCII, blank or over-long text, and `default` above row 5. Items: a slot outside the container, a missing item, a bad amount, a later layer replacing an earlier one in the same slot. Skipped text is drawn as a faint ghost, not as the pixels the player will not get |
| Item layers | `studio/gui/ItemExtras.svelte` | the picker is the item studio's (Materione items with their thumbnails, vanilla materials with their icons); a pick is stored `pack:id` or `minecraft:id` (a bare id resolves in the `materione` namespace in game, so it is never written). Items **snap to container slots only**: drags, nudges and typed slots resolve to the chest's own slots (`ItemComponent` rejects the rest). The canvas draws the real item icon (the existing thumbnail pipeline, `ItemIcon` with a `bare` mode) and the stack count |
| Customize | `gui/customize.ts`, `gui/server.ts`, `GuiStudioState.customize`, `GuiItemAppearance.svelte` | see below; `gui.customizeItem` decides the item's id and refusals (§10 #2) |

**As built (issue 225: the Design tab and the logic).** The studio is three tabs over one document: Design, Logic and Script
(logic.md §7.1, §33). The Design tab knows which rules a layer has: a button, an item or a list has a **Logic** section in the
inspector ("Add click logic" adds a `gui.click` rule filtered to the layer and opens it in the Logic tab, or "Open in Logic" when it
has rules), a layer with rules wears a bolt in the layers panel (a button that opens the rule), the compiler's verdict on a rule is drawn on
its layer (a triangle on the row, the message with its step path in the inspector), and deleting a layer that has rules asks
whether to delete the rules or keep them without the `layer:` filter, as one undo step with the layer. The link is the rule's `layer:`
filter and nothing is stored. The studio's `open:` block (§6.1) is edited in the Script tab and saved as its own field with the layers.

**As built (issue 277: the open canvas, gui.md §11).** The canvas is no longer the screen; it is the whole art cap with the chest drawn on it.

| Piece | Where | Notes |
|---|---|---|
| Vanilla underlay | `gui/vanilla.ts`, `gui/vanilla-sheet.ts`, `static/vanilla/generic_54.png` | the owner's sheet, composed for 1-6 rows as the client blits it (the top `rows*18+17` rows, then 96 rows from `v = 126`; the last row of the screen stays empty). It is not a layer: it is never listed, saved or in the history. The vitest and the browser spec compare it pixel for pixel with the sheet for every row count |
| Regions and veil | `gui/vanilla.ts` `veiledScreen`, `gui/open-canvas.ts` | container normal; the player's inventory (`y >= rows*18+17` on a chest; the profile's `inventoryTop` for any container) under a translucent grey veil (`VEIL`) that lifts **per painted pixel** (any pixel of the composed art with alpha above 0 shows the vanilla pixel at full strength, the neighbours stay veiled); the open space outside the screen has the screen outline and the cap box. The status bar names the region under the pointer. `item` and `list` layers stay container-only |
| The canvas | `GuiCanvas.svelte` | three stacked canvases: the open space (the cap's box), `gui-underlay` (the chest, screen-sized) and `gui-art` (the composed art over the cap: 1024 x 1024, its top-left at GUI pixel -424, -243; `ArgbCanvas` gained an origin for this, `RasterScene` an `originX`/`originY`). Pan and zoom cover the whole cap (`panBy` takes the content's origin); the pixel grid covers what the stage shows. Snapping also pulls to the cap's edges |
| Guides | `gui/open-canvas.ts` `windowGuide`, toolbar "Window guides" | dashed rectangles for what a 1920 x 1080 window shows around the screen at GUI scales 2, 3 and 4 (§9.12 and §11.5: `leftPos = floor((ceil(W/s) - 176) / 2)`, `topPos = floor((ceil(H/s) - imageHeight) / 2)`), with the margins in the label; off by default, like the slot grid |
| Paint layers own their box | `gui/paint-geometry.ts`, `gui/pixel-command.ts`, `GuiStudioState` | see *Decisions taken in #277*: x, y, width, height; a stroke runs on a working copy that covers the cap, so it can leave the layer and is clipped to the cap; the layer grows to hold it in the same undo step; **Save trims** every paint layer to its painted pixels (the PNG holds no margin), and the studio adopts the trimmed layer so what is saved is what it holds. A paint layer moves with X and Y and is never dragged to a size |
| Selecting in the open space | `gui/scene.ts` | hit testing and marquee work anywhere. A layer covering the whole screen is still the backdrop a drag on it marquee-selects over (`coversScreen`); the open space has no layer under it, so a drag there always marquee-selects. A marquee also selects a paint layer when it covers one of its painted pixels |
| Decoration only | `gui/open-canvas.ts` `decorationNote`, layers panel and inspector | a button, or a layer with a click rule (the Design/Logic link of #225), whose click rectangle covers **no container slot** wears a badge and an inspector note: placed outside the screen, "Decoration only: clicks outside the chest arrive as click_outside"; on the screen but over no slot (the player's inventory, the gaps), "it covers no container slot"; covering a slot but leaving the screen, "Partly decoration only". Pure geometry (§9.14, §11.4) stated with the compiler's own rule (MTR-0092, #276); the compiler's warning itself arrives through `gui.validate` like any other |

**Customize is one transaction.** Customize creates an ordinary item at
`items/gui/<gui-path>/<layer-id>.yml`, points the layer's `item:` at it and opens its appearance
inline. Since #204 the server plans it: `gui.customizeItem` (api.md §3.9) makes the item and the
layer's new `item:` in **one `authoring.bulk` call** underneath, which plans every document before
writing any and writes all or none, with the GUI document's revision checked. Pending layout
changes are saved first, as their own transaction, because the server works on the saved file
(§10 #11). The studio applies the pointer to its own copy only after the server answers, as one
undoable edit. Two things the API shapes: **(1)** `authoring.bulk` cannot carry binaries, so pending
pixel assets are saved first by a normal save (itself one transaction) and the customization follows;
**(2)** the file is named after the item's id (the API fixes that), so the id is the layer id when
the pack has no resource of that name, else `<gui-name>_<layer-id>`, else numbered, and the path
is `items/gui/<gui-path>/<id>.yml`. A **vanilla** base becomes a `create` with `material:`. A
**Materione item** base becomes a `create` of `template: <base id>` and nothing else (since #203
an item may be the template of an item, plugin.md §3.1): the scaffold's own `material` and
`display_name` are removed in the same step, so nothing is copied that could drift. The new item
starts identical to the base and keeps following it for everything the designer does not set;
the inline appearance shows "Follows `<base>`", the item studio's chip names the base, and
**Detach template** turns the link into a copy when a designer wants one. If a template with the
base's id exists, a link would name the template rather than the item (plugin.md §3.1
precedence), so Customize refuses with that reason instead of linking to the wrong thing. Blocks and furniture cannot be customized (no item document). The inline appearance
is the item studio's own `ItemAppearance` on that item's document draft; its edits are drafts of
that file, and **Save** writes the layout and every customized item's draft (two kinds of
document, so two transactions, in that order).

## 7. Wire & API surface

No new transport concepts. Reads use existing browse/registry description (the
`gui-components` registry is described like `mechanics`). Writes use `authoring.setFields`
extended with an optional `assets` list committed atomically with the field edits (the
`authoring.saveModel` rollback guarantees, generalized so the pixel editor can use it too).
One new runtime op opens a GUI for the session's player (§5). Protocol fixtures grow
accordingly and both sides test them (web.md).

As built (#183): `authoring.setFields` takes an optional `assets: [{path, data}]` (the
`assets.put` shape: pack-relative path, standard base64 bytes). The core stages every
binary beside its target, validates the path, then promotes it and patches the document
under one lock; a stale revision, a refused path, an oversized binary or a failed write
restores both (the model save-back's rollback, shared as `AssetStaging`). Binaries may
only land under `assets/`: thumbnails stay the model transaction's. The golden frames are
`fixtures/protocol/editor-ops-v1.json`, checked by `EditorOpsGoldenTest` (JVM) and
`ops-golden.spec.ts` (SPA).

A **paint-only save** is a `setFields` with an empty `edits` list and at least one asset (a
paint layer changed, no field did). It takes a new revision with the YAML bytes unchanged,
so a stale studio save still conflicts. The revision is recorded as a journal entry
(`DocumentStore.recordRevision`, action `assets`) that repeats the document's current hash.
This is the cleanest correct form for the journal model: `syncRevision` infers an external
edit only when the file's hash differs from the last entry, so a repeated hash is consistent,
and it avoids rewriting identical bytes. The save keeps the same guarantees as any other:
the revision check, the all-or-none rollback (the journal is restored with the assets), one
audit line and one reload. A save with neither edits nor assets is refused (`MTR-0008`),
on the server and in the demo. `authoring.describe` carries `guiComponents` next to
`behavior`, one entry per `gui-components` registry entry (`id`, the self-description,
`target`, `registeredBy`), read on every call like the mechanics vocabulary.

As built (#204): the typed GUI API. Everything the studio does to a GUI is an API operation
(api.md §3.9), and the editor reaches it through five ops that are all gated on the `gui`
session capability (`editor-op-gates.json`): `gui.get` (the typed view), `gui.edit` (the five
structural edits, one revision-checked transaction), `gui.customizeItem`, `gui.render` (background
PNG, compiled order, diagnostics; a saved file, YAML or a typed draft, with draft PNGs) and
`gui.validate` (the compile's diagnostics for the same sources). Each handler is a JSON
translator over `GuiAuthoringApi` (R9). The component descriptors the studio reads come from the
API (`GuiComponentDescriptor`); `web/` only serializes them. The studio's own save stays on
`authoring.setFields` with the changed top-level fields, because its layer model is a whole-list
diff; `gui.edit` is the path for other consumers (§10 #12).

## 8. Deferred — and how it plugs in

> **Logic is designed in [`logic.md`](logic.md)** (clicks, dynamic slots, lists, prompts, the node
> editor). The seams below are the ones it uses; where it refines one, logic.md wins.

| Later | Seam |
|---|---|
| Click bindings → ECA `menu.click` | **Done in #214** (logic.md §21): the gui type's `behavior:` holds `gui.click` rules with a `layer:` filter, and components declare click regions through `CompiledGuiLayer.clickArea` |
| Pagination, search, dynamic fill | new components with target `SLOT` (+ runtime state) |
| Placeholders in text | `TEXT` runs are already title text, not baked pixels |
| Groups/folders of layers | a `group` component with children; flattening already recursive-ready |
| Hopper/dispenser/anvil containers | `container:` field and a container profile per type. **Designed in §12** (#286, scope approved by the owner): the chests, the hopper and the dispenser in v1, anvil and the furnaces in a later phase |
| Animated layers | frame cycling from issue #9 |

## 9. Open questions — answered (#188, #275)

Each answer states what was verified and how: client bytecode (the 26.1.2 client jar, read with
`javap`; method names are given) or a probe run (`docs/probe.md`: the 26.1.2 client joined to the
pinned Paper 26.1.2 server). **There is almost no 1.21.4 evidence.** This machine has no 1.21.4
client jar, and the probe loop runs 26.x only, so every 1.21.4 statement here is *unverified*; the one exception is the
hopper's and dispenser's window type and size (§12.2.6), asserted on the 1.21.4 server by the harness.

### 9.1 Draw order of title vs slot items (26.1.2)

Verified order, bottom to top:

| # | Element | Submitted by (client method) |
|---|---|---|
| 1 | Screen background, panorama | `Screen.extractRenderStateWithTooltipAndSubtitles`, stratum 1 |
| 2 | Vanilla chest texture | `ContainerScreen.extractBackground`, stratum 1 |
| 3 | Title: the compiled background glyph and every text run | `AbstractContainerScreen.extractLabels`, first element of stratum 2 |
| 4 | Inventory label (`container.inventory`) | `extractLabels`, second text |
| 5 | Hovered slot, back highlight | `extractSlotHighlightBack` |
| 6 | Slot items and their count text | `extractSlots` → `extractSlot` |
| 7 | Hovered slot, front highlight | `extractSlotHighlightFront` |
| 8 | Carried item and snapback | `extractCarriedItem`, `extractSnapbackItem` |
| 9 | Tooltip | `extractTooltip`, deferred elements (last) |

*Mechanism* (`GuiRenderState`, `GuiRenderer`). Each element goes into a node. An element whose
bounds intersect an earlier element is placed in a node above it, so overlapping elements draw in
submission order. The title's bounds are the union of its glyph quads
(`Font$PreparedTextBuilder.markSize`), so the 176 × 222 background glyph makes the title span the
whole canvas and every slot lands above it. Within one node, element blits (item atlas,
highlights) draw before glyph quads (`GuiRenderState`, `lambda$forEachElement$0`). That is the one
way the background could cover an item: an item that does not intersect the title's bounds, placed
in the title's node. The compiled background always spans the canvas, so the design holds.

*Evidence.* A compiled 6-row GUI (`fixtures/gui/shop.yml` and its textures, in a scratch pack)
with a client-side chest whose title is the compiled background glyph: the four items draw over
the background art, the carried item draws over it, and the count text draws over its item. The
hover highlight and the tooltip are not captured (the bridge cannot steer the mouse); their order
comes from the bytecode above.

*Decision.* The studio's target order (`TARGET_RANK`: raster, text, slot items) is the client's
order on 26.1.2. Title-under-items holds with no design change. Text runs draw above the
background and below the items, as designed. **Invariant to keep:** a title carries a full-canvas
background glyph; a future title without one must show that its bounds cover every slot it places
items on. *Superseded by #275 (§9.13):* the open canvas has no full-canvas glyph, and items still draw
above any art they overlap, because ink inside a glyph quad lies inside the title's bounds.

*1.21.4 (unverified).* The 1.21.6+ render-state model above is what 26.x uses. The 1.21.4
`AbstractContainerScreen.render` calls the background, labels and slots in the same sequence, and
its batched text and item draws are expected to match. Confirm with the 1.21.4 client jar (§9.7).

### 9.2 The vanilla "Inventory" label

*Fact.* `AbstractContainerScreen` sets `playerInventoryTitle` from `Inventory.getDisplayName()`,
which is the translatable `container.inventory` (en_us `Inventory`). It draws at
`(inventoryLabelX, inventoryLabelY) = (8, imageHeight − 94)`: y = 128 on a 6-row chest, 38 on a
1-row one. It draws in `extractLabels` after the title, colour `0xFF404040`, without shadow. Title
and label both draw above the background, so no background can cover the label. The probe shows
it over the compiled background at GUI y 128.

| Option | Effect | Scope |
|---|---|---|
| Leave it (vanilla) | designers keep that area clear; the studio shows the label as a ghost | per screen, no config |
| Pack language override of `container.inventory` | language keys merge per file, so one key can be overridden; every vanilla container's player-inventory label changes, in every locale the pack ships | global, per pack |
| Per-screen override | no client hook: the label is the `Inventory` object's name, not the menu title | not possible without a client mod |

*Decision (v1): leave it.* The studio (#187) draws the ghost label at `(8, imageHeight − 94)` so
designers avoid that area; the compiler does not reserve it. The language override is an explicit
owner choice with a global effect, never something a GUI does silently.

### 9.3 Background bitmap size

*Fact (bytecode).* Glyphs are packed into a `FontTexture` atlas of `SIZE = 256` (× 256).
`FontTexture$Node.insert` refuses a glyph wider or taller than 256, and `GlyphStitcher.stitch`
then returns no glyph (it tries each atlas, then a new one). The codepoint renders as the
missing-glyph box, as the probe shows; the compiler reports nothing.

*Measured (probe, four solid test bitmaps).* 32 × 256 renders; 32 × 257 shows the missing-glyph
box; 256 × 16 renders; 257 × 16 shows the missing-glyph box. The limit is per glyph, in both
dimensions, measured on the native image size (not the scaled `height`).

*Consequence.* A canvas is `176 × (114 + 18·rows)`, so the largest chest (6 rows, 176 × 222) fits.
**No split is needed for v1.** A future canvas over 256 px in either dimension needs bands of at
most 256 px, each a glyph in its own font `<ns>:gui/band<k>` with `ascent = 13 − t` (t is the band's
top row; a negative ascent is valid, and `ascent ≤ height` holds), with the title shifting between
bands under the same pen rules. Not built. Until bands exist, the compiler should refuse a canvas
over 256 px with a diagnostic (owner to confirm the code and the wording).

*Superseded by #275 (§9.10, §9.11, §11.3).* Tiles need **no font per band**: providers with different ascents
share one font. `ascent ≤ height` does **not** hold by itself for a tile that ends above row 13: it needs
padding rows, and a violation drops the whole font file (§9.10).

*1.21.4 (unverified).* The 256-px atlas is expected, not read from 1.21.4 bytecode.

### 9.4 Default-font advance widths

*Facts (26.1.2 and 26.2 client jars).* `assets/minecraft/textures/font/ascii.png` is byte-identical
in both versions, and its 95 printable advances (ink width + 1; space 4) are identical in both.
Histogram of the 95: 2 px × 8, 3 × 2, 4 × 11, 5 × 4, 6 × 68, 7 × 2.

*Answer.* The advances are measurements of ink extent, not expressive content. A table of 95
integers is a metric table of the same kind as a font's `hmtx`, and it contains no artwork. It can
ship as numeric data, with its provenance beside it, versioned by the client version it was
measured from, and under a golden test. It must not carry the texture.

*Decision (owner, 2026-10-29): ship it.* The owner approved shipping the 95 vanilla ASCII advances
as a numeric table, versioned by the client version it was measured from, under a golden test. The
texture is never shipped. Two earlier blockers are resolved: a server pack's `default.json` merges
with vanilla's, so vanilla ASCII renders and its widths matter (§9.6); and the owner has signed off
on deriving the numbers from the Mojang asset. #188 records the decision and does not implement
it. The implementation is the follow-up issue the owner files. When it lands, lift the "last
default-font run only" restriction for printable ASCII.

*Shipped (#202).* The table is `fonts/vanilla-ascii/advances.txt`, derived by
`scripts/gui/derive-vanilla-ascii-advances.mjs` from the client jar, read by `VanillaAscii` (Java)
and `font/vanilla-ascii.ts` (studio), and pinned by `fixtures/gui/vanilla-ascii/golden.txt`. The
"last default-font run only" restriction is lifted (see *Decisions taken in #202*). The row-5 limit
is a height rule, not a width question, and stays.

### 9.5 Smallcaps sheet format (a defect, fixed in #188)

The placeholder sheet was **grayscale**, with no alpha. On 26.1.2 the client loads it as an opaque
colour texture: every glyph cell is an opaque black box, and the text colour tints only the ink
(probe: the heading and the alphabet sit on black cells). The fix is an **RGBA** sheet with opaque
white ink and a transparent background, the convention of the vanilla `ascii.png`. The client
tints the white ink with the text colour, and the background stays clear. This supersedes the
grayscale rule of §10 (#184, item 12).

### 9.6 Default-font membership: merge, not replace (settled by #201)

The first draft of this answer said a pack's `default.json` replaces the vanilla file, so vanilla's
ASCII providers were dropped. **That was wrong, and #201 settled the question as merge.**

- *Bytecode (26.1.2, verified in #188).* `FontManager.prepare` calls
  `FileToIdConverter.listMatchingResourceStacks`, and `FontManager.loadResourceStack` parses every
  pack's copy of the font file. Providers from every pack join the font, so a server pack's
  `default.json` adds to vanilla's `minecraft:include/space`, `include/default` and
  `include/unifont`.
- *Probe (#201).* A `default.json` referencing only the shift font and two glyph fonts leaves the
  survival inventory's "Crafting" label, toasts and chat rendering as vanilla. I did not re-run
  this probe for the rebase.
- *What my probe showed.* The black-and-purple glyphs on unstyled text came from the grayscale GUI
  smallcaps sheet that my scratch pack's `default.json` referenced. That sheet maps all printable
  ASCII, so it covered vanilla's ASCII providers with opaque black cells (§9.5). The explanation fits
  every observation. Since #201, GUI fonts never join `minecraft:default` (`STYLED_ONLY`, §4 and
  the #198 decisions in §10), so this path cannot recur.

So the pack compiler needs no change for the default font. The vanilla includes are not prepended,
because merging makes them unnecessary.

### 9.7 Gaps this machine cannot close

- **1.21.4:** no 1.21.4 client jar is installed, and the probe loop runs 26.x only. Getting the
  vanilla 1.21.4 client means a download the repo does not pin. Needed for §9.1 and §9.3 on the
  floor version, and for every fact of §9.8–§9.15: the **ascent / height rule and its whole-font
  failure**, the **256-px cell**, the **unclipped title**, the **pen and ascent arithmetic**, the **click
  wire** and the **draw order**. All are *expected* to hold on 1.21.4 (the font providers and the
  container screen are old code) and **none is measured there**. CI's harness is headless and cannot
  screenshot, so it cannot close the gap either; the probe would need a 1.21.4 build of its client
  bridge.
- **26.2:** only the texture and the advances were compared (byte-identical). No probe run.
- **Containers other than the chest (§12.12):** the menus' slot facts are verified on both pins by reading the Paper server
  jars. For the hopper and the dispenser, the screens were seen on the 26.1.2 probe (§12.2.6, #289); on 1.21.4 only the
  window type and size are measured (a harness test, headless), and every screen fact (size, title position, background,
  art placement, centring) is unverified there for the same reason as above. Every other container's screen fact is
  read from the 26.1.2 client jar only.

### 9.8 Research for the open canvas (#275): method and evidence

Everything in §9.8–§9.15 was measured on the probe client (26.1.2, windowed, real GPU), to answer the
facts that §11 marked *to verify*. The rig:

- **Pack.** A client resource pack, `r275`, generated by [`fixtures/gui/open-canvas/gen.mjs`](../../fixtures/gui/open-canvas/gen.mjs)
  (fonts and titles as JSON in [`fixtures/gui/open-canvas/json/`](../../fixtures/gui/open-canvas/json/)). It has the same shape the
  compiler emits: bitmap providers `{type: bitmap, file, ascent, height, chars}` and a shift font of `space` providers (powers of
  two, both signs). The test art is a marker (solid colour, 2-px black border, 4 × 4 white corner for orientation) or a
  **position-coded image**: pixel `(X, Y)` of the big image has a colour computed from `X` and `Y` (gradients, a black line every 32 px,
  a parity checker), so any gap, overlap or 1-px slip between tiles changes a pixel.
- **Title.** A real server-opened chest whose `CustomName` carries the title: a double chest (6 rows, 176 × 222) placed with `setblock`,
  so the title travels in the normal open-screen packet. No vanilla chest has one row, so the 1-row cases swap the open screen for a
  client-side `ContainerScreen(ChestMenu.oneRow(...))` that reuses the same title component. The click test (§9.14) uses a real
  Materione GUI ([`r275_click.yml`](../../fixtures/gui/open-canvas/r275_click.yml)) whose title is replaced on the client.
- **Capture and judgement.** Pixel-exact PNGs of the framebuffer (`Screenshot.grab` on the render thread, not the bridge's JPEG), read
  back in Node and compared with the pixels the model predicts. The window is resized with GLFW (1920 × 1080 works on a smaller
  monitor), the GUI scale with `mc.options.guiScale()` and `mc.resizeGui()`. The drivers are in
  [`fixtures/gui/open-canvas/runs/`](../../fixtures/gui/open-canvas/runs/) (see its README); the PNGs below are cropped or halved copies of the
  full-size captures, kept small. They are in [`docs/screenshots/gui-open-canvas/`](../screenshots/gui-open-canvas/).

**Placement model, as measured.** For a glyph with `ascent = a` whose title position is `x` (pen) and a GUI of screen origin
`(leftPos, topPos)`: the glyph's top-left pixel is at `(leftPos + x, topPos + 13 − a)`. This held to the pixel in every capture below
(over 100 marker boxes and about 10 million image pixels), at every scale and window size.

### 9.9 Off-screen art is drawn (title not clipped)

*Fact (bytecode).* `AbstractContainerScreen` has no scissor call. The title is one text element submitted by `extractLabels`; each glyph
quad goes where its advance and bearing put it, with no clip rectangle.

*Measured.* 32 × 32 markers left (x = −40), right (x = 184), above (y = −40) and below (y = screen height + 8) of the screen, and
farther (x = −300, x = 476), on the 6-row screen and on the 1-row screen: every marker draws, over the world and over the HUD
hotbar. At 1920 × 1080 scale 1 the extremes also draw: x = −860, x = +1000, y = −243 and y = 640. Each marker's fill box is exactly the
predicted one, clipped only by the window edge.

*Consequence.* Nothing clips the title to the screen. Art past the screen is limited by the window (§9.12) and by the vertical rule
(§9.10), not by the client's GUI code. The compiled glyph needs no change for off-screen placement beyond the formulas in §11.3.

Evidence: [`q1-offscreen-6row-1080p-s2.png`](../screenshots/gui-open-canvas/q1-offscreen-6row-1080p-s2.png) (halved),
[`q1-offscreen-1row-1080p-s2.png`](../screenshots/gui-open-canvas/q1-offscreen-1row-1080p-s2.png) (halved),
[`q1-cut-by-window-1080p-s4.png`](../screenshots/gui-open-canvas/q1-cut-by-window-1080p-s4.png) (the top and bottom markers cut by the
window edge). Pack: `c1r6`, `c1r1` in `json/font/` and `json/titles.json`; driver `runs/b1.mjs`, `b2.mjs`, `b3.mjs`.

### 9.10 Vertical placement, negative ascent and `ascent ≤ height`

*Facts.*

1. **The row formula holds for any ascent**: top = `topPos + 13 − ascent`, checked from ascent 256 (row −243) through 13, 0 and
   negative values down to −627 (row 640). **Zero and negative ascents are valid.**
2. **`ascent ≤ height` is enforced when the font loads**, and a violation is not local. The client logs
   `WARN Unable to load font '<font>' in fonts.json in resourcepack: '<pack>'` with
   `JsonParseException: Ascent 53 higher than height 32` (`FontManager.loadResourceStack`), and **drops that pack's whole font file**:
   every provider in it, valid or not. A font file with one valid and one violating provider (`c2mix`) renders neither. The glyphs draw as
   the missing-glyph box, which advances **6 px** where the real glyph would advance 33, so everything after it in the title also
   moves. In Materione that file is `<ns>:gui`, shared by every GUI of the pack: **one bad provider erases every GUI's art and text**.
   The pack builder does not check.
3. **Padding works, and the cheapest rule is exact.** `ascent == height` is valid, so a tile at row `y` with `th` art rows needs
   `max(0, (13 − y) − th)` transparent rows **below** it (`MIN`: 32 art rows at y = −40, 21 blank rows, cell 53, ascent 53: correct).
   A tile that already reaches row 13 or below (`13 − y ≤ th`) needs none (`EQ13`: 13 rows at y = 0, ascent 13 = height 13: correct).
4. **The reach above the screen is limited to row −243.** A cell is at most 256 px tall (§9.3: a 257-px cell is refused by the glyph
   atlas, silently, and draws as the missing-glyph box, advance 6), and `ascent ≤ height ≤ 256` gives `top = 13 − ascent ≥ −243`. A
   256-px bar with ascent 256 starts at row −243 and ends at row 12 (`EDGE`: correct). Padding cannot go further, and neither can
   splitting: every tile obeys the same bound.
5. **`height` above the cell height is not a way up.** `height` is the drawn height, so a 32-px cell with `height: 128` is drawn four
   times larger (`SCALE`: 128 × 128 px, advance `32·4 + 1 = 129`). Art would be resampled.

*Consequence.* Two things for the compiler: an **emit-time invariant** that every provider has `ascent ≤ height` and a cell of 1–256 px
in both dimensions (a violation must be an internal error that quarantines the GUI, never an emitted provider), and the **asymmetric
art cap** of §11.2: the art box starts at row −243.

Evidence: [`q2-ascent-limit-1080p-s1.png`](../screenshots/gui-open-canvas/q2-ascent-limit-1080p-s1.png) (halved; green padded, the
256-px yellow bar, the four-times cyan, far glyphs at the window's left, right and bottom, and the two missing-glyph boxes),
[`q2-minimal-padding-1080p-s1.png`](../screenshots/gui-open-canvas/q2-minimal-padding-1080p-s1.png),
[`q2-mixed-font-fails-1080p-s1.png`](../screenshots/gui-open-canvas/q2-mixed-font-fails-1080p-s1.png) (a valid and a violating provider
in one font: only the missing-glyph boxes). Packs: `c2`, `c2b`, `c2mix`, `c2v`; drivers `b4.mjs`, `b5.mjs`, `b11.mjs`.

### 9.11 Tiles: seamless, in one font

*Measured.* The position-coded image in 256-px tiles, each tile its own provider with its own ascent, **all in one font**:

| Image | Tiles | Rows' ascents | Where | Result |
|---|---|---|---|---|
| 530 × 530 at (−177, −154) | 3 × 3: 256, 256, 18 wide and tall; the right column's PNG has 4 blank columns, the bottom row's 6 blank rows | 167, −89, −345 | 1920 × 1080 scales 1–4, 1280 × 720 scales 1–3, 854 × 480 scales 1–2 | **0 mismatching pixels** in 7.8 million compared |
| 1024 × 1024 at (−424, −243) | 4 × 4 of 256 | 256, 0, −256, −512 | 1920 × 1080 scales 1 and 2 | **0 mismatching pixels** in 3 million compared |

(Pixels under the vanilla "Inventory" label are excluded: the label draws over the art, §9.13.)

- **No seams at any scale.** A tile's pixels land on exact multiples of the GUI scale; there is no gap, overlap or filtering at the tile
  borders at scales 1, 2, 3 and 4.
- **One font holds every tile.** Providers with different ascents coexist in one font (`<ns>:gui`); no font per row or per band.
- **The advance is `ink width + 1`**, `ink width` being the rightmost column with any alpha ≠ 0, plus one (the rule of §4 *Title
  geometry*). Two tiles of 256 px side by side need a shift of **−1** between them; blank columns at a PNG's right edge do not
  matter (the 18-px tiles with 4 blank columns joined seamlessly).
- **Returning to the left for the next tile row is a negative shift** of the row's whole advance (up to −1024 here). It composes
  from the shift font's powers of two, as every other shift does.
- **The title stays short**: the 16 tiles of the 1024 × 1024 image are 16 glyphs plus 22 shift characters.

Evidence: [`q3-tiles-1024-1080p-s1.png`](../screenshots/gui-open-canvas/q3-tiles-1024-1080p-s1.png) (halved),
[`q3-tiles-seam-1080p-s4.png`](../screenshots/gui-open-canvas/q3-tiles-seam-1080p-s4.png) (scale 4, 1:1: the corner where four tiles
meet, at the middle of the crop; the black grid line every 32 image pixels runs through the seam without a step). Packs `c3`, `c4`; drivers
`b6.mjs`, `b10.mjs`.

### 9.12 GUI scale and window size

*Facts.* The GUI scale is clamped to what the window can hold: `scale ≤ min(⌊W / 320⌋, ⌊H / 240⌋)`. A larger configured scale is
reduced (measured: asking for 4 at 1280 × 720 gives 3; for 3 or 4 at 854 × 480 gives 2). The GUI-pixel area of the window is
`⌈W / s⌉ × ⌈H / s⌉` and the screen is centred in it. Art is cut by the window edge, never wrapped.

| Window | Scale | GUI area | `leftPos`, `topPos` of the 6-row screen | Art visible above / below / left / right |
|---|---|---|---|---|
| 1920 × 1080 | 1 | 1920 × 1080 | 872, 429 | 429 / 429 / 872 / 872 |
| 1920 × 1080 | 2 | 960 × 540 | 392, 159 | 159 / 159 / 392 / 392 |
| 1920 × 1080 | 3 | 640 × 360 | 232, 69 | 69 / 69 / 232 / 232 |
| 1920 × 1080 | 4 (the maximum, so "Auto") | 480 × 270 | 152, 24 | 24 / 24 / 152 / 152 |
| 1280 × 720 | 3 (the maximum) | 427 × 240 | 125, 9 | 9 / 9 / 125 / 126 |
| 854 × 480 | 2 (the maximum) | 427 × 240 | 125, 9 | 9 / 9 / 125 / 126 |

The 6-row screen is the tallest; a 1-row screen (132 px) has 45 px more room above and below at each scale. The formulas behind the measured numbers are `leftPos = ⌊(⌈W/s⌉ − 176) / 2⌋` and `topPos = ⌊(⌈H/s⌉ − imageHeight) / 2⌋`.

*Consequence.* At the largest scale a 1080p player sees 24 px above and below a 6-row screen; at scale 2, 159 px. Art past those
margins is invisible to that player, which is what the studio guides of §11.5 should show. Cropping at the window edge is what a
player expects from any tall art; nothing in the compiled output needs to know the window.

Evidence: [`q4-window-854x480-s2.png`](../screenshots/gui-open-canvas/q4-window-854x480-s2.png),
[`q4-window-1280x720-s3.png`](../screenshots/gui-open-canvas/q4-window-1280x720-s3.png) (halved: the markers above and below are cut by the
window). Drivers `b1.mjs`, `b3.mjs`, `b6.mjs`.

### 9.13 Draw order with open-canvas art

*Measured.* With a real double chest holding items, and art over them, at scale 2 on 1080p:

- A title whose art covers the whole screen (`c3`, 530 × 530): the **slot items and their counts draw above the art**; the **Inventory
  label draws above the art**, at its normal place (8, 128).
- Off-screen markers plus one on-screen marker over item slots (`c1r6`): items draw above the on-screen marker.
- A title that is **only a small marker over item slots** (`c5on`, no art covering the canvas): items still draw above it.
- Art drawn over the HUD hotbar (the marker below the screen) draws above the HUD, as part of the screen.

*Consequence.* §9.1 and §9.2 hold unchanged for open-canvas art: raster under text under slot items, and the Inventory label over
the art. **The invariant of §9.1 ("a title carries a full-canvas background glyph") is not needed.** An item that overlaps a glyph's
ink intersects the title's bounds (the union of the glyph quads), so it is placed in a node above it; an item that does not overlap
the art cannot be covered by it. The lone-marker case is the evidence. Padding rows add transparent area to a glyph's quad and do not
change what is drawn.

Evidence: [`q5-draw-order-tiles-1080p-s2.png`](../screenshots/gui-open-canvas/q5-draw-order-tiles-1080p-s2.png) (1:1 crop: items and the
label over the tiles),
[`q5-draw-order-lone-marker-1080p-s2.png`](../screenshots/gui-open-canvas/q5-draw-order-lone-marker-1080p-s2.png),
[`q5-draw-order-offscreen-1080p-s2.png`](../screenshots/gui-open-canvas/q5-draw-order-offscreen-1080p-s2.png). Driver `b7.mjs`, `b11.mjs`.
The hover highlight and the tooltip are not captured (§9.1).

### 9.14 Clicks outside the container

*Fact (bytecode).* `AbstractContainerScreen.hasClickedOutside` is pure geometry: a click is outside when `x < leftPos`, `y < topPos`,
`x ≥ leftPos + imageWidth` or `y ≥ topPos + imageHeight`; the client then sends slot −999. The packet,
`ServerboundContainerClickPacket`, carries `containerId`, `stateId`, `slotNum`, `buttonNum`, `containerInput`, `changedSlots` and
`carriedItem`: **no coordinates**. The client does not consult the title, so art does not change what a click is.

*Measured.* A real Materione GUI (3 rows; `gui.click_outside` and a `gui.click` on the gem's layer, each running `say`) opened on the
probe, its title replaced on the client by the `c1r6` art, and real client clicks made through `AbstractContainerScreen.mouseClicked`:

| Click | Server |
|---|---|
| On the art left of the screen, on the art right of it, on the art above it, on the art below it | `gui.click_outside` ran, each time |
| On empty ground far outside | `gui.click_outside` ran |
| Right button on the art left of the screen | `gui.click_outside` ran |
| Inside the container, on the gem's slot | the layer's `gui.click` ran |
| Inside the container, on an empty slot, and on the on-screen art over a slot | no rule (a plain slot click, as without art) |

*Consequence.* The wire is what §11.4 says: art outside the container is inert, and `gui.click_outside` is the only signal. Art over
the container's slots does not take clicks from them. The studio's "decoration only" badge for a clickable layer placed outside the
container is correct and needs no new server behaviour.

Evidence: [`q6-click-outside-1280x720-s2.png`](../screenshots/gui-open-canvas/q6-click-outside-1280x720-s2.png) (the GUI with art
drawn around it); server log markers `MTR275-CLICK-OUTSIDE` and `MTR275-CLICK-GEM`. Pack: `r275_click.yml`; driver `b9.mjs`.

### 9.15 A GUI without art

*Measured.* A chest titled with plain text; a title that is only a shifted text run in an offset font (`<shift>` then a vanilla-ASCII
provider at `ascent = 13 − y`); an empty title. Each renders as the vanilla chest with the title text where the offsets put it
(the text run at the predicted row and column), and nothing else. There is no texture to emit, and no background glyph is needed for the
title to work. The first-character rule of §4 *Title geometry* did not show: a title that starts with the glyph itself (no leading shift)
draws it exactly at the pen position (`c6first`), so the leading shift is harmless but not needed for placement.

Evidence: [`q7-text-only-1280x720-s2.png`](../screenshots/gui-open-canvas/q7-text-only-1280x720-s2.png),
[`q7-plain-title-1280x720-s2.png`](../screenshots/gui-open-canvas/q7-plain-title-1280x720-s2.png). Driver `b8.mjs`, `b12.mjs`.

## 10. Decisions taken in #183 (content type and component SPI)

These fill gaps the sections above leave open. Each one is reversible by the owner.

1. **Keyed file, not a bare mapping.** §2 now shows the GUI under its id key. The pipeline
   (`ParseStage`) reads one resource per top-level key with a mapping value, which is the
   master §7.2 rule for every content type; a bare `title:`/`container:`/`layers:` file would
   parse to no resource at all.
2. **Flat discovery in v1.** A GUI file is `guis/<name>.yml`, not `guis/**/*.yml`. The
   pipeline lists each content directory non-recursively for every type, so folder content
   is not compiled anywhere today (the editor can create folders that the pipeline skips).
   Nested GUI folders wait for a pipeline-wide decision about folders, not a GUI-only special
   case.
3. **Per-part diagnostics are a `ContentType` hook.** `ContentType.diagnose(parsed)` (default
   empty) returns the non-fatal problems of a definition that still compiles. The pack
   compiler publishes them against the resource. The GUI type uses it for `MTR-0052`, one
   diagnostic per skipped layer, targeting `layers.<index>`. The skipped layer is absent from
   `GuiDefinition.layers()`; it is never guessed at.
4. **Opacity belongs to raster layers, enforced.** A non-raster layer that sets `opacity:` is
   skipped (`MTR-0052`), rather than silently ignoring the value.
5. **Unknown layer keys are not errors.** Common fields belong to the gui type; the rest is
   handed to the component, which reads what it knows. Unknown keys stay in the file
   (lossless round trip), and a component may reject them only if it chooses to.
6. **Render methods are declared, not implemented.** `CompiledGuiLayer.paint`, `drawText` and
   `placeSlots` are default methods that throw `UnsupportedOperationException` with a `#184`
   reference. Each built-in implements parsing only; nothing calls the render methods until #184.
7. **Components register like content types.** The plugin's `PipelineService.registerBuiltins`
   registers the seven built-ins into `gui-components` through `Registry.register`, then the
   `gui` type. An addon uses the same registry, under the same bootstrap and reload window
   rules as content types. No `GuiComponentsApi` facade exists yet; if addons need one, it
   mirrors `MechanicsApi` in its own issue.
8. **Component configuration carries the geometry.** `GuiComponentConfiguration` includes
   `rows`, so a component can bound-check its own slots (the `item` component rejects a slot
   outside the GUI's rows) without the gui type knowing component rules.

### Decisions taken in #253 (GUI type organisation)

`GuiType` remains the content-type and schema facade; the package-private `GuiLayerParser`
owns ordered layer parsing and per-layer validation. The split preserves registry resolution,
component failure containment, schema order, diagnostics and the lossless authored document
(D1, D6, master §5.6). The §9 size guard in [logic.md](logic.md#9-code-organisation)
covers the facade, parser and #217 visual-compile helpers, not unrelated legacy GUI classes.

### Decisions taken in #184 (raster, background glyph and title)

These fill the gaps §4 leaves open. Each one is reversible by the owner.

1. **Pixels reach paint through the compile context, never through parse.**
   `CompiledGuiLayer.paint(RasterCanvas, TextureSource)` receives a `TextureSource` (api):
   decoded straight-alpha ARGB for a texture reference. The pack compiler supplies it through
   `PackEmitter.textures()`, bound to the pack's own `assets/textures/` folder (`PackTextures`,
   core). `PngDecoder` (ImageIO, core) reads gray images as raw samples, because `getRGB` would
   convert them through the linear gray colour space. `parse` stays pure: it never reads a file.
   A missing or unreadable texture makes the painting component throw; the compiler skips that
   layer with `MTR-0052`.
2. **Compile output travels through the emitter, diagnostics too.** `PackEmitter` gains
   `generatedAsset(path, bytes)` for compiler-made binaries, and `diagnostic(d)` so a compile
   hook can report a skipped layer without failing the GUI. The runtime needs neither: it calls
   `GuiCompiler.compile` and reads `GuiCompilation.model()`.
3. **The runtime model is `CompiledGui`** (`id`, `rows`, MiniMessage `title`, `accessibleTitle`,
   `slots`). The background is inside the title, so the runtime draws no raster itself.
4. **Background codepoints** come from the durable `gui-background` allocation (U+E200 upward,
   BMP only). A used-up block is `MTR-0053` (error) and quarantines that GUI only.
5. **Font keys:** `<ns>:gui` for the background; `<ns>:gui/smallcaps_y<Y>` and
   `<ns>:gui/default_y<Y>` for text, one offset font per (font, y). An identical provider from
   two GUIs is emitted once (`PackCompiler.Emitter.fontProvider`).
6. **Default-font text is restricted.** *(Superseded in part by #202: the "only the last run"
   rule is gone, since the vanilla widths are shipped. A run on row 4 or above and a non-ASCII run
   are still skipped with `MTR-0052`.)*
7. **Colours stay `#RRGGBB` or `#AARRGGBB`** (#183's `GuiComponentConfiguration.color`). The
   shared vectors use the same order, and the studio writes the same strings.
8. **Raster components accept negative origins and zero sizes** (`x`, `y` in −512..512;
   `width`, `height` in 0..512). A layer partly outside the canvas is clipped, and a zero-size
   layer draws nothing; the shared vectors (`clipping`, `button-1px-bevel`) require both.
   *(#276 widened the ranges to `x`, `y` in −1024..1024 and `width`, `height` in 0..1024 so the art
   cap of §11.2 is reachable; the canvas that clips is now the cap, not the screen.)*
9. **`slots` takes its chest rows from the layer's configuration** so it can place wells.
   Its defaults are the vanilla slot well (`#8B8B8B` interior, `#FFFFFF` highlight, `#373737`
   shadow), not the panel's.
10. **Layers composite with group opacity.** Each raster layer paints onto a transparent canvas
    first, so `opacity` applies to the layer as a whole, not to each of its pixels.
11. **Overlapping items.** A later item in the same slot wins, and the earlier layer is
    reported. Title-under-items is the intended draw order (raster, then text, then slot items);
    the 26.1.2 bytecode shows the title extracted in `extractLabels`, but the order of slot
    extraction still needs the probe (§9.1).
12. **Smallcaps is a placeholder** until #188 (see *Fonts for text layers*). The sheet is
    grayscale and generated in code; the pack ships it under `font/materione/smallcaps.png`.
13. **Goldens.** `fixtures/gui/pack/` holds the two shop textures; `fixtures/golden/gui/` holds
    the shop's pack-output summary and background PNG. The summary is byte-compared and the
    background is compared by pixels as well, so a JDK deflate change shows as a byte-only
    difference, never a pixel one.

### Decisions taken in #198 (GUI fonts stay out of the default font)

These fix a regression from #184: every generated font was referenced from
`minecraft:default`, so a pack with a GUI text layer replaced unstyled ASCII (chat, item names,
vanilla labels, toasts) with GUI glyphs. Each decision is reversible by the owner.

1. **Membership is declared per emission.** `PackEmitter.fontProvider(key, json, FontMembership)`
   takes `JOINS_DEFAULT` for glyph fonts (`GlyphType`) and `STYLED_ONLY` for every GUI font
   (`GuiType`). The pack compiler references only `JOINS_DEFAULT` fonts from `default.json`. A
   key emitted with both memberships is `STYLED_ONLY`. The two-argument `fontProvider` remains for
   addons compiled against older cores and means `JOINS_DEFAULT`, the glyph meaning it always had.
2. **A server pack's `default.json` merges with vanilla's, so the compiler prepends no vanilla
   includes.** Replacement would have required `minecraft:include/space`, `include/default`
   (`filter: {uniform: false}`) and `include/unifont` at the head of the file. The 26.1.2 client
   bytecode and the probe both show merging; runtime-systems §6 has the evidence.
3. **The shift font is still referenced from `default.json` whenever any font is generated**,
   GUI-only packs included, as before.
4. **Fixture and test.** `fixtures/packs/guitext` (a glyph, plus a GUI with one smallcaps and one
   default run) is served by the probe. `PackCompilerTest.guiTextNeverJoinsTheDefaultFont` pins the
   glyph reference, asserts that no GUI font is referenced, and checks the offset fonts are still
   written. The sample golden is unchanged because the sample pack has no GUI.

### Decisions taken in #188 (research: draw order, bitmap limits, smallcaps)

These answer §9. Each one is reversible by the owner.

1. **The smallcaps source is one text file.** `fonts/smallcaps/smallcaps.txt` holds the artwork.
   `SmallcapsFont` loads it from the classpath (the content build copies `fonts/`), and the studio
   parses the same file. Advances are derived from the pixels, never stored, and
   `fixtures/gui/smallcaps/advances.txt` pins them for both sides (`SmallcapsFontTest`,
   `webui/test/gui-font.spec.ts`). The #184 in-code table is gone.
2. **The sheet is RGBA** (opaque white ink, transparent background). This supersedes the grayscale
   rule of item 12 above; the grayscale sheet rendered as black cells on 26.1.2 (§9.5).
3. **Lowercase is a small capital (5 px), capitals are 6 px, space is 3 px.** The #184 placeholder
   drew lowercase with capital shapes and used a 4 px space. The golden `shop-compiled.txt` changes
   for the space advance and the sheet hash only; the background golden is unchanged.
4. **The target order stays title-under-items** (§9.1): raster, title text, slot items, highlights,
   carried item, tooltip. The title's background glyph must keep its bounds over every slot.
5. **The Inventory label is left alone in v1** (§9.2). The studio draws it as a ghost (#187). A
   pack language override of `container.inventory` stays an owner choice.
6. **No bitmap split in v1** (§9.3). A background is at most 176 × 222, under the 256-px glyph
   atlas. A canvas over 256 px is a diagnostic until bands exist.
7. **Vanilla ASCII advances ship as a numeric table (owner decision, §9.4).** The table is recorded
   here and implemented by the follow-up issue the owner files; #188 implements none of it. The
   default-font "defect" of the first draft was wrong: a pack's `default.json` merges with vanilla's
   (§9.6, #201), so no fix is needed.

### Decisions taken in #187 (studio: paint, image, text and item components)

These fill gaps the sections above leave open. Each one is reversible by the owner.

1. **Customize links a Materione base item (#203, superseding the copy this decision first took).** The first cut of #187 *duplicated* a Materione base, because the template pool was `templates/` only and `template: <item>` would have quarantined the new item. The owner approved items as templates (plugin.md §3.1, *Items as templates*), and Customize now creates `template: <base>` instead; see *Decisions taken in #203* below. Vanilla bases are unchanged (`material:`).
2. **A customized item's id may differ from the layer id.** The API names the file after the id and ids are unique per pack, so a clash falls back to `<gui-name>_<layer-id>` (the path stays under `items/gui/<gui-path>/`).
3. **Item layers address container slots only**, matching `ItemComponent` (the player inventory is vanilla and not an item layer target); the studio snaps and flags accordingly instead of writing a layer the compiler would skip.
4. **Pixel edits are commands outside the document.** The history tracks them by identity of the newest applied pixel command, so dirtiness is exact without copying canvases.
5. **Uploads keep the original PNG bytes** and are stored under `textures/gui/<gui-path>/`; they ride the layout transaction like a paint layer's PNG, so an undone or deleted layer never leaves a file behind.
6. **The default-font warning stays until #202 ships.** *(Resolved by #202: the text-after-`default` flag is gone with the restriction. The `default`-above-row-5 flag remains, as the server's verdict.)*
### Decisions taken in #185 (runtime, live refresh and preview)

These fill the gaps §5 leaves open. Each one is reversible by the owner.

1. **The live view is a plugin-side shadow view.** `RuntimeGuis` lives in `plugin/gui/`, not
   `core/runtime/` next to `RuntimeGlyphs`: it holds `CompiledGui` (content depends on core, so core
   cannot name it) and resolves `minecraft:` ids with `Material`. It is still a shadow-swap view,
   retrieved by class key, so the swap rule of runtime-systems §1 holds.
2. **Refresh hooks the swap, not the reload event.** `PipelineService.observeSwaps` is called after
   the swap and the recipes, on the global thread, and never for a rolled-back apply, so the refresh
   always reads the registries that are live. The reload event (`PipelineApi.subscribeReloads`) is
   per request, not per swap, and is raised by the reload path for the editor's push; it is the wrong
   seam for "what did this swap change".
3. **Viewers are found by holder, not by a tracked set.** A refresh schedules one region task per
   online player and inspects that player's own top inventory there. Nothing is read off-thread on
   Folia, and no viewer set can go stale when a window closes some other way.
4. **A base-title or size change on reload re-opens; other updates edit in place.** Dynamic title
   refreshes instead use the pinned platform transport (logic.md §6.4); this does not reopen the
   server inventory. A shape-changing reload creates a new window. The cursor item is moved off the
   old window before it closes and back onto the new one; a closed window (a removed GUI) keeps
   vanilla's return-to-inventory behaviour, which is the one case the cursor is not preserved.
5. **Two new diagnostic codes.** `MTR-0054` (a GUI slot's item is not live; warning, the slot stays
   empty) and `MTR-0055` (a permission node is missing; error). The api had no permission code, and
   `MTR-0009` describes a reference, not an actor. Both are documented in `plugin.md` §5.
6. **The editor's preview acts as the session owner.** The owner is the player who opened the session
   in game, as the trust flow already establishes. The editor's actor keeps the existing `node -> true`
   attribution of every editor op: the session is already trusted with authoring, so opening a GUI
   for its owner adds no authority. A console session has no owner and is refused, never guessed.
7. **The preview ops are gated on a `gui` session capability.** The jar advertises `gui` in
   `SessionBlob.CURRENT_CAPABILITIES` and the editor's protocol gate reads it: `runtime.openGui` and
   `runtime.previewTarget` are listed in `fixtures/protocol/editor-op-gates.json` with capability `gui`.
   The studio calls them, and shows the button, only when the connected blob advertises `gui`
   (`hasCapability(blob, 'gui')`, and a blob with no capability list counts as having none). A release
   without the ops therefore never receives them, and `scripts/compat/editor-compat.mjs check` passes
   against every committed release. The `since` value is `post-0.1.0`: the tree still reads 0.1.0 and
   the next version is not yet set.
8. **The title is parsed without the live emoji registry.** `MaterioneText.parseData` keeps glyph and
   emoji markers literal, and the compiler has already laid the text out with its own fonts, so an
   emoji substitution would break the pixel layout. `<shift>`, `<font>` and colour tags still resolve.

### Decisions taken in #204 (the typed GUI API, and the studio on top of it)

These move the GUI rules from the studio into the API (api.md §3.9). Each decision is reversible by the owner.

1. **The verdict is the server's.** `analyzeText` and `analyzeItems`, and the TypeScript copy of the compiler's rules they carried, are gone. The studio sends the document it shows to `gui.validate`, debounced, and draws each diagnostic on the layer it names (`layers.<index>`, the draft's position). A diagnostic with no layer (a parse failure) is shown for the document. Every compile diagnostic about a layer is a skip, so the severity is `skip` alone; the studio's old `note` (the default run that blocks the runs after it) is no longer a separate message. The skipped runs after it carry their own, from the compiler.
2. **Customize's naming and refusals moved server-side unchanged** (`GuiItemNaming`, `GuiAuthoringApiImpl.customizeItem`): the layer id when the pack has no resource of that id, else `<gui>_<layer>`, else numbered; a vanilla base is a `create` with `material:` (refused when the server's material list does not know it); a Materione item was a `duplicate` (now a template link, see *Decisions taken in #203*), with a refusal when it is not an item of this pack or the layer already has its own. The seam for #203 was the one place a Materione base is copied (`GuiAuthoringApiImpl.customizeItem`).
3. **`edit` returns the typed view, not the raw document.** `GuiAuthoringApi.edit` returns `GuiDocumentView` (the view `get` returns, plus the revision) and carries the compiler's warnings as the successful result's diagnostics. The raw `DocumentView` is what `setFields` returns; the view is what a GUI consumer reads.
4. **Binaries reuse `AuthoringApi.ModelAsset`**, the shape `setFields` takes, rather than a second `AssetUpload` record.
5. **Layer-list edits rewrite the `layers:` block.** `edit` and `customizeItem` write the whole `layers` list through `setFields`/`bulk`, as the studio's save has since #183. Unknown keys inside a layer survive; comments inside the `layers:` block do not (the lossless writer addresses mapping keys, not list items). A list-item path in `LosslessYamlDocument` would lift this; it is not in this change. An edit that changes nothing writes nothing.
6. **One GUI per file for the typed API.** `get`, `edit`, `customizeItem`, `render` and `validate` work on a file's single GUI resource and refuse a file that holds several (the typed view has no resource id to choose by). The studio, which opens by resource id, is unaffected.
7. **Render and validate allocate nothing.** They compile through the pack's own steps with `AllocationTable.preview()`, a view that answers what the table would and binds nothing. The background codepoint does not change a pixel or a diagnostic, so `render` returns the bytes the pack ships; exhaustion (MTR-0053) is still reported exactly. *(#276: the wire background is now the screen-sized preview, and tiles are allocated only when emitted.)*
8. **Validate and the pack compiler run one sequence.** The per-resource sequence (parse, diagnose the parts, compile, with the crash and rejection diagnostics and quarantine entries in the order the pack publishes them) lives in `core.pipeline.ResourceCompiler`. `PackCompiler` runs it for every resource of a pack with the bus and the quarantine tracker as its sink. `GuiAuthoringApiImpl.check` runs it for a saved file or a draft with a collecting emitter and the non-persisting allocation view. Nothing is copied, and `content` and `pack` each depend only on `core`. `GuiValidateParityTest` stays as a regression guard on the wiring: it compiles a GUI with every kind of skip through `PackCompiler` and asserts `validate` returns the same diagnostics. The template pool is built from a different source on each side (the pack's resources, and the authoring catalog for an API call), but it is data, not the sequence.
9. **Components are described from the API.** `GuiComponentDescriptor` (api) is built from the registry entry; `EditorContentOps` serializes it and nothing else. `GuiComponentsApi` (api §3.10, `MaterioneAPI.guiComponents()`) registers addon components on the bounded path `mechanics()` uses, so a registration works after the registry froze and is removed by its owner's disable.
10. **The `gui` capability is this change's, with #185's name.** Both PRs advertise `gui` and gate their ops on it. The five ops are listed in `editor-op-gates.json`; an older release that lacks them is judged by the compat check as a gated op, so the studio must check `hasCapability(…, 'gui')` before calling any of them.
11. **Pending changes are saved before Customize.** Customize is one server transaction at the GUI's revision; the layout and pixels that are pending are saved first as their own transaction, because the server works on the saved file. The earlier single `authoring.bulk` that carried both is gone. Nothing is lost if the customization is refused: the save stands, and the pointer is applied to the studio's copy only after the server answers. Reverting this means sending the pending layout inside `gui.customizeItem`, which needs a list-item path for a per-layer patch (decision 5).
12. **The studio keeps `authoring.setFields` for saves.** Its layer model is a whole-list diff, so the changed `layers` value is the natural unit; `gui.edit` is the structural path for API consumers. Switching the studio's save would mean deriving the five structural edits from the diff, which loses the one-shot "the layout as it stands" property the studio relies on.
13. **Without the `gui` capability the studio degrades, and says so.** A connection whose session blob does not advertise `gui` (an older plugin, or the demo, which has no blob) gets no verdict and no Customize: the studio shows an explicit banner, draws no skip flags, and disables Customize with the reason. Layout editing and saves are unchanged, since they use `setFields` as before.
14. **Demo and browser tests.** The demo server has no compiler, so its studio reports the verdict as unavailable; `gui-studio.pw.ts` asserts that state, and the live Customize flow is pinned by the QA catalog (`gui.studio-item-customize`) on a running server.

**Preview allocation prerequisite for #217.** A preview now snapshots the table and tracks
its own allocations and counters. Multiple glyph requests in one preview therefore get distinct
values in the same order as a real compile, including range growth and tombstones; no preview
binding is persisted or shared with a later preview. The snapshot does not predict allocations
made concurrently after it was created.

### Decisions taken in #202 (vanilla ASCII advance widths)

These implement §9.4 and lift the `default`-run restriction. Each decision is reversible by the owner.

1. **One numeric table, one source.** The 95 advances (space included) live in `fonts/vanilla-ascii/advances.txt`. The Java compiler (`VanillaAscii`, read from the classpath the way `SmallcapsFont` reads its file) and the studio (`font/vanilla-ascii.ts`, imported raw) parse that file, so nothing is hand-copied. The golden (`fixtures/gui/vanilla-ascii/golden.txt`) pins the table's canonical digest and histogram, and both sides test it. The texture is never written anywhere.
2. **The table is derived, not remembered, and the derivation is reproducible.** `scripts/gui/derive-vanilla-ascii-advances.mjs <client.jar>` reads `assets/minecraft/textures/font/ascii.png` and `assets/minecraft/font/include/space.json` from the client jar (a zip read with Node's built-in `zlib`, no dependency) and applies the client's bitmap rule to each 8 px cell. `--check` fails when the file on disk differs from the jar. The source is the Minecraft 26.1.2 jar of the launcher install; the 26.2 jar yields the same table. The texture is a 128 × 128 **1-bit palette** PNG: index 0 is transparent, index 1 is white ink, so the derivation decodes palette and `tRNS` alpha as well as RGBA. Its SHA-1 is in the table's header.
3. **Space is 4, from the space provider.** Its cell in `ascii.png` is empty, so its width comes from `include/space.json`, the value the offset font's `space` provider already emitted (`GuiFonts.defaults`).
4. **The restriction is lifted by width, not by a rule.** A `default` run moves the pen by `VanillaAscii.measure`, so `GuiTitle` no longer tracks an unknown pen (`canPlace` is gone) and any run may follow any other. A test pins a `default` run followed by a `smallcaps` run and by a second `default` run, at the measured shift.
5. **What remains of the restriction.** A `default` run must start on row 5 or lower (`ascent ≤ height`: the vanilla cell is 8 px and the ascent for row `y` is `13 − y`), so rows 0–4 stay `MTR-0052`. Non-ASCII in `default` stays `MTR-0052`, since the table covers `0x20`–`0x7E` only. Neither is a width question, so no table solves either.
6. **Diagnostics.** `MTR-0052` loses its "text placed after a `default` run" cause (plugin.md §5). The studio's text-after-`default` flag goes with it, because that flag is the server's verdict and the server no longer reports the case. The `default`-above-row-5 flag remains.
7. **The studio's `default` preview keeps its approximate glyphs and says so**, but its pen and selection box use the exact widths. Only letter shapes are approximate now.
8. **Existing goldens do not change.** The shared raster vectors and the pack goldens contain no `default` run after another run, and the `guitext` fixture has its `default` run last, so their bytes are unchanged.

### Decisions taken in #275 (research: the open canvas on the real client)

These record what §9.8–§9.15 measured and the rules §11.3 draws from it. Each one is reversible by the owner.

1. **Off-screen, negative-ascent and tiled title art is drawn exactly where the formulas put it**, with no clipping to the screen, at every GUI scale and window size measured (§9.9–§9.12). Nothing in the approved design's model is contradicted.
2. **The art cap is asymmetric at the top.** The client bounds a glyph's top at row −243 (`ascent ≤ height ≤ 256`, §9.10), so the box is x −424..599, y −243..780 (§11.2). The top edge is a client fact; the other three are the owner's size ("about 1024") and may grow, since no limit was found below or to the sides.
3. **`ascent ≤ height` becomes a compiler invariant, not a hope.** One violating provider erases the whole `<ns>:gui` font file of the pack, every GUI's art and text with it (§9.10). The compiler asserts it for every provider it emits and pads tiles with the fewest transparent rows that satisfy it.
4. **Tiles share one font.** No per-row or per-band font (§9.11); this supersedes the `<ns>:gui/band<k>` sketch of §9.3.
5. **§9.1's full-canvas-glyph invariant is retired** (§9.13). The title's bounds already cover every pixel the art inks.
6. **The leading no-ink shift of a title stays** (it is harmless and the shipped goldens have it), but it is not needed for placement: no first-character offset was measured (§9.15).
7. **1.21.4 is unverified** for all of this (§9.7); the rules are stated for the 26.1.2 client and expected to hold on the floor version.

### Decisions taken in #276 (open canvas compiler)

The compiler half of §11. Each one is reversible by the owner.

1. **One flatten over the art cap, kept apart from the screen flatten.** `GuiFlatten.flatten` takes the rectangle's origin;
   `GuiCompiler.compile` calls it over `x −424..599, y −243..780` (`GuiArt.CAP_*`) and the public
   `GuiCompiler.flatten(width, height, …)` stays a screen-sized call, because the shared raster vectors (including
   `clipping`) pin it and no pixel rule changed. `ArgbCanvas` gained an origin (every coordinate stays a GUI pixel), a written
   rectangle (so clearing, compositing and the bounds scan visit only what a layer touched), and a flag for a visible pixel
   clipped by its edge, which is how a layer past the cap is detected. `panel`, `button` and `image` accept `x`, `y` in
   −1024..1024 and `width`, `height` in 0..1024 (#184 decision 8 allowed ±512), so the whole cap is reachable and a layer past it
   is clipped and reported (`MTR-0090`) rather than rejected. `text` layers keep `x`, `y` in 0..512: text outside the screen is
   not part of this issue (the small-caps cell would need its own row limit, `ascent ≤ 16`).
2. **Codepoint keys.** The tile at grid row 0, column 0 of the static background keeps the GUI's own allocation (the GUI id, kind
   `gui-background`), and the first tile of an overlay frame keeps the existing
   `minecraft:materione_gui_overlay/<hex ns>/<hex gui>/<hex layer>/<frame>` key. **An existing GUI whose art is one tile keeps its
   codepoint, its PNG path and its provider; its title is unchanged when its raster starts at the screen's top-left.** Every other
   tile appends `tile/<row>/<col>`: `minecraft:materione_gui_tile/<hex ns>/<hex gui>/tile/<row>/<col>` for the background and
   `…/<frame>/tile/<row>/<col>` for an overlay. Row and column count from the painted bounds' top-left, so moving a whole piece of
   art keeps its codepoints; growing it to add a tile adds allocations and never moves existing ones. A GUI that paints nothing
   asks for no allocation, so the table holds no entry for it (an old entry stays, unused, and is never reused). `GuiCodepoints`
   is the compiler's way of asking, once per emitted tile; the runtime answers from the table without allocating (D7), and a
   missing entry skips that GUI's shadow build with the existing diagnostic.
3. **Texture paths.** The first background tile stays at `font/materione/gui/<id>.png`; other tiles are
   `font/materione/gui_tile/<hex id>/<row>_<col>.png`, and overlay tiles `…/<frame>/<row>_<col>.png`. Hex keeps ids that contain
   `/` from colliding with a tile folder.
4. **The pen.** `GuiTitle` tracks an absolute pen from 8: `art(tiles)` shifts to each tile's `tx`, advances by `inkWidth + 1`, and
   ends at 8, so a screen-sized background is the same string as before and an overlay frame with no tile appends nothing. The
   leading no-ink shift of #275 decision 6 is now simply the first tile's shift.
5. **Emit-time invariants** (`GuiArt.check`, new `MTR-0091`, error): `a ≤ H`, `1 ≤ tw ≤ 256`, `1 ≤ H ≤ 256`, `ty ≥ −243`. The check
   runs before the codepoint is asked and before any asset is built, as a `CompileRejectException`, so `GuiType.compile` emits
   nothing for the GUI and the pack quarantines only it. The cap makes it unreachable for authored content; a canvas of another
   origin and direct calls are tested, and a golden pins one padded tile.
6. **A GUI with dynamic visuals has no static background**: no PNG, provider, codepoint or glyph (before, it emitted a blank
   screen-sized PNG and one provider). Its title starts empty; `GuiVisualPlan` lost `background` and `backgroundAdvance`, and a
   frame's glyph is a list of tiles (`GuiTitle.ArtGlyph`).
7. **Clicks outside the container** warn with new `MTR-0092` (§11.4): a `button`, or any layer a `gui.click` rule names with
   `layer:`, whose click rectangle covers no container slot. The check is in `GuiType.diagnose`, from the raw rules and the layers'
   own click areas, so it needs no flow engine, and an unsized image (whose rectangle needs its texture) is not judged. The layer
   is kept and drawn.
8. **`GuiRender.background` stays the screen-sized preview** (176 × height) the vectors pin. It is flattened for the preview and is
   no longer the bytes the pack ships, since the pack ships tiles. The studio draws the open canvas itself (#277).
9. **`GuiCompilation.assets()` is sorted** (it was an unordered `Map.copyOf`), so emission order is deterministic (D7).
10. **A `paint` layer owns its position (§11.2, for #277).** It reads `x`, `y` (default 0, −1024..1024) and draws its PNG 1:1
    there; `width` and `height` (0..1024) record the trimmed size. A layer with a zero `width` or `height` has nothing painted:
    it draws nothing and its `image` need not exist. A layer without these fields sits at the origin, so canvas-sized paint
    layers written before #277 compile as before.

### Decisions taken in #278 (the starter, the showcase and the vanilla corners)

These are the owner's reversible choices as built. The rules themselves are the shared vectors' (`fixtures/gui/raster/README.md`, *Corners*).

1. **A new GUI has no layers.** The starter file holds `title` and `rows` only. The server's New GUI scaffold (`core` `Scaffolds`, mirrored byte for byte in `web` `Scaffolds`, pinned by `ScaffoldSchemaConsistencyTest`) and the studio's `starterDocument` both say so. Existing files are untouched: nothing rewrites a layer that is already there.
2. **The showcase drops its frames.** The five GUIs of `fixtures/packs/showcase` lose their full-screen `frame` panels; the vanilla chest is the background, so no panel is kept and no panel covers the player inventory. `ShowcasePackTest` stays green unchanged.
3. **The field is `corners` on `panel`, values `square` and `vanilla`.** An absent field is `square` in both the Java parser (`PanelComponent.corners`) and the TypeScript raster (`flatten.ts`). That is the back-compat rule: a file written before this change, or one that does not set the field, renders exactly as before. The studio writes `corners: vanilla` only on a panel it creates (a preset in `registry.ts`'s `newLayer`, not in the schema); the schema default is `square`, the value an absent field means. Lossless: the field is written only when the author or the studio sets it, and no existing layer gains it.
4. **The vanilla corners are the chest's, pixel for pixel, on the top.** The top-left cut is two transparent pixels of a diagonal (`x + y <= 1`), the top-right cut is one pixel larger (`dx + y <= 2`), as the owner's art has it, and the `(3, 3)` highlight of the top-left step is reproduced. The bottom corners are square outline, because the chest's bottom edge is the inventory separator (a light line, not a bevel) and a bottom bevel would not match the art. Frames at least 8 x 8 are exact at any size.
5. **The chest is the test.** `vanilla-chest-corners` renders a 176 x 125 vanilla panel (the top part of a 6-row chest). `VanillaChestCornersTest` checks it against `webui/static/vanilla/generic_54.png`: both top 12 x 12 corners, and the outer outline columns, match with no mismatch.
6. **Goldens.** `fixtures/golden/editor-demo-snapshot.json` changes twice: the panel schema gains `corners`, and the New GUI scaffold text loses its layers. Both come from the Java source and were regenerated with `-Dmaterione.golden.regen=true` (`EditorDemoSnapshotTest`). No other golden changed; the two existing vectors `chest-6-rows` and `chest-1-row` keep their PNGs (their JSON descriptions now say they are square-cornered).
7. **Not yet seen in a client.** The corners are verified against the art's pixels, not against a running client. The QA visual entry `gui.vanilla-corners-visual` (`qa/catalog/gui.mjs`) is for the next visual pass.

### Decisions taken in #277 (open canvas studio)

Each one is reversible by the owner.

1. **The underlay is drawn, not stored.** The vanilla chest is composed from `static/vanilla/generic_54.png` for the row count and drawn under the art; it is not a layer, is never saved and is not in the history (§11.2). The veil is a translucent grey (`0xA0303030`, source-over) on the opaque vanilla pixels of the inventory part; it lifts under any pixel of the composed art with alpha above 0, smallcaps title text included (an opaque ink pixel hides what is under it either way).
2. **A paint layer's bitmap is the truth for its size.** The layer is drawn 1:1 at (`x`, `y`) with the PNG's own size, as the compiler blits it; `width`/`height` in the file mirror the bitmap (and stand in for it only until it has loaded). A layer from before the open canvas (no `x`, `y`, `width`, `height`, a canvas-sized PNG) opens as it is and is rewritten, trimmed, by the next save that writes the layout or a texture (a save of the title, the rows or the logic alone never rewrites art).
3. **Growing and trimming.** A stroke or fill grows the layer's box to hold what it painted, in the same undo step; erasing never shrinks it while editing. **Save trims** every paint layer to the box of its pixels with alpha above 0 and writes the layer's `x`, `y`, `width`, `height` with it. The history keeps its patches in GUI pixels with the layer's box before and after, so a save in the middle of the history does not break undo. A layer whose bitmap could not be read is never trimmed (a failed read cannot empty a layer).
4. **An empty paint layer.** A paint layer with no painted pixel (never painted, cleared, or erased) is saved with `width: 0` and `height: 0` (and its last `x`, `y`), **no PNG**, and its `image:` reference kept; a stale file from an earlier save is left where it is. This follows the zero-size rule of §10 *Decisions taken in #184* (8): a zero-size layer draws nothing. The server must accept the layer without the texture existing (#276's compiler); until it does, the compiler reports the missing texture of such a layer like any other.
5. **Fill is bounded.** Fill runs on the layer's box joined with the screen (clipped to the cap), so it fills empty space on the screen and the inside of painted shapes, and never floods the art cap. A click outside that area says so.
6. **Painting is clipped to the cap.** Strokes run on a working copy of the cap's size, so pixels outside `x -424..599, y -243..780` are never painted; a stroke that starts outside says why. The cap is geometry the studio needs to draw and clip; whether art past it is an error is the compiler's (`gui.validate`), and the studio holds no copy of those rules.
7. **The eyedropper** samples what Materione draws first (the composed art with its alpha, as the player sees it), then the vanilla chest under it, in its own colour and not the veiled one; open space and the transparent corners of the sheet say "transparent".
8. **The backdrop rule is generalised, not dropped.** A layer covering the whole screen is still what a drag on it marquee-selects over (a click selects it, and once selected it moves); with the open canvas there is also open space with no layer under it, where a drag always marquee-selects. A marquee selects a paint layer when it covers one of its painted pixels (paint layers now have real boxes).
9. **Decoration only is geometry, and it is the compiler's rule.** A button, or a layer with a click rule, whose click rectangle covers no container slot (the 16 x 16 item areas of the chest's slots) is badged, with the reason: outside the screen it arrives as `click_outside`; on the screen over no slot a click is a plain click (§9.14). A layer that covers a slot and also leaves the screen is badged "partly". This agrees with MTR-0092 (#276), which the same layer also gets through `gui.validate`.
10. **Guides** are drawn for 1920 x 1080 at scales 2, 3 and 4 as §11.5 says. The "small window" guides (1280 x 720 at 3, 854 x 480 at 2) are not drawn yet; `windowGuide` already computes them.

---

## 11. The open canvas (#274) — design approved by the owner; built (#276, #277, #278)

> **Status.** Approved by the owner on 2026-11-05. It replaces the bounded canvas of §1, the starter GUI
> of §6 and the full-screen flatten of §4 step 1 **as each issue lands**: #275 (research), #276
> (compiler), #277 (studio), #278 (starter, showcase, panel corners; **landed**, §11.6). Until an issue lands, the
> sections it replaces still describe the shipped behaviour. **#275 has landed:** the facts this section
> marked *to verify* were measured on the 26.1.2 probe client (§9.8–§9.15) and the rules below are the
> verified ones, with the exact numbers #276 needs. One finding amends the design: the art box is
> asymmetric at the top (§11.2). 1.21.4 is not measured (§9.7). **#276 has landed:** the compiler and the
> runtime implement §11.2's flatten and §11.3 rules 1 to 6, and the warning of §11.4 (see *Decisions taken in
> #276*). **#277 has landed:** the studio side of this section is built (§6 *As built (issue 277)*): the vanilla
> underlay and the veil, the open canvas with the cap and the player-window guides, and paint layers that own
> their box. With #278, every part of the design is built; the visual QA entries await a blessing run.
> This section describes the **chest**. How the same model extends to other containers (a profile per container for the
> screen size, the slots, the title origin, the art cap's top and the veil) is §12.5.

### 11.1 Why

- **The chest was redrawn, not shown.** A new GUI started as a `panel` and a `slots` layer that
  repainted the chest with our own bevel, whose 1-px corner cut does not match the client's rounded
  corners. Every GUI shipped a full-screen background, even a transparent one.
- **The showcase covered the player inventory** with a 176 × 168 frame, so the player's own items
  floated on flat grey without their slot grid.
- **Art was boxed into the screen** (`176 × (114 + 18·rows)`), although the title glyph that carries
  it is not clipped to the screen, and §9.3 already found the negative ascent and the 256-px tiles that
  larger art needs.
- **A GUI should be worth making without any art.** Materione's logic should stand on its own: a GUI
  with no custom pixels must cost nothing in the pack and look exactly like a vanilla chest.

### 11.2 The model

**The vanilla chest is the base, not a layer.** The client draws its own chest under the title, so
Materione never compiles or stores it. The studio draws the owner's reference art
(`webui/static/vanilla/generic_54.png`, the 176 × 222 chest on a 256 × 256 sheet) under every GUI,
composed for 1–6 rows exactly as the client composes it: the top `rows·18 + 17` px of the sheet, then
the 96-px player-inventory part from `v = 126`. A new GUI has **no layers**.

**Coordinates.** GUI pixels, origin at the top-left of the vanilla screen, as before. A layer may sit
anywhere within the **art cap**, so negative positions and positions past the screen are ordinary. The cap is the
box **x −424..599, y −243..780** (1024 × 1024): 512 px either side of the screen's centre horizontally, and from the
highest row the client can draw (−243: a glyph's top is bounded by `ascent ≤ height ≤ 256`, §9.10) down 1024 px. **The top edge is
a client fact, and it makes the box asymmetric** (a box "around the screen" would reach as far above as below), so the box reaches 243 px above the screen and 559 px below a
6-row one. The other three edges are a size choice: the client showed no limit below or to the sides (§9.9), so the owner may
widen them without changing any rule of §11.3.

**Three regions:**

| Region | Where | In the studio | Rules |
|---|---|---|---|
| Container | the chest's top part and its slots | drawn normally | the only slots Materione controls; `item` and `list` layers live here |
| Player inventory | `y ≥ rows·18 + 17` inside the screen | the vanilla art under a **grey veil** | the veil **lifts per painted pixel**: a pixel of any raster layer is drawn at full strength, the unpainted vanilla pixels around it stay veiled. Items are the player's own, so no `item` layer here |
| Outside | everything past the screen edge, within the cap | open space with the screen's outline and the cap's box | any raster or text layer may draw here; nothing here is clickable (11.4) |

**Nothing transparent is stored.** The compiler flattens over the **painted bounds** (the smallest
box holding every pixel with alpha > 0), not over the screen, and places the glyph by shift and ascent
from that box's origin. A GUI whose raster layers paint no pixel emits **no PNG, no font provider and
no background glyph**; its title is its text runs (or the plain title) alone. The same holds for each
dynamic overlay glyph (§4 step 7): each is cropped to its own bounds.

**Paint layers own their geometry.** A `paint` layer carries `x`, `y`, `width`, `height` like an
`image`; the studio grows it as strokes leave it and trims it to its painted bounds on save. No paint
PNG is canvas-sized any more. *(Built in #277.)*

### 11.3 Compiling art past the screen (rules for #276, verified by #275, built)

Every number below was measured (§9.9–§9.12); the formulas reproduce the probe's captures pixel for pixel. Symbols: `(tx, ty)` is a
tile's top-left in GUI px (origin: the screen's top-left), `tw × th` its art size, `a` its provider's `ascent`, `H` its cell height.

**1. Clip to the art cap** (§11.2): everything outside `x −424..599`, `y −243..780` is dropped before anything else and reported once
per layer as `MTR-0090`, never quarantining the GUI.

**2. Cut into tiles.** The flattened raster is cut on a 256-px grid anchored at the painted bounds' top-left; a fully transparent
tile is not emitted; each remaining tile is cropped to its own ink box (alpha > 0), which sets `(tx, ty, tw, th)`. A tile is at most
256 × 256. (A raster of up to 256 × 256 is one tile and never splits.) Cropping is free: the client places a glyph by its ink.

**3. One provider per tile, in `<ns>:gui`:**

```
a   = 13 − ty                                   the provider's ascent: the tile's top row is 13 − a on screen
H   = max(th, a)                                the cell: the PNG's height, and the provider's height
pad = H − th                                    transparent rows below the art; 0 unless the tile ends above row 13 (ty + th < 13)
{type: bitmap, file: <ns>:font/materione/gui/<id>.png, ascent: a, height: H, chars: [cp]}   PNG is tw × H, art in rows 0..th−1
```

Because the cap keeps `ty ≥ −243`, `a ≤ 256`, so `H ≤ 256` always holds and no tile ever needs more than the client's 256-px cell. A
zero or negative `a` (a tile starting at row 13 or lower) is valid and needs no padding. `height` must equal the PNG's height: any
other value rescales the art (§9.10). **Every tile of every GUI goes in the one font `<ns>:gui`**: providers with different ascents
coexist, so there is no font per row or per band (§9.11). Codepoints come from the durable `gui-background` allocation, as today,
keyed by GUI and tile (`…/<hex gui>/tile/<row>/<col>`; the dynamic overlays of §4 step 7 add the layer and frame before the tile).

**4. Emit-time invariants (an internal error that quarantines the GUI, never an emitted provider).** For every provider: `a ≤ H`,
`1 ≤ tw ≤ 256`, `1 ≤ H ≤ 256`, `ty ≥ −243`. A single provider with `a > H` makes the client drop the whole `<ns>:gui` font file
for the pack (§9.10), so this assertion is the guard for every GUI at once; a test must pin it, and a golden must pin one padded
tile (`ty + th < 13`).

**5. Title.** The pen starts at `8` (`titleLabelX`). For each tile, in row-major order:

```
shift = tx − pen          emit <shift:shift> (nothing when 0), then the tile's glyph
pen   = tx + inkW + 1     inkW = the PNG's rightmost column with alpha ≠ 0, plus one; after cropping, inkW = tw
```

Two 256-px tiles side by side therefore need a shift of −1 between them, and the next tile row begins with the negative shift of the
whole row (§9.11). The shift characters decompose as every other shift does (`ShiftMath`; the largest magnitude here is 1024, well
inside its 65,536 bound). A **piece** (the background group, or each dynamic overlay glyph group, §4 step 7) is self-contained:
it starts at pen 8 and **ends by returning to pen 8** (`<shift:8 − pen>`), the convention the shipped title already has, so
`TitleRenderer` can assemble pieces and text runs in any order and drop any of them. Nothing else about the title changes. The
leading no-ink shift is kept for compatibility with the goldens; no first-character offset was observed (§9.15).

**6. Nothing painted, nothing emitted.** When no raster layer paints a pixel the GUI has no PNG, no provider, no codepoint and no
glyph: the title is its text runs, or the plain title (§9.15). The same holds per overlay glyph.

**7. The window decides what is seen, not the compiler** (§9.12). Tiles are compiled for the whole cap box; there is no
per-resolution variant.

*Worked example (a 1024 × 1024 image at x −424, y −243).* Four rows of four tiles; `ty` = −243, 13, 269, 525, so `a` = 256, 0, −256, −512
and `H` = 256 for all (no padding, since `th = 256 ≥ a` whenever `a ≥ 0`, and `a ≤ 0` needs none); the title is 16 glyphs and 22 shift
characters. This is the `c4` case of §9.11, pixel-exact at scales 1 and 2.

*Worked example (a thin banner above the screen).* A 120 × 12 banner at (28, −30): `a` = 43, `H` = max(12, 43) = 43, `pad` = 31
transparent rows; the PNG is 120 × 43. Without the padding the client refuses the font (§9.10).

### 11.4 Clicks outside the container (verified, §9.14)

The client sends a click outside the container as "outside" (slot −999) with no position; the packet has no coordinates field and the
client never consults the title. A clickable layer (a `button`, or any layer a `gui.click` rule filters on) drawn outside the
container cannot be told apart from any other outside click: measured, a click on art drawn left, right, above and below the screen
ran `gui.click_outside` each time, and art over a slot did not take the slot's click. The studio marks such a layer as **decoration
only**, with the reason, and the compiler warns with `MTR-0092` (a `button`, or a layer a `gui.click` rule names, whose rectangle covers no container slot; the layer is kept and drawn). `gui.click_outside` stays the only trigger for that area. No server behaviour changes.

### 11.5 What the player's window cuts (verified, §9.12)

The title is not clipped to the screen at any scale or window size; the window edge cuts art, never wraps it. The GUI scale is clamped to
`min(⌊W / 320⌋, ⌊H / 240⌋)`, and the screen is centred in a `⌈W / s⌉ × ⌈H / s⌉` area. For a **6-row screen** (the tallest) the art
visible on each side is:

| Window and scale | Above / below | Left / right |
|---|---|---|
| 1920 × 1080, scale 2 | 159 | 392 |
| 1920 × 1080, scale 3 | 69 | 232 |
| 1920 × 1080, scale 4 (Auto) | 24 | 152 |
| 1280 × 720, scale 3 (Auto) | 9 | 125 |
| 854 × 480, scale 2 (Auto) | 9 | 125 |

The studio draws guides for what fits around the screen at GUI scales 2, 3 and 4 on a 1920 × 1080 window, so an author sees what most
players will see; the last two rows are worth a "small window" guide. A 1-row screen has 45 more px above and below.

### 11.6 What stays

- The `panel` and `slots` components stay for authors who want to repaint the chest. **Built in #278:** `panel`
  takes `corners: vanilla | square` (§11.6, *Decisions taken in #278*); the studio starts a new panel with
  `vanilla`, and a file without the field keeps the square corners it always had.
- Text layers, item layers, `visible_if`, `frames` and the dynamic path (§4 step 7) are unchanged apart
  from cropping and placement.
- The vanilla **Inventory** label is still drawn by the client over everything (§9.2).

### Decisions taken in #203 (items usable as templates, Customize links)

The owner approved items as templates on #187 (PR #200). The rules live in plugin.md §3.1, *Items as
templates*; this records what they mean for the GUI studio. Each decision is reversible by the owner.

1. **Customize writes a link, not a copy.** For a Materione base item the new `items/gui/<gui-path>/<id>.yml` is
   `template: <base id>` and nothing else (`GuiAuthoringApiImpl.customizeItem` creates the item with the
   scaffold's `material` and `display_name` removed in the same `authoring.bulk` call, so the GUI pointer and the
   link are still one all-or-nothing transaction). The base id is written bare (the item is in this pack, so a bare
   reference resolves in the new item's own namespace). Reading the new item's file tells the whole story; the
   merged values come from the one resolver, so the inline Appearance shows them as inherited.
2. **The base is followed, on purpose.** Renaming the base, or changing its lore, texture or abilities, changes every
   customized item that links to it and has not overridden that field; the pack and the open GUI windows follow on the
   next reload like any other change. A designer who wants a frozen copy uses the item studio's **Detach template**
   (copies the inherited values in, drops the link) — an ordinary write, not a Customize mode.
3. **A template with the base's id refuses Customize.** The reference would resolve to the template (the precedence
   rule), so the link would silently name the wrong thing; the refusal says to rename one of them. This is the one
   place the studio does not fall back to a copy, so the two outcomes ("linked" and "copied") never differ without
   the designer being told. `itemResource` also stops picking a template over the item that shares its id when it
   looks the base up.
4. **Vanilla bases are unchanged**: a `create` with `material:` (a vanilla item is not a resource, so there is nothing
   to link to). A layer that already points into the GUI's own folder is still refused ("already has its own item").
5. **The studio shows the link where the designer is looking.** The inline appearance says "Follows `<base>`"; the
   item studio's template chip names the base, its menu says **Open base item** (not "Open template") when the link
   is an item (`InheritanceView.templates[id].kind === 'item'`), and the chain provenance lines read as for templates.
   The chip's template picker also offers items, under an "Items" heading, for an item only (plugin.md §3.1,
   *Owner answers*), so a designer can re-link a customized item to another base, or link any item to one, without
   editing YAML; it never offers the item itself or a choice that would close a loop.
6. **Owner answers on PR #285.** (a) Items are selectable in the template picker (see 5). (b) A shared
   template/item id is resolved by renaming; `MTR-0094` stays as it is and no spelling reaches the shadowed item.
   (c) Customize on a shadowed base refuses (decision 3) rather than copying.

---

## 12. Containers beyond the chest (#286) — design approved and merged (#287); rows R (#289), M (#290), C (#291), P (#292), S (#294) and Q (#295) built; row A (#293, owner art) open; row B (#296) later

> **Status: the design is approved and merged (#287, all owner questions answered, §12.13, none open, §12.14). Row M is built
> (#290): `container:` replaces `rows:`, the container profiles are one file (`shared/gui/containers.json`, §12.5.1), and every
> GUI in the repository is converted. Row R (the in-game probe, #289) is done (§12.2.6). Row C is built (#291): the compiler
> is profile-driven for every container, hopper and dispenser included (§12.5.7). Row P is built (#292): the runtime opens
> every GUI by the profile's `open` descriptor. Row S is built (#294): the studio draws every profile, with a container select
> in the Screen panel and a generated diagram under a container that has no owner art (§12.7 as built). Row Q is built (#295):
> the QA catalog entries for the hopper and the dispenser (`guicontainers.*`, §12.8 as built), the showcase's two GUIs, and the
> as-built notes of logic.md §1, §1.1 and §11 and this document. Row A (the owner's sheets, #293) and row B (the user's own jar,
> #296) remain.** The chest, the hopper
> and the dispenser work end to end. The consistency edits of this design to §1, §2, §4, §6,
> §8 and §11 are one-line pointers; they change no behaviour.
>
> **The governing rule (owner, §12.13 item 5): the system is agnostic of the container type.** The chest is the default
> a New GUI starts with, nothing more. Every container, the chest included, is a profile (§12.5.1) read by the same code:
> no chest fast path, no `if chest`, no capability that only non-chest containers need.
>
> Traceability: files canonical (D1): the container is one line of YAML and nothing else stores it. One pipeline (D6): the
> container is read by the same `GuiCompiler` the pack and the runtime share. Registries with self-description (D5): the
> container list reaches the studio through `authoring.describe`, not through a studio-side list. Fail-soft: an unknown
> container quarantines that GUI, never the server. Lossless round-trip (master §5.6) holds for `container:` files like any other: load then untouched save is byte-identical.

### 12.1 Goal and non-goals

DeluxeMenus offers 18 `inventory_type`s (logic.md §1). A GUI should be able to be a hopper (five slots), a dispenser
(3 x 3), and later a furnace or an anvil, with the same layers, art, logic and studio as a chest. The point of the first
cut is the **abstraction**: one container description that the compiler, the runtime and the studio all read, instead of
`rows` and `176 x (114 + 18 * rows)` spread through them (§12.6 lists every place). Non-goals: a custom client screen
(the client decides every screen, §12.2), a type the Paper API cannot open from a plugin, and any behaviour that needs a
block (a furnace that really smelts).

### 12.2 Research: what a Paper plugin can open

#### 12.2.1 Evidence and its limits

Each fact below carries a label for how it was verified, in the manner of §9.

| Label | Source | Used for |
|---|---|---|
| **API** | Paper API sources of both pins, from the Gradle cache: `paper-api-1.21.4-R0.1-SNAPSHOT-sources.jar` and `paper-api-26.1.2.build.74-stable-sources.jar` | `MenuType`, `InventoryType`, the view interfaces, `Server.createInventory` |
| **SRV** | The Paper **server** jars the harness and the probe already hold: `paper-1.21.4.jar` (the harness pin) and `paper-26.1.2.jar` (the probe pin), read with `javap -c -p -constants` | `CraftContainer`, the `net.minecraft.world.inventory.*Menu` slot constructors, the packet handlers |
| **CLI** | The 26.1.2 **client** jar of the launcher install (the probe's), read with `javap`; texture sizes are the PNG headers (`IHDR`); nothing was extracted into the repo | Screen sizes, title positions, backgrounds, sprites, menu classes |
| **CMP** | The same menu constructors read in the 1.21.4 server jar (SRV) and the 26.1.2 client jar (CLI) and compared | Slot coordinates on the 1.21.4 floor (the only 1.21.4 evidence there is) |
| **U** | Not verified | Everything the sections below mark so |

To reproduce: extract `net/minecraft/client/gui/screens/inventory/*Screen.class` and
`net/minecraft/world/inventory/*Menu.class` from a client or server jar and run `javap -p -c -constants` on them (the
constructors hold the slot coordinates and the screen sizes; `init()` holds a centred title; `extractBackground` holds the
blits); read a texture's size from its PNG `IHDR`.

No client ran in this research pass, and no 1.21.4 *client* exists on this machine (§9.7). So: **menu and slot facts are
verified on both pins** (CMP, SRV), **screen facts (size, title position, background) are verified on 26.1.2 only** and
expected on 1.21.4, and **nothing here was seen on screen** by this research pass. Row R (#289) then saw the hopper and the
dispenser on the probe: §12.2.6 has what it confirmed, and §12.12 what is still unverified.

CMP result: `HopperMenu`, `DispenserMenu`, `ChestMenu`, `AbstractFurnaceMenu`, `AnvilMenu` (with `ItemCombinerMenu`),
`ShulkerBoxMenu`, `BrewingStandMenu`, `BeaconMenu`, `MerchantMenu` and `CrafterMenu` have the same slot coordinates, the same
standard-inventory origin and the same `MenuType` on both pins; the only differences are fields the Bukkit patches add to the
1.21.4 server classes. `EnchantmentMenu`, `LoomMenu`, `StonecutterMenu`, `CartographyTableMenu`, `GrindstoneMenu`,
`SmithingMenu` and `CraftingMenu` were read on 1.21.4 (SRV) only. The Paper API this design uses is the same on both pins:
`MenuType` lists the same 25 menu types, the view interfaces are identical, and `InventoryType` differs only by `PLAYER`
(41 to 43 slots) and a new `SHELF` (not creatable). `CraftContainer` has the same delegate structure on both pins [SRV].

#### 12.2.2 Two ways to open a menu

| | Legacy: `createInventory(holder, InventoryType, Component)` | `MenuType.X.create(player, Component)` (or its builder) |
|---|---|---|
| API | [API] `Server.createInventory`, Paper's `Component` overload. The Javadoc says some types "do not support titles" and that `WORKBENCH` and `ENCHANTING` do not compute results (use `MenuType`) | [API] `MenuType.Typed.create(HumanEntity, Component)`; `LocationInventoryViewBuilder` takes an optional `location` and `checkReachable` (per its Javadoc, `checkReachable(true)` with no location closes the view at once, so a view with no location is a plain view with the check off) |
| Holder | **Yes**: a custom `InventoryHolder`. `GuiMenu` is the holder and `GuiListener` routes by it | **No**: the holder of the view's top inventory is not ours to set. Routing must identify the window by its `InventoryView` (the `AnvilInput` prompt already does) |
| Server menu | [SRV] `CraftContainer` (both pins). For each `InventoryType` it builds a **real vanilla menu as a delegate** and *adopts the delegate's `slots`, `lastSlots`, `remoteSlots` and data slots*: `ChestMenu`, `DispenserMenu`, `FurnaceMenu` (with a 4-value `SimpleContainerData`), `BlastFurnaceMenu`, `SmokerMenu`, `BrewingStandMenu`, `HopperMenu`, `BeaconMenu`, `ShulkerBoxMenu`, `LecternMenu`, `LoomMenu`, `CartographyTableMenu`, `GrindstoneMenu`, `EnchantmentMenu`, `MerchantMenu`, `CrafterMenu`. `ANVIL`, `WORKBENCH`, `SMITHING` and `STONECUTTER` get plain `Slot`s (`setupAnvil`, `setupWorkbench`, `setupSmithing`, `setupStoneCutter`); for the first two an `AnvilMenu` or `CraftingMenu` delegate is created afterwards, used only for `quickMoveStack`. Chest-like types are chosen by size (9, 18, 27, 36, 41, 45, 54). `stillValid` is always true; `quickMoveStack` is the delegate's | The real vanilla menu (`AnvilMenu`, `FurnaceMenu`, ...), with its logic |
| What the server does for you | Slot placement rules (`Slot.mayPlace`) of the delegate apply to any click that is not cancelled; no result computation, no recipe book, no rename, no button effects: the packet handlers test the *class* of the player's menu, and `CraftContainer` is none of `AnvilMenu`, `RecipeBookMenu`, `BeaconMenu`, `MerchantMenu` [SRV: `handleRenameItem`, `handlePlaceRecipe`, `handleSetBeaconPacket`, `handleSelectTrade`] | Everything vanilla does: an anvil renames and prices, a furnace menu accepts a recipe-book placement (`PlayerRecipeBookClickEvent` is the hook that can stop it) |
| Progress and properties | `InventoryView.setProperty(Property, int)` [API: `BURN_TIME`, `COOK_TIME`, `BREW_TIME`, ...]; reaches the delegate's data slots [U: the `CraftContainer` shares them, [SRV], but no `setProperty` call was traced] | `FurnaceView.setCookTime/setBurnTime`, `BrewingStandView.getBrewingTicks`, `AnvilView.getRenameText/setRepairCost`, `BeaconView.setPrimaryEffect`, ... [API] |
| Title refresh | **Works unchanged**: `PaperRichTitles` (platform) asks `CraftContainer.getNotchInventoryType(inventory)` for the menu type, which is type-aware (`Inventory.getType()` and, for chests, the size) [SRV, platform source] | Needs its own binding: the view's menu is not a `CraftContainer` |
| Fit for GUIs | Chest, hopper, dispenser: exactly right (plain slots) | Types whose behaviour is the point (anvil rename, furnace progress) |

*Recommendation.* v1 uses the **legacy path** for every type it ships, because it keeps the one router (holder identity)
and the one title transport, and v1's types are plain-slot. The `MenuType` path is reserved for the types that need it
(§12.11) and brings a second window identity with it; that is why those types are a later phase.

Both paths open without a block for every type listed below: the legacy path never touches the world, and
`MenuType.create` has no block requirement [API]. `InventoryType.isCreatable()` is false for `CRAFTING`, `CREATIVE`,
`MERCHANT`, `COMPOSTER`, `CHISELED_BOOKSHELF`, `JUKEBOX`, `DECORATED_POT` (and 26.x's `SHELF`) [API]; a merchant is opened
through `MenuType.MERCHANT.builder().merchant(...)` only.

#### 12.2.3 The catalogue

Slot ids are the menu's own (what the client's click packets carry and what `event.getSlot()` reports for the top
inventory); `(x, y)` is the item's top-left in screen pixels, the 18 x 18 well being one pixel up and left. Every menu below
is followed by the 36 player slots (27 main, then the hotbar 58 px below the first row) at the listed origin. All background
textures are 256 x 256 sheets blitted from `(0, 0)` unless noted (`villager.png` is 512 x 256). Title and label positions
are the screen's `titleLabelX/Y` and `inventoryLabelX/Y` (defaults `(8, 6)` and `(8, imageHeight - 94)`).

| Type (`MenuType` / `InventoryType`) | Slots (id: x, y) | Screen | Player inv. origin | Title (x, y) | Background | Notes |
|---|---|---|---|---|---|---|
| **Chest** 1 to 6 rows (`GENERIC_9X1..6` / `CHEST`, `BARREL`, `ENDER_CHEST`) | `0..9r-1`: `8+18·(i mod 9), 18+18·(i div 9)` | 176 x (114+18r) | (8, 31+18r) | (8, 6) | `generic_54.png`, two blits (§11.2) | Barrel and ender chest are the same menu and screen; shipped |
| **Hopper** (`HOPPER`) | `0..4`: `44+18i, 20` | 176 x 133 | (8, 51) | (8, 6) | `hopper.png`, one blit `(0,0,176,133)` | label y = 39; plain slots |
| **Dispenser / dropper** (`GENERIC_3X3` / `DISPENSER`, `DROPPER`) | `0..8`: `62+18·(i mod 3), 17+18·(i div 3)` | 176 x 166 | (8, 84) | **centred**, y 6 | `dispenser.png`, one blit `(0,0,176,166)` | one screen for both; label y = 72; plain slots |
| **Shulker box** (`SHULKER_BOX`) | `0..26`: `8+18·(i mod 9), 18+18·(i div 9)` | 176 x 167 | (8, 84) | (8, 6) | `shulker_box.png` | a 3-row chest with its own texture; `ShulkerBoxSlot` refuses shulker boxes client-side |
| **Furnace / blast furnace / smoker** (`FURNACE`, `BLAST_FURNACE`, `SMOKER`) | `0` ingredient `56,17`; `1` fuel `56,53` (`FurnaceFuelSlot`); `2` result `116,35` (`FurnaceResultSlot`) | 176 x 166 | (8, 84) | **centred**, y 6 | `furnace.png`, `blast_furnace.png`, `smoker.png` | 4 data values (lit time and duration, cook progress and total); client draws a 14 x 14 flame at `(56,36)` and a 24 x 16 arrow at `(79,34)` from them; recipe book (below) |
| **Anvil** (`ANVIL`) | `0 27,47`; `1 76,47`; `2` result `134,47` | 176 x 166 | (8, 84) | (**60**, 6) | `anvil.png` | rename field (110 x 16 sprite at `(59,20)`, an `EditBox` of 103 x 12 at `(62,24)`); cost data slot; error sprite 28 x 21 at `(99,45)`; client predicts the result (below) |
| **Brewing stand** (`BREWING_STAND` / `BREWING`) | `0 56,51`; `1 79,58`; `2 102,51` (potions); `3 79,17` ingredient; `4 17,17` fuel | 176 x 166 | (8, 84) | **centred**, y 6 | `brewing_stand.png` | 3 data values; bubbles 12 x 29, brew progress 9 x 28, fuel bar 18 x 4; `PotionSlot`, `IngredientsSlot`, `FuelSlot` refuse other items |
| **Enchanting table** (`ENCHANTMENT` / `ENCHANTING`) | `0 15,47`; `1 35,47` (lapis only) | 176 x 166 | (8, 84) | (8, 6) | `enchanting_table.png` | 10 data slots (3 costs, seed, 3 clues, 3 level clues); three 108 x 19 buttons; the legacy path does not compute offers [API] |
| **Beacon** (`BEACON`) | `0 136,110` (payment items only) | **230 x 219** | **(36, 137)** | (8, 6) | `beacon.png` | 3 data values; 22 x 22 effect buttons; the set-effect packet needs a real `BeaconMenu` |
| **Loom** (`LOOM`) | `0 13,26` banner; `1 33,26` dye; `2 23,45` pattern; `3 143,57` result | 176 x 166 | (8, 84) | (8, **4**) | `loom.png` | pattern grid and scroller are client widgets; one data value (selected pattern) |
| **Stonecutter** (`STONECUTTER`) | `0 20,33`; `1 143,33` result | 176 x 166 | (8, 84) | (8, **5**) | `stonecutter.png` | recipe grid is a client widget; one data value |
| **Cartography table** (`CARTOGRAPHY_TABLE`) | `0 15,15` map; `1 15,52` paper or glass; `2 145,39` result | 176 x 166 | (8, 84) | (8, **4**) | `cartography_table.png` | map preview drawn from the item's data |
| **Grindstone** (`GRINDSTONE`) | `0 49,19`; `1 49,40`; `2 129,34` result | 176 x 166 | (8, 84) | (8, 6) | `grindstone.png` | error sprite 28 x 21 at `(92,31)` |
| **Smithing table** (`SMITHING`) | `0 8,48` template; `1 26,48` base; `2 44,48` addition; `3 98,48` result | 176 x 166 | (8, 84) | (**44**, **15**) | `smithing.png` | armour-stand preview; cycling slot icons |
| **Crafting table** (`CRAFTING` / `WORKBENCH`) | `0` result `124,35`; `1..9` grid `30+18·((i-1) mod 3), 17+18·((i-1) div 3)` | 176 x 166 | (8, 84) | (**29**, 6) | `crafting_table.png` | recipe book; the legacy path computes no recipes [API] |
| **Crafter** (`CRAFTER_3X3` / `CRAFTER`) | `0..8` grid `26+18·(i mod 3), 17+18·(i div 3)`; result (the last slot, 45) `134,35` | 176 x 166 | (8, 84) | **centred**, y 6 | `crafter.png` | per-slot disabled state and powered flag are data; `CrafterSlot` |
| **Merchant** (`MERCHANT`) | `0 136,37`; `1 162,37`; `2` result `220,37` | **276 x 166** | **(108, 84)** | bespoke (own `extractLabels`) | `villager.png` (512 x 256) | trade list from the offers packet; **not creatable** through `InventoryType` |
| **Lectern** (`LECTERN`) | `0 0,0` (the book) | a book screen | none | none | none (book) | `LecternScreen` extends `BookViewScreen`, not the container screen; the page is a data value |
| Not menus a plugin opens | `PLAYER`, `CREATIVE`, `CRAFTING` (2 x 2), `COMPOSTER`, `CHISELED_BOOKSHELF`, `JUKEBOX`, `DECORATED_POT`, `SHELF` | | | | | not creatable [API] |

Provenance of the table: slots and origins are [SRV]/[CMP] (and [CLI] for the 26.1.2 menus); screen size, title and label
positions, backgrounds and sprite sizes are [CLI]. `i` is the slot id and `r` the chest's row count. The slot restrictions named (lapis, beacon
payment items, fuel, shulker boxes) were read from the slot classes' `mayPlace` [CLI]. The hopper's `inventoryTop` (where the vanilla player-inventory art begins, `baseY - 14`) is derived, not read
from the screen: 37 for the hopper and 70 for the dispenser, from the same rule that gives `18r + 17` for the chest [U:
check against the owner's art].

#### 12.2.4 Behaviour that matters to a GUI

| Behaviour | Types | Verified how | Policy |
|---|---|---|---|
| **The client picks the screen from the menu type.** The server sends only `(containerId, menuType, title)`; layout, widgets and sprites are the client's | all | [SRV] `CraftContainer.getNotchInventoryType`, `PaperRichTitles`; [CLI] the screen classes | A GUI cannot restyle a screen's widgets; it draws *under* them (the title is behind widgets or over them: §12.12 U4) |
| **Centred title.** `init()` sets `titleLabelX = (imageWidth - font.width(title)) / 2`, and `Font.width` is `ceil` of the *sum of glyph advances*, shift characters included | dispenser, furnace family, brewing stand, crafter | [CLI] bytecode of `DispenserScreen`, `AbstractFurnaceScreen`, `BrewingStandScreen`, `CrafterScreen`; `Font.width`, `StringSplitter.stringWidth` | Compiled titles end with a trailing shift that makes the width `imageWidth - 16`, which puts the origin back at 8 (§12.5) |
| **Fixed non-default title positions** | anvil x 60; crafting x 29; smithing (44, 15); loom y 4; stonecutter y 5; cartography y 4 | [CLI] | Per-profile `titleX0`, `titleY0` (§12.5) |
| **Result prediction.** The menu's own `slotsChanged` also runs in the client's copy of the menu. No `isClientSide`, `ServerLevel` or `ServerPlayer` test guards it in the anvil (`ItemCombinerMenu` and `AnvilMenu.createResult`; its only `ServerPlayer` use is the text filter), grindstone, loom, stonecutter and cartography menus. Smithing, enchanting, crafting and the merchant also override `slotsChanged` but test `ServerLevel` or `ServerPlayer`, so whether the client computes a result there is not established | anvil, grindstone, loom, stonecutter, cartography (shown); smithing, enchanting, crafting, merchant (U) | [CLI] `slotsChanged` overrides and the absence of side tests in those classes | A display GUI built on one of these types must expect the **client to compute and show a result the server did not send**, until the server resyncs. Out of v1 for this reason |
| **Slots that refuse items client-side** (`Slot.mayPlace`) | furnace fuel and result, brewing slots, beacon payment, enchanting lapis, shulker boxes, every result slot, crafter slots | [CLI]/[SRV] the slot classes named in the table | An `input: true` slot on such a slot is a diagnostic, not a silent failure (reserved `MTR-0100`, §12.4) |
| **Rename** | anvil | [SRV] `handleRenameItem` runs `AnvilMenu.setItemName` only for an `AnvilMenu`; [API] `AnvilView.getRenameText` | The legacy anvil (plain slots) ignores the packet; the `MenuType` anvil gives the text (the prompt's `AnvilInput` does this today) |
| **Progress arrows and fuel bars** | furnace family (4 data values), brewing (3) | [SRV] `checkContainerDataCount`; [CLI] sprite blits; [API] `FurnaceView`, `BrewingStandView`, `InventoryView.Property` | Not in v1. A `progress` component would be a fourth render target (§12.11) |
| **Buttons** (`ServerboundContainerButtonClickPacket`) | enchanting, loom, stonecutter, lectern, beacon (separate packet) | [SRV] the handlers; `CraftContainer` does not forward `clickMenuButton` to its delegate (it uses the delegate only for slots, data and `quickMoveStack`) | Inert on the legacy path [U: no click was sent]; a trigger per button is a later design |
| **Recipe book** | furnace family, crafting table | [CLI] `AbstractRecipeBookScreen`; [SRV] `handlePlaceRecipe` requires a `RecipeBookMenu` | The screen shifts when the book is open (art moves with it, being relative to `leftPos`); on the legacy path a book click moves nothing; on the `MenuType` path cancel `PlayerRecipeBookClickEvent`. The button itself cannot be removed from the server [U] |
| **Quick move** | all | [SRV] `CraftContainer.quickMoveStack` delegates to the real menu | Never reached: every shift-click into or out of a GUI is cancelled and planned by the input rules (logic.md §25) |
| **Sync after a cancelled click** | all | existing behaviour of `GuiListener` | Unchanged |

#### 12.2.5 Does any container have parameters?

The owner asked (§12.13 item 2): if the chest's row count is the only parameter any container has, the row count can be part
of the container's identity (`chest_4`) instead of a second field; if others have parameters, a parameter API is needed.
The evidence covers **every** menu type, including the later and out ones:

| Menu / `InventoryType` | Structural parameter a file could set? | Evidence |
|---|---|---|
| `GENERIC_9X1` .. `GENERIC_9X6` | **The row count, and vanilla already makes it part of the type**: six distinct `MenuType`s, six registry entries, six `GENERIC_9xN` constants | [API] `MenuType` lists `GENERIC_9X1..6`; [SRV] `ChestMenu` is constructed with one `MenuType.GENERIC_9xN` per row count, and the client's `ContainerScreen` takes its row count from the menu |
| `BARREL`, `ENDER_CHEST`, `CHEST` (legacy) | no: barrel and ender chest are `GENERIC_9X3`; `CHEST` is `GENERIC_9X3` by default and the legacy API's size argument selects one of the six | [API] `InventoryType` maps them to `MenuType.GENERIC_9X3`; [SRV] `CraftContainer.getNotchInventoryType` picks `GENERIC_9xN` from the size |
| `GENERIC_3X3` (dispenser, dropper), `HOPPER`, `SHULKER_BOX` (27), `CRAFTER_3X3` (9 + result) | no: fixed slot count and layout | [SRV] `checkContainerSize(9 / 5 / 27)` and fixed coordinates |
| `FURNACE`, `BLAST_FURNACE`, `SMOKER`, `BREWING_STAND`, `ENCHANTMENT`, `BEACON`, `ANVIL`, `GRINDSTONE`, `LOOM`, `STONECUTTER`, `CARTOGRAPHY_TABLE`, `SMITHING`, `CRAFTING` | no: fixed slots; what varies is **runtime state** (progress, costs, selected pattern, rename text) carried by data slots | [SRV]/[CLI] constructors; `checkContainerDataCount` |
| `MERCHANT` | no: three fixed slots; the offers vary, but they are runtime content sent by the server | [SRV] `MerchantMenu`; [API] `MerchantInventoryViewBuilder` |
| `LECTERN` | no: one slot; the page is a data value | [SRV] `LecternMenu` |

**Conclusion: the chest's row count is the only structural parameter of any vanilla menu, and vanilla itself already
treats it as part of the type.** The runtime state the other menus carry (progress, page, offers, rename text) is not a
property of the *container shape* in a file: it belongs to the components and triggers that drive it (§12.11), which
already have their own parameters. So **types-as-parameters wins** and no container parameter API is designed: a container
is an id, an id names a profile, a profile is data. The test that would reopen this: a container whose *authored*
geometry varies independently of its id (a configurable slot count on a non-chest menu). None exists in vanilla; adding such
a container would be the point to design one.

#### 12.2.6 Verified in game (#289, row R)

Row R put a hopper and a dispenser on the probe client (26.1.2, windowed, real GPU) and measured them in the manner of
§9.8. **U1, U2, U3 and U5 are confirmed, and so is the plain-slot half of U4; the dispenser stays in v1.** The 1.21.4 floor
gets a harness test for the window type and size; the screens themselves are still 26.1.2 evidence (§12.12 U9, U10).

*Rig.* A test-only Paper plugin, [`harness/src/containerProbe`](../../harness/src/containerProbe/java/dev/materione/harness/probe/ContainerProbe.java)
(built by `:harness:containerProbeJar`, never shipped, no part of the `container:` model, the profiles or the opener of
rows M, C and P), opens the window through the legacy path, `Bukkit.createInventory(holder, InventoryType.HOPPER | DISPENSER,
title)`, with a holder of its own, a different item in every slot, and a title read from Adventure JSON. The title is the shape
the compiler emits: one run in a font that holds the shift `space` providers and the art's `bitmap` providers
([`fixtures/gui/containers/gen.mjs`](../../fixtures/gui/containers/gen.mjs), pack `r289`; README next to it). The client side is read
with Groovy through the bridge: `mc.screen.@target` (`imageWidth`, `imageHeight`, `leftPos`, `topPos`, `titleLabelX/Y`,
`inventoryLabelX/Y`), `mc.font.width(title)`, `player.containerMenu`; pixels with `Screenshot.grab` (lossless) and the
model's predictions, as in §9.8; clicks with `AbstractContainerScreen.mouseClicked`. Windows of 1920 x 1080 at GUI scales 1, 2
and 3. The drivers are in [`fixtures/gui/containers/runs/`](../../fixtures/gui/containers/runs/).

*The two windows, as the server and the client see them.*

| | Hopper | Dispenser (and dropper) |
|---|---|---|
| Server window | `InventoryType.HOPPER`, top size 5, menu type `minecraft:hopper`, 41 menu slots, holder kept | `DISPENSER`, top size 9, menu type `minecraft:generic_3x3`, 45 menu slots, holder kept |
| Client screen | `HopperScreen` | `DispenserScreen` |
| `imageWidth` x `imageHeight` | 176 x 133 | 176 x 166 |
| `titleLabelX`, `titleLabelY` | 8, 6 | centred (below), 6 |
| `inventoryLabelX`, `inventoryLabelY` | 8, 39 | 8, 72 |
| `leftPos`, `topPos` at 1080p, scale 1 / 2 / 3 | 872, 473 / 392, 203 / 232, 113 | 872, 457 / 392, 187 / 232, 97 |

The positions are §9.12's formulas (`leftPos = ⌊(⌈W/s⌉ − 176) / 2⌋`, `topPos = ⌊(⌈H/s⌉ − imageHeight) / 2⌋`). The window type
and sizes are what the 1.21.4 harness test asserts on the floor server (`PaperContainerWindowIT`: `HOPPER`, 5,
`minecraft:hopper`; `DISPENSER`, 9, `minecraft:generic_3x3`; the holder survives creation).

**U1 confirmed: the trailing shift puts the dispenser's title origin at 8.** `DispenserScreen.init()` sets
`titleLabelX = (imageWidth − font.width(title)) / 2`, and the width is the sum of the advances, shift characters included,
with no clamp on negative ones:

| Title (dispenser) | `font.width` | `titleLabelX` read from the screen | Art |
|---|---|---|---|
| plain text "Probe dispenser", no font | 84 | **46** = (176 − 84) / 2 | none |
| one 16-px marker at x = 8, **with** the trailing shift (+143) | **160** | **8** | marker at the model's place |
| the same, **without** it | 17 | 79 = ⌊159 / 2⌋ | marker 71 px to the right of its place, exactly `titleLabelX − 8` |
| seven markers (x −300 to 476), **with** the trailing shift (**−341**: the pen ended at 509, past 168) | **160** | **8** | all seven at the model's place |
| the same, **without** it | 501 | **−162** = trunc((176 − 501) / 2) | all seven displaced by exactly −170 GUI px |

So the §12.5.3 rule holds: `W = 160` gives origin 8, the same on every scale, and a trailing shift may be **negative** (the
art ran past 168) without trouble. Java's integer division truncates toward zero, so a wide title without the shift lands at
−162, not −163; that is the failure the shift prevents, and it is visible (`dispenser-no-trailing-shift`). The hopper ignores
the width: `titleLabelX` is 8 with a width of 501 or of 160, and its art is identical either way, so the shift is harmless
on a profile whose title is not centred, and the compiler may add it for every container or only for `centred` ones.

**U2 confirmed: both screens place art as the chest does.** A glyph with ascent `a` at title position `x` has its top-left pixel
at `(leftPos + x, topPos + titleLabelY + 7 − a)`, with `titleLabelY = 6`, i.e. `topPos + 13 − a` (§9.8). Checked with seven
markers per screen (left, right, above with padding, below, on the slots, and two far ones at x = −300 and 476), at three
scales, on both screens: every fill box is exactly the predicted one (82 boxes matched, 0 off the model; far markers outside
the window are absent, as the window cuts them, §9.12). Art past the screen is drawn, never clipped, as on the chest.

**U3 confirmed: the veil starts at row 37 (hopper) and 70 (dispenser).**

- *The sheets.* The bottom 96 rows of `hopper.png` (rows 37..132) and of `dispenser.png` (rows 70..165) are **identical** to the
  chest's player-inventory block, rows 126..221 of `generic_54.png` (the second blit of the chest's `extractBackground`);
  the row above each (36 and 69) is not. So the player-inventory art begins at `imageHeight − 96`, which is `inventory.y − 14`
  (51 − 14, 84 − 14). Read from the sheets of the 26.1.2 client jar (not committed; `u3-sheets.mjs`).
- *The screens.* With no art and an empty window, each screen is **pixel-identical to its sheet** over the whole
  176 x 133 and 176 x 166 area at scales 1, 2 and 3 (21 606, 86 424 and 194 454 hopper samples; 27 414, 109 656 and
  246 726 dispenser samples; 0 mismatches), except the sheet's transparent corner pixels (the world shows), the title text and the
  "Inventory" label. So each screen is one blit of its sheet at `(leftPos, topPos)`, sized as the screen.

**U4, plain-slot screens: items and the label draw above the art; art is seamless across the screen edge.** With a title whose
art covers the whole screen (a 176-px position-coded image over every slot):

- the slot items and their counts are drawn above it: every slot rectangle holds pixels that are not the art (100 to 167 of 256
  per slot; none of the art lost in a slot-free patch, 200 of 200);
- the "Inventory" label (`0x404040`) is drawn above it (104 pixels at its place, row 39 and row 72);
- a lone 32-px marker over slots (no full-screen art) is covered by the items of the slots it overlaps (hopper slots 1 to 3,
  dispenser slots 0 to 5) just as on the chest, so the invariant of §9.13 holds on these screens.

A 300 x 230 position-coded image placed at (−60, −40), cut into two 256-px tiles as the compiler cuts, across all four edges of each screen:
**0 mismatching pixels** of 956 144 per screen (1 912 288 in all: scales 1, 2 and 3; the 704 GUI pixels
under the label excluded), with no seam at the tile border. The hover highlight and the tooltip are not captured (§9.1). What
U4 asked about a screen's own widgets (the anvil's field, the enchanting buttons) needs those screens and stays open (§12.12).

**Clicks: slot ids and `gui.click_outside`.** Real client clicks, with the probe plugin logging the `InventoryClickEvent` (the
facts `GuiListener.route` reads: `getClickedInventory() == null` for "outside", whether it is the top inventory, `getSlot()`):

| Click | Server event |
|---|---|
| Slot `i` of the hopper, `i` = 0..4; of the dispenser, `i` = 0..8 (full-screen art over it) | top inventory, `slot = rawSlot = i`, type `CONTAINER`, top size 5 / 9 |
| Player inventory, first main slot / first hotbar slot | bottom inventory, slot 9 / 0, raw slot 5 and 32 (hopper), 9 and 36 (dispenser): `slotCount + 0..35`, as for a chest |
| Left, right, above, below the screen (on art, §9.14's arrangement), empty ground far away, and the right button on art | **outside**: `getClickedInventory() == null`, `slot = rawSlot = −999`, type `OUTSIDE`, on both screens |
| Inside the screen between slots | no event (the client sends nothing) |
| Slot 1 with the right button | top, `slot = 1`, `click = RIGHT` |

So `gui.click_outside` has the same signal on a hopper and a dispenser as on a chest, art outside the container is inert, art over
slots takes no click from them, and the router's size-agnostic `ClickTarget.of(policy, outside, top, slot)` receives a 5- or 9-slot top
with the slot ids of §12.2.3. *Limit:* the holder here is the probe's, not `PluginGui` (the runtime that opens a Materione GUI as a
hopper is row P), so the layer rule that `gui.click_outside` runs is the chest-tested product code, not exercised on a hopper.

**U5 confirmed: a dynamic title refresh behaves as on a chest, including `init()` re-centring.** Through the packet that
`PaperRichTitles.send` sends (a `ClientboundOpenScreenPacket` with the open menu's id, the type `CraftContainer.getNotchInventoryType`
gives, the new title, then `updateInventory`; the probe reproduces it) on an open window, three refreshes in turn:

| | Hopper | Dispenser |
|---|---|---|
| Menu id before and after each refresh | unchanged | unchanged |
| Items in the client's menu after each refresh | all five intact | all nine intact |
| Server `InventoryOpenEvent` / `InventoryCloseEvent` | none | none |
| Art after a refresh to another compiled title (trailing shift) | at the model's place | at the model's place |
| `titleLabelX` after a refresh to a title **without** the shift (width 123) | 8 (not centred) | **26** = ⌊(176 − 123) / 2⌋: `init()` ran again and re-centred |
| `titleLabelX` after a refresh back to a compiled title | 8 | 8 |
| A click after the refreshes | `slot = 2`, top, holder routed | `slot = 1`, top, holder routed |

The client builds a new screen object on each refresh (its identity changes), which is what re-runs `init()`; nothing is lost
and no event fires. The cursor-held case is the chest's (`HeldTitleRefresh`) and unchanged.

*Evidence* (halved or cropped copies in [`docs/screenshots/gui-containers/`](../screenshots/gui-containers/); drivers `u1.mjs`, `u3.mjs`, `u5.mjs`,
`clicks.mjs`): [`hopper-markers-1080p-s2.png`](../screenshots/gui-containers/hopper-markers-1080p-s2.png) (the seven markers around the hopper),
[`dispenser-trailing-shift-1080p-s2.png`](../screenshots/gui-containers/dispenser-trailing-shift-1080p-s2.png) and
[`dispenser-no-trailing-shift-1080p-s2.png`](../screenshots/gui-containers/dispenser-no-trailing-shift-1080p-s2.png) (the same title, origin 8 and −162),
[`hopper-tiles-1080p-s2.png`](../screenshots/gui-containers/hopper-tiles-1080p-s2.png) and
[`dispenser-tiles-1080p-s2.png`](../screenshots/gui-containers/dispenser-tiles-1080p-s2.png) (the position-coded image across the edges),
[`hopper-draw-order-1080p-s2.png`](../screenshots/gui-containers/hopper-draw-order-1080p-s2.png) and
[`dispenser-draw-order-1080p-s2.png`](../screenshots/gui-containers/dispenser-draw-order-1080p-s2.png) (1:1: items and the label over full-screen art),
[`dispenser-refresh-recentred-1080p-s2.png`](../screenshots/gui-containers/dispenser-refresh-recentred-1080p-s2.png) (after a refresh to a title with no shift: origin 26).

*Not covered.* Nothing here ran on a 1.21.4 client (§12.12 U9, U10). The runtime path (`PluginGui` with an `InventoryType`
opener) does not exist yet. The hover highlight and tooltip order, and the recipe-book and widget screens, are not v1.

### 12.3 Scope: which types v1 supports

*Approved (§12.13): v1 is the chest, the hopper and the dispenser. No "rich" type in v1. The point is to prove that GUIs work across container types, not to build the full array.*

| Type | v1 | Reason |
|---|---|---|
| Chest, 1 to 6 rows: six containers, `chest_1` .. `chest_6` (barrel and ender chest are the same menu) | shipped | nothing about the chest changes; it stops being special |
| **Hopper** | **in** | Plain slots, fixed title position, a single background blit. A 5-slot, one-row container with its own screen size (176 x 133) forces every `rows`-shaped assumption into the open |
| **Dispenser** (also the dropper: one menu, one screen) | **in** | Plain slots in a 3 x 3 grid (3 columns, not 9). Its **centred title** proves the per-profile title origin that every later type needs (§12.5.3). The probe (row R, #289) confirmed the centring arithmetic on screen (§12.2.6, U1), so the dispenser stays in v1 |
| Shulker box | later | Only a texture and a slot restriction on top of a 3-row chest; cheap, but nothing it teaches is missing from v1 |
| **Anvil**, **furnace / blast furnace / smoker** | phase 2 | The "rich" types the issue names. Each needs the `MenuType` path or progress data, a second window identity (§12.2.2) and, for the anvil, a client-predicted result slot (§12.2.4). They are worth doing *after* the abstraction exists and one non-chest type is seen working on a client |
| Brewing stand, enchanting table, beacon, loom, stonecutter, cartography table, grindstone, smithing table, crafter | later, one design each | Buttons, data values, slot restrictions or client-side result prediction: each is a behaviour design, not a geometry entry |
| Merchant | out | `InventoryType` cannot create it, its screen is 276 wide with a bespoke layout and a client-side offer list |
| Lectern | out | A book screen: no container background, no title, one slot at (0, 0) |
| Crafting table | out | A crafting UI, not a menu: the legacy path computes no recipes, the `MenuType` path adds the recipe book |
| `PLAYER`, `CREATIVE`, `CRAFTING` (2 x 2), composter, jukebox, chiseled bookshelf, decorated pot | out | not creatable by a plugin (§12.2.2) |

Why this small: every type adds geometry (data), but the **behaviours** are the cost, and v1's three have none. A small v1
also gives one cheap probe pass (R) that settles the unknowns shared by all types (title centring, the veil boundary,
legacy-path routing, title refresh) before any rich type spends effort on them.

### 12.4 The file model

```yaml
orders:
  title: Orders
  container: hopper        # a container id: chest_1 .. chest_6, hopper, dispenser
  layers: [ ... ]          # the same layers; slots are the container's own, 0..4
guide:
  title: Guide
  container: chest_3       # a chest is a container like any other: its row count is part of its id
```

| Rule | Decision |
|---|---|
| **Name and values** | `container:` holds the id of a built-in container profile, lower-snake-case, valid wherever the schema takes an enum: `chest_1` .. `chest_6`, `hopper`, `dispenser` in v1. `dispenser` is also how a dropper is written (one menu, one screen; the importer maps `DROPPER` to it). Later ids follow the menu names (`shulker_box`, `furnace`, `blast_furnace`, `smoker`, `anvil`, ...). The chest ids are spelled `chest_N` rather than vanilla's `generic_9xN` (§12.13 item 8) |
| **No parameters** | A container has no fields of its own in the file (§12.2.5). The row count is part of the id, so a chest of 4 rows is `chest_4`. There is no parameter API, and there is no `rows:` field |
| **`rows:` is dropped** | The GUI system has not been released, so there is no compatibility to keep (owner, §12.13 item 9). `rows:` is removed from the schema and the parser outright: no alias, no resolver, no two spellings. A file that still says `rows:` has no `container:`, so it fails like any other file missing a required field; `rows` is then just an unknown top-level key, preserved losslessly as unknown keys are (master §5.6) |
| **Round-trip and conversion** | Load then untouched save is byte-identical. Every file in the repository that uses `rows:` is converted to `container: chest_N` **in the same change that lands the parser** (row M, §12.10), so `main` never holds a mixed state. The change is breaking, acceptable because GUIs are unreleased, and the CHANGELOG entry says so |
| **`container:` is required** | A definition without `container:` is a schema error with the existing codes (the same path a missing `rows` takes today). "Chests are the default" is a choice of the New GUI dialog (`chest_3`), not a rule of the parser |
| **Unknown or unsupported id** | The GUI is quarantined with `MTR-0097` (error): the message names the value and lists the supported ids. A file written for a newer plugin therefore costs that one GUI on an older server, never the pack, and the file is untouched (fail-soft, D1). No capability is involved |
| **Slot numbers** | A slot is the container's own slot id (§12.2.3): `0..4` on a hopper, `0..8` on a dispenser, row-major `0..9N-1` on `chest_N`. An `item` layer, a `list` slot set or a rule's `slot:` outside the container is the existing diagnostic (`MTR-0052` for an item or list layer, `MTR-0021` for a rule), with the message saying "container" and the slot count instead of "rows". No new code |
| **Schema self-description (D5)** | `container` replaces `rows` in the gui type's `SelfDescription`: a required `ENUM` whose options are the profiles (group `placement`). Nothing is conditional on the container, so the schema needs no applicability hint. `authoring.describe` gains `guiContainers` (id, label, group, slot count, columns, screen size) next to `guiComponents` |
| **The editor ships apart from the jar** | The studio offers the profiles that `guiContainers` lists, the same for every container. A server too old to list them has no GUI containers at all; the studio says so and offers none. Plugin and editor are released together while GUIs are unreleased, so no fallback is designed |

**Diagnostics reserved for this feature.** The next free code is `MTR-0097`. The block `MTR-0097` to `MTR-0100` is
reserved here, in one place (plugin.md §5 notes the reservation so another change does not take them); `MTR-0100` stays unassigned unless the design genuinely needs it:

| Code | Severity | Meaning | When it lands |
|---|---|---|---|
| `MTR-0097` | error | A GUI's `container:` is not a supported container id (or is not a string). The GUI is quarantined; the rest of the pack compiles | M |
| `MTR-0098` | error | A GUI's container cannot be opened on this server (a platform without the menu API a later type needs). The open is refused with this code, before the previous session changes, like the unavailable-window refusal of logic.md §6.1 step 4. **Unused in v1**: all v1 containers open on every supported Paper | phase 2 |
| `MTR-0099` | warning | An `input: true` slot sits on a container slot the client refuses items for (§12.2.4: a fuel slot, a result slot, a beacon payment slot, ...). The layer is kept; the item would not go in. **Unused in v1** (all v1 slots accept anything) | phase 2 |
| `MTR-0100` | - | reserved, unassigned (it was the legacy-alias disagreement of an earlier draft; with `rows:` dropped nothing needs it) | - |

### 12.5 The compiler

#### 12.5.1 The container profile

Every container is **data**: a profile in one file that both Java and the studio read (the pattern of `smallcaps.txt` and
`advances.txt`: one source, a golden that pins its digest, a test on each side), not constants in either language. The
file is `shared/gui/containers.json` at the repository root (decided by row M, built): the content jar carries it as
`/gui/containers.json` (`GuiContainers`), the studio imports it (`webui/src/lib/gui/containers.ts`), and the golden
`fixtures/golden/editor-demo-snapshot.json` pins what the describe call reports from it. `authoring.describe` mirrors it
only as the list of ids, labels, groups, slot counts, columns and screen sizes (`guiContainers`).

| Field | Meaning | chest_4 (example of `chest_N`) | hopper | dispenser |
|---|---|---|---|---|
| `id`, `label`, `group` | the `container:` value, the studio's name, and the heading the select groups it under | `chest_4`, "Chest, 4 rows", Chests | `hopper` | `dispenser` |
| `slotCount`, `columns` | slots and the grid's width (lists and `skip_columns` use `columns`) | 36, 9 | 5, 5 | 9, 3 |
| `slots` | the item's top-left in screen px, as an origin and an 18-px pitch over `columns` | origin `(8, 18)`, 9 columns | origin `(44, 20)`, 5 columns | origin `(62, 17)`, 3 columns |
| `width`, `height` | the screen | `176`, `186` | `176`, `133` | `176`, `166` |
| `inventory` | the player's 27 slots' origin; the hotbar is `+58` below | `(8, 103)` | `(8, 51)` | `(8, 84)` |
| `inventoryTop` | first row of the player-inventory art (the veil starts here): `inventory.y - 14` | 89 | `37` | `70` |
| `title` | the pen origin `titleX0` (a number, or `centred`) and the label row `titleY0` | `8`, `6` | `8`, `6` | `centred`, `6` |
| `label` | the vanilla "Inventory" label | `(8, 92)` | `(8, 39)` | `(8, 72)` |
| `underlay` | a list of blits `(sheet, source rect, destination)` that compose the vanilla screen | `generic_54`: two blits (§11.2) | `hopper`: one | `dispenser`: one |
| `open` | how the window is created: by size or by `InventoryType` (§12.6) | size 36 | `HOPPER` | `DISPENSER` |

The values are §12.2.3's, [SRV]/[CMP] for slots and origins and [CLI] for sizes and title rules. **The file holds six chest
profiles, `chest_1` .. `chest_6`, as six entries like any others** (a generator may write them; the golden pins the result). A
later container is one more entry; the geometry is never a code change, and nothing in the code knows the word "chest".

#### 12.5.2 What changes, and what does not

**Identical for every container, byte for byte:** the flatten over the art cap (§4 step 1), the cut into 256-px tiles, the
PNG encoder, the glyph allocation and the `<ns>:gui` font, the offset fonts, `ShiftMath`, the dynamic-visual path (§4 step
7), `MTR-0090`/`MTR-0091`, and every existing golden and vector. **A row's exit criterion is that no existing golden
changes.** The converted files (`rows: N` to `container: chest_N`, §12.4) compile exactly as today because `chest_N` reproduces today's constants.

**Generalised** (the compiler's `rows`-shaped places, found by reading the code, §12.6 has the plugin and studio ones):

| Where | Today | With a profile |
|---|---|---|
| `GuiType` (`MAX_ROWS`, the `rows` schema entry and range check) | rows 1 to 6 or quarantine | `container` is read and looked up in the profiles (`MTR-0097` when unknown); `rows` and `MAX_ROWS` disappear |
| `GuiDefinition`, `CompiledGui` (`rows`, `WIDTH`, `height(rows)`) | chest size | carry the profile; `width()`/`height()` from it; `rows()` is removed (the GUI system is unreleased, so no deprecated shim) |
| `GuiCompiler` slot table (`for slot < rows * 9`) | 9-wide | `slotCount` |
| `ItemComponent` (`rows * 9`, "outside the GUI's N row(s)") | chest slot | `slotCount`, message says container |
| `ListSlots` (`COLUMNS = 9`, `rows * 9`), `ListComponent` | chest slot grid | profile `columns` and `slotCount` |
| `SlotsComponent.itemOrigins` and the well painter (`rows * 18 + 31`, `8 + col * 18`) | chest geometry | the profile's `slot(i)` and `inventory`; `region: player` uses `inventory` |
| `ClickMapCompiler` (`centreX(slot % 9)`, `centreY(slot / 9)`, `rows * 9`), `GuiTextRules.slots`, `GuiVisualCompiler` | 9-wide centres | the profile's slot centres |
| `GuiAuthoringApiImpl.render` (the preview flattened at `CompiledGui.height(rows)`), `GuiLayerEdits` (screen edit: rows 1 to 6) | chest | profile height; the `screen` edit takes a `container` id instead of `rows` |
| `GuiComponentConfiguration.rows` (api) | the component's view of the geometry | replaced by the profile (an api change, acceptable while unreleased) |

#### 12.5.3 The title origin, including centred titles

§4's arithmetic is `ascent = 13 - y` and a pen that starts at 8. Both are the chest's `titleLabelY + 7` and
`titleLabelX`. A profile makes them parameters: **`ascent = (titleY0 + 7) - y`** and the pen starts at `titleX0`. The
hopper and every chest profile have `(8, 6)`, so their strings are unchanged; later types differ (anvil 60, loom y 4, smithing
`(44, 15)`).

The centred screens (dispenser, and later the furnaces, the brewing stand and the crafter) set `titleLabelX` in `init()` to
`(imageWidth - font.width(title)) / 2` [CLI], and `font.width` is the `ceil` of the signed sum of the string's glyph
advances, shift characters included [CLI: `Font.width` -> `StringSplitter.stringWidth`]. The compiler knows every advance
(it already tracks the pen), so it can make the width a constant. Let `W` be the title's total advance; the origin is
`(176 - W) / 2` (Java integer division). Choosing `W = 160` gives `(176 - 160) / 2 = 8`: **the origin is back at 8, so every
position in §4 and §11.3 holds unchanged.** Because the pieces of a title already track the pen, the rule is one step:

> After the last piece, shift the pen to `titleX0 + (imageWidth - 16)`, that is `168` on a 176-px screen.

That is one trailing `<shift:168 - pen>` (nothing when the pen is already 168), added by `GuiTitle` (so by the static compile
and by the dynamic `TitleRenderer`, which assemble through it) at the end of every assembled title that has art or text runs,
**on a profile whose title is `centred`**. A GUI with neither keeps its plain `title:` as the title, and the client centres it:
the vanilla look. The check is exact integer arithmetic, so the golden can pin it.
It rests on `Font.width` summing negative advances without a clamp, which the bytecode shows ([CLI]: the lambda adds each
`getAdvance` to a `MutableFloat`) and the probe confirmed on a real `DispenserScreen` (§12.2.6, U1: `font.width = 160`,
`titleLabelX = 8`; without the shift a 501-px title lands at −162). The shift is harmless on a profile whose title is not centred
(the hopper's art is identical with and without it), so it could be added uniformly; **as built (row C) it is added for `centred`
profiles only**, because a uniform shift would append a `<shift:..>` to every existing chest title and move every existing golden,
which the row forbids. The condition is a profile property (`title.centred`), not a container name. Had the probe refuted it, the dispenser would have left
v1 (§12.3); nothing else depends on it.

#### 12.5.4 The art cap and the regions

- **Cap.** The cap is the box of §11.2 (`x -424..599`, `y -243..780`). Its top row is a client fact,
  `titleY0 + 7 - 256`: `-243` for `titleY0 = 6`, which is every v1 container, so **v1 shares one cap**. A later container with
  another `titleY0` has its own top (smithing `-234`; loom `-245`); the cap is stated per profile and `GuiArt.CAP_*` become
  functions of it. Its width is centred on the screen: 176-px screens share `-424..599`; a 230- or 276-px screen shifts it.
- **Regions** (§11.2). Container = the profile's slot area, **player** = `y >= inventoryTop` within the screen, **outside** =
  the rest of the cap. The veil starts at `inventoryTop`. `item` and `list` layers stay container-only.
- **Window guides** (§11.5) are the same formulas with the profile's `height`; a hopper (133) leaves more room above and
  below than any chest.

#### 12.5.5 Click areas and slot ids

`ClickMap` maps each of the container's slots (`0..slotCount-1`) to the layers covering it, by the rule of logic.md §6.2:
a rectangle covers a slot when the slot's 16 x 16 centre `(x+8, y+8)` falls inside it. Only `slot(i)` changes. The player
slots keep being the viewer's: the client sends them with `slotNum >= slotCount`. A click outside is still slot -999 with no
position (§9.14), for every container.

#### 12.5.6 Tests

Per profile: a compiled golden (the pack-output summary), shared raster vectors for the underlay composition, a
click-map table, and a **title vector** with a trailing shift. A ban test forbids `rows * 9`, `% 9` and `/ 9`, the
numbers `176`, `114`, `18 * rows` and `rows * 18`, and the identifiers `rows` and `"chest"` anywhere outside the profile data. Nothing in
the parser reads `rows`. The existing
`chest-*` vectors and every shipped golden stay as they are. What row C built is in §12.5.7.

#### 12.5.7 As built (row C, #291)

Row M had already made the slot table, the click map, `ItemComponent`, `ListSlots` and `SlotsComponent` read the profile, and the
preview height and the `screen` edit take the profile too (§12.5.2). Row C finished the compiler, which was still chest-shaped
in the title and the art cap:

| Where | What it is now |
|---|---|
| `GuiTitle` | `new GuiTitle(namespace, container)`. The pen starts at `originX(container)` (the profile's `title.x`, or 8 for a `centred` title), every art piece returns the pen there, and `build()` appends the trailing shift to `trailingX(container)` = `origin + width - 16` (168) when the title is `centred` and has a piece. `ascentFor(container, screenTop)` is `titleY + 7 - screenTop` |
| `GuiArt` | The cap is `GuiArt.Cap.of(container)`: `x = (width - 1024) / 2`, `y = titleY + 7 - 256`, so `-424` and `-243` for every v1 profile (one shared box, §12.5.4); `capCanvas`, `cut` and the `MTR-0091` check take it, and `MTR-0090`'s message prints the profile's box. The constants `CAP_X`/`CAP_Y` are gone |
| `GuiFonts`, `GuiTextRules`, `GuiCompiler`, `GuiVisualCompiler`, `TitleRenderer` | The offset fonts' ascent, the "default-font text must start on row N" rule (N is derived: `5` for title row 6) and the dynamic title all take the container. The plugin's `TitleRenderer` changes only by passing the container it already had |
| Offset-font keys | A key is `<ns>:gui/<family>_y<row>`, with no title row in it. Two GUIs of one namespace with different title rows would disagree on a key's ascent, so the day a profile has a title row other than 6 the key must carry it first; `GuiContainerCompilerTest` fails if one is added without it. All v1 profiles have title row 6 |
| The underlay | **No Java code composes it.** The blit list (`underlay`) is profile data read by the studio (row S); the compiler never draws the vanilla screen, so there is no `generic_54` geometry in it to remove. The shared raster vectors pin the geometry the studio's renderer and the compiler share (slots and screen size) for hopper and dispenser |
| `ItemComponent` | The editor's slot range is `0..(largest profile slot count - 1)`, derived from the profiles; the GUI's own profile bounds it further |

*Tests.* `GuiContainerCompilerTest` pins a hopper and a dispenser compile (`fixtures/golden/gui/hopper-compiled.txt`,
`dispenser-compiled.txt`: title, fonts, assets and a click table), replays the dispenser title's pen to 168, and checks the cap of
every profile; `GuiTitleOriginTest` pins the trailing shift (positive, negative, absent, idempotent `build()`); `TitleRendererTest`
pins the dynamic title; `GuiAuthoringApiTest` the preview's height per container. Four new shared vectors (`hopper-screen`,
`dispenser-screen`, `slots-container-hopper`, `slots-container-dispenser`) are rendered by the Java compiler's flatten and by the
studio's raster (which reads the profile through `cellsOf`; the studio's own scene passed rows until row S, which removed that
path: every scene now carries the profile). **The ban** is `GuiContainerAgnosticTest`: in the GUI sources of `content`, `api` and `plugin`, outside
comments and string contents, no identifier `rows`, no `% 9` or `/ 9`, no `176` or `114`, and no `"chest` literal. Its allow-list
is the `rows` that are not container rows (glyph sheets, the art tile grid, the entries of a list layer) and `GuiType`'s
`NEW_GUI_CONTAINER`, the one default a New GUI starts with. No existing compiled-output golden or vector changed.

### 12.6 Runtime

> **As built (#292, row P).** `PluginGui` takes the profile's `GuiContainer.Open` (one constructor): a size goes to
> `createInventory(holder, size, title)`, a type to `createInventory(holder, InventoryType, title)`, both on the legacy
> path. `GuiMenu` passes `container.open()`; nothing branches on the container id. An `open` the server cannot create
> (an unknown or non-creatable `InventoryType`, or a size that is not a multiple of 9 from 9 to 54) is refused by
> `BukkitWindowHost.unavailable` with `MTR-0098` before the viewer's window changes, which is where the chest's sizes
> were already checked. `GuiRuntime.viewOf` fills `RuntimeApi.GuiView`'s new `profile` component. The refresh compares
> profile ids (`RuntimeGuis.Live.sameAs`, `GuiRefreshPlan.viewerAction`), which the code already did: a
> `chest_3` to `hopper` change reopens, a same-profile change rewrites in place. `GuiRuleDiagnoser` already names
> "container slots 0 to N-1". The router (`GuiListener.route`, `dragRoute`, `ClickTarget.of`) was already size-agnostic
> and is now tested with a 5-slot and a 9-slot top (`ContainerSizeRoutingTest`). The 1.21.4 check is the opt-in
> harness scenario `PaperContainerGuiFloorIT` (`-Pharness.containerGuiFloor=true`, after `npm ci` in `harness/protocol`):
> a bot joins, `materione gui open` shows `sample:hopper_shop` and `sample:dispenser_shop` through the product,
> and the probe's `ctprobe top` reports `HOPPER`/5 and `DISPENSER`/9, the `minecraft:hopper` and `minecraft:generic_3x3`
> menus, a `PluginGui` holder, and the bot's open-window packet carrying the server's menu id. It runs opt-in because the
> default integration run has no `npm ci` for the protocol client (the rich-title floor is opt-in for the same reason).

**Opening.** `GuiOpener` is unchanged (logic.md §6.1). `BukkitWindowHost` builds the window from the compiled GUI's
profile's `open` descriptor: by size, `Bukkit.createInventory(holder, size, title)` (the six chests), or by type,
`Bukkit.createInventory(holder, InventoryType.HOPPER | DISPENSER, title)` [API]. The descriptor is data, so `PluginGui`
takes it (one constructor for both forms) and `GuiMenu` takes the profile instead of `rows`; no code asks whether the
container is a chest. The holder is still the identity: `GuiListener`
routes by `getTopInventory().getHolder() instanceof PluginGui`, which this keeps.

**The router.** `GuiListener.route` and `dragRoute` already take the top inventory's *size* (`event.getView()
.getTopInventory().getSize()`), and `ClickTarget.of(policy, outside, top, slot)` is size-agnostic, so the click table is
unchanged. The viewer-side raw slots are `slotCount + 0..35`, as for a chest. Input slots (logic.md §25),
`gui.slot_change`, `player_inventory: locked` and `gui.click_inventory` need nothing new. The plain-slot delegates
(`HopperMenu`, `DispenserMenu`) add no `mayPlace` rule [SRV: both build plain `Slot`s], so an input slot behaves as in a
chest. No block exists, so a hopper window pulls nothing and a dispenser dispenses nothing.

**Refresh and live reload.** `GuiRefreshPlan`/`RuntimeGuis.Live` compare `rows` and title to decide "rewrite in place" or
"open a new window"; they compare the **profile id** and title instead (a change from `chest_3` to `chest_4` and one from
`chest_3` to `hopper` are the same event). A container change
re-opens, with the cursor lifted and restored as today. The dynamic-title transport needs no change: `PaperRichTitles`
asks `CraftContainer.getNotchInventoryType(expected)` for the menu type, which is type-aware on both pins, so a hopper's
title refresh sends `MenuType.HOPPER` [SRV; platform source].

**API.** `RuntimeApi.GuiView` carries the container id (row M) and, as built in #292, the profile itself as a fifth component
(`profile`, the `GuiContainer` the window opens as; the canonical constructor changed, an api change acceptable while
unreleased); `/materione gui open` and the editor's preview are container-agnostic. `GuiRuleDiagnoser`'s "outside this GUI's N row(s)" becomes "container slots 0 to N-1".

**`player_inventory`.** The policy is a property of the viewer's inventory and is container-independent. The only per-type
fact is where that inventory is drawn (the profile's `inventory`); a merchant would draw it at x 108, which is why the
profile carries an origin and not a constant.

**Special behaviours.** None in v1, which is the point. For the later types (§12.11): the anvil's result slot and rename
text, the furnaces' recipe book, the beacon and enchanting buttons and the merchant's offers are either *supported*
through a `MenuType` view and a named trigger, or *neutralised* (cancel the click, cancel `PlayerRecipeBookClickEvent`,
set the repair cost to 0), never left to vanilla.

### 12.7 The studio

**Canvas and underlay.** *(As built in #294: see the table after "New GUI" below.)* `chest.ts` (28 sites), `open-canvas.ts` (20), `model.ts`, `vanilla.ts`, `studio.svelte.ts`,
`scene.ts`, `snap.ts`, `GuiCanvas.svelte`, the inspector and the layers panel read `rows` and `canvasHeight(rows)`. They
read the **profile** instead: slot cells and their numbers (the slot grid overlay shows the container's own ids), snap
targets, `screenRect`, `inventoryTop`, `regionAt`, the window guides, the decoration-only rule (`coversContainerSlot`) and
the "Inventory" ghost label. The art cap box is drawn from the profile too (§12.5.4).

**The underlay without Mojang art.** The chest's underlay is the owner's own redraw (`webui/static/vanilla/README.md`).
The same cannot be committed for other screens from Mojang's files. Options:

| Option | What the studio draws | For | Against |
|---|---|---|---|
| **A. Owner-drawn sheets** | A sheet per container in `webui/static/vanilla/`, drawn by the owner as `generic_54.png` was, composed by the profile's blits | Legal by construction; deterministic; the same pixel tests as the chest | Manual art per type (2 sheets for v1: 176 x 133 and 176 x 166, and 17 more for the rest); fidelity depends on the redraw (the chest's corners took #278) |
| **B. The user's own client, read in the browser** | The studio asks once for the user's Minecraft jar (a file picker; the browser unzips `assets/minecraft/textures/gui/container/<name>.png`, which the 26.1.2 jar contains [CLI]) and caches it in IndexedDB. Nothing is uploaded, stored by the relay or committed | Pixel-exact for every present and future type, any version; no art to draw | The user must own and locate a jar; needs the owner's comfort that using a user's own copy locally is acceptable; not available to CI (tests keep synthetic fixtures); the dynamic sprites (flames, arrows) are not part of the sheet |
| **C. Generated diagram** | A neutral drawing from the profile alone: the screen outline, the slot wells at the exact positions, the player-inventory block, a label "vanilla art not shown" | Zero assets; works for every type on day one; correct for geometry and snapping, which is most of what the underlay is for | Not the vanilla look: art cannot be judged against the frame's real features |
| **D. Nothing** | Open space and the outline | Trivial | Authors cannot see the slots |

*Decided (§12.13 item 3):* **A for the hopper and dispenser, with C as the fallback while a sheet is missing**, so the studio row
never waits for art and any future type has a working canvas the day its profile is added. **B as a follow-up row when the
set grows past a handful of types**, because it removes the drawing cost that is linear in the type count; it needs the
owner's decision, which is made (§12.13 item 3). Mojang assets never enter the repository under any option.

**Switching the type of an existing GUI.** The container is a document field, edited in the Screen panel as a
select grouped by the profiles' `group` (the options come from `authoring.describe`, §12.4; `chest_1` .. `chest_6` are six
entries of it); the switch is **one undo step** and loses nothing:

| What the document holds | On a switch | How the author sees it |
|---|---|---|
| `container:` | rewritten to the new id; undo restores the exact original line. A switch from `chest_3` to `chest_5` is the same edit as one to a hopper | the Screen panel shows the id |
| `item` layers in slots the new container lacks | kept, unchanged | the existing server verdict (`gui.validate`, `MTR-0052`) flags each: the orange `!` on the canvas, the warning in the layers panel, the reason in the inspector; the compiler skips only those layers |
| `list` slot sets, rules with `slot:` | kept | `MTR-0052` and `MTR-0021` respectively, as above |
| `slots` layers | kept; they are drawn from the new container's wells | none needed |
| Art, panels, text, buttons | kept at the same pixels | the underlay changes under them; a button that now covers no slot gets the existing "decoration only" badge |
| Rules | kept | none |

Before the switch is applied, the Screen panel states the cost in one line from the server's last verdict computed for the
target container ("3 item layers use slots a hopper does not have; they are kept and skipped until you move them"),
and the status bar repeats it after. No layer is moved, resized or deleted by the switch: that would be a silent edit. A
**Move out-of-range slots** helper (a deliberate, undoable action that remaps by index) is a possible later convenience, not
part of this design.

**New GUI.** The starter document is `title` and `container:`; the New GUI dialog offers every profile and starts on
`chest_3`. The scaffold text is mirrored in `core` and `web` and pinned by a golden, so the change is made there once
(row M).

**As built (#294, row S).** The studio draws every profile with the same code; the ban below keeps it that way.

| Part | As built |
|---|---|
| The reader | `chest.ts` is gone: its geometry (`canvasSize`, `cellsOf`, `slotCell`, `nearestContainerCell`, `wellRect`, `itemRect`) lives in `webui/src/lib/gui/containers.ts` next to the profile file's reader, every function taking a `ContainerProfile`. `studioDraws` is removed. `GuiDocument` has no `rows`; `Scene`, `RasterScene` and the snap sources carry the profile; `open-canvas.ts` (`screenRect`, `regionAt`, `windowGuide`, `clickReach`, `coversContainerSlot`, `decorationNote`) reads `width`, `height` and `inventoryTop` from it. A GUI whose `container:` the studio does not know is read-only and draws the default profile |
| The canvas | The screen size, the slot cells and their numbers (the slot-grid overlay writes each container slot's own id in its well), the snap targets, the veil boundary (`inventoryTop`), the window guides and the ghost "Inventory" label (`inventoryLabel`) are the profile's. The art cap is the shared 1024-px box of §12.5.4 |
| The underlay | `underlay.ts` composes the screen from the profile's blit list. When every sheet a blit names exists (`webui/static/vanilla/<sheet>.png`, found by file name, so adding a container's sheet is dropping the PNG in), the owner's art is blitted (option A); otherwise a generated diagram is drawn (option C: a flat cool-grey frame, a well at every container and player slot, a note "Generated diagram, vanilla art not shown" above the screen). The chests have `generic_54.png`; the hopper and the dispenser are the diagram until their sheets land (row A). The player's part is veiled from `inventoryTop` in both. The eyedropper reads the owner's art only: a diagram is not vanilla colour |
| The select | The Screen panel's **Container** select offers the profiles `authoring.describe` lists in `guiContainers` (`guiContainersOf`, grouped by the profile's `group`; an id the studio has no profile for is not offered). A server that lists none gets a disabled select and a note |
| The switch | Choosing a container **stages** it: the panel states the cost in one line and offers **Switch to X** and **Cancel**. The cost is the server's own verdict: the studio sends `gui.validate` the document with the target container and words the layers it newly flags (`switch-cost.ts`: "On Hopper, 2 item layers use slots it does not have; they are kept and skipped until you move them."); with no `gui` capability the line says the cost cannot be shown and that no layer changes. Applying is one `setDocumentFields` command (one undo step, label "Switch to X") that rewrites only `container:`; the status bar repeats the line. The earlier row control also resized a frame panel that spanned the canvas: that was a layer edit and is gone, per §12.9 item 8 (the author resizes it). The line counts layers; a rule's `slot:` (`MTR-0021`) is the Logic tab's verdict, not this line's |
| New GUI | The scaffold (`container: chest_3`, row M) opens drawn and selectable like any container. The New GUI dialog still asks only for a name; the author picks another container in the Screen panel afterwards (the design's "dialog offers every profile" was not built: it would be a second route to the same one-line edit) |
| The ban | `webui/test/gui-container-studio.spec.ts` scans `webui/src/lib/gui` and `webui/src/lib/studio/gui`, outside `containers.ts` and outside comments and string contents: no identifier `rows` (allow-list: the glyph sheets and the layers panel's own list), no `% 9` or `/ 9`, no `176` or `114`, and the word `chest` nowhere in code, strings or markup. The help text of the `panel` and `slots` components (compiler-side, §3) was made container-neutral, which moved the editor demo golden |
| Tests | Unit: the profile reader for every container, the underlay and the diagram, the switch cost words, item snapping on a hopper and a dispenser, a hopper file's lossless round-trip. Playwright (`gui-containers.pw.ts`): the select and its groups, hopper and dispenser canvases (size, slot ids, veil row, label, diagram note), the cost line before and after, a switch and its single undo (document and writes untouched), the saved file differing by the one line, the older-server and no-verdict cases, the starter. QA catalog: `guicontainerstudio.*`. Screenshots of the studio on a hopper, a dispenser and the staged switch: `docs/screenshots/gui-containers/studio-*.png` |

### 12.8 Logic, importer and QA

- **logic.md §1 / §11.** The "Size and container type" row becomes: `container: <id>` (`chest_1`..`chest_6`, `hopper`, `dispenser` in v1,
  gui.md §12); DeluxeMenus' `inventory_type` maps `CHEST`, `HOPPER`, `DISPENSER`, `DROPPER` now and the rest as the later
  phases add profiles. The deferred row "Non-chest containers: click-kind mapping per container" is answered for v1: **no
  mapping is needed**, because the click kinds are Bukkit's and the v1 menus are plain slots; a container's own behaviours
  (rename, buttons, trades, progress) become **triggers and components of that container** when it is designed, never a change
  to the existing ones. The coverage table (§1.1) row "Size and container type" stays "Covered (chest)" until the
  types ship, and then lists the showcase's hopper and dispenser GUIs.
- **Components.** `slots` wells, `item`, `list` (`columns`), `text` click footprints and `visible_if` need nothing but the
  profile. No new component and no new render target in v1.
- **Importer (#17).** DeluxeMenus `size` and `inventory_type` together map to a `container:` id (a `CHEST` of size 36 is `chest_4`); an unsupported
  type is a skipped GUI with a message, not a chest (owner, §12.13 item 6).
- **QA catalog (`qa/catalog/`).** Auto: the window opens as the right `InventoryType` with the right size; a click on each
  slot id runs the layer's rule; shift-click and drag stay cancelled; input slots and `player_inventory: locked` behave as in
  a chest; the dynamic title refreshes on a hopper; and **the open dispenser screen's `titleLabelX` is 8 for a compiled title
  and `(176 - width) / 2` for a plain one**, read with `ctx.exec` from the client (the check that closes U1 and then guards the
  trailing shift). Studio auto: switching `chest_6` to `hopper` keeps every layer, flags the out-of-range ones, and undo restores the
  original line; a file edited without touching its container is byte-identical. Visual: art over a hopper and a dispenser (tiles across the screen edge, the veil boundary, the "Inventory" label
  over the art, items over art), and the studio canvas for each container. Showcase: a hopper GUI and a dispenser GUI join
  `fixtures/packs/showcase`.

**As built (#295, row Q).** The QA entries are `qa/catalog/guicontainers.mjs`, nine in all. A hopper opens as a `HopperMenu` of 41 slots
(five container slots and the player inventory) and a dispenser as a `DispenserMenu` of 45; a click on each slot id runs its rule once
and the server names the id; a click outside (slot -999) runs `gui.click_outside`; the hopper's title text follows the server stock with
no reopen; the dispenser's `titleLabelX` is 8; and two `visual` entries capture the art for the verdict. They drive `showcase:hopper` and
`showcase:dispenser` (`fixtures/packs/showcase/guis/`), which are opened by name with `materione gui open` and have no open command. The
showcase now holds seven GUIs, and `ShowcasePackTest` pins them. No golden moved: the showcase is in no golden (the editor demo reads
`sample` and `world`), and the pack count is unchanged. The entries are not blessed; blessing runs on main (qa/README.md). Importer (#17):
no importer doc exists yet (docs/architecture/README.md lists the importer specs as still to be written), so the mapping above is recorded
in logic.md §11 ("Importing incumbent menus") and posted on the issue.

### 12.9 Decisions this design takes

Each is reversible by the owner (the owner's own are in §12.13).

1. **A container is data (a profile), not code.** One file read by Java and the studio, pinned by a golden; adding a type is a
   data change plus its probe. The chest is six entries of it.
2. **Types-as-parameters.** The chest's row count is the only structural parameter of any vanilla menu and vanilla already
   makes it part of the type (§12.2.5); a container is an id and has no parameters. No parameter API.
3. **`container:` names the profile and is required; `rows:` is dropped, not aliased.** One spelling, no resolver; the repository's
   files are converted in the change that lands the parser.
4. **No chest special-casing anywhere.** No capability, no `if chest`, no conditional field, no chest adapter, no alias.
5. **v1 opens through `InventoryType` or by size (the legacy path), keeping the holder as the window's identity,** chosen by
   the profile's `open` descriptor. The `MenuType` path arrives with the first type that needs it, with a view-identity router
   beside the holder one.
6. **Centred titles are neutralised by a trailing shift, so the pen origin stays 8.** Verified on a real dispenser screen (§12.2.6, U1).
7. **One art cap for v1**, stated per profile so later containers can differ.
8. **Switching container never edits a layer.** It reports; the author decides.
9. **`MTR-0097` to `MTR-0100` are reserved for this feature**; three are defined and `MTR-0100` stays unassigned (§12.4).

### 12.10 Implementation rows

Proposed after approval, as #274 did (R research, C compiler, S studio, Q close-out; M and P are new here because the
runtime and the model are first-class). Models follow the delegation policy: senior work and web UI on Sonnet at high
effort, regular implementation on Haiku.

| Row | Title | Scope | Depends on | Model |
|---|---|---|---|---|
| **R** | Verify containers in game | On the 26.1.2 probe: open a hopper and a dispenser through the legacy path with a holder; read `titleLabelX/Y` and the screen size from the client; check the dispenser's centring with and without the trailing shift (U1); art placement, tiles across the screen edge, the veil boundary rows 37 and 70, draw order and the "Inventory" label (U2, U3, U4); `gui.click_outside`; a dynamic title refresh on a hopper (U5); clicks by slot id. Output: a §9-style subsection with screenshots, and the 1.21.4 CI harness test for window type and size | none | Sonnet, high. **Done (#289, §12.2.6)** |
| **M** | Model, profiles and the conversion | `container:` replaces `rows:` in the schema and `GuiType.parse` (required; `MTR-0097` for an unknown id); the profile data file (six chest entries, hopper, dispenser; `open`, `underlay`) with Java and TS readers and its golden; `guiContainers` in `describe`; `GuiComponentConfiguration` and `CompiledGui` carry the profile; the scaffold change in `core` and `web`. **The conversion, in this row:** every `rows:` of a GUI becomes `container: chest_N` in the same change: `fixtures/gui/*.yml` and `fixtures/gui/open-canvas`, `fixtures/packs/*/guis` (showcase, guilogic, guitext, opencanvas, overlay), the built-in `plugin/src/main/resources/builtin-packs/materione/guis/browser.yml`, `harness/protocol/floor.yml`, YAML embedded in Java and TS tests and runners (`content`, `core`, `pack`, `plugin`, `harness`, `web`, `webui` demo data and specs), the vector generator, the goldens they regenerate (`editor-demo-snapshot.json` and the pack-output goldens), and the docs' examples (gui.md §2, logic.md). Glyph sheets and the satchel item have their own `rows` that are not GUI rows and stay. The CHANGELOG entry states the breaking change and why it is acceptable (GUIs are unreleased). A grep-based test fails the build on any GUI file with `rows:` | none | Haiku for the sweep, Sonnet reviews the parser |
| **C** | Compiler | The profile-driven slot table, click map, `ItemComponent`/`ListSlots`/`SlotsComponent`, `GuiTitle`/`TitleRenderer` origin and the trailing shift, the per-profile cap, the render/preview height, the underlay as a blit list, goldens and vectors for hopper and dispenser, the ban test (no `rows`, no `"chest"` outside the profile data); **no compiled-output golden changes** (the conversion in M moves only the goldens that echo a file's `rows:`) | M; R for the dispenser | Sonnet, high. **Done (#291, §12.5.7)** |
| **P** | Plugin runtime | `PluginGui`/`GuiMenu`/`BukkitWindowHost` open from the profile's `open` descriptor, `GuiRefreshPlan` and `RuntimeGuis` compare profile ids, `RuntimeApi.GuiView`, diagnoser messages, router unit tests with a 5-slot and a 9-slot top, a harness IT on the 1.21.4 floor. **Built (#292, §12.6 as built): `MTR-0098` is its one new code; the IT is opt-in (`harness.containerGuiFloor`)** | M | Haiku |
| **A** | Underlay art | The owner's `hopper` and `dispenser` sheets and README entries (option A), or the generated diagram (option C), which the studio row shipped first (#294) | owner (§12.13 item 3) | owner / Haiku for C. **Open for the owner**: the `hopper` and `dispenser` sheets; the studio uses them as soon as `webui/static/vanilla/<sheet>.png` exists |
| **S** | Studio | The container select (all profiles, grouped, from `guiContainers`), the profile-driven canvas (`chest.ts` becomes the profile reader; `model.ts` drops `rows` and its clamping), slot grid, snap, regions, guides, ghost label and cap, the underlay from the blit list (A or C), the switch UX and its one-line cost, the starter dialog (`container: chest_3`), `webui` specs; **no `if chest` in any of it** | M; C for the verdict text | Sonnet, high. **Built (#294, §12.7 as built)**: option C ships now; the select stages a switch and states its cost; the New GUI dialog stays a name field |
| **Q** | Close-out | QA catalog entries (§12.8), showcase hopper and dispenser, logic.md §1/§1.1/§11 and gui.md as-built notes (the shipped sections stop saying `rows:`), plugin.md §5 rows, importer note on #17 (chest size to `chest_N`; unsupported types skipped) | R, C, P, S | Haiku. **Done (#295, §12.8 as built)** |
| **B** (later) | Underlay from the user's jar | Option B of §12.7, approved as the follow-up once the type count grows | after v1 | Sonnet, high |

R, M and A can start together. R (#289) did not refute U1, so C ships the hopper and the dispenser (§12.2.6); had it refuted U1, C would have shipped the hopper only and the dispenser been re-filed as phase 2.

### 12.11 Later phases (not designed here)

So the v1 seams do not paint the rich types into a corner:

- **A `MenuType` window.** A second way to open a window (`MenuType.X.create(player, title)`, §12.2.2) and a router that
  identifies it by `InventoryView` beside the holder. `AnvilInput` already opens an anvil this way.
- **Anvil.** A `gui.rename` trigger carrying the text (`AnvilView.getRenameText`), the result slot neutralised (the client
  predicts one, §12.2.4: cancel `PrepareAnvilEvent`, repair cost 0), the rename field left to the client (it cannot be
  removed from the server; art draws over or under it, U4).
- **Furnace family.** A `progress` component writing the data values (`FurnaceView.setCookTime/setBurnTime`), which is a
  fourth render target next to raster, text and slot; the SPI (`RenderTarget`) gains a value, an API change that gets its own
  design. The recipe book cannot be hidden from the server (U6).
- **The others** each get a design when asked for, with a probe row first.

### 12.12 What was unverified, and what still is

Row R (#289) answered U1, U2, U3 and U5, and the plain-slot half of U4, on the 26.1.2 probe (§12.2.6). They are no longer open:

| # | Claim | Answer | Measured |
|---|---|---|---|
| **U1** | A compiled title with a trailing shift makes `Font.width = 160` and puts a dispenser's pen origin at 8 | **Confirmed** | `font.width = 160`, `titleLabelX = 8`; without the shift a 501-px title gives −162, a 17-px one 79, a plain 84-px one 46 (§12.2.6) |
| **U2** | The hopper and dispenser screens place art exactly as the chest does (`leftPos + x`, `topPos + 13 - ascent`) | **Confirmed** | 82 marker boxes at three scales, 0 off the model; `titleLabelY = 6` on both |
| **U3** | The veil boundaries `37` (hopper) and `70` (dispenser) match the vanilla art | **Confirmed** | the bottom 96 rows of each sheet equal the chest's player block, the row above does not; each live screen equals its sheet |
| **U4** (plain-slot half) | Title art draws over or under the slot items and the "Inventory" label | **Under the items, the counts and the label** | 100 to 167 of 256 pixels per slot are items; the label is above the art; a lone marker is covered by the items under it |
| **U5** | A dynamic title refresh on a hopper or dispenser behaves as on a chest (including `init()` re-centring) | **Confirmed** | same menu id, items intact, no open or close event; the dispenser re-centred to 26 on a title with no shift |

What is still unverified:

| # | Claim | Why unverified | What verifies it |
|---|---|---|---|
| **U4** (widgets half) | Title art draws over or under a screen's own widgets (the anvil's text field, enchanting buttons) | Only plain-slot screens are in v1 | the phase-2 probe |
| **U6** | The recipe-book button on a furnace screen cannot be avoided and what it does to art | Not run | the phase-2 probe |
| **U7** | `InventoryView.setProperty` reaches a legacy window's data values | `CraftContainer` shares the delegate's data slots [SRV]; no call traced | the phase-2 probe |
| **U8** | Legacy window buttons are inert (`CraftContainer` does not forward `clickMenuButton`) | Read in bytecode, not sent | the phase-2 probe |
| **U9** | Every screen fact on **1.21.4** (title positions, sizes, backgrounds, and now also U1, U2, U3 and U5): no 1.21.4 client jar is on this machine (§9.7), and a download is out of scope here | Needs the vanilla 1.21.4 client jar | a 1.21.4 jar read with `javap`; or the probe on a 1.21.4 bridge build. The harness test (`PaperContainerWindowIT`) covers the window type and size on 1.21.4 only |
| **U10** | The background sheets' names and sizes (256 x 256 sheets, blitted at the screen's size) on **1.21.4** | same | same |

Also not exercised by row R: the hover highlight and tooltip order (§9.1: the bridge cannot steer the mouse), the layer rule that
`gui.click_outside` runs on a hopper (the holder was the probe's, not `PluginGui`; the signal it reads was measured, §12.2.6), and the
runtime opener itself (rows M and P). Row P (#292) now opens both containers through the product on the 1.21.4 floor, and the
harness checks the window type, size, holder and the client's window id (§12.6); what it does not check is the screen the client
draws from that window, and a click on it: the 1.21.4 screen facts stay U9 and U10, and a click through the product's
`gui.click_outside` on a hopper stays unexercised.

The slot and menu facts are verified on both pins (§12.2.1); this table is only what a person looking at the screen, or a
jar this machine lacks, can add.

### 12.13 Decided by the owner (PR #287)

1. **v1 = chest + hopper + dispenser: approved.** The goal is to prove that GUIs work across container types, not to build the
   full array yet. The dispenser was conditional on the probe (U1), which confirmed it (row R, §12.2.6); anvil and furnaces are phase 2.
2. **The dropper is the dispenser: approved. The chest's rows become part of the type** if the chest is the only container with
   parameters, otherwise a parameter API: answered by §12.2.5 (the chest is the only one, and vanilla already splits it into six
   types), so the file model is types-as-parameters (§12.4).
3. **Underlay: option A (the owner's hopper and dispenser sheets) with C (the generated diagram) as the fallback now, and B
   (the user's own jar) later: approved.**
4. **`MTR-0097` to `MTR-0100` reserved: approved.**
5. **The system is agnostic of the container type: no special treatment for non-chest containers, and none for chests.** The
   chest is the default a New GUI starts with and nothing more. This removed the `gui.containers` capability, the "chest only"
   `rows` rule, the `appliesWhen` hint, the removal of `rows` on a switch, the chest adapter and the chest fast path in the
   opener (§12.4, §12.5, §12.6, §12.7). The alias and the older-server rule that an earlier draft of this item produced were
   dropped by item 9.
6. **The importer skips a GUI whose `inventory_type` is not supported yet: approved.**
7. **The furnace recipe-book button is acceptable (it cannot be removed): approved.** The furnaces stay phase 2 and the button
   is not a blocker.
8. **The chest ids are `chest_1` .. `chest_6`, with `hopper` and `dispenser`.** (Not vanilla's `generic_9xN`; the profile may
   carry the vanilla name as data.)
9. **`rows:` is dropped entirely: no legacy alias, no alias resolver, no `alias` profile field, no "both present" rule, no
   older-server alias rule.** The GUI system was never publicly released, so there is no compatibility to keep ("make smart
   decisions now, and not compromise for other decisions"). `container:` is required; a file without it is a schema error with
   the existing codes; an unknown id is `MTR-0097`. The repository's own files are converted in row M (§12.10), and the
   CHANGELOG entry says the change is breaking.

### 12.14 Still open

None. All owner questions are answered (§12.13) and the design is merged (#287). Row A (#293, the owner's hopper and dispenser
sheets) and row B (#296, the user's own jar) are open implementation rows, not design questions (§12.10).

