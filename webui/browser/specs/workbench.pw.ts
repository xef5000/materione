import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';

// Vanilla item icons load from jsDelivr at runtime; keep these specs offline
// and deterministic (vanilla-icons.pw.ts serves a fake manifest instead).
test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
});

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

async function openFixture(page: Page, options: { summaryError?: boolean } = {}): Promise<void> {
  const query = options.summaryError ? '?summaryError=1' : '';
  await page.goto(`/browser/fixture.html${query}`);
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
}

async function openStudioFromContent(page: Page, resourceId = 'ruby_blade'): Promise<void> {
  const names: Record<string, string> = {
    ruby_blade: 'Ruby Blade',
    ruby_a: 'Crimson Jewel',
    ruby_b: 'Ruby B',
  };
  const name = names[resourceId] ?? resourceId;
  await page.getByRole('option', { name: new RegExp(name, 'i') }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

async function openBladeInFiles(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.getByRole('button', { name: /ruby_blade\.yml/i }).click();
  await expect(page.getByLabel('Display name', { exact: true })).toBeEditable();
}

async function editDisplayName(page: Page, value: string): Promise<void> {
  const field = page.getByLabel('Display name', { exact: true });
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

async function readCalls(page: Page) {
  return page.evaluate(() => {
    const controls = window.__materioneTestControls;
    if (!controls) throw new Error('Browser fixture controls are missing.');
    return controls.calls();
  });
}

async function chooseMaterialByKeyboard(page: Page, label: string, query: string): Promise<void> {
  const material = page.getByRole('combobox', { name: label });
  await material.fill(query);
  await expect(page.getByRole('listbox', { name: `${label} choices` })).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
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

  await page.setViewportSize({ width: 1365, height: 900 });
  await openStudioFromContent(page);
  await page.screenshot({
    path: testInfo.outputPath('item-workbench-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.setViewportSize({ width: 1024, height: 768 });
  await page.screenshot({
    path: testInfo.outputPath('item-workbench-narrow-desktop.png'),
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

test('uses pack-scoped summary pages and searches human names without reading each document', async ({
  page,
}) => {
  await openFixture(page);

  const calls = await readCalls(page);
  const summaryCalls = calls.filter((call) => call.op === 'authoring.summaries');
  expect(summaryCalls).toEqual([
    { op: 'authoring.summaries', payload: { pack: 'fixture_pack' } },
  ]);
  expect(calls.filter((call) => call.op === 'browse.get')).toHaveLength(0);
  expect(await readWrites(page)).toHaveLength(0);

  const search = page.getByPlaceholder('search items…');
  await search.fill('Crimson Jewel');
  const result = page.getByRole('option', { name: /Crimson Jewel/ });
  await expect(result).toBeVisible();
  await expect(page.getByRole('option', { name: /Ruby Blade/ })).toHaveCount(0);
  await expect(result.locator('.summary')).toHaveText('Blaze Rod');
  await expect(result.locator('.health')).toHaveText('unknown');
  await expect(
    page.getByRole('img', { name: 'Crimson Jewel: no preview available' }),
  ).toBeVisible();
});

test('keeps list and grid preferences per type and hides technical columns by default', async ({ page }) => {
  await openFixture(page);

  const listLayout = page.getByRole('button', { name: 'Dense visual list' });
  const gridLayout = page.getByRole('button', { name: 'Compact grid' });
  await expect(gridLayout).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'ID / path' }).click();
  await expect(page.getByText('ruby_blade', { exact: true })).toBeVisible();
  await expect(page.getByText('items/ruby_blade.yml', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'ID / path' }).click();
  await expect(page.getByText('ruby_blade', { exact: true })).toHaveCount(0);

  await listLayout.click();
  await expect(listLayout).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Blocks', exact: true }).click();
  await expect(page.getByRole('listbox', { name: 'Blocks in blocks' })).toBeVisible();
  await expect(gridLayout).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Items', exact: true }).click();
  await expect(listLayout).toHaveAttribute('aria-pressed', 'true');
});

test('opens the selected resource root when several items share one document', async ({ page }) => {
  await openFixture(page);

  await page.getByPlaceholder('search items…').fill('Ruby B');
  const result = page.getByRole('option').filter({
    has: page.getByText('Ruby B', { exact: true }),
  });
  await expect(result).toBeVisible();
  await result.dblclick();
  await expect(page.locator('.studio header .meta')).toContainText('ruby_b');
  await expect(page.getByLabel('Display name', { exact: true })).toHaveValue('Ruby B');
  const materialField = page.locator('[data-field="material"]');
  await expect(materialField.locator('.current code')).toHaveText('CUSTOM_FUTURE_MATERIAL');
  await expect(materialField).toContainText(/current ID is not in this server's material list/i);
  const loreField = page.locator('[data-field="lore"]');
  await expect(loreField.getByText(/cannot be edited as plain lines/i)).toBeVisible();
  await expect(loreField.getByRole('textbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();
  expect(await readWrites(page)).toHaveLength(0);
});

test('refuses to flatten non-string lore entries and leaves their editor unavailable', async ({ page }) => {
  await openFixture(page);
  await page.getByPlaceholder('search items…').fill('Malformed Lore');
  await page.getByRole('option', { name: /Malformed Lore/ }).dblclick();

  const loreField = page.locator('[data-field="lore"]');
  await expect(loreField.getByText(/cannot be edited as plain lines/i)).toBeVisible();
  await expect(loreField.getByRole('textbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Edit item lore' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();
  expect(await readWrites(page)).toHaveLength(0);
});

test('keeps the item preview in place while the long form scrolls independently', async ({ page }) => {
  await openFixture(page);
  await page.setViewportSize({ width: 1280, height: 540 });
  await openStudioFromContent(page);

  const preview = page.locator('.studio .preview');
  const form = page.locator('.studio .form');
  await expect(page.getByRole('region', { name: 'Item preview' })).toBeVisible();
  await expect(preview).toBeVisible();
  await expect.poll(() =>
    form.evaluate((element) => element.scrollHeight > element.clientHeight),
  ).toBe(true);
  const before = await preview.boundingBox();
  const previewScrollBefore = await preview.evaluate((element) => element.scrollTop);
  expect(before).not.toBeNull();

  await form.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
  });
  await expect.poll(() => form.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);

  const after = await preview.boundingBox();
  expect(after).not.toBeNull();
  expect(after!.x).toBe(before!.x);
  expect(after!.y).toBe(before!.y);
  expect(await preview.evaluate((element) => element.scrollTop)).toBe(previewScrollBefore);
  expect(await readWrites(page)).toHaveLength(0);
});

test('falls back to humanised resource IDs and unknown health when summary reads fail', async ({ page }) => {
  await openFixture(page, { summaryError: true });

  await expect(page.getByRole('status')).toContainText('Some resource summaries are unavailable');
  const fallback = page.getByRole('option', { name: /Ruby blade/ });
  await expect(fallback.locator('.summary')).toHaveText('Summary unavailable');
  await expect(fallback.locator('.health')).toHaveText('unknown');
  await expect(page.getByRole('img', { name: 'Ruby blade: no preview available' })).toBeVisible();
  expect((await readCalls(page)).filter((call) => call.op === 'browse.get')).toHaveLength(0);
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
  await page.getByLabel('Display name', { exact: true }).fill('Staged batch name');
  await chooseMaterialByKeyboard(page, 'Base material', 'blaze rod');
  await expect(page.getByRole('combobox', { name: 'Base material' })).toHaveValue('Blaze Rod');
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

test('searches authoritative material labels by keyboard and waits for explicit apply', async ({ page }) => {
  await openFixture(page);
  await openStudioFromContent(page);

  const material = page.getByRole('combobox', { name: 'Base material' });
  await material.fill('blaze rod');
  await expect(
    page.getByRole('option', { name: /Blaze Rod\s+BLAZE_ROD/ }),
  ).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(material).toHaveValue('Diamond Sword');
  expect(await readWrites(page)).toHaveLength(0);

  await material.fill('blaze rod');
  await expect(
    page.getByRole('option', { name: /Blaze Rod\s+BLAZE_ROD/ }),
  ).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect(material).toHaveValue('Blaze Rod');
  expect(await readWrites(page)).toHaveLength(0);
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeEnabled();

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(1);
  expect((await readWrites(page))[0]?.payload.edits).toEqual([
    { field: ['ruby_blade', 'material'], value: 'BLAZE_ROD' },
  ]);
});

test('keeps an untouched item Studio save disabled and mutation-free', async ({ page }) => {
  await openFixture(page);
  await openStudioFromContent(page);

  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeDisabled();
  expect(await readWrites(page)).toHaveLength(0);
});

test('preview name and lore controls focus their corresponding editor fields', async ({ page }) => {
  await openFixture(page);
  await openStudioFromContent(page);

  const name = page.getByLabel('Display name', { exact: true });
  const lore = page.getByLabel('Lore', { exact: true });
  await page.getByRole('button', { name: 'Edit item name' }).click();
  await expect(name).toBeFocused();
  await page.getByRole('button', { name: 'Edit item lore' }).click();
  await expect(lore).toBeFocused();
});

test('previews formatted name and lore drafts as safe text and formats lore independently per line', async ({
  page,
}) => {
  await openFixture(page);
  await openStudioFromContent(page);

  const name = page.getByLabel('Display name', { exact: true });
  const lore = page.getByLabel('Lore', { exact: true });
  const previewName = page.getByRole('button', { name: 'Edit item name' });
  const previewLore = page.getByRole('button', { name: 'Edit item lore' });

  await name.fill('Ruby Sword');
  await name.press('Control+A');
  await page.getByLabel('Display name color').selectOption('a');
  await expect(name).toHaveValue('&aRuby Sword');
  await page.getByRole('button', { name: 'Display name: Bold' }).click();
  await expect(name).toHaveValue('&a&lRuby Sword');
  await expect(previewName.locator('.bold')).toHaveText('Ruby Sword');

  const unsafeLookingText = '&aRuby &lBlade <img src=x onerror=alert(1)> &z';
  await name.fill(unsafeLookingText);
  await expect(previewName).toContainText('Ruby Blade <img src=x onerror=alert(1)> &z');
  await expect(previewName.locator('img')).toHaveCount(0);
  await expect(previewName.locator('.bold')).toHaveText('Blade <img src=x onerror=alert(1)> &z');

  const loreDraft = '&cBurns with fire\n\n&9  keeps spaces  \n';
  await lore.fill(loreDraft);
  await lore.evaluate((element) => {
    const textarea = element as HTMLTextAreaElement;
    textarea.setSelectionRange(0, textarea.value.length);
  });
  await page.getByRole('button', { name: 'Lore: Bold' }).click();
  const formattedLore = '&l&c&lBurns with fire\n\n&l&9&l  keeps spaces  \n';
  await expect(lore).toHaveValue(formattedLore);
  await expect(previewLore.locator('.lore-line')).toHaveCount(4);
  await expect(previewLore.locator('.lore-line').nth(0).locator('.bold')).toHaveText('Burns with fire');
  await expect(previewLore.locator('.lore-line').nth(2).locator('.bold')).toHaveText('  keeps spaces  ');
  expect(await readWrites(page)).toHaveLength(0);

  await page.getByRole('button', { name: 'Save & apply' }).click();
  await expect.poll(async () => (await readWrites(page)).length).toBe(1);
  expect((await readWrites(page))[0]?.payload.edits).toEqual([
    { field: ['ruby_blade', 'display_name'], value: unsafeLookingText },
    { field: ['ruby_blade', 'lore'], value: ['&l&c&lBurns with fire', '', '&l&9&l  keeps spaces  ', ''] },
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
  await expect(page.getByLabel('Display name', { exact: true })).toHaveValue('Name remains a draft');

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
  await expect(page.getByLabel('Display name', { exact: true })).toHaveValue('Name remains a draft');
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
  await expect(page.getByText(
    'The save outcome is unknown. Your draft was kept. Reconnect and reconcile before applying anything again.',
    { exact: true },
  )).toBeVisible();
  await expect(page.getByLabel('Display name', { exact: true })).toHaveValue('Submitted before disconnect');
  await expect(page.getByLabel('Display name', { exact: true })).toBeDisabled();

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
  await expect(page.getByLabel('Display name', { exact: true })).toHaveValue('Do not publish this unsaved name');
});
