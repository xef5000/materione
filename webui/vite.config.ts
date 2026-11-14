/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { searchForWorkspaceRoot } from 'vite';
import { workerBuild } from './vite.worker';
import { sandboxAssets } from './vite.sandbox';

// Dev-server proxy: the SPA runs on Vite's port while the plugin listener
// keeps serving /s/* (session blobs) and /editor/ws (the channel) on its own
// loopback port. Production builds are served BY the listener itself, so no
// proxy applies there.
const origin = process.env.EDITOR_ORIGIN ?? 'http://127.0.0.1:8089';

export default defineConfig({
  // sandboxAssets: the code-mode sandbox (frame, engine, core, wasm) as static files under sandbox/, fetched on the first agent use
  plugins: [svelte(), sandboxAssets()],
  server: {
    // The demo imports its recorded server snapshot from the shared fixture corpus; the GUI studio reads the
    // smallcaps glyph file and the vanilla default-font widths the Java compiler reads (their single sources),
    // so both sides draw and measure the same font.
    fs: {
      allow: [
        searchForWorkspaceRoot(process.cwd()),
        '../fixtures/golden',
        '../fonts/smallcaps',
        '../fonts/vanilla-ascii',
        '../shared/gui',
      ],
    },
    proxy: {
      '/s': origin,
      '/editor/ws': { target: origin, ws: true },
    },
  },
  // The code-mode type-check Worker: its own chunk, fetched on the first agent use (see vite.worker.ts).
  worker: workerBuild,
  build: {
    // Under Gradle's build directory so the plugin module can consume it as
    // a conventional output (and **/build/ already covers it in .gitignore).
    outDir: 'build/spa',
    emptyOutDir: true,
    sourcemap: false,
  },
});
