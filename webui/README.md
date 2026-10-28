# Materione Editor SPA

The browser editor (M3): Svelte 5 + Vite + TypeScript, built to static files and
embedded into the plugin jar under `webui/` so direct mode works fully offline.
The shell is a VS Code-style workbench: activity rail (Explorer / Diagnostics /
Server), sidebar views, tabbed editors, a structured pipeline dock, and a status
bar.

- `src/lib/protocol/` — the wire protocol's browser half (canonical JSON, WebCrypto
  Ed25519/P-256/AES-GCM/HKDF, handshake + sealed frame channel). Pinned byte-for-byte
  by `test/protocol.spec.ts` against `fixtures/protocol/editor-protocol-v1.json` — the
  same golden file the JVM tests consume. The WebSocket transport buffers frames that
  arrive during the handshake→channel handover (a drop would desync the strict
  receive sequence).
- `src/lib/state.svelte.ts` — the rune stores (workspace, tabs, diagnostics,
  activity, server) and their actions; `src/lib/events.ts` fans the channel's
  single-slot callbacks out to every pane.
- `src/lib/components/` — the shell (Workbench, TabBar), Login (capability URL),
  DocEditor (form-first: schema-driven fields, texture/model pickers, Advanced
  section with the raw YAML; the full YAML view is an explicit dev tool), AssetPicker,
  AssetPreviewTab, SettingsTab, BlockbenchTab (the embedded pinned Blockbench build:
  save-back with texture mapping + quick-attach, `.bbmodel` import — bridged over
  postMessage only), ContextMenu, PromptDialog.
- `src/lib/views/` — ExplorerView (file tree over documents + assets, right-click
  menus: scaffolded create, rename/move, delete, upload, open Blockbench),
  DiagnosticsView (live `push.diagnostic` feed with filters + click-through),
  ServerView (control center: status, manual reloads, audit trail).
- `src/lib/dock/ActivityDock.svelte` — the pipeline log at the bottom.
- `src/lib/tree.ts` — pure tree building over the flat browse listings
  (vitest-pinned in `test/tree.spec.ts`).
- `src/lib/blockbench/protocol.ts` — the bridge message shapes and the pure
  save-back math (texture mapping, model normalization; vitest-pinned in
  `test/blockbench-bridge.spec.ts`). `src/lib/external.ts` — the
  `push.document.changed` decision reducer (`test/external.spec.ts`).
- `src/lib/gui/` + `src/lib/studio/gui/` — the GUI studio (M6, gui.md §6): a canvas tool for
  `guis/**`. `gui/raster/` is the TS mirror of the compiler's raster (ARGB canvas, bevel painter,
  nearest-neighbour blit, layer compositing), pinned pixel for pixel by the shared vectors in
  `fixtures/gui/raster/` (`test/gui-raster.spec.ts`); `gui/model.ts` + `commands.ts` +
  `history.ts` are the lossless document model and command stack; `scene.ts`/`snap.ts`/
  `transform.ts`/`viewport.ts` are the canvas rules; `save.ts` plans the one authoring
  transaction. The palette and inspector come from the `gui-components` registry
  description, never a component list of the studio's own.
- `public/blockbench/` — the pinned, unmodified Blockbench v5.1.6 web build
  (see its PIN.md for the pin + build recipe; upgrades are pin bumps).
  `public/materione-bridge.js` — our injected iframe bridge.

Commands (Node is provisioned by Gradle — `./gradlew :webui:npmInstall` once):

```bash
npm run dev     # Vite dev server, proxying /s and /editor/ws to 127.0.0.1:8089
npm run test    # vitest — golden protocol fixture, tree builder, signal fan-out
npm run build   # production bundle → build/spa/
```

Requires a browser with WebCrypto Ed25519 (Chrome 133+, Safari 17+, Firefox 129+) and
a secure context: plain HTTP works only on localhost.
