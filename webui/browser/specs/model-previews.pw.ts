import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function openGallery(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?modelGallery');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('button', { name: 'Blocks', exact: true }).click();
}

/** Mean luminance of a 6×6 patch of the rendered preview (128×128 canvas space). */
async function luminance(page: Page, label: string, x: number, y: number): Promise<number> {
  return page.getByRole('img', { name: label }).locator('img').evaluate(async (image: HTMLImageElement, at) => {
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d')!;
    context.drawImage(image, 0, 0);
    const data = context.getImageData(at.x - 3, at.y - 3, 6, 6).data;
    let sum = 0;
    for (let index = 0; index < data.length; index += 4) {
      if (data[index + 3]! < 200) return -1;
      sum += 0.2126 * data[index]! + 0.7152 * data[index + 1]! + 0.0722 * data[index + 2]!;
    }
    return sum / (data.length / 4);
  }, { x, y });
}

test('blocks render live inventory-style previews from their models', async ({ page }) => {
  await openGallery(page);

  for (const name of ['Crate Cube', 'Crate Slab', 'Crate Stairs', 'Crate Sprout', 'Stone Lamp']) {
    const thumbnail = page.getByRole('img', { name });
    await expect(thumbnail).toHaveAttribute('title', `${name}: Live model preview (browser approximation)`);
    expect(await thumbnail.locator('img').evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(128);
  }

  // Inventory lighting on a cube: top brightest, left side next, right side darkest.
  const top = await luminance(page, 'Crate Cube', 64, 34);
  const left = await luminance(page, 'Crate Cube', 36, 80);
  const right = await luminance(page, 'Crate Cube', 92, 80);
  expect(Math.min(top, left, right)).toBeGreaterThan(0);
  expect(top).toBeGreaterThan(left);
  expect(left).toBeGreaterThan(right);

  // Unknown vanilla geometry falls back; with no static visual that is the placeholder.
  await expect(page.getByRole('img', { name: 'Mystery Anvil: no preview available' })).toBeVisible();
  // Blocks without render data are unchanged.
  await expect(page.getByRole('img', { name: 'Ruby Crate: no preview available' })).toBeVisible();
});

test('the block studio shows the live render while shape fields are unedited', async ({ page }) => {
  await openGallery(page);
  await page.getByRole('option', { name: /Crate Stairs/ }).dblclick();
  const preview = page.locator('.preview .thumbnail');
  await expect(preview).toHaveClass(/live/);
  // The preview kind is a hover hint on the image, not a caption under it.
  await expect(preview).toHaveAttribute('title', /Live model preview/);
});
