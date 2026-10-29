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
`items/gui/<gui-path>/<layer-id>.yml` (optionally `template:`-linked to the base item) and
points the layer's `item:` at it. There is no "GUI-local item" concept: the item type, its
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

Text and item layers are drawn as clearly marked placeholders until the paint, image, text and
item studio work lands (issue 187); it plugs in by registering painters, not by changing the
canvas. Shortcuts: `V` select, `H` hand, `Space` (hold) pan, `0` fit, `1` 100%, `+`/`-` zoom,
`Ctrl+Z` / `Ctrl+Shift+Z` undo/redo, `Ctrl+D` duplicate, `Del` delete, `Ctrl+A` all, `Ctrl+]`/`[`
raise/lower, `F2` rename, arrows nudge (`Shift` = 10 px), `Ctrl+S` save.

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
