# Materione Glossary

One-line definitions of project vocabulary, with pointers to where each term is fully
specified. Alphabetical. If a term is used in code or docs without a definition, it belongs
here.

| Term | Definition | Spec |
|---|---|---|
| **Addon** | Third-party JAR in `plugins/Materione/addons/`, loaded in a child-first classloader, extending the plugin through the same registries built-ins use. | plugin.md §3 |
| **Allocation table** | Append-only, **durable** record (`storage/allocation.bin`) mapping content to finite pack namespaces (model ids, glyph codepoints, hijacked blockstates). The one derived artifact that is *not* disposable. | master §6.3, plugin.md §4.4 |
| **Apply (stage)** | Pipeline stage that swaps new runtime state in via shadow-swap; the only stage that schedules world mutation. | plugin.md §4.5 |
| **Asset** | Binary file (texture, model, font, sound) belonging to a pack's `assets/` tree; fingerprinted for incremental compile. | master §7.2 |
| **Authority (network)** | In network mode, the single backend that owns content files and runs the pipeline; other backends load its compiled definitions. | master §8.5 |
| **Ban test** | ArchUnit rule in `testing/` that fails the build on a module-boundary violation (schedulers, version queries, api isolation, leaf, raw threads). | build.md §5 |
| **Blob (session)** | Small encrypted record in the payload store establishing a session (session id, channel id, plugin signing key); wrapping key travels in the URL fragment. | web.md §4.1 |
| **build-logic/** | Gradle convention plugins + custom tasks ("build/" is Gradle's output dir). | build.md §3 |
| **Capability descriptor** | Resolved-at-boot description of what the current server version supports; powers platform validation and editor feature hints. | plugin.md §6, master §8.2 |
| **Channel** | A Durable Object hosting one relay session's two-party message flow. | web.md §3.2 |
| **Chunk PDC** | Persistent data container attached to a chunk (via CustomBlockData); the storage-of-truth for placed blocks/furniture. | master §8.3 |
| **Code** | Unguessable ≥100-bit short string identifying a payload-store blob; possession = access. | web.md §5.3 |
| **Content graph** | The validated network of all resources and their typed references; the heart of the product ("the intertwining, made inspectable"). | master §5.3 |
| **Content type** | A registered kind of resource (item, block, furniture, glyph, HUD, menu, dialog, recipe, sound, painting, …), extensible by addons. | master §5.1, runtime-systems.md §1 |
| **Canned ECA** | A built-in mechanic implemented as a parameterized Event→Condition→Action bundle rather than custom Java; registered identically to third-party mechanics. | runtime-systems.md §2.3 |
| **Datapack artifact** | Pack output Minecraft requires in datapack form (trims, worldgen modifiers; paintings and jukebox songs on pre-1.21.6 servers) — restart-required content. On 1.21.6+ those two register through Paper's registry events instead, with no world-folder artifact. | plugin.md §4.4, runtime-systems.md §10 |
| **Definition** | The immutable, validated data of one resource, post-parse. | runtime-systems.md §1 |
| **Diagnostics** | Structured failure records (`{code, severity, message, resource, file:line, hint}`) with stable `MTR-####` codes; the fail-soft backbone. | plugin.md §5 |
| **Direct mode** | Editor transport where the plugin serves the API itself on loopback (or reverse-proxied); relay cut out; same protocol. | web.md §4.4 |
| **D1–D11** | The eleven binding architectural decisions. | master §3 |
| **ECA** | Event → Condition → Action: the single behavior model used by items, blocks, furniture, menus, dialogs, HUDs. | master D4, runtime-systems.md §2 |
| **Envelope** | Standard encrypted container format for store blobs and chunks. | web.md §5.4 |
| **Expression engine** | The one universal condition/interpolation language across all content types (single settled decision; PlaceholderAPI resolves inside strings). | runtime-systems.md §2.1 |
| **Facet** | One of the three aspects of a resource: appearance (pack-bound), behavior (server-side), metadata. | master §5.1 |
| **Fail-soft** | Content errors quarantine a resource/pack; the server never crashes, wedges, or half-applies. | master D6 |
| **Fingerprint** | Hash of an asset; drives incremental compile (unchanged asset → unchanged pack entry). | plugin.md §4.2 |
| **Fixture corpus** | `fixtures/`: data-only test inputs + golden files; not a Gradle module; grow-only (every wild bug becomes a fixture). | fixtures/README.md, build.md §6 |
| **Fragment key** | The session wrapping key carried in the URL fragment (`#...`), never sent to any server. | web.md §4.1 |
| **Harness** | `harness/`: the product-independent headless-Paper boot + console-driver + log-marker assertions behind `:harness:integrationTest`. | build.md §7 |
| **Host block** | The vanilla block whose states are hijacked to represent a custom block (note block, tripwire, chorus plant, technical bases). | runtime-systems.md §4.1 |
| **ItemModel tree** | Declarative model-definition tree (reference / range_dispatch / select / condition / composite / empty nodes) enabling per-context, per-state, time-based item models; the supported alternative to hand-writing item model JSON. | runtime-systems.md §3.1 |
| **Key-confirmation digest** | Handshake step where both sides verify they derived the same session symmetric key, catching mismatches at handshake rather than first use. | web.md §4.1 |
| **HUD** | Player-visible overlay (image/text/bar layers) rendered via the font/packet system — a native system, not boss-bar tricks. | runtime-systems.md §7 |
| **Knowledge tiers (T1–T4)** | Installer / Assembler / Creator / Power-user-dev — the progressive-disclosure user model. | master §2 |
| **Lossless round-trip** | Load → untouched save is byte-identical (comments, ordering, unknown fields); the dual-surface invariant. | master §5.6 |
| **Mechanic** | Named reusable behavior attachable to content (soulbound, backpack, lifeleech…) — canned ECA or native, same registry either way. | runtime-systems.md §2.3 |
| **Manifest (pack)** | `pack.yml`: id, version, schema version, dependencies, credits. | master §7.2 |
| **Manifest (payload)** | Content-addressed chunk list describing one uploaded asset. | web.md §4.3 |
| **Marker (harness)** | Stable `MATERIONE-*` log line (BOOT-OK, VERSION, DOCTOR-OK, BYE) asserted by the harness; test API, treated like `MTR-####` codes. | build.md §7, harness/README.md |
| **Namespace** | Per-pack unique prefix of every resource id (`packid:resource`). One namespace per pack, enforced. | master §5.1 |
| **Orphan** | A placed instance whose defining resource was removed: kept, flagged, admin-resolved — never deleted by guess. | plugin.md §4.5, runtime-systems.md §1 |
| **Pack** | The unit of modularity/distribution: namespace + manifest + content files + assets, zippable as-is. | master §5.2 |
| **Payload store** | The dumb, opaque, TTL'd encrypted-blob HTTP service (`store.` Worker + R2). | web.md §3.1 |
| **PDC tag** | The `materione:id` (or similar) persistent-data marker on an ItemStack/Entity identifying its custom resource. | runtime-systems.md §3.2 |
| **Placement item** | The item that places a block or spawns furniture: the definition's `item:` link, a same-id item resource, or the auto-generated synthetic item (runtime-only, flagged `synthetic`). | runtime-systems.md §4.2, §13 |
| **Pipeline** | The single path from files to runtime: scope → parse → validate → graph → allocate → compile → apply → deliver. | plugin.md §4 |
| **Placement index** | Acceleration cache in `generated/index/` mapping positions → placed blocks/furniture; rebuildable by chunk scan, never load-bearing truth. | plugin.md §7 |
| **Platform layer** | The only module aware of server versions; exposes capabilities, hides version churn. | plugin.md §6 |
| **Pre-apply report** | Operator-visible diff before commit: migrations, removals by policy, restart-required changes. | plugin.md §4.5 |
| **Quarantine** | Runtime state of a resource excluded from compilation/gameplay due to diagnostics; visible, never silently dropped. | master §5.4 |
| **Relay mode** | Default editor transport: server and browser both dial out to the relay services; no inbound connections (D11). | web.md §3, master D11 |
| **Resource** | The atomic unit of content: identity (`ns:id`) + type + facets + provenance. | master §5.1 |
| **Restart-required** | Change class that cannot hot-apply (datapack-baked content); staged for next boot and reported honestly. | master §6.4 |
| **Revision number** | Per-document counter rejecting stale concurrent writes (last-writer-wins *with visible warning*). | master §9.4 |
| **Self-description** | Registry-entry metadata (name, description, parameter schema, since-version) that generates editor forms, docs, and tab completion — the single schema source. | plugin.md §3 |
| **Shadow-swap** | Transactional reload: build new runtime fully, diff, swap by single volatile reference; in-flight events finish on the old snapshot; rollback = keep the old set. | plugin.md §4.5, runtime-systems.md §1 |
| **Shaped block** | Custom stair/slab/door/trapdoor/grate using the waxed-copper blockstate technique. | runtime-systems.md §4.2 |
| **Tombstone** | Allocation-table record of a retired id/codepoint/blockstate; prevents reuse against old worlds; reclaimed only by explicit compaction. | master §6.3 |
| **Trusted workstation** | Opt-in flag where an editor persists its signing key (IndexedDB) and the plugin whitelists it, skipping repeated in-game trust prompts; revocable in-game. | web.md §5.2 |
| **Trigger** | Anything that starts a pipeline run (startup, command, web save, watcher, import) — all funnel into the same entry. | master §6.5 |
| **Validation levels 1–5** | Syntax → schema → semantic (references) → platform (version gates) → build (allocation feasibility). | master §5.4 |
| **Wrapping key** | Random 256-bit key encrypting the session blob; transported only in the URL fragment. | web.md §5.2 |
