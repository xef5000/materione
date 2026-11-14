import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import '../contract';
import { decodePng } from '../../test/helpers/png';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The open canvas in the GUI studio (gui.md section 11, issue 277): the owner's vanilla chest under every GUI, the
// player's inventory under a veil that lifts per painted pixel, painting anywhere within the art cap, paint layers that
// own their box, the guides for the player's window, and clickable layers outside the chest marked decoration only.
test.beforeEach(async ({ page }) => blockCdn(page));

const SHEET = decodePng(new Uint8Array(readFileSync(fileURLToPath(new URL('../../static/vanilla/generic_54.png', import.meta.url)))));
/** The sheet's pixel as RGBA, the way the browser's 2D context reports it. */
function sheetRgba(x: number, y: number): number[] {
  const argb = SHEET.get(x, y);
  return [(argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, argb >>> 24];
}

/** The veil over the player's inventory: translucent grey (0x303030 at 160/255) over the vanilla pixel, per channel, rounding aside. */
function veiled(rgba: number[]): number[] {
  return [0, 1, 2].map((c) => Math.round((rgba[c]! * (255 - 160) + 0x30 * 160) / 255)).concat(rgba[3]!);
}

/**
 * What the client draws for a screen of `rows` (gui.md section 11.2): the top rows*18+17 rows of the sheet, then 96 rows from
 * v = 126; the last row of the screen is empty.
 */
function vanillaRgba(rows: number, x: number, y: number): number[] {
  const top = rows * 18 + 17;
  if (y < top) return sheetRgba(x, y);
  if (y < top + 96) return sheetRgba(x, 126 + (y - top));
  return [0, 0, 0, 0];
}

async function openShop(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?gui');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
  await expect(page.locator('[data-status]')).toHaveText('Saved');
}

/** The shop with every layer deleted: nothing but the vanilla chest, so what the canvas shows is the underlay alone. */
async function openEmpty(page: Page, rows = 6): Promise<void> {
  await openShop(page);
  await page.locator('.stage').focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.press('Delete');
  await expect(page.locator('.layers .row')).toHaveCount(0);
  if (rows !== 3) {
    await page.getByTestId('container-select').selectOption(`chest_${rows}`);
    await page.getByTestId('container-switch-apply').click();
  }
  await expect.poll(() => underlaySize(page)).toEqual([176, rows * 18 + 114]);
}

function underlaySize(page: Page): Promise<number[]> {
  return page.getByTestId('gui-underlay').evaluate((element: HTMLCanvasElement) => [element.width, element.height]);
}

/** The whole vanilla underlay as the browser paints it: RGBA, row-major. */
function underlay(page: Page): Promise<number[]> {
  return page.getByTestId('gui-underlay').evaluate((element: HTMLCanvasElement) =>
    [...element.getContext('2d')!.getImageData(0, 0, element.width, element.height).data]);
}

async function underlayPixel(page: Page, x: number, y: number): Promise<number[]> {
  return page.getByTestId('gui-underlay').evaluate((element: HTMLCanvasElement, [px, py]) =>
    [...element.getContext('2d')!.getImageData(px!, py!, 1, 1).data], [x, y]);
}

/** A pixel of the art (GUI pixels; the art canvas starts at the cap's corner, -424, -243). */
function art(page: Page, x: number, y: number): Promise<number[]> {
  return page.getByTestId('gui-art').evaluate((element: HTMLCanvasElement, [px, py]) =>
    [...element.getContext('2d')!.getImageData(px! + 424, py! + 243, 1, 1).data], [x, y]);
}

/**
 * The vanilla chest's box once the layout has stopped moving. Selecting a tool changes the toolbar's hint line, which
 * resizes the stage, and the studio re-centres the view from a ResizeObserver a frame later: a box read in between is
 * stale by many GUI pixels (issue 365). Two consecutive frames with the same box is the real "settled" signal.
 */
async function settledBox(page: Page): Promise<{ x: number; y: number; width: number }> {
  return page.getByTestId('gui-underlay').evaluate((element) => new Promise<{ x: number; y: number; width: number }>((resolve) => {
    let last = '';
    const frame = (): void => {
      const r = element.getBoundingClientRect();
      const now = `${r.x},${r.y},${r.width}`;
      if (now === last) return resolve({ x: r.x, y: r.y, width: r.width });
      last = now;
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }));
}

/** Screen position of a GUI pixel, on the screen or around it, from the vanilla chest's live box. */
async function at(page: Page, x: number, y: number): Promise<[number, number]> {
  const box = await settledBox(page);
  const zoom = box.width / 176;
  return [box.x + x * zoom + zoom / 2, box.y + y * zoom + zoom / 2];
}

/** Pans (space-drag) until a GUI pixel is well inside the stage, so the pointer can reach it. */
async function reveal(page: Page, x: number, y: number): Promise<void> {
  const stage = (await page.locator('.stage').boundingBox())!;
  const [px, py] = await at(page, x, y);
  const margin = 40;
  if (px > stage.x + margin && px < stage.x + stage.width - margin && py > stage.y + margin && py < stage.y + stage.height - margin) return;
  const cx = stage.x + stage.width / 2;
  const cy = stage.y + stage.height / 2;
  await page.locator('.stage').focus();
  await page.keyboard.down(' ');
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx + (cx - px), cy + (cy - py), { steps: 5 });
  await page.mouse.up();
  await page.keyboard.up(' ');
}

async function click(page: Page, x: number, y: number): Promise<void> {
  await reveal(page, x, y);
  const [sx, sy] = await at(page, x, y);
  await page.mouse.click(sx, sy);
}

async function dragTo(page: Page, from: [number, number], to: [number, number]): Promise<void> {
  await reveal(page, from[0], from[1]);
  const [x0, y0] = await at(page, ...from);
  const [x1, y1] = await at(page, ...to);
  await page.mouse.move(x0, y0);
  await page.mouse.down();
  await page.mouse.move(x1, y1, { steps: 6 });
  await page.mouse.up();
}

async function chooseColour(page: Page, value: string): Promise<void> {
  const hex = page.getByRole('textbox', { name: 'Paint colour hex' });
  await hex.fill(value);
  await hex.press('Enter');
}

const RED = [255, 0, 0, 255];

async function brush(page: Page, colour = '#FF0000'): Promise<void> {
  await page.getByRole('button', { name: 'Brush', exact: true }).click();
  await chooseColour(page, colour);
  await page.keyboard.press('1'); // 100%: one GUI pixel is one screen pixel, so a click paints exactly one pixel
}

function pngSize(base64: string): [number, number] {
  const bytes = Buffer.from(base64, 'base64');
  return [bytes.readUInt32BE(16), bytes.readUInt32BE(20)];
}

test.describe('the vanilla underlay', () => {
  for (const rows of [1, 6]) {
    test(`${rows} row${rows === 1 ? '' : 's'}: composed from the owner's sheet as the client blits it, pixel for pixel (veil lifted by painting the screen)`, async ({ page }) => {
      await openEmpty(page, rows);
      const height = rows * 18 + 114;
      const top = rows * 18 + 17;

      // as it opens: the container part is the sheet, the inventory part is the sheet under the veil (same shape)
      const shown = await underlay(page);
      const wrong: string[] = []; // collected, so a mismatch is one readable failure and not 40,000 assertions
      for (let y = 0; y < height; y++) {
        for (let x = 0; x < 176; x++) {
          const got = shown.slice((y * 176 + x) * 4, (y * 176 + x) * 4 + 4);
          const want = y < top || vanillaRgba(rows, x, y)[3] === 0 ? vanillaRgba(rows, x, y) : veiled(vanillaRgba(rows, x, y));
          const tolerance = y < top ? 0 : 1; // the veil is a blend: rounding aside
          if (got.some((value, c) => Math.abs(value - want[c]!) > tolerance)) wrong.push(`${x},${y}: got ${got} want ${want}`);
        }
      }
      expect(wrong.slice(0, 5)).toEqual([]);
      expect(shown.slice(((top + 20) * 176 + 88) * 4, ((top + 20) * 176 + 88) * 4 + 4)).not.toEqual(vanillaRgba(rows, 88, top + 20));

      // paint the whole screen: nothing is veiled any more, and the underlay is exactly what the client draws
      await page.getByRole('button', { name: 'Fill', exact: true }).click();
      await chooseColour(page, '#FF0000');
      await click(page, 88, 10);
      const lifted = await underlay(page);
      const stillVeiled: string[] = [];
      for (let y = 0; y < height; y++) {
        for (let x = 0; x < 176; x++) {
          const got = lifted.slice((y * 176 + x) * 4, (y * 176 + x) * 4 + 4);
          const want = vanillaRgba(rows, x, y);
          if (got.some((value, c) => value !== want[c])) stillVeiled.push(`${x},${y}: got ${got} want ${want}`);
        }
      }
      expect(stillVeiled.slice(0, 5)).toEqual([]);
    });
  }

  test('is not a layer: it is not listed, not in the history, and saving a GUI with no layer writes no PNG', async ({ page }) => {
    await openEmpty(page, 3);
    await expect(page.locator('.layers .row')).toHaveCount(0);
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const sent = (await writes(page)).filter((entry) => entry.op === 'authoring.setFields');
    expect(sent).toHaveLength(1);
    const payload = sent[0]!.payload as { edits: { field: string[]; value: unknown[] }[]; assets?: unknown[] };
    expect(payload.edits.find((edit) => edit.field.at(-1) === 'layers')!.value).toEqual([]);
    expect(payload.assets ?? []).toEqual([]);
    // and undo has nothing of the chest to take back
    await expect(page.getByRole('button', { name: 'Undo Delete layers' })).toBeEnabled(); // the delete is in the history, the chest is not
  });
});

test.describe('the inventory veil', () => {
  test('lifts exactly under painted pixels: the vanilla pixels around a painted one stay veiled, the container is never veiled', async ({ page }) => {
    await openEmpty(page, 6);
    await brush(page);
    const painted: [number, number][] = [[40, 140], [41, 140], [40, 141]];
    for (const [x, y] of painted) await click(page, x, y);

    for (const [x, y] of painted) {
      expect(await underlayPixel(page, x, y), `lifted ${x},${y}`).toEqual(vanillaRgba(6, x, y));
      expect(await art(page, x, y)).toEqual(RED);
    }
    for (const [x, y] of [[42, 140], [41, 141], [39, 140], [40, 142]] as const) {
      const got = await underlayPixel(page, x, y);
      const raw = vanillaRgba(6, x, y);
      expect(got, `veiled ${x},${y}`).not.toEqual(raw);
      for (let c = 0; c < 4; c++) expect(Math.abs(got[c]! - veiled(raw)[c]!)).toBeLessThanOrEqual(1);
    }
    // painting in the container changes nothing underneath
    await click(page, 60, 50);
    expect(await underlayPixel(page, 60, 50)).toEqual(vanillaRgba(6, 60, 50));
    // undo puts the veil back, redo lifts it again, pixel by pixel
    for (let i = 0; i < 4; i++) await page.keyboard.press('Control+z');
    expect(await underlayPixel(page, 41, 140)).not.toEqual(vanillaRgba(6, 41, 140));
    await page.keyboard.press('Control+Shift+z');
    await page.keyboard.press('Control+Shift+z');
    expect(await underlayPixel(page, 41, 140)).toEqual(vanillaRgba(6, 41, 140));
    expect(await underlayPixel(page, 40, 141)).not.toEqual(vanillaRgba(6, 40, 141));
  });
});

test.describe('painting outside the screen', () => {
  test('a stroke outside the chest paints there, grows the layer in one undo step, and the inspector shows the box', async ({ page }) => {
    await openEmpty(page, 3);
    await brush(page);
    await click(page, -100, 50);
    expect(await art(page, -100, 50)).toEqual(RED);
    await expect(page.locator('.layers .row.selected .name')).toHaveText('Paint');
    await expect(page.getByTestId('paint-size')).toContainText('1 x 1 px');
    await expect(page.getByRole('textbox', { name: 'X', exact: true })).toHaveValue('-100');
    await expect(page.getByRole('textbox', { name: 'Y', exact: true })).toHaveValue('50');

    await click(page, 300, 400);
    expect(await art(page, 300, 400)).toEqual(RED);
    await expect(page.getByTestId('paint-size')).toContainText('401 x 351 px');
    await expect(page.getByRole('textbox', { name: 'X', exact: true })).toHaveValue('-100');

    // the growth and the stroke are one step
    await page.keyboard.press('Control+z');
    expect(await art(page, 300, 400)).toEqual([0, 0, 0, 0]);
    await expect(page.getByTestId('paint-size')).toContainText('1 x 1 px');
    await page.keyboard.press('Control+Shift+z');
    await expect(page.getByTestId('paint-size')).toContainText('401 x 351 px');
    // the vanilla chest underneath is untouched by art that is not on it
    expect(await underlayPixel(page, 10, 10)).toEqual(vanillaRgba(3, 10, 10));
  });

  test('is clipped to the art cap: x -424..599, y -243..780', async ({ page }) => {
    await openEmpty(page, 3);
    await brush(page);
    await dragTo(page, [590, 100], [620, 100]);
    expect(await art(page, 599, 100)).toEqual(RED);
    await expect(page.getByTestId('paint-size')).toContainText('10 x 1 px'); // 590..599
    // a stroke that starts outside the cap paints nothing and says why
    await click(page, 640, 100);
    await expect(page.getByTestId('gui-hint')).toContainText('outside the art cap');
    await expect(page.getByTestId('paint-size')).toContainText('10 x 1 px');
    // the other three edges
    await click(page, -424, -243);
    expect(await art(page, -424, -243)).toEqual(RED);
    await expect(page.getByTestId('paint-size')).toContainText('1024 x 344 px'); // x -424..599, y -243..100
    await click(page, 599, 780);
    await expect(page.getByTestId('paint-size')).toContainText('1024 x 1024 px');
    await click(page, 0, -244);
    await expect(page.getByTestId('gui-hint')).toContainText('outside the art cap');
  });
});

test.describe('paint layers own their box', () => {
  test('are trimmed to the painted pixels on save: the PNG holds no margin and the layer says where it is', async ({ page }) => {
    await openEmpty(page, 3);
    await brush(page);
    await click(page, -100, 50);
    await click(page, 300, 400);
    await page.getByRole('button', { name: 'Eraser', exact: true }).click();
    await click(page, -100, 50); // erased: the box does not shrink while editing, the save trims it
    await expect(page.getByTestId('paint-size')).toContainText('401 x 351 px');

    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const sent = (await writes(page)).filter((entry) => entry.op === 'authoring.setFields');
    expect(sent).toHaveLength(1);
    const payload = sent[0]!.payload as { edits: { field: string[]; value: Record<string, unknown>[] }[]; assets: { path: string; data: string }[] };
    const layers = payload.edits[0]!.value;
    expect(layers).toHaveLength(1);
    expect(layers[0]).toMatchObject({ type: 'paint', image: 'gui/shop/main/paint', x: 300, y: 400, width: 1, height: 1 });
    expect(payload.assets.map((asset) => asset.path)).toEqual(['assets/textures/gui/shop/main/paint.png']);
    expect(pngSize(payload.assets[0]!.data)).toEqual([1, 1]);
    // the file on disk says the same
    const saved = (await documentOf(page, 'guis/shop/main.yml'))!.parsed.main as { layers: Record<string, unknown>[] };
    expect(saved.layers[0]).toMatchObject({ x: 300, y: 400, width: 1, height: 1 });
    // the studio holds the trimmed layer now, and the picture did not change
    await expect(page.getByTestId('paint-size')).toContainText('1 x 1 px');
    expect(await art(page, 300, 400)).toEqual(RED);
    expect(await art(page, -100, 50)).toEqual([0, 0, 0, 0]);
    await expect(page.locator('[data-status]')).toHaveText('Saved');
  });

  test('a layer with no painted pixel saves no PNG and is written with width 0 and height 0', async ({ page }) => {
    await openEmpty(page, 3);
    await brush(page);
    await click(page, 20, 30);
    await page.getByRole('button', { name: 'Eraser', exact: true }).click();
    await click(page, 20, 30);
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const sent = (await writes(page)).filter((entry) => entry.op === 'authoring.setFields');
    const payload = sent[0]!.payload as { edits: { value: Record<string, unknown>[] }[]; assets?: unknown[] };
    expect(payload.edits[0]!.value[0]).toMatchObject({ type: 'paint', width: 0, height: 0 });
    expect(payload.assets ?? []).toEqual([]);
    await expect(page.getByTestId('paint-extras')).toContainText('No pixels yet');
  });

  test('a canvas-sized layer from before the open canvas still opens and is trimmed by the next save', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Header art', exact: true }).click();
    // opened as it was written: no box in the file, the PNG is the whole screen
    await expect(page.getByTestId('paint-size')).toContainText('176 x 168 px');
    await page.getByRole('button', { name: 'Hide Heading' }).click(); // any edit makes a save possible
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const payload = (await writes(page)).filter((entry) => entry.op === 'authoring.setFields')[0]!.payload as {
      edits: { value: Record<string, unknown>[] }[]; assets: { path: string; data: string }[];
    };
    expect(payload.edits[0]!.value.find((layer) => layer.id === 'art')).toMatchObject({ x: 6, y: 3, width: 162, height: 77 });
    expect(pngSize(payload.assets.find((asset) => asset.path.endsWith('art.png'))!.data)).toEqual([162, 77]);
    await expect(page.getByTestId('paint-size')).toContainText('162 x 77 px');
  });

  test('a save of the title alone never rewrites art: no layers field, no PNG', async ({ page }) => {
    await openShop(page);
    await page.locator('.stage').focus();
    await page.keyboard.press('Escape');
    await page.getByPlaceholder('Shown to screen readers').fill('Shop, renamed');
    await page.keyboard.press('Control+s');
    await expect(page.locator('[data-status]')).toHaveText('Saved');
    const payload = (await writes(page)).filter((entry) => entry.op === 'authoring.setFields')[0]!.payload as {
      edits: { field: string[] }[]; assets?: unknown[];
    };
    expect(payload.edits.map((edit) => edit.field)).toEqual([['main', 'title']]);
    expect(payload.assets ?? []).toEqual([]);
  });

  test('moves with x and y like any layer, but is never dragged to a size', async ({ page }) => {
    await openEmpty(page, 3);
    await brush(page);
    await click(page, 20, 30);
    await expect(page.locator('[data-handle]')).toHaveCount(0);
    const x = page.getByRole('textbox', { name: 'X', exact: true });
    await x.fill('120');
    await x.press('Enter');
    expect(await art(page, 120, 30)).toEqual(RED);
    expect(await art(page, 20, 30)).toEqual([0, 0, 0, 0]);
    await expect(page.getByRole('textbox', { name: 'W', exact: true })).toHaveCount(0);
  });
});

test.describe('the eyedropper', () => {
  test('takes what Materione draws first, else the vanilla colour under it (never the veiled one)', async ({ page }) => {
    await openEmpty(page, 6);
    const hex = page.getByRole('textbox', { name: 'Paint colour hex' });
    const toHex = (rgba: number[]): string => '#' + rgba.slice(0, 3).map((value) => value.toString(16).padStart(2, '0')).join('').toUpperCase();

    await page.getByRole('button', { name: 'Eyedropper', exact: true }).click();
    await click(page, 88, 10); // the container's frame
    await expect(hex).toHaveValue(toHex(sheetRgba(88, 10)));

    await page.getByRole('button', { name: 'Eyedropper', exact: true }).click();
    await click(page, 90, 140); // the player's inventory, veiled on screen: the pick is the vanilla colour
    await expect(hex).toHaveValue(toHex(vanillaRgba(6, 90, 140)));

    await brush(page, '#123456');
    await click(page, 90, 140);
    await page.getByRole('button', { name: 'Eyedropper', exact: true }).click();
    await chooseColour(page, '#FFFFFF');
    await click(page, 90, 140); // painted: custom pixels first
    await expect(hex).toHaveValue('#123456');

    await page.getByRole('button', { name: 'Eyedropper', exact: true }).click();
    await click(page, 400, 400); // open space: nothing there
    await expect(page.getByTestId('gui-hint')).toContainText('transparent');
  });
});

test.describe('window guides', () => {
  test('toggle like the slot grid and show what a 1920 x 1080 window shows at GUI scales 2, 3 and 4', async ({ page }) => {
    await openShop(page); // 3 rows: the screen is 176 x 168
    const guides = page.getByTestId('gui-window-guide');
    await expect(guides).toHaveCount(0);
    await page.getByRole('button', { name: 'Window guides', exact: true }).click();
    await expect(guides).toHaveCount(3);
    await expect(page.getByRole('button', { name: 'Window guides', exact: true })).toHaveAttribute('aria-pressed', 'true');

    const screen = (await page.getByTestId('gui-underlay').boundingBox())!;
    const zoom = screen.width / 176;
    // [scale, window width, window height in GUI pixels]: 3 rows is 168 tall, so the margins are floor((area - size) / 2)
    const expected = [[2, 960, 540, -392, -186], [3, 640, 360, -232, -96], [4, 480, 270, -152, -51]] as const;
    for (const [scale, w, h, left, top] of expected) {
      const rect = guides.locator(`xpath=self::*[@data-scale="${scale}"]`).locator('rect');
      const box = await rect.evaluate((element) => {
        const r = element.getBoundingClientRect();
        return { x: r.x, y: r.y, w: r.width, h: r.height };
      });
      expect(Math.abs(box.w / zoom - w), `scale ${scale} width`).toBeLessThan(1.5);
      expect(Math.abs(box.h / zoom - h), `scale ${scale} height`).toBeLessThan(1.5);
      expect(Math.abs((box.x - screen.x) / zoom - left), `scale ${scale} left`).toBeLessThan(1.5);
      expect(Math.abs((box.y - screen.y) / zoom - top), `scale ${scale} top`).toBeLessThan(1.5);
    }
    await page.getByRole('button', { name: 'Window guides', exact: true }).click();
    await expect(guides).toHaveCount(0);
  });

  test('the art cap is drawn, and the screen outline sits inside it', async ({ page }) => {
    await openShop(page);
    const cap = (await page.getByTestId('gui-cap').boundingBox())!;
    const screen = (await page.getByTestId('gui-screen-edge').boundingBox())!;
    const zoom = screen.width / 177; // the outline is one pixel wider than the screen
    expect(Math.abs(cap.width / zoom - 1025)).toBeLessThan(2);
    expect(Math.abs((screen.x - cap.x) / zoom - 423.5)).toBeLessThan(2);
    expect(Math.abs((screen.y - cap.y) / zoom - 242.5)).toBeLessThan(2);
  });
});

test.describe('clickable layers outside the chest are decoration only', () => {
  const note = (page: Page) => page.getByTestId('decoration-note');
  const badge = (page: Page, name: string) => page.locator('.layers .row', { hasText: name }).getByTestId('layer-decoration');

  test('a button placed outside the screen says so, with the reason, in the layers panel and the inspector', async ({ page }) => {
    await openShop(page);
    await expect(badge(page, 'Buy button')).toHaveCount(0);
    await page.getByRole('button', { name: 'Buy button', exact: true }).click();
    await expect(note(page)).toHaveCount(0);

    const x = page.getByRole('textbox', { name: 'X', exact: true });
    await x.fill('220');
    await x.press('Enter');
    await expect(badge(page, 'Buy button')).toHaveAttribute('aria-label', /Decoration only.*clicks outside the screen arrive as click_outside/);
    await expect(note(page)).toContainText('click_outside');
    await expect(note(page)).toContainText('Decoration only');

    await x.fill('150'); // 150 + 48 reaches past the screen's 176
    await x.press('Enter');
    await expect(badge(page, 'Buy button')).toHaveAttribute('aria-label', /Partly decoration only/);

    // on the screen but over no container slot (the player's inventory) is the compiler's MTR-0092 case: decoration too
    const y = page.getByRole('textbox', { name: 'Y', exact: true });
    await x.fill('118');
    await x.press('Enter');
    await y.fill('100');
    await y.press('Enter');
    await expect(badge(page, 'Buy button')).toHaveAttribute('aria-label', /covers no container slot/);
    await expect(note(page)).toContainText('covers no container slot');

    await y.fill('50');
    await y.press('Enter');
    await expect(badge(page, 'Buy button')).toHaveCount(0);
    await expect(note(page)).toHaveCount(0);
  });

  test('a layer nothing clicks is just art: no badge outside the screen', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Coin', exact: true }).click();
    const x = page.getByRole('textbox', { name: 'X', exact: true });
    await x.fill('300');
    await x.press('Enter');
    await expect(badge(page, 'Coin')).toHaveCount(0);
    await expect(note(page)).toHaveCount(0);
  });
});

test.describe('selecting in the open space', () => {
  test('dragging empty space outside the screen marquee-selects what it touches', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Coin', exact: true }).click();
    const x = page.getByRole('textbox', { name: 'X', exact: true });
    await x.fill('300');
    await x.press('Enter');
    await page.getByRole('button', { name: 'Buy button', exact: true }).click();
    await x.fill('-200');
    await x.press('Enter');
    await page.keyboard.press('Escape');
    await page.keyboard.press('1');

    await dragTo(page, [280, 10], [350, 100]); // starts on nothing at all
    const selected = page.getByRole('region', { name: 'Layers' }).locator('.row.selected .name');
    await expect(selected).toHaveText(['Coin']);

    await dragTo(page, [-260, 40], [-140, 90]);
    await expect(selected).toHaveText(['Buy button']);

    // a drag that touches nothing selects nothing
    await dragTo(page, [200, 200], [260, 260]);
    await expect(selected).toHaveCount(0);
  });

  test('a layer outside the screen is hit where it is: clicking it selects it, dragging it moves it', async ({ page }) => {
    await openShop(page);
    await page.getByRole('button', { name: 'Coin', exact: true }).click();
    const x = page.getByRole('textbox', { name: 'X', exact: true });
    await x.fill('300');
    await x.press('Enter');
    await page.keyboard.press('Escape');
    await page.keyboard.press('1');
    await click(page, 310, 40);
    await expect(page.getByRole('region', { name: 'Layers' }).locator('.row.selected .name')).toHaveText(['Coin']);
    await dragTo(page, [310, 40], [330, 60]);
    await expect(x).toHaveValue('320');
  });
});
