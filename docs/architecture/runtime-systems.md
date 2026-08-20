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
  definition with overridable fields; diamond inheritance → validation error.

### 3.3 Failure modes

Unresolved model ref → quarantine (works server-side, renders as base material); definition
hash unknown (removed pack) → renders vanilla + flagged for admin; unsupported component →
platform-gate diagnostic ("needs 1.21.x").

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

- **Chunk PDC is the truth** for placed blocks (CustomBlockData); the index in `generated/`
  accelerates lookups and is rebuildable by chunk scan (self-healing).
- **Host-block physics suppressed** (Paper) so hijacked note blocks don't beep and tripwire
  doesn't cut.
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

---

## 10. Sounds, paintings, music discs

- **Sounds**: `.ogg` assets → pack `sounds.json`; registration namespaced; replace-vanilla
  is a platform-gated override map. Usable everywhere via `play_sound` action + text
  pipeline.
- **Music discs / jukebox songs**: datapack artifact (auto-generated at compile), item
  component `jukebox_playable`, comparator output/range/duration configurable.
- **Paintings**: datapack variant registry (1.21+), asset dimensions validated at compile;
  animated via `.mcmeta`. **Restart-required content** (master §6.4): painting/jukebox/trim
  changes stage for the next boot and are reported as such — they never pretend to hot-apply.
- **Custom particles**: a small particle API on items/furniture/blocks (`particle` action in
  ECA + aura-style shapes) — deliberately thin; deep VFX stays with ModelEngine/particle
  plugins through the integrations seam rather than a parallel particle engine (parity
  §3.12's "small particle API" target).

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
| Item ↔ Block/Furniture | the same item id may have a block and/or furniture placement facet; placing resolves through the graph |
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
