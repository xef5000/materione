// SPIKE (#340) - throwaway. Its own config so the spike never rides the editor's CI job
// (`npm run test:browser`). Run: `npm run spike:codemode`.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './specs',
  testMatch: '**/*.pw.ts',
  outputDir: '../../build/playwright-spike-results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  timeout: 120_000,
  use: { baseURL: 'http://127.0.0.1:4180', browserName: 'chromium' },
  webServer: {
    command: 'node spike/codemode/server.mjs',
    cwd: '../..',
    url: 'http://127.0.0.1:4180/harness.html',
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
