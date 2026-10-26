import { expect, test, type Page } from '@playwright/test';

/**
 * The real SPA entry (index.html → App.svelte), with `/host.json` answered the
 * way each host answers it: the cloud Worker's `_headers` adds the marker, the
 * plugin listener does not (web.md §6, cloud landing). The CDN is never contacted.
 */
async function serveAs(page: Page, host: 'cloud' | 'plugin'): Promise<void> {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
  await page.route('**/host.json', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: host === 'cloud' ? { 'X-Materione-Host': 'cloud' } : {},
    body: '{}',
  }));
}

test('the cloud host shows the landing card instead of the paste-a-link form', async ({ page }) => {
  await serveAs(page, 'cloud');
  await page.goto('/');

  await expect(page.getByRole('heading', { level: 1, name: 'Materione' })).toBeVisible();
  await expect(page.getByText('Web content editor', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'View Demo' })).toBeVisible();
  await expect(page.getByText('To start a new session, run /materione editor in-game')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Open a session' })).toHaveCount(0);
});

test('View Demo opens the real workbench over the in-browser sample pack', async ({ page }) => {
  await serveAs(page, 'cloud');
  const apiCalls: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/v1/') || path.startsWith('/s/') || path === '/editor/ws') apiCalls.push(path);
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'View Demo' }).click();

  await expect(page).toHaveURL(/\/\?demo$/);
  const banner = page.getByRole('status').filter({ hasText: 'Sample content in this tab only' });
  await expect(banner).toBeVisible();
  await expect(page.getByRole('combobox').first()).toHaveValue('demo_pack');
  await expect(page.getByText('Ruby Blade', { exact: true }).first()).toBeVisible();

  // A reload of /?demo starts the demo over directly.
  await page.reload();
  await expect(banner).toBeVisible();

  page.on('dialog', (dialog) => void dialog.accept());
  await banner.getByRole('button', { name: 'Exit demo' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('button', { name: 'View Demo' })).toBeVisible();
  // No relay, session or socket traffic: the demo is client-side only.
  expect(apiCalls).toEqual([]);
});

test('a plugin-served editor keeps the paste-a-link card and ignores ?demo', async ({ page }) => {
  await serveAs(page, 'plugin');
  await page.goto('/?demo');

  await expect(page.getByRole('heading', { name: 'Open a session' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'View Demo' })).toHaveCount(0);
  await expect(page.getByText('Sample content in this tab only')).toHaveCount(0);
});
