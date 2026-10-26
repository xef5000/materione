import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig, searchForWorkspaceRoot } from 'vite';

export default defineConfig({
  plugins: [svelte()],
  optimizeDeps: {
    entries: ['browser/fixture.html'],
  },
  publicDir: false,
  server: {
    fs: { allow: [searchForWorkspaceRoot(process.cwd()), '../fixtures/golden'] },
  },
});
