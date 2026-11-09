import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The studio for every container (gui.md section 12.7, row S, issue 294): the Screen panel's container select (every
// profile the server describes, grouped), a canvas drawn from the profile whatever the container (the screen, the slot
// grid and its ids, the veil boundary, the "Inventory" label), the generated diagram where no owner art exists, the
// one-line cost of a switch, and the switch as one undo step that edits no layer. `?logic` is the fixture whose server
// advertises the `gui` capability, so the verdict (and with it the cost line) is available; `?gui` is a server without it.
test.beforeEach(async ({ page }) => blockCdn(page));

const SHOP_PATH = 'guis/shop/main.yml';

async function openShop(page: Page, query = 'logic'): Promise<void> {
  await page.goto(`/browser/fixture.html?${query}`);
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
  await expect(page.locator('[data-status]')).toHaveText('Saved');
  await expect(page.getByTestId('container-select')).toBeEnabled();
}

function underlaySize(page: Page): Promise<number[]> {
  return page.getByTestId('gui-underlay').evaluate((element: HTMLCanvasElement) => [element.width, element.height]);
}

function underlayPixel(page: Page, x: number, y: number): Promise<number[]> {
  return page.getByTestId('gui-underlay').evaluate(
    (element: HTMLCanvasElement, [px, py]) => [...element.getContext('2d')!.getImageData(px!, py!, 1, 1).data], [x, y]);
}

/** Choose a container in the Screen panel and apply the switch (two steps: the choice is staged, its cost is stated). */
async function switchTo(page: Page, id: string): Promise<void> {
  await page.getByTestId('container-select').selectOption(id);
  await page.getByTestId('container-switch-apply').click();
  await expect(page.getByTestId('container-select')).toHaveValue(id);
}

/** The shop with its layers deleted: nothing but the screen under the art, so the canvas shows the underlay alone. */
async function openEmpty(page: Page, query = 'logic'): Promise<void> {
  await openShop(page, query);
  await page.locator('.stage').focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.press('Delete');
  await expect(page.locator('.layers .row')).toHaveCount(0);
}

test.describe('the container select', () => {
  test('lists every profile the server describes, grouped, and starts on the document\'s own container', async ({ page }) => {
    await openShop(page);
    const select = page.getByTestId('container-select');
    await expect(select).toHaveValue('chest_3');
    const groups = await select.evaluate((element: HTMLSelectElement) =>
      [...element.querySelectorAll('optgroup')].map((group) => [group.label, [...group.querySelectorAll('option')].map((option) => option.value)]));
    expect(groups).toEqual([
      ['Chests', ['chest_1', 'chest_2', 'chest_3', 'chest_4', 'chest_5', 'chest_6']],
      ['Hopper', ['hopper']],
      ['Dispenser', ['dispenser']],
    ]);
    await expect(page.getByTestId('screen-size')).toContainText('176 x 168 px, 27 slots');
  });

  test('a server too old to list containers offers none and says so', async ({ page }) => {
    await page.goto('/browser/fixture.html?gui&noContainers');
    await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
    await page.getByRole('button', { name: 'Content', exact: true }).click();
    await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
    await page.getByRole('option', { name: /shop/i }).dblclick();
    await page.getByRole('option', { name: /main/i }).dblclick();
    await expect(page.getByTestId('gui-art')).toBeVisible();
    await expect(page.getByTestId('container-select')).toBeDisabled();
    await expect(page.getByTestId('container-unlisted')).toContainText('does not list GUI containers');
    // the document is still drawn from its own profile, and shows its own container in the select
    expect(await underlaySize(page)).toEqual([176, 168]);
    await expect(page.getByTestId('container-select')).toHaveValue('chest_3');
  });
});

test.describe('a hopper and a dispenser are drawn from their profiles', () => {
  test('hopper: 176 x 133, five slots, the veil from row 37, the label at (8, 39), a generated diagram', async ({ page }) => {
    await openEmpty(page);
    await switchTo(page, 'hopper');
    await expect.poll(() => underlaySize(page)).toEqual([176, 133]);
    await expect(page.getByTestId('screen-size')).toContainText('176 x 133 px, 5 slots');
    // no owner art for the hopper: the screen is the generated diagram and says so
    await expect(page.getByTestId('underlay-note')).toContainText('Generated diagram');
    // the screen's own corners are transparent, its frame and wells are opaque
    expect((await underlayPixel(page, 0, 0))[3]).toBe(0);
    const frame = await underlayPixel(page, 5, 5);
    expect(frame[3]).toBe(255);
    // a well at slot 0 (item origin (44, 20)), and the veil begins at row 37: the row above is the plain frame, the row at it is dimmed
    expect(await underlayPixel(page, 52, 28)).not.toEqual(frame);
    expect(await underlayPixel(page, 5, 36)).toEqual(frame);
    expect(await underlayPixel(page, 5, 37)).not.toEqual(frame);
    // the ghost "Inventory" label sits at the profile's label origin, (8, 39)
    const box = (await page.getByTestId('gui-underlay').boundingBox())!;
    const zoom = box.width / 176;
    const label = (await page.getByTestId('inventory-label').boundingBox())!;
    expect(Math.abs(label.x - (box.x + 8 * zoom))).toBeLessThan(3); // the glyph's own side bearing
    expect(label.y + label.height).toBeGreaterThan(box.y + 39 * zoom);
    expect(label.y).toBeLessThan(box.y + (39 + 9) * zoom);
  });

  test('hopper: the slot grid has the five slots numbered 0 to 4, then the player\'s 36', async ({ page }) => {
    await openEmpty(page);
    await switchTo(page, 'hopper');
    await page.getByRole('button', { name: 'Slot grid', exact: true }).click();
    await expect(page.getByTestId('slot-id')).toHaveText(['0', '1', '2', '3', '4']);
    await expect(page.locator('rect.slot')).toHaveCount(5 + 36);
  });

  test('dispenser: 176 x 166, a 3 x 3 grid numbered 0 to 8, the veil from row 70, the label at (8, 72)', async ({ page }) => {
    await openEmpty(page);
    await switchTo(page, 'dispenser');
    await expect.poll(() => underlaySize(page)).toEqual([176, 166]);
    await expect(page.getByTestId('screen-size')).toContainText('176 x 166 px, 9 slots');
    await expect(page.getByTestId('underlay-note')).toBeVisible();
    const frame = await underlayPixel(page, 5, 5);
    expect(await underlayPixel(page, 5, 69)).toEqual(frame);
    expect(await underlayPixel(page, 5, 70)).not.toEqual(frame);
    await page.getByRole('button', { name: 'Slot grid', exact: true }).click();
    await expect(page.getByTestId('slot-id')).toHaveText(['0', '1', '2', '3', '4', '5', '6', '7', '8']);
    const box = (await page.getByTestId('gui-underlay').boundingBox())!;
    const zoom = box.width / 176;
    const label = (await page.getByTestId('inventory-label').boundingBox())!;
    expect(label.y + label.height).toBeGreaterThan(box.y + 72 * zoom);
    expect(label.y).toBeLessThan(box.y + (72 + 9) * zoom);
  });

  test('a chest still has the owner\'s art, not the diagram (the same code, a profile whose sheet exists)', async ({ page }) => {
    await openEmpty(page);
    await expect(page.getByTestId('underlay-note')).toHaveCount(0);
    await switchTo(page, 'chest_6');
    await expect.poll(() => underlaySize(page)).toEqual([176, 222]);
    await expect(page.getByTestId('underlay-note')).toHaveCount(0);
    await switchTo(page, 'hopper');
    await expect(page.getByTestId('underlay-note')).toBeVisible();
    await switchTo(page, 'chest_1');
    await expect.poll(() => underlaySize(page)).toEqual([176, 132]);
    await expect(page.getByTestId('underlay-note')).toHaveCount(0);
  });

  test('a new item layer starts on the hopper\'s first slot and its slot never leaves the five', async ({ page }) => {
    await openEmpty(page);
    await switchTo(page, 'hopper');
    await expect.poll(() => underlaySize(page)).toEqual([176, 133]);
    await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Item', exact: true }).click();
    await page.getByTestId('gui-art').focus();
    for (let step = 0; step < 8; step++) await page.keyboard.press('ArrowRight'); // one slot per press, stopping at the last
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const saved = await documentOf(page, SHOP_PATH);
    const layers = saved!.parsed.main!.layers as { type: string; slot?: number }[];
    expect(layers.filter((layer) => layer.type === 'item').map((layer) => layer.slot)).toEqual([4]);
    expect(saved!.parsed.main!.container).toBe('hopper');
  });
});

test.describe('switching the container', () => {
  test('states the cost in one line before it is applied, and the switch edits no layer', async ({ page }) => {
    await openShop(page);
    await page.getByTestId('container-select').selectOption('hopper');
    // staged, not applied: the canvas is still the chest, the cost is the server's verdict on the document as a hopper
    await expect(page.getByTestId('container-switch-note')).toHaveText(
      'On Hopper, 2 item layers use slots it does not have; they are kept and skipped until you move them.');
    expect(await underlaySize(page)).toEqual([176, 168]);
    await page.getByTestId('container-switch-apply').click();
    await expect.poll(() => underlaySize(page)).toEqual([176, 133]);
    // the status bar repeats the cost after the switch
    await expect(page.getByText(/Switched to Hopper\. On Hopper, 2 item layers/)).toBeVisible();
    // every layer is where it was
    await expect(page.locator('.layers .row')).toHaveCount(9);
    // and the verdict flags the two items the hopper lacks: the "!" on the canvas and the warning in the layers panel
    await expect(page.locator('[data-flag]')).toHaveCount(2, { timeout: 10_000 });
    await expect(page.locator('[data-flag=sword]')).toHaveCount(1);
    await expect(page.locator('[data-flag=apple]')).toHaveCount(1);
    await expect(page.locator('.layers .row', { hasText: 'Sword' }).getByRole('img', { name: /Compiler warning/ })).toBeVisible();
  });

  test('says nothing is flagged when every layer fits the target', async ({ page }) => {
    await openShop(page);
    await page.getByTestId('container-select').selectOption('chest_6');
    await expect(page.getByTestId('container-switch-note')).toHaveText('Nothing is flagged on Chest, 6 rows: every layer fits.');
  });

  test('is one undo step: the original line comes back, the document is clean, and nothing else moved', async ({ page }) => {
    await openShop(page);
    const before = await documentOf(page, SHOP_PATH);
    await switchTo(page, 'dispenser');
    await expect(page.locator('[data-status]')).toHaveText('Unsaved changes');
    await expect(page.getByRole('button', { name: 'Undo Switch to Dispenser' })).toBeEnabled();
    await page.locator('.stage').focus();
    await page.keyboard.press('Control+z');
    await expect(page.getByTestId('container-select')).toHaveValue('chest_3');
    await expect.poll(() => underlaySize(page)).toEqual([176, 168]);
    await expect(page.locator('[data-status]')).toHaveText('Saved'); // back to the loaded document
    await expect(page.getByRole('button', { name: /^Undo/ })).toBeDisabled(); // one step, not two
    expect(await writes(page)).toEqual([]);
    expect(await documentOf(page, SHOP_PATH)).toEqual(before);
    // redo puts the switch back
    await page.keyboard.press('Control+Shift+z');
    await expect(page.getByTestId('container-select')).toHaveValue('dispenser');
  });

  test('cancelling the staged choice changes nothing', async ({ page }) => {
    await openShop(page);
    await page.getByTestId('container-select').selectOption('hopper');
    await expect(page.getByTestId('container-switch-note')).toBeVisible();
    await page.getByTestId('container-switch-cancel').click();
    await expect(page.getByTestId('container-switch-note')).toHaveCount(0);
    await expect(page.getByTestId('container-select')).toHaveValue('chest_3');
    expect(await underlaySize(page)).toEqual([176, 168]);
    await expect(page.locator('[data-status]')).toHaveText('Saved');
  });

  test('saves only the container line: layers, comments and unknown keys are untouched, and a reload draws the hopper', async ({ page }) => {
    await openShop(page);
    const before = await documentOf(page, SHOP_PATH);
    await switchTo(page, 'hopper');
    await page.locator('.stage').focus();
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const recorded = await writes(page);
    expect(recorded).toHaveLength(1);
    const after = await documentOf(page, SHOP_PATH);
    const body = (parsed: Record<string, Record<string, unknown>> | undefined) => parsed!.main!;
    expect(body(after!.parsed).container).toBe('hopper');
    expect({ ...body(after!.parsed), container: 'chest_3' }).toEqual(body(before!.parsed));
    expect(Object.keys(body(after!.parsed))).toEqual(Object.keys(body(before!.parsed)));
    // the raw file differs from the original by the one line
    const lines = (raw: string) => raw.split('\n');
    const changed = lines(after!.raw).filter((line, index) => line !== lines(before!.raw)[index]);
    expect(changed).toEqual(['  container: hopper']);
  });

  test('without the server\'s check the line says the cost is unknown, and still changes no layer', async ({ page }) => {
    await openShop(page, 'gui');
    await page.getByTestId('container-select').selectOption('hopper');
    await expect(page.getByTestId('container-switch-note')).toContainText('check is not available');
    await page.getByTestId('container-switch-apply').click();
    await expect.poll(() => underlaySize(page)).toEqual([176, 133]);
    await expect(page.locator('.layers .row')).toHaveCount(9);
  });
});

test.describe('the starter', () => {
  test('a New GUI starts on chest_3, drawn and selectable like any container', async ({ page }) => {
    await page.goto('/browser/fixture.html?gui');
    await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
    await page.getByRole('button', { name: 'Content', exact: true }).click();
    await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
    await page.getByRole('button', { name: /new gui/i }).first().click();
    await page.getByRole('dialog').getByRole('textbox').first().fill('Fresh Screen');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('gui-art')).toBeVisible();
    await expect(page.getByTestId('container-select')).toHaveValue('chest_3');
    expect(await underlaySize(page)).toEqual([176, 168]);
    await switchTo(page, 'hopper');
    await expect.poll(() => underlaySize(page)).toEqual([176, 133]);
  });
});
