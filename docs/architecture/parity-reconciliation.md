# Parity Reconciliation — every target spec → its owner

> **Purpose.** [`feature-parity.md`](../feature-parity.md) is the requirements doc; the
> architecture docs are the design. This table is the contract between them: every
> **🎯 Target spec** in the parity doc maps to the section that owns it — or to an explicit
> "dropped because". If a target spec has no owner, that's a bug in the architecture docs.
>
> **Provenance caveat** (parity doc §⚠️): the ItemsAdder column is model-knowledge-only.
> MVP items below that rest on ⚠️ rows are marked; if verification overturns a row, only the
> marked entries move.

| Parity § | Target spec | Owner | Milestone |
|---|---|---|---|
| 3.1 | YAML items, full properties; auto + hand-authored models; 1.21.4+ ItemModels; per-context models; auto-updater; templates | `runtime-systems.md` §3 (+ model tree builder); `plugin.md` §4 | M1 |
| 3.2 | Version-aware pass-through of the full component surface | `runtime-systems.md` §3.1 + `plugin.md` §6 (platform layer) | M1, expands per release |
| 3.3 | Shields/bows/crossbows/rods multi-stage; first-class trident; elytra via equipment | `runtime-systems.md` §3 model tree (`condition`/`range_dispatch` nodes) + §12 | M1 (basic), M7 (full) |
| 3.4 | ECA backbone + built-in mechanics library + public registration API | `runtime-systems.md` §2; `plugin.md` §3 | M2 (ECA v1), M7 (full catalog) |
| 3.5 | NoteBlock/StringBlock/Chorus/technical + shared props + shaped blocks | `runtime-systems.md` §4 | M2 |
| 3.6 | Display-first furniture: hitboxes, seats, lights, storage, evolution, rotation, text displays; states/connectable/placements/beds/sliding doors as differentiators | `runtime-systems.md` §5 (core in M2; differentiator features M7) | M2, M7 |
| 3.7 | Component armor default; trim option; animated/emissive; elytra; mob armor/saddles/harness | `runtime-systems.md` §12 | M7 |
| 3.8 | First-class Java HUD system | `runtime-systems.md` §7 | M6 |
| 3.9 | Full glyph system + shift tags + emoji + GLSL effects | `runtime-systems.md` §6 | M3 |
| 3.10 | Item browser GUI; **real interactive menu builder**; server-side dialogs | `runtime-systems.md` §8 (+ in-game builder = menus+dialogs dogfooding) | M6 |
| 3.11 | All vanilla recipe types + in-game builder + disable-vanilla | `runtime-systems.md` §9 | M1 (3 types), M7 (all + builder) |
| 3.12 | Custom sounds (+replace), music discs, custom paintings, small particle API | `runtime-systems.md` §10 | M3 (sounds/discs/paintings), M7 (particles) |
| 3.13 | Ore/worldgen + custom fluids (open opportunities); mobs/vehicles delegated | `runtime-systems.md` §11; delegation per master §12 | M8 |
| 3.14 | Asset-driven generation; external pack merge; obfuscation + PackSquash; hosting backends incl. S3; proxy dedup; pre-join; layering; hosting SPI | `plugin.md` §4.4 (merge + post-steps); master §8.4 (delivery) | M1 (self-host), M7 (rest) |
| 3.15 | PlaceholderAPI; public API + Maven; ModelEngine/MythicMobs/MMOItems hooks; BlockLocker; anti-cheat bridges; **import converters from all three** | master §10 (`api/`, `integrations/`, `importers/`) | M7 (importers earlier if demand) |

**External pack merge (3.14) detail** — owned by the compile stage: `pack/external/` input
folder, conflict resolution in `paper.json`/`sounds.json` merges, quarantined on
unresolvable conflicts. Ships M7; the compile-stage seam exists from M1 so it's additive.

## Dropped, with reasons

| Parity item | Dropped because |
|---|---|
| Item-frame / armor-stand furniture as supported methods | D10 modern floor — display entities are strictly better on 1.19.4+; converters map old furniture to display entities |
| Legacy shader armor (Optifine/leather layers) | Breaks Iris/CIT; component path (1.21.2+) covers the need on our floor |
| `custom_model_data`-predicates as a primary authoring path | 1.21.4+ ItemModels supersede it on our floor; appears only on importer paths for migrated content |
| Bedrock/Geyser at launch | Real but large; reserved as a `pack/` conversion step (M8), not M1 scope |
| Creative-inventory client mod (NCI-style) | Client mod is its own distribution/maintenance surface; M8 stretch after the core proves out |
| Native mobs/vehicles/pets AI | Permanently delegated (master §12) — partner, don't reimplement |

## Contradictions resolved by this reconciliation

- **"In-game recipe builder"** (3.11) vs master §9.5 ("commands operate, the editor
  authors"): resolved — the in-game builder is *menus + dialogs composed from existing
  primitives* (dogfooding), not a parallel command surface. Owner: `runtime-systems.md` §9.
- **D10 vs legacy clients**: ItemModels are 1.21.4+ *server*-side; older *clients* via
  ViaVersion still receive the same pack (models are client-version-agnostic in output
  terms). The floor is about server features, and the docs now say so consistently
  (master §3 D10 consequence column).
- **Fabric mod / NCI**: parity lists it as a Nexo differentiator; the architecture keeps it
  as M8-optional, explicitly out of the plugin's core commitments (master §12 "in" list
  doesn't include it).

## Open verification items (parity §7)

The seven ItemsAdder-uncertainty items (backpack, shaped-block depth, ItemModel support,
tridents, Folia, proxy dedup, mob armor) do not change this mapping: each is either owned
regardless (Folia — D8; proxy dedup — master §8.4; mob armor — §12) or is a ⚠️-row whose
inclusion in M7's mechanics catalog is unaffected by ItemsAdder's exact behavior (we build
the catalog for its own sake, not to mirror a rival's feature list).
