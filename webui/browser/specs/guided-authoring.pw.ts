import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

// Vanilla item icons load from jsDelivr at runtime; keep these specs offline.
test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function openGuided(page: Page): Promise<string[]> {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.goto('/browser/fixture.html?guided');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  return pageErrors;
}

async function openItem(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('option', { name: new RegExp(name, 'i') }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

async function writes(page: Page) {
  return page.evaluate(() => window.__materioneTestControls?.writes() ?? []);
}

test('Start lists registry-driven starters, targeted fixes and the server behaviour vocabulary', async ({ page }) => {
  const pageErrors = await openGuided(page);
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  const start = page.getByRole('region', { name: 'Start', exact: true });

  await expect(start.getByRole('button', { name: /^New item/i })).toBeVisible();
  await expect(start.getByRole('button', { name: /^New block/i })).toBeVisible();
  await expect(start.getByText(/quarantined: unresolved texture/)).toBeVisible();
  await expect(start.getByRole('button', { name: 'Fix texture' })).toBeVisible();
  await expect(start.getByText(/1 built-in ability, 1 from add-ons/)).toBeVisible();
  await expect(start.getByText('ExampleAddon', { exact: true })).toBeVisible();

  await start.getByRole('button', { name: /^New item/i }).click();
  await expect(page.getByRole('dialog')).toContainText('New item');
  expect(await writes(page)).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test('inherited values show their template and Reset stages a single revision-checked removal', async ({ page }) => {
  const pageErrors = await openGuided(page);
  await openItem(page, 'Templated Blade');

  await expect(page.getByText('Inherits from')).toBeVisible();
  await expect(page.locator('[data-provenance="lore"]')).toHaveText(/Inherited from blade_base: \["Forged from a template"\]/);
  const material = page.locator('[data-provenance="material"]');
  await expect(material).toContainText('Overrides blade_base (DIAMOND_SWORD)');

  await material.getByRole('button', { name: 'Reset to inherited' }).click();
  await expect(material).toContainText('Resets to the value from blade_base on Save & apply');
  expect(await writes(page)).toEqual([]);

  await material.getByRole('button', { name: 'Undo' }).click();
  await expect(material).toContainText('Overrides blade_base');
  await material.getByRole('button', { name: 'Reset to inherited' }).click();

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  const [write] = await writes(page);
  expect(write?.op).toBe('authoring.setFields');
  expect(write?.payload).toMatchObject({
    path: 'items/guided.yml',
    rev: 1,
    edits: [{ field: ['templated_blade', 'material'], remove: true }],
  });
  await expect(page.locator('[data-provenance="material"]')).toContainText('Inherited from blade_base: DIAMOND_SWORD');
  expect(pageErrors).toEqual([]);
});

test('abilities and rules are added from the server vocabulary and saved in the existing formats', async ({ page }) => {
  const pageErrors = await openGuided(page);
  await openItem(page, 'Storm Wand');

  const abilities = page.getByRole('region', { name: 'Abilities' });
  await abilities.getByLabel('Add an ability').selectOption({ label: 'Soulbound' });
  await expect(abilities.getByText(/Activates: When the owner dies/)).toBeVisible();
  await abilities.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(abilities.locator('[data-mechanic="soulbound"]')).toContainText('Built in');
  await expect(abilities.getByLabel('Add an ability').locator('option', { hasText: 'Soulbound' }))
    .toHaveJSProperty('disabled', true);

  await abilities.getByLabel('Add an ability').selectOption({ label: 'Charge (ExampleAddon)' });
  await expect(abilities.getByText(/Activates: not described by its plugin/)).toBeVisible();
  await abilities.getByRole('button', { name: 'Add', exact: true }).click();
  const charge = abilities.locator('[data-mechanic="example:charge"]');
  await expect(charge).toContainText('Added by ExampleAddon');
  await charge.getByLabel(/capacity/i).fill('5');
  await charge.getByLabel(/capacity/i).press('Tab');

  const rules = page.getByRole('region', { name: 'Behaviour rules' });
  await expect(rules.getByLabel('Rule starting point').locator('option')).toHaveText([
    'Blank rule', 'Right-click sends a message', 'Right-click runs a command',
  ]);
  await rules.getByLabel('Rule starting point').selectOption({ label: 'Right-click sends a message' });
  await rules.getByRole('button', { name: 'Add rule' }).click();
  await expect(rules.getByRole('button', { name: 'On right click: send message “&aHello!”' })).toBeVisible();
  await expect(rules.getByText('The player left- or right-clicks with the item, or right-clicks the furniture (right only).'))
    .toBeVisible();
  await expect(rules.getByLabel('When').locator('option')).toHaveText(['Click', 'Break', 'Consume']);
  expect(await writes(page)).toEqual([]);

  await page.getByRole('button', { name: 'Save & apply' }).first().click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  const [write] = await writes(page);
  expect(write?.payload.edits).toEqual([
    { field: ['storm_wand', 'mechanics'], value: [{ soulbound: {} }, { 'example:charge': { capacity: 5 } }] },
    { field: ['storm_wand', 'behavior'], value: [{ on: 'click:right', do: [{ message: '&aHello!' }] }] },
  ]);
  expect(pageErrors).toEqual([]);
});

test('changing the event of a rule re-explains when it runs, from the server event table', async ({ page }) => {
  const pageErrors = await openGuided(page);
  await openItem(page, 'Storm Wand');
  const rules = page.getByRole('region', { name: 'Behaviour rules' });
  await rules.getByRole('button', { name: 'Add rule' }).click();
  await rules.getByLabel('When').selectOption({ label: 'Consume' });
  await expect(rules.getByText('The player eats or drinks the item.')).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('a missing texture is repaired from Problems without opening Files or YAML', async ({ page }) => {
  const pageErrors = await openGuided(page);
  await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).click();
  const problems = page.getByRole('region', { name: 'Problems', exact: true });
  await problems.getByRole('button', { name: 'Fix texture' }).click();

  const repair = page.getByRole('group', { name: 'Repair Texture' });
  await expect(repair).toContainText('assets/textures/item/cracked_gem.png does not exist in this pack (MTR-0010)');
  await repair.getByRole('button', { name: 'Draw it now' }).click();
  const canvas = page.locator('canvas');
  await expect(canvas).toBeVisible();
  await canvas.click();
  const textureSave = page.getByRole('button', { name: 'Save & apply' }).last();
  await expect(textureSave).toBeEnabled();
  await textureSave.click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  const [write] = await writes(page);
  expect(write?.op).toBe('assets.put');
  expect(write?.payload.path).toBe('assets/textures/item/cracked_gem.png');
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page.getByRole('group', { name: 'Repair Texture' })).toHaveCount(0);
  expect(await page.evaluate(() => (window.__materioneTestControls?.calls() ?? [])
    .filter((call) => call.op === 'edit.put' || call.op === 'authoring.commit').length)).toBe(0);
  expect(pageErrors).toEqual([]);
});
