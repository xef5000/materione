import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn, openModules, openResource, selectModule, settingsPanel } from './helpers/modules';

// The studio body is one centered group with width caps (--studio-* tokens in app.css):
// on a wide window the Appearance card, the module rows, the Add module bar and the
// settings panel keep the width they need, sit next to each other, and leave empty
// background on both sides instead of anchoring to the window edges.
test.beforeEach(async ({ page }) => blockCdn(page));

// Mirrors the tokens: --studio-column-max 800, --studio-panel-width 320, gap 20.
const COLUMN_MAX = 800;
const PANEL_MAX = 320;

type Box = { x: number; y: number; width: number; height: number };

async function boxOf(locator: Locator): Promise<Box> {
  const box = await locator.boundingBox();
  expect(box, 'element has a layout box').not.toBeNull();
  return box!;
}

async function openRubySword(page: Page): Promise<void> {
  await openModules(page);
  await openResource(page, 'Items', 'Ruby Sword');
  await selectModule(page, 'Durability');
  await expect(settingsPanel(page).getByRole('spinbutton')).toBeVisible();
}

async function measure(page: Page) {
  const body = await boxOf(page.locator('section.studio .body'));
  const appearance = await boxOf(page.getByRole('article', { name: 'Appearance' }));
  const rows = await boxOf(page.getByRole('list', { name: 'Modules' }).locator('li').first());
  const add = await boxOf(page.getByRole('button', { name: 'Add module' }));
  const panel = await boxOf(settingsPanel(page));
  const render = await boxOf(page.getByRole('article', { name: 'Appearance' }).locator('.canvas'));
  const tooltip = await boxOf(page.getByLabel('Item tooltip'));
  return { body, appearance, rows, add, panel, render, tooltip };
}

test('at a wide window the studio is a centered group with empty space on both sides', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await openRubySword(page);
  const { body, appearance, rows, add, panel } = await measure(page);

  // every component stays within its cap, however wide the window is
  expect(appearance.width).toBeLessThanOrEqual(COLUMN_MAX + 1);
  expect(rows.width).toBeLessThanOrEqual(COLUMN_MAX + 1);
  expect(add.width).toBeLessThanOrEqual(COLUMN_MAX + 1);
  expect(panel.width).toBeLessThanOrEqual(PANEL_MAX + 1);
  // the rows and the Add module bar match the Appearance card's column
  expect(Math.abs(rows.width - appearance.width)).toBeLessThanOrEqual(1);
  expect(Math.abs(add.width - appearance.width)).toBeLessThanOrEqual(1);

  // the group is centered: the left and right margins are empty and equal
  const left = appearance.x - body.x;
  const right = body.x + body.width - (panel.x + panel.width);
  expect(left).toBeGreaterThan(120);
  expect(right).toBeGreaterThan(120);
  expect(Math.abs(left - right)).toBeLessThanOrEqual(24);

  // the panel sits right next to the column, not at the window edge
  const gap = panel.x - (appearance.x + appearance.width);
  expect(gap).toBeGreaterThanOrEqual(0);
  expect(gap).toBeLessThanOrEqual(40);
});

test('the tooltip sits next to the render and is as wide as its text needs', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await openRubySword(page);
  const { appearance, render, tooltip } = await measure(page);

  expect(render.width).toBeGreaterThanOrEqual(220);
  expect(render.width).toBeLessThanOrEqual(441);
  expect(tooltip.x - (render.x + render.width)).toBeLessThanOrEqual(40);
  expect(tooltip.width).toBeLessThanOrEqual(361);
  // the card keeps room around them instead of flinging the tooltip to its far edge
  expect(tooltip.x + tooltip.width).toBeLessThan(appearance.x + appearance.width - 16);
});

for (const width of [1440, 1365]) {
  test(`a ${width}px window fills the group without clipping or overflow`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await openRubySword(page);
    const { body, appearance, panel } = await measure(page);
    // everything is inside the body, with the panel beside the column
    expect(appearance.x).toBeGreaterThanOrEqual(body.x);
    expect(panel.x + panel.width).toBeLessThanOrEqual(body.x + body.width + 1);
    expect(panel.x).toBeGreaterThan(appearance.x + appearance.width);
    expect(panel.width).toBeGreaterThanOrEqual(300);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
}

test('below 1100px the panel stacks under the list, still capped and centered', async ({ page }) => {
  await page.setViewportSize({ width: 1000, height: 900 });
  await openRubySword(page);
  const { appearance, panel } = await measure(page);
  expect(panel.x).toBeLessThan(appearance.x + appearance.width);
  expect(panel.y).toBeGreaterThan(appearance.y + appearance.height);
  expect(panel.width).toBeLessThanOrEqual(COLUMN_MAX + 1);
});

test('blocks share the centered layout: preview, list and panel column stay within the caps', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await openModules(page);
  await openResource(page, 'Blocks', 'Amethyst Lamp');
  await selectModule(page, 'Shape');
  const body = await boxOf(page.locator('section.studio .body'));
  const preview = await boxOf(page.locator('section.studio aside.preview'));
  const display = await boxOf(page.getByRole('article', { name: 'Display' }));
  const panel = await boxOf(settingsPanel(page));
  expect(display.width).toBeLessThanOrEqual(COLUMN_MAX + 1);
  expect(panel.width).toBeLessThanOrEqual(PANEL_MAX + 1);
  const left = preview.x - body.x;
  const right = body.x + body.width - (panel.x + panel.width);
  expect(left).toBeGreaterThan(80);
  expect(right).toBeGreaterThan(80);
  expect(Math.abs(left - right)).toBeLessThanOrEqual(24);
});
