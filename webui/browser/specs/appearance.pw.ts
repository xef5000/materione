import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

// The item studio's one Appearance component (issue #173): a single large render,
// texture and model chosen from an asset picker, name and lore edited in place in the
// tooltip, and obfuscated text drawn scrambled until it is edited. Runs on the
// deterministic ?modules fixture, whose item type mirrors a module-declaring server.
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

async function save(page: Page) {
  const before = (await writes(page)).length;
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await writes(page)).length).toBe(before + 1);
  return (await writes(page)).at(-1)!.payload.edits as { field: string[]; value?: unknown; remove?: true }[];
}

test('the item is drawn once, large, with no form rows for its look', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  const appearance = page.getByRole('article', { name: 'Appearance' });
  await expect(appearance).toBeVisible();
  // one render only: the duplicate 32 px inventory slot and its label are gone
  await expect(page.getByText('Inventory scale')).toHaveCount(0);
  await expect(appearance.getByRole('img')).toHaveCount(1);
  const render = await appearance.getByRole('img').boundingBox();
  const body = await page.locator('section.studio .body').boundingBox();
  expect(render!.width).toBeGreaterThanOrEqual(body!.width / 3 - 4);
  expect(render!.width).toBeLessThanOrEqual(body!.width / 2 + 4);

  // name and lore live in the tooltip; texture and model are pickers, not dropdowns
  const tooltip = appearance.getByLabel('Item tooltip');
  await expect(tooltip.getByRole('textbox', { name: 'Display name' })).toContainText('Ruby Sword');
  await expect(tooltip.getByRole('textbox', { name: 'Lore' })).toContainText('Burns with inner fire');
  await expect(page.locator('select#appearance-texture, select#appearance-model')).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: /texture|3D model/i })).toHaveCount(0);
  await expect(appearance.getByRole('button', { name: /^3D model: ruby_sword_model/ })).toBeVisible();
  // inheritance still reads on the component
  await expect(page.locator('[data-provenance="display_name"]')).toContainText('Overrides base_item (A base item)');
  expect(pageErrors).toEqual([]);
});

test('a texture is picked from the asset picker with the keyboard and saved as one edit', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  const slot = page.getByRole('button', { name: /^Texture: none/ });
  await slot.focus();
  await page.keyboard.press('Enter');
  const picker = page.getByRole('dialog', { name: 'Choose texture' });
  await expect(picker).toBeVisible();
  await expect(picker.getByRole('option', { name: /ruby_blade/ })).toBeVisible();
  await expect(picker.getByRole('option', { name: /ruby_gem/ })).toBeVisible();
  // search narrows; Enter chooses the active row
  const search = picker.getByRole('searchbox', { name: 'Search textures' });
  await search.fill('gem');
  await expect(picker.getByRole('option')).toHaveCount(1);
  await search.press('Enter');
  await expect(picker).toHaveCount(0);
  // focus returns to the control that opened the picker
  await expect(page.getByRole('button', { name: /^Texture: items\/ruby_gem/ })).toBeFocused();

  expect(await save(page)).toEqual([{ field: ['ember_charm', 'texture'], value: 'items/ruby_gem' }]);
  expect(pageErrors).toEqual([]);
});

test('the picker offers Used by, upload and drawing a new texture', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');
  await page.getByRole('button', { name: /^Texture: none/ }).click();
  const picker = page.getByRole('dialog', { name: 'Choose texture' });
  await picker.getByRole('option', { name: /ruby_blade/ }).click();
  await expect(page.getByRole('button', { name: /^Texture: items\/ruby_blade/ })).toBeVisible();

  await page.getByRole('button', { name: /^Texture: items\/ruby_blade/ }).click();
  await expect(picker.getByRole('option', { name: /ruby_blade/ })).toContainText('Current');
  await expect(picker.getByRole('button', { name: 'Used by' })).toBeVisible();
  await expect(picker.getByRole('button', { name: 'Upload' })).toBeVisible();
  await picker.getByRole('button', { name: 'New texture' }).click();
  await expect(picker).toHaveCount(0);
  // the existing pixel editor takes over the studio
  await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('a model is picked, and None removes only a model the item sets itself', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  await page.getByRole('button', { name: /^3D model: none/ }).click();
  const picker = page.getByRole('dialog', { name: 'Choose 3D model' });
  await expect(picker.getByRole('option', { name: /ruby_sword_model/ })).toBeVisible();
  await picker.getByRole('option', { name: /ruby_gem/ }).click();
  await expect(picker).toHaveCount(0);
  expect(await save(page)).toEqual([{ field: ['ember_charm', 'model'], value: 'fixture_pack:items/ruby_gem' }]);

  await page.getByRole('button', { name: /^3D model: items\/ruby_gem/ }).click();
  await expect(picker.getByRole('button', { name: 'Edit in Blockbench' })).toBeVisible();
  await picker.getByRole('option', { name: 'None' }).click();
  expect(await save(page)).toEqual([{ field: ['ember_charm', 'model'], remove: true }]);
  await expect(page.getByRole('button', { name: /^3D model: none/ })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('name and lore are edited in place in the tooltip, as one revision-checked write', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');

  const tooltip = page.getByRole('article', { name: 'Appearance' }).getByLabel('Item tooltip');
  const name = tooltip.getByRole('textbox', { name: 'Display name' });
  await name.click();
  await name.press('Control+A');
  await page.keyboard.type('Ruby Blade');
  await expect(name).toHaveText('Ruby Blade');
  // the selection toolbar of the rich-text editor appears on the tooltip
  await name.press('Control+A');
  await expect(page.getByRole('toolbar', { name: 'Display name formatting' })).toBeVisible();
  await page.getByRole('button', { name: 'Display name: Bold' }).click();

  const lore = tooltip.getByRole('textbox', { name: 'Lore' });
  await lore.click();
  await lore.press('Control+End');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Forged in embers');
  await expect(lore.locator('.mc-line')).toHaveCount(2);

  const edits = await save(page);
  expect(edits).toEqual([
    { field: ['ruby_sword', 'display_name'], value: '<b>Ruby Blade' },
    { field: ['ruby_sword', 'lore'], value: ['Burns with inner fire', 'Forged in embers'] },
  ]);
  expect(pageErrors).toEqual([]);
});

test('an untouched tooltip never becomes a draft', async ({ page }) => {
  await openModules(page);
  await openItem(page, 'Ruby Sword');
  const name = page.getByRole('textbox', { name: 'Display name' });
  await name.click();
  await name.press('End');
  await name.press('Shift+Home');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();
  expect(await writes(page)).toHaveLength(0);
});

test('obfuscated text is scrambled in the tooltip and revealed while it is edited', async ({ page }) => {
  // the suite runs with reduced motion; this one needs the live scramble
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  const pageErrors = await openModules(page);
  await openItem(page, 'Ember Charm');

  const name = page.getByRole('textbox', { name: 'Display name' });
  await name.click();
  await name.press('Control+A');
  await name.press('Control+Shift+K');
  const run = name.locator('.mc-obf');
  // selected: the real characters can be edited
  await expect(run).toHaveText('Ember Charm');
  await expect(run).toHaveAttribute('data-revealed', 'true');

  // leaving re-scrambles, with the same number of characters
  await page.getByRole('article', { name: 'Appearance' }).getByLabel('Item tooltip').getByRole('textbox', { name: 'Lore' }).click();
  await expect(run).not.toHaveAttribute('data-revealed', 'true');
  await expect.poll(async () => (await run.textContent())).not.toBe('Ember Charm');
  expect([...(await run.textContent())!]).toHaveLength('Ember Charm'.length);
  // the real text is still there for hover and assistive tech
  await expect(run).toHaveAttribute('title', 'Ember Charm');
  // it keeps scrambling, like the game
  const first = await run.textContent();
  await expect.poll(async () => (await run.textContent())).not.toBe(first);

  // placing the caret inside reveals it again, and typing edits the real text
  await run.click();
  await expect(run).toHaveText('Ember Charm');
  await name.press('End');
  await page.keyboard.type('s');
  await expect(run).toHaveText('Ember Charms');
  await page.getByRole('textbox', { name: 'Lore' }).click();
  await expect(run).not.toHaveText('Ember Charms');

  const edits = await save(page);
  expect(edits).toHaveLength(1);
  expect(edits[0]!.field).toEqual(['ember_charm', 'display_name']);
  expect(String(edits[0]!.value)).toMatch(/obf/i);
  expect(String(edits[0]!.value)).toContain('Ember Charms');
  expect(pageErrors).toEqual([]);
});

test('reduced motion gets a static scramble', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openModules(page);
  await openItem(page, 'Ember Charm');
  const name = page.getByRole('textbox', { name: 'Display name' });
  await name.click();
  await name.press('Control+A');
  await name.press('Control+Shift+K');
  await page.getByRole('textbox', { name: 'Lore' }).click();
  const run = name.locator('.mc-obf');
  await expect.poll(async () => (await run.textContent())).not.toBe('Ember Charm');
  const scrambled = await run.textContent();
  await page.waitForTimeout(500);
  expect(await run.textContent()).toBe(scrambled);
  await run.click();
  await expect(run).toHaveText('Ember Charm');
});

test('the model style sits behind the component menu and the keyboard reaches it', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openItem(page, 'Ruby Sword');
  const menu = page.getByRole('button', { name: 'More appearance options' });
  await menu.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('textbox', { name: 'Model style' })).toBeVisible();
  // inherited from the template, shown compactly
  await expect(page.locator('[data-provenance="model_parent"]')).toContainText('Inherited from base_item');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('textbox', { name: 'Model style' })).toHaveCount(0);
  await expect(menu).toBeFocused();
  expect(pageErrors).toEqual([]);
});
