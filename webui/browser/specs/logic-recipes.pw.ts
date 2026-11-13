import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The Recipes panel (issue 225, logic.md section 7.5) on the deterministic `?logic` fixture: the server's own recorded recipe
// registry, and a stand-in for `logic.instantiate` that writes the shapes of fixtures/golden/recipes. `economy` makes the
// money actions available, as a server with Vault would; without it, Pay to receive is greyed with the server's reason.
test.beforeEach(async ({ page }) => blockCdn(page));

async function openLogic(page: Page, query = 'logic&economy'): Promise<void> {
  await page.goto(`/browser/fixture.html?${query}`);
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
const panel = (page: Page): Locator => page.getByTestId('logic-recipes');
const recipe = (page: Page, id: string): Locator => panel(page).locator(`[data-recipe="${id}"]`);
const node = (page: Page, id: string): Locator => page.locator(`.node[data-node="${id}"]`);

async function openPanel(page: Page): Promise<void> {
  await page.getByTestId('logic-recipes-toggle').click();
  await expect(panel(page)).toBeVisible();
}

async function save(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
}

async function behavior(page: Page): Promise<unknown> {
  const doc = await documentOf(page, 'guis/shop/main.yml');
  return (doc!.parsed.main as { behavior?: unknown }).behavior;
}

test('lists the server\'s recipes with a name and a line each', async ({ page }) => {
  await openLogic(page);
  await openPanel(page);
  await expect(panel(page).locator('[data-recipe]')).toHaveCount(10);
  for (const [id, name] of [
    ['pay_to_receive', 'Pay to receive'], ['close_button', 'Close button'], ['welcome_message', 'Welcome message'],
    ['confirm_dialog', 'Confirm dialog'], ['paged_player_list', 'Paged player list'], ['toggle_setting', 'Toggle setting'],
    ['server_selector', 'Server selector'], ['back_button', 'Back button'], ['trash_slot', 'Trash slot'], ['cooldown_gate', 'Cooldown gate'],
  ] as const) {
    await expect(recipe(page, id)).toContainText(name);
  }
  await expect(recipe(page, 'close_button')).toContainText('Closes the GUI when the button is clicked.');
  await expect(panel(page).locator('[data-unavailable]')).toHaveCount(0); // with an economy, nothing is greyed
});

test('Pay to receive: the form is the inspector\'s typed fields, the insert is one undo step, and the saved file is the expanded rule', async ({ page }) => {
  await openLogic(page);
  await openPanel(page);
  await recipe(page, 'pay_to_receive').click();
  const form = page.getByTestId('logic-recipe-form');
  await expect(page.getByTestId('recipe-insert')).toBeDisabled(); // layer, price and item are required

  await form.getByRole('combobox', { name: 'layer' }).selectOption('buy');
  await form.getByRole('textbox', { name: 'price' }).fill('250');
  await form.getByRole('textbox', { name: 'price' }).press('Enter');
  await form.getByRole('combobox', { name: 'item' }).fill('minecraft:diamond');
  await form.getByRole('textbox', { name: 'amount' }).fill('2');
  await form.getByRole('textbox', { name: 'amount' }).press('Enter');
  await expect(page.getByTestId('recipe-insert')).toBeEnabled();
  await page.getByTestId('recipe-insert').click();

  await expect(page.getByTestId('recipe-inserted')).toContainText('Inserted 1 rule');
  await expect(page.getByTestId('recipe-warning')).toHaveCount(0); // an economy is installed
  await expect(node(page, 'behavior.0')).toHaveAttribute('data-layer', 'buy');
  await expect(node(page, 'behavior.0')).toHaveAttribute('aria-selected', 'true');
  await expect(node(page, 'behavior.0.do.0')).toBeVisible(); // the require guard
  await expect(status(page)).toHaveText('Unsaved changes');

  // one undo step takes the whole rule back
  await page.getByTestId('logic-canvas').focus();
  await page.keyboard.press('Control+z');
  await expect(page.locator('.node')).toHaveCount(0);
  await page.keyboard.press('Control+Shift+z');
  await expect(node(page, 'behavior.0')).toBeVisible();

  await save(page);
  expect(await behavior(page)).toEqual([
    {
      on: 'gui.click',
      layer: 'buy',
      do: [
        {
          require: [{ take_money: { amount: 250 } }, { give_item: { item: 'minecraft:diamond', amount: 2 } }],
          else: [{ message: '<red>You cannot afford that.' }],
        },
        'gui.refresh',
      ],
    },
  ]);
  const sent = await writes(page);
  expect(sent.filter((write) => write.op === 'logic.edit').flatMap((write) => (write.payload.edits as { op: string }[]).map((edit) => edit.op))).toEqual(['addRule']);
  expect(sent.filter((write) => write.op === 'authoring.setFields')).toHaveLength(0);
});

test('a recipe the server cannot run here is greyed with the reason, and inserting it shows the warning', async ({ page }) => {
  await openLogic(page, 'logic');
  await openPanel(page);
  await expect(recipe(page, 'pay_to_receive')).toHaveAttribute('data-unavailable', 'true');
  await expect(recipe(page, 'pay_to_receive').getByTestId('recipe-reason')).toContainText('take_money');
  await expect(recipe(page, 'pay_to_receive').getByTestId('recipe-reason')).toContainText('vault');
  await expect(recipe(page, 'close_button')).not.toHaveAttribute('data-unavailable', 'true');

  await recipe(page, 'pay_to_receive').click();
  const form = page.getByTestId('logic-recipe-form');
  await form.getByRole('combobox', { name: 'layer' }).selectOption('buy');
  await form.getByRole('textbox', { name: 'price' }).fill('5');
  await form.getByRole('textbox', { name: 'price' }).press('Enter');
  await form.getByRole('combobox', { name: 'item' }).fill('minecraft:apple');
  await form.getByRole('combobox', { name: 'item' }).press('Tab');
  await expect(page.getByTestId('recipe-insert')).toHaveText(/Insert anyway/);
  await page.getByTestId('recipe-insert').click();
  await expect(page.getByTestId('recipe-warning')).toContainText('MTR-0088');
  await expect(page.getByTestId('recipe-inserted')).toBeVisible(); // a warning, not a refusal (logic.md section 30)
  await expect(node(page, 'behavior.0')).toBeVisible();
});

test('the layer selected in Design is offered as the recipe\'s layer, and several recipes can be inserted in turn', async ({ page }) => {
  await openLogic(page);
  await page.getByRole('tab', { name: 'Design' }).click();
  await page.locator('section[aria-label="Layers"] li.row').filter({ hasText: 'Coin' }).getByRole('button', { name: 'Coin', exact: true }).click();
  await page.getByRole('tab', { name: 'Logic' }).click();
  await openPanel(page);
  await recipe(page, 'close_button').click();
  await expect(page.getByTestId('logic-recipe-form').getByRole('combobox', { name: 'layer' })).toHaveValue('coin');
  await page.getByTestId('recipe-insert').click();
  await expect(node(page, 'behavior.0')).toHaveAttribute('data-layer', 'coin');

  await recipe(page, 'welcome_message').click();
  await page.getByTestId('logic-recipe-form').getByRole('combobox', { name: 'text' }).fill('<gold>Welcome!');
  await page.getByTestId('logic-recipe-form').getByRole('combobox', { name: 'text' }).press('Tab');
  await page.getByTestId('recipe-insert').click();
  await expect(node(page, 'behavior.1')).toHaveAttribute('data-op', 'gui.open');

  await save(page);
  expect(await behavior(page)).toEqual([
    { on: 'gui.click', layer: 'coin', do: ['gui.close'] },
    { on: 'gui.open', do: [{ message: '<gold>Welcome!' }] },
  ]);
});

test('the server\'s refusal is shown in the panel and nothing is inserted', async ({ page }) => {
  await openLogic(page);
  await openPanel(page);
  await recipe(page, 'pay_to_receive').click();
  const form = page.getByTestId('logic-recipe-form');
  await form.getByRole('combobox', { name: 'layer' }).selectOption('buy');
  await form.getByRole('textbox', { name: 'price' }).fill('9');
  await form.getByRole('textbox', { name: 'price' }).press('Enter');
  await form.getByRole('combobox', { name: 'item' }).fill('minecraft:apple');
  await form.getByRole('combobox', { name: 'item' }).press('Tab');
  // an expression where the server wants a number: the recipe check refuses it
  await form.getByRole('button', { name: 'Use an expression for price' }).click();
  await form.getByRole('combobox', { name: 'price' }).fill('${vars.player.coins}');
  await form.getByRole('combobox', { name: 'price' }).press('Tab');
  await page.getByTestId('recipe-insert').click();
  await expect(page.getByTestId('recipe-error')).toContainText('MTR-0086');
  await expect(page.locator('.node')).toHaveCount(0);
  await expect(status(page)).toHaveText('Saved');
});
