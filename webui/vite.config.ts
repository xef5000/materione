/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { searchForWorkspaceRoot } from 'vite';

// Dev-server proxy: the SPA runs on Vite's port while the plugin listener
// keeps serving /s/* (session blobs) and /editor/ws (the channel) on its own
// loopback port. Production builds are served BY the listener itself, so no
// proxy applies there.
const origin = process.env.EDITOR_ORIGIN ?? 'http://127.0.0.1:8089';

export default defineConfig({
  plugins: [svelte()],
  server: {
    // The demo imports its recorded server snapshot from the shared fixture corpus; the GUI studio reads the
    // smallcaps glyph file the Java compiler reads (its single source), so both sides draw the same font.
    fs: { allow: [searchForWorkspaceRoot(process.cwd()), '../fixtures/golden', '../fonts/smallcaps'] },
    proxy: {
      '/s': origin,
      '/editor/ws': { target: origin, ws: true },
    },
  },
  build: {
    // Under Gradle's build directory so the plugin module can consume it as
    // a conventional output (and **/build/ already covers it in .gitignore).
    outDir: 'build/spa',
    emptyOutDir: true,
    sourcemap: false,
  },
});
