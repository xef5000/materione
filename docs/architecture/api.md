# API-First Architecture & Transformation Plan

> **Traceability.** Implements and extends master decisions [D1–D11](../architecture.md#3-the-eleven-architectural-decisions) —
> specifically **D1** (files on disk remain the canonical source of truth), **D4** (one ECA behavior system),
> **D5** (registries all the way down), **D6** (one pipeline from files to runtime), and **D8** (async-first, Folia-native).
> Parent: [`architecture.md`](../architecture.md) · Companions: [`plugin.md`](plugin.md) · [`web.md`](web.md) · [`runtime-systems.md`](runtime-systems.md).

---

## 1. Thesis: The Plugin Is an Implementation of the API

Historically, `api/` (and `MaterioneAPI`) has been a minimal read-only facade (`version()`, `registries()`, `diagnostics()`, `platform()`), while actual operations — authoring content, mutating parameters, reloading the pipeline, placing blocks/furniture, giving items, publishing packs, and running diagnostics — were wired directly between `plugin/`, `core/`, and `web/`.

**The API-first transformation inverts this relationship:**

> **Architectural Invariant — Every action is described through `api/`.**
> Registering content types/mechanics, creating/editing resources and assets, changing parameters, reloading the pipeline, publishing packs, inspecting diagnostics, and interacting with live world/runtime content must all be expressed as typed operations on `MaterioneAPI`. The plugin (`core/`, `content/`, `pack/`, `delivery/`, `plugin/`) is strictly the *implementation* of `api/`.

### Three Consumers, One Surface

```
 ┌─────────────────────────┐   ┌─────────────────────────┐   ┌─────────────────────────┐
 │   1. Files & Commands   │   │     2. Web Editor       │   │   3. Future MCP Server  │
 │  (/materione, watcher)  │   │  (Direct & Relay RPC)   │   │  (AI / Tooling Agent)   │
 └────────────┬────────────┘   └────────────┬────────────┘   └────────────┬────────────┘
              │                             │                             │
              ▼                             ▼                             ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────┐
 │                              dev.materione.api.MaterioneAPI                         │
 │  registries · authoring · assets · pipeline · delivery · runtime · admin · events   │
 └──────────────────────────────────────────┬──────────────────────────────────────────┘
                                            │ implemented by
                                            ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────┐
 │                        Core & Plugin Implementation Planes                          │
 │   DocumentStore (D1) ──► PipelineRunner (D6) ──► PackCompiler (D7) ──► Runtime      │
 └─────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Files & Commands (`plugin/` + file watcher):** `/materione reload|doctor|give|dev|editor` and the file watcher become thin CLI/event adapters that parse arguments, construct an `ActorContext`, and invoke `MaterioneAPI`.
2. **Web Panel (`web/` + `webui/`):** `RpcDispatcher` handlers in `web/` no longer reach into `core.document.DocumentStore`, `SettingsService`, or plugin-internal bridges (`ReloadTrigger`, `DeliveryControl`). `web/` depends **only on `api/`** (plus crypto/wire framing) and translates JSON RPC frames 1:1 into `MaterioneAPI` calls.
3. **MCP Server (Long-Range Goal):** Because every action on `MaterioneAPI` is typed, actor-attributed, fail-soft, and backed by `SelfDescription` / `ParameterSchema` metadata, an MCP server adapter can automatically expose every API operation and content schema as MCP Tools and Resources without touching plugin internals.

---

## 2. Reconciling "API-Driven" with D1 (Files Canonical) & D6 (One Pipeline)

Making the plugin API-driven **never** violates D1 or D6:

- **API mutations write to canonical files (D1):** Calling `api.authoring().createResource(...)` or `api.authoring().setFields(...)` does *not* inject ephemeral in-memory objects into the live runtime. It executes a lossless YAML transaction via the document store on `content/packs/<pack>/...`, records a revision + audit entry, and triggers the pipeline.
- **Registration vs. Authoring:**
  - *Extensibility registration (D5):* Registering a new `ContentType`, `Mechanic`, `ActionType`, `ConditionType`, or `DeliveryBackend` happens through `api.registries()` during the registration window.
  - *Content creation & modification (D1/D6):* Adding an item, adding a block, or changing a parameter happens through `api.authoring()`, which mutates the pack's YAML/assets on disk and runs the single compile/apply pipeline.
- **No direct-to-runtime shortcut (D6):** Both the web editor and future MCP tools go through `api.authoring()` → disk → `api.pipeline().reload(...)` → shadow-swap.

---

## 3. Target `MaterioneAPI` Surface Design

`MaterioneAPI` expands from a 4-method read stub into a domain-partitioned service facade. Every sub-API resides in `dev.materione.api.*` and uses pure, immutable Java records for requests and responses.

```java
package dev.materione.api;

public interface MaterioneAPI {
    String version();
    PlatformDescriptor platform();
    DiagnosticsBus diagnostics();
    SchedulingGateway scheduling();

    // 1. Extensibility & Schema Introspection (D5)
    RegistryView registries();

    // 2. Content & Document Authoring (D1, master §5.6, §9.4)
    AuthoringApi authoring();

    // 3. Binary Asset Management (web.md §4.2, §4.3)
    AssetApi assets();

    // 4. Pipeline & Validation Control (D6, plugin.md §4)
    PipelineApi pipeline();

    // 5. Pack Hosting & Release Promotion (master §8.4, plugin.md §4.6)
    DeliveryApi delivery();

    // 6. Live Runtime & World Operations (runtime-systems.md)
    RuntimeApi runtime();

    // 7. Server Health, Settings, Audit & Sessions (plugin.md §5, §7.1)
    AdminApi admin();
}
```

### 3.1 Cross-Cutting Execution Context (`ActorContext` & `ApiResult`)

Because actions can be triggered by a console command, an in-game player, a web editor session, an addon, or an MCP tool call, every mutating or privileged API call accepts an `ActorContext`:

- **`ActorContext`:** `{ String actorId, Kind kind (PLAYER, CONSOLE, EDITOR_SESSION, ADDON, MCP), Optional<UUID> playerUuid, Predicate<String> permissionChecker }`.
  - Enforces the master §9.2 permission taxonomy (`materione.editor`, `materione.edit.<pack>`, `materione.pipeline`, `materione.admin`, `materione.give`, `materione.dev`) inside the API implementation rather than scattering checks across command executors and web handlers.
  - Automatically populates `storage/audit.log` and document revision journals (`actor` field).
- **`ApiResult<T>`:** Fail-soft return wrapper `{ boolean ok, T value, List<Diagnostic> diagnostics }`. API calls never throw unchecked exceptions for domain errors (syntax errors, revision conflicts `MTR-0034`, missing files `MTR-0009`); they return structured `Diagnostic` records with stable `MTR-####` codes.

### 3.2 `RegistryView` & Extensibility SPI (`dev.materione.api.registry`, `dev.materione.api.content`)

Currently, `ContentType` lives in `dev.materione.core.content.ContentType`, which prevents third-party addons (restricted to `api/`) from registering custom content types without touching `core/`.

- **Promote SPIs to `api/`:**
  - `ContentType`, `PackEmitter`, `Allocations`, `AllocatedRange`, `CompileRejectException` move from `core.content` to `dev.materione.api.content`.
  - ECA `ActionExecutor` / action & condition registration contracts expose clean SPIs in `dev.materione.api.behavior`.
- **Typed Registry Keys:** Standard `RegistryKey` constants (`RegistryKeys.CONTENT_TYPES`, `RegistryKeys.MECHANICS`, `RegistryKeys.ACTIONS`, `RegistryKeys.CONDITIONS`, `RegistryKeys.DELIVERY_BACKENDS`) live in `api/`.
- **Window Management:** `Registry<T>` remains open during bootstrap Phase 4 and reload re-checks; built-in registrations (`ItemType`, `BlockType`, `FurnitureType`, `GlyphType`, `SoundType`, `PaintingType`, `EffectType`, `RecipeType`, `NativeMechanics`) register strictly through `api.registries()`.

### 3.3 `AuthoringApi` (`dev.materione.api.authoring`)

Describes every operation that reads or mutates content packs, folders, documents, and parameters on disk:

| Method | Description | Current Location to Consolidate |
|---|---|---|
| `listPacks()` / `createPack(actor, packId)` | Enumerate or scaffold `content/packs/<id>/pack.yml`. | `DocumentStore`, `EditorContentOps` |
| `listFolders(pack, type)` / `createFolder` / `moveFolder` / `deleteFolder` | Manage organizational subdirectories inside a content type. | `DocumentStore`, `EditorContentOps` |
| `listResources(ResourceFilter)` | Query resources by pack, content type, folder, or substring search. | `DocumentStore.listResources` |
| `getDocument(pack, path)` | Read raw YAML, parsed tree, current revision, SHA-256, and server-computed metadata (e.g. allocated glyph codepoints). | `DocumentStore.read` + `EditorTransportService.computedMeta` |
| `getHistory(pack, path, limit)` | Read per-document revision journal (`storage/revisions/`). | `DocumentStore.history` |
| `createDocument(actor, CreateDocumentRequest)` | Create a YAML document from a type's curated scaffold. | `EditorContentOps` (`edit.create`), `Scaffolds` |
| `createResource(actor, CreateResourceRequest)` | Transactional onboarding/resource creation (optional pack creation + scaffold + initial fields + single reload). | `EditorContentOps` (`authoring.createResource`) |
| `duplicateResource(actor, DuplicateResourceRequest)` | Clone an existing resource under a new ID/path. | `EditorContentOps` (`authoring.duplicateResource`) |
| `putDocument(actor, PutDocumentRequest)` | Revision-guarded raw YAML write + targeted reload. | `DocumentStore.write` (`edit.put`) |
| `setScalar(actor, SetScalarRequest)` | Lossless single-field or scalar-list patch preserving comments and formatting (§5.6). | `DocumentStore.setScalar` (`edit.setScalar`) |
| `setFields(actor, SetFieldsRequest)` | Atomic batch of scalar/structured field patches (e.g. item studio save, furniture hitboxes, recipe maps) → 1 revision, 1 audit, 1 reload. | `DocumentStore.setFields` (`authoring.setFields`) |
| `moveDocument(actor, MoveDocumentRequest)` | Rename/move a document across folders with truthful `move-from` journal lineage. | `DocumentStore.move` (`edit.move`) |
| `deleteDocument(actor, DeleteDocumentRequest)` | Revision-guarded deletion + targeted reload. | `DocumentStore.delete` (`edit.delete`) |
| `saveModelTransaction(actor, SaveModelRequest)` | Atomic Blockbench save-back: stages model JSON, textures, `.bbmodel`, `.materione/thumbnails/`, `.materione/model-links.json`, and patches owner field. | `AuthoringModelOps` (`authoring.saveModel`) |
| `validateDraft(ValidateDraftRequest)` | Dry-run syntax, schema, and reference validation on unsaved content without touching disk. | `EditorContentOps` (`validate.dryRun`) + `ValidateStage` |
| `queryGraph(GraphQuery)` | Inspect content graph edges ("where is `mystuff:ruby` used?", dead content, broken refs). | `ContentGraph` (new exposure) |
| `subscribeChanges(Consumer<DocumentChangeEvent>)` | Subscribe to external or internal document changes (file watcher + API saves). | `PackWatcher` |

### 3.4 `AssetApi` (`dev.materione.api.assets`)

Describes all binary asset operations under `content/packs/<pack>/assets/` (textures, models, sounds):

- `listAssets(pack, AssetFilter)` — list relative paths, sizes, hashes, and MIME/types.
- `getAsset(pack, path)` — read binary bytes (or stream/chunk descriptors for M5).
- `putAsset(actor, pack, path, bytes)` — size-capped, path-sanitized, atomic write + audit + preview reload.
- `moveAsset(actor, pack, fromPath, toPath)` — atomic rename + audit + reload.
- `deleteAsset(actor, pack, path)` — atomic delete + audit + reload.

### 3.5 `PipelineApi` (`dev.materione.api.pipeline`)

Replaces `web.ReloadTrigger` and direct `PipelineService` calls in commands:

- `reload(actor, ReloadRequest)` — accepts `ReloadScope` (`Everything`, `OnePack`, `FileSet`) and delivery mode (`PUBLISH_DEFAULT` vs `CANDIDATE_PREVIEW` for a session owner). Returns `CompletableFuture<ApiResult<ReloadSummary>>`.
- `lastApplyReport()` — returns the latest `ApplyReport` (items/blocks/furniture added/removed/changed, recipes toggled, staged restart-required entries).
- `pendingRestartChanges()` — lists changes staged for next boot (`MTR-0028/0029`).
- `quarantinedResources()` — lists all currently quarantined resources and their `Diagnostic` reasons.
- `subscribeReloads(Consumer<ReloadEvent>)` — event stream for connected editors, CLI waiters, or MCP listeners.

### 3.6 `DeliveryApi` (`dev.materione.api.delivery`)

Replaces `web.DeliveryControl` and direct `DeliveryService` calls:

- `status()` — returns `DeliveryStatus(String publishedSha256, String candidateSha256, String packUrl, boolean running)`.
- `publishCandidate(actor, expectedCandidateSha256)` — atomically promotes a previewed candidate pack hash to `published.sha256` and distributes to online players.
- `sendPack(actor, UUID player, PackTarget)` — sends published or candidate pack to a specific player.

### 3.7 `RuntimeApi` (`dev.materione.api.runtime`)

Exposes live, shadow-swapped content definitions and world interactions (currently scattered across `MaterioneCommand`, `DevCommands`, `CustomBlockService`, `FurnitureService`, `MaterioneStacks`):

- **Live Catalog Queries:**
  - `items()`, `item(NamespacedId)`, `resolveItem(String query)`
  - `blocks()`, `block(NamespacedId)`
  - `furnitures()`, `furniture(NamespacedId)`
  - `glyphs()`, `effects()`, `sounds()`, `paintings()`, `recipes()`
- **Item Operations (`ItemRuntimeApi`):**
  - `buildItemStack(NamespacedId id, int amount)` / `identifyItemStack(ItemStack)`
  - `giveItem(ActorContext, NamespacedId id, UUID targetPlayer, int amount)` (Folia-safe via `SchedulingGateway`)
- **World Placement & Inspection (`WorldRuntimeApi`):**
  - `placeBlock(ActorContext, NamespacedId blockId, WorldPosition pos)`
  - `removeBlock(ActorContext, WorldPosition pos)`
  - `placeFurniture(ActorContext, NamespacedId furnitureId, WorldLocation loc)`
  - `removeFurniture(ActorContext, WorldPosition pos)`
  - `inspectAt(WorldPosition pos)` — returns `PlacementInspection` (custom block/furniture ID, chunk-PDC state, index state, orphan status).
  - `sweepOrphans(ActorContext, boolean fix)` — scans loaded chunks for orphaned placements (`MTR-0026`).
- **Media & Text (`MediaRuntimeApi`):**
  - `formatText(String miniMessageWithGlyphsAndEffects)` — resolves `<glyph:...>` and `<effect:...>` tags into Adventure `Component`.
  - `playSound(ActorContext, UUID player, String soundKey, float volume, float pitch)`
  - `placePainting(ActorContext, UUID player, NamespacedId variantId)`

### 3.8 `AdminApi` (`dev.materione.api.admin`)

Consolidates `AdminOps`, `Doctor`, `SettingsService`, `AuditLog`, and `EditorCommands`:

- `doctor()` — returns a structured `DoctorReport` (platform descriptor, pack format bounds, live counts, quarantined counts, allocation pressure per namespace, orphan counts, pending restart count, delivery status, recent errors/warnings). Both `/materione doctor` and the web dashboard render this exact record.
- `getSettings(ActorContext)` / `putSettings(ActorContext, content, expectedSha256)` — hash-guarded, pre-validated (`SettingsService.validateDraft`) settings read/write.
- `recentAudit(int limit)` — query `storage/audit.log`.
- `editorSessions()` — `openSession(ActorContext)`, `status()`, `approve(ActorContext, noncePrefix)`, `deny(ActorContext, noncePrefix)`, `revokeAll(ActorContext)`, `stopListener(ActorContext)`.

---

## 4. Consumer Refactoring & Architectural Enforcement

### 4.1 Consumer 1: Commands & File Watcher (`plugin/`)

- `MaterioneCommand`, `DevCommands`, `EditorCommands`, and `ContentBrowser` hold a reference to `MaterioneAPI` instead of reaching into `MaterionePlugin` sub-services (`pipeline()`, `customBlocks()`, `furniture()`, `delivery()`).
- Example: `/materione doctor` calls `api.admin().doctor()` and formats the returned `DoctorReport` as chat components.
- Example: `/materione give` calls `api.runtime().items().giveItem(actor, id, player, amount)`.
- Bukkit ServiceManager registration: `MaterionePlugin` registers `MaterioneAPI.class` with Bukkit's `ServicesManager` so external plugins/addons can obtain it via standard `Bukkit.getServicesManager().load(MaterioneAPI.class)`.

### 4.2 Consumer 2: Web Panel (`web/`)

- **Delete ad-hoc bridge interfaces in `web/`:** `ReloadTrigger`, `DeliveryControl`, `EditorContentOps.ComputedMeta`, and `TypeMeta` are superseded by `MaterioneAPI`.
- **Decouple `web/` from `core/`:** `AdminOps`, `AssetOps`, `AuthoringModelOps`, `DeliveryOps`, and `EditorContentOps` are rewritten to take `MaterioneAPI` (or its sub-interfaces `AuthoringApi`, `AssetApi`, `PipelineApi`, `DeliveryApi`, `AdminApi`).
- **New ArchUnit Ban Rule (R9 — `WEB_ONLY_CONSUMES_API`):**
  - Classes in `dev.materione.web..` may only depend on `dev.materione.api..` (plus JDK, Gson, and Undertow). Depending on `dev.materione.core..`, `dev.materione.content..`, `dev.materione.pack..`, or `dev.materione.plugin..` fails the build.
  - This mechanically guarantees that the Web Editor cannot perform *any* action that is not in `MaterioneAPI`.

### 4.3 Consumer 3: Future MCP Server Readiness (`mcp/` or external adapter)

Because every operation in `MaterioneAPI` is modeled as a self-contained request/response record with `SelfDescription` schemas:

- **Introspectable Schemas:** `api.registries()` provides `SelfDescription` and `ParameterSchema` for every content type, mechanic, action, and condition — mapping directly to JSON Schema for MCP tool input definitions.
- **Transport Agnostic:** An MCP server (either embedded stdio/SSE or bridging over the signed WebSocket RPC protocol) can expose:
  - *MCP Resources:* `materione://packs`, `materione://pack/{pack}/resources/{type}`, `materione://diagnostics`, `materione://doctor`
  - *MCP Tools:* `create_resource`, `update_resource_fields`, `validate_draft`, `save_model`, `upload_asset`, `reload_pipeline`, `publish_pack`, `give_item`, `place_block`, `inspect_world`
- Zero additional plugin hooks will be needed when the MCP server is built.

---

## 5. Phased Implementation Plan

> **As built (M5, 2026-10):** all five phases shipped. Phases 1–4 landed exactly as
> planned; phase 5's command rewiring covers `doctor`, `reload` and the editor session
> control (the web dashboard's future data source is `AdminApi.doctor()`), while the dev
> subcommands, `give` and the in-game browser GUI keep their plugin-internal wiring until
> `RuntimeApi` results carry the exact operator-facing detail those surfaces render
> (ambiguous-query candidates, placement status lines, GUI catalog entries) — tracked as
> the follow-up tail of the API-first issue. `MaterionePlugin` retains its getters for
> those in-module consumers; the *public* surface is the ServicesManager-registered
> `MaterioneAPI`.

Each phase is a self-contained, testable slice that keeps all existing unit tests, ArchUnit rules, and headless-Paper harness gates green.

### Phase 1 — Promote Core SPIs & Domain Records to `api/`
**Goal:** Move the contracts needed to describe content, pipeline scopes, and runtime state into `api/` with zero breaking changes to existing behavior.

1. Move `ContentType`, `PackEmitter`, `Allocations`, `AllocatedRange`, and `CompileRejectException` from `dev.materione.core.content` to `dev.materione.api.content`.
2. Promote `ReloadScope` (`Everything`, `OnePack`, `FileSet`) and `ApplyReport` / `ReloadSummary` records to `dev.materione.api.pipeline`.
3. Create `ActorContext` (`dev.materione.api.auth`) and `ApiResult<T>` (`dev.materione.api`).
4. Add standard `RegistryKeys` (`CONTENT_TYPES`, `MECHANICS`, etc.) to `dev.materione.api.registry`.
5. **Verification:** `./gradlew test` passes; R3 (`API_IS_INTERNAL_DEPENDENCY_FREE`) remains green.

### Phase 2 — Authoring, Assets, Pipeline & Delivery APIs (`AuthoringApi`, `AssetApi`, `PipelineApi`, `DeliveryApi`)
**Goal:** Every file, asset, compile, and delivery operation is implemented behind `MaterioneAPI`.

1. Define `AuthoringApi`, `AssetApi`, `PipelineApi`, and `DeliveryApi` in `api/`.
2. Extract the transactional model-saving logic from `web/rpc/ops/AuthoringModelOps` and asset I/O from `web/rpc/ops/AssetOps` into `core/document/` (e.g. `ModelTransactionService`, `AssetStore`) so `core` owns all filesystem mutations (D1) and `web` owns only RPC serialization.
3. Move `Scaffolds` from `web` into `core` (or derive scaffolds from `ContentType.describe()`).
4. Implement `AuthoringApiImpl`, `AssetApiImpl`, `PipelineApiImpl`, and `DeliveryApiImpl` in `core/` and `plugin/`.
5. **Verification:** Unit tests for `AuthoringApi` and `AssetApi` covering revision conflicts (`MTR-0034`), lossless `setScalar`/`setFields` round-trips, and atomic rollback on model transactions.

### Phase 3 — Runtime & Admin APIs (`RuntimeApi`, `AdminApi`)
**Goal:** Every in-game action, world placement, item stack creation, settings edit, and doctor query is exposed on `MaterioneAPI`.

1. Define `RuntimeApi` (items, blocks, furniture, glyphs/effects/sounds/paintings, world inspect/sweep) and `AdminApi` (structured `DoctorReport`, settings get/put, audit log, editor session control) in `api/`.
2. Implement `RuntimeApiImpl` and `AdminApiImpl` bridging `ContentRuntime`, `CustomBlockService`, `FurnitureService`, `SettingsService`, `AuditLog`, and `EditorTransportService`.
3. Register `MaterioneAPI` with Bukkit's `ServicesManager` during bootstrap in `MaterionePlugin`.
4. **Verification:** Contract tests in `plugin/` verifying `DoctorReport`, `RuntimeApi` item resolution, and settings validation via the API.

### Phase 4 — Rewire Web RPC (`web/`) + Enforce ArchUnit Rule R9
**Goal:** The web editor accesses *only* `MaterioneAPI`, never plugin or core internals.

1. Refactor `EditorContentOps`, `AuthoringModelOps`, `AssetOps`, `AdminOps`, and `DeliveryOps` in `web/rpc/ops/` to accept only `MaterioneAPI` (and construct `ActorContext` from the authenticated `EditorSession`).
2. Delete `web.ReloadTrigger`, `web.DeliveryControl`, and `web.TypeMeta` (replaced by `api.pipeline()`, `api.delivery()`, and `api.registries()`).
3. Remove `implementation(project(":core"))` from `web/build.gradle.kts` so `web` depends *only* on `:api`.
4. Add **Ban Rule R9 (`WEB_ONLY_CONSUMES_API`)** to `MaterioneArchRules` and prove it with a planted violation test.
5. **Verification:** Full M3/M4 web editor harness gate (`PaperBootsIT` handshake, studio edits, Blockbench save-back, asset upload, live reload < 2 s) passes unchanged.

### Phase 5 — Rewire CLI Commands (`plugin/command/`) & Update Architecture Docs
**Goal:** Commands and in-game GUIs become pure `MaterioneAPI` consumers; documentation reflects the API-first spine.

1. Refactor `MaterioneCommand`, `DevCommands`, `EditorCommands`, and `ContentBrowser` to invoke `plugin.api().admin()`, `plugin.api().pipeline()`, and `plugin.api().runtime()`.
2. Remove internal getter leaks on `MaterionePlugin` (`customBlocks()`, `furniture()`, `placements()`, `restartStaging()`, etc.), leaving `plugin.api()` as the single operational entrypoint.
3. Amend `docs/architecture.md` (§10) and `docs/architecture/plugin.md` (§3, §11) to record the R9 boundary (`web → api` only) and the full `MaterioneAPI` facade contract.
4. **Verification:** Full `./gradlew check` + headless-Paper integration suite (`PaperBootsIT`, `PaperWorldPersistenceIT`, `ContentPerfBudgetTest`) passes.

---

## 6. Exit Criteria Summary

- [ ] **100% Action Coverage:** Every action possible via YAML watcher, `/materione` commands, or Web Editor RPC has a 1:1 typed method on `MaterioneAPI`.
- [ ] **Zero Bypass in `web/`:** `web/build.gradle.kts` depends only on `:api` (enforced by Gradle + ArchUnit rule R9).
- [ ] **Zero Bypass in `plugin/command/`:** All `/materione` subcommands delegate to `MaterioneAPI` with a command-derived `ActorContext`.
- [ ] **Addon & MCP Ready:** Third-party addons can register `ContentType`s, `Mechanic`s, and ECA extensions purely against `:api`, and every authoring/runtime operation is self-described and JSON-serializable for a future MCP server.
- [ ] **D1–D11 Preserved:** Files remain the sole source of truth, all mutations funnel through the single pipeline, and lossless YAML round-trips remain byte-identical.
