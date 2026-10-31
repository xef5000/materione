import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { stringify } from 'yaml';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The Logic tab (issue 223, logic.md section 7) on the deterministic `?logic` fixture: the sample shop
// screen with the logic capability and the server's own vocabulary (the recorded snapshot). The fixture's
// logic.edit runs the same edit code the canvas plans with, so a saved file is what the real server writes.
test.beforeEach(async ({ page }) => blockCdn(page));

async function openLogic(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html?logic');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
  await page.getByRole('tab', { name: 'Logic' }).click();
  await expect(page.getByTestId('logic-canvas')).toBeVisible();
}

const status = (page: Page): Locator => page.locator('[data-status]');
const hint = (page: Page): Locator => page.getByTestId('logic-hint');
const node = (page: Page, id: string): Locator => page.locator(`.node[data-node="${id}"]`);
const port = (page: Page, id: string, name: string): Locator => page.locator(`[data-node="${id}"][data-port="${name}"]`);

/** Drags from a node's output port to empty canvas (offset from the port) and releases there. */
async function dragOut(page: Page, from: Locator, dx = 150, dy = 150): Promise<void> {
  const box = (await from.boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx / 2, y + dy / 2, { steps: 4 });
  await page.mouse.move(x + dx, y + dy, { steps: 4 });
  await page.mouse.up();
}

async function pick(page: Page, entry: string): Promise<void> {
  await expect(page.getByTestId('logic-palette')).toBeVisible();
  await page.locator(`[data-entry="${entry}"]`).click();
  await expect(page.getByTestId('logic-palette')).toHaveCount(0);
}

/** open -> sound -> command -> close, built by dragging a wire out of the last port each time. */
async function buildFlow(page: Page): Promise<void> {
  await page.getByRole('button', { name: /add a trigger/i }).click();
  await pick(page, 'trigger:gui.open');
  await expect(node(page, 'behavior.0')).toBeVisible();

  await dragOut(page, port(page, 'behavior.0', 'out'));
  await pick(page, 'action:sound');
  await node(page, 'behavior.0.do.0').getByRole('textbox', { name: 'Play sound value' }).fill('block.chest.open');
  await page.keyboard.press('Enter');

  await dragOut(page, port(page, 'behavior.0.do.0', 'after'));
  await pick(page, 'action:run_command');
  await node(page, 'behavior.0.do.1').getByRole('textbox', { name: 'Run command value' }).fill('say shop opened');
  await page.keyboard.press('Enter');

  await dragOut(page, port(page, 'behavior.0.do.1', 'after'));
  await pick(page, 'action:gui.close');
}

const EXPECTED = `main:
  title: Shop
  rows: 3
  behavior:
    - on: gui.open
      do:
        - sound: block.chest.open
        - run_command: say shop opened
        - gui.close
`;

/** The saved GUI's behavior, as YAML, from the fixture's document. */
async function savedBehavior(page: Page): Promise<string> {
  const doc = await documentOf(page, 'guis/shop/main.yml');
  const { behavior, flows } = (doc!.parsed.main ?? {}) as Record<string, unknown>;
  return stringify({ main: { title: 'Shop', rows: 3, ...(behavior ? { behavior } : {}), ...(flows ? { flows } : {}) } });
}

test('build open, sound, command, close by dragging wires, then save: the file is the expected YAML', async ({ page }) => {
  await openLogic(page);
  await expect(page.getByTestId('logic-empty')).toBeVisible();
  await buildFlow(page);

  await expect(page.locator('.node')).toHaveCount(4);
  await expect(status(page)).toHaveText('Unsaved changes');
  expect(await documentOf(page, 'guis/shop/main.yml').then((doc) => doc!.parsed.main!.behavior)).toBeUndefined(); // nothing written until Save

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
  expect(await savedBehavior(page)).toBe(EXPECTED);

  // one pipeline: the layers went through setFields (nothing changed there), the logic through logic.edit
  const sent = (await writes(page)).filter((write) => write.op === 'logic.edit');
  expect(sent).toHaveLength(1);
  expect((sent[0]!.payload.edits as { op: string }[]).map((edit) => edit.op)).toEqual(['addRule', 'insertStep', 'patchStep', 'insertStep', 'patchStep', 'insertStep']);
  expect((await writes(page)).filter((write) => write.op === 'authoring.setFields')).toHaveLength(0);
});

test('nodes are coloured by registry category and wired in the source colour', async ({ page }) => {
  await openLogic(page);
  await buildFlow(page);
  const token = (name: string) => page.evaluate((n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim(), name);
  const colour = (id: string) => node(page, id).evaluate((element) => getComputedStyle(element).getPropertyValue('--c').trim());
  expect(await colour('behavior.0')).toBe(await token('--logic-trigger'));
  expect(await colour('behavior.0.do.0')).toBe(await token('--logic-player')); // Play sound
  expect(await colour('behavior.0.do.1')).toBe(await token('--logic-server')); // Run command
  expect(await colour('behavior.0.do.2')).toBe(await token('--logic-gui')); // Close GUI
  expect(new Set(await Promise.all(['behavior.0', 'behavior.0.do.0', 'behavior.0.do.1', 'behavior.0.do.2'].map(colour))).size).toBe(4);
  await expect(page.locator('[data-wire]')).toHaveCount(3);
});

test('a join, a loop and a second wire out of a port are refused with a one-line reason; nothing changes', async ({ page }) => {
  await openLogic(page);
  await buildFlow(page);
  const drag = async (from: Locator, to: Locator) => {
    const a = (await from.boundingBox())!;
    const b = (await to.boundingBox())!;
    await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
    await page.mouse.down();
    await page.mouse.move(b.x + b.width / 2, b.y + 20, { steps: 8 });
    await page.mouse.up();
  };

  await drag(port(page, 'behavior.0', 'out'), node(page, 'behavior.0.do.2').locator('header'));
  await expect(hint(page)).toHaveText("GUI opened 'do' already feeds Play sound; fan-out is not allowed.");

  await drag(port(page, 'behavior.0.do.0', 'after'), node(page, 'behavior.0.do.2').locator('header'));
  await expect(hint(page)).toContainText('already feeds');

  await drag(port(page, 'behavior.0.do.2', 'after'), node(page, 'behavior.0.do.0').locator('header'));
  await expect(hint(page)).toHaveText('That would make a loop: Play sound already leads to Close GUI.');

  await expect(page.locator('.node')).toHaveCount(4);
  await expect(page.locator('[data-wire]')).toHaveCount(3);
});

test('dragging a node moves only that node, saves node: {x, y}, and undo is shared with the Design tab', async ({ page }) => {
  await openLogic(page);
  await buildFlow(page);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');

  const head = node(page, 'behavior.0.do.1').locator('header');
  const world = (id: string) => node(page, id).evaluate((element) => [parseFloat((element as HTMLElement).style.left), parseFloat((element as HTMLElement).style.top)]);
  const before = await world('behavior.0.do.1');
  const neighbour = await world('behavior.0.do.0');
  const box = (await head.boundingBox())!;
  await page.mouse.move(box.x + 40, box.y + 8);
  await page.mouse.down();
  await page.mouse.move(box.x + 40 + 60, box.y + 8 + 80, { steps: 6 });
  await page.mouse.up();
  const after = await world('behavior.0.do.1');
  expect(after[0]).toBeGreaterThan(before[0]! + 20);
  expect(after[1]).toBeGreaterThan(before[1]! + 20);
  expect(await world('behavior.0.do.0')).toEqual(neighbour);
  await expect(status(page)).toHaveText('Unsaved changes');

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
  const saved = await documentOf(page, 'guis/shop/main.yml');
  const rule = (saved!.parsed.main!.behavior as { do: { node?: { x: number; y: number } }[] }[])[0]!;
  expect(rule.do[0]!.node).toBeUndefined();
  expect(rule.do[1]!.node).toBeDefined();
  expect(rule.do[1]!.node!.x % 4).toBe(0);
  const last = (await writes(page)).filter((write) => write.op === 'logic.edit').pop()!;
  expect(last.payload.edits).toEqual([{ op: 'setNodePosition', path: 'behavior.0.do.1', x: rule.do[1]!.node!.x, y: rule.do[1]!.node!.y }]);

  // Ctrl+Z from the Design tab takes the move back (one history), and saving puts the file back as it was
  await page.getByRole('tab', { name: 'Design' }).click();
  await page.getByTestId('gui-art').focus();
  await page.keyboard.press('Control+z');
  await expect(status(page)).toHaveText('Unsaved changes');
  await page.getByRole('tab', { name: 'Logic' }).click();
  await expect(node(page, 'behavior.0.do.1')).toBeVisible();
  expect(await world('behavior.0.do.1')).toEqual(before);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
  expect(await savedBehavior(page)).toBe(EXPECTED);
});

test('the palette comes from the vocabulary: grouped, searchable, an unavailable provider greyed with its reason', async ({ page }) => {
  await openLogic(page);
  await page.getByRole('button', { name: /add a trigger/i }).click();
  const palette = page.getByTestId('logic-palette');
  await expect(palette.getByRole('region', { name: 'Triggers' })).toBeVisible();
  await expect(palette.getByRole('region', { name: 'Economy' })).toBeVisible();
  // only the triggers a GUI fires
  await expect(palette.locator('[data-entry^="trigger:"]')).toHaveCount(6);

  await page.getByRole('textbox', { name: 'Search the palette' }).fill('money');
  const money = palette.locator('[data-entry="action:take_money"]');
  await expect(money).toHaveAttribute('aria-disabled', 'true');
  await expect(money).toContainText('vault integration is not installed');
  await expect(palette.locator('[data-entry="action:give_item"]')).toHaveCount(0);
  await page.keyboard.press('Escape');
  await expect(palette).toHaveCount(0);
});

test('the keyboard builds and edits: / adds, Enter on a port inserts, arrows walk, Delete removes, F frames', async ({ page }) => {
  await openLogic(page);
  await page.getByTestId('logic-canvas').focus();
  await page.keyboard.press('/');
  await page.getByRole('textbox', { name: 'Search the palette' }).fill('opened');
  await page.keyboard.press('Enter');
  await expect(node(page, 'behavior.0')).toBeVisible();

  await port(page, 'behavior.0', 'out').focus();
  await page.keyboard.press('Enter');
  await page.getByRole('textbox', { name: 'Search the palette' }).fill('close');
  await page.locator('[data-entry="action:gui.close"]').click();
  await expect(node(page, 'behavior.0.do.0')).toHaveAttribute('data-op', 'gui.close');

  await page.getByTestId('logic-canvas').focus();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await expect(page.getByTestId('logic-selection')).toContainText('Close GUI');
  await page.keyboard.press('f');
  await page.keyboard.press('Delete');
  await expect(node(page, 'behavior.0.do.0')).toHaveCount(0);
  await expect(page.getByTestId('logic-selection')).toContainText('GUI opened');
  await page.keyboard.press('Control+z');
  await expect(node(page, 'behavior.0.do.0')).toHaveCount(1);
});

test('undoing a change before saving leaves nothing to send', async ({ page }) => {
  await openLogic(page);
  await page.getByRole('button', { name: /add a trigger/i }).click();
  await pick(page, 'trigger:gui.open');
  await page.keyboard.press('Control+z');
  await expect(status(page)).toHaveText('Saved');
  await expect(page.getByTestId('logic-empty')).toBeVisible();
});

test('a narrow window does not scroll the page sideways', async ({ page }) => {
  await page.setViewportSize({ width: 560, height: 800 });
  await openLogic(page);
  await buildFlow(page).catch(() => undefined);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
