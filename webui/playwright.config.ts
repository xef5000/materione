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
  // Chromium runs everything. MATERIONE_PW_BROWSERS=firefox,webkit adds those engines for the code-mode sandbox specs only (a blob
  // Worker spawned from an opaque-origin frame is the portability risk); they need `playwright install firefox webkit` first and
  // are not part of CI.
  projects: [
    { name: 'chromium' },
    ...(process.env.MATERIONE_PW_BROWSERS ?? '')
      .split(',')
      .filter((name): name is 'firefox' | 'webkit' => name === 'firefox' || name === 'webkit')
      .map((name) => ({ name, testMatch: '**/codemode-sandbox*.pw.ts', use: { browserName: name } })),
  ],
  webServer: {
    command: 'npm run dev:browser -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173/browser/fixture.html',
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
