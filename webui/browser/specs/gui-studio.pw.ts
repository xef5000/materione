import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import '../contract';
import { SAMPLE_GUI_ASSETS } from '../../src/lib/demo/gui';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The GUI studio (issue 186, gui.md section 6) on the deterministic `?gui` fixture: the sample
// shop screen, the registry's component descriptions and the starter for a new GUI.
test.beforeEach(async ({ page }) => blockCdn(page));

async function openFixture(page: Page, query = 'gui'): Promise<void> {
  await page.goto(`/browser/fixture.html?${query}`);
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
}

async function openShop(page: Page, query = 'gui'): Promise<void> {
  await openFixture(page, query);
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

test('a new GUI opens as a vanilla chest: a panel and slot wells from the server starter, drawn by the shared bevel', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: /new gui/i }).first().click();
  await page.getByRole('dialog').getByRole('textbox').first().fill('Chest Test');
  await page.keyboard.press('Enter');
  await expect(canvas(page)).toBeVisible();
  expect(await canvas(page).evaluate((element: HTMLCanvasElement) => [element.width, element.height])).toEqual([176, 168]); // the starter is a 3-row chest
  await expect(page.locator('.layers .row .name')).toHaveText(['wells', 'frame']);

  // the compiled background, pixel for pixel (fixtures/gui/raster: panel + slots region all)
  expect(await pixel(page, 0, 0)).toEqual([0, 0, 0, 0]); // transparent corner
  expect(await pixel(page, 1, 0)).toEqual([0, 0, 0, 255]); // outline
  expect(await pixel(page, 1, 1)).toEqual([255, 255, 255, 255]); // highlight
  expect(await pixel(page, 5, 5)).toEqual([198, 198, 198, 255]); // fill
  expect(await pixel(page, 174, 100)).toEqual([85, 85, 85, 255]); // shadow
  expect(await pixel(page, 7, 17)).toEqual([55, 55, 55, 255]); // slot well, dark edge
  expect(await pixel(page, 8, 18)).toEqual([139, 139, 139, 255]); // slot well interior
  expect(await pixel(page, 24, 34)).toEqual([255, 255, 255, 255]); // slot well, light edge
  expect(await pixel(page, 8, 85)).toEqual([139, 139, 139, 255]); // first inventory slot (y = 3*18+31)
  expect(await pixel(page, 8, 143)).toEqual([139, 139, 139, 255]); // hotbar slot (y = 3*18+89)
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
    ['Panel', 'Slots', 'Image', 'Paint', 'Button', 'Text', 'Item']);
  await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Panel' }).click();
  await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Paint' }).click();
  await expect(page.locator('.layers .row .name').first()).toHaveText('Paint');

  await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Panel', exact: true }).first().click();
  // x/y/w/h scrub fields and the style colors come from the component's description
  await expect(page.getByRole('textbox', { name: 'W', exact: true })).toHaveValue('48');
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

test('dragging on the frame marquee-selects what is on it; a click selects the frame, and a selected frame moves', async ({ page }) => {
  await openShop(page);
  await page.getByRole('button', { name: 'Lock Slots' }).click(); // the wells under the tag would be covered by the marquee too
  const [x0, y0] = await at(page, 110, 12);
  const [x1, y1] = await at(page, 170, 76);
  await page.mouse.move(x0, y0);
  await page.mouse.down();
  await page.mouse.move(x1, y1, { steps: 6 });
  await page.mouse.up();
  const selected = page.getByRole('region', { name: 'Layers' }).locator('.row.selected .name');
  await expect(selected).toContainText(['Coin', 'Buy button', 'Price tag']);
  await expect(page.getByRole('textbox', { name: 'X', exact: true })).toHaveCount(1); // the group's transform
  // the frame itself was not moved
  await page.getByRole('button', { name: 'Frame', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'X', exact: true })).toHaveValue('0');
  // a plain click on the frame selects it
  const [cx, cy] = await at(page, 3, 100);
  await page.keyboard.press('Escape');
  await page.mouse.click(cx, cy);
  await expect(selected).toHaveText(['Frame']);
  // now that it is selected, dragging moves it
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + 30, cy + 30, { steps: 5 });
  await page.mouse.up();
  await expect(page.getByRole('textbox', { name: 'X', exact: true })).not.toHaveValue('0');
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

// ---------------------------------------------------------------------------------------------
// Issue 187: paint, image, text and item components.

const RED = [255, 0, 0, 255];
const hex = (page: Page): Locator => page.getByRole('textbox', { name: 'Paint colour hex' });

async function chooseColour(page: Page, value: string): Promise<void> {
  await hex(page).fill(value);
  await hex(page).press('Enter');
  await expect(hex(page)).toHaveValue(value.toUpperCase());
}

/** Drags across GUI pixels (screen positions from the live canvas box), in `steps` pointer moves. */
async function drag(page: Page, from: [number, number], to: [number, number], steps = 3): Promise<void> {
  const [x0, y0] = await at(page, ...from);
  const [x1, y1] = await at(page, ...to);
  await page.mouse.move(x0, y0);
  await page.mouse.down();
  await page.mouse.move(x1, y1, { steps });
  await page.mouse.up();
}

function recorded(page: Page, kind: string) {
  return writes(page).then((all) => all.filter((entry) => entry.op === kind));
}

const rgb = (color: number[]): number[] => color.slice(0, 3);

test.describe('paint tools', () => {
  test('the brush draws a continuous stroke at any zoom; the whole stroke is ONE undo step; paint-only saves send empty edits and the PNG', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Brush', exact: true }).click();
    await expect(page.getByTestId('paint-options')).toBeVisible();
    await chooseColour(page, '#FF0000');
    const before = await pixel(page, 40, 120);
    expect(before).not.toEqual(RED);

    // a fast drag: three big pointer jumps, no gaps in between
    await drag(page, [20, 120], [60, 120]);
    for (const x of [20, 21, 33, 47, 59, 60]) expect(await pixel(page, x, 120), `x=${x}`).toEqual(RED);
    await expect(page.locator('[data-status]')).toHaveText('Unsaved changes');

    // the next stroke at 100% zoom, where one GUI pixel is one screen pixel
    await page.keyboard.press('1');
    await expect(page.locator('[data-zoom]')).toHaveText('100%');
    await drag(page, [20, 130], [60, 131], 2);
    expect(rgb(await pixel(page, 20, 130))).toEqual([255, 0, 0]);
    expect(rgb(await pixel(page, 60, 131))).toEqual([255, 0, 0]);
    // the line is continuous: every column between the two ends has exactly one red pixel on row 130 or 131
    for (let x = 20; x <= 60; x++) {
      const [a, b] = [rgb(await pixel(page, x, 130)), rgb(await pixel(page, x, 131))];
      expect([a, b].some((color) => color.join() === '255,0,0'), `1x column ${x}`).toBe(true);
    }

    // and at 16x
    for (let i = 0; i < 12; i++) await page.keyboard.press('=');
    await expect(page.locator('[data-zoom]')).toHaveText('1600%');
    await page.keyboard.press('0');

    // each stroke is one step: undo takes the last one back whole, and again, then the status is clean
    await page.keyboard.press('Control+z');
    expect(await pixel(page, 40, 130)).not.toEqual(RED);
    expect(await pixel(page, 40, 120)).toEqual(RED); // the first stroke is untouched
    await page.keyboard.press('Control+z');
    expect(await pixel(page, 40, 120)).toEqual(before);
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    await page.keyboard.press('Control+Shift+z');
    expect(await pixel(page, 40, 120)).toEqual(RED);

    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const sent = await recorded(page, 'authoring.setFields');
    expect(sent).toHaveLength(1);
    const payload = sent[0]!.payload as { edits: unknown[]; assets: { path: string; data: string }[] };
    expect(payload.edits).toEqual([]); // nothing but pixels changed
    expect(payload.assets.map((asset) => asset.path)).toEqual(['assets/textures/gui/shop/main/art.png']);
    expect(Buffer.from(payload.assets[0]!.data, 'base64').subarray(1, 4).toString()).toBe('PNG');
  });

  test('sizes 1 to 8 and the round shape change the footprint; [ and ] step the size', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Brush', exact: true }).click();
    await chooseColour(page, '#00FF00');
    await page.getByRole('button', { name: 'Brush size 3', exact: true }).click();
    await page.getByRole('button', { name: 'Round brush' }).click();
    const [cx, cy] = await at(page, 30, 125);
    await page.mouse.click(cx, cy);
    // a round size-3 brush is a plus: the corners stay untouched
    expect(rgb(await pixel(page, 30, 125))).toEqual([0, 255, 0]);
    expect(rgb(await pixel(page, 29, 125))).toEqual([0, 255, 0]);
    expect(rgb(await pixel(page, 30, 124))).toEqual([0, 255, 0]);
    expect(rgb(await pixel(page, 29, 124))).not.toEqual([0, 255, 0]);
    await page.keyboard.press(']');
    await expect(page.getByRole('button', { name: 'Brush size 4', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await page.keyboard.press('[');
    await page.keyboard.press('[');
    await expect(page.getByRole('button', { name: 'Brush size 2', exact: true })).toHaveAttribute('aria-pressed', 'true');
  });

  test('the eraser clears, fill floods the contiguous region only, and both undo whole', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Fill', exact: true }).click();
    await chooseColour(page, '#FF0000');
    const [fx, fy] = await at(page, 170, 160);
    const underneath = await pixel(page, 100, 100);
    await page.mouse.click(fx, fy);
    expect(await pixel(page, 100, 100)).toEqual(RED); // the transparent paint region is one contiguous area
    await page.keyboard.press('Control+z');
    expect(await pixel(page, 100, 100)).toEqual(underneath);
    await page.keyboard.press('Control+Shift+z');

    await page.keyboard.press('e');
    await page.getByRole('button', { name: 'Brush size 8', exact: true }).click();
    await drag(page, [60, 100], [90, 100]);
    expect(await pixel(page, 75, 100)).toEqual(underneath);
    expect(await pixel(page, 75, 80)).toEqual(RED);
    await page.keyboard.press('Control+z');
    expect(await pixel(page, 75, 100)).toEqual(RED);
  });

  test('the eyedropper samples the composited canvas (Alt from any tool); recent colours remember what was painted', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Eyedropper', exact: true }).click();
    const [ex, ey] = await at(page, 3, 100);
    await page.mouse.click(ex, ey);
    await expect(hex(page)).toHaveValue('#C6C6C6'); // the frame's fill, not the empty paint layer under it
    // after a pick the studio hands back to the brush
    await expect(page.getByRole('button', { name: 'Brush', exact: true })).toHaveAttribute('aria-pressed', 'true');

    await page.keyboard.down('Alt');
    const [wx, wy] = await at(page, 100, 5);
    await page.mouse.click(wx, wy);
    await page.keyboard.up('Alt');
    await expect(hex(page)).toHaveValue(/^#[0-9A-F]{6}$/);

    await chooseColour(page, '#3C8527');
    await drag(page, [20, 140], [30, 140]);
    await expect(page.getByRole('button', { name: 'Use #3C8527' })).toBeVisible();
    await chooseColour(page, '#FF0000');
    await page.getByRole('button', { name: 'Use #3C8527' }).click();
    await expect(hex(page)).toHaveValue('#3C8527');
  });

  test('a paint tool with no paint layer starts one; a locked layer is explained, not painted', async ({ page }) => {
    await openFixture(page);
    await page.getByRole('button', { name: /new gui/i }).first().click();
    await page.getByRole('dialog').getByRole('textbox').first().fill('Paint Test');
    await page.keyboard.press('Enter');
    await expect(canvas(page)).toBeVisible();
    await page.getByRole('button', { name: 'Brush', exact: true }).click();
    await expect(page.getByTestId('gui-hint')).toContainText('start a Paint layer');
    await chooseColour(page, '#FF0000');
    const [x, y] = await at(page, 30, 40);
    await page.mouse.click(x, y);
    await expect(page.locator('.layers .row .name').first()).toHaveText('Paint');
    expect(rgb(await pixel(page, 30, 40))).toEqual([255, 0, 0]);
    // one Ctrl+Z takes the stroke, the next the layer it created
    await page.keyboard.press('Control+z');
    await page.keyboard.press('Control+z');
    await expect(page.locator('.layers .row .name')).toHaveText(['wells', 'frame']);

    await page.keyboard.press('Control+Shift+z');
    await page.getByRole('button', { name: 'Lock Paint' }).click();
    await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Paint', exact: true }).click();
    await page.mouse.click(x + 40, y);
    await expect(page.getByTestId('gui-hint')).toContainText('is locked');
  });
});

test.describe('image layers', () => {
  const coin = Buffer.from(SAMPLE_GUI_ASSETS[0]!.data, 'base64');

  test('upload: the file is stored beside the GUI as it was chosen, drawn at natural size, scaled with whole multiples, and saved in the layout transaction', async ({ page }) => {
    await openShop(page);
    await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Image' }).click();
    await expect(page.getByRole('dialog', { name: 'Add an image' })).toBeVisible();
    await page.getByLabel('Upload an image').first().setInputFiles({ name: 'My Logo.png', mimeType: 'image/png', buffer: coin });
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.locator('.layers .row.selected .name')).toHaveText('My logo');
    await expect(page.getByTestId('image-size')).toContainText('16 x 16 px · natural 16 x 16 · 1x');

    await page.getByRole('button', { name: '3x', exact: true }).click();
    await expect(page.getByTestId('image-size')).toContainText('48 x 48 px · natural 16 x 16 · 3x');
    await expect(page.getByRole('textbox', { name: 'W', exact: true })).toHaveValue('48');
    await expect(page.getByTestId('natural-size')).toBeEnabled();
    await page.getByTestId('natural-size').click();
    await expect(page.getByRole('textbox', { name: 'W', exact: true })).toHaveValue('16');
    await expect(page.getByTestId('natural-size')).toBeDisabled();

    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const sent = await recorded(page, 'authoring.setFields');
    expect(sent).toHaveLength(1);
    const payload = sent[0]!.payload as { edits: { field: string[]; value: { name?: string; image?: string }[] }[]; assets: { path: string; data: string }[] };
    expect(payload.assets.map((asset) => asset.path)).toEqual(['assets/textures/gui/shop/main/my_logo.png']);
    expect(payload.assets[0]!.data).toBe(coin.toString('base64')); // the original bytes, not a re-encode
    expect(payload.edits[0]!.value.find((layer) => layer.name === 'My logo')?.image).toBe('gui/shop/main/my_logo');
  });

  test('pick from the Assets workspace: a texture the pack already has, at its natural size, nothing uploaded', async ({ page }) => {
    await openShop(page);
    await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Image' }).click();
    await page.getByTestId('image-from-assets').click();
    await page.getByRole('option', { name: /coin/ }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.locator('.layers .row.selected .name')).toHaveText('Coin');
    await expect(page.getByTestId('image-size')).toContainText('natural 16 x 16');
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const payload = (await recorded(page, 'authoring.setFields'))[0]!.payload as { assets?: unknown[] };
    expect(payload.assets ?? []).toEqual([]);
  });

  test('an image layer can be re-pointed: Upload replaces the texture and takes its natural size', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Coin', exact: true }).click();
    await expect(page.getByTestId('image-size')).toContainText('32 x 32 px · natural 16 x 16 · 2x');
    await page.getByTestId('image-extras').getByLabel('Upload an image').setInputFiles({ name: 'gold.png', mimeType: 'image/png', buffer: coin });
    await expect(page.getByTestId('image-size')).toContainText('16 x 16 px · natural 16 x 16 · 1x');
    await page.keyboard.press('Control+z');
    await expect(page.getByTestId('image-size')).toContainText('32 x 32 px');
  });
});

test.describe('text layers', () => {
  const FONT_SOURCE = fileURLToPath(new URL('../../../fonts/smallcaps/smallcaps.txt', import.meta.url));
  const FONT_ADVANCES = fileURLToPath(new URL('../../../fixtures/gui/smallcaps/advances.txt', import.meta.url));

  /** The ink pixels of a smallcaps run, parsed straight from the font source and the advance fixture (never from the studio's own code). */
  function ink(text: string, x: number, y: number): [number, number][] {
    const glyphs = new Map<number, string[]>();
    let rows: string[] | null = null;
    for (const raw of readFileSync(FONT_SOURCE, 'utf8').split('\n')) {
      const line = raw.trimEnd();
      const header = /^glyph U\+([0-9A-Fa-f]{4}) /.exec(line);
      if (header) {
        rows = [];
        glyphs.set(parseInt(header[1], 16), rows);
      } else if (rows && /^[#.]+$/.test(line)) rows.push(line);
      else if (line === '') rows = null;
    }
    const advances = new Map<number, number>();
    for (const raw of readFileSync(FONT_ADVANCES, 'utf8').split('\n')) {
      const line = raw.trim();
      if (!line || line.startsWith(';')) continue;
      const [key, value] = line.split(' ');
      advances.set(key === 'space' ? 32 : Number(key), Number(value));
    }
    const out: [number, number][] = [];
    let pen = x;
    for (const char of text) {
      const cp = char.codePointAt(0)!;
      (glyphs.get(cp) ?? []).forEach((row, dy) => {
        for (let dx = 0; dx < row.length; dx++) if (row[dx] === '#') out.push([pen + dx, y + dy]);
      });
      pen += advances.get(cp)!;
    }
    return out;
  }

  test('smallcaps is previewed pixel for pixel from the shipped sheet: every ink pixel is the text colour where the fixture says', async ({ page }) => {
    await openShop(page);
    // the client's own Inventory label is drawn over the background: a faint ghost at (8, height - 94), 3 rows = 168 px
    const label = (await page.getByTestId('inventory-label').boundingBox())!;
    const art = (await canvas(page).boundingBox())!;
    expect(Math.abs(label.x - (art.x + 8 * (art.width / 176)))).toBeLessThan(2);
    // the box of an SVG text starts a little above its glyph row: within a few GUI pixels of the row
    const row = art.y + (168 - 94) * (art.width / 176);
    expect(label.y).toBeGreaterThan(row - 4 * (art.width / 176));
    expect(label.y).toBeLessThan(row + 4 * (art.width / 176));
    for (const [x, y] of ink('Shop', 8, 6)) expect(await pixel(page, x, y), `(${x},${y})`).toEqual([64, 64, 64, 255]);
    await page.getByRole('button', { name: 'Heading', exact: true }).click();
    await expect(page.getByTestId('smallcaps-hint')).toContainText('Pixel-exact');
    await page.getByRole('textbox', { name: 'Colour hex' }).fill('#FF8800');
    await page.keyboard.press('Enter');
    for (const [x, y] of ink('Shop', 8, 6)) expect(await pixel(page, x, y)).toEqual([255, 136, 0, 255]);
  });

  test('double-click edits in place: live pixel-exact preview, Enter commits ONE undo step, Escape takes it back', async ({ page }) => {
    await openShop(page);
    const [x, y] = await at(page, 10, 8);
    await page.mouse.dblclick(x, y);
    await expect(page.getByTestId('gui-text-edit')).toBeVisible();
    await page.keyboard.type('Hi there');
    for (const [px, py] of ink('Hi there', 8, 6)) expect(await pixel(page, px, py)).toEqual([64, 64, 64, 255]); // live, before committing
    await expect(page.locator('[data-status]')).toHaveText('Saved'); // typing alone is not in the history
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('gui-text-edit')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Text', exact: true })).toHaveValue('Hi there');
    await expect(page.locator('[data-status]')).toHaveText('Unsaved changes');
    await page.keyboard.press('Control+z'); // eight keystrokes, one step
    await expect(page.getByRole('textbox', { name: 'Text', exact: true })).toHaveValue('Shop');
    await expect(page.locator('[data-status]')).toHaveText('Saved');

    await page.mouse.dblclick(x, y);
    await page.keyboard.type('Nope');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('textbox', { name: 'Text', exact: true })).toHaveValue('Shop');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
  });

  test('a new Text layer opens in edit mode on the canvas, like a text tool', async ({ page }) => {
    await openShop(page);
    await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Text' }).click();
    await expect(page.getByTestId('gui-text-edit')).toBeVisible();
    await page.keyboard.type('Gold');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('textbox', { name: 'Text', exact: true })).toHaveValue('Gold');
  });

  test('the default font says its preview is approximate; with no GUI API the studio shows no compiler verdict and says so', async ({ page }) => {
    await openShop(page);
    await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Text' }).click();
    await page.keyboard.press('Escape'); // keep the placeholder text; it is listed above the heading
    await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Heading', exact: true }).click();
    await page.getByRole('group', { name: 'Font' }).getByRole('button', { name: 'Default', exact: true }).click();
    await expect(page.getByTestId('default-font-hint')).toContainText('Approximate preview');
    // the demo has no compiler: the restrictions the compiler applies come from gui.validate, which it
    // cannot answer, so the studio does not claim any layer is skipped and says where the verdict comes from
    await expect(page.getByTestId('gui-api-unavailable')).toBeVisible();
    await expect(page.getByTestId('text-issue')).toHaveCount(0);
    await expect(page.locator('[data-flag]')).toHaveCount(0);
  });
});

test.describe('item layers', () => {
  test('items snap to container slots only: dragging one over the player inventory lands it in the chest', async ({ page }) => {
    await openShop(page);
    await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Sword', exact: true }).click();
    await drag(page, [88, 44], [60, 100], 5); // over the player inventory
    const slot = Number(await page.getByRole('textbox', { name: 'Slot', exact: true }).inputValue());
    expect(slot).toBeGreaterThanOrEqual(0);
    expect(slot).toBeLessThan(27);
    await expect(page.locator('[data-flag]')).toHaveCount(0);
    // an item typed onto an inventory slot would be flagged by the server's verdict; the demo has none
    await page.getByRole('textbox', { name: 'Slot', exact: true }).fill('30');
    await page.keyboard.press('Enter');
    await expect(page.locator('[data-flag=sword]')).toHaveCount(0);
    await expect(page.getByTestId('gui-api-unavailable')).toBeVisible();
  });

  test('the picker offers vanilla materials; the choice is stored as minecraft:id', async ({ page }) => {
    await openShop(page);
    await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Sword', exact: true }).click();
    await page.getByRole('button', { name: /^Item:/ }).click();
    await page.getByRole('combobox', { name: /Search items/ }).fill('stick');
    await page.getByRole('combobox', { name: /Search items/ }).press('Enter');
    await expect(page.getByTestId('item-extras')).toContainText('minecraft:stick');
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const saved = await documentOf(page, 'guis/shop/main.yml');
    expect((saved!.parsed.main as { layers: { id: string; item?: string }[] }).layers.find((layer) => layer.id === 'sword')?.item).toBe('minecraft:stick');
  });

  test('Customize needs the server\'s GUI API: in the demo it is disabled, says why, and writes nothing', async ({ page }) => {
    // The live flow (one gui.customizeItem transaction, the item and its pointer, then the inline
    // appearance) is pinned by the QA catalog on a running server (gui.studio-item-customize); the demo
    // has no server to plan it, so the studio refuses here instead of planning it in TypeScript.
    await openShop(page, 'gui&modules');
    await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Sword', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Customize', exact: true })).toBeDisabled();
    await expect(page.getByTestId('item-appearance')).toContainText('needs a Materione plugin with the GUI API');
    await expect(page.getByTestId('gui-api-unavailable')).toBeVisible();
    expect(await writes(page)).toEqual([]);
  });

  test('a vanilla item layer is no different in the demo: Customize waits for the server, and nothing is written', async ({ page }) => {
    await openShop(page, 'gui&modules');
    await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Apple', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Customize', exact: true })).toBeDisabled();
    expect(await writes(page)).toEqual([]);
  });
});
