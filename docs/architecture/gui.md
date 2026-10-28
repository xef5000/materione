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
   The vanilla ASCII texture is referenced by resource location and never shipped.
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

**Fonts for text layers.** `smallcaps` is a first-party bitmap font authored in this repo
(original artwork — no Mojang or incumbent glyphs), shipped in the pack, previewed in the
studio from the same PNG, so text preview is pixel-exact. In #184 it is a **placeholder**: a
3 × 5 pixel table drawn in code (`SmallcapsFont`), printable ASCII, lowercase drawn with the
capital shapes, emitted as a grayscale sheet so the client tints it with the text colour. Its
width table is the client's rule applied to the same pixels, so it cannot drift from the
artwork; the research issue (#188) replaces the table and nothing else. `default` uses the
client's own font: offset providers reference the vanilla bitmap textures by resource location
(nothing redistributed); the studio preview of `default` is approximate and says so. Non-ASCII
in `default` is a documented v1 limitation.

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

## 9. Open questions (verify, then record the answer here)

1. **Draw order of title vs slot items** on 1.21.4 and 26.x (the 1.21.6 GUI render
   refactor may differ). Verify with the probe loop (`docs/probe.md`) before claiming the
   target order in the studio.
2. **The vanilla "Inventory" label** draws over the background. Options: leave it, or an
   opt-in pack-level lang override (global effect — must be a visible owner choice).
3. **Maximum background bitmap size** per client version (font provider limits) and whether
   tall canvases need splitting into several glyphs.

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
