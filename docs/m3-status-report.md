# M3 Status Report — The Web Editor v1

> **What this is.** A point-in-time audit (2026-09-04) of milestone M3 against the
> codebase: is it implemented, and what is still needed. Sources of truth:
> [`roadmap.md`](roadmap.md#m3--the-web-editor-v1-authoring-without-yaml),
> [`architecture/web.md`](architecture/web.md),
> [`architecture/build.md`](architecture/build.md), and the
> [editor quickstart](editor-quickstart.md). Every claim below was verified against
> the code on `main` (clean tree); file references are evidence, not decoration.

## Verdict

**M3 is implemented.** Every capability the roadmap scopes for M3 — direct-mode
embedded listener, the editor SPA with document store and revisions, the live
preview loop, and Blockbench integration v1 (including the two bullets once
deferred to early M4, later closed out per the roadmap) — exists as concrete,
tested code. Nothing remains that blocks the milestone. The outstanding items are
either explicitly deferred to M5+ by the docs, or minor polish.

## Verification by scope area

| M3 scope (roadmap + web.md + build.md) | Status | Evidence |
|---|---|---|
| Direct mode: embedded HTTP/WS on Undertow, loopback default, SPA served from the jar | ✅ implemented | `web/src/main/java/dev/materione/web/EditorListener.java` (bind + fail-soft ports + MTR-0036 warning on non-loopback binds; classpath `/webui` statics); Undertow shaded + relocated in `build-logic/.../materione.plugin-conventions.gradle.kts` |
| Wire protocol: canonical JSON, Ed25519 sign-after-encrypt, strict per-direction sequences, AES-256-GCM, P-256 ECDH + HKDF-SHA256, key confirmation (transcript excludes `confirm`), wrapping key only in URL fragment | ✅ implemented | `web/.../protocol/` (`Wire`, `FrameCodec`, `Envelope`, `SessionCrypto`, `Hkdf`, `SessionBlob`, `HelloReplyMessage`); golden fixture `fixtures/protocol/editor-protocol-v1.json` tested from **both** sides: JVM `GoldenProtocolFixturesTest` and `webui/test/protocol.spec.ts` |
| In-game trust: `/materione editor open/status/approve/deny/revoke/stop`, clickable nonce-keyed Approve/Deny prompt, 120 s timeout | ✅ implemented | `plugin/.../command/EditorCommands.java`, `web/TrustPrompt.java`, `plugin/.../runtime/EditorTransportService.java`; diagnostics MTR-0030..0037 defined in `core/.../diagnostics/DiagnosticCodes.java` and emitted |
| Document store: per-document revision journals in `storage/revisions/`, external-edit journaling (`syncExternal`), stale-write rejection (MTR-0034), audit log at `storage/audit.log`, lossless `edit.setScalar` (scalars **and** scalar lists) | ✅ implemented | `core/.../document/` (`DocumentStore`, `LosslessYamlDocument`, `AuditLog`, `PackWatcher`, `DocumentConflictException`) |
| Editor SPA v1: workbench shell (rail, dock, status bar, header with pack switcher + release state), Content explorer per registered type (folders, breadcrumbs, layouts, search, keyboard nav, full file/folder CRUD), studios, diagnostics center, server view, publish flow | ✅ implemented | `webui/src/` — Svelte 5 + Vite + TypeScript (`webui/package.json`), hermetic Node 22.17 build (`webui/build.gradle.kts`), embedded into the shaded jar by `plugin/build.gradle.kts` |
| Form-first authoring from registry self-description: `authoring.createResource` / `duplicateResource` / `setFields` (one revision, one audit, one reload) / `saveModel` (atomic, rollback on conflict/failure) | ✅ implemented | `web/.../rpc/ops/EditorContentOps.java`, `web/.../rpc/ops/AuthoringModelOps.java`; labels/sections/structured shapes declared per type in `content/` (`ItemType`, `BlockType`, `FurnitureType`, `RecipeType` via `ParameterSchema.EditorMetadata`) |
| Live preview loop: save → targeted reload → in-game, **under 2 s** | ✅ implemented | asserted in `harness/.../EditorHandshakeRunner.java` (save → `push.reload` < 2 s; item watched live via `dev stack sample:editor_gem`) |
| Blockbench v1: pinned unmodified v5.1.6 vendored, contextual workbench (no start screen/format picker, `.bbmodel` reuse or synthesized `java_block` project), postMessage-only bridge (`collect`/`importProject`/`thumbnail`, origin+source validated), atomic save-back, thumbnail cache under `.materione/thumbnails/` | ✅ implemented | `webui/public/blockbench/PIN.md` (v5.1.6, tag + commit), `webui/public/materione-bridge.js`, `webui/src/lib/studio/ModelWorkbench.svelte`, `blockbench/protocol.ts`; `.bbmodel` + `.materione/` excluded from pack.zip (`pack/.../PackCompiler.java`); relationship manifest `.materione/model-links.json` written only by `authoring.saveModel` |
| External-edit feed: WatchService pump over `content/packs/`, ~500 ms debounce, `push.document.changed`, "changed on disk" banner vs. clean-tab auto-reload | ✅ implemented | `core/.../document/PackWatcher.java` (R5-clean via scheduling gateway, `editor_file_watcher` toggle), `DocumentStore.syncExternal`, UI in `webui/src/lib/components/DocEditor.svelte` + `external.ts` (vitest-pinned) |
| Delivery candidate/published channel: only the session-opening player gets candidate downloads, Publish promotes exactly the previewed hash, stale publish fails visibly, content-addressed snapshots + atomic pointer under `storage/delivery/`, bounded retention | ✅ implemented | `plugin/.../runtime/DeliveryService.java`, `web/.../rpc/ops/DeliveryOps.java` (`delivery.status`/`delivery.publish`), `delivery/.../SelfHostBackend.java` (pin/trim) |
| Ban rule R8: wire protocol reachable only from `web/` | ✅ implemented | `testing/src/testFixtures/.../arch/MaterioneArchRules.java` (`WIRE_PROTOCOL_ONLY_IN_WEB`), applied in `web/.../ArchitectureTest`; proven by planted violation per build.md §5 |
| Tests: vitest pure-logic suites (pixels, tree, external-feed, bridge, protocol fixtures, layout-invariant CSS), JVM golden tests, CI harness gate | ✅ implemented | 11 specs under `webui/test/` (incl. `layout.spec.ts` asserting shrinkable tracks / no horizontal scroll / flex stacking); `harness/.../PaperEditorHandshakeIT` runs in CI (`.github/workflows/ci.yml`) |

**Exit criterion** ("a new user creates an item with a custom texture, in the
browser, without docs"): exercised end-to-end on real Paper by the harness gate —
onboarding transaction (`authoring.createResource` creating pack + item + display
name in one op), item creation, texture upload, targeted reload, in-game
verification, plus the workbench extension (`edit.create`/`edit.move`/`assets.move`/
`pipeline.reload`/settings/audit) and the closeout extension (external-edit push +
journaled external revision; Blockbench save-back via `assets.put` + `edit.setScalar`
watched going live). This audit did not re-run the suite; CI runs
`:harness:integrationTest` on every push and `main` was clean with recent
M3-polish commits at audit time.

## What is still needed

Nothing blocks M3. Grouped by nature:

### Deferred/planned by the docs themselves (no code yet, by design)

1. **`authoring.describe`** — marked *Planned* in web.md §4.2 ("completes the
   capability handshake"); zero occurrences in code.
2. **Manifest + content-addressed chunk flow for large payloads** (web.md §4.3) —
   relay-transport machinery that lands with M5; today assets ride inside sealed
   frames under the 2 MiB v1 cap (`AssetOps.MAX_ASSET_BYTES`), which covers the
   textures/models v1 needs.
3. **Ops listed as "still illustrative-only"** (web.md §4.2): where-used graph,
   import pack, pre-apply report / apply confirm via the editor, preview renders,
   `close`.
4. **Pagination/filtering for resource and asset listings** — web.md ties this to
   relay-mode scale ("before relay mode makes eager full-pack traversal a scale
   boundary"), i.e. M5.

### Minor nuances found during verification (candidates for a small follow-up)

5. **Ed25519 in the browser is native WebCrypto only** (Chrome 133+, Safari 17+,
   Firefox 129+) — no JS fallback library. Deliberate and documented in-code
   (`webui/src/lib/protocol/crypto.ts`) and on the login screen; a fallback would
   widen the supported-browser floor.
6. **"Trusted workstation" opt-in** (web.md §5.2: editor persists its signing key,
   plugin whitelists it to skip repeat prompts): `/materione editor revoke` exists,
   but the skip-prompt whitelist path was not traced end-to-end in this audit —
   confirm the implementation or adjust the doc.
7. **Blockbench bridge hardening nit:** the iframe→parent direction checks
   `event.source === window.parent` but has no origin allowlist inside the bridge
   itself (parent-side checks are strict; the iframe is same-origin, and the
   bridge's outbound target origin is locked via `configure`).
8. **Cosmetic:** the header "preview ready" chip does not render the candidate
   hash verbatim (the hash is used as the publish guard); and the mojibake guard
   is a JUnit test (`plugin/.../SourceEncodingTest.java`), not a vitest spec as the
   roadmap wording implies — it works, it just lives in the other suite.
9. **Doc nit:** roadmap headers for M0/M1/M2 carry "✅ shipped + date"; the M3
   header does not, even though the body and the closeout notes declare the
   milestone complete.

## Bottom line

M3's promise — *a T2/T3 user builds content with zero YAML knowledge* — is met in
code and pinned by tests on both sides of the wire. The next milestone (M4:
glyphs, fonts, sounds, paintings) is where new capability work belongs; the items
above are either scheduled for M5 by design or are small polish.
