import { expect, test } from '@playwright/test';
import '../contract';

const CDN = 'https://cdn.jsdelivr.net/npm/minecraft-textures@26.3.0/dist/textures';
// A 32x32 opaque PNG standing in for a vanilla icon; the real CDN is never contacted.
const ICON = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAL0lEQVR4nO3OIQEAAAgDMOIQkYi0ghg3E/Ornb6kEhAQEBAQEBAQEBAQEBAQSAcesHOciKoaL68AAAAASUVORK5CYII=',
  'base64',
);

test('items without a custom visual show the vanilla icon for their material', async ({ page }) => {
  const manifests: string[] = [];
  await page.route('https://cdn.jsdelivr.net/**', async (route) => {
    const url = route.request().url();
    if (url.startsWith(`${CDN}/manifest/`)) {
      manifests.push(url.slice(CDN.length));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: { 'minecraft:blaze_rod': { texture: 'abc123.png' } } }),
      });
    } else if (url === `${CDN}/assets/abc123.png`) {
      await route.fulfill({ status: 200, contentType: 'image/png', body: ICON });
    } else {
      await route.abort();
    }
  });
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');

  // BLAZE_ROD is in the manifest; STICK is not, so it keeps the placeholder.
  const jewel = page.getByRole('img', { name: 'Crimson Jewel' });
  await expect(jewel).toHaveAttribute('title', 'Crimson Jewel: Vanilla item icon (no custom visual)');
  await expect(jewel.locator('img')).toHaveAttribute('src', `${CDN}/assets/abc123.png`);
  expect(await jewel.locator('img').evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(32);
  await expect(page.getByRole('img', { name: 'Malformed Lore: no preview available' })).toBeVisible();
  // The fixture server reports 1.21.4, so the matching manifest is chosen, once.
  expect(manifests).toEqual(['/manifest/1.21.4.id.json']);
});
