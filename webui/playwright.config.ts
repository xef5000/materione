import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './browser/specs',
  testMatch: '**/*.pw.ts',
  outputDir: 'build/playwright-results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    browserName: 'chromium',
    viewport: { width: 1365, height: 900 },
    // Every spec runs the reduced-motion path (app.css, "Motion"): transitions and loops
    // are off, so assertions never race an animation. The motion spec opts back in.
    reducedMotion: 'reduce',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  webServer: {
    command: 'npm run dev:browser -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173/browser/fixture.html',
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
