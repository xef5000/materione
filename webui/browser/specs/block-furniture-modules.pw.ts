import { expect, test } from '@playwright/test';
import {
  addModule, blockCdn, documentOf, openModules, openResource, saveAndApply,
} from './helpers/modules';

// Blocks and furniture as display + modules (issue #153). The schemas come
// from the plugin's golden snapshot: the module list, labels, option labels
// and widgets are exactly what the Java registry declares.
test.beforeEach(async ({ page }) => blockCdn(page));

test('a block reads as a display card plus the modules the registry declares', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Blocks', 'Amethyst Lamp');

  const display = page.getByRole('article', { name: 'Display' });
  await expect(display.getByRole('textbox', { name: 'Display name' })).toContainText('Amethyst Lamp');
  await expect(display.getByLabel('Texture', { exact: true })).toBeVisible();

  // "Shape" replaces the backend word `technique`; the stored value stays the key's own.
  const shape = page.locator('[data-module="technique"]');
  await expect(shape).toContainText('Shape');
  await expect(shape.getByRole('radio', { name: 'Slab' })).toHaveAttribute('aria-checked', 'true');
  await expect(shape.getByRole('radio', { name: 'Full block' })).toHaveAttribute('aria-checked', 'false');

  // Drops are item chips with counts: a repeated reference is its count.
  const drops = page.locator('[data-module="drops"]');
  await expect(drops).toContainText('Drops');
  await expect(drops.locator('[data-item="ruby"] output')).toHaveText('2');
  await expect(drops.locator('[data-item="minecraft:diamond"] output')).toHaveText('1');

  await expect(page.locator('[data-module="blast_resistant"]')).toContainText('Explosion resistant');
  const placedBy = page.locator('[data-module="item"]');
  await expect(placedBy).toContainText('Placed by item');
  await expect(placedBy.getByRole('button', { name: /Placed by item: Ruby/ })).toBeVisible();

  // The host block is advanced; the unknown key keeps its honest report.
  await expect(page.getByText('1 field is only editable in Advanced Files (future_flag)')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add module' })).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('block modules write the same lossless edits: shape, drops with counts, pistons, and removal', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Blocks', 'Amethyst Lamp');

  await page.locator('[data-module="technique"]').getByRole('radio', { name: 'Stairs' }).click();

  // Drops: another ruby, a new vanilla item found by search, and one removed.
  const drops = page.locator('[data-module="drops"]');
  await drops.getByRole('button', { name: 'More Ruby' }).click();
  await drops.getByRole('button', { name: 'Add item' }).click();
  await page.getByRole('combobox', { name: 'Search items for Add item' }).fill('emerald');
  await page.getByRole('combobox', { name: 'Search items for Add item' }).press('Enter');
  await expect(drops.locator('[data-item="minecraft:emerald"]')).toBeVisible();
  await drops.getByRole('button', { name: 'Remove Diamond' }).click();

  // A new module: pistons, written with the schema's default and changed to "Breaks".
  await addModule(page, 'piston');
  const pistons = page.locator('[data-module="move_policy"]');
  await expect(pistons.getByRole('radio', { name: 'Moves with the piston' })).toHaveAttribute('aria-checked', 'true');
  await pistons.getByRole('radio', { name: 'Breaks' }).click();

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['amethyst_lamp', 'technique'], value: 'stairs' },
    { field: ['amethyst_lamp', 'drops'], value: ['ruby', 'ruby', 'ruby', 'minecraft:emerald'] },
    { field: ['amethyst_lamp', 'move_policy'], value: 'break' },
  ]);
  const saved = await documentOf(page, 'blocks/amethyst_lamp.yml');
  expect(saved?.parsed.amethyst_lamp).toMatchObject({
    technique: 'stairs',
    drops: ['ruby', 'ruby', 'ruby', 'minecraft:emerald'],
    move_policy: 'break',
    blast_resistant: true,
    future_flag: { keep: 'me' },
  });

  // Removing a switch module removes exactly its key.
  await page.locator('[data-module="blast_resistant"]')
    .getByRole('button', { name: 'Remove Explosion resistant' }).click();
  const removal = await saveAndApply(page, 2);
  expect(removal.payload.edits).toEqual([{ field: ['amethyst_lamp', 'blast_resistant'], remove: true }]);
  expect((await documentOf(page, 'blocks/amethyst_lamp.yml'))?.raw).not.toContain('blast_resistant');
  expect(pageErrors).toEqual([]);
});

test('adding "Explosion resistant" turns it on, and the picker lists the block modules by category', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Blocks', 'Plain Stone');

  await page.getByRole('button', { name: 'Add module' }).click();
  const picker = page.getByRole('dialog', { name: 'Add a module' });
  for (const label of ['Shape', 'Drops', 'Explosion resistant', 'When pushed by pistons', 'Placed by item', 'When… do…']) {
    await expect(picker.getByRole('option', { name: new RegExp(label) })).toBeVisible();
  }
  await picker.getByLabel('Search modules').fill('explosion');
  await picker.getByLabel('Search modules').press('Enter');
  await expect(page.locator('[data-module="blast_resistant"]').getByRole('checkbox')).toBeChecked();

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{ field: ['plain_stone', 'blast_resistant'], value: true }]);
  expect(pageErrors).toEqual([]);
});

test('the placed-by picker offers this pack\'s items with icons and clears with the module intact', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Blocks', 'Amethyst Lamp');

  const placedBy = page.locator('[data-module="item"]');
  await placedBy.getByRole('button', { name: /Placed by item: Ruby/ }).click();
  const search = page.getByRole('combobox', { name: 'Search items for Placed by item' });
  // Placement links are pack items only: no vanilla materials are offered.
  await search.fill('diamond');
  await expect(page.getByText('No matching item.')).toBeVisible();
  await search.fill('');
  await expect(page.getByRole('option', { name: /Ruby Sword/ })).toBeVisible();
  await page.getByRole('option', { name: /Ember Charm/ }).click();

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{ field: ['amethyst_lamp', 'item'], value: 'ember_charm' }]);
  expect(pageErrors).toEqual([]);
});

test('furniture modules: seats, hitbox and light on a block-scale picture, rotation as snapping choices, storage in slots', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Furniture', 'Chair');

  const seats = page.locator('[data-module="seats"]');
  await expect(seats).toContainText('Seats');
  const front = seats.getByRole('group', { name: 'Seats, front view' });
  await expect(front.getByRole('button', { name: 'Seats 1 at x 0, y 0.3, z 0' })).toBeVisible();

  // Keyboard: arrows move a handle by 5 cm (Shift = 25 cm) and rewrite only `offset`.
  const handle = front.getByRole('button', { name: /Seats 1/ });
  await handle.focus();
  await handle.press('ArrowRight');
  await handle.press('Shift+ArrowUp');
  await expect(front.getByRole('button', { name: 'Seats 1 at x 0.05, y 0.55, z 0' })).toBeVisible();

  // Drag: a handle follows the pointer, snapped to the 5 cm grid.
  const box = await front.boundingBox();
  const from = await front.getByRole('button', { name: /Seats 1/ }).boundingBox();
  const scale = box!.width / 4; // the view is 4 blocks wide
  await page.mouse.move(from!.x + from!.width / 2, from!.y + from!.height / 2);
  await page.mouse.down();
  await page.mouse.move(box!.x + scale * 2.5, box!.y + scale * 2, { steps: 4 }); // x = +0.5, y = 0.5
  await page.mouse.up();
  await expect(front.getByRole('button', { name: 'Seats 1 at x 0.5, y 0.5, z 0' })).toBeVisible();

  // The light and the hitbox draw too, and the exact numbers stay editable.
  await expect(page.locator('[data-module="lights"]').getByRole('group', { name: 'Light source, top view' })).toBeVisible();
  await expect(page.locator('[data-module="hitboxes"]')).toContainText('Hitbox');

  // Rotation snapping reads as labelled choices of numbers.
  const rotation = page.locator('[data-module="rotation"]');
  await expect(rotation.getByRole('radio', { name: '90°' })).toHaveAttribute('aria-checked', 'true');
  await rotation.getByRole('radio', { name: '45°' }).click();

  // Storage: a new module with a range and a unit.
  await addModule(page, 'storage');
  const storage = page.locator('[data-module="storage"]');
  await expect(storage).toContainText('slots');
  await expect(storage).toContainText('9–54');
  await storage.getByRole('spinbutton', { name: 'Storage' }).fill('100');
  await storage.getByRole('spinbutton', { name: 'Storage' }).press('Tab');
  await expect(storage.getByRole('spinbutton', { name: 'Storage' })).toHaveValue('54');

  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([
    { field: ['chair', 'seats'], value: [{ offset: [0.5, 0.5, 0] }] },
    { field: ['chair', 'rotation'], value: 45 },
    { field: ['chair', 'storage'], value: 54 },
  ]);
  const saved = await documentOf(page, 'furnitures/chair.yml');
  expect(saved?.parsed.chair).toMatchObject({
    hitboxes: [{ type: 'interaction', offset: [0, 0, 0], width: 1, height: 1 }],
    lights: [{ offset: [0.5, 1, 0], level: 12 }],
  });
  expect(pageErrors).toEqual([]);
});

test('furniture: "Grows into…" and the light module add rows through the same lossless path', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Furniture', 'Stool');

  await addModule(page, 'grows');
  const grows = page.locator('[data-module="evolution"]');
  await expect(grows).toContainText('Grows into…');
  await grows.getByRole('button', { name: 'Add row' }).click();
  await expect(grows.getByRole('row')).toHaveCount(2); // header + one stage

  await addModule(page, 'light');
  await page.locator('[data-module="lights"]').getByRole('button', { name: 'Add light' }).click();

  const save = await saveAndApply(page, 1);
  const edits = save.payload.edits as { field: string[]; value: unknown }[];
  expect(edits.map((edit) => edit.field)).toEqual([['stool', 'evolution'], ['stool', 'lights']]);
  expect(edits[1]!.value).toEqual([{ offset: [0, 0, 0], level: 15 }]);
  expect(pageErrors).toEqual([]);
});
