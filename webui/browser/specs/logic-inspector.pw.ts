import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The node inspector (issue 224, logic.md section 7.3, 31) on the deterministic `?logic` fixture: the sample shop
// screen with the logic capability, the server's own recorded vocabulary (list sources, input methods, takesElse and
// the widget hints included) and a declared-variables file. `economy` makes the money actions available, as a server
// with Vault would. Everything below is authored through the palette, the canvas and the inspector; no YAML is typed.
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
const inspector = (page: Page): Locator => page.getByTestId('logic-inspector');
const node = (page: Page, id: string): Locator => page.locator(`.node[data-node="${id}"]`);
const port = (page: Page, id: string, name: string): Locator => page.locator(`[data-node="${id}"][data-port="${name}"]`);

/** Drags from a node's output port to empty canvas below it and releases there: the palette opens at that port. */
async function dragOut(page: Page, from: Locator): Promise<void> {
  const box = (await from.boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x - 20, y + 60, { steps: 4 });
  await page.mouse.move(x - 40, y + 140, { steps: 4 });
  await page.mouse.up();
}

async function pick(page: Page, entry: string): Promise<void> {
  await expect(page.getByTestId('logic-palette')).toBeVisible();
  await page.locator(`[data-entry="${entry}"]`).click();
  await expect(page.getByTestId('logic-palette')).toHaveCount(0);
}

async function fit(page: Page): Promise<void> {
  await page.getByTestId('logic-canvas').focus();
  await page.keyboard.press('0');
}

/** The saved GUI's logic block, as the fixture holds it. */
async function savedLogic(page: Page): Promise<{ behavior?: unknown; flows?: unknown }> {
  const doc = await documentOf(page, 'guis/shop/main.yml');
  const { behavior, flows } = (doc!.parsed.main ?? {}) as Record<string, unknown>;
  return { behavior, flows };
}

async function save(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
}

/**
 * gui.click on the buy button -> require (take money, give an item) -> failed: a message. Built with the palette,
 * the canvas and the inspector only. The message is left for the caller to fill.
 */
async function buildPayToReceive(page: Page): Promise<void> {
  await page.getByRole('button', { name: /add a trigger/i }).click();
  await pick(page, 'trigger:gui.click');
  await expect(node(page, 'behavior.0')).toBeVisible();

  // the trigger's filter: the layer the click is about, offered from the GUI's own layers
  await inspector(page).getByRole('combobox', { name: 'Layer' }).fill('buy');
  await page.keyboard.press('Enter');
  await expect(node(page, 'behavior.0')).toContainText('layer: buy');

  await dragOut(page, port(page, 'behavior.0', 'out'));
  await pick(page, 'control:require');
  await expect(inspector(page).getByTestId('require-note')).toBeVisible();

  await fit(page);
  await dragOut(page, port(page, 'behavior.0.do.0', 'steps'));
  await pick(page, 'action:take_money');
  await inspector(page).getByRole('textbox', { name: 'Amount' }).fill('250');
  await page.keyboard.press('Enter');
  await expect(node(page, 'behavior.0.do.0.require.0').getByRole('textbox', { name: 'Take money value' })).toHaveValue('250');

  await fit(page);
  await dragOut(page, port(page, 'behavior.0.do.0.require.0', 'after'));
  await pick(page, 'action:give_item');
  await inspector(page).getByRole('button', { name: /^Item/ }).first().click();
  await page.getByRole('combobox', { name: /Search items/ }).fill('blade');
  await page.locator('[data-choice="ruby_blade"]').click();
  await inspector(page).getByRole('textbox', { name: 'Amount' }).fill('2');
  await page.keyboard.press('Enter');

  await fit(page);
  await dragOut(page, port(page, 'behavior.0.do.0', 'failed'));
  await pick(page, 'action:message');
}

test('pay to receive: authored with the palette, the canvas and the inspector, saved as the expected YAML', async ({ page }) => {
  await openLogic(page);
  await buildPayToReceive(page);

  // the message's template field completes from the rule's trigger context: ${ offers the roots, then the paths
  const text = inspector(page).getByRole('combobox', { name: 'Text' });
  await text.click();
  await page.keyboard.type('Sorry ${pl');
  await expect(inspector(page).getByRole('option', { name: /^player\./ })).toBeVisible();
  await page.keyboard.press('Enter'); // player.
  await page.keyboard.type('na');
  await expect(inspector(page).getByRole('option', { name: /player\.name/ })).toBeVisible();
  await page.keyboard.press('Enter'); // player.name}
  await page.keyboard.type(', you need $250.');
  await expect(text).toHaveValue('Sorry ${player.name}, you need $250.');
  await expect(inspector(page).locator('[data-lint]')).toHaveCount(0); // everything it names is in the trigger's context
  await page.keyboard.press('Enter');
  await expect(node(page, 'behavior.0.do.0.else.0').getByRole('textbox', { name: 'Send message value' })).toHaveValue('Sorry ${player.name}, you need $250.');

  await expect(status(page)).toHaveText('Unsaved changes');
  expect((await savedLogic(page)).behavior).toBeUndefined(); // nothing is written until Save
  await save(page);

  expect(await savedLogic(page)).toEqual({
    behavior: [
      {
        on: 'gui.click',
        do: [
          {
            require: [{ take_money: 250 }, { give_item: { item: 'fixture_pack:ruby_blade', amount: 2 } }],
            else: [{ message: 'Sorry ${player.name}, you need $250.' }],
          },
        ],
        layer: 'buy',
      },
    ],
  });

  // one pipeline: every edit reached the server as a logic.edit, none as a whole-block rewrite
  const sent = (await writes(page)).filter((write) => write.op === 'logic.edit');
  expect(sent).toHaveLength(1);
  const ops = (sent[0]!.payload.edits as { op: string }[]).map((edit) => edit.op);
  expect(ops).toContain('patchRule');
  expect(ops).toContain('patchStep');
  expect(ops).not.toContain('removeRules');
  expect((await writes(page)).filter((write) => write.op === 'authoring.setFields')).toHaveLength(0);
});

test('an invalid field is flagged on its own field and on the right node, and the flag clears when it is fixed', async ({ page }) => {
  await openLogic(page);
  await buildPayToReceive(page);

  const text = inspector(page).getByRole('combobox', { name: 'Text' });
  await text.click();
  await page.keyboard.type('Hello ${bogus.value}');
  // the field's own lint answers at once; the node has nothing yet because nothing was committed
  await expect(inspector(page).locator('[data-lint]')).toContainText("'bogus'");
  await expect(node(page, 'behavior.0.do.0.else.0').getByRole('img', { name: /MTR-0060/ })).toHaveCount(0);
  await page.keyboard.press('Enter');

  // the compiler's verdict (logic.validate) comes back for the committed draft and is drawn on the message node only
  const flagged = node(page, 'behavior.0.do.0.else.0').getByRole('img', { name: /MTR-0060.*'bogus'/ });
  await expect(flagged).toBeVisible();
  for (const other of ['behavior.0', 'behavior.0.do.0', 'behavior.0.do.0.require.0', 'behavior.0.do.0.require.1']) {
    await expect(node(page, other).getByRole('img', { name: /MTR-/ })).toHaveCount(0);
  }
  // and in the inspector, on the field that says it
  await expect(inspector(page).locator('[data-verdict]')).toContainText("'bogus'");

  await text.fill('Hello ${player.name}');
  await page.keyboard.press('Enter');
  await expect(node(page, 'behavior.0.do.0.else.0').getByRole('img', { name: /MTR-/ })).toHaveCount(0);
  await expect(inspector(page).locator('[data-verdict]')).toHaveCount(0);
});

test('the compiler is asked about a template while it is typed, before it is committed', async ({ page }) => {
  await openLogic(page);
  await buildPayToReceive(page);
  const calls = () => page.evaluate(() => window.__materioneTestControls!.calls().filter((call) => call.op === 'logic.validate').length);
  const before = await calls();
  await inspector(page).getByRole('combobox', { name: 'Text' }).click();
  await page.keyboard.type('${nope.x}');
  await expect.poll(calls).toBeGreaterThan(before); // the draft went to logic.validate (debounced) with the document untouched
  await expect(status(page)).toHaveText('Unsaved changes');
  await expect(inspector(page).locator('[data-verdict]')).toContainText("'nope'");
});

test('% completes from the placeholder expansions Materione defines, and functions show their signatures', async ({ page }) => {
  await openLogic(page);
  await buildPayToReceive(page);
  const text = inspector(page).getByRole('combobox', { name: 'Text' });
  await text.click();
  await page.keyboard.type('Balance %materione_g');
  await expect(inspector(page).getByRole('option', { name: '%materione_gui_open%' })).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(text).toHaveValue('Balance %materione_gui_open%');
  await text.fill('');
  await page.keyboard.type('${con');
  const contains = inspector(page).getByRole('option', { name: /^contains\(/ });
  await expect(contains).toBeVisible();
  await expect(contains).toContainText('boolean');
});

test('declared variables are picked from the files, and a new one is declared through the authoring ops', async ({ page }) => {
  await openLogic(page);
  await page.getByRole('button', { name: /add a trigger/i }).click();
  await pick(page, 'trigger:gui.click');
  await dragOut(page, port(page, 'behavior.0', 'out'));
  await pick(page, 'action:set_var');

  const field = inspector(page).getByTestId('variable-field').first();
  await field.getByRole('button', { name: /^Choose/ }).click();
  await expect(page.locator('[data-variable="fixture_pack:coins"]')).toBeVisible();
  await expect(page.locator('[data-variable="fixture_pack:vip"]')).toBeVisible();
  await page.locator('[data-variable="fixture_pack:coins"]').click();
  await expect(field.getByRole('textbox', { name: 'Var' })).toHaveValue('fixture_pack:coins');
  await expect(field).toContainText('player number, starts as 0');

  // declare a new one: the declaration is written to the pack's variables file, and the node names it
  await field.getByRole('button', { name: /^Choose/ }).click();
  await page.getByTestId('declare-new').click();
  const form = page.getByTestId('declare-variable');
  await form.getByLabel('Name').fill('gems');
  await form.getByLabel('Stored').selectOption('server');
  await form.getByLabel('Note').fill('Gems in the vault');
  await form.getByRole('button', { name: 'Declare' }).click();
  await expect(field.getByRole('textbox', { name: 'Var' })).toHaveValue('fixture_pack:gems');

  const declared = await documentOf(page, 'variables/shop.yml');
  expect(declared!.parsed.gems).toEqual({ scope: 'server', type: 'number', default: 0, description: 'Gems in the vault' });
  expect(declared!.parsed.coins).toBeDefined(); // the rest of the file is untouched
  const edit = (await writes(page)).filter((write) => write.op === 'authoring.setFields');
  expect(edit).toHaveLength(1);
  expect(edit[0]!.payload.path).toBe('variables/shop.yml');

  // the new variable is offered to the next template as vars.server.fixture_pack.gems
  await expect(page.getByTestId('declare-variable')).toHaveCount(0);
  await inspector(page).getByRole('combobox', { name: 'Value' }).click();
  await page.keyboard.type('${vars.server.');
  await expect(inspector(page).getByRole('option', { name: /fixture_pack\./ })).toBeVisible();

  // a taken or malformed name is refused with the reason, before anything is written
  await field.getByRole('button', { name: /^Choose/ }).click();
  await page.getByTestId('declare-new').click();
  await page.getByTestId('declare-variable').getByLabel('Name').fill('coins');
  await expect(page.getByTestId('declare-variable').getByRole('alert')).toContainText('already declared');
  await expect(page.getByTestId('declare-variable').getByRole('button', { name: 'Declare' })).toBeDisabled();
});

test('the condition stack: predicates with typed fields, an expression, nesting, reordering and removal', async ({ page }) => {
  await openLogic(page);
  await page.getByRole('button', { name: /add a trigger/i }).click();
  await pick(page, 'trigger:gui.click');
  await dragOut(page, port(page, 'behavior.0', 'out'));
  await pick(page, 'control:if');
  const stack = inspector(page).getByTestId('condition-stack');

  // a new if starts as ${true}: change the row to a check, which is a typed predicate
  await expect(stack.locator('[data-cond-row]')).toHaveCount(1);
  await stack.getByRole('combobox', { name: 'Kind of condition' }).selectOption('predicate');
  await stack.getByTestId('predicate').selectOption('has_money');
  await stack.getByRole('textbox', { name: 'Amount' }).fill('100');
  await page.keyboard.press('Enter');

  // a second condition makes the stack a list (which is all); an expression row autocompletes bare names
  await stack.getByRole('button', { name: 'Also require an expression' }).click();
  await expect(stack.locator('[data-cond-row]')).toHaveCount(3); // the all, and its two rows
  const expression = stack.getByRole('combobox', { name: 'Expression' });
  await expression.fill('');
  await expression.click();
  await page.keyboard.type('player.le');
  await expect(inspector(page).getByRole('option', { name: 'player.level' })).toBeVisible();
  await page.keyboard.press('Enter');
  await page.keyboard.type(' >= 10');
  await page.keyboard.press('Enter');

  // any of: switch the group, then nest a not around the first check
  await stack.getByRole('combobox', { name: 'Kind of condition' }).first().selectOption('any');
  await expect(stack.locator('[data-cond-row][data-kind="any"]')).toHaveCount(1);

  // reorder: move the expression above the check
  await stack.getByRole('button', { name: 'Move up' }).last().click();

  await save(page);
  expect((await savedLogic(page)).behavior).toEqual([
    {
      on: 'gui.click',
      do: [{ if: { any: ['player.level >= 10', { has_money: 100 }] }, then: [] }],
    },
  ]);

  // remove a row: the group keeps the other
  await stack.getByRole('button', { name: 'Remove this condition' }).last().click();
  await save(page);
  expect(((await savedLogic(page)).behavior as { do: { if: unknown }[] }[])[0]!.do[0]!.if).toEqual({ any: ['player.level >= 10'] });
});

test('a prompt step: the input methods come from the server, an unavailable one is greyed with its reason, and its else is a branch', async ({ page }) => {
  await openLogic(page);
  await page.getByRole('button', { name: /add a trigger/i }).click();
  await pick(page, 'trigger:gui.click');
  await dragOut(page, port(page, 'behavior.0', 'out'));
  await pick(page, 'action:prompt');

  const methods = inspector(page).getByRole('radiogroup', { name: 'Method' });
  await expect(methods.getByRole('radio', { name: 'Chat' })).toBeVisible();
  const sign = methods.getByRole('radio', { name: 'Sign' });
  await expect(sign).toHaveAttribute('aria-disabled', 'true');
  await expect(inspector(page).locator('[data-method-reason="sign"]')).toContainText('openVirtualSign');
  await sign.click({ force: true }); // an unavailable method cannot be picked
  await expect(methods.getByRole('radio', { name: 'Chat' })).toHaveAttribute('aria-checked', 'true');
  await methods.getByRole('radio', { name: 'Anvil' }).click();

  await inspector(page).getByRole('combobox', { name: 'Text' }).fill('Search for an item');
  await page.keyboard.press('Enter');
  await inspector(page).getByTestId('variable-field').first().getByRole('textbox').fill('session.search');
  await page.keyboard.press('Enter');
  await expect(inspector(page).getByTestId('takes-else')).toBeVisible();

  // the question has an else output, like if's: a step hung on it is written as the else: of the step
  await expect(port(page, 'behavior.0.do.0', 'else')).toBeVisible();
  await fit(page);
  await dragOut(page, port(page, 'behavior.0.do.0', 'else'));
  await pick(page, 'action:message');
  await inspector(page).getByRole('combobox', { name: 'Text' }).fill('No search, then.');
  await page.keyboard.press('Enter');

  await save(page);
  expect((await savedLogic(page)).behavior).toEqual([
    {
      on: 'gui.click',
      do: [
        {
          prompt: { text: 'Search for an item', var: 'session.search', method: 'anvil' },
          else: [{ message: 'No search, then.' }],
        },
      ],
    },
  ]);
});

test('what the server does not describe is kept and shown, never dropped', async ({ page }) => {
  await openLogic(page, 'logic&economy&seed=odd');
  await expect(node(page, 'behavior.0')).toBeVisible();

  // an unknown parameter of a known action: listed read-only, and every other edit leaves it alone
  await node(page, 'behavior.0.do.0').locator('header').click();
  await expect(inspector(page).getByTestId('stray-keys')).toContainText('shiny');
  await inspector(page).getByRole('textbox', { name: 'Volume' }).fill('3');
  await page.keyboard.press('Enter');

  // an unknown rule key
  await node(page, 'behavior.0').locator('header').click();
  await expect(inspector(page).getByTestId('stray-keys')).toContainText('mystery');

  // an action nothing registers: said so, and its settings stay editable as written
  await node(page, 'behavior.0.do.1').locator('header').click();
  await expect(inspector(page)).toContainText('No action called future_action is registered');
  await expect(inspector(page).getByRole('textbox', { name: 'Settings' })).toHaveValue('{"a":1}');

  // a prompt that names a method this server cannot use keeps it selected, with the warning
  await node(page, 'behavior.0.do.2').locator('header').click();
  await expect(inspector(page).getByRole('radiogroup', { name: 'Method' }).getByRole('radio', { name: 'Sign' })).toHaveAttribute('aria-checked', 'true');
  await expect(port(page, 'behavior.0.do.2', 'else')).toBeVisible();
  await expect(node(page, 'behavior.0.do.2.else.0').getByRole('textbox', { name: 'Send message value' })).toHaveValue('gave up');

  await save(page);
  expect((await savedLogic(page)).behavior).toEqual([
    {
      on: 'gui.click', layer: 'buy', mystery: 7,
      do: [
        { sound: { sound: 'block.chest.open', volume: 3, shiny: true } },
        { future_action: { a: 1 } },
        { prompt: { text: 'Name it', var: 'session.name', method: 'sign' }, else: [{ message: 'gave up' }] },
      ],
    },
  ]);
});

test('the inspector collapses to a rail and comes back, and the page never scrolls sideways', async ({ page }) => {
  await openLogic(page);
  await expect(inspector(page)).toBeVisible();
  await inspector(page).getByRole('button', { name: 'Hide the inspector' }).click();
  await expect(inspector(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'Show the inspector' }).click();
  await expect(inspector(page)).toBeVisible();
  await expect(inspector(page).getByTestId('inspector-empty')).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
});
