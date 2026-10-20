# Materione Roadmap — M0 → M8

> **What this is.** The route from an empty repo to the architecture in
> [`architecture.md`](architecture.md). The architecture is the *destination*; this is the
> *order of travel*. Each milestone is a shippable state — a server owner can run it, a
> contributor can see where their work lands, and nothing lands half-built waiting for a
> later milestone.
>
> **Rule:** every milestone respects the load-bearing decisions D1–D11 from day one (files
> canonical, one pipeline, registries, fail-soft). Milestones add *capability*, never
> re-architecture. If a milestone seems to require breaking a decision, the milestone is
> wrong — stop and amend the architecture doc first.
>
> **Tracking lives on GitHub** (since 2026-10-09): open work is
> [issues](https://github.com/xef5000/materione/issues) under milestones M5–M8, viewable
> on the [Materione Roadmap board](https://github.com/users/xef5000/projects/1); open
> questions from architecture §14 are `kind:decision` issues. This document keeps the
> milestone definitions, the shipped record (as-built notes below), and the
> cross-milestone rules — it does not track progress.

---

## Why the first release is deliberately small

The parity doc's "non-negotiable MVP" is the *union of three mature plugins* — a cathedral.
As a first release it would be unfundable and untestable. The actual adoption sequence for a
server owner is: **(1)** it works and doesn't break my server → **(2)** my items look right
in game → **(3)** I can edit without YAML → **(4)** I can leave the incumbents. The roadmap
deliberately reorders steps (2) and (3): prove the spine (M0–M2), win on authoring early
(M3 — the web editor, extended to anywhere in M5), fill out the content surface in between
(M4), differentiate on GUIs (M6), then close parity gaps (M7–M8).

---

## M0 — Skeleton & spine (weeks, not months) — ✅ shipped 2026-08-20

**Goal:** the architectural invariants exist and are enforced before features do.

- Gradle multi-module skeleton with the module boundaries of master §10 and the ban-list
  tests (no Bukkit scheduler outside the gateway, no versions outside `platform/`).
- Platform probe + capability descriptor (master §8.2) with one implementation (current
  Paper line).
- Registry machinery (master §5/D5) with self-description metadata — before any content type
  exists.
- Diagnostics bus with stable codes; quarantine as runtime state.
- Settings + storage dirs per master §7.1; migration chains stubbed from day one.
- `/materione` command root: `doctor`, `reload`, `version`.
- CI: unit + headless-Paper harness + the golden-file fixture corpus (empty at first, grows
  every milestone).

**Exit:** plugin loads on Paper, enforces nothing yet, but every invariant above is tested.

**Shipped (as-built notes; details in [`architecture/build.md`](architecture/build.md)):**
modules `api, core, platform, plugin` + 7 skeleton modules + `testing`, `harness`,
`fixtures/`, `webui/`; ban tests R1–R5 proven by planted violation (R1 hardened with a
`getScheduler` entry-point predicate — a discarded call leaves no package dependency);
diagnostics `MTR-0001..0006`; the headless-Paper harness boots real Paper 1.21.4-232 and
asserts the `MATERIONE-*` log markers, including a fail-soft variant with a broken
`settings.yml`. **Deviations:** FoliaLib dropped (native Paper schedulers; no shaded deps at
all at M0); `build/` renamed `build-logic/`; assembly lives in a new `plugin/` module.
Project license still open (master §14) — deliberate, not forgotten.

## M1 — Content pipeline & items (the spine carries real weight) — ✅ shipped 2026-08-22

**Goal:** define an item in YAML, see it in game with a custom model.

- Pipeline stages (scope → parse → validate → graph → allocate → compile → apply) with
  fail-soft semantics throughout (D6).
- Pack layout on disk (master §7.2); lossless round-trip parsing (master §5.6) — **this is
  the moment the parser contract is set; changing it later is the most expensive refactor in
  the project**.
- Allocation table in `storage/`, append-only, tombstones, audited rebuild (§6.3).
- Item content type: identity via PDC, data components via platform layer, ItemModel
  generation (auto-model + hand-authored), templates/inheritance, item auto-updater.
- Delivery: self-host HTTP backend only; versioned URL; send on join.
- Recipes: shaped + shapeless + furnace (the rest wait for M7).

**Exit:** a 50-item fixture pack loads, hot-reloads, and survives fault-injection tests.

**Shipped (as-built notes; details in [`architecture/build.md`](architecture/build.md)):**
the lossless YAML layer is hand-rolled over SnakeYAML (byte-identical round-trips proven per
fixture, CRLF-aware, one-field-touch → one-line diff); the pipeline runs parse → validate →
graph in `core` with fail-soft quarantine throughout, compile in `pack`, and the shadow-swap
apply through the scheduling gateway with rollback. The pack emitter is **creative 1.7.3**
(MIT, shaded + relocated — the documented amendment to M0's zero-shaded-deps state; the D7
determinism guarantee is ours: sorted hand-rolled JSON, creative's epoch-0 ZIP timestamps,
sha-compare no-op detection). Delivery is the self-host backend on creative's
`ResourcePackServer` (versioned cache-busting URLs, 4-version retention). Diagnostics
`MTR-0007..0016`; ban rule R6 (validators pure) has real subjects. Exit gate proven live:
the harness boots real Paper with sample + a generated 50-item corpus pack (53 live items),
runs the hot-reload sequence (add → 54, broken file → still 54 + `MTR-0007`, remove → 53),
and the stage-pair fault-injection matrix plus §9 perf budgets run in CI (`plugin` module).
Fault injection found and fixed a real fail-soft gap (a throwing content-type emitter could
crash a whole reload). Deferred with owners: targeted reload < 1 s and fingerprint skipping
(M2), cold item detect (M4), inventory-scan throttling (M2 mechanics).

## M2 — Blocks & furniture (the world-facing systems) — ✅ shipped 2026-08-23

**Goal:** place and break custom things.

- NoteBlock + StringBlock + ChorusBlock + technical blocks; shaped blocks; host-physics
  suppression; chunk-PDC persistence; placement index + chunk-scan rebuild.
- Furniture: display-entity clusters, hitboxes, seats, lights, storage, rotation, limited
  placing, evolution; entity-cluster reconstruction from PDC.
- ECA engine v1: event taxonomy, context object, the universal expression engine, core
  actions, cooldowns. Mechanics: commands, soulbound, food, durability, backpack.
- Pre-apply reports, restart-required class, orphan policy — the full apply-stage honesty
  of plugin.md §4.5.

**Exit:** a fixture world with blocks and furniture survives reload, restart, and world copy.

**Shipped (as-built notes; details in [`architecture/runtime-systems.md`](architecture/runtime-systems.md)):**
the ECA engine v1 lives in a new Bukkit-free `behavior` module (new ban rule R7 keeps it
embeddable): parse-once expression engine over a typed AST, action SPI with universal
`chance`/`delay`, cooldown/charge rule options, fail-soft chain continuation
(diagnostics `MTR-0021..0023`), and plugin-side listener bindings for item/block/furniture
events with a PlaceholderAPI soft-hook. Blocks run the state-as-identity fast path (decoded
slot → precomputed per-host arrays; vanilla positions cost zero PDC reads) refined by
**chunk-PDC truth**: a decoded candidate must match the chunk record or it is quarantined
(`MTR-0024`) — this is what makes the disposable allocation table safe, proven live by the
corruption-pair fault injection (table deleted + sort order changed → mismatch flagged,
record wins, state resynced). The placement index really is rebuildable: chunk-load events
and a boot pass reconcile it from chunk PDC (world-copy survival). Physics suppression
probes Paper's block-update flags with event-based fallbacks (`MTR-0025`); orphans are
keep-flagged with `dev sweep`/`inspect` surfacing (`MTR-0026`). Shaped blocks ship as
waxed-cut-copper stairs/slabs (PDC-identified). Furniture is real persistent entity clusters
(ItemDisplay + Interaction/barrier hitboxes incl. waterlogged barriers + seats via the
passenger API + LIGHT cells + TextDisplay labels + persistent storage) reconstructible from
PDC, with rotation snap, floor-only placing and lazy timestamp-based evolution. Mechanics
register through the public `mechanics` registry seam exactly like future addons: commands,
soulbound, food (build-time vanilla FoodComponent — no Consumable API at our floor),
durability, backpack; unknown mechanics skip with `MTR-0027`. Inventory scans are throttled
to one per player per second. Apply honesty: the pre-apply report is computed before the
swap and logged; a reload counts as no-op only when the *runtime* diff is empty (identical
pack bytes alone proved insufficient live); restart staging runs at boot (`MTR-0028/0029`,
producers wait for M4); fingerprint caching makes OnePack/FileSet scopes real with a
drift-based expansion guard. Diagnostics `MTR-0019..0029`. Exit gate proven live by the
five-boot `PaperWorldPersistenceIT`. Fault injection found and fixed two real gaps: YAML 1.1
parses bare `on:` keys as booleans (normalized in every behavior map) and WARN diagnostics
never reached the server log. **Deviations:** stringblocks ship dry — tripwire is not
waterloggable at the API floor (parity §3.5 aspiration, owner M7); content directories are
plural (`items/`, `blocks/`, `furnitures/`). Deferred with owners: progressive mining/
hardness, directional variants, log stripping, furniture states/connectables/wall-roof/
beds/sliding doors, model_tree builders, full mechanics catalog → M7; glyphs/sounds/discs/
paintings plus restart-required producers → M4; menus/HUD/dialogs → M6.

## M3 — The web editor v1 (authoring without YAML)

**Goal:** a T2/T3 user builds content with zero YAML knowledge (master §2's promise, tested).

- Direct mode first (embedded HTTP/WS, loopback) — the editor is usable before any cloud
  exists.
- Editor SPA v1: dashboard, content browser, item/block/furniture editors (forms from
  registry self-description), diagnostics center, document store with revisions.
- Live preview loop (save → targeted reload → in-game).
- Blockbench integration v1 (pinned build + our plugin + asset save).

**Exit:** a new user creates an item with a custom texture, in the browser, without docs.

**Shipped (as-built notes; details in [`architecture/build.md`](architecture/build.md)
and the [editor quickstart](editor-quickstart.md)):** the transport lives in `web/` on an embedded **Undertow**
listener (Jetty's 12.x websocket API churn ruled it out empirically; shaded + relocated,
the M3 shading amendment) serving the SPA from classpath resources, the capability route
(`GET /s/<code>`) and one WebSocket channel. The wire protocol is exactly web.md §4/§5:
canonical JSON, Ed25519 sign-after-encrypt frames with strict per-direction sequence
numbers, AES-256-GCM bodies, P-256 ECDH session keys via HKDF-SHA256, key-confirmation
digest over a transcript that excludes `confirm`, and the wrapping key riding only in the
URL fragment — all JDK JCA server-side, all WebCrypto browser-side, pinned together by
golden fixtures (`fixtures/protocol/editor-protocol-v1.json`) that BOTH sides test against.
Trust is in-game: `/materione editor open` prints the capability URL, the hello surfaces a
clickable approve/deny prompt keyed by nonce prefix (`MTR-0030..0037`). The document store
(core.document) exposes browse/edit ops with per-document revision journals in
`storage/revisions/`, external-edit detection via content-hash drift journaled as its own
revision, stale-write conflicts rejected visibly (`MTR-0034`) instead of clobbering, an
audit log at `storage/audit.log`, and — the authoring trick — `edit.setScalar`: forms patch
single fields through the lossless YAML layer so comments survive byte-for-byte while raw
YAML editing stays available beside them. Assets upload/list/delete under a 2 MiB v1 cap
riding inside sealed frames (the §4.3 manifest+chunk flow is relay-transport machinery and
lands with M5). The SPA is Svelte 5 + Vite + TypeScript, embedded into the shaded jar by
the Gradle node toolchain (pinned Node 22, hermetically provisioned); dev loop via Vite
proxy. The live preview loop closes in CI: the harness handshake scenario creates an item
through the editor API, watches the targeted reload take it in-game
(`dev stack sample:editor_gem`), receives the `push.reload` event, uploads a texture, and
asserts the whole save→applied round trip stays **under 2 s** (plugin.md §9). Ban rule R8
("no wire protocol outside web/") proven by planted violation — including the discovery
that constant reads fold away and only method calls leave teeth. Exit criterion exercised
end-to-end live on Paper.

**Deferred:** Blockbench v1 (pinned web build + bridge plugin + model save-back) moves to
early M4 — it is UI-integration surface, independent of everything above, and the exit
criterion does not depend on it. External-file watcher → `document.changed` pushes also
wait for M4 (edits through the editor already push).

**M3 closeout (Blockbench + external-edit feed):** both deferred bullets landed anyway, and
the milestone is now complete. **Blockbench v1** ships the pinned, unmodified Blockbench
v5.1.6 web build inside the jar (`webui/public/blockbench/`, source recipe + pin in its
PIN.md — D9 honored: no patches, upgrades are pin bumps), opened as a workbench tab and
bridged strictly over postMessage: "save to Materione" exports the Java block/item model
JSON + referenced textures + the `.bbmodel` project source into the pack through
`assets.put`, with quick-attach scaffolding a new item or wiring `model:` onto an existing
one via `edit.setScalar`; external `.bbmodel` projects import into the embedded build for
further editing. `.bbmodel` sources are authoring state and never ship in pack.zip. The
**external-edit feed** is a WatchService pump over `content/packs/` (parked on an async
gateway thread, ~500 ms debounce): hand edits journal an *external* revision through
`DocumentStore.syncExternal` and reach connected editors as `push.document.changed` — the
explorer refreshes, clean tabs auto-reload, dirty tabs show a "changed on disk" banner
instead of clobbering. `editor_file_watcher: false` disables it (lazy hash-drift detection
keeps working). The harness gate drives both flows end to end on Paper: the hand edit's
push + journaled revision, and the exact save-back asset/setScalar sequence watched going
live in game.

**Workbench update (M3 closeout):** the SPA's first cut was, bluntly, a glorified YAML
editor — so it was rebuilt on a proper foundation. The shell is now a VS Code-style
workbench: activity rail, sidebar views, tabbed editors (documents, asset previews,
settings), a structured pipeline dock, and a status bar; design tokens live in one
`app.css` instead of copy-pasted hex. The Explorer is a real file tree over documents
and assets with right-click menus — scaffolded New Item/Block/Furniture/Recipe
(`edit.create`, curated starters pinned against the registry schemas by a plugin-module
test), rename/move across directories (`edit.move` — the moved file's journal opens
with a `move-from` entry and the old path's closes, so stale writes against either
fail honestly), delete, upload-here. Document editing is **form-first**: schema-driven
fields (enums, booleans, numbers, scalar lists as one-entry-per-line), texture/model
pickers fed by pack assets with inline upload (the picker hint is derived on
`ParameterSchema.uiHint()` so the knowledge stays in the schema), and an Advanced
section holding the raw YAML for what forms can't safely render — the full-file YAML
view remains one click away as a dev tool. `edit.setScalar` now patches whole lists
losslessly (`withScalar` renders block sequences replacing only the field's own item
lines). The **control center** (Server view) exposes identity + listener status,
manual full/per-pack reloads (`pipeline.reload` — fire-and-forget, outcome via
`push.reload`, always announced for manual triggers), the settings.yml editor
(hash-guarded, validated through `SettingsService.validateDraft` *before* any bytes
land on disk, honest restart note), and the audit trail. Diagnostics stream live
(`push.diagnostic` off the bus's subscribe seam) into a filterable center with
click-through to files. Two real protocol flaws surfaced under the new concurrent
pushes and were fixed: frames arriving during the handshake→channel handover were
dropped (now buffered in both the SPA transport and the harness client — a drop
desyncs the strict receive sequence), and server frame sealing wasn't thread-safe
(`EditorSession.sendFrame` now synchronizes seal+write so seq assignment matches
wire order). Component-level DOM tests (happy-dom et al.) were consciously skipped —
the pure-logic seams (tree building, signal fan-out, protocol fixtures) are
vitest-pinned; the UI itself is exercised live by the harness gate, which now also
drives create/move/assets.move/manual-reload/settings/audit end-to-end on Paper.

**M3 product-polish follow-up (shipped):** the default surface is now one-time project onboarding,
a resource-first Content catalog, and a schema-driven Item Studio; Explorer/YAML remain under
Advanced. Creating the first project and item is one `authoring.createResource` transaction with
one audit event and reload. Explicit registry metadata supplies human labels and the Identity,
Appearance, Gameplay, and Behavior sections instead of deriving UI from YAML identifiers.
Blockbench opens contextually from the item's model control; `authoring.saveModel` atomically saves
all model assets, the optional `.bbmodel`, `.materione/model-links.json`, and the owner field, with
rollback under revision conflict or failure. Editor changes still write canonical files and
hot-reload the complete runtime globally. Pack delivery now has a candidate/published release
channel: only the session-opening player receives candidate downloads, and explicit Publish
promotes exactly the hash that player previewed. Published content-addressed snapshots and an atomic hash pointer persist under
`storage/delivery/`; bounded retention pins both the published version and active
candidate. This extends master §8.4/§9.3/§9.4 and D1/D6, not the source of truth or pipeline.
Blocks and furniture can reuse the same metadata and transaction primitives.

**M3 content-workspace rebuild (shipped):** the first cut of that catalog was a flat card wall in
a sidebar-width column — strictly worse than Advanced Files for anyone with fifty items — so the
workspace was rebuilt around the way packs are actually browsed. Each rail view now owns the whole
main area; the pack switcher and release state moved to an app header; the tab host moved inside
Advanced Files, which keeps the tree, the generic schema form and raw YAML unchanged as the T4
surface. **Content** is a file explorer per registered type: type tabs from `browse.types`,
folders, breadcrumbs, details/icon layouts, search, keyboard navigation and the full
create/rename/duplicate/move/delete set for files and folders (`browse.folders`,
`edit.createFolder/deleteFolder/moveFolder`, `authoring.createResource` into a chosen type and
folder, `authoring.duplicateResource`). Opening an entry opens a **studio** — item, block,
furniture and recipe — built from the same registry metadata, including structured lists whose
shape the registry now declares (furniture hitboxes, seats, lights, evolution) and a real 3×3
crafting grid; a studio save is one `authoring.setFields` transaction carrying only changed fields,
and every key no control covers is reported with a link to Advanced Files rather than rewritten.
A built-in **pixel editor** (pure `pixels.ts` engine, vitest-pinned) makes a three-pixel texture
fix a click instead of a 3D session, and creating a texture for a resource that lacks one also
writes the owning field. Blockbench became contextual: no start screen, no format picker — the
resource's `.bbmodel` is loaded or a minimal project is synthesised for the pinned build's single
Java format with the texture attached, inside a workbench over the studio, and the bridge returns
a render cached under `.materione/thumbnails/` (excluded from the compiled pack exactly like
`model-links.json`). The layout invariants that failed the first time — shrinkable grid tracks, no
horizontal scrolling, no filler prose — are now asserted by a test over the surfaces' own CSS, and
a source-encoding test guards against the mojibake that the same pass introduced. A follow-up
found the same failure one layer down: full-height surfaces stacked their children on positional
grid rows, so an optional bar that was not rendered slid the filling child into an `auto` track —
which is how the embedded Blockbench came to render 150 pixels tall. They stack with flex now, and
the test covers that too.

## M4 — Glyphs, fonts, sounds, paintings ✅ shipped 2026-09-04

**Goal:** the full pack surface beyond items.

- Glyph system (basic/multi-bitmap/animated/grid/reference), shift tags, emoji mode.
- GLSL text effects. Custom sounds (+ replace-vanilla), music discs, custom paintings
  (restart-required class proven here).
- The text pipeline (glyph resolution in chat/lore/signs/books).

**Exit:** pack output covers every asset kind; golden files lock them.

**As built:** four new content types — `glyphs/`, `sounds/`, `paintings/`, `effects/` —
registered through the same registry path (D5) with schema-driven editor studios (glyph
preview shows the server-allocated codepoint; audio and painting previews render inline).
Codepoints come from a new consecutive-run allocation (`AllocationTable.range`, kind
`glyph-codepoint`, origin 42000) — a deliberate divergence from Nexo/Oraxen, which write
the char back into the user's YAML; materione's table is the durable record and hand-authored
files stay allocation-free. Every generated font carries the shift space provider
(power-of-two advances, U+E10x/E11x), so `<glyph:id>`, `<g:id>`, `<shift:N>` and `:placeholder:`
resolve anywhere the text pipeline renders — chat decorate, signs, books, anvil renames, item
names/lore — with per-glyph permission gating and pack-acceptance awareness in chat. Sounds
group per namespace into `sounds.json`; `replace_vanilla:` entries ride the vanilla-override
merge seam; `jukebox:` sections and painting variants register at boot — through Paper's
registry events on 1.21.6+ (the Nexo model: nothing written into the world) and through a
deterministic `datapack.zip` deployed atomically to the world's datapacks folder on the
1.21.4/1.21.5 floor — the first producers of the M2 restart-staging ledger (MTR-0028/0029
prove the class).
Music discs are items with `jukebox_song:` (the component applies fail-soft until the datapack
loads). Goldens lock the full media pack and datapack bytes; the harness drives glyphs through
the live editor loop and asserts every staged artifact on real Paper.

**As built (shader-injection slice):** `effects/` compiles into the injected
`rendertype_text` vertex-shader ladder — per-version overlays keyed on each effect's durable
signal color (runtime-systems §6). Use `<effect:pack:id>…</effect>` in any resolved text;
`/materione dev effect <pack>:<id> <text>` is the in-game test. Remaining deferred here:
animated glyphs emit all frames and render frame 1; per-surface frame cycling arrives with
M6 HUDs.

## M5 — Relay mode & the cloud (zero-config editing anywhere)

**Goal:** `/materione editor` works from behind NAT.

- Payload store + WS relay Workers (manifest+chunk flow, content addressing, quotas).
- The full handshake (§4.1 of web.md): fragment-key blob, ECDH, key confirmation, encrypted
  signed sequenced frames, in-game trust, workstation whitelisting.
- Editor served centrally; direct-mode auto-preference on LAN.
- Website (`materione.dev`) with docs rendered from the repo.

**Exit:** an admin on shared hosting edits their server through the public editor URL.

## M6 — Menus, dialogs, HUDs (the GUI differentiators)

**Goal:** the three gaps none of the incumbents fill well (master §5 differentiators).

- Menu builder (chest + free-form canvas layouts, pagination/search, ECA click bindings).
- Server-side dialogs (1.21+ wrapper, dialog.submit → ECA).
- Native HUD system (image/text/bar layers, bindings, per-world conditions).
- In-game content browser GUI (categories, search, get-item).

**Exit:** a datapack-free "server GUI" — menus + dialogs + HUDs built entirely in Materione.

## M7 — Parity close-out (the long tail)

**Goal:** everything in the parity MVP list that M1–M6 didn't cover.

- All recipe types + in-game recipe builder; loot tables.
- Armor: component path, mob armor/saddles/harness (capability-gated).
- Mechanics catalog: combat + farming spells (the Oraxen list) as canned ECA.
- Importers: ItemsAdder → Oraxen → Nexo order (biggest audience first).
- Delivery extras: S3/R2, proxy dedup, pre-join send, obfuscation/PackSquash steps.
- Integrations: PlaceholderAPI, ModelEngine, MythicMobs, anti-cheat bridges.

**Exit:** an incumbent user imports their pack and cannot tell what's missing.

## M8 — Differentiators & scale (post-parity)

Worldgen/ores + custom fluids · network mode (authority model, master §8.5) · Geyser/Bedrock
· cross-server state adapters · community pack hub (decision pending, master open question
#6) · addon SDK public docs · the client creative-inventory mod (à la NCI).

---

## What is explicitly NOT in any milestone

Mob AI, vehicle physics, pets — delegated permanently to ModelEngine/MythicMobs (master
§12). Legacy-server support — permanently abandoned (D10). Item-frame/armor-stand furniture
— display entities only.

---

## Cross-milestone rules

1. **Docs move with code**: a milestone isn't done until the user-facing docs and the
   architecture docs reflect what shipped.
2. **The fixture corpus only grows**: every bug found in the wild becomes a fixture.
3. **Performance budgets (plugin.md §9) are enforced from M1**, not retrofitted.
4. **Every milestone ships to real servers** (even alphas) — the fail-soft architecture is
   only proven by hostile input.
