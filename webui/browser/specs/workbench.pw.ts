import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

const blockbenchBridge = readFileSync(
  new URL('../../public/materione-bridge.js', import.meta.url),
  'utf8',
);

// Stand in for Blockbench's external globals while exercising the real bridge and ModelWorkbench.
const blockbenchFrame = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <script>
      window.Blockbench = { setup_successful: true };
      window.Project = null;
      window.Codecs = {
        java_block: {
          compile: () => ({ parent: 'builtin/generated', textures: {}, elements: [] }),
        },
        project: {
          load: (project) => { window.Project = project; },
          compile: () => window.Project,
        },
      };
      window.Texture = { all: [] };
    </script>
  </head>
  <body></body>
</html>`;

async function openFixture(page: Page): Promise<void> {
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
}

async function openStudioFromContent(page: Page, resourceId = 'ruby_blade'): Promise<void> {
  await page.getByRole('option', { name: new RegExp(resourceId, 'i') }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

async function openBladeInFiles(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('button', { name: /ruby_blade\.yml/i }).click();
  await expect(page.getByLabel('Display name')).toBeEditable();
}

async function editDisplayName(page: Page, value: string): Promise<void> {
  const field = page.getByLabel('Display name');
  await field.fill(value);
  await field.press('Tab');
  await expect(field).toHaveValue(value);
}

async function readWrites(page: Page) {
  return page.evaluate(() => {
    const controls = window.__materioneTestControls;
    if (!controls) throw new Error('Browser fixture controls are missing.');
    return controls.writes();
  });
}

async function stubBlockbenchBoundary(page: Page): Promise<void> {
  await page.route('**/blockbench/index.html', (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: blockbenchFrame }),
  );
  await page.route('**/materione-bridge.js', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/javascript',
      body: blockbenchBridge,
    }),
  );
}

test('mounts the real Workbench at desktop and narrow desktop widths', async ({ page }, testInfo) => {
  await openFixture(page);

  await expect(page.getByRole('button', { name: /content/i }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /files/i }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /publish/i })).toBeVisible();
  await expect(page.getByRole('listbox', { name: /items/i })).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath('workbench-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.setViewportSize({ width: 1024, height: 768 });
  await page.screenshot({
    path: testInfo.outputPath('workbench-narrow-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
});

test('navigates from the real Files document into its Studio', async ({ page }) => {
  await openFixture(page);

  await page.getByRole('button', { name: /files/i }).first().click();
  await page.getByRole('button', { name: /ruby_blade\.yml/i }).click();
  await expect(page.getByRole('button', { name: /open in studio/i })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'fixture_pack:ruby_blade' })).toBeVisible();

  await page.getByRole('button', { name: /open in studio/i }).click();
  await expect(page.getByText('Ruby Blade', { exact: true }).last()).toBeVisible();
  await expect(page.getByRole('button', { name: /open.*(files|yaml)/i })).toBeVisible();

  const writes = await readWrites(page);
  expect(writes).toHaveLength(0);
});

test('selects the second resource root in a shared Files document before opening Studio', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('button', { name: /shared_gems\.yml/i }).click();

  const resource = page.getByLabel('Resource');
  await expect(resource).toHaveValue('ruby_a');
  await resource.selectOption('ruby_b');
  await expect(page.getByRole('heading', { name: 'fixture_pack:ruby_b' })).toBeVisible();
  await page.getByRole('button', { name: 'Open in Studio' }).click();

  await expect(page.getByText('Ruby B', { exact: true }).last()).toBeVisible();
  expect(await readWrites(page)).toHaveLength(0);
});

test('stages multiple Files fields until one explicit Save & apply batch', async ({ page }) => {
  await openFixture(page);
  await openBladeInFiles(page);

  const save = page.getByRole('button', { name: 'Save & apply' });
  await expect(save).toBeDisabled();
  await page.getByLabel('Display name').fill('Staged batch name');
  await page.getByLabel('Material').selectOption('BLAZE_ROD');
  await expect(save).toBeEnabled();
  expect(await readWrites(page)).toHaveLength(0);

  await save.click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(1);
  await expect(save).toBeDisabled();
  const writes = await readWrites(page);
  expect(writes).toHaveLength(1);
  expect(writes[0]?.op).toBe('authoring.setFields');
  expect(writes[0]?.payload.edits).toEqual([
    { field: ['ruby_blade', 'display_name'], value: 'Staged batch name' },
    { field: ['ruby_blade', 'material'], value: 'BLAZE_ROD' },
  ]);
});

test('retains pending raw YAML across workspace navigation and external edits until explicit recovery', async ({
  page,
}) => {
  await openFixture(page);
  await openBladeInFiles(page);
  await page.getByRole('tab', { name: 'yaml' }).click();

  const rawYaml = page.getByLabel('raw YAML');
  const pendingYaml = (await rawYaml.inputValue()).replace('Ruby Blade', 'Pending YAML workspace draft');
  await rawYaml.fill(pendingYaml);
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await expect(rawYaml).toHaveValue(pendingYaml);

  await page.evaluate(() =>
    window.__materioneTestControls?.externalEdit('fixture_pack', 'items/ruby_blade.yml'),
  );
  await expect(page.getByText('changed on disk — your unsaved edits are kept')).toBeVisible();
  await expect(rawYaml).toHaveValue(pendingYaml);
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();

  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'discard and reload' }).click();
  await expect
    .poll(async () => rawYaml.inputValue())
    .toContain('Ruby Blade (external edit)');
  expect(await rawYaml.inputValue()).not.toContain('Pending YAML workspace draft');
  expect(await readWrites(page)).toHaveLength(0);
});

test('keeps raw YAML after a revision conflict until the user reloads it', async ({ page }) => {
  await openFixture(page);
  await openBladeInFiles(page);
  await page.getByRole('tab', { name: 'yaml' }).click();

  const rawYaml = page.getByLabel('raw YAML');
  const pendingYaml = (await rawYaml.inputValue()).replace('Ruby Blade', 'Pending YAML conflict draft');
  await rawYaml.fill(pendingYaml);
  await page.evaluate(() => window.__materioneTestControls?.conflictNextWrite());
  await page.getByRole('button', { name: 'Save & apply' }).click();

  await expect(page.getByText(/changed elsewhere/i)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();
  await expect(rawYaml).toHaveValue(pendingYaml);
  const writes = await readWrites(page);
  expect(writes).toHaveLength(1);
  expect(writes[0]?.op).toBe('edit.put');
  expect(writes[0]?.outcome).toBe('conflict');

  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'reload', exact: true }).click();
  await expect
    .poll(async () => rawYaml.inputValue())
    .toContain('Ruby Blade');
  expect(await rawYaml.inputValue()).not.toContain('Pending YAML conflict draft');
  expect(await readWrites(page)).toHaveLength(1);
});

test('stages a decimal field as a number and saves only on explicit apply', async ({ page }) => {
  await openFixture(page);
  await openBladeInFiles(page);

  const power = page.getByLabel('Power');
  await power.fill('1.5');
  await expect(power).toHaveValue('1.5');
  expect(await readWrites(page)).toHaveLength(0);

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(1);
  const writes = await readWrites(page);
  expect(writes[0]?.op).toBe('authoring.setFields');
  expect(writes[0]?.payload.edits).toEqual([
    { field: ['ruby_blade', 'power'], value: 1.5 },
  ]);
});

test('opens a diagnostic at its saved source location without replacing pending YAML', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('button', { name: /ruby_blade\.yml/i }).click();
  await page.getByRole('tab', { name: 'yaml' }).click();

  const pendingYaml = (await page.getByLabel('raw YAML').inputValue()).replace(
    'Ruby Blade',
    'Pending YAML location draft',
  );
  await page.getByLabel('raw YAML').fill(pendingYaml);
  await expect(page.getByLabel('raw YAML')).toHaveValue(pendingYaml);

  await page.getByRole('button', { name: /^Problems/ }).click();
  await page
    .getByRole('button', {
      name: /Open items\/ruby_blade\.yml in Files at C:\\server\\plugins\\Materione/i,
    })
    .click();

  const savedSource = page.getByLabel('Saved source');
  await expect(savedSource).toBeVisible();
  await expect(savedSource).toHaveJSProperty('readOnly', true);
  const savedText = await savedSource.inputValue();
  expect(savedText).toContain('Ruby Blade');
  expect(savedText).not.toContain('Pending YAML location draft');
  await expect(page.getByLabel('raw YAML')).toHaveCount(1);
  await expect(page.getByLabel('raw YAML')).toHaveValue(pendingYaml);

  await expect
    .poll(() =>
      savedSource.evaluate((element) => {
        const area = element as HTMLTextAreaElement;
        return area.value.slice(area.selectionStart, area.selectionEnd);
      }),
    )
    .toBe('material: DIAMOND_SWORD');
  expect(await readWrites(page)).toHaveLength(0);
});

test('does not run a hidden Studio save shortcut, but saves when the Studio is active', async ({ page }) => {
  await openFixture(page);
  await openStudioFromContent(page);
  await editDisplayName(page, 'Draft preserved while hidden');

  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.keyboard.press('Control+s');
  expect(await readWrites(page)).toHaveLength(0);

  await page.getByRole('button', { name: 'Content' }).click();
  await page.keyboard.press('Control+s');
  await expect.poll(async () => (await readWrites(page)).length).toBe(1);
  const [write] = await readWrites(page);
  expect(write?.op).toBe('authoring.setFields');
  expect(write?.payload.edits).toEqual([
    { field: ['ruby_blade', 'display_name'], value: 'Draft preserved while hidden' },
  ]);
});

test('texture and model apply commit only their asset/attachment fields, preserving a staged name', async ({
  page,
}) => {
  await stubBlockbenchBoundary(page);
  await openFixture(page);
  await openStudioFromContent(page);
  await editDisplayName(page, 'Name remains a draft');

  await page.getByRole('button', { name: 'Draw', exact: true }).click();
  const canvas = page.locator('canvas');
  await expect(canvas).toBeVisible();
  await canvas.click();
  const textureSave = page.getByRole('button', { name: 'Save & apply' }).last();
  await expect(textureSave).toBeEnabled();
  await textureSave.click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(2);

  let writes = await readWrites(page);
  expect(writes.map((write) => write.op)).toEqual(['assets.put', 'authoring.setFields']);
  expect(writes[0]?.payload.path).toBe('assets/textures/items/ruby_blade.png');
  expect(writes[1]?.payload.edits).toEqual([
    { field: ['ruby_blade', 'texture'], value: 'items/ruby_blade' },
  ]);
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page.getByLabel('Display name')).toHaveValue('Name remains a draft');

  await page.getByRole('button', { name: 'Edit in Blockbench' }).click();
  await expect(page.getByText('new model, texture attached', { exact: true })).toBeVisible();
  const modelSave = page.getByRole('button', { name: 'Save & apply' }).last();
  await expect(modelSave).toBeEnabled();
  await modelSave.click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(3);

  writes = await readWrites(page);
  expect(writes.map((write) => write.op)).toEqual([
    'assets.put',
    'authoring.setFields',
    'authoring.saveModel',
  ]);
  expect(writes[2]?.payload.field).toEqual(['ruby_blade', 'model']);
  expect(writes[2]?.payload.modelRef).toBe('fixture_pack:ruby_blade');
  await expect(page.getByLabel('Display name')).toHaveValue('Name remains a draft');
});

test('keeps a lost write outcome uncertain until reconnection/reconciliation', async ({ page }) => {
  await openFixture(page);
  await page.getByRole('button', { name: 'Files' }).click();
  await page.getByRole('button', { name: /ruby_blade\.yml/i }).click();
  await editDisplayName(page, 'Submitted before disconnect');
  await page.evaluate(() => window.__materioneTestControls?.disconnectAfterNextWrite());

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect(page.getByRole('button', { name: 'Reconcile server files' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Reconcile server files' })).toBeDisabled();
  await expect(page.getByText('Connection lost')).toBeVisible();

  const writes = await readWrites(page);
  expect(writes).toHaveLength(1);
  expect(writes[0]?.op).toBe('authoring.setFields');
  expect(writes[0]?.outcome).toBe('response-lost');
});

test('refuses a stale captured publication hash without applying a Studio draft', async ({ page }) => {
  await openFixture(page);
  await openStudioFromContent(page);
  await editDisplayName(page, 'Do not publish this unsaved name');
  await page.evaluate(() => window.__materioneTestControls?.setCandidateSha256('b'.repeat(64)));

  await page.getByRole('button', { name: 'Publish pack' }).click();
  await expect(page.getByText('candidate bbbbbbbb', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Publish pack' })).toBeEnabled();

  const writes = await readWrites(page);
  expect(writes).toHaveLength(1);
  expect(writes[0]?.op).toBe('delivery.publish');
  expect(writes[0]?.payload.expectedCandidateSha256).toBe('a'.repeat(64));
  expect(writes[0]?.outcome).toBe('rejected');
  const status = await page.evaluate(() => window.__materioneTestControls?.deliveryState());
  expect(status).toEqual({
    candidateSha256: 'b'.repeat(64),
    publishedSha256: '0'.repeat(64),
  });
  await expect(page.getByLabel('Display name')).toHaveValue('Do not publish this unsaved name');
});
