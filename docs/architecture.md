# Materione — High-Level Architecture

> **Status:** Foundation document. This defines *what the product is, how it is shaped, and why* —
> before any implementation planning. Companion documents: [`idea.txt`](idea.txt) (the problem),
> [`feature-parity.md`](feature-parity.md) (what must be built to compete).
>
> **Subsystem documents** (one level more technical, each tracing back to the decisions below):
> [`architecture/plugin.md`](architecture/plugin.md) — the plugin: lifecycle, threading,
> registries, pipeline internals, persistence, testing ·
> [`architecture/web.md`](architecture/web.md) — the Cloudflare Workers ecosystem: relay
> services, wire protocol, crypto, editor SPA, self-hosting ·
> [`architecture/runtime-systems.md`](architecture/runtime-systems.md) — per content-type
> runtime: items, blocks, furniture, ECA engine, HUDs, menus, worldgen, armor, interop.
>
> **Not in scope here:** class design, interface-vs-abstract decisions, library API choices,
> implementation order, timelines. Those follow *after* this document is agreed.

---

## 1. The thesis, restated as an architecture constraint

Every incumbent (ItemsAdder, Oraxen, Nexo, DeluxeMenus, BetterHUD…) solves **one** problem and
exposes it through **its own** YAML dialect and command set. A server owner assembling a server
must learn N syntaxes and then wire the plugins together with glue (PlaceholderAPI, events,
command chains). The cost of building a server is dominated by **translation and wiring, not by
the content itself**.

Materione's claim is that the *unified authoring surface* is the product. Therefore:

> **Architecture constraint #1 — Unification is load-bearing.**
> Items, blocks, furniture, glyphs, HUDs, menus, dialogs, recipes, sounds, armor, and paintings
> are not features sitting next to each other. They are *typed resources in one content model*
> that reference each other freely and share one behavior system. If any content type ends up
> with its own private config format, its own action syntax, or its own reload path, the
> architecture has failed — we would have rebuilt the N-plugin problem inside one JAR.

> **Architecture constraint #2 — The web editor is the primary authoring surface, not a wrapper.**
> The incumbents are YAML-first because they predate any alternative. We are editor-first: an
> admin should be able to build a complete server without ever opening a YAML file — while the
> YAML (and the whole filesystem layout) remains a fully supported, versionable, shareable
> power-user surface.

> **Architecture constraint #3 — Free and open by default.**
> The project is open-source, and free is a *feature*: accessibility (any server owner, not
> just paying ones) and contributability (community packs, addons, core contributions) are the
> adoption strategy against paid incumbents. This has architectural consequences, not just
> licensing ones: every component — including the central editor and relay services (§9) — must
> be buildable and hostable by anyone; content formats and schemas are public documentation,
> not internal detail; and module boundaries must stay clean enough that an outside contributor
> can land a change without understanding the whole system.

These three constraints drive every decision below.

---

## 2. Who uses this, and what must they know?

The product serves distinct knowledge tiers. The architecture must let each tier be productive
without forcing the next tier's knowledge on them (**progressive disclosure**).

| Tier | Persona | Knows | Doesn't need to know | Primary surface |
|---|---|---|---|---|
| **T1 — Installer** | Server owner setting up a server | How to drop a JAR, restart | Anything else | One command; import a community pack; done |
| **T2 — Assembler** | Owner customizing an existing pack | Concepts (what an item/block/HUD *is*) | YAML, file paths, model JSON, resource-pack internals | Web UI editors + in-game browser |
| **T3 — Creator** | Building original content | T2 + basic asset concepts (texture, model, namespace as the UI presents them) | Raw `items/*.json` model syntax, font codepoints, allocation | Web UI with embedded Blockbench + live preview |
| **T4 — Power user / dev** | Packaged content authors, server devs | T3 + the file format, git, the Java API | — | Filesystem + API + addon development |

Concrete corollaries:

- **YAML knowledge is never required** for T1–T3, yet **the file format is always available**
  (T4). There is no "UI-only" content and no "file-only" content — both surfaces are complete.
- **No tier ever needs to understand resource-pack internals** (model JSON, `custom_model_data`
  ranges, font codepoint allocation, note-block state ids). Those are *compiler output*.
- The web UI must explain **Minecraft-domain concepts** (durability, hitboxes, display contexts)
  in its own words, with inline previews — it is also the documentation.
- T2/T3 work must be **safe by default**: validation happens before anything reaches the live
  server, and destructive operations are explained in domain terms ("3 placed chairs will be
  removed"), not file terms.

**Multi-admin reality:** servers have teams. Edits are attributable (audit log), sessions are
bound to in-game identities and their permission nodes, and concurrent editing is detected
(see §9.4).

---

## 3. The eleven architectural decisions

These are the load-bearing decisions. Everything else in this document follows from them.

| # | Decision | Rationale | Consequence we accept |
|---|---|---|---|
| **D1** | **Files on disk are the canonical source of truth.** The web UI is a structured editor *over the filesystem*, not a database frontend. | Survives crashes/corruption; git-friendly; the entire existing ecosystem shares file-based packs; imports from incumbents land naturally; no sync problem between "what the DB says" and "what the server runs". | The web UI needs file-level concerns (watching, atomic writes, conflict detection) rather than a database. |
| **D2** | **Everything readable is a typed resource in one content graph** (§5). Cross-references between content types are first-class, validated edges. | This *is* the product thesis: intertwining every feature. Validation of references is what makes intertwining safe. | One schema discipline for all content types; adding a type is a real commitment. |
| **D3** | **Appearance and behavior are separated within every resource.** Appearance (models, textures, fonts, sounds) compiles into the resource pack; behavior (mechanics) runs on the server. The same split exists in Oraxen/Nexo (`Pack` vs `Mechanics`) — it is correct and we adopt it as universal. | The pack compiler and the runtime never entangle. A resource can change behavior without repacking, and vice versa. | Editors and schemas always carry both facets; never mix them in one flat field list. |
| **D4** | **One behavior system everywhere:** a single Event → Condition → Action model used by items, blocks, furniture, menus, dialogs, and HUDs. Built-in mechanics are merely pre-packaged ECA bundles. | "Same action language everywhere" is the deepest form of unification; it is also the incumbents' common backbone. Third parties extend one thing, not N. | The ECA engine is critical-path and must be excellent from day one. |
| **D5** | **Registries all the way down.** Content types, mechanics, conditions, actions, hosting backends, importers, expression engines, and validation rules are all registry entries. Built-in content types register through the *same public registry* add-ons use. | The extension API is proven by our own code ("eat your own dog food"); parity gaps become addons, not forks. | Core must load before any content type registers; strict registration lifecycle. |
| **D6** | **Loading is a pipeline with staged validation and fail-soft semantics** (§6). Content errors quarantine a resource or a pack — they never crash or wedge the server. | "Extremely robust" is a requirement. Incumbent servers die to a misplaced quote; ours must not. | More machinery: diffing, shadow-swap, rollback (§6.4). |
| **D7** | **Generated artifacts are deterministic; derived *runtime* state is durable, not disposable.** The resource pack and indices live under `generated/` and are rebuildable at any time. The **allocation table is the exception**: its tombstones (freed ids/codepoints/blockstates) are *not* recoverable from the content graph — losing it risks id reuse against worlds that still hold old placements. It lives in `storage/` (durable, backed up), is treated append-only, and a rebuild-from-graph is an **audited, admin-confirmed event** (§6.3). Allocation is *stable* (§6.3). | Stable allocation is a real-world incumbent pain (reordering a pack shifts every model id). Determinism makes builds testable and reviewable. Tombstones are information the graph doesn't contain — this is the one derived artifact that must survive. | Allocation state must be persisted durably and treated append-only; rebuild requires operator confirmation. |
| **D8** | **Async-first, Folia-native.** All IO, parsing, compilation, and delivery run off the main/regional threads; only world/entity mutation crosses onto the correct schedulers (FoliaLib). | Folia support is table stakes (Oraxen/Nexo have it); async-first is also what keeps pack rebuilds from lagging live servers. | Region/thread discipline is a review-level invariant in `platform/`. |
| **D9** | **Blockbench is integrated as an unmodified, separately-distributed artifact**, driven through its plugin API across a messaging boundary. | Blockbench is GPL-3; its plugin exception explicitly allows proprietary plugins. Keeping it unmodified and out-of-process (in the browser) keeps our license surface clean regardless of the license we choose. | We never fork or vendor Blockbench source; we pin a version and adapt via a Blockbench plugin. |
| **D10** | **Modern-Paper-only floor (1.21.4+), multi-client via pack output.** We anchor on ItemModels (1.21.4+), data components (1.20.5+), display entities (1.19.4+), and `equippable` (1.21.2+) as the *baseline*, per the parity doc's "be more modern than Nexo" guidance. | Supporting legacy server versions would tax every subsystem with compatibility branches; the component surface churns per release and must be isolated anyway (§8.2). | We deliberately abandon pre-1.21.4 servers. Lower *clients* are served via versioned pack output + ViaVersion, not by lowering the floor. |
| **D11** | **Outbound-only editor connectivity (the LuckPerms pattern).** The server never accepts inbound connections for authoring. The editor is a stateless SPA (centrally hosted, self-hostable); server and browser meet through two open relay services — a payload store for bulk data and a signed WebSocket channel for interactive traffic — both dialed *out* to. A direct mode (embedded HTTP server) speaks the same API for LAN/air-gapped setups (§9.1). | The T1/T2 personas (§2) often cannot open ports. "Zero port-forwarding" is the difference between *every* server owner being able to use the editor and only VPS admins. LuckPerms proved the pattern at massive scale. | The project must operate (and open-source) relay infrastructure, and treat it as untrusted — content payloads are end-to-end encrypted (§9.2). |

---

## 4. System shape (the five planes)

Materione decomposes into five planes with strict data-flow direction:

```
                         ┌──────────────────────────────────────────────┐
                         │                WEB EDITOR PLANE               │
                         │  Browser SPA · Blockbench (unmodified) · API  │
                         └───────────────┬──────────────────────────────┘
                                         │ outbound HTTPS / WSS via relay services
                                         │ (payload store + signed WS channel),
                                         │ or direct to embedded server on LAN
 ┌──────────────┐        ┌───────────────▼──────────────────────────────┐
 │ CONTENT      │ files  │              AUTHORING / CONTROL PLANE        │
 │ (T4 users,   ├───────►│  Document store · validation · audit · file   │
 │ git, packs)  │◀───────┤  watcher · diagnostics                        │
 └──────────────┘ writes └───────────────┬──────────────────────────────┘
                                         │ same pipeline as /reload
                                         ▼
                         ┌──────────────────────────────────────────────┐
                         │                COMPILE PLANE                  │
                         │  Content graph → allocation → resource pack   │
                         │  (+ datapack: paintings, jukebox, trims)      │
                         └───────────────┬───────────────┬──────────────┘
                                         │ pack          │ typed definitions
                                         ▼               ▼
                         ┌──────────────────────┐  ┌──────────────────────┐
                         │    DELIVERY PLANE    │  │     RUNTIME PLANE    │
                         │ self-host · S3 · CDN │  │ registries · ECA     │
                         │ proxy dedup · prejoin│  │ blocks · furniture · │
                         └──────────┬───────────┘  │ HUDs · menus · …     │
                                    │  pack URL   └──────────┬───────────┘
                                    ▼                        ▼
                              PLAYER CLIENTS  ◄──────────── LIVE SERVER
                                                  (Paper / Folia)
```

The **same compile pipeline** runs identically whether the trigger is a web-UI save, a file edit
picked up by the watcher, `/materione reload`, or server startup. There is exactly one path from
source files to running content (D6) — no "UI applies changes directly" shortcut exists.

---

## 5. The content model — how Materione understands content

This is the heart of the system.

### 5.1 Resource

A **resource** is the atomic unit of content. Every resource has:

- **Identity**: `namespace:id` (e.g. `mystuff:ruby_sword`). **One namespace per pack**,
  enforced unique among enabled packs (two enabled packs claiming the same namespace is a
  load-time validation error, reported precisely). Within a pack, ids are unique per content
  type. `minecraft:` is reserved.
- **Type**: one of the registered content types (item, block, furniture, glyph, hud, menu,
  dialog, sound, recipe, armor-set, painting, …). The type set is extensible via registries (D5).
- **Facets**, per D3:
  - *Appearance* — everything that compiles into the pack: model reference, textures, sounds,
    display transforms, font data, animations.
  - *Behavior* — everything the server does: ECA rules, mechanics, interaction handlers.
  - *Metadata* — display name, category (for browsers), tags, permission, documentation blurb.
- **Provenance**: which file, which pack, which schema version, when last changed.

### 5.2 Pack

A **pack** is the unit of modularity, distribution, enablement, and import/export — the
ItemsAdder insight (drop-in `contents/<pack>/` folders), kept. Packs:

- own a namespace, a manifest (id, version, schema version, dependencies, credits);
- contain both config **and** assets together (self-contained, zip-and-share);
- can be enabled/disabled, updated, and replaced as units;
- are the granularity of the marketplace/ecosystem and of migration converters.

`feature-parity.md` §2 showed the incumbents organize content differently (ItemsAdder packs vs
Oraxen/Nexo flat folders). We take the pack model and make it universal — it is strictly more
structure, and structure is what the UI, validation, and sharing need.

### 5.3 The content graph

Resources reference each other through **typed, validated edges**:

```
 recipe ──requires/gives──► item ──uses-model──► model ──has-texture──► texture
 menu   ──displays────────► item            hud ──uses-glyph──► glyph
 furniture ──runs-action──► action ──gives──► item
 item ──inherits──► template        block ──drops──► loot-table ──► item
```

The graph is not an implementation nicety; it is the product:

- **Safety** — deleting a texture immediately reports the 4 items that break; adding a recipe
  for an item that doesn't exist is a load error with a fix suggestion, not a runtime surprise.
- **Feature depth** — autocomplete in the web UI ("which items can this menu display?") is a
  graph query. Dead-content detection, impact analysis, and "where is this used?" are graph
  queries.
- **Intertwining** — the thesis of `idea.txt` is literally "intertwine every feature with each
  other". The graph is the intertwining, made inspectable.

### 5.4 Validation levels

Validation is staged, and **every stage produces structured diagnostics** (resource id, file,
line, severity, human message, suggested fix) that surface both in the web UI inline and in
`/materione doctor`:

1. **Syntax** — file parses.
2. **Schema** — fields are the right types/ranges for the declared schema version.
3. **Semantic** — references resolve; combinations are coherent (a block needs a block-model
   kind, not an item-model kind).
4. **Platform** — the resource's requirements are satisfiable on the running server version
   (D10 gate: e.g. a 1.21.6 feature used on a 1.21.4 server).
5. **Build** — the compiler can actually allocate it (font space left? note-block states left?).

A resource that fails any stage is **quarantined** (excluded from compilation and runtime) with
its diagnostic; the rest of the pack loads (D6). Quarantined content is visible in the UI as
"broken, here's why", never silently dropped.

### 5.5 Schema evolution

Content schemas carry a version in the pack manifest. The pipeline migrates old schemas forward
through a declared chain, so packs authored a year ago still load. This is a durable promise the
incumbents' de-facto formats don't make, and it is only cheap if designed in from the start.

### 5.6 Lossless round-trip (the dual-surface invariant)

Because both the editor and hand-editing are complete surfaces (constraint #2), one invariant
makes that honest rather than aspirational: **a document loaded, untouched, and saved is
byte-identical** (comments, ordering, blank lines, unknown fields included). Consequences:

- Parsers preserve **unknown fields** (forward compatibility: a newer pack's fields survive an
  older plugin round-trip) and documents carry their own formatting context.
- There is **exactly one schema definition** per content type (the registry's self-description,
  see plugin doc §3) that drives validation, editor forms, docs, and migration — never a second
  hand-maintained copy.
- Editor saves produce **minimal diffs** (only fields the author touched change), so pack repos
  stay git-reviewable. Hand-written per-editor forms are forbidden for the same reason they're
  forbidden in the plugin: they drift.

---

## 6. The content pipeline

```
 files ──► parse ──► validate(1–4) ──► CONTENT GRAPH ──► allocate ──► compile pack
                          │                                   │            │
                     quarantine                            persist        datapack
                          ▼                                   ▼            ▼
                     diagnostics ──────────────► shadow-swap  ◄────── build(5)
                                                          │
                                            diff & apply atomically ──► runtime
                                                          │                 │
                                                    rollback on failure    └► delivery:
                                                                            pack URL → players
```

### 6.1 Load (async, off-thread — D8)

Parse and validation never touch the main thread. The result of a load is a **complete,
validated content graph** or a precise set of diagnostics — never a half-applied state.

### 6.2 Compile

The compile plane turns appearance facets + assets into:

- a **resource pack** (models, textures, fonts, sounds) — generated via the `creative` library;
- **datapack artifacts** where Minecraft requires them (custom paintings, jukebox songs,
  trim-based armor option);
- **server-side definitions** (registries of items/blocks/furniture/HUD behaviors).

Compilation is pure: same input graph → byte-identical output (D7). That makes golden-file
testing trivial and regressions obvious.

### 6.3 Stable allocation (the detail that makes or breaks trust)

Model ids, glyph codepoints, and hijacked block states (note-block ~800, tripwire ~127, chorus
~63 — see parity doc §3.5) are *finite namespaces* that the compiler allocates. Rules:

- Allocation is driven by a **persisted allocation table** (in `storage/`, durable — see below),
  treated as **append-only**: adding content never shifts existing assignments; removing content
  leaves a tombstone (reclaimable only via an explicit, announced compaction command).
- **The table is durable state, not a disposable artifact** (D7 amendment). Tombstones record
  ids that *were* in use — information the content graph no longer contains. A naive
  rebuild-from-graph would happily reassign a retired note-block state while worlds still hold
  placements of the old block. Therefore: the table is backed up with `storage/`, and a
  rebuild-from-graph (only possible if the table is lost) is an **audited, admin-confirmed
  event** that warns exactly this.
- Exhaustion of any namespace is a *build-level diagnostic* with per-type guidance, not a
  runtime oddity.

### 6.4 Apply & hot reload (transactional)

Applying a new graph is a **shadow-swap**: build the new runtime state fully, diff against the
old, then swap atomically. On any apply failure, the previous state keeps running and the
failure is reported. The diff drives:

- registry swaps, recipe updates, datapack application;
- **placed-content migration** — placed custom blocks/furniture whose definitions changed are
  migrated (model swap) or flagged; definitions *removed* invoke a declared per-resource policy
  (keep-flagged / replace-with-air / replace-with-vanilla), surfaced to the admin before apply,
  never guessed;
- **restart-required honesty** — some changes cannot hot-apply because Minecraft bakes them at
  datapack load (custom paintings, jukebox songs, trim sets, worldgen modifiers). These are
  their own diff class: the pre-apply report lists them as **restart-required**, they are
  staged for the next boot, and `/materione doctor` reports pending restart-required changes.
  Nothing pretends to be live that isn't.
- pack re-delivery: versioned URL + resend/prompt to online players.

### 6.5 Triggers

Startup · `/materione reload` · web-UI save (single resource → targeted recompile) · file
watcher (hand edits by T4 users) · pack import. All funnel into the same pipeline.

---

## 7. Content organization on disk

### 7.1 Server-side layout (plugin data folder)

```
plugins/Materione/
  settings/                  ← plugin configuration (delivery, web server, permissions)
  content/packs/             ← THE SOURCE OF TRUTH (D1) — user content lives here
  generated/                 ← pack output, indices, caches (disposable, rebuildable,
                             │  gitignorable — D7)
  storage/                   ← DURABLE plugin state: allocation table, web tokens, audit
                             │  log, local player state — backed up with the server (D7)
  blockbench/                ← pinned Blockbench build + our Blockbench plugin (D9)
  logs/                      ← diagnostics history, reload reports
```

Two invariants:

- **Settings and content never mix.** Incumbents blend plugin config with user content; we
  don't. Settings are about *the server*; content is *the product being built*.
- **`content/` and `generated/` are the only folders a user or the UI ever writes.** Everything
  else is ours. `storage/` is plugin-written but **operator-backed-up** — it is part of the
  server's state, not a cache (§6.3).

### 7.2 Pack layout

```
content/packs/<pack-id>/
  pack.yml                   ← manifest: id, version, schema version, depends, credits
  items/*.yml                ← one content type per file set…
  blocks/*.yml
  furniture/*.yml
  glyphs/*.yml
  huds/*.yml
  menus/*.yml
  dialogs/*.yml
  recipes/*.yml
  sounds.yml   paintings.yml
  templates/*.yml            ← shared inheritance bases
  assets/
    textures/  models/  fonts/  sounds/  optifine-legacy/ (if any)
```

Rules that matter for robustness and tooling:

- **A file contains resources of exactly one content type**, keyed by id. (Ecosystem-compatible
  with incumbent converters, keeps parsing and error reporting precise, keeps web-UI writes
  small and atomic.)
- **Config and assets colocated in the pack** — a pack zips into a shareable unit as-is.
- **Hand-authored vs generated are separated by directory**: users author `assets/` and `*.yml`;
  the compiler writes only `generated/`. Nothing the compiler writes ever overwrites something a
  human wrote.
- The file format is **interchange-first**: importers (ItemsAdder/Oraxen/Nexo — the parity doc's
  #1 adoption lever) land content in this exact layout, so imported content is indistinguishable
  from native content thereafter.

---

## 8. Runtime & behavior architecture

### 8.1 One ECA engine (D4)

- **Events**: clicks, breaks, places, equips, drops, deaths, inventory interactions, menu
  clicks, HUD thresholds, timers — normalized across content types ("a menu click and an item
  right-click are the same event shape").
- **Conditions**: a single expression engine (pluggable; one default), with PlaceholderAPI
  available in all text. Conditions compose and negate.
- **Actions**: one action vocabulary — give item, run command, send message/dialog, play sound,
  set HUD value, open menu, spawn/despawn furniture, modify variable, fire custom action.
  The same action list an item runs on right-click is what a menu button runs, what a dialog
  submit runs, what a furniture interaction runs.

**Built-in mechanics** (soulbound, backpack, auto-smelt, lifeleech, aura, …) are *canned ECA
bundles with parameters*, registered exactly like third-party mechanics (D5). This keeps the
parity catalog extensible without core churn.

### 8.2 Platform isolation layer

Everything that touches version-sensitive surfaces — data components, ItemModel JSON, display
entities, equipment slots, dialogs API — lives behind one internal `platform` boundary with a
per-server-version capability descriptor. The capability descriptor also powers **platform
validation (§5.4 level 4)** and the web UI's "this feature needs server 1.21.6" hints. Mojang
churns the component surface every release; the blast radius of an update must be one module.

### 8.3 Persistence

| Data | Home | Why |
|---|---|---|
| Content | files (`content/`) | D1 |
| Placed blocks/furniture | **chunk PDC** (CustomBlockData / MorePersistentDataTypes) | Placement data lives and dies with the world — no external DB to desync, no orphans after a world copy/restore. An index cache (in `generated/`) accelerates lookup and is **rebuildable by chunk scan** (self-healing). |
| Allocation table | `storage/` | **Durable, not disposable** (§6.3): tombstones hold id-history the graph no longer contains. |
| Player state (HUD values, cooldowns, menu prefs) | player PDC; cross-server state behind a storage interface (local default, Redis/SQL adapters later) | Single-server works with zero setup; networks have an upgrade path without content changes. |
| Web sessions / audit | `storage/` | Survives restarts, inspectable. |

### 8.4 Delivery

Pluggable hosting backends behind one SPI (self-host HTTP, S3/R2, external URL, …), per D5.
Versioned pack URLs (cache-busting), proxy-side dedup for Velocity/Bungee networks, pre-join
send on 1.21.7+, and optional post-processing (obfuscation, PackSquash) as pipeline steps.
Pack delivery is observable: who has which pack version, resend failures, etc.

Authoring separates **candidate hosting** from **published delivery** without creating a second
content or runtime environment. Every successful editor change still updates canonical files and
runs the one pipeline through the normal shadow-swap (D1/D6). When pack bytes change, the resulting
immutable hash is the candidate: it is sent immediately only to the player who opened that editor
session. The durable published hash remains the join/default version for everyone else. An explicit
publish operation promotes only the exact candidate hash that the author previewed; promotion is
rejected if a newer candidate exists. Promotion moves a version pointer and sends that version to
online players -- it never recompiles or substitutes newer bytes. Runtime-only changes with
identical pack bytes apply normally and need no promotion.

The delivery backend retains every published version and any active candidate within its bounded
version window. The published pointer lives in `storage/`, survives restart, and is the only
version sent on join. Candidate sends are bound server-side to the authenticated player that
opened the session; a browser cannot select an arbitrary recipient. Console-origin sessions may
author and publish but have no implicit in-game preview recipient.

### 8.5 Network mode (multi-server)

The plugin is single-instance by design, but real deployments are networks (Velocity/Bungee
with survival + creative + minigame backends). The architecture recognizes **one network
authority** per network, chosen in settings:

- **Solo (default)**: each server is its own authority — loads its own packs, serves its own
  pack URL. Zero configuration; identical to a standalone server.
- **Network authority**: exactly **one** backend is the authority — it owns the content files
  (or pulls them from a shared volume/git), runs the compile pipeline, and publishes the pack +
  **compiled runtime definitions** to the other backends. Non-authority servers load the
  compiled definitions (no local pipeline run), enforce the same pack hash, and refuse to
  drift (a backend with a different hash reports to the authority and `/materione doctor`).
- Player-facing traffic always flows through the **proxy pack-dedup** (one send per player per
  network, not one per backend hop), and cross-server player state (HUD values, cooldowns)
  flows through the storage interface (§8.3) when an adapter is configured.

Editor sessions in network mode attach to the **authority** only — editing is inherently a
whole-network operation, and there is exactly one source of truth (D1) to edit.

---

## 9. The web editor plane

### 9.1 Shape — outbound-only by default, direct when possible

Editor connectivity comes in two modes, speaking the **same versioned API**:

- **Relay mode (default, zero configuration).** The editor SPA is hosted centrally (by the
  project — open-source, self-hostable) and is *stateless*. The Minecraft server and the
  browser never connect to each other; both dial **out** to two small, open relay services:
  a **payload store** (HTTP GET/POST for bulk data — pack dumps, edited documents, compiled
  previews) and a **WebSocket relay** (a channel the server joins; signed messages flow both
  ways for interactive traffic). This is the proven LuckPerms editor model, adapted: an admin
  runs `/materione editor`, gets a URL, and edits — no port-forwarding, no reverse proxy, no
  static DNS, works identically behind shared hosting, NAT, or a proxy network.
- **Direct mode (opt-in).** The plugin embeds the same HTTP + WS API in a local server (bound
  to localhost by default; reverse-proxy pattern documented for teams). For LAN, air-gapped, or
  "I self-host everything" setups — and as the fallback if relay infrastructure is ever
  unreachable.

The frontend is a **separate build artifact** (SPA) — no Node on the server, no build step for
the admin. It talks only to the documented API, which is therefore also the automation surface
for CI (pack repos can drive it).

Screens (product-architecture level): **Dashboard** (server health, published/candidate state,
pending diagnostics) · **Content browser** (resource-first graph search across all types) ·
**Type studios** (item, block, furniture, glyph, HUD, menu, dialog, recipe, sound, painting) ·
**Asset manager** (textures/models/sounds) · **Advanced files** (filesystem/YAML surface for T4)
· **Diagnostics center** · **Live preview**. Pack creation is a one-time project onboarding step;
normal T2/T3 authoring does not expose content directories or require file operations.

### 9.2 Auth & trust — the relay is untrusted

Because relay infrastructure is operated by the project (or a third party), it is treated as
**hostile** by design, exactly as LuckPerms treats bytebin/bytesocks:

- **Session initiation is in-game.** `/materione editor` creates the session; the resulting
  URL (containing an unguessable payload id) is given to the admin in-game. The URL is a
  capability — its secrecy is the transport-level auth, and it is short-lived.
- **Signed handshake.** On connect, the editor sends a `hello` with a nonce, its public key,
  and the session id; the server replies after the admin confirms trust **in-game** (the nonce
  disambiguates when multiple editors connect). Thereafter, both directions sign every message
  (LuckPerms uses SHA256withRSA); the server rejects unsigned or wrongly-signed traffic, and
  rejects any browser key not explicitly trusted for the session.
- **End-to-end encryption of payloads.** Session content uploaded to the payload store is
  encrypted client/plugin-side with a session key negotiated in the handshake — the relay
  stores ciphertext, always. (This goes one step beyond LuckPerms, whose payloads are
  compressed but readable by bytebin; our editor edits whole content packs, which are more
  sensitive than permission lists.)
- **Permissions stay in Minecraft.** Once a browser session is bound to an in-game identity,
  it acts with that player's **existing permission nodes** — no parallel auth system to drift.
  Rate limiting and session expiry apply. Full audit log of who changed what, when (teams,
  T2–T3). The permission taxonomy is small and declared once (web doc §4.2 groups map onto it):
  `materione.editor` (open editor sessions), `materione.edit.<pack>` (write a pack's content),
  `materione.pipeline` (trigger reloads/apply), `materione.admin` (settings, delivery, delete).
  Reads require `materione.editor` alone; every write path checks the matching node.
- **Mode separation.** The pack self-host server (public, read-only file serving) and the
  editor API (authenticated everything) are separate listeners with separate trust levels.

### 9.3 Blockbench integration (D9 boundary)

Blockbench (GPL-3) runs **unmodified** in the browser from a pinned artifact under
`blockbench/`, served as **static assets by the editor SPA host** (the editor's own CDN/origin
— never an inbound connection to the game server, keeping D11 intact). A **Materione
Blockbench plugin** (explicitly permitted to be any license by
Blockbench's plugin exception) adds "save to Materione" and model presets, communicating with
our editor/API across a message boundary. We never vendor or patch Blockbench source; upgrading
Blockbench is a pin bump. If a fork were ever needed, it stays a separate project — never inside
this codebase.

Blockbench is a contextual asset editor, not a top-level application the author organizes
manually. **Create model** / **Edit model** opens the pinned build from the owning resource's
appearance field with pack, model, source-project, revision, and return context. Save-back stages
the model JSON, textures, optional `.bbmodel` source, relationship metadata, and owner-field patch
as one revision-checked authoring transaction. Existing model references remain valid without
relationship metadata. The bridge is capability-limited to import/export messages, verifies the
exact iframe window and origin, and never receives editor session keys or filesystem access.

### 9.4 Editing model & concurrency

The API exposes the filesystem as a **document store**: structured reads/writes per resource,
atomic per-save writes, revision numbers per resource (stale writes are rejected — last-writer
wins *with a visible warning*, never silent clobbering). The file watcher feeds external edits
(hand-edited YAML, git pulls) back into all connected editors in real time. Live preview closes
the loop: save → pipeline → targeted reload → visible in a connected game client within seconds.

The primary editor is resource-first. A short creation flow derives safe ids and paths, then opens
one visual studio organized by the domain facets in D3 (identity, appearance, gameplay, behavior,
advanced). Forms render from richer registry self-description -- labels, grouping, constraints,
reference targets, structured children, and conditional visibility -- rather than from field ids
or hand-written per-type schemas. Explorer, revisions, paths, and raw YAML remain complete T4
surfaces under progressive disclosure.

Multi-file authoring actions (resource creation, coherent field batches, and Blockbench save-back)
are server-side transactions: validate every path and revision, stage all bytes, atomically promote
the set, record one audit summary, and enqueue one reload. Failure leaves no partial assets,
attachments, revisions, or success-shaped response. The stateless SPA may hold a bounded unsaved
draft, but durable draft, candidate, and publication state remain server/filesystem-owned.

### 9.5 In-game surface (for tiers without a browser open)

An in-game **content browser** (parity with `/ia` — categories, search, get-item) and
server-side **dialogs** for lightweight admin flows. Principle: **commands operate, the editor
authors** — `/materione` offers `editor`, `browse`, `reload`, `doctor`, `pack import|export`,
and nothing that tries to be an authoring surface.

---

## 10. Codebase division

Gradle multi-module; one plugin artifact shades the JVM modules. Modules are *compile-time
boundaries that enforce the dependency rules*, not separate plugins.

```
materione/
├─ api/            ← Public Java API: registries, content-type SPI, ECA SPI, events.
│                    Stable, semver'd, the ONLY module third parties may touch.
├─ core/           ← Bootstrap, settings, scheduling (Folia-safe), pipeline,
│                    document store, diagnostics, persistence interfaces.
├─ content/        ← Built-in content types, package-per-type (items, blocks,
│                    furniture, glyphs, huds, menus, dialogs, recipes, sounds,
│                    paintings, armor). Registered via api/ — same path as addons (D5).
├─ behavior/       ← ECA engine, expression engine, built-in mechanics (canned ECA).
├─ pack/           ← Allocation, pack compiler, datapack artifacts, post-processing.
├─ delivery/       ← Hosting backends SPI + built-ins, pack send, proxy dedup.
├─ platform/       ← Version-sensitive surfaces + capability descriptor (§8.2).
├─ web/            ← Editor API implementation: document store endpoints, sessions, signed
│                    handshake, auth, audit. Transport-agnostic — same handlers serve direct
│                    mode (embedded HTTP/WS server) and relay mode (payload store + WS relay
│                    clients). The relay services themselves are separate open-source
│                    deployments, not plugin modules.
├─ webui/          ← Frontend SPA (TypeScript) + the Blockbench plugin source.
│                    Not a Gradle module — its build lands in M3/M5.
├─ importers/      ← ItemsAdder / Oraxen / Nexo converters → native pack layout.
├─ integrations/   ← PlaceholderAPI, ModelEngine, MythicMobs, Geyser, anti-cheat bridges.
├─ plugin/         ← Assembly module (the leaf): plugin.yml, JavaPlugin bootstrap,
│                    commands, shaded distributable. Nothing may depend on it.
├─ testing/        ← Shared test infrastructure (ArchUnit rule set, golden-file
│                    support) — build infrastructure, not a product module.
├─ harness/        ← Headless-Paper integration harness (real server boot) —
│                    build infrastructure, not a product module.
├─ fixtures/       ← Golden-file + protocol fixture corpus. NOT a Gradle module;
│                    grow-only (roadmap rule 2).
└─ build-logic/    ← Gradle convention plugins + custom tasks, version catalog.
                    (Named build-logic/, not build/: "build" is Gradle's output dir.)
```

**Dependency rules (enforced, not aspirational):**

- `api` depends on nothing internal; `core` depends only on `api`.
- `content`, `behavior`, `pack`, `delivery`, `importers`, `integrations` depend on `api` +
  `core` — never on each other's internals.
- `platform` is the only module that knows a server version exists; `pack` and `content`
  consume capabilities, not versions.
- `web` depends on `api`/`core` (+ content schemas for the document store) and **nothing
  depends on `web`**. `webui` depends only on the API contract (and is transport-agnostic:
  relay or direct).
- The plugin JAR wires the modules together at bootstrap; runtime wiring order is:
  `core → registries → built-in registrations → load content → compile → serve`.
- `plugin`, `testing`, `harness` are infrastructure: nothing product-side depends on
  `plugin` (the leaf), and `testing`/`harness` are never shipped. All module boundaries
  above are **enforced by the build** (ban tests + Gradle isolation), per plugin.md §11.

Notable choice: **one `content` module with package-per-type** rather than module-per-type —
content types share most of their machinery, and built-ins existing as "first-party addons"
is the point (D5). If a type grows huge (furniture likely), it can graduate to its own module
without moving packages.

---

## 11. Cross-cutting robustness principles

These are invariants, checked in review and CI, not aspirations:

1. **Fail-soft content** — the server never crashes, wedges, or kicks players because of a
   content error; errors are quarantined with actionable diagnostics (§5.4, §6.1).
2. **Transactional application** — shadow-swap or rollback; no half-applied reload (§6.4).
3. **Disposable derived data** — everything in `generated/` can be deleted and rebuilt
   deterministically (D7). The allocation table is the declared exception: durable state in
   `storage/`, rebuilt only via an audited, admin-confirmed event (§6.3).
4. **Self-healing state** — placement index rebuilds from chunks; caches are never
   load-bearing truth. The allocation table is *not* self-healing by design — its tombstones
   are historical truth the graph cannot re-derive (§8.3, §6.3).
5. **One path** — one pipeline from files to runtime, no matter the trigger (§4, §6.5).
6. **Observability as a feature** — `/materione doctor` reports pack health, allocation
   pressure, orphaned placements, delivery status; the web dashboard mirrors it. Diagnostics
   are data, not log noise.
7. **Determinism in tests** — golden files for compiled packs; importer corpora from real
   incumbent packs; API contract tests; a headless server harness for integration tests.
8. **Semver discipline on `api/`** — deprecate-then-remove, never silent breaks; addons are the
   ecosystem's investment in us.

---

## 12. Scope boundaries (architectural, not tactical)

**In:** everything in the parity doc's MVP list plus its differentiators (HUD system, real menu
builder, dialogs, worldgen/ores, fluids, mob equipment, converters, Geyser later) — and the
central editor hosting + relay services, operated by the project as free infrastructure and
fully self-hostable by anyone (constraint #3).

**Explicitly out (delegate, don't reimplement):** mob AI, vehicles/physics, pets →
ModelEngine / MythicMobs integration. We render, place, protect, and wire them; we do not
become them.

**Deliberately abandoned (D10):** pre-1.21.4 servers; item-frame/armor-stand furniture as a
primary method (display entities only; legacy paths only if a converter needs them).

---

## 13. Risks the architecture must hold against

| Risk | Mitigation built into this design |
|---|---|
| Mega-plugin scope creep | D5 registries: parity gaps become addons; `content` types are isolated; scope list above is contractual |
| Mojang per-release churn | `platform` isolation + capability descriptor (§8.2); modern-only floor (D10) |
| Web UI drift from file reality | D1 files-canonical + document store + watcher (§9.4); UI is never a second truth |
| Concurrent edits / team conflicts | Revision numbers, audit log, in-game identity binding (§9.2, §9.4) |
| Relay infrastructure becomes a liability (cost, trust, outage) | Relay is untrusted-by-design and optional: E2E-encrypted payloads, in-game trust confirmation, direct mode as full fallback (D11, §9.2) |
| Allocation instability eroding trust | Append-only allocation table, tombstones, durable storage + audited rebuild (§6.3) |
| No phased path — contributors can't start | Roadmap document with de-scoped first release ([`roadmap.md`](roadmap.md)); architecture is the destination, roadmap is the route |
| GPL contamination via Blockbench | Unmodified artifact + plugin-exception boundary (D9, §9.3) |
| Incumbent switching cost | Importers land in the native layout (§7.2) — migration is one-way and complete |
| A bad pack on a live network | Fail-soft + transactional reload + pre-apply migration preview (§5.4, §6.4) |

---

## 14. Open questions (decide before implementation planning)

1. **Project license** — open-source is settled (constraint #3); the specific license (GPL-3
   vs. more permissive) still needs choosing. It affects how we treat the Blockbench plugin
   (any license, per its exception) and any borrowed Oraxen reference code (Oraxen is GPL —
   *read* it as reference, never copy into a non-GPL codebase).
2. **Cross-server state adapter set** — which of Redis/SQL/none ships in v1 behind the storage
   interface (§8.3).
3. **Geyser/Bedrock timing** — architecture reserves a conversion step in `pack/`, but scope
   timing is open.
4. **Web UI distribution** — bundled in the JAR (simplest, chosen default) vs. downloaded on
   first run (smaller JAR); decide before packaging design.
5. **Relay services scope** — the payload store and WS relay are small, self-contained services
   (bytebin/bytesocks are the reference designs, both open-source). Decide: fork-and-rebrand
   them vs. write minimal equivalents; and what the shared free instance's quotas look like
   (quota sketch in web doc §3.3).
6. **Community pack hub** — whether the project also hosts a public pack registry/marketplace
   (natural extension of constraint #3, but real infrastructure and moderation scope); decide
   after the editor proves out.

> **Resolved since first draft:** project naming (Materione, everywhere), the ECA expression
> engine (one universal engine — D4), relay pattern (LuckPerms-style, D11), and cloud platform
> (Cloudflare Workers). **Reconciled with the parity research:** every target spec in
> [`feature-parity.md`](feature-parity.md) now maps to an owning section or an explicit
> "dropped because" in [`architecture/parity-reconciliation.md`](architecture/parity-reconciliation.md).
> The build order lives in [`roadmap.md`](roadmap.md).

---

*This document is the contract between the idea and the implementation. When subsystem design
begins, each subsystem doc must trace back to a decision (D1–D11) or explicitly amend this one.*
