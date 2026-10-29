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
| **Canvas** | The full container screen in GUI pixels: `176 × (114 + rows·18)` — top container *and* the player-inventory area, exactly the area of the vanilla screen. |
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
(editor-only affordance, preserved), `opacity` (0–100, raster components only). Everything
else belongs to the component's schema. Unknown fields are preserved losslessly (master
§5.6); an unknown `type` quarantines that one layer (`MTR-` code assigned by the
implementing PR, documented in plugin.md §5) and the rest of the GUI still compiles.

**Customized items live next to the GUI as ordinary items.** When the studio customizes an
item layer's appearance, it creates a normal item resource at
`items/gui/<gui-path>/<layer-id>.yml` (a vanilla base becomes `material:`; a Materione base is
copied, since items cannot be templates, see section 6) and points the layer's `item:` at it. There is no "GUI-local item" concept: the item type, its
studio, its compile path and its auto-updater are reused as they are (D1, D5).

**Paint layers own one PNG** at `textures/<image>.png`, canvas-sized, RGBA. The studio
writes it through the asset store; the compiler reads it like any other texture.

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
| `SLOT` | A real item in a container slot | `item` |

**Z-order is honored within a target and fixed across targets** by the client's draw order
(raster under text; slot items relative to the title is the open question in §9). The studio
preview renders the *compiled* order, not the list order, and badges a layer whose list
position cannot be honored — what you see is what the player gets.

Steps (in `GuiCompiler.compile`, deterministic per D7; `GuiType.compile` and the runtime call
the same function, so the pack and the running server cannot disagree):

1. **Flatten raster layers** bottom → top (`GuiCompiler.flatten`). Each visible raster layer
   paints onto its own transparent canvas of canvas size (`176 × (114 + rows·18)`); that canvas
   is source-over composited onto the output with the layer's `opacity` (a group opacity, so a
   layer's own pixels never blend with each other). Pixel rules are below. Hidden layers are
   skipped. A layer whose component throws (a missing texture, say) draws nothing and is
   reported as `MTR-0052`; the others still draw.
2. **Encode** the canvas with the in-repo `PngEncoder` (RGBA8, the three chunks `IHDR`, `IDAT`,
   `IEND`, filter type 0, deflate level 6). The pack writes it at
   `assets/<ns>/textures/font/materione/gui/<id>.png`. Bytes are stable for a given JDK; the
   goldens also compare decoded pixels, so a deflate change cannot hide a pixel regression.
3. **Background glyph.** The GUI's durable allocation of kind `gui-background` (value `n`,
   like `glyph-codepoint`) maps to codepoint `U+E200 + n − 1`, so the block is
   `U+E200`–`U+F8FF` (BMP only, above the glyph range and the shift block). Its provider lives
   in the GUI font `<ns>:gui`: `{type: bitmap, file: <ns>:font/materione/gui/<id>.png,
   ascent: 13, height: H, chars: [cp]}` with `H` the canvas height. The bitmap is one cell, so
   its scale is 1; `ascent: 13` puts its row 0 on screen row 0 (see *Title geometry*).
4. **Title.** One MiniMessage string, built by `GuiTitle` (the exact arithmetic is below):
   `<shift:-8><font:<ns>:gui><#FFFFFF>{cp}</font><shift:8−advance>`, then per text run
   `<shift:Δ><font:<ns>:gui/<font>_y<y>><#RRGGBB>text</font>`. The background is white so no
   text colour tints it. The runtime model carries the title; nothing else is sent.
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
- *Text advances.* A `smallcaps` run's advance is the sum of its characters' advances, known
  exactly from the font's own pixels. A `default` run's advance would need the vanilla ASCII
  ink widths, which the compiler does not carry, so the pen is unknown after one: only the last
  text run of a title may be `default`. A run placed after it is reported (`MTR-0052`), never
  guessed.
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
  pixels in `fill`, interior `fill`. Panels use a 2 px highlight/shadow edge.
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
says so. Non-ASCII in `default` is a documented v1 limitation, and `default` text is restricted to
its last run until its advances ship (§9.4).

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
  from the item factory (`MaterioneStacks.build`, amount clamped to the stack size).
- *Clicks.* `GuiListener` routes three targets: the GUI's own slots and the outside of the window are
  cancelled, and the GUI's handler is empty in v1; the viewer's inventory stays live except the two
  actions that would move items into or out of the GUI (shift-move, double-click collection). The table
  is `GuiListener.route`, unit-tested.
- *Command.* `/materione gui open <id> [player]`, permission `materione.gui.open` (default op). A
  qualified id must be live; a bare value must match one live GUI. A console sender names a player.
  Markers: `MATERIONE-GUI-OPENED`, `MATERIONE-GUI-DENIED`, `MATERIONE-GUI-FAILED`.
- *Live refresh.* After every successful swap, `PipelineService.observeSwaps` runs on the global
  thread (after recipes; a rolled-back apply notifies nobody). `GuiRefreshPlan` compares the two views:
  a GUI whose compiled view differs (rows, title, or an item's definition hash in a slot) is CHANGED,
  and one no longer live is REMOVED. Each online player's region thread then acts on its own window
  only when it shows a changed or removed GUI:
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
  as `panel` + `slots` — the vanilla chest look, painted by our own bevel routine.
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
| Save | `gui/save.ts` | `authoring.setFields` with the changed top-level fields (`title`, `rows`, `layers`) and `assets: [{ path, data }]` (base64 PNG, via `ops.setFields`) for pixel assets a layer still refers to; a pixels-only save sends empty `edits` |

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
| Text layers | `gui/font/`, `gui/text.ts`, `gui/compose.ts`, `studio/gui/TextEditOverlay.svelte` | **smallcaps is pixel-exact**: the canvas draws each glyph from the shared font source `fonts/smallcaps/smallcaps.txt` (section 4, *Fonts*, parsed by `font/smallcaps.ts`; `font/render.ts` draws it), at the pen the compiler computes, tinted by the colour; the browser spec compares every ink pixel against glyphs parsed independently from that file. The studio also draws the vanilla **Inventory** label as a faint ghost at (8, height - 94), since the client draws it over the background (section 9.2). **Double-click** (or `Enter`) edits in place: a hidden input takes the keyboard, the canvas keeps drawing the real font, and the overlay draws only caret and selection from the font's own advances; typing previews live without touching the history, `Enter`/leaving commits one undo step, `Escape` takes it back. `default` is drawn with the bundled Minecraftia and says **Approximate preview**; its advances only size the selection box |
| Compiler rules, shown | `gui/validation.ts`, `gui/server.ts` (since #204, §10 #8) | the verdict is the server's: `gui.validate` answers for the document as shown, debounced as you edit, and each diagnostic lands on the layer it names. The studio holds no copy of the rules, so the list below is what the server enforces, stated where the author is looking: an orange `!` flag on the canvas, a warning icon in the layers panel and the reason in the inspector. Text: reserved characters, non-ASCII, blank or over-long text, `default` above row 5, and **only the last text run may be `default`** (the default run is told it hides the layers after it; those layers are told why). Items: a slot outside the container, a missing item, a bad amount, a later layer replacing an earlier one in the same slot. Skipped text is drawn as a faint ghost, not as the pixels the player will not get |
| Item layers | `studio/gui/ItemExtras.svelte` | the picker is the item studio's (Materione items with their thumbnails, vanilla materials with their icons); a pick is stored `pack:id` or `minecraft:id` (a bare id resolves in the `materione` namespace in game, so it is never written). Items **snap to container slots only**: drags, nudges and typed slots resolve to the chest's own slots (`ItemComponent` rejects the rest). The canvas draws the real item icon (the existing thumbnail pipeline, `ItemIcon` with a `bare` mode) and the stack count |
| Customize | `gui/customize.ts`, `gui/server.ts`, `GuiStudioState.customize`, `GuiItemAppearance.svelte` | see below; `gui.customizeItem` decides the item's id and refusals (§10 #2) |

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

| Later | Seam |
|---|---|
| Click bindings → ECA `menu.click` | a per-layer `on:` block owned by the gui type; `button`/`item` layers declare click regions |
| Pagination, search, dynamic fill | new components with target `SLOT` (+ runtime state) |
| Placeholders in text | `TEXT` runs are already title text, not baked pixels |
| Groups/folders of layers | a `group` component with children; flattening already recursive-ready |
| Hopper/dispenser/anvil containers | `container:` field; canvas size per container |
| Animated layers | frame cycling from issue #9 |

## 9. Open questions — answered (#188)

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
items on.

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
  floor version.
- **26.2:** only the texture and the advances were compared (byte-identical). No probe run.

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
6. **Default-font text is restricted.** Its advance is not known to the compiler, so only the
   last text run of a title may use `default`; a run after it, a run on row 4 or above, and a
   non-ASCII run are skipped with `MTR-0052`. Shipping a width table of the vanilla ASCII ink
   widths would lift the restriction; that is a metric-data decision for the owner.
7. **Colours stay `#RRGGBB` or `#AARRGGBB`** (#183's `GuiComponentConfiguration.color`). The
   shared vectors use the same order, and the studio writes the same strings.
8. **Raster components accept negative origins and zero sizes** (`x`, `y` in −512..512;
   `width`, `height` in 0..512). A layer partly outside the canvas is clipped, and a zero-size
   layer draws nothing; the shared vectors (`clipping`, `button-1px-bevel`) require both.
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
6. **The default-font warning stays until #202 ships.** The studio flags `default` above row 5 and any text after a `default` run today; once the vanilla ASCII widths ship (#202) those restrictions go and so does the warning.
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
4. **A title or size change re-opens; anything else edits in place.** The title and size of a Bukkit
   window are fixed at creation, so only those force a new window. The cursor item is moved off the
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
7. **Render and validate allocate nothing.** They compile through the pack's own steps with `AllocationTable.preview()`, a view that answers what the table would and binds nothing. The background codepoint does not change a pixel or a diagnostic, so `render` returns the bytes the pack ships; exhaustion (MTR-0053) is still reported exactly.
8. **Validate and the pack compiler are one set of steps, pinned by a test.** `GuiAuthoringApiImpl.check` mirrors `PackCompiler`'s per-resource sequence (parse, diagnose, compile with a collecting emitter, the crash and rejection diagnostics in the same order). `GuiValidateParityTest` compiles a GUI with every kind of skip through `PackCompiler` and asserts `validate` returns the same diagnostics. The sequence is mirrored, not shared, because `pack` does not depend on `content`; moving it into `core` is the follow-up if the mirror ever drifts.
9. **Components are described from the API.** `GuiComponentDescriptor` (api) is built from the registry entry; `EditorContentOps` serializes it and nothing else. `GuiComponentsApi` (api §3.10, `MaterioneAPI.guiComponents()`) registers addon components on the bounded path `mechanics()` uses, so a registration works after the registry froze and is removed by its owner's disable.
10. **The `gui` capability is this change's, with #185's name.** Both PRs advertise `gui` and gate their ops on it. The five ops are listed in `editor-op-gates.json`; an older release that lacks them is judged by the compat check as a gated op, so the studio must check `hasCapability(…, 'gui')` before calling any of them.
11. **Pending changes are saved before Customize.** Customize is one server transaction at the GUI's revision; the layout and pixels that are pending are saved first as their own transaction, because the server works on the saved file. The earlier single `authoring.bulk` that carried both is gone. Nothing is lost if the customization is refused: the save stands, and the pointer is applied to the studio's copy only after the server answers. Reverting this means sending the pending layout inside `gui.customizeItem`, which needs a list-item path for a per-layer patch (decision 5).
12. **The studio keeps `authoring.setFields` for saves.** Its layer model is a whole-list diff, so the changed `layers` value is the natural unit; `gui.edit` is the structural path for API consumers. Switching the studio's save would mean deriving the five structural edits from the diff, which loses the one-shot "the layout as it stands" property the studio relies on.
13. **Without the `gui` capability the studio degrades, and says so.** A connection whose session blob does not advertise `gui` (an older plugin, or the demo, which has no blob) gets no verdict and no Customize: the studio shows an explicit banner, draws no skip flags, and disables Customize with the reason. Layout editing and saves are unchanged, since they use `setFields` as before.
14. **Demo and browser tests.** The demo server has no compiler, so its studio reports the verdict as unavailable; `gui-studio.pw.ts` asserts that state, and the live Customize flow is pinned by the QA catalog (`gui.studio-item-customize`) on a running server.
