import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';
import { blockCdn, documentOf, writes } from './helpers/modules';

// The Script tab (issue 225, logic.md section 7.1) on the deterministic `?logic&seed=linkage` fixture: the same document as
// the Design and Logic tabs, as YAML. Edits commit through the shared history and reach the file through logic.edit
// (behavior: and flows:) or the layers' own save (open:).
test.beforeEach(async ({ page }) => blockCdn(page));

async function openScript(page: Page, query = 'logic&seed=linkage'): Promise<void> {
  await page.goto(`/browser/fixture.html?${query}`);
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('option', { name: /shop/i }).dblclick();
  await page.getByRole('option', { name: /main/i }).dblclick();
  await expect(page.getByTestId('gui-art')).toBeVisible();
  await page.getByRole('tab', { name: 'Script' }).click();
  await expect(editor(page)).toBeVisible();
}

const editor = (page: Page): Locator => page.getByTestId('script-editor');
const state = (page: Page): Locator => page.getByTestId('script-state');
const status = (page: Page): Locator => page.locator('[data-status]');
const tab = (page: Page, name: string): Locator => page.getByRole('tab', { name });

async function save(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(status(page)).toHaveText('Saved');
}

async function file(page: Page): Promise<Record<string, unknown>> {
  const doc = await documentOf(page, 'guis/shop/main.yml');
  return (doc!.parsed.main ?? {}) as Record<string, unknown>;
}

/** Types a whole new script and waits for it to be applied to the document. */
async function type(page: Page, text: string): Promise<void> {
  await editor(page).fill(text);
  await expect(state(page)).toHaveAttribute('data-state', 'synced');
}

const SEEDED = `behavior:
  - on: gui.click
    layer: buy
    do:
      - message: hello
  - on: gui.click
    layer: sword
    do:
      - sound: ""
  - on: gui.click
    layer:
      - apple
      - coin
    do:
      - gui.refresh
`;

test('shows the resource\'s behavior as YAML, and nothing else of the file', async ({ page }) => {
  await openScript(page);
  expect(await editor(page).inputValue()).toBe(SEEDED);
  await expect(state(page)).toHaveAttribute('data-state', 'synced');
  await expect(status(page)).toHaveText('Saved'); // opening the tab rewrites nothing
  expect(await writes(page)).toHaveLength(0);
});

test('an edit round trip: typed, applied after a pause, saved through logic.edit, the rest of the file untouched', async ({ page }) => {
  await openScript(page);
  await type(page, SEEDED.replace('message: hello', 'message: bye'));
  await expect(status(page)).toHaveText('Unsaved changes');
  expect((await file(page)).behavior).toMatchObject([{ do: [{ message: 'hello' }] }, {}, {}]); // nothing written until Save

  await save(page);
  const saved = await file(page);
  expect(saved.title).toBe('Shop');
  expect(saved.container).toBe('chest_3');
  expect((saved.behavior as { do: unknown[] }[])[0]!.do).toEqual([{ message: 'bye' }]);
  expect((saved.behavior as unknown[]).length).toBe(3);

  const sent = await writes(page);
  // only the rule that changed travels: the other two are not rewritten
  expect(sent.filter((write) => write.op === 'logic.edit').flatMap((write) => (write.payload.edits as { op: string }[]).map((edit) => edit.op))).toEqual(['removeRules', 'addRule']);
  expect(sent.filter((write) => write.op === 'authoring.setFields')).toHaveLength(0);
});

test('the open: block is edited here too, and saved with the layers\' own save', async ({ page }) => {
  await openScript(page);
  await type(page, `open:\n  commands:\n    - shop\n${SEEDED}`);
  await save(page);
  expect((await file(page)).open).toEqual({ commands: ['shop'] });
  const sent = await writes(page);
  const fields = sent.filter((write) => write.op === 'authoring.setFields').flatMap((write) => write.payload.edits as { field: string[] }[]);
  expect(fields.map((edit) => edit.field.join('.'))).toEqual(['main.open']);
  expect(sent.filter((write) => write.op === 'logic.edit')).toHaveLength(0); // the rules did not change

  // and taking it out again removes the key
  await type(page, SEEDED);
  await save(page);
  expect(await file(page)).not.toHaveProperty('open');
});

test('invalid YAML is kept as a draft: the error is on its line, nothing reaches the file, and the draft survives a tab switch', async ({ page }) => {
  await openScript(page);
  await editor(page).fill('behavior: [');
  await expect(state(page)).toHaveAttribute('data-state', 'error');
  await expect(page.getByTestId('script-error')).toContainText('Line');
  await expect(page.locator('.ln[data-mark="error"]')).toHaveCount(1);
  await page.waitForTimeout(1200); // well past the pause that would have committed it
  await expect(status(page)).toHaveText('Saved');
  expect(((await file(page)).behavior as unknown[]).length).toBe(3);

  await tab(page, 'Design').click();
  await tab(page, 'Logic').click();
  await expect(page.locator('.node[data-kind="trigger"]')).toHaveCount(3); // the document still has its rules
  await tab(page, 'Script').click();
  expect(await editor(page).inputValue()).toBe('behavior: [');
  await expect(state(page)).toHaveAttribute('data-state', 'error');

  // fixed: it applies
  await type(page, 'behavior:\n  - on: gui.open\n    do:\n      - gui.close\n');
  await save(page);
  expect((await file(page)).behavior).toEqual([{ on: 'gui.open', do: ['gui.close'] }]);
});

test('a draft that was not applied survives a change made in another tab, until the author discards it', async ({ page }) => {
  await openScript(page);
  await editor(page).fill('behavior: [');
  await expect(state(page)).toHaveAttribute('data-state', 'error');
  await tab(page, 'Logic').click();
  await page.getByRole('button', { name: 'New named flow' }).click();
  await tab(page, 'Script').click();
  expect(await editor(page).inputValue()).toBe('behavior: ['); // the draft is not overwritten
  await expect(page.getByTestId('script-discard')).toBeVisible();
  await expect(page.getByTestId('script-apply-anyway')).toBeVisible();
  await page.getByTestId('script-discard').click();
  expect(await editor(page).inputValue()).toBe(`${SEEDED}flows:\n  flow: []\n`); // the document as it stands now
  await expect(state(page)).toHaveAttribute('data-state', 'synced');
});

test('an edit the logic edits refuse (a rule with no on:) is not applied, and says why', async ({ page }) => {
  await openScript(page);
  await editor(page).fill('behavior:\n  - do:\n      - gui.close\n');
  await expect(state(page)).toHaveAttribute('data-state', 'error');
  await expect(page.getByTestId('script-error')).toContainText('on:');
  await page.waitForTimeout(1200);
  await expect(status(page)).toHaveText('Saved');
  await page.getByTestId('script-discard').click();
  expect(await editor(page).inputValue()).toBe(SEEDED);
});

test('a key this tab does not hold is refused, and the rest of the file is said to be untouched', async ({ page }) => {
  await openScript(page);
  await editor(page).fill(`title: Hacked\n${SEEDED}`);
  await expect(state(page)).toHaveAttribute('data-state', 'error');
  await expect(page.getByTestId('script-error')).toContainText('not edited here');
});

test('the compiler\'s verdict on the draft is drawn on the line its step path names', async ({ page }) => {
  await openScript(page);
  const line = SEEDED.split('\n').findIndex((text) => text.includes('sound: ""')) + 1;
  const diagnostic = page.getByTestId('script-diagnostic');
  await expect(diagnostic).toHaveCount(1);
  await expect(diagnostic).toHaveAttribute('data-line', String(line));
  await expect(diagnostic).toContainText('MTR-0057');
  await expect(page.locator(`.ln[data-line="${line}"]`)).toHaveAttribute('data-mark', 'warn');

  // fixing the line clears it
  await type(page, SEEDED.replace('sound: ""', 'sound: block.chest.open'));
  await expect(page.getByTestId('script-diagnostic')).toHaveCount(0);
});

test('one undo history across the tabs: a script edit is undone from Design, and a Logic edit from the Script tab', async ({ page }) => {
  await openScript(page);
  await type(page, SEEDED.replace('message: hello', 'message: bye'));
  await tab(page, 'Design').click();
  await expect(status(page)).toHaveText('Unsaved changes');
  await page.getByRole('button', { name: /^Undo Edit script/ }).click();
  await expect(status(page)).toHaveText('Saved');
  await tab(page, 'Script').click();
  expect(await editor(page).inputValue()).toBe(SEEDED); // the text follows the document

  // a Logic edit shows in the Script tab, and its undo is there
  await tab(page, 'Logic').click();
  await page.getByRole('button', { name: 'New named flow' }).click();
  await tab(page, 'Script').click();
  expect(await editor(page).inputValue()).toContain('flows:\n  flow: []');
  await page.getByRole('button', { name: 'Undo', exact: true }).click();
  expect(await editor(page).inputValue()).toBe(SEEDED);
  await expect(status(page)).toHaveText('Saved');
});

test('Ctrl+S saves a draft typed a moment ago', async ({ page }) => {
  await openScript(page);
  await editor(page).fill(SEEDED.replace('message: hello', 'message: quick'));
  await page.keyboard.press('Control+s'); // before the pause that would have applied it
  await expect(status(page)).toHaveText('Saved');
  expect(((await file(page)).behavior as { do: unknown[] }[])[0]!.do).toEqual([{ message: 'quick' }]);
});
