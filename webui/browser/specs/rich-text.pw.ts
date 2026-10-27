/**
 * The rich text editor (issue 151, web.md "Rich text"): Minecraft text edited in
 * place, stored as MiniMessage, with untouched values never rewritten.
 */
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

async function openFixture(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
}

async function openItem(page: Page, name = 'Ruby Blade'): Promise<void> {
  await page.getByRole('option', { name: new RegExp(name, 'i') }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

async function openCrate(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Blocks', exact: true }).click();
  await page.getByRole('option', { name: /Ruby Crate/i }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

async function readWrites(page: Page) {
  return page.evaluate(() => window.__materioneTestControls!.writes());
}

/** Selects the editor's whole text with the keyboard, like an author would. */
async function selectAll(editor: Locator): Promise<void> {
  await editor.click();
  await editor.press('Control+A');
}

async function saveEdits(page: Page) {
  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(1);
  return (await readWrites(page))[0]!.payload.edits as { field: string[]; value: unknown }[];
}

test('shows legacy codes as formatting and leaves an untouched value alone', async ({ page }) => {
  await openFixture(page);
  await openCrate(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  // no codes on screen: the text reads as the player sees it, in the game face
  await expect(name).toHaveText('Ruby Crate');
  await expect(name).toHaveCSS('font-family', /Minecraftia/);
  await expect(name.locator('.mc-b')).toHaveText('Crate');
  await expect(name.locator('.mc-run').first()).toHaveCSS('color', 'rgb(255, 170, 0)');
  // focus, move around, select and leave: nothing becomes a draft
  await name.click();
  await name.press('End');
  await name.press('Shift+Home');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();
  expect(await readWrites(page)).toHaveLength(0);
});

test('formats a selection from the floating toolbar and stores MiniMessage', async ({ page }) => {
  await openFixture(page);
  await openItem(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await selectAll(name);
  const toolbar = page.getByRole('toolbar', { name: 'Display name formatting' });
  await expect(toolbar).toBeVisible();
  await toolbar.getByRole('button', { name: 'Display name: Bold' }).click();
  await expect(toolbar.getByRole('button', { name: 'Display name: Bold' })).toHaveAttribute('aria-pressed', 'true');
  await toolbar.getByRole('button', { name: 'Display name: Color' }).click();
  await page.getByRole('button', { name: 'Display name: Gold' }).click();
  await expect(name.locator('.mc-b').first()).toHaveCSS('color', 'rgb(255, 170, 0)');
  // the preview tooltip draws the same text
  await expect(page.getByRole('button', { name: 'Edit item name' }).locator('.mc-b')).toHaveText('Ruby Blade');

  expect(await saveEdits(page)).toEqual([{ field: ['ruby_blade', 'display_name'], value: '<gold><b>Ruby Blade' }]);
});

test('authors a gradient name the game will draw', async ({ page }) => {
  await openFixture(page);
  await openItem(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await selectAll(name);
  await page.getByRole('button', { name: 'Display name: Gradient' }).click();
  await page.getByRole('button', { name: 'Add gradient stop' }).click();
  await page.getByLabel('Gradient stop 3').fill('#ffffff');
  await page.getByRole('button', { name: 'Apply gradient' }).click();

  const runs = name.locator('.mc-run');
  await expect(runs).toHaveCount('Ruby Blade'.length);
  await expect(runs.first()).toHaveCSS('color', 'rgb(249, 115, 30)');
  await expect(runs.last()).toHaveCSS('color', 'rgb(255, 255, 255)');
  const colors = await runs.evaluateAll((elements) => elements.map((element) => getComputedStyle(element).color));
  expect(new Set(colors).size).toBeGreaterThan(5);
  await expect(name).toHaveText('Ruby Blade');

  expect(await saveEdits(page)).toEqual([
    { field: ['ruby_blade', 'display_name'], value: '<gradient:#f9731e:#ffd27a:#ffffff>Ruby Blade' },
  ]);
});

test('turns typed codes and shortcuts into formatting, with undo and redo', async ({ page }) => {
  await openFixture(page);
  await openItem(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await selectAll(name);
  await page.keyboard.type('&6Gold ');
  await page.keyboard.press('Control+B');
  await page.keyboard.type('Bold');
  await expect(name).toHaveText('Gold Bold');
  await expect(name.locator('.mc-b')).toHaveText('Bold');

  await page.keyboard.press('Control+Z');
  await expect(name).toHaveText('Gold ');
  await page.keyboard.press('Control+Y');
  await expect(name).toHaveText('Gold Bold');
  // Enter does not add a line to a name
  await page.keyboard.press('Enter');
  await expect(name.locator('.mc-line')).toHaveCount(1);

  expect(await saveEdits(page)).toEqual([{ field: ['ruby_blade', 'display_name'], value: '<gold>Gold <b>Bold' }]);
});

test('edits lore line by line and keeps untouched lines byte for byte', async ({ page }) => {
  await openFixture(page);
  await openItem(page);
  const lore = page.getByRole('textbox', { name: 'Lore', exact: true });
  await expect(lore).toHaveText('A deterministic browser fixture');
  await expect(lore).toHaveCSS('color', 'rgb(170, 0, 170)');
  await lore.click();
  await lore.press('Control+End');
  await page.keyboard.press('Enter');
  await page.keyboard.type('&cBurns ');
  await page.keyboard.press('Control+I');
  await page.keyboard.type('hot');
  await expect(lore.locator('.mc-line')).toHaveCount(2);
  await expect(page.getByRole('button', { name: 'Edit item lore' }).locator('.lore-line')).toHaveCount(2);

  expect(await saveEdits(page)).toEqual([
    { field: ['ruby_blade', 'lore'], value: ['A deterministic browser fixture', '<red>Burns <i>hot'] },
  ]);
});

test('pastes plain text only and keeps markup-looking text literal', async ({ page }) => {
  await openFixture(page);
  await openItem(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await selectAll(name);
  await name.evaluate((element) => {
    const data = new DataTransfer();
    data.setData('text/html', '<img src=x onerror="window.__pwned = true"><b>Bold</b>');
    data.setData('text/plain', '&aRuby <img src=x>\nBlade');
    element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await expect(name).toHaveText('Ruby <img src=x> Blade');
  await expect(name.locator('img')).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { __pwned?: boolean }).__pwned)).toBeUndefined();

  expect(await saveEdits(page)).toEqual([
    { field: ['ruby_blade', 'display_name'], value: '<green>Ruby \\<img src=x> Blade' },
  ]);
});

test('inserts a glyph as a chip and stores it as a glyph tag', async ({ page }) => {
  await openFixture(page);
  await openItem(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await name.click();
  await name.press('End');
  await page.getByRole('button', { name: 'Display name: Insert glyph' }).click();
  await page.getByRole('group', { name: 'Display name glyphs' }).getByRole('button', { name: /heart_icon/ }).click();
  await expect(name.locator('.mc-glyph')).toHaveAttribute('data-glyph', 'heart_icon');

  expect(await saveEdits(page)).toEqual([
    { field: ['ruby_blade', 'display_name'], value: 'Ruby Blade<glyph:heart_icon>' },
  ]);
});

test('block names use the same editor as item names', async ({ page }) => {
  await openFixture(page);
  await openCrate(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await name.click();
  await name.press('End');
  // the typed space continues the bold before it, as in a word processor
  await page.keyboard.type(' &bBox');
  await expect(name).toHaveText('Ruby Crate Box');

  expect(await saveEdits(page)).toEqual([
    { field: ['ruby_crate', 'display_name'], value: '<gold>Ruby <b>Crate </b></gold><aqua>Box' },
  ]);
});

test('visual: the editor and toolbar in the game look', async ({ page }, testInfo) => {
  await openFixture(page);
  await openItem(page);
  const name = page.getByRole('textbox', { name: 'Display name', exact: true });
  await selectAll(name);
  await page.getByRole('button', { name: 'Display name: Gradient' }).click();
  await page.getByRole('button', { name: 'Apply gradient' }).click();
  await name.press('End');
  await name.press('Shift+ArrowLeft');
  await page.getByRole('button', { name: 'Display name: Bold' }).click();
  const field = page.locator('[data-field="display_name"]');
  await expect(field.getByRole('toolbar')).toBeVisible();
  const box = (await field.boundingBox())!;
  await page.screenshot({
    path: testInfo.outputPath('rich-text-editor.png'),
    clip: { x: Math.max(0, box.x - 8), y: Math.max(0, box.y - 56), width: Math.min(640, box.width + 16), height: box.height + 64 },
  });
  await page.keyboard.press('Tab');
  await page.getByRole('button', { name: 'Edit item name' }).scrollIntoViewIfNeeded();
  await page.locator('.item-preview .tooltip').screenshot({ path: testInfo.outputPath('rich-text-tooltip.png') });
});
