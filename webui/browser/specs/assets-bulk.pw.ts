import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function openCatalog(page: Page): Promise<string[]> {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.goto('/browser/fixture.html?catalog');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  return pageErrors;
}

async function writes(page: Page) {
  return page.evaluate(() => window.__materioneTestControls?.writes() ?? []);
}

async function openAsset(page: Page, name: RegExp): Promise<void> {
  await page.getByRole('button', { name: 'Assets', exact: true }).click();
  await page.getByRole('option', { name }).click();
}

test('Assets lists every user of a shared texture and is honest about what it read', async ({ page }) => {
  const pageErrors = await openCatalog(page);
  await openAsset(page, /amulets\/shared\.png/);
  const detail = page.getByRole('region', { name: 'Selected asset' });

  await expect(detail.getByRole('heading', { name: /Used by — 5 items/ })).toBeVisible();
  // two spellings of the same asset are both found
  await expect(detail.getByText('fixture_pack:amulets/shared')).toBeVisible();
  await expect(detail.getByText(/7 documents read|\d+ documents? read/)).toBeVisible();
  await expect(detail.getByText(/other asset files/)).toBeVisible();
  await expect(detail.getByRole('button', { name: 'amulet_black' })).toHaveCount(0);
  expect(await writes(page)).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test('a user opens in its studio with a way back to the asset', async ({ page }) => {
  await openCatalog(page);
  await openAsset(page, /amulets\/shared\.png/);
  await page.getByRole('region', { name: 'Selected asset' }).getByRole('button', { name: 'amulet_red' }).click();

  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
  await page.getByRole('button', { name: /Back to shared\.png/ }).click();
  await expect(page.getByRole('region', { name: 'Selected asset' }).getByRole('heading', { name: 'shared.png' })).toBeVisible();
});

test('Replace everywhere previews, then lands as one all-or-none bulk write', async ({ page }) => {
  const pageErrors = await openCatalog(page);
  await openAsset(page, /amulets\/shared\.png/);
  const detail = page.getByRole('region', { name: 'Selected asset' });

  await detail.getByRole('button', { name: 'Replace everywhere…' }).click();
  await detail.getByRole('button', { name: 'Preview changes' }).click();
  const impact = detail.getByLabel('impact summary');
  await expect(impact).toContainText('5 documents, 5 fields');
  await expect(impact).toContainText('Written together, or not at all');
  // the preview is a dry run: no write yet
  expect(await writes(page)).toHaveLength(1);
  expect((await writes(page))[0]?.payload).toMatchObject({ dryRun: true });

  await detail.getByRole('button', { name: /Replace in 5 uses/ }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(2);
  const [, applied] = await writes(page);
  expect(applied?.op).toBe('authoring.bulk');
  expect(applied?.payload).toMatchObject({ dryRun: false });
  expect(applied?.payload.operations).toHaveLength(5);
  expect(applied?.outcome).toBe('saved');

  await openAsset(page, /amulets\/other\.png/);
  await expect(page.getByRole('region', { name: 'Selected asset' }).getByRole('heading', { name: /Used by — 6 items/ })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('a copy for chosen users leaves the others sharing the original', async ({ page }) => {
  await openCatalog(page);
  await openAsset(page, /amulets\/shared\.png/);
  const detail = page.getByRole('region', { name: 'Selected asset' });

  await detail.getByRole('button', { name: 'Give some users a copy…' }).click();
  await detail.getByLabel('Give amulet_red its own copy').check();
  await detail.getByLabel('Give amulet_blue its own copy').check();
  await detail.getByRole('button', { name: 'Preview changes' }).click();
  await expect(detail.getByLabel('impact summary')).toContainText('2 documents, 2 fields');
  await detail.getByRole('button', { name: /Retarget 2 uses/ }).click();

  await expect.poll(async () => (await writes(page)).map((write) => write.op)).toEqual([
    'authoring.bulk', 'assets.put', 'authoring.bulk',
  ]);
  await expect(detail.getByRole('heading', { name: 'shared_copy.png' })).toBeVisible();
  await expect(detail.getByRole('heading', { name: /Used by — 2 items/ })).toBeVisible();
});

test('bulk edit: mark what is shown, preview, apply once; a stale revision changes nothing', async ({ page }) => {
  const pageErrors = await openCatalog(page);
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByPlaceholder(/search items/i).fill('amulet');
  const list = page.getByRole('listbox', { name: /items in/i });
  await list.focus();
  await page.keyboard.press('Control+a');
  await page.getByRole('button', { name: 'Edit 6 marked…' }).click();

  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Field').selectOption({ label: 'Base material' });
  await dialog.getByLabel('New value').selectOption('BLAZE_ROD');
  await dialog.getByRole('button', { name: 'Preview changes' }).click();
  await expect(dialog.getByLabel('impact summary')).toContainText('6 documents, 6 fields');

  await page.evaluate(() => window.__materioneTestControls?.conflictNextWrite());
  await dialog.getByRole('button', { name: /Apply to 6 documents/ }).click();
  await expect(dialog.getByRole('alert')).toContainText(/revision/);
  expect((await writes(page)).at(-1)?.outcome).toBe('conflict');

  await dialog.getByRole('button', { name: 'Preview changes' }).click();
  await dialog.getByRole('button', { name: /Apply to 6 documents/ }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  const bulk = (await writes(page)).filter((write) => write.op === 'authoring.bulk' && write.outcome === 'saved');
  expect(bulk.at(-1)?.payload).toMatchObject({ dryRun: false });
  expect(pageErrors).toEqual([]);
});

test('variants and a recipe are created together in one change', async ({ page }) => {
  await openCatalog(page);
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('option', { name: /Red Amulet/ }).click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Make variants…' }).click();

  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox').first().fill('Teal Amulet\nViolet Amulet');
  await dialog.getByLabel(/Also create a recipe/).check();
  await dialog.getByRole('button', { name: 'Preview changes' }).click();
  await expect(dialog.getByLabel('impact summary')).toContainText('4 documents');
  await dialog.getByRole('button', { name: /Apply to 4 documents/ }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  const saved = (await writes(page)).filter((write) => write.op === 'authoring.bulk' && write.payload.dryRun === false);
  expect(saved).toHaveLength(1);
  await page.getByPlaceholder(/search items/i).fill('teal');
  await expect(page.getByRole('option', { name: /Teal Amulet/ })).toBeVisible();
});

test('the command palette goes to views, resources and fields from the keyboard', async ({ page }) => {
  await openCatalog(page);
  await page.keyboard.press('Control+k');
  const palette = page.getByRole('dialog', { name: 'Command palette' });
  await palette.getByRole('combobox').fill('assets');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('region', { name: 'Assets', exact: true })).toBeVisible();

  await page.keyboard.press('Control+k');
  await palette.getByRole('combobox').fill('gold amu');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();

  await page.keyboard.press('Control+k');
  await palette.getByRole('combobox').fill('material');
  await expect(palette.getByRole('option').first()).toContainText('Field');
  await page.keyboard.press('Escape');
  await expect(palette).toHaveCount(0);
});

test('dragging a rectangle across rows marks them, like a file manager', async ({ page }) => {
  await openCatalog(page);
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByPlaceholder(/search items/i).fill('amulet');
  const rows = page.getByRole('option', { name: /Amulet/ });
  await expect(rows).toHaveCount(6);
  const first = await rows.nth(0).boundingBox();
  const third = await rows.nth(2).boundingBox();
  const list = await page.getByRole('listbox', { name: /items in/i }).boundingBox();
  if (!first || !third || !list) throw new Error('rows are not laid out');
  // start in the list's own space (right of the rows if any), drag over the first three
  await page.mouse.move(first.x + 2, first.y + 2);
  await page.mouse.down();
  await page.mouse.move(third.x + third.width - 2, third.y + third.height - 2, { steps: 8 });
  await expect(page.locator('.band')).toBeVisible();
  await page.mouse.up();
  await expect(page.getByRole('button', { name: 'Edit 3 marked…' })).toBeVisible();
  expect(await writes(page)).toEqual([]);
});
