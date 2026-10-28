import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

// The item studio as display + modules (issue #152). Everything here runs on
// the deterministic ?modules fixture, whose item type mirrors what a
// module-declaring server self-describes.
test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function openModules(page: Page): Promise<string[]> {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.goto('/browser/fixture.html?modules');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  return pageErrors;
}

async function openItem(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('option', { name: new RegExp(name, 'i') }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

function writes(page: Page) {
  return page.evaluate(() => window.__materioneTestControls?.writes() ?? []);
}

function documentOf(page: Page, path: string) {
  return page.evaluate(([target]) => {
    const found = window.__materioneTestControls?.documents().find((entry) => entry.path === target);
    return found ? { raw: found.raw, parsed: found.parsed } : null;
  }, [path]);
}

test('the item studio reads as a display card plus module cards derived from the schema and the registries', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  const display = page.getByRole('article', { name: 'Display' });
  // The display name is the WYSIWYG rich-text editor (contenteditable textbox).
  await expect(display.getByRole('textbox', { name: 'Display name' })).toContainText('Ruby Sword');
  await expect(display.getByRole('textbox', { name: 'Lore' })).toBeVisible();
  await expect(display.getByLabel('Texture')).toBeVisible();
  await expect(display.getByLabel('3D model')).toHaveValue('fixture_pack:ruby_sword_model');

  const material = page.locator('[data-module="material"]');
  await expect(material).toContainText('Base material');
  // A required module is the floor of the item, not an add/remove card.
  await expect(material.getByRole('button', { name: 'Remove' })).toHaveCount(0);
  await expect(material.locator('select, input').first()).toBeVisible();

  const durability = page.locator('[data-module="mechanic:materione:durability"]');
  await expect(durability).toContainText('Durability');
  // Origin and activation are hover hints on the ability's name, not copy on the card.
  await expect(durability.locator('strong')).toHaveAttribute('title', /Built in/);
  await expect(durability.getByLabel(/max durability/i)).toHaveValue('500');

  await expect(page.locator('[data-module="mechanic:materione:soulbound"] strong'))
    .toHaveAttribute('title', /Activates: When the owner dies/);
  await expect(page.locator('[data-module="mechanic:materione:commands"]'))
    .toContainText('Console Commands');

  // Inheritance stays explained: ruby_sword overrides its template's name.
  await expect(page.getByRole('button', { name: 'Template: A base item' })).toBeVisible();
  await expect(page.locator('[data-provenance="display_name"]'))
    .toContainText('Overrides base_item (A base item)');

  // Everything on this item is display or module; the unknown key keeps its
  // honest Advanced-only report.
  await expect(page.getByText('1 field is only editable in Advanced Files (future_component)')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add module' })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('modules are found through the searchable picker with categories, keyboard navigation and added marks', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  await page.getByRole('button', { name: 'Add module' }).click();
  const picker = page.getByRole('dialog', { name: 'Add a module' });
  await expect(picker).toBeVisible();

  // Search narrows to one module; Enter adds the active row.
  await picker.getByLabel('Search modules').fill('back');
  await expect(picker.getByRole('option')).toHaveCount(1);
  await picker.getByLabel('Search modules').press('Enter');

  const backpack = page.locator('[data-module="mechanic:materione:backpack"]');
  await expect(backpack).toContainText('Backpack');
  await expect(backpack.getByLabel('Inventory rows')).toBeVisible();
  await expect(backpack.getByLabel('Backpack title')).toBeVisible();

  // Already-added non-repeatable modules are marked and unselectable.
  await page.getByRole('button', { name: 'Add module' }).click();
  await picker.getByLabel('Search modules').fill('backpack');
  await expect(picker.getByRole('option', { name: /Backpack/ })).toContainText('Added');
  await expect(picker.getByRole('option', { name: /Backpack/ })).toBeDisabled();

  // Category chips filter; the repeatable add-on ability stays addable.
  await picker.getByLabel('Search modules').fill('');
  await picker.getByRole('button', { name: 'Behaviour', exact: true }).click();
  await expect(picker.getByRole('option', { name: /Charge/ })).toContainText('ExampleAddon');
  await picker.getByLabel('Search modules').fill('nothing here');
  await expect(picker.getByText(/No module matches/)).toBeVisible();
  await picker.getByLabel('Search modules').press('Escape');
  await expect(picker).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('adding, configuring and removing modules writes through the lossless layer into the saved YAML', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  // Add a mechanic module through the picker, then configure its parameters.
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('food');
  await page.getByLabel('Search modules').press('Enter');
  const food = page.locator('[data-module="mechanic:materione:food"]');
  await food.getByLabel('Nutrition').fill('6');
  await food.getByLabel('Nutrition').press('Tab');
  await food.getByLabel('Saturation').fill('0.8');
  await food.getByLabel('Saturation').press('Tab');
  await food.getByLabel('Can always eat').check();

  // A module with a declared default writes it the moment it is added.
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('model style');
  await page.getByLabel('Search modules').press('Enter');
  await expect(page.locator('[data-module="model_parent"]')
    .getByRole('textbox', { name: 'Model style' })).toHaveValue('item/generated');

  // A reference module with no default opens an empty card; the first value
  // writes the key.
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('jukebox');
  await page.getByLabel('Search modules').press('Enter');
  const song = page.locator('[data-module="jukebox_song"]');
  await song.getByRole('textbox', { name: 'Jukebox song' }).fill('fixture_pack:echoes');
  await song.getByRole('textbox', { name: 'Jukebox song' }).press('Tab');

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  const [save] = await writes(page);
  expect(save?.op).toBe('authoring.setFields');
  expect(save?.payload.edits).toEqual([
    { field: ['ember_charm', 'mechanics'], value: [{ food: { nutrition: 6, saturation: 0.8, can_always_eat: true } }] },
    { field: ['ember_charm', 'model_parent'], value: 'item/generated' },
    { field: ['ember_charm', 'jukebox_song'], value: 'fixture_pack:echoes' },
  ]);

  const saved = await documentOf(page, 'items/ember_charm.yml');
  expect(saved?.parsed.ember_charm).toMatchObject({
    mechanics: [{ food: { nutrition: 6, saturation: 0.8, can_always_eat: true } }],
    model_parent: 'item/generated',
    jukebox_song: 'fixture_pack:echoes',
  });
  expect(saved?.raw).toContain('jukebox_song:');

  // Removing modules stages lossless removals: the only ability takes the
  // whole mechanics key with it, a field module loses exactly its key.
  await food.getByRole('button', { name: 'Remove Food' }).click();
  await song.getByRole('button', { name: 'Remove Jukebox song' }).click();
  await expect(food).toHaveCount(0);
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(2);
  const [removals] = await writes(page).then((all) => all.slice(-1));
  expect(removals?.payload.edits).toEqual([
    { field: ['ember_charm', 'mechanics'], remove: true },
    { field: ['ember_charm', 'jukebox_song'], remove: true },
  ]);
  const after = await documentOf(page, 'items/ember_charm.yml');
  expect(after?.parsed.ember_charm).not.toHaveProperty('mechanics');
  expect(after?.parsed.ember_charm).not.toHaveProperty('jukebox_song');
  expect(after?.raw).not.toContain('jukebox_song:');
  expect(pageErrors).toEqual([]);
});

test('removing one authored ability keeps the others exactly as written', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  await page.locator('[data-module="mechanic:materione:durability"]')
    .getByRole('button', { name: 'Remove Durability' }).click();
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  const [save] = await writes(page);
  expect(save?.payload.edits).toEqual([
    {
      field: ['ruby_sword', 'mechanics'],
      value: [
        { soulbound: {} },
        { commands: { on_left_click: ['tellraw %player% {"text":"The ruby hums."}'] } },
      ],
    },
  ]);
  const saved = await documentOf(page, 'items/ruby_sword.yml');
  expect(saved?.parsed.ruby_sword?.mechanics).toEqual([
    { soulbound: {} },
    { commands: { on_left_click: ['tellraw %player% {"text":"The ruby hums."}'] } },
  ]);
  // The unknown key and template stay untouched by the studio save.
  expect(saved?.parsed.ruby_sword?.future_component).toEqual({ keep: 'me' });
  expect(saved?.parsed.ruby_sword?.template).toBe('base_item');
  expect(pageErrors).toEqual([]);
});

test('the When… do… module authors rules in the existing format, with segmented enums for small choices', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('when');
  await page.getByLabel('Search modules').press('Enter');
  const rules = page.locator('[data-module="behavior"]');
  await expect(rules).toContainText('No rules yet.');

  await rules.getByLabel('Rule starting point').selectOption({ label: 'Right-click sends a message' });
  await rules.getByRole('button', { name: 'Add rule' }).click();
  await expect(rules.getByRole('button', { name: /On right click: send message/ })).toBeVisible();

  // The rule opens expanded; a small enum reads as one segmented choice.
  const target = rules.getByRole('radiogroup', { name: 'Where the text appears' });
  await expect(target.getByRole('radio', { name: 'actionbar' })).toBeVisible();
  await target.getByRole('radio', { name: 'actionbar' }).click();

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  const [save] = await writes(page);
  expect(save?.payload.edits).toEqual([
    {
      field: ['ember_charm', 'behavior'],
      value: [{ on: 'click:right', do: [{ message: { text: '&aHello!', target: 'actionbar' } }] }],
    },
  ]);

  // Removing the rules module removes the whole behavior key.
  await rules.getByRole('button', { name: 'Remove When… do…' }).click();
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(2);
  const [removal] = await writes(page).then((all) => all.slice(-1));
  expect(removal?.payload.edits).toEqual([{ field: ['ember_charm', 'behavior'], remove: true }]);
  const saved = await documentOf(page, 'items/ember_charm.yml');
  expect(saved?.parsed.ember_charm).not.toHaveProperty('behavior');
  expect(pageErrors).toEqual([]);
});
