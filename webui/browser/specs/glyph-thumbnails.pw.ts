import { expect, test } from '@playwright/test';
import '../contract';

test('glyphs show decoded source textures in list and grid without per-row document reads', async ({
  page,
}, testInfo) => {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Glyphs', exact: true }).click();
  await page.getByRole('button', { name: 'Dense visual list' }).click();

  const heart = page.getByRole('option', { name: /Heart Icon/ });
  const thumbnail = heart.locator('.thumbnail img');
  await expect(thumbnail).toBeVisible();
  await expect.poll(() => thumbnail.evaluate((image) =>
    image instanceof HTMLImageElement && image.complete && image.naturalWidth === 8,
  )).toBe(true);
  await expect(heart.getByRole('img', { name: 'Heart Icon', exact: true }))
    .toHaveAttribute('title', 'Heart Icon: Source texture preview');
  const missing = page.getByRole('option', { name: /Missing Icon/ });
  await expect(missing.locator('.health')).toHaveText('error');
  await expect(missing.getByRole('img', { name: 'Missing Icon: no preview available' })).toBeVisible();

  await page.screenshot({ path: testInfo.outputPath('glyphs-list-desktop.png'), animations: 'disabled' });
  await page.getByRole('button', { name: 'Compact grid' }).click();
  await expect(thumbnail).toBeVisible();
  await page.setViewportSize({ width: 1024, height: 768 });
  await page.screenshot({ path: testInfo.outputPath('glyphs-grid-narrow.png'), animations: 'disabled' });

  const calls = await page.evaluate(() => window.__materioneTestControls?.calls() ?? []);
  expect(calls.filter((call) => call.op === 'browse.get')).toEqual([]);
  expect(calls.filter((call) => call.op === 'assets.get' &&
    call.payload.path === 'assets/textures/gui/heart.png')).toHaveLength(1);
  expect(await page.evaluate(() => window.__materioneTestControls?.writes())).toEqual([]);
  expect(pageErrors).toEqual([]);
});
