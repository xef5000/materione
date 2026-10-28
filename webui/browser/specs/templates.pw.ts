import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import '../contract';
import {
  blockCdn, documentOf, moduleRow, openModules, openResource, saveAndApply, selectModule, settingsPanel, writes,
} from './helpers/modules';

// Templates as a creation choice, not a field (issue #154). Everything runs on
// the deterministic ?modules fixture, whose `base_item` template mirrors the
// sample pack's: a shared base that sets several modules.
test.beforeEach(async ({ page }) => {
  await blockCdn(page);
});

async function openNewDialog(page: Page, tab: string, singular: string) {
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: tab }).click();
  await page.getByRole('button', { name: `New ${singular}` }).first().click();
  const dialog = page.getByRole('dialog', { name: `New ${singular}` });
  await expect(dialog).toBeVisible();
  return dialog;
}

test('Ruby Dagger from base_item: create, see the inherited modules, change a value, detach — YAML right at each step', async ({ page }) => {
  const pageErrors = await openModules(page);
  const dialog = await openNewDialog(page, 'Items', 'item');

  // The name comes first; "Start from" offers Blank, a template, or a copy.
  await dialog.getByRole('textbox', { name: 'item name' }).fill('Ruby Dagger');
  await expect(dialog.getByText('id: ruby_dagger')).toBeVisible();
  await expect(dialog.getByRole('radio', { name: 'Blank' })).toBeChecked();
  await dialog.getByRole('radio', { name: /A template/ }).check();

  // Cards say what each template sets, as the modules a user would pick.
  const card = dialog.getByRole('option', { name: /A base item/ });
  await expect(card).toContainText('base_item');
  await expect(card).toContainText('Base material');
  await expect(card).toContainText('Abilities');
  await expect(card).toContainText('Used by 1');
  await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled(); // nothing chosen yet
  await card.click();
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();

  // Step 1: the file is the link plus the name, and nothing copied from the template.
  const created = await documentOf(page, 'items/ruby_dagger.yml');
  expect(created?.parsed.ruby_dagger).toEqual({ template: 'base_item', display_name: 'Ruby Dagger' });
  expect(created?.raw).toContain('template: base_item');
  expect(created?.raw).not.toContain('material');
  expect((await writes(page)).map((write) => write.op)).toEqual(['authoring.createResource']);

  // The studio shows what it inherits: as modules, with the template named.
  await expect(page.locator('[data-based-on-button]')).toContainText('A base item');
  // Inherited modules are dashed rows marked Inherited; the template's value and its origin open in the panel.
  await expect(page.locator('[data-inherited-module="material"]')).toContainText('Inherited');
  await selectModule(page, 'Base material');
  await expect(settingsPanel(page)).toContainText('Inherited from base_item');
  await expect(settingsPanel(page)).toContainText('PAPER');
  // the model style is an option of the Appearance component, with its inheritance line
  await page.getByRole('button', { name: 'More appearance options' }).click();
  await expect(page.locator('[data-provenance="model_parent"]')).toContainText('Inherited from base_item: item/handheld');
  await page.getByRole('button', { name: 'More appearance options' }).click();
  await selectModule(page, 'Durability');
  await expect(page.locator('[data-inherited-module="mechanic:materione:durability#0"]')).toContainText('Inherited');
  await expect(settingsPanel(page)).toContainText('max 250');
  await expect(page.getByRole('button', { name: 'Template: A base item' })).toBeVisible();

  // Step 2: override one module; the save writes only that key, the link stays.
  await page.getByRole('button', { name: 'More appearance options' }).click();
  const style = page.getByRole('textbox', { name: 'Model style' });
  await style.fill('item/generated');
  await style.press('Tab'); // text fields commit on blur
  await saveAndApply(page, 2);
  const changed = await documentOf(page, 'items/ruby_dagger.yml');
  expect(changed?.parsed.ruby_dagger).toEqual({
    template: 'base_item', display_name: 'Ruby Dagger', model_parent: 'item/generated',
  });
  expect(changed?.raw).toContain('template: base_item');
  expect(changed?.raw).toContain('model_parent: item/generated');
  // the template itself is untouched
  expect((await documentOf(page, 'templates/base_item.yml'))?.parsed.base_item).toMatchObject({
    model_parent: 'item/handheld',
  });

  // Step 3: detach copies what is inherited, keeps the override and drops the link.
  await page.locator('[data-based-on-button]').click();
  await page.locator('[data-detach]').click();
  await expect(page.getByRole('button', { name: 'Template: No template' })).toBeVisible();
  const detached = await documentOf(page, 'items/ruby_dagger.yml');
  expect(detached?.parsed.ruby_dagger).toEqual({
    display_name: 'Ruby Dagger',
    model_parent: 'item/generated',
    material: 'PAPER',
    lore: ['Forged', 'Hard'],
    mechanics: [{ durability: { max: 250 } }],
  });
  expect(detached?.raw).not.toContain('template:');
  expect((await writes(page)).map((write) => write.op).slice(-1)).toEqual(['authoring.detachTemplate']);
  // the values are now this resource's own modules
  await expect(page.locator('[data-module="material"]')).toBeVisible();
  await expect(page.locator('[data-inherited-module]')).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('an inherited module is overridden from the settings panel, and Reset to inherited hands it back to the template', async ({ page }) => {
  const pageErrors = await openModules(page);
  // ruby_sword sets its own material; the template's PAPER is what Reset brings back.
  await openResource(page, 'Items', 'Ruby Sword');
  await selectModule(page, 'Base material');
  const panel = settingsPanel(page);
  await expect(panel).toContainText('Overrides base_item (PAPER)');
  await panel.getByRole('button', { name: 'Reset to inherited' }).click();

  // Reset stages a removal: the module becomes a dashed inherited row, and it stays selected.
  await expect(page.locator('[data-inherited-module="material"]')).toBeVisible();
  await expect(moduleRow(page, 'Base material')).toHaveAttribute('aria-current', 'true');
  await expect(panel).toContainText('Inherited from base_item');
  await expect(panel).toContainText('PAPER');
  expect(await writes(page)).toEqual([]);

  // Override here, in the panel, copies the template's value back into the resource.
  await panel.getByRole('button', { name: 'Override here' }).click();
  await expect(page.locator('[data-inherited-module]')).toHaveCount(0);
  await expect(page.locator('[data-module="material"]')).toBeVisible();
  await expect(panel).toContainText('Overrides base_item (PAPER)');

  // One edit: the later value replaces the staged removal, and the link stays.
  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{ field: ['ruby_sword', 'material'], value: 'PAPER' }]);
  expect((await documentOf(page, 'items/ruby_sword.yml'))?.parsed.ruby_sword)
    .toMatchObject({ template: 'base_item', material: 'PAPER' });
  expect(pageErrors).toEqual([]);
});

test('the New dialog filters templates to the type, searches them and works from the keyboard', async ({ page }) => {
  const pageErrors = await openModules(page);

  // Blocks have no templates in this pack: the choice is there but cannot be taken.
  const blocks = await openNewDialog(page, 'Blocks', 'block');
  await expect(blocks.getByRole('radio', { name: /A template/ })).toBeDisabled();
  await blocks.getByRole('button', { name: 'Cancel' }).click();

  const dialog = await openNewDialog(page, 'Items', 'item');
  await dialog.getByRole('textbox', { name: 'item name' }).fill('Keyboard Blade');
  await dialog.getByRole('radio', { name: /A template/ }).check();
  const search = dialog.getByRole('searchbox', { name: 'Search templates' });
  await search.fill('zzz');
  await expect(dialog.getByText(/Nothing matches/)).toBeVisible();
  // search matches what a template sets, not only its name
  await search.fill('abilit');
  await expect(dialog.getByRole('option')).toHaveCount(1);
  await search.fill('base');
  await search.press('ArrowDown');
  await search.press('Enter'); // picks the highlighted card and creates
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
  const created = await documentOf(page, 'items/keyboard_blade.yml');
  expect(created?.parsed.keyboard_blade).toEqual({ template: 'base_item', display_name: 'Keyboard Blade' });
  expect(pageErrors).toEqual([]);
});

test('an existing resource can be the starting point; the copy takes the new name', async ({ page }) => {
  const pageErrors = await openModules(page);
  const dialog = await openNewDialog(page, 'Items', 'item');
  await dialog.getByRole('textbox', { name: 'item name' }).fill('Ruby Twin');
  await dialog.getByRole('radio', { name: /An existing one/ }).check();
  await dialog.getByRole('searchbox').fill('ember');
  await dialog.getByRole('option', { name: /Ember Charm/ }).click();
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
  const copy = await documentOf(page, 'items/ruby_twin.yml');
  expect(copy?.parsed.ruby_twin).toEqual({ display_name: 'Ruby Twin', material: 'BLAZE_ROD' });
  // the original is unchanged
  expect((await documentOf(page, 'items/ember_charm.yml'))?.parsed.ember_charm)
    .toEqual({ display_name: 'Ember Charm', material: 'BLAZE_ROD' });
  expect(pageErrors).toEqual([]);
});

test('Save as template extracts the chosen fields into a new template that opens in its own studio', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Items', 'Ember Charm');

  await page.locator('[data-save-as-template]').click();
  const dialog = page.getByRole('dialog', { name: 'Save as template' });
  await dialog.getByRole('textbox', { name: 'Template name' }).fill('Ember Base');
  // everything the resource sets is offered; its own name is not preselected
  await expect(dialog.locator('input[data-field="material"]')).toBeChecked();
  await expect(dialog.locator('input[data-field="display_name"]')).not.toBeChecked();
  await dialog.getByRole('button', { name: 'Save template' }).click();

  const saved = await documentOf(page, 'templates/ember_base.yml');
  expect(saved?.parsed.ember_base).toEqual({ material: 'BLAZE_ROD' });
  expect(saved?.raw).toContain('ember_base:');
  // the source keeps its own copy
  expect((await documentOf(page, 'items/ember_charm.yml'))?.parsed.ember_charm)
    .toEqual({ display_name: 'Ember Charm', material: 'BLAZE_ROD' });
  // the new template is open, edited as the type it serves
  await expect(page.locator('[data-template-badge]')).toContainText('Template for Item');
  await expect(page.locator('[data-used-by]')).toContainText('Not used yet');
  await expect(page.locator('[data-module="material"]')).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('Templates is a Content tab: create one from scratch, edit it with the type\'s modules, see who uses it', async ({ page }) => {
  const pageErrors = await openModules(page);

  // From scratch, for blocks: the starter field comes from the block type.
  const dialog = await openNewDialog(page, 'Templates', 'template');
  await dialog.getByRole('textbox', { name: 'template name' }).fill('Lamp Base');
  await dialog.getByLabel('Used for').selectOption({ label: 'Block' });
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(page.locator('[data-template-badge]')).toContainText('Template for Block');
  expect((await documentOf(page, 'templates/lamp_base.yml'))?.parsed.lamp_base).toEqual({ technique: 'note_block' });
  await page.getByRole('button', { name: /back to templates/i }).click();

  // The catalog says what each template is for and who uses it.
  const row = page.getByRole('option', { name: /A base item/ });
  await expect(row).toContainText('Item');
  await expect(row).toContainText('used by 1');
  await row.dblclick();
  await expect(page.locator('[data-template-badge]')).toContainText('Template for Item');
  await expect(page.locator('[data-used-by]')).toContainText('Used by 1');
  await expect(page.locator('[data-used-by]').getByRole('button', { name: 'ruby_sword' })).toBeVisible();

  // Edited with the item's own modules; the save is one lossless field edit.
  await page.getByRole('button', { name: 'More appearance options' }).click();
  const style = page.getByRole('textbox', { name: 'Model style' });
  await style.fill('item/generated');
  await style.press('Tab'); // text fields commit on blur
  const save = await saveAndApply(page, 2);
  expect(save.payload.edits).toEqual([{ field: ['base_item', 'model_parent'], value: 'item/generated' }]);

  // A user opens in one click, and the studio explains the link from its side.
  await page.locator('[data-used-by]').getByRole('button', { name: 'ruby_sword' }).click();
  await expect(page.locator('[data-based-on-button]')).toContainText('A base item');
  expect(pageErrors).toEqual([]);
});

test('no studio shows a raw template field: the link is the template control in the header', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Items', 'Ruby Sword');
  await expect(page.locator('[data-based-on]')).toBeVisible();
  await expect(page.getByLabel('Template', { exact: true })).toHaveCount(0);
  await expect(page.locator('[data-field="template"]')).toHaveCount(0);
  const advanced = page.locator('details.advanced');
  if (await advanced.count()) await advanced.locator('summary').click();
  await expect(page.getByLabel('Template', { exact: true })).toHaveCount(0);
  // staying honest: the saved key is not reported as unreachable either
  await expect(page.getByText(/only editable in Advanced Files \(.*template/)).toHaveCount(0);

  await page.getByRole('button', { name: /^back to/ }).click();
  await openResource(page, 'Blocks', 'Amethyst Lamp');
  await expect(page.locator('[data-based-on]')).toBeVisible();
  await expect(page.getByLabel('Template', { exact: true })).toHaveCount(0);

  // the classic (non-module) studio of the default fixture too
  await page.goto('/browser/fixture.html');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('option', { name: /Ruby Blade/i }).dblclick();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
  await expect(page.locator('[data-based-on]')).toBeVisible();
  await expect(page.getByLabel('Template', { exact: true })).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('the template chip switches the template without leaving the studio; Save & apply writes it', async ({ page }) => {
  const pageErrors = await openModules(page);
  await openResource(page, 'Items', 'Ember Charm');
  await expect(page.locator('[data-based-on-button]')).toContainText('No template');
  // a resource with no link cannot be "detached"
  await page.locator('[data-based-on-button]').click();
  await expect(page.locator('[data-detach]')).toHaveCount(0);
  await page.getByRole('searchbox', { name: 'Search templates' }).fill('base');
  await page.getByRole('option', { name: /A base item/ }).getByRole('button').click();
  // the chip shows the staged choice; nothing is written until Save & apply
  await expect(page.getByRole('button', { name: 'Template: A base item' })).toBeVisible();
  const save = await saveAndApply(page, 1);
  expect(save.payload.edits).toEqual([{ field: ['ember_charm', 'template'], value: 'base_item' }]);
  await page.getByRole('button', { name: 'More appearance options' }).click();
  await expect(page.locator('[data-provenance="model_parent"]')).toContainText('item/handheld');
  expect((await documentOf(page, 'items/ember_charm.yml'))?.raw).toContain('template: base_item');
  expect(pageErrors).toEqual([]);
});
