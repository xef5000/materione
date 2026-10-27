import { expect, test } from '@playwright/test';
import {
  blockCdn, documentOf, openModules, openResource, saveAndApply,
} from './helpers/modules';

// Recipes, glyphs and paintings as display + modules (issue #153). Schemas come
// from the plugin's golden snapshot through the ?modules fixture.
test.beforeEach(async ({ page }) => blockCdn(page));

test('a shaped recipe is a mode switch, a crafting grid of item slots and a result card', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Recipes', 'ruby_sword');

  const display = page.getByRole('article', { name: 'Display' });
  const mode = display.getByRole('radiogroup', { name: 'Recipe kind' });
  await expect(mode.getByRole('radio', { name: 'Shaped' })).toHaveAttribute('aria-checked', 'true');
  await expect(mode.getByRole('radio', { name: 'Smelting' })).toBeVisible();

  // The result is an item chip (pack item with its name), not a text reference.
  await expect(display.getByRole('button', { name: 'Result: Ruby Sword' })).toBeVisible();

  const grid = display.getByRole('group', { name: 'Crafting grid' });
  await expect(grid.getByRole('button', { name: 'Slot 1,2: Ruby' })).toBeVisible();
  await expect(grid.getByRole('button', { name: 'Slot 3,2: Stick' })).toBeVisible();
  await expect(grid.getByRole('button', { name: 'Slot 1,1: empty' })).toBeVisible();
  // Shapeless and smelting bodies do not apply to a shaped recipe.
  await expect(display.getByText('Smelts')).toHaveCount(0);

  // The result preview card follows the draft.
  const preview = page.getByRole('region', { name: 'Recipe preview' });
  await expect(preview.getByRole('figure', { name: 'Result' })).toContainText('Ruby Sword');

  await grid.getByRole('button', { name: 'Slot 1,1: empty' }).click();
  const search = page.getByRole('combobox', { name: 'Search items for Slot 1,1' });
  await search.fill('diamond');
  await search.press('Enter');
  await expect(grid.getByRole('button', { name: 'Slot 1,1: Diamond' })).toBeVisible();

  const save = await saveAndApply(page, 1);
  const edits = save.payload.edits as { field: string[]; value: unknown }[];
  expect(edits.map((edit) => edit.field)).toEqual([['ruby_sword', 'shape'], ['ruby_sword', 'ingredients']]);
  const saved = await documentOf(page, 'recipes/ruby_sword.yml');
  expect(saved?.parsed.ruby_sword).toMatchObject({
    type: 'shaped',
    result: 'ruby_sword',
    shape: ['AB', ' B', ' C'],
    ingredients: { A: 'minecraft:diamond', B: 'ruby', C: 'minecraft:stick' },
  });
  expect(pageErrors).toEqual([]);
});

test('shapeless ingredients are item chips with counts; switching mode swaps the body and keeps the other keys', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Recipes', 'ruby_fuse');

  const display = page.getByRole('article', { name: 'Display' });
  await expect(display.locator('[data-item="ruby"] output')).toHaveText('2');
  await display.getByRole('button', { name: 'More Ruby' }).click();
  const first = await saveAndApply(page, 1);
  expect(first.payload.edits).toEqual([
    { field: ['ruby_fuse', 'ingredients'], value: ['ruby', 'ruby', 'ruby'] },
  ]);

  // Smelting: its own body appears, the shapeless ingredients stay in the file.
  await display.getByRole('radio', { name: 'Smelting' }).click();
  await expect(display.getByRole('button', { name: /Smelts: none/ })).toBeVisible();
  await expect(display.getByRole('spinbutton', { name: 'Cooking time' })).toBeVisible();
  await expect(display.locator('[data-item="ruby"]')).toHaveCount(0);
  const second = await saveAndApply(page, 2);
  expect(second.payload.edits).toEqual([{ field: ['ruby_fuse', 'type'], value: 'furnace' }]);
  const saved = await documentOf(page, 'recipes/ruby_fuse.yml');
  expect(saved?.parsed.ruby_fuse?.ingredients).toEqual(['ruby', 'ruby', 'ruby']);
  expect(pageErrors).toEqual([]);
});

test('a smelting recipe shows its input, experience and cooking time with units and ranges', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Recipes', 'ruby_smelt');

  const display = page.getByRole('article', { name: 'Display' });
  await expect(display.getByRole('button', { name: 'Smelts: Ruby' })).toBeVisible();
  await expect(display.getByRole('button', { name: 'Result: Diamond' })).toBeVisible();
  await expect(display.getByText('ticks (20 = 1 s)')).toBeVisible();
  const time = display.getByRole('spinbutton', { name: 'Cooking time' });
  await time.fill('0');
  await time.press('Tab');
  await expect(time).toHaveValue('1'); // clamped to the declared minimum

  const preview = page.getByRole('region', { name: 'Recipe preview' });
  await expect(preview).toContainText('7 xp');
  await expect(page.getByText('1 field is only editable in Advanced Files (future_flag)')).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('a glyph previews in chat and in an item name, with size presets, frames and placeholder chips', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Glyphs', /^heart:/);

  // The glyph drawn inline in the Minecraft-text face, baseline-aligned.
  await expect(page.locator('[data-context="chat"] img.inline')).toBeVisible();
  await expect(page.locator('[data-context="item-name"] img.inline')).toBeVisible();

  const size = page.locator('[data-module="height"]');
  await expect(size).toContainText('Size');
  await expect(size.getByRole('radio', { name: 'Emoji' })).toHaveAttribute('aria-checked', 'true');
  await size.getByRole('radio', { name: 'Big title' }).click();
  await expect(size.getByRole('spinbutton', { name: 'Size (custom)' })).toHaveValue('24');
  await expect(page.locator('[data-context="chat"] img.inline')).toHaveAttribute('style', /height: 48px/);

  const placeholders = page.locator('[data-module="placeholders"]');
  await expect(placeholders.locator('[data-chip=":heart:"]')).toBeVisible();
  await expect(placeholders.locator('[data-chip="<3"]')).toBeVisible();
  await placeholders.getByRole('textbox', { name: /Add to Chat placeholders/ }).fill(':love:');
  await placeholders.getByRole('textbox', { name: /Add to Chat placeholders/ }).press('Enter');
  await placeholders.getByRole('button', { name: 'Remove <3' }).click();

  await expect(page.locator('[data-module="emoji"]').getByRole('checkbox')).toBeChecked();
  await expect(page.locator('[data-module="ascent"]')).toContainText('px');

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['heart', 'height'], value: 24 },
    { field: ['heart', 'placeholders'], value: [':heart:', ':love:'] },
  ]);
  expect(pageErrors).toEqual([]);
});

test('glyph animation frames are a strip: reorder, remove, add, and the module picker offers them', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Glyphs', 'spark');

  const frames = page.locator('[data-module="frames"]');
  await expect(frames.getByRole('listitem')).toHaveCount(2);
  await frames.getByRole('button', { name: 'Remove frame 1' }).click();
  await expect(frames.getByRole('listitem')).toHaveCount(1);

  await page.getByRole('button', { name: 'Add module' }).click();
  const picker = page.getByRole('dialog', { name: 'Add a module' });
  for (const label of ['Size', 'Baseline', 'Chat placeholders', 'Emoji mode', 'Chat completion', 'Permission']) {
    await expect(picker.getByRole('option', { name: new RegExp(label) })).toBeVisible();
  }
  await picker.getByLabel('Search modules').fill('completion');
  await picker.getByLabel('Search modules').press('Enter');

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['spark', 'frames'], value: ['gui/heart'] },
    { field: ['spark', 'tab_complete'], value: true },
  ]);
  expect(pageErrors).toEqual([]);
});

test('a painting resizes by dragging a corner on a block-scale wall; the numbers follow', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Paintings', 'sunrise');

  const display = page.getByRole('article', { name: 'Display' });
  await expect(display.getByRole('textbox', { name: 'Title' })).toContainText('Sunrise');
  await expect(display.getByRole('textbox', { name: 'Author' })).toContainText('Materione');
  const width = display.getByRole('spinbutton', { name: 'Width' });
  await expect(width).toHaveValue('2');
  await expect(display.getByText('1–16').first()).toBeVisible();
  await expect(page.locator('[data-module="placeable"]').getByRole('checkbox')).toBeChecked();

  const handle = page.getByRole('slider', { name: 'Painting size in blocks' });
  await expect(handle).toHaveAttribute('aria-valuetext', '2 by 1 blocks');
  await handle.focus();
  await handle.press('ArrowRight');
  await expect(handle).toHaveAttribute('aria-valuetext', '3 by 1 blocks');

  // Drag the corner to the 5th column / 3rd row of the wall (16 px per block).
  const wall = await page.locator('[data-wall]').boundingBox();
  const from = await handle.boundingBox();
  await page.mouse.move(from!.x + from!.width / 2, from!.y + from!.height / 2);
  await page.mouse.down();
  await page.mouse.move(wall!.x + 5 * 16 - 8, wall!.y + 3 * 16 - 8, { steps: 5 });
  await page.mouse.up();
  await expect(handle).toHaveAttribute('aria-valuetext', '5 by 3 blocks');
  await expect(width).toHaveValue('5');
  await expect(display.getByRole('spinbutton', { name: 'Height' })).toHaveValue('3');

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['sunrise', 'width'], value: 5 },
    { field: ['sunrise', 'height'], value: 3 },
  ]);
  expect(pageErrors).toEqual([]);
});
