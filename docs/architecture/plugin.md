# Plugin Architecture — Technical

> **Traceability.** Implements the master decisions [D1–D11](../architecture.md#3-the-eleven-architectural-decisions).
> Parent: [`architecture.md`](../architecture.md) · Companions: [`web.md`](web.md) (Cloudflare
> ecosystem) · [`runtime-systems.md`](runtime-systems.md) (per content-type runtime).
>
> **Level of detail:** module internals, threading, lifecycle, data flow through the pipeline,
> directory contracts, invariants, failure semantics, testing strategy. Still no class design —
> interfaces vs. abstracts are decided in subsystem implementation plans, not here.

---

## 1. Process & lifecycle

Materione is a single Paper/Folia plugin JAR (Gradle multi-module, shaded). One JVM process =
one server instance. Nothing assumes a shared JVM with other Materione servers.

### 1.1 Bootstrap sequence (strictly ordered)

```
onEnable
 ├─ 1. Platform probe        → server version, Paper/Folia, mod env; resolve a
 │                              PlatformCapability descriptor (§6)
 ├─ 2. Core services         → scheduler wrappers, settings, paths, logging,
 │                              diagnostics bus, persistence interfaces
 ├─ 3. Registries            → empty registry objects created, no entries (D5)
 ├─ 4. Built-in registrations→ content types, mechanics, actions, conditions,
 │                              hosting backends, importers, expression engine
 ├─ 5. Storage init          → open PDC-backed stores, rebuild indices if stale (§7)
 ├─ 6. Content load          → full pipeline run (§4) on content/packs/*;
 │                              errors are diagnostics, never fatal (D6)
 ├─ 7. Compile               → pack build if hashes changed; allocation table
 │                              load-or-rebuild (D7, §4.4)
 ├─ 8. Runtime swap          → items/blocks/furniture/... become addressable
 ├─ 9. Delivery              → start hosting backend, expose pack URL, pre-join hook
 ├─10. Editor transport      → direct-mode listener (if enabled) + relay client
 └─11. Commands & in-game UI → registered last; nothing is executable before the
                                system it touches is ready
```

Invariants:

- **No phase may crash `onEnable`.** Every phase's failures degrade: platform probe failure →
  clean disable with a precise message; content load failures → diagnostics (D6).
- **Nothing user-visible registers before its backing state is ready.** A command that would
  touch not-yet-loaded content doesn't exist yet, rather than erroring at use time.
- Phases are **idempotent on re-run** (a reload re-executes 6→9; a full re-bootstrap 1→11).
- **Shutdown is reverse-order** (transports → delivery → runtime → storage flush), with a hard
  timeout; unresponsive phases are abandoned with a warning, never blocking server stop.

### 1.2 Reloading taxonomy

| Kind | Trigger | Scope | Does NOT |
|---|---|---|---|
| **Full reload** | `/materione reload`, startup | whole pipeline (6→9) | touch settings or storage |
| **Targeted reload** | web save, watcher, `reload <pack>` | one pack/resource → diff → minimal apply | full recompile |
| **Settings reload** | `/materione reload settings` | `settings/` only | some keys declare "restart required" (ports, etc.) |
| **Pack toggle** | `pack enable/disable <id>` | manifest + dependency re-evaluation | delete pack files |

All four funnel into the same pipeline entry (one-path, D6). A running reload **serializes** —
a second trigger queues (no concurrent pipelines); triggers arriving mid-run coalesce per-scope.

### 1.3 Platform probe & floor enforcement

Runs before anything version-sensitive. Answers: exact server version, Paper vs **Folia server
flavor** (detected from server classes — e.g. `ThreadedRegionizer` on the classpath — *not* from
whether a Folia-compat library is present, since we ship our own scheduler routing), Geyser/
ViaVersion present, mod environment. Floor (1.21.4+) not met → clean disable
with instructions. Optional integrations present → recorded as **available hooks**, wired at
phase 4, used opportunistically, never required by core.

M0 note: the scheduling gateway calls Paper's native `RegionScheduler` / `EntityScheduler` /
`AsyncScheduler` directly — no FoliaLib dependency is shaded (deviation from the original plan,
recorded in `docs/architecture/build.md`).

---

## 2. Threading model (async-first, Folia-safe — D8)

Three thread classes exist in the whole plugin. Nothing else may spawn threads.

| Class | What runs there | Owned by |
|---|---|---|
| **Main / region threads** | any world/entity/player mutation; anything Paper requires synchronous | scheduler wrappers in `core` |
| **Pipeline pool** (fixed, core-sized) | parse, validate, compile, hash, image/audio IO work | `core` scheduling |
| **IO threads** (virtual where available) | HTTP(S) client calls (relay, hosting uploads); watcher events debounced onto pipeline pool | `core` scheduling |

Rules (enforced in review + by an ArchUnit-style ban test):

- **All world/entity mutation goes through one gateway** (in `core`): `sync(location, task)` /
  `entity(entity, task)` — on Folia this routes to the region/entity scheduler, on Paper to the
  main-thread scheduler. Code outside `platform/` never touches Bukkit scheduler APIs directly.
- **Every event listener/handler declares its thread class** at registration; the registration
  API rejects mismatches (an async handler that would mutate the world is caught at
  registration, not in production).
- **File watcher events are debounced** (default ~500 ms quiet period) before entering the
  pipeline — a `git pull` that touches 300 files is one pipeline run, not 300.
- **Pipelines are cancellable.** A running compile holds a cancel token; a newer trigger
  supersedes it after the current atomic stage completes. This bounds wasted work under rapid
  save storms from the web editor.
- Pipeline pool is fixed-size because compilation is CPU-bound; an unbounded pool under save
  storms becomes an OOM. Per-stage CPU/memory budgets are owned by the pipeline supervisor,
  not by stages.

---

## 3. Registries (D5) — exact semantics

- **Namespaced keys.** Every entry is `namespace:id`. Built-ins use `materione:` (e.g.
  `materione:backpack`). Addons register under their own namespace. `minecraft:` is reserved,
  rejected at registration.
- **Registration windows.** Built-ins register in phase 4, before content load (content
  references mechanics). Third parties register **early** (before first content load) or
  **late** (after — valid, but content referencing the entry stays quarantined until next
  reload). Registration outside a window is rejected with a clear error. Registries **freeze**
  when content loads; a reload re-opens the window, re-checks, re-freezes.
- **Addon loading.** Addons are JARs under `plugins/Materione/addons/`, each in a
  **child-first classloader**, given a sandboxed context (`MaterioneAddon` API: register
  content types/mechanics/actions/conditions/backends, read-only content graph view,
  diagnostics sink, scheduler gateway). Addons may not touch Bukkit APIs except through their
  own `plugin.yml` listeners — enforced by the classloader filter (allowlist documented in
  `api/`).
- **Inter-addon dependencies** are manifest-declared (`depends: [x, y]`), resolved
  topologically; a cycle quarantines all members with one diagnostic each.
- **Built-ins use the identical registration path** as addons — no internal route (D5).
- **Self-description.** Every registry entry carries metadata the web editor renders forms
  from: display name, description, parameter schema (id/type/range/default/required),
  since-version. One source of truth for editor forms, docs, and tab completion. Hand-written
  per-mechanic editor forms are forbidden — they drift.

---

## 4. The content pipeline — inside the stages

```
trigger ─► SCOPE ─► PARSE ─► VALIDATE(1–4) ─► GRAPH BUILD ─► ALLOCATE ─► COMPILE ─► APPLY ─► DELIVER
          (files)   (AST)    (schema/semantic/  (typed edges,   (ids,        (pack +     (shadow-  (pack URL,
                             platform)          lineage)        codepoints,  datapack,    swap)      resend)
                                                                blockstates)  post-steps)
```

### 4.1 Scope resolution

A trigger carries scope: *everything* | a pack | a file set. The stage maps scope → files to
(re)process (manifests, per-type YAML, assets), consulting the **pack dependency graph** and
the **resource dependency graph** (a texture change invalidates items referencing it). Scope
expansion is logged ("file X changed → resources A, B, C affected").

### 4.2 Parse

- YAML/JSON → raw document tree with **per-file error context** (file, line, column) captured
  as diagnostics. No semantic interpretation yet.
- Assets are **fingerprinted** (hash) here; the fingerprint index in `generated/` drives
  incremental compile — unchanged asset → unchanged pack entry.
- A parse failure in one file never reads or blocks other files (D6).

### 4.3 Validate (levels 1–4, master §5.4)

Runs on the pipeline pool. Produces per resource: pass | quarantine(level, reason, hint).
Validation is **pure** — reads graph-so-far + registries, touches nothing.

Level 3 (semantic) is where **reference resolution** happens: every edge typed and checked
against existing resources; unresolved → quarantine with an edit-distance "did you mean"
suggestion.

### 4.4 Allocate & compile

- **Allocation table** (`storage/allocation.bin` — durable, not disposable; master §6.3) is
  append-only, persisted *before* the outputs depending on it are written. Its tombstones are
  historical truth the graph cannot re-derive, so: the file is backed up with `storage/`, and
  if it is ever lost or corrupt, the rebuild-from-graph is an **audited, admin-confirmed
  event** (`/materione doctor` offers it with an explicit warning that retired ids may be
  reassigned against worlds holding old placements) — never an automatic silent action.
- **Compile is pure & deterministic**: identical graph + allocation → byte-identical pack.
  Output is staged in `generated/pack/` and hashed; hash equals live pack's hash → skip apply
  and delivery entirely (no-op reloads are free and invisible).
- **Pack-format policy (as built)**: the staged `pack.mcmeta` advertises the 1.21.4 floor
  (46) through the newest known bound in *both* schema generations — `pack_format` +
  `supported_formats` for pre-25w31a clients, `min_format`/`max_format` for 1.21.9+/26.x
  clients (which ignore `supported_formats`; a pack carrying only the old fields shows as
  incompatible on every current client). The bound comes from the server itself when it can:
  `ServerPackFormat` reads `pack_version.resource` out of the server jar's `version.json`
  (`MATERIONE-PACKFORMAT` at boot), so a server newer than this build still accepts its own
  pack; the known-version table is the fallback, and MTR-0018 is a warning only when *neither*
  source covers the running version (an informational MTR-0018 when the server reports a
  format the table doesn't know yet).
- **Datapack artifacts** (paintings, jukebox songs, optional trims) compile alongside, into the
  world's datapacks folder via atomic replace; each carries the content hash so staleness is
  detectable. **Datapack registry content (paintings, jukebox songs, trim sets, worldgen
  modifiers) is restart-required**: Minecraft bakes these at datapack load, so these changes
  stage for the next boot and appear in the pre-apply report and `/materione doctor` as
  *pending restart-required* — they never masquerade as live (master §6.4).
- **Post-processing** (obfuscation, PackSquash, signing) is an ordered chain over the staged
  output; each step is cache-keyed by (input hash, step config) — unchanged inputs never
  re-run an expensive step.

### 4.5 Apply (shadow-swap, transactional)

The only stage allowed to schedule onto main/region threads (through the §2 gateway).

Sequence: build new runtime registries fully → diff vs live → **pre-apply report**
(placed-content migration counts, removals by policy, recipe enable/disable counts, and
**restart-required changes listed separately — staged, not applied**) → swap
atomically → post-swap reconciliation (orphan check, recipe book resync, display entity
resync) → any exception rolls back to the previous registries (never unlinked, only shadowed)
and reports.

The **pre-apply report reaches the operator before commit** when interactive (web dashboard +
in-game), and is logged when automatic. Removals follow the declared per-resource policy,
defaulting to **keep-flagged** (content stays, marked orphaned, admin decides later — least
destructive default).

### 4.6 Deliver

Publishes the staged pack per the configured backend (master §8.4), computes the versioned URL,
and prompts/resends to online players per policy. Failures here never roll back apply — the
server runs the new content; players just keep the old pack until delivery succeeds (visible in
`/materione doctor`).

---

## 5. Diagnostics system (fail-soft backbone)

Every failure anywhere becomes a **structured diagnostic**: `{id, severity (error|warn|info),
code, message, resource?, file?:line?, hint?, since?}`. Diagnostics flow onto a bus at load,
compile, apply, and runtime; consumers render them in-game (clickable, with hints), in the web
editor (inline squiggles + diagnostics center), and in `/materione doctor`.

- **Diagnostic codes are stable and documented** (`MTR-1042: unresolved reference`) so users
  can search them and addons can emit them.
- **Quarantine is first-class runtime state**: a quarantined resource is invisible to players
  but visible in the editor and in-game browser as "broken, here's why" — never silently
  dropped.
- Runtime errors (an ECA action throwing mid-execution) degrade to a per-invocation diagnostic
  with rate-limited logging — one broken action never spam-logs or takes down the event chain.

---

## 6. Platform isolation layer

`platform/` is the only module that knows a server version exists. It exposes:

- a **PlatformCapability descriptor** — which data components exist, which ItemModel features
  exist, display entity behaviors, equipment slots, dialogs API, packet access — resolved once
  at bootstrap (§1.1) and injected everywhere;
- versioned implementations behind it (one implementation per supported server line, selected
  by the probe);
- capability **gates** used by validation level 4 ("this resource needs 1.21.6 dialogs") and by
  the web editor for feature hints.

Mojang churns components every release; blast radius of an update = one implementation + one
descriptor delta. `content/`, `behavior/`, `pack/` consume capabilities, never versions.

---

## 7. Persistence layout

| Data | Home | Notes |
|---|---|---|
| Content (source) | `content/packs/` files | canonical (D1) |
| Generated artifacts | `generated/` | disposable, deterministic (D7) |
| Placed blocks/furniture | **chunk PDC** (CustomBlockData + MorePersistentDataTypes) | lives and dies with the world; no external DB to desync |
| Placement index (accelerator) | `generated/index/` | rebuildable by chunk scan (self-healing); never load-bearing truth |
| Player state (HUD values, cooldowns, menu prefs) | player PDC; cross-server behind a storage interface (local default; Redis/SQL adapters later) | single-server works with zero setup |
| Web sessions, audit log | `storage/` | survives restarts, inspectable |
| Allocation table | `storage/allocation.bin` | **durable, operator-backed-up** — append-only; tombstones are unrecoverable truth (D7, §4.4) |

World-attachment rule: if data must survive a world copy/restore and die with the world, it
belongs in chunk PDC — not in a database, not in `generated/`.

### 7.1 Settings & storage migration (format evolution)

Two more formats evolve with the plugin and need declared migration chains, same as content
schemas (master §5.5):

- **`settings/` files** carry a `version:` key; the loader migrates forward through a declared
  chain, preserving unknown keys (lossless round-trip, master §5.6) so a downgrade doesn't
  destroy newer settings. Unmigratable (too-old) settings → diagnostic + load defaults beside
  the old file (`.old`), never a crash.
- **`storage/` records** (allocation table, sessions, audit log) are versioned containers;
  readers accept N-1 versions. The allocation table container format is especially frozen:
  its schema changes only additively, because its integrity is world-safety-critical.

World-attachment rule: if data must survive a world copy/restore and die with the world, it
belongs in chunk PDC — not in a database, not in `generated/`.

---

## 8. Failure semantics summary (per subsystem)

| Subsystem | Failure | Behavior |
|---|---|---|
| Parse | malformed YAML | file quarantined, diagnostic with line/column |
| Validate | broken reference | resource quarantined + hint |
| Allocate | namespace exhausted (e.g. note-block states) | build diagnostic with per-type guidance; pack loads without the resource |
| Allocate | table lost/corrupt | rebuild offered as audited admin action with warning; never automatic (§4.4) |
| Compile | compiler bug / OOM | pipeline aborts cleanly, live state untouched (staging never half-written) |
| Apply | exception mid-swap | rollback to shadowed previous registries; diagnostic |
| Apply | restart-required changes present | staged for next boot, listed in pre-apply report + doctor; never silently "live" |
| Deliver | upload failure | content live, delivery retried; `/materione doctor` shows state |
| Runtime | ECA action throws | per-invocation diagnostic, rate-limited log |
| Editor transport | relay unreachable | direct-mode fallback offered; editing queues locally via watcher |
| Addon | throws during registration | addon quarantined, rest load |

The one-line rule: **the server stays up, the diagnostics say why, and nothing is half-applied.**

---

## 9. Performance budgets (architecture-level, verified in CI benchmarks)

| Operation | Budget | Notes |
|---|---|---|
| Full reload (100 packs, warm cache) | < 10 s wall, 0 main-thread stalls > 50 ms | pipeline fully off-thread |
| Targeted reload (1 resource) | < 1 s to apply | incremental compile via fingerprints |
| Item resolve (registry map hit) | < 1 µs | in-memory map lookup by namespaced key |
| Item detect (held stack, cold) | ~10–50 µs | PDC tag read; cached against the stack's identity hash to avoid repeat reads |
| Inventory scan pass (updater) | amortized, < 1 ms per player per pass, batched & throttled | full open-inventory scans are the expensive path; never per-tick |
| Custom block place/break handling | < 200 µs on region thread | event handler + PDC write |
| Pack build (5k assets, warm) | < 30 s, cache-hit no-op < 100 ms | deterministic hashing |
| Editor save round-trip | < 2 s end-to-end | save → validate → targeted apply → confirm |

Honesty notes: the < 1 µs resolve is the *cached* path; first touch of a stack pays a PDC read
(~10–50 µs, NBT decode). The updater's inventory scans are the real cost center — they are
throttled, batched across players, and skipped entirely when a content hash hasn't changed
(the common case). Budgets are CI-tracked with a fixture corpus; regressions block merge, not
"warn".

**Enforcement status (M2):** full-reload wall time, pack build, no-op decision, apply-swap
stall proxy, registry-map resolve, **targeted single-file reload < 1 s** (fingerprint cache
+ scoped parse, M2 slice 7), and **throttled inventory scans** are measured against the
57-item fixture corpus (sample + corpus + world packs) by `plugin`'s `ContentPerfBudgetTest`
on every `build`; the "0 main-thread stalls > 50 ms" row is proxied by the timed apply swap
plus the live harness runs. Not yet owned: cold item detect ~10–50 µs (needs live PDC
reads; harness player scenarios arrive with M4).

---

## 10. Testing strategy

- **Unit**: pipeline stages, allocation, ECA engine, expression engine — pure, fast.
- **Golden files**: compiled packs + datapacks from a fixture corpus; byte-compared (D7 makes
  this sound).
- **Lossless round-trip tests**: load → save → byte-compare for every fixture document
  (comments, ordering, unknown fields preserved — master §5.6); plus unknown-field forward
  compatibility (newer pack through older plugin).
- **Importer corpora**: real-world ItemsAdder/Oraxen/Nexo packs, converted and re-converted,
  diffed.
- **API contract tests**: the `api/` module's surface, semver-checked.
- **Integration**: headless Paper (and Folia) server harness — bootstrap, load fixture packs,
  exercise place/break/interact/HUD/menu flows, assert diagnostics.
- **Concurrency torture**: rapid save storms, watcher + web + command triggers racing — assert
  serialization, no half-applies.
- **Fault injection**: kill the process between every pipeline stage pair; assert next boot
  recovers to a consistent state (allocation table, staging, indices).

---

## 11. Module boundary recap (enforced)

```
api      → (nothing internal)         core → api
content  → api, core                  behavior → api, core
pack     → api, core, platform-caps   delivery → api, core
platform → api (only version-aware module)
web      → api, core                  (nothing depends on web)
webui    → API contract only          importers/integrations → api, core
```

Boundaries are enforced by Gradle module isolation + a ban-list test (no Bukkit scheduler
outside the gateway, no version checks outside `platform/`, no I/O in validators, **no wire
protocol outside `web/`** — `web/` owns both the server-side handlers and the plugin-side
relay/store clients, so both directions of the editor transport evolve in one module).
Violations fail the build, not the review.
