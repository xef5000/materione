import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';
import { moduleRow, selectModule, settingsPanel } from './helpers/modules';

// The item studio as display + modules (issue #152), with the settings panel
// beside the module list (issue #174). Everything here runs on the deterministic
// ?modules fixture, whose item type mirrors what a module-declaring server
// self-describes.
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

test('the item studio reads as a display card plus module rows and a settings panel derived from the schema and the registries', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  // Items draw their look with the one Appearance component (issue 173): name and lore are the
  // rich-text editors inside its tooltip; texture and model are pickers, not form rows.
  const display = page.getByRole('article', { name: 'Appearance' });
  await expect(display.getByRole('textbox', { name: 'Display name' })).toContainText('Ruby Sword');
  await expect(display.getByRole('textbox', { name: 'Lore' })).toBeVisible();
  await expect(display.getByRole('button', { name: /^Texture: none/ })).toBeVisible();
  await expect(display.getByRole('button', { name: /^3D model: ruby_sword_model/ })).toBeVisible();

  // A row is a title and markers: no settings are drawn inside the list.
  const list = page.getByRole('list', { name: 'Modules' });
  await expect(list.locator('input, select, textarea')).toHaveCount(0);
  const material = page.locator('[data-module="material"]');
  await expect(material).toContainText('Base material');
  // A required module is the floor of the item, not a removable row.
  await expect(material.getByRole('button', { name: 'Remove' })).toHaveCount(0);

  await selectModule(page, 'Base material');
  await expect(settingsPanel(page).locator('select, input').first()).toBeVisible();

  await selectModule(page, 'Durability');
  const panel = settingsPanel(page);
  await expect(panel.locator('header strong')).toHaveText('Durability');
  // Origin and activation are hover hints on the ability's name, not copy on the card.
  await expect(panel.locator('header strong')).toHaveAttribute('title', /Built in/);
  await expect(panel.getByLabel(/max durability/i)).toHaveValue('500');

  await expect(page.locator('[data-module="mechanic:materione:commands"]')).toContainText('Console Commands');
  await selectModule(page, 'Soulbound');
  await expect(settingsPanel(page).locator('header strong'))
    .toHaveAttribute('title', /Activates: When the owner dies/);

  // Inheritance stays explained: ruby_sword overrides its template's name.
  await expect(page.getByRole('button', { name: 'Template: A base item' })).toBeVisible();
  await expect(page.locator('[data-provenance="display_name"]'))
    .toContainText('Overrides base_item (A base item)');

  // Everything on this item is display or module; the unknown key keeps its
  // honest Advanced-only report.
  await expect(page.getByText('1 field is only editable in Advanced Files (future_component)')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add module' })).toBeVisible();
  // Show in file is gone from the rows: Open in Files in the header is the way to the file.
  await expect(page.getByRole('button', { name: 'Show in file' })).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('selecting a module shows only its settings in the panel, and an empty panel has no settings', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');
  const panel = settingsPanel(page);

  // Nothing selected: the panel holds no module's settings.
  await expect(panel.getByLabel(/max durability/i)).toHaveCount(0);
  await expect(page.locator('[aria-current="true"]')).toHaveCount(0);

  await selectModule(page, 'Durability');
  await expect(moduleRow(page, 'Durability')).toHaveAttribute('aria-current', 'true');
  await expect(panel.getByLabel(/max durability/i)).toHaveValue('500');
  await expect(panel.getByLabel(/left-click commands/i)).toHaveCount(0);

  // Selecting another row swaps the one settings block; the first one is gone.
  await selectModule(page, 'Console Commands');
  await expect(moduleRow(page, 'Console Commands')).toHaveAttribute('aria-current', 'true');
  await expect(moduleRow(page, 'Durability')).not.toHaveAttribute('aria-current', 'true');
  await expect(panel.getByLabel(/left-click commands/i)).toBeVisible();
  await expect(panel.getByLabel(/max durability/i)).toHaveCount(0);
  await expect(panel.locator('[data-field="mechanics"]')).toHaveCount(1);
  expect(pageErrors).toEqual([]);
});

test('modules are found through the searchable picker with categories, keyboard navigation and added marks', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  await page.getByRole('button', { name: 'Add module' }).click();
  const picker = page.getByRole('dialog', { name: 'Add a module' });
  await expect(picker).toBeVisible();

  // Search narrows to one module; Enter adds the active row, and its settings open in the panel.
  await picker.getByLabel('Search modules').fill('back');
  await expect(picker.getByRole('option')).toHaveCount(1);
  await picker.getByLabel('Search modules').press('Enter');
  await expect(moduleRow(page, 'Backpack')).toHaveAttribute('aria-current', 'true');
  await expect(settingsPanel(page).getByLabel('Inventory rows')).toBeVisible();
  await expect(settingsPanel(page).getByLabel('Backpack title')).toBeVisible();

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

  // Add a mechanic module through the picker; its settings open in the panel.
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('food');
  await page.getByLabel('Search modules').press('Enter');
  const panel = settingsPanel(page);
  await panel.getByLabel('Nutrition').fill('6');
  await panel.getByLabel('Nutrition').press('Tab');
  await panel.getByLabel('Saturation').fill('0.8');
  await panel.getByLabel('Saturation').press('Tab');
  await panel.getByLabel('Can always eat').check();

  // The model style is an option of the Appearance component, not a module row.
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('model style');
  await expect(page.getByText(/No module matches/)).toBeVisible();
  await page.getByLabel('Search modules').press('Escape');
  await page.getByRole('button', { name: 'More appearance options' }).click();
  await page.getByRole('textbox', { name: 'Model style' }).fill('item/generated');
  await page.getByRole('textbox', { name: 'Model style' }).press('Tab');

  // A reference module with no default opens an empty module; the first value
  // writes the key.
  await page.getByRole('button', { name: 'Add module' }).click();
  await page.getByLabel('Search modules').fill('jukebox');
  await page.getByLabel('Search modules').press('Enter');
  await panel.getByRole('textbox', { name: 'Jukebox song' }).fill('fixture_pack:echoes');
  await panel.getByRole('textbox', { name: 'Jukebox song' }).press('Tab');

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

  // Removing modules from their rows stages lossless removals: the only ability
  // takes the whole mechanics key with it, a field module loses exactly its key.
  await page.locator('[data-module="mechanic:materione:food"]').getByRole('button', { name: 'Remove Food' }).click();
  await page.locator('[data-module="jukebox_song"]').getByRole('button', { name: 'Remove Jukebox song' }).click();
  await expect(page.locator('[data-module="mechanic:materione:food"]')).toHaveCount(0);
  await expect(page.locator('[data-module="jukebox_song"]')).toHaveCount(0);
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

test('the remove icon stages the removal and Undo brings the module back before anything is written', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  const soulbound = page.locator('[data-module="mechanic:materione:soulbound"]');
  // The remove control is an icon button: its accessible name is the module it removes.
  const remove = soulbound.getByRole('button', { name: 'Remove Soulbound' });
  await expect(remove).toHaveAttribute('title', 'Remove Soulbound');
  await remove.click();
  await expect(soulbound).toHaveCount(0);
  const notice = page.getByRole('status').filter({ hasText: 'Removed Soulbound' });
  await expect(notice).toBeVisible();
  expect(await writes(page)).toEqual([]);

  await notice.getByRole('button', { name: 'Undo' }).click();
  await expect(page.locator('[data-module="mechanic:materione:soulbound"]')).toHaveCount(1);
  await expect(notice).toHaveCount(0);
  expect(await writes(page)).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test('the module list moves with the arrow keys, Enter opens the settings, and Escape in the panel returns to the list', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  // The rows are in the registry's order: Base material, then the abilities as the server lists them.
  await moduleRow(page, 'Base material').focus();
  await page.keyboard.press('ArrowDown');
  await expect(moduleRow(page, 'Soulbound')).toBeFocused();
  await expect(moduleRow(page, 'Soulbound')).toHaveAttribute('aria-current', 'true');
  await expect(settingsPanel(page).locator('header strong')).toHaveText('Soulbound');

  await page.keyboard.press('ArrowDown');
  await expect(moduleRow(page, 'Durability')).toBeFocused();
  await expect(settingsPanel(page).getByLabel(/max durability/i)).toHaveValue('500');
  await page.keyboard.press('ArrowUp');
  await expect(moduleRow(page, 'Soulbound')).toBeFocused();
  await page.keyboard.press('End');
  await expect(moduleRow(page, 'Console Commands')).toBeFocused();
  await page.keyboard.press('Home');
  await expect(moduleRow(page, 'Base material')).toBeFocused();

  // Enter opens the settings and moves focus into the panel.
  await moduleRow(page, 'Durability').focus();
  await page.keyboard.press('Enter');
  await expect(settingsPanel(page).getByLabel(/max durability/i)).toBeFocused();

  // Escape in the panel returns focus to the selected row, and the studio stays open.
  await page.keyboard.press('Escape');
  await expect(moduleRow(page, 'Durability')).toBeFocused();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('a Problems Fix on a module field opens the item with that module selected and its field focused', async ({ page }) => {
  const pageErrors = await openModules(page);
  await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).click();
  const problems = page.getByRole('region', { name: 'Problems', exact: true });
  await problems.getByRole('button', { name: 'Fix material' }).click();

  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
  // The module that owns the field is the one in the panel, and the field itself has focus.
  await expect(moduleRow(page, 'Base material')).toHaveAttribute('aria-current', 'true');
  await expect(settingsPanel(page).locator('[data-field="material"]')).toHaveCount(1);
  await expect(page.locator('[data-field="material"]').locator(':focus')).toHaveCount(1);
  // The row carries the problem marker the diagnostic put on its field.
  await expect(moduleRow(page, 'Base material')).toHaveAccessibleName(/Has a problem/);
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
  // The rules editor is the selected module's settings: it renders in the panel.
  const rules = settingsPanel(page);
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

  // Removing the rules module (from its row) removes the whole behavior key.
  await page.locator('[data-module="behavior"]').getByRole('button', { name: 'Remove When… do…' }).click();
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(2);
  const [removal] = await writes(page).then((all) => all.slice(-1));
  expect(removal?.payload.edits).toEqual([{ field: ['ember_charm', 'behavior'], remove: true }]);
  const saved = await documentOf(page, 'items/ember_charm.yml');
  expect(saved?.parsed.ember_charm).not.toHaveProperty('behavior');
  expect(pageErrors).toEqual([]);
});
