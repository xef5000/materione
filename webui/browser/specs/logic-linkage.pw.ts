import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The Design/Logic linkage (issue 225, logic.md section 7.4) on the deterministic `?logic` fixture: the sample shop screen
// with the logic capability and the server's recorded vocabulary. `seed=linkage` gives the screen three rules: the buy
// button's, the sword's (whose step the compiler flags) and one shared by the apple and the coin.
test.beforeEach(async ({ page }) => blockCdn(page));

async function openStudio(page: Page, query = 'logic&seed=linkage'): Promise<void> {
  await page.goto(`/browser/fixture.html?${query}`);
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
}

const status = (page: Page): Locator => page.locator('[data-status]');
const node = (page: Page, id: string): Locator => page.locator(`.node[data-node="${id}"]`);
const layerRow = (page: Page, name: string): Locator => page.locator('section[aria-label="Layers"] li.row').filter({ hasText: name });
const tab = (page: Page, name: string): Locator => page.getByRole('tab', { name });

async function selectLayer(page: Page, name: string): Promise<void> {
  await layerRow(page, name).getByRole('button', { name, exact: true }).click();
}

/** The saved GUI's logic and layers, as the fixture holds them. */
async function saved(page: Page): Promise<{ behavior?: unknown; flows?: unknown; open?: unknown; layers: { id: string }[] }> {
  const doc = await documentOf(page, 'guis/shop/main.yml');
  return (doc!.parsed.main ?? {}) as { behavior?: unknown; flows?: unknown; open?: unknown; layers: { id: string }[] };
}

async function save(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
}

test.describe('Add click logic', () => {
  test('a selected button offers it: a gui.click rule filtered to the layer, the Logic tab, its trigger selected, one save, shared undo', async ({ page }) => {
    await openStudio(page, 'logic');
    await expect(page.getByTestId('add-click-logic')).toHaveCount(0); // nothing selected
    await selectLayer(page, 'Frame');
    await expect(page.getByTestId('add-click-logic')).toHaveCount(0); // a panel is not clicked
    await selectLayer(page, 'Buy button');
    await page.getByTestId('add-click-logic').click();

    await expect(tab(page, 'Logic')).toHaveAttribute('aria-selected', 'true');
    await expect(node(page, 'behavior.0')).toHaveAttribute('aria-selected', 'true');
    await expect(node(page, 'behavior.0')).toHaveAttribute('data-layer', 'buy');
    await expect(node(page, 'behavior.0')).toContainText('Buy button'); // the layer's name
    await expect(node(page, 'behavior.0').getByTestId('layer-thumb')).toBeVisible(); // and its thumbnail
    await expect(status(page)).toHaveText('Unsaved changes');

    // Ctrl+Z from the Logic tab undoes it: one history across the tabs
    await page.getByTestId('logic-canvas').focus();
    await page.keyboard.press('Control+z');
    await expect(page.locator('.node')).toHaveCount(0);
    await page.keyboard.press('Control+Shift+z');
    await expect(node(page, 'behavior.0')).toBeVisible();

    await save(page);
    expect((await saved(page)).behavior).toEqual([{ on: 'gui.click', layer: 'buy', do: [] }]);
    // one pipeline: the rule travelled as logic.edit, the layers were not rewritten
    const sent = await writes(page);
    expect(sent.filter((write) => write.op === 'logic.edit').flatMap((write) => (write.payload.edits as { op: string }[]).map((edit) => edit.op))).toEqual(['addRule']);
    expect(sent.filter((write) => write.op === 'authoring.setFields')).toHaveLength(0);
  });

  test('items are offered it too, and a layer that has a rule says so and opens it instead', async ({ page }) => {
    await openStudio(page);
    await selectLayer(page, 'Sword');
    await expect(page.getByTestId('layer-logic-count')).toHaveText('1 rule answers clicks on this layer.');
    await page.getByTestId('add-click-logic').click();
    await expect(node(page, 'behavior.3')).toHaveAttribute('data-layer', 'sword');
    await tab(page, 'Design').click();
    await selectLayer(page, 'Sword');
    await expect(page.getByTestId('layer-logic-count')).toHaveText('2 rules answer clicks on this layer.');
  });
});

test.describe('the bolt on a layer', () => {
  test('a layer with rules wears a bolt; a click on it opens the rule in Logic', async ({ page }) => {
    await openStudio(page);
    await expect(layerRow(page, 'Buy button').getByTestId('layer-bolt')).toBeVisible();
    await expect(layerRow(page, 'Sword').getByTestId('layer-bolt')).toBeVisible();
    await expect(layerRow(page, 'Apple').getByTestId('layer-bolt')).toBeVisible(); // the shared rule is on both its layers
    await expect(layerRow(page, 'Coin').getByTestId('layer-bolt')).toBeVisible();
    await expect(layerRow(page, 'Frame').getByTestId('layer-bolt')).toHaveCount(0);
    await expect(layerRow(page, 'Heading').getByTestId('layer-bolt')).toHaveCount(0);

    await layerRow(page, 'Sword').getByTestId('layer-bolt').click();
    await expect(tab(page, 'Logic')).toHaveAttribute('aria-selected', 'true');
    await expect(node(page, 'behavior.1')).toHaveAttribute('aria-selected', 'true');
    await expect(node(page, 'behavior.1')).toHaveAttribute('data-layer', 'sword');
  });
});

test.describe('the layer on a trigger node', () => {
  test('hovering a trigger highlights its layer on a mini preview', async ({ page }) => {
    await openStudio(page);
    await tab(page, 'Logic').click();
    await expect(page.getByTestId('logic-layer-preview')).toHaveCount(0);
    await node(page, 'behavior.0').hover();
    const preview = page.getByTestId('logic-layer-preview');
    await expect(preview).toBeVisible();
    await expect(preview).toHaveAttribute('data-layer', 'buy');
    await expect(page.getByTestId('logic-layer-highlight')).toHaveAttribute('data-box', '118,50,48,18'); // the buy button's rectangle in the GUI
    await expect(preview).toContainText('Buy button');

    await node(page, 'behavior.1').hover();
    await expect(preview).toHaveAttribute('data-layer', 'sword');
    await page.getByTestId('logic-canvas').hover({ position: { x: 5, y: 5 } });
    await expect(preview).toHaveCount(0);
  });
});

test.describe('deleting a layer that has rules', () => {
  test('asks, and "Delete the rules" removes them with the layer in one undo step', async ({ page }) => {
    await openStudio(page);
    await selectLayer(page, 'Buy button');
    await page.getByRole('button', { name: 'Delete layer', exact: true }).click();
    const dialog = page.getByTestId('delete-layer-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('A rule in the Logic tab answers clicks on this layer');
    await dialog.getByTestId('delete-layer-rules').click();
    await expect(dialog).toHaveCount(0);
    await expect(layerRow(page, 'Buy button')).toHaveCount(0);
    await tab(page, 'Logic').click();
    await expect(page.locator('.node[data-kind="trigger"]')).toHaveCount(2);
    await expect(node(page, 'behavior.0')).toHaveAttribute('data-layer', 'sword');

    // one undo step brings the layer and its rule back
    await tab(page, 'Design').click();
    await page.getByRole('button', { name: 'Undo Delete layer and its rules' }).click();
    await expect(layerRow(page, 'Buy button')).toBeVisible();
    await expect(layerRow(page, 'Buy button').getByTestId('layer-bolt')).toBeVisible();
    await page.getByRole('button', { name: 'Redo Delete layer and its rules' }).click();
    await expect(layerRow(page, 'Buy button')).toHaveCount(0);

    await save(page);
    const file = await saved(page);
    expect(file.layers.map((layer) => layer.id)).not.toContain('buy');
    expect(file.behavior).toEqual([
      { on: 'gui.click', layer: 'sword', do: [{ sound: '' }] },
      { on: 'gui.click', layer: ['apple', 'coin'], do: ['gui.refresh'] },
    ]);
    const sent = await writes(page);
    expect(sent.filter((write) => write.op === 'logic.edit').flatMap((write) => (write.payload.edits as { op: string }[]).map((edit) => edit.op))).toEqual(['removeRules']);
  });

  test('"Keep the rules" takes the layer filter off them, and a rule shared with a layer that stays keeps that one', async ({ page }) => {
    await openStudio(page);
    await selectLayer(page, 'Sword');
    await page.keyboard.press('Delete');
    await page.getByTestId('delete-layer-detach').click();
    await expect(layerRow(page, 'Sword')).toHaveCount(0);

    await selectLayer(page, 'Apple');
    await page.getByRole('button', { name: 'Delete layer', exact: true }).click();
    await expect(page.getByTestId('delete-layer-dialog')).toContainText('also names a layer that stays');
    await page.getByTestId('delete-layer-rules').click();
    await expect(layerRow(page, 'Apple')).toHaveCount(0);
    await expect(layerRow(page, 'Coin').getByTestId('layer-bolt')).toBeVisible(); // the shared rule serves the coin still

    await save(page);
    expect((await saved(page)).behavior).toEqual([
      { on: 'gui.click', layer: 'buy', do: [{ message: 'hello' }] },
      { on: 'gui.click', do: [{ sound: '' }] }, // detached: no layer filter any more
      { on: 'gui.click', layer: ['coin'], do: ['gui.refresh'] },
    ]);
  });

  test('cancelling changes nothing, and a layer without rules is deleted at once', async ({ page }) => {
    await openStudio(page);
    await selectLayer(page, 'Buy button');
    await page.getByRole('button', { name: 'Delete layer', exact: true }).click();
    await page.getByTestId('delete-layer-dialog').getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByTestId('delete-layer-dialog')).toHaveCount(0);
    await expect(layerRow(page, 'Buy button')).toBeVisible();
    await expect(status(page)).toHaveText('Saved');

    await selectLayer(page, 'Price tag');
    await page.getByRole('button', { name: 'Delete layer', exact: true }).click();
    await expect(page.getByTestId('delete-layer-dialog')).toHaveCount(0);
    await expect(layerRow(page, 'Price tag')).toHaveCount(0);
  });
});

test.describe('diagnostics on the layer', () => {
  test('the compiler\'s verdict on a rule is drawn on its layer in Design, with the step path', async ({ page }) => {
    await openStudio(page);
    const problem = layerRow(page, 'Sword').getByTestId('layer-logic-problem');
    await expect(problem).toBeVisible();
    await expect(problem).toHaveAttribute('title', /MTR-0057.*behavior\.1\.do\.0/);
    await expect(layerRow(page, 'Buy button').getByTestId('layer-logic-problem')).toHaveCount(0);

    await selectLayer(page, 'Sword');
    const flag = page.getByTestId('layer-logic-flag');
    await expect(flag).toContainText('an action is missing its value');
    await expect(flag).toContainText('behavior.1.do.0');

    // and on the node, in Logic
    await tab(page, 'Logic').click();
    await expect(node(page, 'behavior.1.do.0').locator('.flag')).toBeVisible();
  });
});
