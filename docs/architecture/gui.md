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
# content/packs/<pack>/guis/shop/main.yml
title: Shop                    # accessibility/fallback title (narration, title-less clients)
rows: 6                        # 1..6 (chest); other containers are a later extension
layers:                        # bottom → top
  - id: frame                  # stable, unique within the GUI; [a-z0-9_]+
    type: panel                # component name in the gui-components registry
    name: Frame                # optional display name in the layers panel
    x: 0
    y: 0
    width: 176
    height: 222
  - id: wells
    type: slots
    region: container          # container | player | all
  - id: art
    type: paint                # pixel layer, backed by a canvas-sized PNG asset
    image: gui/shop/main/art   # textures/ implied, like every other texture reference
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
    font: smallcaps            # smallcaps | default
    color: "#404040"
  - id: sword
    type: item
    slot: 13
    item: shop:gui/main/sword  # a Materione item or a vanilla material (minecraft:diamond_sword)
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
                                  RegistryKeys.GUI_COMPONENTS
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

Steps (in `GuiType.compile`, deterministic per D7):

1. **Flatten raster layers** bottom → top onto one `ArgbCanvas` of canvas size (straight
   alpha, source-over compositing, integer math; `opacity` multiplies source alpha with
   round-half-up). Hidden layers are skipped.
2. **Encode** the canvas with the in-repo `PngEncoder` (RGBA8, no ancillary chunks, fixed
   filter and deflate level) — byte-stable for a given JDK; goldens also compare decoded
   pixels so a JDK zlib change cannot hide a real regression.
3. **Allocate a background glyph** in the pack's GUI font (allocation kind
   `gui-background`, durable like `glyph-codepoint`) and emit the bitmap provider with the
   ascent/height that places the bitmap's top-left on the screen's top-left given the
   title's fixed draw origin.
4. **Assemble the title**: `shift(-titleX)` → background glyph → `shift` back → each text
   run as `shift(x)` + text in the offset font for its `y` → `shift` back. Offset fonts are
   generated once per `(font, y)` actually used (a provider set with shifted ascent), merged
   per pack like glyph fonts.
5. **Slot table**: `slot → (item reference, amount)`; references resolve at apply time like
   recipe results (a missing item is a diagnostic and an empty slot, never a crash).

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
studio from the same PNG, so text preview is pixel-exact. `default` uses the client's own
font: offset providers reference the vanilla bitmap textures by resource location (nothing
redistributed); the studio preview of `default` is approximate and says so. Non-ASCII in
`default` is a documented v1 limitation.

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
