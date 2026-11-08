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
| **GUI** | One `guis/**/*.yml` resource: a container screen design (v1: a chest of 1–6 rows). |
| **Canvas** | The full container screen in GUI pixels: `176 × (114 + rows·18)` — top container *and* the player-inventory area, exactly the area of the vanilla screen. *Replaced by the open canvas of §11 (#274): the compiler (#276) flattens over the art cap, so art may extend past the screen; the studio (#277) and the starter GUI (#278) follow.* |
| **Layer** | One entry in the GUI's ordered `layers:` list (bottom → top). Every layer is an instance of one component. |
| **Component** | A registered layer kind (`gui-components` registry): `panel`, `slots`, `image`, `paint`, `button`, `text`, `item` in v1. Addons register more through the same path. |
| **Render target** | Where a component's output physically lands in the client: the **raster** background, the **title text**, or a container **slot** (§4). |

---

## 2. File format

```yaml
# content/packs/<pack>/guis/shop.yml
shop:                          # one resource per key (master §7.2); the key is the GUI id
  title: Shop                  # accessibility/fallback title (narration, title-less clients)
  rows: 6                      # 1..6 (chest); other containers are a later extension
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
copied, since items cannot be templates, see section 6) and points the layer's `item:` at it. There is no "GUI-local item" concept: the item type, its
studio, its compile path and its auto-updater are reused as they are (D1, D5).

**Paint layers own one PNG** at `textures/<image>.png`, canvas-sized, RGBA. The studio
writes it through the asset store; the compiler reads it like any other texture.

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
  (title component + slot contents + rows) from the shadow swap and renders it through the
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
it on screen (§9.1).

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
  a GUI whose compiled view differs (rows, title, or an item's definition hash in a slot) is CHANGED,
  and one no longer live is REMOVED. Each viewer's region thread then acts on its own window
  only when it shows a changed or removed GUI (since #214 the viewers are the open sessions, and the
  session carries on through a rewrite or a re-open: arguments, variables and the back stack survive,
  logic.md §21 decision 17):
  - *items only* (same rows and title): the slots are rewritten in place and the window stays;
  - *title or rows changed*: a new window is opened. The cursor item is lifted off the old window
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
  with no layers: the title and the rows, nothing else (§11.2, built in #278). The vanilla chest is the client's own, and the studio draws the owner's reference art under it (§11, #277). A new **panel** starts with `corners: vanilla`.
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
| Document model | `gui/model.ts` | the API's own `title`/`rows`/`layers` structure, immutable; unknown fields, layer types and top-level keys ride along; a file with a layer the model cannot hold (no type, missing or duplicate id) opens **read-only** instead of being rewritten. The file is keyed by its resource id (`<id>:` then the fields) and field paths are `[id, 'layers']`; an unkeyed file is read for display only and never written |
| History | `gui/commands.ts`, `gui/history.ts` | every edit is an invertible command (patch, insert, remove, reorder, document fields, composite); drags commit one command on release; nudges and scrubs coalesce within 900 ms; undoing back to the saved baseline makes the document clean |
| Canvas rules | `gui/scene.ts`, `snap.ts`, `transform.ts`, `viewport.ts` | bounds, hit testing, snapping (canvas edges and middle, slot grid, sibling edges and centers; whole pixels only), 8-handle resize, zoom 1-16x anchored at the cursor |
| Compiled order | `gui/scene.ts` | raster layers, then text, then slot items, each in list order; a layer listed on the wrong side of a layer of another target carries a `#n` badge. The relative order of title text and slot items is still the open question of section 9.1, so the studio states the assumption in one place (`TARGET_RANK`) |
| Registry | `gui/registry.ts` | palette, inspector fields, defaults for a new layer and what may be dragged or resized are all derived from the component descriptions (`guiComponentsOf` over `authoring.describe`; `x`/`y`/`width`/`height` fields mean movable and resizable, `slot` means slot-bound; a layer's `type:` matches the registry id with `materione:` optional); no component list lives in the studio. Cosmetic icons and the bevel style's four colors (a raw map in the schema) are the only studio-side knowledge |
| Save | `gui/save.ts` | `authoring.setFields` with the changed top-level fields (`title`, `rows`, `layers`, and `open` since #225) and `assets: [{ path, data }]` (base64 PNG, via `ops.setFields`) for pixel assets a layer still refers to; a pixels-only save sends empty `edits` |

Shortcuts: `V` select, `H` hand, `Space` (hold) pan, `0` fit, `1` 100%, `+`/`-` zoom,
`Ctrl+Z` / `Ctrl+Shift+Z` undo/redo, `Ctrl+D` duplicate, `Del` delete, `Ctrl+A` all, `Ctrl+]`/`[`
raise/lower, `F2` rename, `Enter` edit the selected text in place, arrows nudge (`Shift` = 10 px),
`Ctrl+S` save, and the paint tools below.

**As built (issue 187: paint, image, text and item components).** Text and item layers are real
renders now (the placeholders are gone), and the paint, image, text and item components each have
the tools a designer expects.

| Piece | Where | Notes |
|---|---|---|
| Paint tools | `gui/paint.ts`, `gui/pixel-command.ts`, `studio/gui/PaintOptions.svelte` | brush and eraser (1-8 px, square or round), contiguous fill (tolerance 0), eyedropper (samples the **composited** canvas: raster layers and smallcaps text, as the player sees it), palette with recent colours and hex input (persisted in the browser, never in the file). The drawing rules are `pixels.ts`'s (stamp, Bresenham line, exact-match flood fill) run on a working RGBA copy of the layer's `ArgbCanvas`; `Brush` gained an optional `shape` for the round footprint (additive: the texture editor is unchanged). A stroke joins every pointer event, coalesced ones included, with a line, so a fast drag leaves no gaps at any zoom; Shift-click draws a straight line from the last stroke's end; Alt picks a colour from any paint tool |
| Strokes are commands | `GuiHistory` | a stroke, fill or clear is **one** `PixelCommand` carrying a patch (touched pixels, before and after) rather than a copy of the canvas. The document does not change, so the history tracks the newest applied pixel command by identity: a stroke makes the studio dirty, undoing back to the saved state makes it clean, and undoing past a save, or painting something else after it, never looks clean. Paint-only saves send empty `edits` plus the PNG (section 7) |
| Paint target | `GuiStudioState` | the selected paint layer, else the topmost visible unlocked one, else a new `Paint` layer is created by the first stroke (one more undo step); a locked or hidden target is explained in the status bar, never painted. A paint layer whose canvas no longer matches the rows is grown (overlap kept) |
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
**Materione item** base becomes a `duplicate` of its document: items are not templates
(`template:` resolves only against `templates/`), so a live link to a base item cannot be
expressed; the copy starts identical, keeps the base's own `template:`, and is then the layer's
to change. Blocks and furniture cannot be customized (no item document). The inline appearance
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
| Hopper/dispenser/anvil containers | `container:` field; canvas size per container |
| Animated layers | frame cycling from issue #9 |

## 9. Open questions — answered (#188, #275)

Each answer states what was verified and how: client bytecode (the 26.1.2 client jar, read with
`javap`; method names are given) or a probe run (`docs/probe.md`: the 26.1.2 client joined to the
pinned Paper 26.1.2 server). **There is no 1.21.4 evidence yet.** This machine has no 1.21.4
client jar, and the probe loop runs 26.x only, so every 1.21.4 statement here is *unverified*.

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
   master §7.2 rule for every content type; a bare `title:`/`rows:`/`layers:` file would
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

1. **Customize copies a Materione base item instead of template-linking it, until items can be templates (#203).** The template pool is `templates/` only, so `template: <item>` would quarantine the new item; `authoring.bulk` `duplicate` is the closest faithful operation. If a live link to a base item is wanted, it needs a template-pool decision (items usable as templates), not a studio workaround.
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
2. **Customize's naming and refusals moved server-side unchanged** (`GuiItemNaming`, `GuiAuthoringApiImpl.customizeItem`): the layer id when the pack has no resource of that id, else `<gui>_<layer>`, else numbered; a vanilla base is a `create` with `material:` (refused when the server's material list does not know it); a Materione item is a `duplicate`, with a refusal when it is not an item of this pack or the layer already has its own. **The seam for #203** is the one place a Materione base is copied (`GuiAuthoringApiImpl.customizeItem`, the `DUPLICATE` branch): switching it to a template link is a change of that one operation once items can be templates.
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

---

## 11. The open canvas (#274) — design approved by the owner; compiler built (#276)

> **Status.** Approved by the owner on 2026-11-05. It replaces the bounded canvas of §1, the starter GUI
> of §6 and the full-screen flatten of §4 step 1 **as each issue lands**: #275 (research), #276
> (compiler), #277 (studio), #278 (starter, showcase, panel corners; **landed**, §11.6). Until an issue lands, the
> sections it replaces still describe the shipped behaviour. **#275 has landed:** the facts this section
> marked *to verify* were measured on the 26.1.2 probe client (§9.8–§9.15) and the rules below are the
> verified ones, with the exact numbers #276 needs. One finding amends the design: the art box is
> asymmetric at the top (§11.2). 1.21.4 is not measured (§9.7). **#276 has landed:** the compiler and the
> runtime implement §11.2's flatten and §11.3 rules 1 to 6, and the warning of §11.4 (see *Decisions taken in
> #276*). The studio (#277) is still to land.

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
PNG is canvas-sized any more.

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
