import type { UserConfig } from 'vite';

/**
 * How Vite builds the code-mode type-check Worker (src/lib/codemode/typecheck/worker.ts, codemode.md section 5.1): a module
 * Worker, so its own chunk and a static asset beside the SPA, fetched on the first agent use and never by the editor's own bundle.
 *
 * `treeshake: false` is load-bearing. With Rollup's tree-shaking the worker bundles but its compiler fails at the first check
 * ("Cannot read properties of undefined (reading 'get')" in createTypeChecker: the CommonJS `typescript` package relies on
 * module side effects that Rollup drops); the dev server and Node are unaffected, which is why the production artifact has its own
 * Playwright spec. The worker holds the compiler and a few small files, so there is nothing of ours to shake.
 *
 * Shared by vite.config.ts and by the specs that build the worker on their own.
 */
export const workerBuild: NonNullable<UserConfig['worker']> = {
  format: 'es',
  rollupOptions: { treeshake: false },
};
