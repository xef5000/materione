import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** Shared Svelte config: TS in script blocks (vite + svelte-check). */
export default {
  preprocess: vitePreprocess(),
};
