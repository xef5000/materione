# Materione Editor SPA

The browser editor (M3): Svelte 5 + Vite + TypeScript, built to static files and
embedded into the plugin jar under `webui/` so direct mode works fully offline.

- `src/lib/protocol/` — the wire protocol's browser half (canonical JSON, WebCrypto
  Ed25519/P-256/AES-GCM/HKDF, handshake + sealed frame channel). Pinned byte-for-byte
  by `test/protocol.spec.ts` against `fixtures/protocol/editor-protocol-v1.json` — the
  same golden file the JVM tests consume.
- `src/lib/components/` — Login (capability URL), Dashboard (packs, resources,
  diagnostics, activity feed, assets), DocEditor (raw YAML beside schema-driven forms;
  form edits patch single scalars through the server's lossless layer).

Commands (Node is provisioned by Gradle — `./gradlew :webui:npmInstall` once):

```bash
npm run dev     # Vite dev server, proxying /s and /editor/ws to 127.0.0.1:8089
npm run test    # vitest — golden protocol fixture spec
npm run build   # production bundle → build/spa/
```

Requires a browser with WebCrypto Ed25519 (Chrome 133+, Safari 17+, Firefox 129+) and
a secure context: plain HTTP works only on localhost.
