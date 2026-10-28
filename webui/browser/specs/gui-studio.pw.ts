import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The GUI studio (issue 186, gui.md section 6) on the deterministic `?gui` fixture: the sample
// shop screen, the registry's component descriptions and the starter for a new GUI.
test.beforeEach(async ({ page }) => blockCdn(page));

async function openFixture(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?gui');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
}

async function openShop(page: Page): Promise<void> {
  await openFixture(page);
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
  await expect(page.locator('[data-status]')).toHaveText('Saved');
}

function canvas(page: Page): Locator {
  return page.getByTestId('gui-art');
}

/** The GUI pixel (x, y) of the canvas as the browser paints it: RGBA from the real 2D context. */
function pixel(page: Page, x: number, y: number): Promise<number[]> {
  return page.evaluate(([px, py]) => {
    const element = document.querySelector<HTMLCanvasElement>('[data-testid=gui-art]')!;
    return [...element.getContext('2d')!.getImageData(px!, py!, 1, 1).data];
  }, [x, y]);
}

/** Screen position of a GUI pixel, from the canvas's live box. */
async function at(page: Page, x: number, y: number): Promise<[number, number]> {
  const box = (await canvas(page).boundingBox())!;
  const zoom = box.width / 176;
  return [box.x + x * zoom + zoom / 2, box.y + y * zoom + zoom / 2];
}

test('a new GUI opens as a vanilla chest: a panel and slot wells, 176 x 222, drawn by the shared bevel', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: /new gui/i }).first().click();
  await page.getByRole('dialog').getByRole('textbox').first().fill('Chest Test');
  await page.keyboard.press('Enter');
  await expect(canvas(page)).toBeVisible();
  expect(await canvas(page).evaluate((element: HTMLCanvasElement) => [element.width, element.height])).toEqual([176, 222]);
  await expect(page.locator('.layers .row .name')).toHaveText(['Slots', 'Frame']);

  // the compiled background, pixel for pixel (fixtures/gui/raster/chest-6-rows.png)
  expect(await pixel(page, 0, 0)).toEqual([0, 0, 0, 0]); // transparent corner
  expect(await pixel(page, 1, 0)).toEqual([0, 0, 0, 255]); // outline
  expect(await pixel(page, 1, 1)).toEqual([255, 255, 255, 255]); // highlight
  expect(await pixel(page, 5, 5)).toEqual([198, 198, 198, 255]); // fill
  expect(await pixel(page, 174, 100)).toEqual([85, 85, 85, 255]); // shadow
  expect(await pixel(page, 7, 17)).toEqual([55, 55, 55, 255]); // slot well, dark edge
  expect(await pixel(page, 8, 18)).toEqual([139, 139, 139, 255]); // slot well interior
  expect(await pixel(page, 24, 34)).toEqual([255, 255, 255, 255]); // slot well, light edge
  expect(await pixel(page, 8, 139)).toEqual([139, 139, 139, 255]); // first inventory slot (y = 6*18+31)
  expect(await pixel(page, 8, 197)).toEqual([139, 139, 139, 255]); // hotbar slot (y = 6*18+89)
});

test('select, nudge with arrow keys (Shift = 10), undo and redo; the status follows the history', async ({ page }) => {
  await openShop(page);
  await page.getByRole('button', { name: 'Buy button', exact: true }).click();
  const x = page.getByRole('textbox', { name: 'X', exact: true });
  await expect(x).toHaveValue('118');

  await page.getByTestId('gui-art').focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Shift+ArrowDown');
  await expect(x).toHaveValue('120');
  await expect(page.getByRole('textbox', { name: 'Y', exact: true })).toHaveValue('60');
  await expect(page.locator('[data-status]')).toHaveText('Unsaved changes');

  // a burst of nudges is ONE undo step, and undoing back to the saved state makes it clean again
  await page.keyboard.press('Control+z');
  await expect(x).toHaveValue('118');
  await expect(page.getByRole('textbox', { name: 'Y', exact: true })).toHaveValue('50');
  await expect(page.locator('[data-status]')).toHaveText('Saved');
  await page.keyboard.press('Control+Shift+z');
  await expect(x).toHaveValue('120');
  await expect(page.locator('[data-status]')).toHaveText('Unsaved changes');
});

test('drag-moves a layer on the canvas, resizes it by a handle, and one undo takes each back', async ({ page }) => {
  await openShop(page);
  const [fromX, fromY] = await at(page, 142, 65);
  const box = (await canvas(page).boundingBox())!;
  const zoom = box.width / 176;
  await page.mouse.move(fromX, fromY);
  await page.mouse.down();
  await page.mouse.move(fromX - 4 * zoom, fromY + 2 * zoom, { steps: 6 });
  await page.mouse.up();
  await expect(page.getByRole('textbox', { name: 'X', exact: true })).not.toHaveValue('118');
  const movedX = Number(await page.getByRole('textbox', { name: 'X', exact: true }).inputValue());
  expect(movedX).toBeLessThan(118);

  const handle = page.locator('[data-handle=e]');
  const h = (await handle.boundingBox())!;
  await page.mouse.move(h.x + 4, h.y + 4);
  await page.mouse.down();
  await page.mouse.move(h.x + 4 + 6 * zoom, h.y + 4, { steps: 6 });
  await page.mouse.up();
  const width = Number(await page.getByRole('textbox', { name: 'W', exact: true }).inputValue());
  expect(width).toBeGreaterThan(48);

  await page.keyboard.press('Control+z');
  await expect(page.getByRole('textbox', { name: 'W', exact: true })).toHaveValue('48');
  await page.keyboard.press('Control+z');
  await expect(page.getByRole('textbox', { name: 'X', exact: true })).toHaveValue('118');
});

test('the layers panel renames, hides, locks and reorders; the badge says what the player will see', async ({ page }) => {
  await openShop(page);
  const names = page.locator('.layers .row .name');
  await expect(names).toHaveText(['Apple', 'Sword', 'Coin', 'Buy button', 'Price tag', 'Heading', 'Header art', 'Slots', 'Frame']);

  // heading is text: listed under raster layers but compiled over them
  await expect(page.getByRole('img', { name: /Renders over/ }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Coin', exact: true }).dblclick();
  await page.getByLabel('Layer name').fill('Gold coin');
  await page.keyboard.press('Enter');
  await expect(names.nth(2)).toHaveText('Gold coin');

  await page.locator('.layers .row', { hasText: 'Gold coin' }).dragTo(
    page.locator('.layers .row', { hasText: 'Apple' }), { targetPosition: { x: 60, y: 2 } });
  await expect(names.first()).toHaveText('Gold coin');
  await page.keyboard.press('Control+z');
  await expect(names.nth(2)).toHaveText('Gold coin');

  const before = await pixel(page, 142, 60);
  await page.getByRole('button', { name: 'Hide Buy button' }).click();
  expect(await pixel(page, 142, 60)).not.toEqual(before);
  await page.getByRole('button', { name: 'Show Buy button' }).click();
  expect(await pixel(page, 142, 60)).toEqual(before);

  await page.getByRole('button', { name: 'Lock Price tag' }).click();
  const [x, y] = await at(page, 120, 25);
  await page.mouse.click(x, y);
  // the click falls through the locked layer to the slot wells beneath it
  await expect(page.getByRole('region', { name: 'Layers' }).locator('.row.selected .name')).toHaveText('Slots');
});

test('adds components from the registry palette, schema-derived inspector, then saves ONE transaction with the paint asset', async ({ page }) => {
  await openShop(page);
  await expect(page.getByRole('region', { name: 'Components' }).getByRole('button')).toHaveText(
    ['Panel', 'Slot wells', 'Image', 'Paint', 'Button', 'Text', 'Item']);
  await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Panel' }).click();
  await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Paint' }).click();
  await expect(page.locator('.layers .row .name').first()).toHaveText('Paint');

  await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Panel', exact: true }).first().click();
  // x/y/w/h scrub fields and the style colors come from the component's description
  await expect(page.getByRole('textbox', { name: 'W', exact: true })).toHaveValue('64');
  await page.getByRole('textbox', { name: 'Fill hex' }).fill('#3C8527');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('textbox', { name: 'Fill hex' })).toHaveValue('#3C8527');

  await page.keyboard.press('Control+s');
  await expect(page.locator('[data-status]')).toHaveText('Saved');
  const recorded = (await writes(page)).filter((entry) => entry.op === 'authoring.setFields');
  expect(recorded).toHaveLength(1);
  const payload = recorded[0]!.payload as { edits: { field: string[] }[]; assets: { path: string }[] };
  expect(payload.edits.map((edit) => edit.field)).toEqual([['main', 'layers']]);
  expect(payload.assets.map((asset) => asset.path)).toEqual(['assets/textures/gui/shop/main/paint.png']);
  const saved = await documentOf(page, 'guis/shop/main.yml');
  expect((saved!.parsed.main as { layers: { id: string }[] }).layers.map((layer) => layer.id)).toContain('panel');
});

test('zoom 1x to 16x around the cursor, pixel grid from 8x, 0 fits and 1 is 100%, space-drag pans', async ({ page }) => {
  await openShop(page);
  const zoom = page.locator('[data-zoom]');
  const [wx, wy] = await at(page, 40, 40);
  await page.mouse.move(wx, wy);
  const under = async () => (await at(page, 40, 40));
  for (let i = 0; i < 3; i++) await page.mouse.wheel(0, -100);
  await expect(zoom).toHaveText('600%');
  // the GUI pixel under the cursor stayed under the cursor
  const [ux, uy] = await under();
  expect(Math.abs(ux - wx)).toBeLessThan(6);
  expect(Math.abs(uy - wy)).toBeLessThan(6);

  await expect(page.locator('.pixels')).toHaveCount(0);
  for (let i = 0; i < 12; i++) await page.keyboard.press('=');
  await expect(zoom).toHaveText('1600%');
  await expect(page.locator('.pixels')).toHaveCount(1);

  await page.keyboard.press('1');
  await expect(zoom).toHaveText('100%');
  await page.keyboard.press('0');
  await expect(zoom).toHaveText('300%');

  const before = (await canvas(page).boundingBox())!;
  await page.keyboard.down(' ');
  await page.mouse.move(before.x + 100, before.y + 100);
  await page.mouse.down();
  await page.mouse.move(before.x + 60, before.y + 70, { steps: 4 });
  await page.mouse.up();
  await page.keyboard.up(' ');
  const after = (await canvas(page).boundingBox())!;
  expect(Math.round(after.x - before.x)).toBe(-40);
  expect(Math.round(after.y - before.y)).toBe(-30);
});

test('keyboard shortcuts: V and H switch tools, Ctrl+D duplicates, Delete removes, Ctrl+S saves', async ({ page }) => {
  await openShop(page);
  await page.keyboard.press('h');
  await expect(page.getByRole('button', { name: 'Hand', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('v');
  await expect(page.getByRole('button', { name: 'Select', exact: true })).toHaveAttribute('aria-pressed', 'true');

  await page.getByRole('button', { name: 'Buy button', exact: true }).click();
  await page.keyboard.press('Control+d');
  await expect(page.locator('.layers .row .name')).toHaveCount(10);
  await expect(page.locator('.layers .row.selected .name')).toHaveText('Buy button copy'); // the copy is selected, just above the original
  await page.keyboard.press('Delete');
  await expect(page.locator('.layers .row .name')).toHaveCount(9);
  await page.keyboard.press('Control+z');
  await expect(page.locator('.layers .row .name')).toHaveCount(10);

  await page.keyboard.press('Control+s');
  await expect(page.locator('[data-status]')).toHaveText('Saved');
  // the history outlives a save: undoing across it works, and the file is then the thing that differs
  await page.keyboard.press('Control+z');
  await expect(page.locator('.layers .row .name')).toHaveCount(9);
  await expect(page.locator('[data-status]')).toHaveText('Unsaved changes');
});

test('the studio fills the main area and never scrolls sideways, even in a narrow window', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 700 });
  await openShop(page);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const studio = (await page.locator('section.gui-studio').boundingBox())!;
  const stage = (await page.locator('.stage').boundingBox())!;
  expect(stage.width).toBeGreaterThan(300);
  expect(stage.y + stage.height).toBeLessThanOrEqual(studio.y + studio.height + 1);
});
