import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [svelte()],
  optimizeDeps: {
    entries: ['browser/fixture.html'],
  },
  publicDir: false,
});
