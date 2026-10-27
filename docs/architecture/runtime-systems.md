# Runtime Systems Architecture — Technical

> **Traceability.** Implements the content model (master §5) and D2–D8 for each built-in
> content type. Parent: [`architecture.md`](../architecture.md) · Companions:
> [`plugin.md`](plugin.md) · [`web.md`](web.md).
>
> **Scope:** how each content type **lives at runtime** — data model, data flow from
> definition to gameplay, persistence, failure modes, and interop. Compile internals are in
> `plugin.md` §4; the pack *format* details belong to a future pack-compiler subsystem doc.

---

## 1. Shared runtime contract (read first)

Every content type implements the same shape — this is what makes D2 (one content graph) and
D4 (one ECA engine) real:

```
ContentType (registry entry)
 ├─ Parser         : file document → typed definition          (pipeline, off-thread)
 ├─ Definition     : immutable, validated data (appearance+behavior+metadata facets)
 ├─ CompilerHooks  : definition → pack/datapack artifacts      (pure, deterministic)
 ├─ RuntimeType    : addressable runtime form (what players encounter)
 ├─ EventBinding   : which game events this type listens to    (thread-declared)
 ├─ Persistor      : how placed/owned instances survive        (chunk PDC / player PDC / none)
 └─ SelfDescription: editor form schema                        (plugin.md §3)
```

Cross-cutting runtime rules:

- **Runtime data is immutable and swapped atomically (shadow-swap).** A reload builds a new
  set of RuntimeTypes; the swap is one volatile reference exchange (plugin.md §4.5). Handlers
  resolve that reference at event-entry, so an in-flight event completes against the old
  definition — half-reloaded behavior is never observable. On Folia, "atomic" here means the
  reference swap itself (a single volatile write is region-thread safe); per-region in-flight
  work simply finishes on the old snapshot, and the post-swap reconciliation pass (break
  resync, recipe resync) is scheduled per-region through the thread gateway (plugin.md §2).
- **Every placed/owned instance stores its resource id** (`materione:id`) in PDC; runtime
  resolves id → current RuntimeType at use. A placement whose resource was removed resolves to
  **Orphan** (kept, flagged, admin-resolved) — never air, never a crash.
- **All behavior flows through the ECA engine** (§2). Types contribute *events*; mechanics and
  actions are shared vocabulary.
- **Everything is diagnosed.** Each system's failure modes assume the diagnostics bus
  (plugin.md §5) catches and reports without killing the event chain.

---

## 2. ECA engine (behavior backbone — D4)

### 2.1 Model

```
Rule = { id, on: EventSelector, if: [Conditions], do: [Actions], opts }
EventSelector = { type, subtypes?, scope? }      // e.g. click:right on item
Condition      = expression (universal engine) | registered predicate
Action         = registry entry + params         // shared across all content types
```

- **Event taxonomy** (normalized across types): `click` (left/right/shift, on item/block/
  furniture/air), `break`, `place`, `interact`, `equip`, `unequip`, `drop`, `pickup`,
  `inv_click`, `death`, `consume`, `tick` (timers), `menu.click`, `hud.threshold`,
  `dialog.submit`, `custom` (addon-defined). A menu button click and an item right-click are
  the same `click` event carrying different context fields.
- **Condition evaluation**: one universal expression engine (settled decision). Expressions
  parse once at load into an AST; runtime evaluation is tree-walk with typed values;
  compile-to-lambda is a later optimization, not a design change. PlaceholderAPI resolves
  inside string interpolations.
- **Action execution**: actions run on the thread the event arrived on; each action declares
  sync-safe vs must-defer, and world-mutating actions go through the thread gateway
  (plugin.md §2). One action failing → diagnostic + skip; the rest of the rule chain
  continues.
- **Cooldowns & charges** are rule options, not separate mechanics — any action set can carry
  them (per-player, or per-item-instance via PDC).

### 2.2 Context object

Every event delivers one **context record**: `{player?, location?, item?, block?, furniture?,
menu?, dialog?, vars}`. Actions read context; expressions read context variables
(`player.xp`, `item.durability`, `server.online`). The context schema is versioned; addon
actions get the same core plus type-specific fields.

### 2.3 Built-in mechanics = canned ECA

`soulbound`, `backpack`, `auto_smelt`, `lifeleech`, `aura`, `harvesting`, `bigmining`,
`watering`, `bedrockbreak`, `bottledxp`, `spear_lunge`, `thor`, `fireball`, `energyblast`,
`witherskull`, `knockback_strike`, `bleeding` … are **parameterized ECA bundles registered
through the same mechanic registry addons use**. A mechanic needing packet-level or tick-level
work the ECA engine can't express may be a **native mechanic** (Java implementation) — same
registry, same self-description; the ECA-vs-native distinction is invisible to authors.

**Executable extension contract:** `Mechanic` is a factory with pure per-item
parsing and one managed runtime per registration, not a metadata marker.
`MechanicInstance` carries typed parameters, shared ECA contributions and ordered
item-building hooks. The host owns native listeners and cleanup; lookups are
namespaced, snapshot-based and registration-scoped. All five existing built-ins
use the same public `MaterioneAPI.mechanics()` path as addons.
[`mechanics.md`](mechanics.md) specifies the lifecycle and extension example.

---

## 3. Items

### 3.1 Model

```
ItemDefinition
 ├─ identity: materione:id
 ├─ appearance: item-model ref | generated model spec | hand-authored 3D model ref
 │              (1.21.4+ ItemModel JSON via the platform layer; legacy custom_model_data
 │               only on the importer path for migrated content)
 ├─ model_tree: declarative ItemModel tree (see below) — reference / range_dispatch /
 │              select / condition / composite / empty nodes
 ├─ components: vanilla data components (version-aware, platform-gated)
 ├─ behavior: ECA rules + mechanics list
 └─ metadata: name, lore, category, permission, tags
```

**The ItemModel tree builder is a first-class authoring surface** (parity §3.1 "don't ship on
`custom_model_data` alone" — Nexo's deepest item). A definition may declare a full model tree
in config: time-based models (`local_time` condition), GUI-vs-world swapping
(`display_context`), dye-state rendering (`range_dispatch` over a dye component), throwing
vs. held tridents (`condition` on thrown state). The compiler emits the tree as
`assets/<ns>/items/<id>.json`; no author ever writes that JSON by hand (they may, as raw
`model:` refs — but the tree builder is the supported path).

### 3.2 Runtime flow

```
definition ──compile──► item model JSON + textures → pack
              └─apply──► ItemStack factory:
                          material + item_model component + PDC tag {materione:id}

resolve (hot path): ItemStack → cached PDC tag read → RuntimeItem   (~1 µs map hit;
                    first touch of a stack pays a PDC read, ~10–50 µs, then cached
                    against the stack's identity — see plugin.md §9 budgets)
```

- **Identity is a PDC tag**, not a model predicate — the item behaves correctly in vanilla
  contexts (hoppers, anvils, crafting grids) where predicates never run.
- **Item auto-updater**: on join and on throttled inventory events, stacks with a Materione
  tag are compared against the current definition hash; changed → rebuilt preserving volatile
  state (durability, in-game enchantments, custom counters). Same updater heals items after
  pack migration.
- **Templates/inheritance**: `template` refs resolve at graph build; a template is a partial
  definition with overridable fields; diamond inheritance → validation error. *As built:* one
  resolver, `core.pipeline.TemplateResolver` (single parent, shallow top-level merge, child wins;
  `content.Templates` is its facade), serves both parsing and the editor's provenance view, which
  reports per key which template supplies it (web.md §4.2).

### 3.3 Failure modes

Unresolved model ref → quarantine (works server-side, renders as base material); definition
hash unknown (removed pack) → renders vanilla + flagged for admin; unsupported component →
platform-gate diagnostic ("needs 1.21.x").

**As shipped (M1):** `RuntimeItem`/`RuntimeRegistries` (immutable maps, definition hash =
SHA-256 over identity+material+model+name+lore), the `ContentRuntime` shadow-swap with
pre-apply diff and rollback, `MaterioneStacks` (PDC `{materione:id}` + 1.21.4
`item_model` component, volatile-state-preserving refresh), the join-time auto-updater
(throttled inventory scans arrive with M2's mechanics work), template inheritance with
cycle/diamond rejection, and the auto-model + hand-authored-model compile paths. The
declarative `model_tree` surface stayed parsed-but-inert; the `components:` field warns
at validate since M4's QA round (MTR-0048, #103) — the version-aware pass-through is
the M7 item — while `behavior` shipped with M2's ECA engine;
`range_dispatch`/`select`/`condition` authoring waits for the model tree.

**As shipped (M2):** `behavior:` rules compile at apply through the ECA engine
(invalid rules/expressions skip with `MTR-0021/0022`, siblings stay live) and resolve from
the shadow-swapped registries at event entry; the `mechanics:` list resolves against the
public mechanics registry (natives: commands, soulbound, food, durability, backpack —
unknown entries skip with `MTR-0027`). Food rides a build-time vanilla `FoodComponent`
(1.21.4 has no Consumable API); durability/soulbound/backpack state persists in stack or
player PDC. The auto-updater now scans on throttled inventory events (one scan per player
per second, put-before-check gate) in addition to join. `model_tree` stays parsed-but-inert
(M7). Authored names/lore render non-italic by default — vanilla italicizes `custom_name`
and lore unless a component opts out; the `ItemText` wrapper opts out at the root while an
author's `&o` still wins on its own segments.

---

## 4. Custom blocks

### 4.1 Techniques & allocation

| Technique | Capacity | Use |
|---|---|---|
| NoteBlock states | ~800 | general solid blocks |
| StringBlock (tripwire) | ~127 | foliage, tall plants, waterloggable, saplings |
| ChorusBlock | ~63 | transparent blocks (glass-like, leaves-like) |
| Technical blocks (mushroom-stem class) | small | invisible/technical bases |

Allocation is owned by the compiler's allocation table (plugin.md §4.4) — append-only, stable
across reloads; runtime never allocates.

### 4.2 Runtime flow

```
place:    interact with custom-block item → place host block (note block etc.)
          → write chunk PDC {materione:block=id, variant} at position
          → client renders the pack model mapped to that blockstate
break:    BlockBreakEvent → PDC read → custom drops (loot table) → remove PDC
          → cancel vanilla drop, play break sound/particles
interact/step/punch: PDC read → ECA events
piston/explosion: custom blast resistance honored; move policy per definition
                 (default: block move, keep PDC)
```

**Placement items (the itemblock seam).** Every live block or furniture is
grabbable without authoring a separate item. The placement link resolves in
this order: the definition's explicit `item:` reference → a live item
resource with the *same id* (implicit placement facet, §13) → a **synthetic
placement item** generated at apply: same id as the block/furniture, the
host's item form for blocks when vanilla has one (note block, chorus plant,
shaped hosts — placing rides `BlockPlaceEvent`), string for the tripwire
technique, paper for item-less hosts and furniture, the block/furniture's
model, flagged `synthetic` in the items registry (browsers
skip it as a standalone row). The compile stage emits the matching
`assets/<ns>/items/<id>.json` only when no real item owns the id. A
placement item whose material is not placeable (an explicit `item:` link to
a paper item) places through a `PlayerInteractEvent` fallback against the
clicked face — vanilla `BlockPlaceEvent` never fires for non-block items.

- **Chunk PDC is the truth** for placed blocks (CustomBlockData); the index in `generated/`
  accelerates lookups and is rebuildable by chunk scan (self-healing).
- **Host-block physics suppressed** (Paper) so hijacked note blocks don't beep and tripwire
  doesn't cut.
- **Host use is denied; placement is ours**: CraftBukkit short-circuits the whole
  interaction once a `PlayerInteractEvent`'s block result is DENY — vanilla item use
  (placement) is unreachable against our blocks. So a right-click on an identified (or
  chunk-recorded) position that carries one of our placement items places that block
  against the clicked face through the runtime itself (the §13 contract, covering clicks
  on our own blocks; slab stacking mirrored). A held vanilla block is placed by the
  runtime too, mirroring vanilla placement for the common shapes (slab/stairs/trapdoor
  halves from the hit point, log axes from the clicked face, directional fronts toward
  the placer, waterlogging, support and entity checks) and announcing through a real
  `BlockPlaceEvent` so protection plugins keep their say; multi-cell structures (doors,
  beds) stay sneak-only, where vanilla places them itself and never touches the host.
  Every other right-click is fully cancelled — the host's own behavior (note tuning/play,
  any interactive technical host) never runs. Non-placement clicks fire ECA `interact`
  rules once, on the main hand. Left clicks stay vanilla; the break pipeline owns
  teardown. Quarantined positions (record present, drifted state) are denied too but
  fire nothing.
- **Reload migration**: definition changed → placed instances re-render through the new model
  (same blockstate mapping, no world edit); definition removed → Orphan policy (keep-flagged
  default).
- **Shaped blocks** (stairs/slabs/doors/trapdoors/grates): waxed-copper technique
  (ShapedBlock-style) — one blockstate family per shape family, allocated like any block;
  placement/interaction logic (half positions, openable doors) lives in the block runtime,
  not per-author config.

### 4.3 Failure modes

Allocation exhaustion per technique → build diagnostic with per-technique pressure; unknown
PDC id after pack removal → Orphan rendering (vanilla base + flag) with an admin sweep
command; corrupt PDC on chunk load → quarantine that block, diagnostic, world unaffected.

**As shipped (M2):** all four technique families plus shaped stairs/slabs (waxed-cut-copper,
PDC-identified per decision — no spare state property; doors/trapdoors/grates wait for M7).
Reads are **state-as-identity**: the host blockstate decodes to a slot int answered by a
precomputed per-host array, so vanilla positions cost zero PDC accesses; the decoded
candidate is then confirmed against the chunk-PDC record and a mismatch is quarantined
(`MTR-0024`) instead of trusted — the accelerator is disposable, the record wins. The
placement index is rebuilt from chunk PDC on every chunk load plus one boot pass over
already-loaded chunks (world-copy survival). Physics suppression probes Paper's
`disable-*-updates` flags with event-based fallback suppressors (`MTR-0025`). One shared
removal pipeline serves player break, explosions, pistons (move-policy aware) and falling
blocks; orphans are keep-flagged with targeted client resync via `dev sweep`
(`MTR-0026`). Reads stay quarantined on state/PDC drift (`MTR-0024`); `dev sweep --fix`
is the explicit repair — it rewrites the drifted host state from the chunk record, so the
position renders and identifies as its recorded definition again.
Dev commands `place/remove/inspect/sweep` double as the harness driver.
Deviation: stringblocks ship dry — tripwire has no waterlogged state at the API floor.

---

## 5. Furniture

### 5.1 Model

Display-entity-first (master §3.6): one **ItemDisplay** (the model) + optional **Interaction**
entities (hitboxes) + optional barrier blocks (collision) + optional **TextDisplay** (labels)
+ seat entities with passenger tracking.

```
FurnitureDefinition
 ├─ model: item model ref (+ per-state models)
 ├─ hitboxes: [interaction|barrier|shulker|ghast]      (platform-gated)
 ├─ seats: [{offset, multi?}]
 ├─ lights: toggleable light level
 ├─ storage: {type, size}
 ├─ states: ordered, cycle trigger, per-state conditions
 ├─ placements: per-surface behavior (floor/wall/roof)
 ├─ evolution: timed stages → new furniture or block
 └─ behavior: ECA
```

### 5.2 Runtime flow

```
place:   item use → raytrace surface → spawn entity cluster (ItemDisplay + hitboxes +
         barriers + seats) at anchor → write chunk PDC {materione:furniture=id,
         entity-uuids, rotation, state} → apply initial placement rules (surface, rotation
         snap, waterlog checks)
interact: hitbox InteractionEntity → ECA (click, sit, open storage, cycle state)
remove:  break hitbox or ECA → kill entity cluster → drop item → clear PDC
tick:    seats/passenger validation (dismount cleanup), evolution timers, animation frames
reload:  PDC id → current definition → respawn entity cluster if model changed; removed →
         Orphan policy (entities kept, flagged)
```

Geometry conventions: the anchor cell spans `[y, y+1]`; the root ItemDisplay
spawns at the **block center** `(x.5, y.5, z.5)` because the client renders a
display entity's model centered on the entity position in every axis —
members (hitboxes, seats, cells) use the bottom-of-cell convention, and the
TextDisplay label sits at `y + 1.25`, clearing the model top by 0.25.

- **Entity cluster coherence** is the furniture runtime's core job: UUIDs of every member
  entity are tracked in the placement record; chunk load replays cluster creation if entities
  were lost (server crash mid-place, world backup restore) — furniture is **reconstructible
  from PDC alone**.
- **Seats** track passengers; on dismount, death, or furniture removal, passengers are
  dismounted safely (teleport to safe adjacent block, never into blocks).
- **States** cycle on interact (or condition), swapping the ItemDisplay's item model — a
  furniture-level concern, distinct from ECA (though a state change also fires an ECA event).
- **Evolution** timers are stored as absolute timestamps in PDC (pause-safe across restarts).

### 5.3 Failure modes

Missing member entity on chunk load → respawn from placement record; unknown id → Orphan
(entities kept, flagged); seat passenger desync (player logged out seated) → cleanup on join;
evolution target missing → hold at stage, diagnostic.

**As shipped (M2):** the parity core — ItemDisplay base + Interaction and barrier hitboxes
(barriers waterlog-aware), plain-entity seats via the passenger API (no NMS seat entity),
LIGHT cells, TextDisplay labels, persistent storage (`InventoryHolder` contents carried in
the root record), 90°-step rotation snap, floor-only placing with a space pre-check, and
timed evolution evaluated lazily from absolute timestamps in the root PDC — never a global
scan timer. The root record lives in entity/chunk PDC so a cluster is reconstructible from
PDC alone; reload rebuilds visuals only and never rewrites stored ids. States cycling,
connectables, wall/roof placements, beds and sliding doors wait for M7 (§3.6). Furniture
definitions participate in the content graph (PLACED_BY_ITEM / FURNITURE_STAGE edges) and
`dev place/remove/inspect` fall through to furniture when no block occupies the position
(harness markers `MATERIONE-DEV-FURNITURE-*`).

---

## 6. Glyphs & fonts

- Compile-time concern mostly (font JSON + textures into the pack), plus runtime text
  handling: glyph ids usable in any text the plugin renders (chat, lore, menus, HUDs, signs,
  books) through a text pipeline that resolves `:glyph_id:` and `<shift:N>` tags.
- Animated glyphs (sprite sheets) are pure client-side via the pack; the runtime only ensures
  the codepoint allocation is stable (allocation table, D7).
- Emoji mode: a chat interceptor + tab-completion + permission per glyph — a thin ECA-ish
  feature over the registry (usage permission = metadata on the glyph).
- **GLSL text effects** (rainbow/wave/shake/pulse, 1.21.4+): shipped as part of the font
  system — effect definitions are resources declaring shader fragments and slot ids; the
  compiler injects them into the pack's `rendertype_text` shader set. Parity table stakes
  (Oraxen and Nexo both have this); the runtime's only job is stable id allocation.

**As built (M4):** glyphs compile to bitmap providers merged per font key into one
`assets/<ns>/font/<name>.json` (the pack glyph font `<ns>:glyphs` for glyphs without an
explicit `font:`), and every generated font is wired into the client's default font:
`assets/minecraft/font/default.json` gains one `type:reference` provider per generated font
(the 1.21.2+ reference provider — the client renders unstyled text only through the default
font, so without the references codepoints show as tofu boxes). The shift font's `type:space`
provider (power-of-two advances on U+E10x/E11x — Nexo's technique) exists once at
`assets/materione/font/shift.json` and is referenced from the default font and every generated
font, so `<glyph:id>`, `<g:id>`, `<shift:N>` and `:placeholder:` compose inside any materione
font without switching fonts. Default-font glyphs go out unstyled; explicit `font:` glyphs are
styled with their font key.
Codepoints are durable consecutive runs from the allocation table (kind `glyph-codepoint`,
origin 42000, `AllocationTable.range`) — never written back into authoring YAML, unlike the
incumbents; an explicit `char` field therefore does not exist, and the editor shows the
allocated codepoint as server-computed browse metadata. Shapes: single texture, `rows`/`columns`
sheets (chars row-major), `frames` animations (one codepoint per frame; frame cycling surfaces
arrive with M6), `reference` aliases (pure alias — no allocation, resolved through the target
at text time). The runtime keeps a `RuntimeGlyphs` view in the shadow swap; the text pipeline
(`platform/text/GlyphText`) resolves markers in item text, chat (decorate-time, previews
included), signs, books and anvil renames, forces white unless `colorable` (the incumbent
color-bleed trick), applies optional `shadow_color`, and leaves denied or unresolvable markers
literal. Chat resolution checks the viewer's glyph permission and the live registry —
placeholders resolve only for emoji glyphs.
*(Since issue #151, authored text (names, lore, labels, titles) goes through
`platform/text/MaterioneText`, plugin §6.1: the same markers resolve inside a MiniMessage parse,
so they can sit inside a color or gradient. Effect spans may then nest, and an unclosed span runs
to the end of the text instead of staying literal. Player-typed text keeps the marker-only path
described here.)*

**Text effects (as of the shader-injection slice).** `effects/*.yml` compile to a durable
signal color (allocation kind `effect-signal`: the band `#FF00FF` downward, blue channel
254..1 — colors no chat style produces, the Nexo magic-color trade-off) and a
`RuntimeEffects` runtime view. The text pipeline resolves paired spans —
`<effect:pack:id>…</effect>` (alias `<e:…>`; no nesting) — coloring the span's plain runs
with the signal; glyph codepoints inside keep their forced white, explicit `&`-codes keep
theirs. The pack injects the client's text **vertex** shader per version-matched overlay
(`materione_1_21_4` formats 46–62, `materione_1_21_6` 63–83,
`materione_26_1` 84–87, `materione_26_2` 88). The 26.1 `rendertype_text` and 26.2 `text`
entry points each import an include baked for their own format. The replacement outputs
the vanilla varyings, so the client's own fragment shader (alpha cutout, fog) keeps working
unmodified; only the vertex shader is replaced (plus the program `.json` through 1.21.8 with
the `GameTime` uniform — since 1.21.6 `GameTime` rides the Globals UBO). The generated
`materione_text_effects.glsl` include bakes one branch per effect keyed on its signal:
wave/shake displace the vertex pre-transform, rainbow/pulse recolor post-lightmap (authored
`colors` stops become a mix-chain; rainbow falls back to a cosine palette).

Overlay entries carry both metadata schemas with matching ranges because current clients
validate `formats` against `min_format`/`max_format`, while pre-25w31a clients require
`formats`. The 26.1 entry advertises 64–87 in both declarations, and the 26.2 entry 64–88,
while their verified client ranges remain 84–87 and 88 respectively. Both broad entries are
emitted before the more-specific 1.21.6 entry, which shadows `rendertype_text` and its include
for formats 64–83; the 1.21.4 entry remains 46–62. The extra 26.2 `text` shader path is not
referenced by those earlier clients' `rendertype_text` pipelines. Shaders never ship in the
base layer — a client matching no effective rung gets a shader-free pack — and pack.mcmeta
carries the `overlays` ladder plus `sodium.ignored_shaders`.
`MTR-0044` survives as the honest conditional: when the server's detected client format is
outside the verified 46–88 ladder, the effects compile but a WARN says they will not animate.
`MTR-0047` fires when the 254-slot signal band is exhausted (signals are
durable; deleting an effect does not release its color to a new one automatically). Test
in-game with `/materione dev effect <pack>:<id> <text>`.

---

## 7. HUDs

The ItemsAdder-gap differentiator (master §3.8). Rendered via the font/glyph system + per-
player packet-driven text displays, not boss bars.

```
HudDefinition
 ├─ layers: [image|text|bar]        // z-ordered
 ├─ positioning: anchor + x/y + scale (integer pixel math, deterministic)
 ├─ visibility: per-world, conditions (ECA expression), animation frames
 └─ bindings: bar/text values ← expressions | placeholders | custom vars
```

Runtime: per-player HUD state machine — evaluates visibility conditions and value bindings on
a throttled tick (configurable, default ~4 Hz, only for online players with visible HUDs),
renders to text-display overlays via the platform packet layer. Values can be written by ECA
actions (`hud.set`), by expressions polling placeholders, or by addons.

Failure modes: expression error → HUD hides that layer + diagnostic (never a hot-loop
exception spam); packet failure (player gone) → session torn down cleanly.

---

## 8. Menus & dialogs

### 8.1 Menus (the GUI builder differentiator)

```
MenuDefinition
 ├─ layout: grid slots (chest semantics) or free-form glyph-canvas
 ├─ items: static items | item references (content graph) | template-driven fill
 ├─ click bindings: per-slot or per-region ECA rules (menu.click events)
 ├─ pagination, search, sorting — built-in behaviors with config
 └─ open triggers: command, ECA action, NPC via integrations
```

Runtime: built on triumph-gui-style chest GUIs (virtual inventory) for chest layouts; the
free-form canvas mode renders a glyph background + real slots mapped onto it (shift-tag
layout, as incumbents do). Every click is an ECA `menu.click` — same engine, same actions,
same conditions as everything else.

**Shipped ahead of it (pre-M3 tooling):** the holder-based gui kit in `plugin/gui/`
(`PluginGui` identified by `InventoryHolder` identity — never title matching — plus
`PaginatedGui` page math and one shared click router; the router cancels only the
gui's own slots so a picked item can be deposited into the viewer's inventory).
Built-in admin surfaces already ride
it: `/materione give <id> [player] [amount]` and the `/materione browser` pack-picker →
paginated grid of a pack's items, blocks, and furniture (permission `materione.give`) —
blocks/furniture render as their placement item's real stack, and every placeable
definition has one: the definition's `item:` link, a same-id item resource, or the
auto-generated placement item (below). MenuDefinition will render author menus
through this same kit.

### 8.2 Dialogs (1.21+ server-side)

Thin, faithful wrapper over the vanilla dialog system (NOTICE/CONFIRM/LIST/MULTI/LINK;
message/item bodies; text/bool/number/single inputs; static + dynamic actions), with inputs
flowing into ECA `dialog.submit` events. Dialog definitions are content resources like any
other — referencable from menus, items, furniture, NPCs.

---

## 9. Recipes

- All vanilla types (shaped, shapeless, furnace, blasting, smoking, stonecutting, smithing,
  brewing) as content resources; ingredients/results reference items via the graph (incl.
  tags, vanilla types).
- Applied through the platform layer's recipe API at apply-stage; the recipe book is resynced
  post-swap.
- Disable-vanilla is a settings-level concern per recipe id, applied at the same stage.
- In-game builder is an editor feature (menus + dialogs composed from the same primitives —
  dogfooding the menu/dialog systems).
- Failure: unresolvable ingredient → quarantine recipe only; vanilla conflict → rename with
  namespaced id (never collide with `minecraft:`).

**As shipped (M1):** shaped, shapeless, and furnace only (the M1 scope above the fold);
results and ingredients resolve through the graph, Materione results become `ExactChoice`,
vanilla ingredients `MaterialChoice`; managed keys are removed then re-registered per swap
with a recipe-book resync. The remaining types (blasting, smoking, stonecutting, smithing,
brewing), disable-vanilla settings, and the in-game builder wait for M7.

---

## 10. Sounds, paintings, music discs

- **Sounds**: `.ogg` assets → pack `sounds.json`; registration namespaced; replace-vanilla
  is a platform-gated override map. Usable everywhere via `play_sound` action + text
  pipeline.
- **Music discs / jukebox songs**: registry-registered on Paper 1.21.6+ (`:modern`),
  world-datapack artifact (auto-generated at compile) on older servers; item component
  `jukebox_playable`, comparator output/range/duration configurable.
- **Paintings**: painting-variant registry (1.21+) — composed through Paper's registry
  events on Paper 1.21.6+, datapack variant entries on older servers; asset dimensions
  validated at compile; animated via `.mcmeta`. **Restart-required content** (master §6.4):
  painting/jukebox/trim changes stage for the next boot and are reported as such — they
  never pretend to hot-apply.
- **Custom particles**: a small particle API on items/furniture/blocks (`particle` action in
  ECA + aura-style shapes) — deliberately thin; deep VFX stays with ModelEngine/particle
  plugins through the integrations seam rather than a parallel particle engine (parity
  §3.12's "small particle API" target).

**As built (M4):** sounds are `sounds/*.yml` — one event per file, `.ogg` sources under
`assets/sounds/`; events group per namespace into `assets/<ns>/sounds.json`, and
`replace_vanilla: <event>` entries route through the compiler's vanilla-override merge into
`assets/minecraft/sounds.json` (first-wins with diagnostics, like blockstates). `/materione
dev sound <pack>:<id>` plays a key at the sender for instant checking. A sound's `jukebox:`
section turns it into a datapack `jukebox_song` artifact; an item's `jukebox_song:` field
adds the `minecraft:jukebox_playable` component fail-soft at stack build (unknown song keys —
not registered yet — are skipped, never crash the stack). Paintings are
`paintings/*.yml` → painting variants + the `minecraft:placeable` tag for
vanilla random placement, with the texture's exact pixel size (16/block) validated from the
PNG header at parse time (MTR-0041). **The texture needs no generated atlas file**: the
client's paintings atlas (`assets/minecraft/atlases/paintings.json`, plural) directory-scans
`textures/painting/` across all namespaces with an empty prefix, so the sprite id is
`<ns>:<path>` and the variant's `asset_id` is exactly that — a `painting/`-prefixed asset_id
or a generated single-source atlas file renders as an invisible checkerboard (an earlier
implementation shipped `atlases/painting.json`, a file the client never loads, and learned
this the hard way).

**Two registration seams (as of the 26.x migration).** Capability detection
(`RegistryCapabilities`, reflection on the server's own classes — no version strings) picks
one per boot:
- *Registry mode* (Paper 1.21.6+, the Nexo model): the plugin's `PluginBootstrap`
  (`:modern`, compiled against paper-api 26.1.2 — the D10 floor exception) parses the
  registry-relevant content straight from the pack sources (same parsers, same template
  pool), registers painting variants via `RegistryEvents.PAINTING_VARIANT.compose()`,
  jukebox songs via `RegistryEvents.JUKEBOX_SONG.compose()` (the song's sound event is
  declared inline through the nested builder factory — a resource-pack-only sound key is
  not a registry entry), and placeable paintings via the post-flatten
  `TAGS(PAINTING_VARIANT)` event. Nothing is written into the world; compose-registered
  entries sync to clients like datapack entries.
- *Datapack mode* (1.21.4/1.21.5 floor): the variants and songs compile into one
  deterministic `datapack.zip` (pack.mcmeta, hash
marker) that `DatapackService` deploys atomically to the default world's `datapacks/` folder
on every reload — unchanged hash skips, a changed hash stages restart-required intent
(MTR-0028) that the boot drain clears (MTR-0029). Restart-required classification is hence
proven end to end: deployed bytes are visible, applied only after a restart, and the pre-apply
report never pretends otherwise. The applier trusts the world zip, not a side
marker: the deployed artifact carries its hash inside `materione.json`, the
skip decision compares against that embedded hash, and a lost or regenerated
world is repaired on the next reload even when the pack bytes are unchanged
(a noop reload still verifies the world folder). `/materione doctor` prints
the deployed vs boot-loaded hashes, and `/materione dev painting <pack>:<id>`
places one exact variant where a player looks — vanilla placement only picks
randomly from the placeable tag, so the dev command is the deterministic
check. The datapack's `pack.mcmeta` advertises its format range in both schema
generations (`pack_format`+`supported_formats`, `min_format`+`max_format`) with
the server-reported data format folded into the upper bound — formats are
major.minor pairs since 25w31a (26.1's data format is 101.1) and a datapack
advertising only a fixed old `pack_format` reads as incompatible on
1.21.9+/26.x servers. Deployment targets the **world root's** `datapacks/`
folder (world container + the world's name): since the 26.x world-folder
restructure, `getWorldFolder()` of the first Bukkit world points at the
overworld *dimension* directory, which vanilla never scans — the first
implementation deployed there and the datapack silently never loaded. The
acceptance gate closes that class of failure: at boot the plugin asks the
server's `DatapackManager` whether `materione-content` was actually
registered (MTR-0046 when not), and `/materione doctor` prints deployed vs
boot-loaded hashes plus acceptance. `/materione dev painting <pack>:<id>`
places one exact variant where a player looks — vanilla placement only picks
randomly from the placeable tag, so the dev command is the deterministic
check.

In registry mode the same hash ledger runs against the compiled artifact
(the registered content's proxy): the first apply of a boot records it, a
reload that changes it stages restart-required, any world-folder artifact
left from a datapack-era boot or server upgrade is removed (its entries
would register first at world load and collide with the registry's own),
and `/materione doctor` prints `registry: … registered at boot` instead of
the datapack line.

---

## 11. World generation & fluids (differentiators)

- **Ores/worldgen**: pack-scoped worldgen modifiers (datapack JSON) referencing custom-block
  ids; placement rules (biome, Y-range, vein size, frequency) are definition fields. Existing
  chunks are untouched — generation applies to new terrain only (documented clearly; retro-
  population is an explicit opt-in command, not a default).
- **Fluids**: flowing-liquid simulation is the one system allowed a **per-region tick
  budget** — a bounded work queue processed on region threads (Folia-safe), spreading
  scheduled, not instant; flow state in chunk PDC. Hard caps (spread per tick, total active
  fluid cells) with backpressure — fluid simulations are the classic server-killer, and the
  architecture refuses to become one.

---

## 12. Armor & equipment

- Component-based (`equippable.asset_id`) as the default path (1.21.2+): asset generation at
  compile, any material, no datapack restart.
- Trim-based path as a documented option (auto-datapack, chainmail base).
- Mob armor / saddles / harness tracked as platform capabilities — new equipment slots
  Mojang adds appear here first as capability gates, then as definition fields (master §3.7).
- Animated/emissive layers are pack-side (texture + optional emissive map), no runtime
  behavior beyond the item system's.

---

## 13. Interop surface (how systems meet)

| Seam | Contract |
|---|---|
| Item ↔ Block/Furniture | the placement link resolves in order: the definition's `item:` reference → a same-id item resource (implicit placement facet — authoring `items/chair.yml` + `furnitures/chair.yml` needs no wiring) → the auto-generated synthetic placement item (§4.2). Every placeable definition is grabbable; synthetic items live only in the runtime registries, never as files |
| Menu ↔ anything | menu items are graph refs; menu.click is ECA — actions can give items, open dialogs, teleport, etc. |
| HUD ↔ anything | hud.set/hud.get actions + expression variables — any system can drive HUD values |
| Dialog ↔ anything | dialog.submit is ECA with input vars; actions consume them |
| Recipes ↔ items | graph refs both directions; recipe quarantines independently |
| Worldgen ↔ blocks | worldgen modifier references block id; removed block → worldgen modifier quarantined, world unaffected |
| ECA ↔ all | every system's events/conditions/actions flow through one engine (§2) |

The pattern that matters: **every cross-system interaction is a graph edge + an ECA event**.
No system calls another system's internals; they meet at the registry, the graph, and the
engine. That is D2/D4/D5 made concrete, and it is why an addon can extend any of them without
touching core.
