# HUDs: a view kind over state (M6, #7)

> **Status: design, awaiting the owner's approval (#7).** Nothing here is built. Every decision this design takes is reversible
> by the owner; what it does not decide is in §17 (owner questions). Facts marked "row R confirms" are read from the client's
> bytecode or reasoned, not yet seen on screen.
>
> **Traceability.** Implements the HUD row of the parity contract ([`feature-parity.md`](../feature-parity.md) §3.8 →
> [`parity-reconciliation.md`](parity-reconciliation.md), owner `runtime-systems.md` §7, M6) and refines
> [`runtime-systems.md`](runtime-systems.md) §7. Binding decisions: D1 (files), D2 (the `hud ──uses-glyph──► glyph` edge, master
> §5.3), D3 (appearance compiles, behavior runs), D4 (one engine: bindings are the logic engine's templates), D5 (registries and
> self-description), D6 (one pipeline), D7 (durable allocation), D8 (Folia), D10 (1.21.4 floor), D12 (a HUD is a **view kind**,
> [`primitives.md`](primitives.md) §5.3, #335), D13 (the SDK is generated). Parent: [`architecture.md`](../architecture.md) ·
> Companions: [`gui.md`](gui.md) (the compiler this reuses), [`logic.md`](logic.md) (templates, variables, rules),
> [`plugin.md`](plugin.md) (§2 threads, §5 diagnostics, §9 budgets), [`web.md`](web.md) (studio, editor ops).

---

## 1. Goals and non-goals

**Goals.**

| # | Goal | Traces to |
|---|---|---|
| G1 | A `hud` content type (`huds/*.yml`, master §7.2) whose layers are **image**, **text** and **bar**, positioned in integer screen pixels against an anchor, layered, optionally scaled. | feature-parity §3.8, runtime-systems §7 |
| G2 | Layer values bound to **templates**: `${expression}`, declared variables, PlaceholderAPI `%token%`, the exact value syntax of every other Materione parameter. No HUD-specific binding language and no HUD-specific value store. | D4, logic.md §2.5, primitives.md §5.3 |
| G3 | Visibility per world, per permission and by any condition, evaluated per viewer. | feature-parity §3.8 |
| G4 | A HUD is a **view over state**: it names the state it reads, its viewers are a set, a write to that state re-renders exactly the viewers that read it. | D12, primitives.md §5.3, #335 |
| G5 | Animated images, as a **consumer** of the animated-glyph frame provider (#9), not a second animation system. | runtime-systems §6, #9 |
| G6 | Compiles through the one pipeline into the resource pack; runtime is a shadow-swapped view; fail-soft per layer. | D6, runtime-systems §1 |
| G7 | Pixel-exact, without a client mod, on every client the D10 floor admits (1.21.4 and later). | D10 |
| G8 | Budgets stated and CI-tracked from the first row (§10). | plugin.md §9 |

**Non-goals (v1).**

| Not in v1 | Why, and where it goes |
|---|---|
| Edge anchors (top-left, bottom-right, screen centre) | They need a core shader to read the screen size; shader-free text surfaces are centred (§5.2). Row X (§15), owner question Q1. |
| The title and subtitle channels | Scaled 4× and 2×, and the surface every announcement plugin uses. A `title` action stays an action. |
| Retexturing vanilla HUD parts (hearts, hunger, hotbar) | A pack concern (resource overrides), not a view. Nexo's Bedrock-only retexture is out of scope here. |
| Packet-level merging with other plugins' action-bar text | Needs a packet interceptor, a dependency question (Q3). v1 coexists by rules (§11). |
| HUD-specific actions or triggers (`hud.set`, `hud.threshold`, `hud.refresh`) | Feature-shaped under D12 (§3.3); values are variables, thresholds are conditions. The generic `view.refresh` (`views.md` §3.6, row V2, #457) re-renders a HUD's viewers, polled reads included, so no `hud.refresh` is added. |
| A `behavior:` block on a HUD | A HUD is not a rule host in v1; behavior lives in `rule` documents (logic.md §37). An authored `behavior:` key is preserved as an unknown field (master §5.6) and reported (`MTR-0110`). |
| Text-display entities as the render surface | Rejected (§5.1): they are world-space, not screen-space. |
| Bedrock (Geyser) HUDs | Master §14.3, open. |

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **HUD** | One `hud` resource: a list of layers, a visibility condition and a refresh clock. |
| **Layer** | One positioned element of a HUD: a component (`image`, `text`, `bar`, …) plus the common fields of §4.2. |
| **Channel** | The vanilla text surface a layer is delivered through: `bossbar` (the boss-bar name, top of the screen) or `actionbar` (the overlay message above the hotbar). Derived from the anchor, never authored. |
| **Anchor** | The screen point a layer's `x`/`y` are measured from (§5.2). |
| **Piece** | The compiled form of one layer frame: a run of glyphs that starts and ends with the pen at the channel origin, so pieces concatenate in any order (the GUI's rule, gui.md §11.3 rule 5). |
| **Viewer** | An online player for whom a HUD's visibility holds and whose loaded pack contains the HUD (§8.4). |
| **Reads** | The set of state keys a HUD's templates and conditions read, extracted at compile time by the shared views extraction (§7.2, `views.md` §5.2). |

---

## 3. Where a HUD sits: a view kind (D12)

### 3.1 The HUD as a view kind

The view model is designed in **`views.md`** (#335, PR #454; owner answers 2026-11-19), written before this design is built so
that neither is built twice. The HUD implements the view-kind contract of `views.md` §3.2 with these values (`views.md` §4.1,
§4.4):

| Contract member | HUD |
|---|---|
| `contentType` | `hud` |
| `viewerRule` | `predicate`: every online player for whom the HUD's visibility holds (§6, `views.md` §3.5); no open or close verb |
| Binding | none: no `args`, no subject stack, no key. Keyed reads resolve their key per viewer at render (`views.md` §4.4, §5.3) |
| `reads(D)` | the variables and polled sources its templates, conditions, `worlds` and `permission` read (§7.2), extracted into the shared reads index |
| `render` | one string per channel (§8.2) |
| `deliver` | diff with the last sent string; the channel writers (§8.2) |
| Rerender policy | always `on_change`; there is no `rerender:` key on a HUD |
| `minInterval` | 1 tick (2 for server-driven animation, §5.6) |
| Viewer record | `HudViewer`: **one per online player, from join to quit**, attached to zero or more visible HUDs and composing them into one output per channel (§8.2); holds last-sent strings, poll cache, animation phase (viewer-local state only) |

Showing a HUD to one player is content: its `visible_if` reads a variable, and a rule sets that variable (§6.3). There is one
binding system: the logic engine's templates and the variable store (logic.md §2.5, §3), through `views.md`'s reads index and
write observer (§7.2).

### 3.2 The universality tests (primitives.md §2.2)

| Test | How the HUD passes |
|---|---|
| **U1 Name** | `hud` is a view kind; its components are `image`, `text`, `bar`: capabilities, not use cases. There is no "mana bar" anywhere in the engine; T20's mana bar is a `bar` reading a variable (agent-benchmark.md T20). |
| **U2 Generality** | Any declared variable, any expression, any PlaceholderAPI token, any player. Keyed state (#333 step 2) is read the same way when it lands. |
| **U3 Composition** | Values are written by `set_var` from any rule; visibility reads any condition; no Java to wire a new HUD. |
| **U4 Self-description** | `hud` is a registered content type; its layers are entries of the existing `gui-components` registry (§4.3), each with its schema. The editor's inspector and the agent's SDK are generated from them (§13, §14). |
| **U5 Safety** | Offline validation (levels 1–4) of the file, the components, the reads and the anchors; dry-run by the transaction pipeline; a broken layer hides for that viewer with a diagnostic (§12). |

### 3.3 What D12 removes from the earlier sketch

Master §8.1 lists "HUD thresholds" among events and "set HUD value" among actions, and logic.md §11 left the seams `hud.threshold`
(a trigger) and `hud.set` (an action). Under D12 both are feature-shaped (U1 fails: they would be wrong in a second feature):

- **"Set HUD value" is `set_var`** on a declared variable (logic.md §3.3). primitives.md §5.3 already places HUD values in keyed
  state; master §8.3's persistence row ("HUD values ... player PDC") is then the `player` variable store, unchanged.
- **"HUD threshold" is a condition over a variable.** A rule that must fire when a value crosses a line is a variable-change
  trigger, a primitive for any variable, not a HUD event. It is not designed here (it belongs with #333 or #327's trigger set).

This reading of master §8.1 is recorded as owner question Q6, because the master's sentence names them.

---

## 4. The content type and file format

### 4.1 Example

```yaml
# content/packs/rpg/huds/mana.yml
mana:                                  # one resource per key (master §7.2); the key is the HUD id
  worlds: [world, world_nether]        # optional; absent = every world (§6.1)
  permission: rpg.hud.mana             # optional (§6.1)
  visible_if: "${vars.player.rpg.hud_on}"   # optional condition (logic.md §2.3); absent = true
  priority: 10                         # optional; orders HUDs sharing a channel (§5.4); default 0
  refresh: 5                           # ticks between polls of polled reads (§8.3); default 5, min 2
  layers:                              # bottom → top
    - id: frame
      type: image
      anchor: hotbar_right             # §5.2
      x: 4
      y: -12
      image: hud/rpg/mana_frame        # textures/ implied, like every texture reference
    - id: fill
      type: bar
      anchor: hotbar_right
      x: 5
      y: -11
      fill: hud/rpg/mana_fill          # the full bar; the compiler slices it (§5.5)
      value: "${vars.player.rpg.mana}"
      max: 100
      direction: right                 # right | left | up | down
    - id: icon
      type: image
      anchor: hotbar_right
      x: -8
      y: -13
      glyph: rpg:mana_orb              # an animated glyph: frames come from the provider (§5.6)
    - id: number
      type: text
      anchor: hotbar_right
      x: 50
      y: -11
      text: "${vars.player.rpg.mana}/100"
      font: default                    # default | smallcaps, as in GUIs (gui.md §2)
      color: "#7FBFFF"
      align: left                      # left | center | right
    - id: papi
      type: text
      anchor: top
      x: 0
      y: 24
      text: "%vault_eco_balance_formatted%"
      visible_if: "${player.world == 'world'}"
```

### 4.2 HUD and common layer fields

Owned by the `hud` type, not by components:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `worlds` | list of world names | absent = all | Viewer must be in one of them (§6.1). Sugar that compiles into the visibility condition; a person could write the condition by hand (D12 R4). |
| `permission` | string | absent | Viewer must have it (`has_permission`, logic.md §4.2). Same sugar rule. |
| `visible_if` | condition | true | Any condition of the logic engine (logic.md §2.3). |
| `priority` | integer | 0 | Draw order between HUDs on one channel (§5.4). |
| `refresh` | ticks, 2..200 | 5 | Poll clock for reads that cannot notify (§8.3). runtime-systems §7's "~4 Hz" default. |
| `layers` | list | required | Bottom to top. |
| *layer* `id`, `type`, `name`, `visible`, `locked`, `opacity`, `visible_if` | as in gui.md §2 | as in gui.md §2 | The GUI's common layer fields, same meaning. |
| *layer* `anchor` | anchor name (§5.2) | `bottom` | The point `x`/`y` are measured from. Selects the channel. |
| *layer* `x`, `y` | integers | 0 | Offset of the layer's top-left from the anchor, in GUI pixels; `y` grows downward. |
| *layer* `scale` | integer 1..4 | 1 | Integer pixel scale (§5.3). |

Unknown fields at every level are preserved (master §5.6): load then untouched save is byte-identical, which the golden
round-trip fixtures pin (§15 row S). The schema has **one source**: `HudType.describe()` → `SelfDescription` (plugin.md §3,
D5), which the studio, the validator and the SDK generator all read.

### 4.3 Components: the GUI's registry, a HUD surface

A HUD layer's `type` names an entry of the existing **`gui-components` registry** (gui.md §3, §10), not a new registry. Each
component already declares its render target (`RASTER`, `TEXT`, `SLOT`, gui.md §4); a HUD is a new **surface** for the RASTER
and TEXT targets.

| Component | On a HUD | Notes |
|---|---|---|
| `image` | yes | Takes `image:` (a texture) **or** `glyph:` (a glyph resource, the D2 edge `hud ──uses-glyph──► glyph`). `glyph:` is new for `image` and also valid in GUIs. |
| `paint`, `panel` | yes | RASTER, unchanged. |
| `text` | yes | TEXT; gains `align` (left, center, right), which needs the measured advances the GUI already has (gui.md §9.4). |
| `bar` | **new**, HUD only in v1 | RASTER with a value (§5.5). Declared in `gui-components` so a later GUI can use it; GUI support is not in v1 (the GUI's dynamic-visual path would need a value-to-frame mapping, logic.md §6.4). |
| `slots`, `item`, `button`, `list` | **no** | SLOT target or click footprint; a HUD has neither. The layer is skipped with `MTR-0110`; the rest of the HUD compiles. |

Each component declares the surfaces it supports in its self-description, so the studio and the SDK offer only the valid ones
on a HUD (§13, §14). Reusing the registry is the D12 reading of "one view model, three kinds" (§3.1): an addon component that
draws raster art works in a GUI and on a HUD with no second registration. Whether to keep it that way, or give HUDs their own
registry, is owner question Q5.

---

## 5. The element model and how it reaches the screen

### 5.1 The render surface: text, not entities

runtime-systems §7 sketched "per-player packet-driven text displays, not boss bars". That sketch is **corrected here**, with this
rationale: a text-display entity is drawn in world space. Riding the player it stays at the body's position while the head
turns, is occluded by blocks, is distorted by field of view and view bobbing, and shows in third person. A HUD must be drawn in
screen space, in GUI pixels, above the world. The only server-driven screen-space surfaces a vanilla 1.21.4+ client offers are
**text components on HUD overlays**: the boss-bar name, the action-bar overlay message and the title. A custom font makes such
text into positioned bitmaps: the technique the GUI already uses for container titles (gui.md §4). The difference from the
incumbents' "boss-bar tricks" (glossary) is not the transport but the engine: a typed layer model, a deterministic layout
compiler, bindings, viewer sets and diagnostics. The boss bar is only the wire.

(Row R confirms the rejection on screen with one text display ridden by the player; if it showed otherwise, this section is
revised before row V.)

### 5.2 Channels, anchors and the geometry

The two channels v1 uses, as read from the 26.1.2 client's bytecode (`BossHealthOverlay.render`, `Gui.renderOverlayMessage`);
row R confirms every number on screen on 1.21.4 and 26.x before the compiler depends on it, as gui.md §4 did for titles:

| Channel | Where the client draws it | Horizontal | Draw row `R` | Lifetime |
|---|---|---|---|---|
| `bossbar` | First boss bar's name, top of screen | `x = ⌊W/2⌋ − ⌊w/2⌋` | 3 (first bar; each further bar +19) | Until removed |
| `actionbar` | Overlay message above the hotbar | `x = ⌊W/2⌋ − ⌊w/2⌋` | `H − 72` | 60 ticks; opaque for the first 40, then fades |

`W`, `H` are the GUI-pixel window size (gui.md §9.12), `w` is the string's advance. Every compiled channel string has
**advance 0** (each piece returns the pen to the origin), so `x = ⌊W/2⌋` for every player at every GUI scale: the origin is the
screen's centre column. Vertically, a bitmap glyph's top is `R + 7 − ascent` (the bitmap rule verified for titles, gui.md §4
*Title geometry*; zero and negative ascents are valid, gui.md §9.10 fact 1).

So without a shader, **every position is relative to the top centre or the bottom centre of the screen.** v1's anchors are a
closed, engine-owned set of such points:

| Anchor | Channel | Point (GUI px) | Note |
|---|---|---|---|
| `top` | bossbar | (centre, 0) | |
| `bottom` | actionbar | (centre, H) | The default. |
| `hotbar_left` | actionbar | (centre − 91, H − 22) | The hotbar is 182 × 22, centred, on the bottom edge. |
| `hotbar_right` | actionbar | (centre + 91, H − 22) | |
| `above_hotbar` | actionbar | (centre, H − 22) | |

A layer at anchor point `(ax, ay)` with offset `(x, y)` has its top-left at `(ax + x, ay + y)`. The compiler turns that into a
shift and an ascent: `ascent = R + 7 − top`, where `top` is expressed relative to the channel's own row. The hotbar names are
fixed offsets from `bottom`; they exist because "next to the hotbar" is what authors mean, and they are the same pixels a person
could write against `bottom` (D12 R4).

**Constraints the compiler enforces** (all from verified client facts, gui.md §9.10): every provider has `ascent ≤ height`, a cell
of 1..256 px in both dimensions, and lives in a HUD font file of its own (§5.7), so a violation can never be emitted. A layer
reaches at most `256 − 7` px above its channel row. A layer whose box extends past ±213 px from the centre column is visible
only on windows wider than the smallest GUI area (427 px, gui.md §9.12) and is reported (`MTR-0114`, warning).

**Shadows.** The client draws both channels with a drop shadow, which would print a dark copy of every image. Since 1.21.4 a
text component carries `shadow_color`; image and bar pieces are sent with a fully transparent shadow; `text` layers keep the
shadow unless `shadow: false`. (1.21.4 is the D10 floor, so this needs no fallback. Row R confirms.)

**Known client artefacts, documented not fixed:** a player who turns off "chat only" text backgrounds gets the action bar's
backdrop, a 4 × 13 px box at the origin (the backdrop is sized from the zero advance); the boss-bar channel moves down 19 px per
boss bar another plugin showed before Materione's (§11).

### 5.3 Positioning, scale and layering

- **Integer pixel math only** (runtime-systems §7). `x`, `y`, `scale` are integers; non-integers are refused at level 1.
- **Scale** is the provider's `height = cell × scale`, which the client draws `scale` times larger with advance
  `w·scale + 1` (gui.md §9.10 fact 5, verified). `cell × scale ≤ 256` is enforced (the verified bound); a larger product skips
  the layer (`MTR-0110`). Text scale is the same rule on the offset font.
- **Within a HUD**, layer order is z order. Static raster layers (no `visible_if`, no animation, no binding) on the same channel
  are **flattened at compile time** into tiles, exactly as the GUI flattens its static raster (gui.md §4 steps 1 to 3, the same
  functions), so their overlap is pixel-exact. Dynamic layers are separate pieces drawn in layer order. Draw order between
  glyphs of different textures inside one string is not verified; row R measures it, and if the client batches by texture the
  studio badges overlapping dynamic layers as it badges unhonourable GUI order (gui.md §4).
- **Across HUDs** on one channel, pieces are concatenated by `priority`, then by HUD id (deterministic, D7).
- **Across channels**, the client's own HUD order decides; row R records it.

### 5.4 Text

A `text` layer is a template rendered per viewer and laid out with the measured advances the GUI uses (the `default` font's
95-entry width table, the `smallcaps` sheet's own pixels; gui.md §9.4, *Text advances*). `align` moves the run's start by
`0`, `−w/2` or `−w` after measuring. Fonts are offset fonts `<ns>:hud/<font>_y<A>`, one per distinct ascent, built by the same
generator as the GUI's `<ns>:gui/<font>_y<Y>` (gui.md §4 step 5) and referencing the vanilla ASCII texture by resource location,
never shipping it. A rendered value that breaks the text rules (characters outside the measured set) is not drawn and reports
`MTR-0113`, as a GUI's dynamic text does (`MTR-0073`). Glyph markers (`<glyph:id>`) inside a HUD text keep their own font's
vertical metrics; a positioned icon is an `image` layer.

### 5.5 Bars

A `bar` renders `clamp(value / max, 0, 1)` of its `fill` art, with optional `background` art under it and an optional `steps`
count.

| Field | Default | Meaning |
|---|---|---|
| `fill` | required | Texture (or `glyph:`) of the full bar. |
| `background` | absent | Drawn under the fill; static, so it flattens (§5.3). |
| `value`, `max` | required, `1` | Templates evaluating to numbers. A non-number hides the layer for that viewer (`MTR-0113`). |
| `direction` | `right` | Which way the bar fills. |
| `steps` | the fill's length in px | How many distinct fill states; 1..256. |
| `mode` | `clip` | `clip`: the fill art is cut into `steps` slices along `direction`, each slice its own glyph, and the bar emits the first `n = round(f · steps)` slices; `segments`: `count` copies of `full` / `half` / `empty` art (hearts, pips), the value choosing each copy. |

Cost: a `clip` bar allocates `steps` codepoints (one per slice); a `segments` bar allocates three (one per state) per distinct
position. Vertical bars cut row slices, each with its own ascent, which the per-glyph provider allows. Both are bounded by
`steps ≤ 256` and the codepoint block (§5.7).

### 5.6 Animated images: a consumer of the frame provider (#9)

An `image` whose `glyph:` names an animated glyph (runtime-systems §6: `frames`, one durable codepoint per frame) plays it.
The frame-cycling provider is being built in #9; the HUD depends only on what any surface must know, not on its API:

| The HUD needs to know, per animated glyph | Why |
|---|---|
| Whether cycling is **client-driven** (the pack advances frames itself) or **server-driven** (a surface must send the next frame). | Client-driven: the HUD emits the piece once. Server-driven: the HUD re-renders when the frame changes. |
| For server-driven: the frame count, each frame's duration in ticks, and the phase (global or per viewer). | So the HUD computes the frame for a tick and schedules its next wake (§8.3) instead of polling. |

The HUD compiles one placement glyph per frame (§5.7), so whichever the provider is, the pixels are already in the pack. A
server-driven animation re-renders at most every 2 ticks on a HUD (10 fps); a faster glyph is clamped for HUDs with an info
diagnostic (`MTR-0115`), because each frame is a packet per viewer (§10). If #9 lands after the HUD, row A (§15) wires it; until
then an animated glyph shows frame 1 on a HUD, as it does everywhere today (roadmap M4 as-built).

### 5.7 Compilation (D6, D7)

`HudType.compile` runs in the pipeline's compile stage (plugin.md §4.4), pure and deterministic, and the runtime calls the same
function's output through the shadow swap (runtime-systems §1), so pack and server cannot disagree. Steps:

1. **Resolve and validate** each layer's component on the HUD surface (§4.3), its anchor and its channel (§5.2), its scale
   (§5.3) and its reads (§7.2). A failing layer is skipped with its diagnostic; the HUD still compiles.
2. **Flatten static raster** per channel (§5.3), reusing `GuiFlatten` and `GuiArt.cut` with a **HUD surface profile**: origin
   row `R` and the reach bound of §5.2 in place of the chest's title origin and art cap (the same parameterization gui.md §12.5.3
   made for container profiles).
3. **Allocate.** Every tile, frame slice and placement glyph takes a durable allocation of the new kind **`hud-glyph`**, keyed by
   `hud/<ns>/<hud>/<layer>/<frame or slice>` (so editing one layer never moves another's codepoint, D7). Placement glyphs are
   needed because a glyph resource's own provider carries its own ascent: the HUD emits a provider over the same texture file at
   the HUD's ascent. The block proposed is the Supplementary Private Use Area-A, `U+F0000`–`U+FFFFD` (65,534 codepoints, clear of
   the glyph range, the shift block and the GUI's `U+E200`–`U+F8FF`); row R verifies supplementary codepoints in bitmap
   providers on 1.21.4 and 26.x, and if they fail the block falls back to a BMP range chosen in row C. Exhaustion quarantines the
   HUD (`MTR-0112`).
4. **Emit** providers into the HUD font `<ns>:hud` and the offset fonts `<ns>:hud/<font>_y<A>`, `STYLED_ONLY` (never joining
   `minecraft:default`, runtime-systems §6, #198; the channel strings name their font keys). Every provider passes the emit-time
   invariant of gui.md §9.10 (`ascent ≤ height`, cell 1..256) or the HUD is quarantined (`MTR-0111`, an internal error), because
   a single bad provider makes the client drop the whole font file. HUD fonts are separate files from GUI fonts so one cannot
   erase the other.
5. **Pieces.** Each layer frame becomes a piece: `<shift:x>` + glyphs (white, transparent shadow) + `<shift:−advance−x>`,
   advance 0 overall. Static pieces are complete strings; dynamic ones are templates the renderer fills (§8.2).
6. **Bossbar sprite.** When any HUD uses the `bossbar` channel, the pack overrides the two sprites of the reserved boss-bar colour
   (`textures/gui/sprites/boss_bar/<color>_background.png` and `_progress.png`) with transparent images, so Materione's bar draws
   only its name. The colour is a server setting (Q2).

Goldens pin the emitted fonts, PNGs and pieces (§15).

---

## 6. Visibility

### 6.1 The condition

A HUD's viewer-set rule is `predicate` (`views.md` §3.5): its viewers are the players for whom its visibility holds. Visibility
for a player is the conjunction, evaluated on the player's owner thread (§9):

```
inWorld(worlds) ∧ hasPermission(permission) ∧ visible_if ∧ packContains(hud)
```

`worlds` and `permission` compile into the same condition tree as `visible_if` (logic.md §2.3); they are authoring sugar with no
runtime of their own. A `worlds` entry that names no loaded world is a warning at apply (`MTR-0117`), not an error: worlds load
late. Each layer's own `visible_if` then hides that layer only.

### 6.2 When it is re-evaluated

On join, world change, respawn, permission recalculation (the permission provider's change signal where it has one, logic.md
§4.5; otherwise the poll clock), a write to any variable in the HUD's reads, the poll clock for polled reads, a reload, and the
viewer's pack status (§8.4).

**Attach on visibility (`views.md` §5.3, acceptance case 1 of `views.md` §11.1).** A player's `HudViewer` exists from join even
when no HUD is visible to them: it is the HUD kind's **candidate** record. The shared reads index marks each HUD's `visible_if`,
`worlds` and `permission` reads as **visibility reads**; a write that matches one marks the candidate dirty (a `player` write:
that player's record; a `server` write: every online player's), whether or not that HUD is currently attached. The candidate's
wake re-evaluates the visibility of every HUD for that player, attaches the newly visible, detaches the newly hidden and renders
one composed output per channel. A candidate with nothing to re-evaluate costs memory, not wakes (§10).

### 6.3 Per-player show, hide and opt-out are content

There is no `hud.show` action. A HUD that a player can toggle reads a variable, and a rule or command sets it:

```yaml
visible_if: "${vars.player.rpg.hud_on}"     # declared: hud_on: {scope: player, type: boolean, default: true}
```

The toggle command is content (a rule, or a command once #329 lands). This is the view model of §3.1: the viewer set follows
state. Setting `hud_on` from false to true shows the HUD at the player's next eligible render with **no rejoin and no unrelated
poll**, through the candidate record of §6.2; and when this HUD and another are both visible, their pieces still concatenate into
one boss-bar string and one action-bar string (§8.2).

---

## 7. Bindings

### 7.1 Values are templates

Every value field (`text`, `value`, `max`, a `visible_if`) is a template in the logic engine's syntax (logic.md §2.5): a literal,
`${expression}`, or a PlaceholderAPI `%token%`. A HUD evaluates templates in a **viewer context**: `player` (the viewer), the
world, and `vars.player.*` / `vars.server.*` (logic.md §3.3). A HUD render also carries the common view subtree of `views.md` §3.7 with
`view.id` and `view.kind` = `hud` (no `view.args`, `view.item` or `view.key`: a HUD has no binding). There is no `local` or
`session` scope, because a HUD is not a run and not a window (consistent with `views.md` §3.4); a read of `vars.local.*` or `vars.session.*` skips the layer at load (`MTR-0116`). An undeclared persistent
variable is the existing `MTR-0063`. Keyed and item state (#333) are read through the same `vars.*` paths when they ship; a HUD
whose run has no stack reads an item variable's default, as #333 specifies.

### 7.2 Reads, and how a HUD knows to re-render

At compile time the HUD's templates and conditions are walked by the shared extraction of `views.md` §5.2, which records its
**reads**; the notified and polled classes below are `views.md` §5.1's:

| Read | Example | How a change reaches the HUD |
|---|---|---|
| Declared variable | `vars.player.rpg.mana` | **Notified.** The shared reads index and `StateWriteObserver` of `views.md` §5.2 and §5.3 (views row V1, #456): a write is matched by holder (a `player` write to that viewer, a `server` write to every viewer) and marks those viewers dirty. Keyed reads (#333 step 2) resolve their key per viewer at render and match on the resolved key (`views.md` §4.4, §5.3). There is no HUD-specific index. |
| Viewer facts the engine sees | `player.world`, permission | **Notified** by the events of §6.2. |
| World or entity state | `player.health`, `player.level` | **Polled** at `refresh`. |
| PlaceholderAPI token | `%vault_eco_balance%` | **Polled** at `refresh`; each distinct token is read once per viewer per poll, shared across HUDs. |

A HUD with only notified reads and no server-driven animation is never woken except to keep the action bar alive (§8.3).

**PlaceholderAPI on Folia.** Materione rejects generic PlaceholderAPI expansion on Folia (logic.md §18, decision 25): an
installed expansion is not known to be region-safe. A HUD layer that reads a `%token%` on Folia is hidden with `MTR-0113`
(rate-limited); Materione's own `%materione_*%` tokens follow their narrower rules there. On Paper, resolution is the existing
reflection path (logic.md §18, decision 23), on the viewer's thread.

---

## 8. Runtime

### 8.1 Shape

```
shadow swap ──► RuntimeHuds (compiled HUDs, pieces) + views ReadsIndex (StateKey → definitions)
                        │
variable store write ───┼──► reads index ──► mark viewers dirty ─┐
events (join, world…) ──┘                                         │
                                                                  ▼
           per viewer, on its owner thread (§9):  HudViewer
             ├─ visible HUDs (re-evaluated on dirty)
             ├─ render: concatenate pieces per channel (§8.2)
             ├─ diff with last sent ── unchanged ──► nothing
             └─ changed ──► ChannelWriter(bossbar | actionbar) ──► platform text layer
```

`plugin/hud/` holds `HudService` (the HUD kind: visibility evaluation and attach/detach against `views.md`'s `ViewRegistry`),
`HudViewer` (the HUD kind's viewer record: viewer-local state only) and the two `ChannelWriter`s. The viewer registry, the reads
index and the write observer are `plugin/view`'s (`views.md` rows V0, V1; #455, #456), shared with GUIs and dialogs.
`content/hud/` holds `HudType`, `HudDefinition` and the compiler; it is Bukkit-free so the compiler and goldens run headless
(gui.md §3's cut). The runtime never sees components, only compiled pieces (gui.md §3: "the runtime does not know about
components").

### 8.2 Rendering

For each channel, the viewer's string is the concatenation of the visible pieces of its visible HUDs in `priority` order (§5.3),
each dynamic piece filled from its templates (text laid out, bar slice count computed, frame chosen). Each channel string is
compared with the last one sent to that viewer; **only a changed string is sent** (the GUI's rule, gui.md §4 step 7).

- `bossbar`: one Adventure `BossBar` per viewer, colour = the reserved colour (§5.7 step 6), overlay `PROGRESS`, progress 0;
  shown once when the viewer first becomes eligible and **never removed while the player is online** (an empty name when no
  HUD is visible), so its position among other plugins' bars does not move (§11).
- `actionbar`: `sendActionBar` with the channel string, plus a keepalive resend every 40 ticks while any piece is visible, so
  the message never reaches its fade (§5.2). An empty string is not sent (it would blank another source's message).

### 8.3 Cadence

A viewer wakes only when something can have changed:

| Wake | Source |
|---|---|
| Dirty (notified read, §7.2) | The shared `ViewScheduler` of `views.md` §5.4: one dirty bit per viewer, at most one render per viewer per tick. |
| Poll | Every `refresh` ticks of the smallest `refresh` among its visible HUDs with polled reads. |
| Animation | The next server-driven frame change (§5.6). |
| Keepalive | 40 ticks after the last action-bar send, only while an action-bar piece is visible. |

The poll, animation and keepalive wakes are HUD-specific and run in the same per-viewer chain as the shared dirty wake. The
wake is a **chain of one-shots** scheduled through the gateway (`executeEntityLater`, plugin.md §2), the pattern `gui.tick`
uses (logic.md §21, decision 15): each link computes the next wake from the four sources and schedules one task, and stops when
nothing is visible. There is no global tick loop over players. Latency from a variable write to the next send is at most one tick.

### 8.4 Pack gating, join, quit, reload

- **A HUD is shown only to a player whose loaded pack contains it.** The runtime knows which pack version each player accepted
  (master §8.4, "who has which pack version"); each compiled HUD carries a fingerprint, and a viewer is eligible only when its
  accepted pack's manifest lists that fingerprint. Without this, a player who declined the pack, or holds the published pack
  while a candidate adds the HUD, would see missing-glyph boxes and a visible coloured boss bar. A player who declines the pack
  sees no HUD and no boss bar.
- **Candidate preview** (master §8.4): the editor's previewing player holds the candidate pack, so a HUD edited in the studio
  shows to that player only, through the same gate, with no special case.
- **Quit** or the entity scheduler's retirement callback tears the viewer down; nothing persists (HUD state is derived).
- **Reload** swaps `RuntimeHuds`; every viewer is marked dirty and re-renders against the new snapshot on its owner thread. A
  removed HUD's pieces disappear on that render.

---

## 9. Threading (D8)

| Work | Thread |
|---|---|
| Parse, validate, compile, PNG cut, font emission | Pipeline pool (plugin.md §2) |
| Reads index build, `RuntimeHuds` | Built off-thread, published by the shadow swap's volatile write |
| Visibility, template evaluation, PlaceholderAPI (Paper), rendering, diff | The viewer's owner thread (entity scheduler on Folia, main on Paper), through the gateway |
| Dirty marking from a variable write | The writer's thread: sets the viewer's dirty bit (atomic) and, only if no wake is pending, schedules one through the gateway. No viewer state is touched off its owner. |
| Sends | From the owner thread, through the platform text layer (`platform/`, plugin.md §6) |

No HUD code touches Bukkit scheduler APIs (plugin.md §2 rule); the ban test of build.md covers `plugin/hud/` like every module.
On Folia every per-viewer action runs on that player's region; a HUD never reads another player's state except through a
variable or keyed state, whose store already answers off-owner reads with snapshots (logic.md §18, *Player variables*).

---

## 10. Performance budget (proposed rows for plugin.md §9)

The variable-write rows (a write no view reads ≤ 250 ns; one matched viewer ≤ 2 µs; fan-out) are shared by every view kind and
live in `views.md` §5.7. The HUD's own rows:

| Operation | Budget | Test |
|---|---|---|
| Render pass, 200 viewers × 1 HUD × 12 layers, every layer dirty (template evaluation, layout, bar slices, diff), PlaceholderAPI and packet IO excluded | ≤ 2 ms per pass, median of three (≈ 10 µs per viewer) | `HudRenderPerfTest` |
| Idle viewer (notified reads only, no animation, nothing visible on the action bar) | 0 wakes | `HudCadenceTest` (virtual clock) |
| Packets per viewer | ≤ 1 boss-bar name update and ≤ 1 action-bar send per tick; an unchanged render sends nothing; keepalive 1 per 40 ticks | `HudCadenceTest` |
| PlaceholderAPI calls | Each distinct token at most once per viewer per `refresh` (default 5 ticks), shared across HUDs | `HudCadenceTest` with a counting resolver |
| Memory | ≤ 4 KiB per viewer (last-sent strings, poll cache, phase) | `HudViewerFootprintTest` |
| Compile, a 12-layer HUD with a 100-step bar | ≤ 50 ms warm; within the pack-build budget | golden test timing |

Honesty notes: PlaceholderAPI expansions cost what they cost; the budget bounds how often Materione asks, not what an expansion
spends. The client's cost of a long channel string is not measured in CI; row R records frame time on the probe for a 12-layer
HUD at GUI scale 4.

---

## 11. Coexisting with other plugins

| Conflict | v1 behaviour |
|---|---|
| **Another plugin's boss bars** | The client stacks boss bars in the order it received them, 19 px apart. Materione shows its bar at join (after the pack loads) and never removes it while the player is online (§8.2), so bars added later stack below. A bar another plugin shows earlier pushes the HUD's `top` layers down by 19 px each; documented, with a studio note. |
| **The reserved boss-bar colour** | The pack makes that colour's bar sprites transparent for every bar, so another plugin's bar of that colour loses its bar (its name still draws). The colour is a setting so an owner can pick one nothing else uses (Q2). |
| **Another plugin's action-bar messages** | Last writer wins on the client. Another plugin's message replaces the HUD's bottom layers until the HUD's next send (at most 40 ticks, the keepalive) or until it changes; the HUD replaces the other message on its next change. No interception in v1 (Q3). A setting `hud.actionbar: false` turns the channel off; `bottom` layers then hide, with `MTR-0113` once per HUD. |
| **Materione's own `actionbar` action** | Coordinated: when a rule sends an action-bar message to a viewer with visible action-bar pieces, the HUD yields that channel for the message's 60 ticks, then resumes. Both are Materione's, so this needs no interception. |
| **Vanilla action-bar messages** (bed, jukebox "Now playing", mount hints) | Same as another plugin; the HUD returns within 40 ticks. |
| **Other HUD plugins' shaders** | Materione's v1 HUD injects no shader. Its text-effects shader (runtime-systems §6) is unchanged by this design. |

---

## 12. Diagnostics

The next free code on main is `MTR-0106`. #9 (frame cycling) is in flight in parallel and may take the codes after it; to avoid
a collision this design takes **`MTR-0110` to `MTR-0117`** and leaves `MTR-0106` to `MTR-0109` to #9. Row Q moves them into
plugin.md §5's catalog; their meaning is API once shipped. The views design (`views.md` §10) numbers its own codes after this
block; nothing here changes for it.

| Code | Severity | When | Effect and recovery |
|---|---|---|---|
| `MTR-0110` | error | A HUD layer is invalid: unknown `type`, a component not supported on a HUD surface (`slots`, `item`, `button`, `list`), an unknown anchor, a non-integer or out-of-range `scale`, `cell × scale > 256`, a missing texture or glyph, a `bar` without `fill`. Also an authored `behavior:` on a HUD (kept in the file, not run). | That layer is skipped; the rest of the HUD compiles. Fix the field; the target points at it. |
| `MTR-0111` | error | Internal: an emitted HUD provider would break the client font invariant (`ascent > height`, cell outside 1..256). | The HUD is quarantined (`MTR-0010` names it) so the shared HUD font file stays loadable. Report it; it is a compiler defect. |
| `MTR-0112` | error | The `hud-glyph` codepoint block is exhausted. | The HUD is quarantined. Reduce bar `steps` or frames, or delete unused HUDs and run the audited rebuild (master §6.3). |
| `MTR-0113` | warning | At runtime a layer's template or condition fails for a viewer: an expression error, a non-number bar value, text outside the measured set, a `%token%` on Folia, the action-bar channel turned off. | That layer is hidden for that viewer; others render. Rate-limited: once per HUD and layer per minute. |
| `MTR-0114` | warning | A layer's box extends past ±213 px from the centre column, or above the reach bound of its channel. | Compiles; players with narrow windows do not see the part past the edge. The studio draws the guide. |
| `MTR-0115` | info | A server-driven animated glyph on a HUD runs faster than 10 fps. | Clamped to 10 fps on HUDs only. |
| `MTR-0116` | error | A HUD template reads `vars.local.*` or `vars.session.*`. | That layer is skipped. HUDs read `player`, `server` and (later) keyed state. |
| `MTR-0117` | warning | `worlds:` names a world that is not loaded at apply. | Compiles; the HUD shows when that world exists. |

---

## 13. The editor studio (web.md §6)

The HUD studio is a sibling of the GUI studio and reuses its parts (web.md §6, the GUI studio ops of web.md §4.2):

- **Screen canvas.** A mock vanilla screen (crosshair, hotbar, hearts and hunger outlines, the action-bar row) at a selectable
  window size and GUI scale, from gui.md §9.12's table, with the anchors drawn as handles and the ±213 px safe guide (§5.2). A
  layer drags in integer pixels relative to its anchor; changing the anchor keeps the layer on screen by recomputing `x`/`y`.
- **Layers panel and inspector**, generated from the `hud` type's and each component's self-description (§4.2, §4.3); the
  component picker shows only HUD-capable components.
- **Compiled preview.** The canvas draws the compiled layout (flattened static art, pieces, bar slices) through a TypeScript
  mirror of the HUD layout arithmetic, pinned to the Java compiler by **shared vectors** in `fixtures/hud/`, as the GUI raster
  is (gui.md §3). The preview shows what the player gets, including the z-order badges of §5.3.
- **Sample values.** A side panel holds sample values for the HUD's reads (a slider per number variable, a text box per
  `%token%`) so a bar can be scrubbed. Samples are transient studio state, never saved into the file or anywhere else (D1).
- **Preview in game.** With a connected player, saving produces a candidate pack sent to that player only (master §8.4); the HUD
  then renders for them from live state through the pack gate of §8.4. The studio reuses the GUI studio's connected-player
  status op and session-capability gating (web.md §4.2), under a new `hud` capability; no new endpoint (D11).
- **Diagnostics** of §12 render inline on the layer they target (plugin.md §5 targets).

## 14. Code mode (D13)

Nothing is written for the SDK. `hud` is a registered content type with a self-description, and its layers are
`gui-components` entries that declare the HUD surface, so the generator (codemode.md §3.8) emits `p.huds.create(id, {...})`,
typed layer builders for HUD-capable components only, and the anchor enum, with no SDK code (codemode.md §3.5). T20's line in
agent-benchmark.md becomes valid once rows S and C ship; its `anchor: "hotbar_right"` is the §5.2 anchor of the same name.
Changing or deleting an existing HUD waits for the owner's approval like any other object (master §3.2).

---

## 15. Test plan

| Layer | What | Where |
|---|---|---|
| Golden YAML | Load → untouched save byte-identical for every fixture HUD, unknown fields at every level included (master §5.6). | `fixtures/hud/*.yml` |
| Golden pack | Emitted `<ns>:hud` font, offset fonts, PNG tiles and slices (bytes and decoded pixels, as the GUI goldens), the boss-bar sprite overrides, and the compiled pieces. | `fixtures/hud/expected/` |
| Shared vectors | Layout arithmetic (anchor → shift and ascent, bar slices, text alignment) consumed by the Java compiler and the TS studio mirror. | `fixtures/hud/vectors.json` |
| Allocation | Editing one layer keeps every other layer's codepoints; deleting a HUD tombstones its allocations (D7). | unit |
| Invariants | Property test: no emitted provider breaks `ascent ≤ height` or the cell bound for any anchor, offset and scale in range. | unit (property) |
| Runtime | Reads index, dirty coalescing, poll and keepalive cadence, pack gate, actionbar yield, Folia owner discipline (a virtual clock and a recording gateway). | `plugin` unit |
| Perf | The rows of §10. | `HudRenderPerfTest` and friends |
| Harness IT | On the 1.21.4 headless Paper: a fake player with the pack accepted gets a boss bar with the expected name component and action-bar packets; a `set_var` changes the next packet; leaving the listed world removes the pieces; declining the pack gets nothing. | `PaperHudIT` |
| Probe | Row R's verifications and the visual exit check on the 26.x client (docs/probe.md). | `scripts/probe/` |
| QA catalog | `qa/catalog/hud.mjs` (qa/README.md): auto entries for visibility per world and permission, the pack gate, `set_var` → new packet, Folia `%token%` refusal; **visual** entries with screenshots for the exit criterion (a mana bar plus an animated icon tracking a PlaceholderAPI value, visible only in configured worlds), anchor placement at GUI scales 2 and 4, bar fill at 0, 50 and 100 percent, text alignment. Blessed on main. | `qa/catalog/hud.mjs` |

---

## 16. Implementation rows

Model policy (agent delegation, as in gui-input-durability.md §12): senior and cross-cutting work on Sonnet at high effort,
regular implementation on Haiku.

| Row | Title | Scope | Depends on | Model |
|---|---|---|---|---|
| **R** | Client probes | On the probe loop (26.x) and the 1.21.4 harness: boss-bar and action-bar draw rows and centring with a zero-advance string; `shadow_color` transparent on both channels; supplementary-plane codepoints in bitmap providers; draw order of different-texture glyphs in one string; channel order; action-bar fade timing; the text-display rejection of §5.1; frame time of a 12-layer HUD. Output: §5 facts marked verified with screenshots, or this doc revised. | none | Sonnet, high |
| **S** | Schema | `hud` content type (`content/hud/`), parse, self-description, lossless round-trip goldens; `surfaces` in component self-descriptions; `glyph:` on `image`; `align` on `text`; the `bar` component's schema; level 1–3 validation and `MTR-0110`, `MTR-0116`, `MTR-0117`. | none | Haiku |
| **C** | Compiler | HUD surface profile on `GuiFlatten`/`GuiArt.cut`; anchors → shift and ascent; `hud-glyph` allocation; `<ns>:hud` and offset fonts; bar slicing and segments; placement glyphs; boss-bar sprite overrides; emit invariant (`MTR-0111`, `MTR-0112`, `MTR-0114`); pack goldens and shared vectors. | R (geometry), S | Sonnet, high |
| **V** | View runtime | `HudService` and `HudViewer` as the HUD view kind on `views.md`'s `ViewRegistry`, reads index and `ViewScheduler`; the HUD wakes (poll, animation, keepalive), channel writers, actionbar yield, pack gate, join/quit/reload, Folia; `MTR-0113`; the perf rows of §10; `PaperHudIT`. Implements `views.md` §3.2's contract (§3.1). | C, views V0 (#455), V1 (#456) | Sonnet, high |
| **A** | Animation | Consume #9's provider (§5.6): client-driven emit once, server-driven frame wakes, clamp and `MTR-0115`. | V, #9 | Haiku |
| **W** | Studio | The HUD studio of §13 and its `webui` specs; TS layout mirror on the shared vectors; the `hud` session capability. | S, C (vectors) | Sonnet, high (web UI) |
| **K** | Benchmark check | Regenerate the SDK (including the view facet on `hud`, `views.md` §9); turn T20's HUD line green in agent-benchmark.md's harness; no SDK code. | S | Haiku |
| **Q** | Close-out | `qa/catalog/hud.mjs`; plugin.md §5 codes, §9 rows; runtime-systems §7 "as built"; glossary; roadmap M6 note; CHANGELOG. | V, W, A | Haiku |
| **X** (later, owner-gated, Q1) | Shader anchoring | A HUD signal in the existing text vertex shader ladder (runtime-systems §6) that moves marked glyphs to edge and centre anchors from the projection matrix; moves every layer onto the boss bar and frees the action bar; shader-free clients fall back to v1 anchors. Its own design note first. | V | Sonnet, high |

---

## 17. Owner questions

| # | Question | Options | Recommendation |
|---|---|---|---|
| **Q1** | Shader-free v1, or shader-first? | (a) v1 shader-free: `top` via the boss bar, `bottom` and hotbar anchors via the action bar, centre-relative only; edge anchors in row X. (b) Shader-first: every layer on the boss bar, any anchor, no action-bar use, but only on clients inside the verified shader ladder (46–88). | (a). Works on every client the floor admits and needs no shader; (b) is row X. |
| **Q2** | Which boss-bar colour does the HUD reserve (its bar sprites become transparent for every plugin)? | A fixed colour, or a server setting with a default. | A setting `hud.bossbar_color`; the default is the owner's call (this doc writes `yellow` as a placeholder in examples only). |
| **Q3** | Action-bar coexistence with other plugins | (a) Last writer wins, keepalive ≤ 40 ticks, an off switch (§11). (b) Packet-level merge of other plugins' action-bar text into the HUD line, which needs a packet interceptor (a new dependency or a pinned transport like the GUI's rich titles, logic.md §6.4). | (a) for v1; (b) only if users report it, and row X removes the action bar from the HUD altogether. |
| **Q4** | Sequencing with #335 (views over state) | (a) The HUD lands first and implements the minimal view contract privately; #335 lifts it. (b) #335's design document first, then the HUD on it. | **Answered (owner, 2026-11-19): (b).** The owner chose #335's document first (dialogs design, PR #451, Q7), for both kinds; it is `views.md` (PR #454), and §3.1 implements its contract. |
| **Q5** | One component registry for GUIs and HUDs? | (a) HUD layers are `gui-components` entries that declare the HUD surface (§4.3). (b) A separate `hud-elements` registry. | (a): one view-component registry, per D12 and #335; the registry's name (`gui-components`) may later be generalized without changing files. `views.md` endorses (a) as the D12 reading of one view model. |
| **Q6** | Master §8.1 names "HUD thresholds" (event) and "set HUD value" (action); logic.md §11 left `hud.threshold` and `hud.set` seams. | (a) Read them as `set_var` and a future generic variable-change trigger (§3.3); no HUD-specific verbs; amend master §8.1's wording when the HUD ships. (b) Ship `hud.set`/`hud.threshold` as presets over those primitives. | (a). |
| **Q7** | The screen-centre (crosshair) anchor and the title channel | (a) Out of v1; centre comes with row X. (b) Use the subtitle channel at half-scale fonts for a centre anchor now, accepting conflicts with every title plugin. | (a). |

Not asked because already decided elsewhere: PlaceholderAPI on Folia stays refused (logic.md §18, decision 25), so on Folia the
exit criterion's PlaceholderAPI binding is served by a declared variable instead. None of the master §14 open questions is
touched: HUD state needs no cross-server adapter (§14.2; it is derived per viewer), Bedrock is out (§14.3), and nothing here
depends on the license (§14.1). No Oraxen or ItemsAdder code or configuration structure is used; the incumbents were read as
behaviour only (feature-parity §3.8).

---

## 18. What this PR changes elsewhere

- [`README.md`](README.md): the index row for this document.
- [`runtime-systems.md`](runtime-systems.md) §7: a pointer here and the correction of §5.1 (text, not text displays).
- [`logic.md`](logic.md) §11: the HUD seam row points at §3.3.
- [`../glossary.md`](../glossary.md): the HUD entry.
