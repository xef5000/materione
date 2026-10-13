# Feature-Parity Research: ItemsAdder · Oraxen · Nexo

> **Purpose.** This document inventories the features of the three leading premium
> Minecraft content/resource-pack plugins so that an open-source successor (this project)
> can match — and exceed — what each one offers. It is organized as:
> 1. A quick-scan **feature matrix** ("what must I build?").
> 2. A short **architecture note** per plugin (how each one models content).
> 3. A **deep breakdown** per feature category, with each plugin's implementation and a
>    **Target spec** box describing the minimum a competitor must ship.
> 4. **Unique differentiators** per plugin.
> 5. A **recommended scope** (MVP vs. full) and **modern-MC foundation** notes.

---

### ⚠️ Data provenance (read this)

| Plugin | Source | Confidence |
|---|---|---|
| **Oraxen** | Freshly pulled from `docs.oraxen.com` (all `creating-content`, `plugin-setup`, `usage`, `compatibility` pages) | High |
| **Nexo** | Freshly pulled from `docs.nexomc.com` (50+ pages, all mechanics/furniture/blocks/components/addons) | High |
| **ItemsAdder** | **Model knowledge only.** The live-fetch sub-agents all died on a 5-hour API usage limit (429). Treat the ItemsAdder column as accurate for headline features but **spot-verify** finer points against `wiki.itemsadder.com` before depending on them. Items marked `⚠️` are lower-confidence. |

---

## Legend

| Symbol | Meaning |
|---|---|
| ✅ | Native, first-class feature |
| 🟡 | Partial — present but limited, glyph-based, delegated to an addon, or only via a third party |
| ⚠️ | Reported / from model knowledge — **verify** |
| ❌ | Not natively supported (may be achievable by combining other plugins) |

**Materione column** (status as of 2026-10-10; sources: the
[roadmap](roadmap.md) shipped record M0–M4 and the in-game QA catalog
[`qa/`](../qa/README.md)):

| Symbol | Meaning |
|---|---|
| ✅🧪 | Added **and** exercised by the in-game QA suite (real client render / command assertions) |
| ✅ | Added — unit tests + the live-Paper harness cover it in CI |
| 🚧 | Partial — a first slice ships, the rest is owned by a named milestone |
| 📋 | Planned — on the roadmap under the cited milestone, tracked on the [board](https://github.com/users/xef5000/projects/1) |
| ➖ | Not planned — delegated permanently to other tools or rejected (see the roadmap's "explicitly NOT" list) |

---

## 1. Feature Matrix (quick scan)

### A. Architecture & platform

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Config model | YAML "items packs" | YAML items + `Mechanics` engine | YAML items + `Mechanics` engine | ✅ (lossless YAML round-trips) |
| Auto resource-pack generation | ✅ | ✅ | ✅ | ✅ |
| Built-in pack self-host webserver | ✅ | ✅ (Java HTTP) | ✅ (SELFHOST) | ✅ |
| External/CDN upload | ✅ | ✅ (Polymath/Atlas, transfer.sh) | ✅ (S3 / R2 / Hetzner, LobFile, Polymath) | 📋 M7 |
| Pack obfuscation | ⚠️ | ✅ (tamper protection) | ✅ (NONE/SIMPLE/FULL + cache) | 📋 M7 |
| PackSquash compression | ❌ | ❌ | ✅ | 📋 M7 |
| Multi-version packs (ViaVersion) | ✅ ⚠️ | ✅ | ✅ | ✅ (format ladder + overlays) |
| Proxy pack-dedup (Velocity/Bungee) | ❌ ⚠️ | ✅ (PackLayer) | ✅ (NexoProxy) | 📋 M7 |
| Pre-join pack send | ⚠️ | ✅ (1.21.7+) | ✅ | 📋 M7 (send-on-join ✅) |
| **Folia** support | ⚠️ verify | ✅ native | ✅ native | 🚧 (Folia-safe scheduling, unproven) |
| **Bedrock (Geyser)** conversion | ❌ / community | ❌ | ✅ (Scaffolding addon) | 📋 M8 |
| Pack layering (on top of existing packs) | ⚠️ | ✅ | ✅ (MultiPack) | 📋 M7 |
| Creative-inventory client mod | ❌ | ❌ | ✅ (NCI Fabric mod) | 📋 M8 |

### B. Items

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Custom items (name/lore/flags/attrs/enchants/PDC) | ✅ | ✅ | ✅ | ✅🧪 |
| `custom_model_data` models | ✅ | ✅ | ✅ | ✅ (via ItemModels) |
| **1.21.4+ ItemModels** (item model JSON) | ⚠️ | ✅ (4 generation modes) | ✅ (full ItemModel builder) | ✅🧪 |
| **1.20.5+ data components** | 🟡 ⚠️ | ✅ | ✅ (deepest coverage) | ✅ |
| Auto texture/model generation | ✅ | ✅ | ✅ | ✅🧪 |
| Hand-authored 3D models (Blockbench) | ✅ | ✅ | ✅ | ✅🧪 |
| Composite / multi-layer models | ⚠️ | ✅ (gui_model, oversized) | ✅ (composite_models) | 📋 M7 |
| Oversized-in-GUI models (1.21.6+) | ⚠️ | ✅ | ✅ | 📋 M7 |
| Dyeable items | ✅ | ✅ | ✅ | 📋 M7 |
| Durability-based "damaged" model stages | ✅ | ✅ | ✅ | 📋 M7 |
| 2D player heads as items | ⚠️ | ❌ | ✅ | 📋 M7 |
| Item templates / inheritance | ⚠️ | 🟡 | ✅ (`template`) | ✅🧪 |
| Item auto-updater (refresh held items on config change) | ⚠️ | ✅ | ✅ | ✅🧪 |

### C. Special item types

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Custom shields (blocking model) | ✅ | ✅ | ✅ | 📋 M7 |
| Custom bows (pulling stages) | ✅ | ✅ | ✅ | 📋 M7 |
| Custom crossbows (charged/firework) | ✅ | ✅ | ✅ | 📋 M7 |
| Custom fishing rods (cast model) | ✅ | ✅ | ✅ | 📋 M7 |
| Custom tridents | ✅ ⚠️ | 🟡 (via mechanics) | ✅ (dedicated mechanic) | 📋 M7 |
| Custom elytra | ✅ | ✅ | ✅ | 📋 M7 |

### D. Mechanics / Behaviors (item-level logic)

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Events on items (click/equip/death/drop/etc.) | 🟡 ⚠️ | ✅ (Custom Mechanic + clickAction) | ✅ (Custom Mechanic) | ✅ (ECA) |
| Expression conditions (JS/SpEL on player/server) | ⚠️ | ✅ (JS-like) | ✅ (SpEL) | ✅ (ECA expressions) |
| Commands mechanic (console/player/op + cooldown) | ✅ | ✅ | ✅ | ✅🧪 (console + player; cooldown on behavior rules) |
| Custom durability mechanic | ⚠️ | ✅ | ✅ | ✅🧪 (PDC counter; vanilla wear cancelled; survives reload; breaks at max) |
| Custom food / consumable + effects | ✅ | ✅ | ✅ (component) | ✅🧪 (vanilla food component projection + effects on consume) |
| Soulbound (keep on death) | ⚠️ | ✅ | ✅ | ✅🧪 (PDC stash on death, restore on respawn) |
| Armor effects (potion while worn, full-set) | ⚠️ | ✅ | ✅ | 📋 M7 |
| Backpack item mechanic | ⚠️ verify | ✅ | ✅ | ✅🧪 |
| Aura / particle effects | 🟡 | ✅ (simple/ring/helix) | 🟡 | 📋 M7 |
| Skin / skinnable items | ⚠️ | ✅ | ❌ | 📋 M7 |
| Hat (wear any item on head) | ⚠️ | ✅ | 🟡 | 📋 M7 |
| **Combat spell mechanics** (lunge, lightning, fireball, lifeleech, bleed, wither skull, energy blast) | ❌ | ✅ (richest) | 🟡 | 📋 M7 |
| **Farming mechanics** (harvest/replant, big-mining 3×3, auto-smelt, bottled-XP, bedrock-break, watering) | ❌ ⚠️ | ✅ (richest) | ✅ (harvesting, smelting) | 📋 M7 |
| Repair / item-as-repair-material | ⚠️ | ✅ | 🟡 | 📋 M7 |
| Third-party custom-mechanic API | ✅ ⚠️ | ✅ (factory + listener) | ✅ (Mechanic + Factory) | ✅ (mechanics registry seam) |

### E. Custom blocks (world-placed)

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| NoteBlock-based blocks | ✅ (~800) | ✅ (~800) | ✅ (~1149) | ✅🧪 |
| Tripwire/StringBlock (foliage, waterlog, tall, sapling) | ✅ ⚠️ | ✅ (~127) | ✅ (~127) | 🚧 (dry — not waterloggable yet) |
| ChorusBlock (transparent) | ✅ ⚠️ | ✅ (~63) | ✅ (~63) | ✅🧪 (connection-bit state identity; floats without support) |
| Blocks-without-textures (technical) | ✅ | 🟡 (legacy mushroom) | 🟡 | ✅🧪 (PDC-identified on the unclaimed host default state; vanilla hosts coexist) |
| Hardness / best-tool / blast resistance / sounds | ✅ | ✅ | ✅ | 📋 M7 |
| Light emission | ✅ | ✅ | ✅ | ✅🧪 (furniture light cells; the client light engine reads the placed level — blocks carry no light field yet) |
| Custom drops (silk-touch/fortune/tiered/loot-table) | ✅ | ✅ | ✅ | 📋 M7 |
| Block storage (shulker/enderchest/disposal/personal) | ✅ | ✅ | ✅ | ✅🧪 (furniture storage: shift-click round-trips through the root PDC; spills on removal — blocks carry no storage yet) |
| Limited placing (allow/deny lists, roof/floor/wall, radius) | ✅ | ✅ | ✅ | 🚧 (floor-only placing + space pre-check, ✅🧪 live; allow/deny lists, roof/wall, radius 📋 M7) |
| Directional variants (log/barrel/furnace/dropper) | ✅ ⚠️ | ✅ | ✅ | 📋 M7 |
| Falling blocks | ⚠️ | ✅ | ✅ | 📋 M7 |
| **Stairs / slabs / doors / trapdoors / grates / bulbs** | 🟡 ⚠️ | ✅ (ShapedBlock, built-in) | ✅ (Carpentry addon) | 🚧 (stairs + slabs via shaped blocks) |
| Log stripping | ⚠️ | ✅ | ✅ | 📋 M7 |

### F. Furniture (entity decor)

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Display-entity furniture (1.19.4+) | ✅ ⚠️ | ✅ (default) | ✅ (only method) | ✅🧪 (ItemDisplay root + interaction/seat/label members, client-visible, survives client relaunch) |
| Item-frame / glow-frame / armor-stand furniture | ✅ | ✅ | ❌ (dropped) | ➖ (display entities only) |
| Hitboxes (barrier / interaction / shulker / ghast) | ✅ | ✅ | ✅ (4 types, incl. ghast 1.21.6) | ✅ |
| Display transform / scale / brightness / culling | ✅ ⚠️ | ✅ | ✅ | 🚧 (transform + rotation; scale/brightness 📋 M7) |
| Seats (single + multi) | ✅ | ✅ | ✅ | ✅ |
| Lights (toggleable) | ✅ | ✅ | ✅ | ✅ |
| Storage | ✅ | ✅ | ✅ | ✅ |
| Beds (sleep / lie-down) | ⚠️ | ❌ | ✅ | 📋 M7 |
| Doors (rotating + sliding) | ⚠️ | 🟡 | ✅ | 📋 M7 |
| Connectable furniture (couches/tables auto-join) | ⚠️ | ❌ | ✅ | 📋 M7 |
| Furniture states (cycle on interact) | ⚠️ | 🟡 (Pack.models) | ✅ (6 state types) | 📋 M7 |
| Furniture placements (per-surface behavior) | ⚠️ | ❌ | ✅ | 📋 M7 |
| Text displays (PAPI, client-side) | ⚠️ | ✅ | ✅ | ✅ |
| Evolution / farming stages | ✅ | ✅ | ✅ | ✅ |
| Rotation / restricted rotation | ✅ | ✅ | ✅ | ✅ |
| Waterloggable | ⚠️ | ✅ (string/shaped) | ✅ | 🚧 (stringblocks dry) |
| Jukebox furniture (accept discs) | ⚠️ | ✅ | ✅ | 📋 M7 (vanilla-jukebox discs ✅) |
| BlockLocker protection | ⚠️ | ✅ | ✅ | 📋 M7 |
| ModelEngine blueprint binding | ⚠️ | ✅ | ✅ | 📋 M7 |

### G. Armor & equipment

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Component-based armor (1.21.2+, any material) | ⚠️ | ✅ | ✅ | 📋 M7 |
| Trim-based armor (1.20+, datapack) | ✅ | ✅ | ✅ (deprecated 1.25) | 📋 M7 |
| Legacy shader armor (Optifine/leather) | ✅ | ✅ (1.18–1.19.4) | ❌ | ➖ (modern-only, D10) |
| Animated / emissive armor textures | ✅ | ✅ | ✅ | 📋 M7 |
| Custom elytra | ✅ | ✅ | ✅ | 📋 M7 |
| **Mob armor** (wolf / horse / llama / nautilus) | ⚠️ verify | ❌ (reachable via component only) | ✅ (documented) | 📋 M7 |
| **Custom saddles** (1.21.5+) | ❌ | ❌ | ✅ | 📋 M7 |
| **Harness** (Happy Ghast, 1.21.6+) | ❌ | ❌ | ✅ | 📋 M7 |

### H. HUDs & on-screen UI

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| **Native HUD system (Java)** | ✅ **(strongest)** — IMAGES/TEXT/BAR | 🟡 (glyph-based only) | ❌ (Java) / ✅ Bedrock retexture | 📋 M6 |
| Animated HUD images | ✅ | 🟡 | ❌ | 📋 M6 |
| Progress-bar HUDs (mana/stamina/health) | ✅ | 🟡 (glyph bars) | ❌ | 📋 M6 |
| Per-world / conditional HUDs | ✅ | 🟡 | ❌ | 📋 M6 |

### I. Glyphs / fonts / emoji

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Basic glyph (texture → unicode) | ✅ | ✅ | ✅ | ✅ |
| Multi-bitmap glyph (one sheet → many) | ✅ | ✅ | ✅ | ✅ |
| Animated glyph (sprite sheet / GIF) | ✅ | ✅ | ✅ | 🚧 (frame 1 renders; cycling 📋 M6) |
| Grid glyph (large texture across chars) | ✅ | ✅ | ⚠️ | ✅ |
| Reference glyph (alias) | ✅ | ✅ | ✅ | ✅ |
| Pixel-shift tags (`<shift:N>`) | ✅ | ✅ | ✅ | ✅ |
| Emoji/chat integration + permissions + tab-complete | ✅ | ✅ | ✅ | ✅ |
| **GLSL text effects** (rainbow/wave/shake/pulse) | ✅ | ✅ | ✅ (addon, 7 effects) | ✅ |

*As built in Materione (M4):* all glyph shapes (basic, `rows`/`columns` sheets,
`frames` animations, `reference` aliases), shift tags (a space provider appended
to every generated font), and emoji mode (chat decorate interception, per-glyph
permissions, tab-completion) — with one deliberate divergence: codepoints are
durable allocation-table runs, never written back into authoring YAML (Nexo and
Oraxen both mutate the user's file). GLSL text effects inject a version-laddered
`rendertype_text` vertex-shader overlay keyed on durable per-effect signal colors;
spans are `<effect:pack:id>…</effect>` in any resolved text (runtime-systems §6).

### J. GUIs / menus / dialogs

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Built-in item browser GUI (categorized) | ✅ (`/ia`) | ✅ (`/oraxen inv`) | ✅ (`/nexo inv`) | 📋 M6 |
| Free-form interactive GUI builder | 🟡 ⚠️ (font-image backgrounds; clicks via other plugins) | ❌ | 🟡 (glyph backgrounds; MultiPack menu) | 📋 M6 |
| **Server-side dialogs** (1.21+ forms) | ❌ | ❌ | ✅ | 📋 M6 |

### K. Recipes

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Shaped | ✅ | ✅ | ✅ | ✅ |
| Shapeless | ✅ ⚠️ | ✅ | ✅ | ✅ |
| Furnace | ✅ | ✅ | ✅ | ✅ |
| Blasting / Smoking | ⚠️ | ⚠️ | ✅ | 📋 M7 |
| Stonecutting | ⚠️ | ⚠️ | ✅ | 📋 M7 |
| Smithing | ⚠️ | ⚠️ | ✅ (addon) | 📋 M7 |
| Brewing | ❌ | ❌ | ✅ | 📋 M7 |
| In-game recipe builder | ⚠️ | ✅ | ✅ | 🚧 (web studio; in-game 📋 M7) |
| Disable vanilla recipes | ✅ | ✅ | ✅ | 📋 M7 |

### L. Sound, music & visuals

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| Custom sounds registration | ✅ | ✅ | ✅ (incl. replace vanilla) | ✅ |
| Custom music discs / jukebox songs | ✅ | ✅ | ✅ | ✅ |
| **Custom paintings** (1.21 datapack) | ✅ | ❌ | ✅ | ✅ |
| Custom particles | 🟡 ⚠️ | 🟡 (Aura) | ❌ native | 📋 M7 |

*As built in Materione (M4):* custom sounds (`sounds/` definitions → per-namespace
`sounds.json`, `replace_vanilla:` overrides through the merge seam, inline studio
playback, `/materione dev sound` for in-game checks), music discs (a sound's
`jukebox:` section → datapack song; an item's `jukebox_song:` field → the disc
component, fail-soft until the datapack loads), and custom paintings
(`paintings/` → `painting_variant` datapack entries + painting-atlas sprites +
pixel-size validation + the `placeable` tag). All three are first-class editor
types with studios, validation and goldens; paintings/jukebox apply restart-required
through the M2 staging ledger.

### M. World & environment

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| **Ore / world generation** (populate custom blocks) | ✅ **(unique)** | ❌ | ❌ | 📋 M8 |
| **Custom liquids / fluids** (flowing) | ✅ **(unique)** | ❌ | ❌ | 📋 M8 |
| Vehicles / mounts (native driving) | ❌ | ❌ | ❌ | ➖ |
| Pets / companions (native) | ❌ | ❌ | ❌ | ➖ |
| Custom mobs (native AI) | ❌ | ❌ | ❌ | ➖ |
| Loot tables | ⚠️ | 🟡 | ✅ | 📋 M7 |

### N. Integration & API

| Capability | ItemsAdder | Oraxen | Nexo | Materione |
|---|---|---|---|---|
| PlaceholderAPI | ✅ | ✅ | ✅ | ✅ |
| Java developer API | ✅ (CustomStack/Block/Furniture) | ✅ | ✅ (Maven + JavaDocs) | ✅ (registry + mechanics seams) |
| ModelEngine compat (merge RP + blueprints) | ✅ | ✅ | ✅ (auto-strip shaders) | 📋 M7 |
| MythicMobs / MythicCrucible | ⚠️ | ✅ (Crucible) | ✅ | 📋 M7 |
| MMOItems | ⚠️ | ❌ | ✅ | 📋 M7 |
| Vault / economy | ⚠️ | ⚠️ (undocumented) | ⚠️ | 📋 M7 |
| BlockLocker protection | ⚠️ | ✅ | ✅ | 📋 M7 |
| Anti-cheat bridges | ⚠️ | ❌ | ✅ (Vulcan/Spartan) | 📋 M7 |
| Migration converters | — | — | ✅ (from Oraxen **and** ItemsAdder) | 📋 M7 (IA → Oraxen → Nexo) |

---
## 2. Architecture at a glance

### ItemsAdder (LoneDev)
- **Content = "items packs."** Self-contained folders under `plugins/ItemsAdder/contents/<pack>/`, each holding its own YAML configs **and** assets (`textures/`, `models/`, `fonts/`, `sounds/`). This modularity is ItemsAdder's signature trait — packs are drop-in.
- `config.yml` (global), `resource_pack/` (generated output), `contents/` (input packs).
- Pack built/regenerated via `/iazip` (or `/ia` reload); ItemsAdder assigns `custom_model_data`, injects fonts, writes model JSON.
- Hosting: built-in self-host webserver **or** an external/CDN URL.
- **Lean:** ItemsAdder's differentiators are the *resource-pack surface* (HUDs, glyphs, liquids, worldgen, paintings). It is **weaker** on item *behavior* (no rich combat/farming mechanic catalog) and **weak** on modern component/model APIs.

### Oraxen (th0rgal / g_p)
- **Content = YAML items + a Mechanics engine + auto pack generation + auto delivery.**
- `items/*.yml` (each item = ID + `displayname`/`material`/`Pack`/`Components`/`Mechanics`), `mechanics.yml` (global defaults), `glyphs/`, `recipes/`, `pack/` (your hand-authored assets + overrides), `settings.yml`.
- A `NexoItem`/Oraxen item attaches reusable `Mechanics` blocks; the mechanics factory/listener pattern is public so third parties register their own.
- Pack uploaded to Polymath/Atlas, self-host Polymath, self-host Java HTTP, or transfer.sh; dispatched with per-status messages/commands.
- **Lean:** Oraxen's differentiators are the **richest mechanic catalog** (combat + farming spells) and the **ShapedBlock** system (stairs/slabs/doors/trapdoors/grates/bulbs) built in. It is **weaker** on native HUDs, dialogs, mob armor, and Bedrock.

### Nexo (NexoMC)
- **Content = YAML items + Mechanics + the most modern pack/component engine + built-in pack server.**
- `items/` (nested folders), `glyphs/`, `recipes/`, `dialogs/`, plus `settings.yml`, `sounds.yml`, `paintings.yml`, `mechanics.yml`, `inventory.yml`, `multipack.yml`; assets under `pack/assets/<namespace>/...`.
- Merges external packs from `pack/external_packs/`, resolving conflicts in `paper.json`/`sounds.json`.
- Pack server backends: POLYMATH, SELFHOST, LOBFILE, **S3 (R2/Hetzner/AWS)**, pluggable SPI.
- **Lean:** Nexo is the *most modern* — it aggressively pushes 1.21.4+ **ItemModels**, the full **data-component** surface (incl. 1.21.11 weapon/kinetic-weapon components), **server-side dialogs**, **mob armor/saddles/harness**, **MultiPack**, obfuscation/PackSquash, **Bedrock** (Scaffolding), a **proxy** deduper, and a **client creative-inventory mod**. It ships converters **from Oraxen and ItemsAdder**. Its gaps: no native mobs/worldgen/HUD(Java)/fluids.

---

## 3. Deep feature breakdown

> Each category below ends with a **🎯 Target spec** — the minimum bar for parity.

### 3.1 Custom items

**ItemsAdder** — Items defined in a pack's YAML with `display_name`, `lore`, `permission`, `resource` (`material`, `generate`, `textures`, `model_path`, `parent_model`), `durability`, `nbt`, enchantments, attribute modifiers, item flags, custom data. Supports auto-generated 2D models (parent + texture layers) and hand-authored 3D Blockbench models. `custom_model_data` auto-assigned.

**Oraxen** — Each item = `displayname` + `material` + `Pack` (appearance) + `Components` (vanilla data components) + `AttributeModifiers` + `Mechanics`. The `Pack` block generates models (`generate_model` + `parent_model` + `textures`) or points at a hand-authored JSON. On 1.21.4+ offers **four combinable appearance modes**: `item_properties` (item_model component), `model_data_ids` (`custom_model_data.strings` + `select`), `model_data_float` (`floats[0]` + `range_dispatch`), `generate_predicates` (legacy overrides). An `ItemUpdater` refreshes items already in player inventories on reload.

**Nexo** — `NexoItem` = base props (`itemname`/`material`/`lore`/`color`/`Enchantments`/`AttributeModifiers`/`PersistentData`/`ItemFlags`/`template`/`unbreakable`/...) + `Pack` + `Components` + `Mechanics`. A full **ItemModel builder** (1.21.4+) lets you declare Reference / RangeDispatch / Select / Condition / Composite / Empty / Special model trees in YAML — enabling time-based models (`local_time`), GUI-vs-world swapping (`display_context`), dye-state rendering, and throwing-vs-held tridents, all declaratively. Actively pushes users off `custom_model_data` onto unique per-item ItemModels (`/nexo reset_custom_model_data`).

**🎯 Target spec:** YAML item definitions with name/lore/flags/attributes/enchants/PDC; auto model generation (parent + texture layers) **and** hand-authored 3D models; **1.21.4+ ItemModel support** (not just `custom_model_data`); per-context model swapping; an item auto-updater; item templates/inheritance.

---

### 3.2 Data components (1.20.5+)

**Oraxen** — Passes through the common components with YAML sugar: `durability`, `fire_resistant`, `tool`, `food`/`consumable`/`use_remainder`/`use_cooldown` (+ on-consume effects), `equippable`, `item_model`, `tooltip_style`, `jukebox_playable`, repairable.

**Nexo** — **Deepest coverage** of the three: food/consumable, tool, **weapon (1.21.5+)**, **kinetic weapon (1.21.11+)**, piercing weapon, attack range, swing animation, use effects, damage type, min attack charge, **blocks-attacks (shields)**, equippable, glider, damage resistant, enchantable, repairable, **death protection**, use cooldown (groups), use remainder, jukebox playable, tooltip style/display, hide tooltip, break sound, item model, **player profile** (heads), **painting variant**, custom model data (floats/colors/flags/strings), unset components, max stack/damage, fire resistant, enchantment glint override, custom data, **can-place-on / can-break**.

**ItemsAdder** — Partial/⚠️; leans on NBT and item flags rather than a full component pass-through.

**🎯 Target spec:** A version-aware component pass-through that exposes **every** vanilla data component (1.20.5 → latest), with YAML sugar for the common ones (food, tool, equippable, glider, shields, durability, glint, custom data). Track Mojang's component churn per version.

---

### 3.3 Special item types (shields, bows, tridents, rods, elytra)

All three model **shields** (`blocking_model`), **bows** (`pulling_models`, 3 stages), **crossbows** (`charged_model` + `firework_model`), **fishing rods** (`cast_model`), **elytra** (via the armor system).

- **Nexo** additionally has a dedicated **custom trident** mechanic (`thrown_item_model`, `display_transform`, throw/hit sounds, 1.21.4+ conditional model on throwing state, or a dual-item method for older versions).
- **Oraxen** reaches trident behavior via mechanics rather than a dedicated type.

**🎯 Target spec:** Configurable multi-stage models for shields/bows/crossbows/fishing rods; a first-class trident type; elytra as part of the equipment/armor system.

---

### 3.4 Mechanics / Behaviors (item-level logic)

The behavior layer is where the plugins diverge most.

**Oraxen — the richest catalog.**
- *Misc:* custom food, **backpack**, music disc, durability, efficiency (Haste), consumable + potion effects, misc, repair, commands, armor effects, aura (simple/ring/helix), hat, skinnable/skin, item-type, soulbound, toggle-light.
- *Combat:* `spear_lunge`, `thor` (lightning), `lifeleech`, `bleeding`, `energyblast`, `witherskull`, `fireball`, `knockback_strike` — all with `charges`.
- *Farming:* `harvesting` (auto-replant), `bigmining` (3×3), `smelting` (auto-smelt ore), `bottledexp`, `bedrockbreak`, `watering` (watering-can).
- *Interaction:* `clickAction` (run `[console]`/`[player]`/`[message]`/`[actionbar]`/`[sound]` on click, gated by JS-like `#player.*`/`#server.*` conditions), and `custom-ability` (factory + listener; trigger on CLICK/DROP/PICKUP/INV_CLICK, with conditions/actions/one_usage).

**Nexo — modern + solid.**
- `backpack` (rows/title/sounds/blacklist), `misc` (cactus/fire/lava damage toggles, log stripping, compostable, vanilla-recipe allow), `commands`, armor effects, item-type, soulbound, **harvesting**, **smelting**.
- `clickAction` on blocks/furniture with **SpEL** conditions (`#player`, `#server`, negatable).
- `custom-mechanic` with **Event + Conditions + Actions + one_usage**; events: CLICK (right/left/all × block/air/all), INV_CLICK, DROP, PICKUP, BREAK, EQUIP, UNEQUIP, DEATH, DROP_ALL.
- Does **not** ship Oraxen's combat-spell mechanics out of the box.

**ItemsAdder — ⚠️ thinner.** Strong on **events for blocks/furniture** (place/break/interact/step/punch → commands/sounds) and basic item behaviors (food, durability, light, storage, seats, evolution), but no native combat-spell or farming-spell catalog. Custom behavior is generally farmed out to ModelEngine/MythicMobs/another plugin.

**🎯 Target spec:**
1. A generic **Event + Condition (expression language) + Action** system on items, blocks, **and** furniture (this is the common backbone across all three).
2. A library of built-in mechanics: commands, custom durability, food+effects, soulbound, armor effects, backpack, harvesting, big-mining, auto-smelt, bottled-XP, aura/particles.
3. A **public custom-mechanic registration API** so addons can add behaviors without forking.

---

### 3.5 Custom blocks

All three reuse the same vanilla-state hijack techniques:
- **NoteBlock** (note block states; ~800 / ~1149 for Nexo).
- **StringBlock** (tripwire; foliage, **waterloggable**, **tall plants**, **saplings** with tree/schematic growth).
- **ChorusBlock** (chorus plant; transparent blocks like leaves/glass).
- **Technical blocks** (mushroom-stem / barrier-class; ItemsAdder's "blocks without textures").

Shared block properties: `hardness`, `best_tool`, light, blast resistance, custom place/break/hit/step/fall sounds, custom drops (silk-touch/fortune/tiered/loot), **storage** (shulker/enderchest/disposal/personal), **limited placing** (allow/deny lists, roof/floor/wall, radius limits), **directional** variants (log/barrel/furnace/dropper), falling blocks, log stripping, BlockLocker protection.

- **Stairs/slabs/doors/trapdoors/grates/bulbs:** **Oraxen** ships this natively via **ShapedBlock** (waxed-copper-based). **Nexo** via the **Carpentry** addon. **ItemsAdder** ⚠️ partial.
- **Nexo** has the most variations and the newest sub-mechanics; **Oraxen** has the cleanest built-in ShapedBlock.

**🎯 Target spec:** NoteBlock + StringBlock + ChorusBlock + technical block types with the full shared property set; **built-in** shaped-block support (stairs/slabs/doors/trapdoors/grates); directional variants; falling blocks; log stripping. Recommend disabling host-block physics on Paper.

---

### 3.6 Furniture

Entity-rendered 3D decor. **Nexo is ItemDisplay-only** (drops item-frame/armor-stand furniture entirely); Oraxen defaults to display entities but still supports frames/stands; ItemsAdder ⚠️ supports frames/stands and (3.x) display/interaction entities.

Shared properties: `display_transform`, `tracking_rotation`, per-axis `scale`, `translation`, `brightness` (block/sky 0–15), culling (`display_width/height`, `view_range`, shadow), **hitboxes** (barrier / interaction / shulker / **ghast** on 1.21.6 in Nexo), **seats** (single + multi), **lights** (toggleable), **storage**, **evolution/farming stages**, **rotation/restricted rotation**, **limited placing**, **waterloggable**, **jukebox furniture**, **BlockLocker**, **ModelEngine** blueprint binding, **text displays** (PAPI, client-side).

Nexo-only furniture depth:
- **Beds** (offset, skip-night, reset-phantoms).
- **Doors** — rotating **and** sliding (transform-based), open/close sounds, toggle-hitbox-on-open, automatic-close delay.
- **Connectable furniture** — auto-joins pieces (couches/tables) with LEFT/RIGHT/STRAIGHT/INNER/OUTER states.
- **Furniture states** — cycle on interact (TV channels, filling bins); 6 state types (ITEM_MODEL, CUSTOM_MODEL_DATA, FURNITURE, MODELENGINE, ANIMATION, MODELENGINE_ANIM); auto reset; per-state SpEL conditions.
- **Furniture placements** — one item, different behavior per surface (floor/wall/roof).

**🎯 Target spec:** Display-entity-first furniture (with optional frame/stand fallbacks); hitboxes (barrier/interaction at minimum); seats; toggleable lights; storage; evolution/farming; rotation + limited placing; text displays; **states**, **connectable**, **placements**, **beds**, **sliding doors** as differentiators. Bind ModelEngine blueprints.

---

### 3.7 Armor & equipment

Three eras of armor rendering:
- **Component-based (1.21.2+, recommended):** `equippable.asset_id`; works with **any** material; no datapack restart. All three (Nexo ✅, Oraxen ✅, ItemsAdder ⚠️).
- **Trim-based (1.20+):** auto-generates a datapack; requires **chainmail** base; restart to add/remove sets. All three.
- **Legacy shader (Optifine/leather):** `armor_layer_1/2.png` + `_e` emissive; breaks Iris unless CIT-resigned. ItemsAdder ✅, Oraxen ✅ (1.18–1.19.4), Nexo ❌.

Shared: animated/emissive textures, custom **elytra** (`asset_id`, chest slot).

**Nexo pulls ahead on entity equipment** (mirrors Mojang's fast-moving equipment surface):
- **Mob armor:** wolf (`WOLF_ARMOR`), horse (`DIAMOND_HORSE_ARMOR`), llama, nautilus.
- **Custom saddles (1.21.5+):** camel/donkey/horse/mule/pig/strider/etc.
- **Harness (1.21.6+):** Happy Ghast body slot.

**🎯 Target spec:** Component-based armor (any material) as default; trim-based as a documented option; animated/emissive layers; elytra; **mob armor (wolf/horse/llama)**, **saddles**, and **harness** to match Nexo's modern coverage. Track each new equipment slot Mojang adds.

---

### 3.8 HUDs & on-screen UI

**This is ItemsAdder's standout category.** ItemsAdder ships a **native HUD system** (`hud.yml`):
- Element types: **IMAGES**, **TEXT**, **BAR** (progress bars for mana/stamina/health/water).
- `scale`, `x`/`y` positioning, **per-world** show/hide, **conditions**, **animated** images.
- Renders via a custom font + per-player packets — the only one of the three with a real Java-side HUD engine.

**Oraxen** has **no native HUD page**; HUDs are assembled from **glyphs + shifts** rendered into the boss bar / action bar / scoreboard, driven by PlaceholderAPI. Workable but not a first-class system.

**Nexo** has **no native Java HUD**; only **Bedrock** vanilla-HUD retexture (via the Scaffolding addon: hotbar, hearts, hunger/armor/air/XP bars). Custom Java HUD *plugins* (e.g. MythicHUD) are explicitly unsupported.

**🎯 Target spec:** A **first-class Java HUD system** (this is a genuine competitive gap — even Nexo lacks it). Element types: image, text, progress bar; positioning + scale; per-world/conditional; animated images; PlaceholderAPI-driven values.

---

### 3.9 Glyphs / fonts / emoji

Effectively equal across all three: textured unicode glyphs injected into Minecraft fonts, usable in chat/names/lore/scoreboards/boss bar/action bar/signs/books.
- Basic glyph, **multi-bitmap** (one sheet → many), **animated** (sprite sheet / GIF), **grid** (large texture across chars), **reference** (alias).
- **Shift tags** (`<shift:N>`) for pixel-precise layout (the foundation of custom GUIs).
- **Emoji mode** (`is_emoji`) with `/emojis` browser, permissions, tab-complete.
- MiniMessage integration (`<glyph:id>`, `:colorable`, `:shadow`, ranges).

**Text effects (GLSL shaders, 1.21.4+):** Oraxen ✅ (rainbow/wave/shake/pulse, GLSL fragments, 8 id slots), Nexo ✅ (addon by Akis: rainbow/wobble/jump/blinking/combos via `rendertype_text` hex), ItemsAdder ⚠️.

**🎯 Target spec:** Full glyph system (basic/multi-bitmap/animated/grid/reference), shift tags, emoji mode with browser+permissions, and **GLSL text effects**. This is table-stakes parity.

---

### 3.10 GUIs / menus / dialogs

- **Built-in item browser GUI** — all three (`/ia`, `/oraxen inv`, `/nexo inv`), configurable categories/slots/icons/titles; Nexo supports FILE vs DIRECTORY layouts and custom nav/search icons.
- **Free-form interactive GUI builder** — **none** ship a full DeluxeMenus-style builder. Oraxen and Nexo build GUI *backgrounds* from glyph/font images; the click logic comes from other plugins. ItemsAdder ⚠️ likewise.
- **Server-side dialogs (1.21+, Nexo-only)** — full wrapper around Mojang's dialog system: `NOTICE`/`CONFIRM`/`LIST`/`MULTI`/`LINK` dialog types; `MESSAGE`/`ITEM` bodies; `TEXT`/`BOOL`/`NUMBER`(slider)/`SINGLE` inputs; static actions (`OPEN_URL`/`RUN_COMMAND`/`SUGGEST_COMMAND`/`CHANGE_PAGE`/`CLIPBOARD`/`SHOW_DIALOG`/`CUSTOM`) + dynamic ones (`DYNAMIC_RUN_COMMAND`/`DYNAMIC_CUSTOM` using input values). Great for NPC forms/admin panels with no client mod.

**🎯 Target spec:** Configurable item browser GUI; **a real interactive GUI/menu builder** (a genuine shared gap — all three punt on this); **server-side dialogs** (Nexo-only, easy differentiator). A GUI builder + dialogs would be a strong open-source differentiator.

---

### 3.11 Recipes

- All three: **shaped**.
- **Oraxen** + **Nexo**: shapeless, furnace, **in-game recipe builder** (`/recipes builder`).
- **Nexo** additionally: **blasting, smoking, stonecutting, smithing (addon), brewing**; disable vanilla recipes in `disabled_recipes.yml`; ingredients/results accept `nexo_item`/`minecraft_type`/`tag`/`crucible_item`/`mmoitems`.
- **ItemsAdder**: shaped ✅; shapeless/furnace ⚠️; others ⚠️.

**🎯 Target spec:** **All** vanilla recipe types (shaped, shapeless, furnace, blasting, smoking, stonecutting, smithing, brewing); an in-game builder; disable-vanilla; custom-item ingredients/results.

---

### 3.12 Sound, music & visuals

- **Custom sounds** — all three (`.ogg`, mono; Nexo can **replace/mute vanilla sounds**).
- **Music discs / jukebox songs** — all three (Nexo auto-generates the jukebox-song datapack; configurable comparator output/range/duration).
- **Custom paintings (1.21 datapack)** — **ItemsAdder ✅** and **Nexo ✅** (animated via `.mcmeta`); **Oraxen ❌**.
- **Custom particles** — weak everywhere (Oraxen via Aura; ItemsAdder ⚠️ partial; Nexo none native).

**🎯 Target spec:** Custom sounds (incl. replace vanilla), music discs with auto-datapack, **custom paintings** (animated), and a small particle API on items/furniture.

---

### 3.13 World & environment

- **Ore / world generation** — **ItemsAdder only.** Define custom ores by `block`, Y-range, biomes, dimension, vein size, frequency; ItemsAdder populates them into the world. A real differentiator.
- **Custom liquids / fluids** — **ItemsAdder only.** Flowing custom fluids (molten metals, custom liquids). A real differentiator.
- **Vehicles / mounts (native driving)** — none of the three ship native vehicle physics; all rely on **ModelEngine** + furniture seats (+ Nexo's Bedrock mount bridge).
- **Pets / companions (native)** — none (nearest analogs: Oraxen's Hat/Soulbound/Aura).
- **Custom mobs (native AI)** — none; all delegate to **ModelEngine / MythicMobs** (rendering + placement only).
- **Loot tables** — Nexo ✅; Oraxen 🟡; ItemsAdder ⚠️.

**🎯 Target spec:** **Native ore/world generation** and **custom fluids** are open opportunities (only ItemsAdder has them, and only partially). Mobs/vehicles/pets are intentionally delegated — partner with ModelEngine/MythicMobs rather than reimplementing AI.

---

### 3.14 Resource-pack generation & delivery

| Concern | Best-in-class | Notes |
|---|---|---|
| Pack generation from assets | All three | Auto `custom_model_data`/ItemModels, model JSON, font injection |
| External pack **merge** | Nexo | `external_packs/` ZIP/dir import with conflict resolution in `paper.json`/`sounds.json`; Oraxen merges `assets/` |
| **Obfuscation** | Nexo (NONE/SIMPLE/FULL + cache) | Prevents asset theft; Oraxen has tamper protection |
| **PackSquash** | Nexo | Drop-in binary for optimized packs |
| **Hosting backends** | Nexo | Polymath, self-host, LobFile, **S3 (R2/Hetzner/AWS)**, pluggable SPI |
| **Proxy pack-dedup** | Nexo (NexoProxy), Oraxen (PackLayer) | Avoids duplicate sends across backend servers |
| **Pre-join send** | Oraxen + Nexo | 1.21.7+ config-phase send |
| **MultiPack** (extra optional packs) | Nexo | Per-player toggle GUI, TOGGLE/CYCLING slot actions |
| **Bedrock** | Nexo (Scaffolding) | Full Java→Bedrock pack conversion + Geyser deploy |

**🎯 Target spec:** Asset-driven pack generation; **external pack merge** with conflict resolution; **obfuscation** + **PackSquash**; multiple hosting backends incl. **S3**; **proxy dedup**; **pre-join** send; pack layering; a plugin SPI for custom hosts.

---

### 3.15 Integration & developer API

- **PlaceholderAPI** — all three.
- **Java API** — all three (ItemsAdder `CustomStack`/`CustomBlock`/`CustomFurniture`; Nexo publishes Maven coords + JavaDocs with item *update callbacks*).
- **ModelEngine** — all three (merge RP + bind blueprints to furniture/mobs).
- **MythicMobs / MythicCrucible** — Oraxen (Crucible), Nexo (drops/equipment `nexo:` prefixes + `crucible_item`).
- **MMOItems** — Nexo (import as item bases + recipe/drop references).
- **BlockLocker** — Oraxen + Nexo (CONTAINER/DOOR/ATTACHABLE).
- **Anti-cheat bridges** — Nexo (Vulcan, Spartan; community Polar) — important because furniture hitboxes false-positive fly checks.
- **Migration converters** — Nexo ships converters **from Oraxen and from ItemsAdder** (huge for adoption).

**🎯 Target spec:** PlaceholderAPI; a documented public API with Maven coords; ModelEngine/MythicMobs/MMOItems hooks; BlockLocker; **anti-cheat bridges** (don't skip these — furniture + custom blocks trigger anti-cheats); and — critically for adoption — **import converters from all three** (especially Nexo's Oraxen/ItemsAdder converters prove this is the #1 adoption lever).

---

## 4. Unique differentiators (what each plugin owns)

### ItemsAdder
- **Native HUD system** (IMAGES/TEXT/BAR) — the only one of the three.
- **Custom liquids / fluids** — unique.
- **Ore / world generation** — unique.
- **Custom paintings** (shares with Nexo).
- **Modular "items packs"** model — most drop-in-friendly content packaging.

### Oraxen
- **Richest mechanic catalog** — combat spells (`spear_lunge`, `thor`, `lifeleech`, `bleeding`, `witherskull`, `fireball`, `energyblast`, `knockback_strike`) and farming spells (`bigmining`, `smelting`, `bedrockbreak`, `watering`, `bottledexp`, `harvesting`).
- **ShapedBlock** (stairs/slabs/doors/trapdoors/grates/bulbs) built in.
- **GLSL text effects** built in.
- Four combinable **appearance systems** on 1.21.4+.
- Public **custom-mechanic factory/listener API**.

### Nexo
- **Most modern** — 1.21.4+ ItemModels, full data-component surface (incl. 1.21.11 weapon/kinetic-weapon), display-entity-only furniture.
- **Server-side dialogs** (1.21+).
- **Custom paintings** + custom music discs.
- **Mob armor / saddles / harness** (wolf/horse/llama/nautilus, Happy Ghast).
- **MultiPack**, S3/PackSquash/obfuscation.
- **Bedrock** via Scaffolding (Geyser).
- **NexoProxy** (Velocity) + glyph tags in proxy plugins.
- **NCI** client Fabric mod for the Creative inventory.
- **Migration converters** from Oraxen **and** ItemsAdder.

---

## 5. Recommended scope for the open-source alternative

### Non-negotiable MVP (matches all three)

> ⚠️ **Provenance note:** this section claims to target the *union* of all three plugins, but
> the ItemsAdder column is model-knowledge-only (see the provenance box at the top). Entries
> whose inclusion rests on an unverified ItemsAdder row (backpack mechanic, shaped-block
> depth, tridents, mob armor) are built because *Oraxen/Nexo* have them or because they're
> architecturally natural — not because ItemsAdder is proven to. See
> [`architecture/parity-reconciliation.md`](architecture/parity-reconciliation.md) for the
> spec-by-spec mapping and what's explicitly dropped.
1. **Item system** — YAML items with full properties; auto model generation + hand-authored models; **1.21.4+ ItemModels** (don't ship on `custom_model_data` alone).
2. **Data components** — version-aware pass-through of the full component surface.
3. **Resource pack** — generation + external merge + self-host + S3 + obfuscation + PackSquash; pre-join send; proxy dedup.
4. **Glyphs** — basic/multi-bitmap/animated/grid/reference + shifts + emoji mode + GLSL text effects.
5. **Custom blocks** — NoteBlock + StringBlock + ChorusBlock + technical, with the full shared property set + shaped blocks.
6. **Furniture** — display-entity-first; hitboxes; seats; lights; storage; evolution; rotation; limited placing; text displays.
7. **Armor** — component-based (any material) + elytra.
8. **Mechanics** — generic **Event + Condition (SpEL) + Action** on items/blocks/furniture; commands, durability, food, soulbound, armor-effects, backpack, harvesting.
9. **Recipes** — all vanilla types + in-game builder + disable-vanilla.
10. **Sounds/music** — custom sounds (incl. replace vanilla) + music discs + **custom paintings**.
11. **API + PlaceholderAPI + ModelEngine/MythicMobs hooks + anti-cheat bridges.**

### Strong differentiators (where even the incumbents are weak)
12. **Native HUD system** (Java) — only ItemsAdder has this; build a better one.
13. **A real interactive GUI/menu builder** — *none* of the three ship one; this is a wide-open gap.
14. **Server-side dialogs** (Nexo-only) — easy to match, high value for NPCs/admin panels.
15. **Ore/world generation + custom fluids** — only ItemsAdder has these, partially.
16. **Mob armor/saddles/harness** — match Nexo's version cadence.
17. **Migration converters from ItemsAdder + Oraxen + Nexo** — the single biggest adoption lever.
18. **Bedrock (Geyser)** — match Nexo Scaffolding (hard, but a major market).
19. **Modern client bridge** (creative-inventory mod à la NCI).

### Intentionally delegate (don't reimplement)
- **Custom mob AI** → partner with ModelEngine / MythicMobs.
- **Vehicle physics** → ModelEngine + furniture seats.
- **Pets** → third-party.

---

## 6. Modern-MC foundation notes (build on these, don't fight them)

A new competitor should be *more* modern than Nexo, not less. Anchor on:

- **1.21.4+ item model definitions** (`assets/<ns>/items/<id>.json`, `item_model` component, `select`/`range_dispatch`/`composite`) — supersedes `custom_model_data` predicate overrides; gives unique IDs with zero conflicts. Build the ItemModel builder **first**.
- **1.20.5+ data components** — the canonical way to express food, tools, durability, equippable, glint, etc. Expose the *entire* surface, version-tabbed.
- **1.19.4+ display entities** (`item_display`, `text_display`, `interaction`, `block_display`) — the only sane furniture foundation. Use `interaction` for hitboxes, `item_display` for the model, `text_display` for labels. Don't build new furniture on item-frames/armor-stands.
- **1.21.2+ `equippable.asset_id`** — armor/elytra/mob-armor/saddle/harness all flow through this one component. Track each new equipment slot.
- **1.21+ server-side dialogs + custom paintings + jukebox songs** — all datapack-driven features worth wrapping in YAML.
- **Paper-native scheduling + Folia region/entity schedulers** — design async-first; target **Folia** from day one (Oraxen + Nexo do; verify ItemsAdder).
- **Proxy pack dedup** — design for Velocity/Bungee from the start.

---

## 7. Open questions to verify (ItemsAdder column)

Because the live ItemsAdder fetches failed, confirm against `wiki.itemsadder.com`:
- Backpack item mechanic — native or third-party?
- Shaped blocks (stairs/slabs/doors) — native depth?
- 1.21.4+ ItemModel support + data-component pass-through depth.
- Custom tridents as a first-class type.
- Folia support status.
- Proxy pack-dedup / pre-join send availability.
- Custom mob armor (horse/wolf) support.

These do **not** change the recommended scope above (which targets the **union** of all three), but they sharpen the ItemsAdder column of the matrix.
