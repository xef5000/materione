import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

// Icons are the one Lucide set rendered through <Icon> (issue #149). These
// specs check the rendered DOM: every place an icon belongs draws an svg, and
// every icon-only control keeps a name a screen reader can announce.
test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function openFixture(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
}

// Unicode glyphs the editor used as icons before the Lucide set.
const GLYPH_CHARACTERS = /[←→↑↓↶↷↻▤⊞✕◇▱▧▭▪■⬛⬚⊙╱⌫✏✎⚠▴▾⇔]/;

test('the rail draws every view and the labels toggle from the icon set', async ({ page }) => {
  await openFixture(page);

  const rail = page.getByRole('navigation', { name: 'Workspace navigation' });
  // Four authoring views, three support views and the labels toggle.
  await expect(rail.locator('svg.lucide-icon')).toHaveCount(8);
  await expect(rail.locator('svg.lucide-icon[aria-hidden="true"]')).toHaveCount(8);
  await expect(rail.getByRole('button', { name: 'Collapse navigation labels' }).locator('svg.lucide-icon')).toHaveCount(1);
  await expect(rail.getByRole('button', { name: 'Content', exact: true }).locator('svg.lucide-icon')).toHaveCount(1);
});

test('the Content toolbar names its icon-only controls and carries no glyph text', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Content', exact: true }).click();

  const toolbar = page.locator('.content .toolbar');
  await expect(page.getByRole('button', { name: 'Back', exact: true }).locator('svg.lucide-icon')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Up one folder', exact: true }).locator('svg.lucide-icon')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Dense visual list' }).locator('svg.lucide-icon')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Compact grid' }).locator('svg.lucide-icon')).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'New item', exact: true })).toBeVisible();
  expect(await toolbar.innerText()).not.toMatch(GLYPH_CHARACTERS);
});

test('each content type tab draws its own icon', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Content', exact: true }).click();

  const types = page.getByRole('navigation', { name: 'content types' });
  const tabs = types.locator('button');
  const count = await tabs.count();
  expect(count).toBeGreaterThan(1);
  await expect(types.locator('button svg.lucide-icon')).toHaveCount(count);
  expect(await types.innerText()).not.toMatch(GLYPH_CHARACTERS);
});

test('Start draws an icon on every create tile', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Start', exact: true }).click();

  const starters = page.locator('.starter');
  const count = await starters.count();
  expect(count).toBeGreaterThan(1);
  await expect(page.locator('.starter svg.lucide-icon')).toHaveCount(count);
});

test('the Files tree draws a file or folder icon on each row', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Files', exact: true }).click();

  // Shared-document sub-rows (one per resource id) are text-only by design.
  const rows = page.locator('.tree .row:not(.sub) > button.label');
  await expect(rows.first()).toBeVisible();
  const count = await rows.count();
  await expect(rows.locator('svg.file-icon')).toHaveCount(count);
});
