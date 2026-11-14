import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig, searchForWorkspaceRoot } from 'vite';
import { sandboxAssets } from '../vite.sandbox';

export default defineConfig({
  plugins: [svelte(), sandboxAssets()],
  optimizeDeps: {
    // the type-check Worker imports the compiler from inside a Worker, where the dependency scan does not look
    include: ['typescript'],
    entries: ['browser/fixture.html', 'browser/typecheck.html', 'browser/sandbox.html'],
  },
  publicDir: 'public',
  server: {
    fs: { allow: [searchForWorkspaceRoot(process.cwd()), '../fixtures/golden', '../fonts/smallcaps'] },
  },
});
