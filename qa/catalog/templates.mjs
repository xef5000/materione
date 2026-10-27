// Templates as a creation choice, not a field (issue #154). Real local App through the
// normal trust flow, against the `sample` pack and its `base_item` template (which
// `ruby_sword` already inherits). Each test removes what it creates, so the sample pack
// ends exactly as it started.

import { test } from './_lib.mjs';

// Same identity pass the other editor entries ask for (docs/architecture/web.md §6.1).
const IDENTITY_EYES = ' Also: the Materione identity (docs/architecture/web.md §6.1) - navy surfaces, the orange accent only on the primary action and focus, Inter type, and text and focus that keep WCAG AA contrast.';

const TEMPLATE_COVERS = [
  'webui/src/App.svelte',
  'webui/src/lib/protocol/session.ts',
  'webui/src/lib/components/Workbench.svelte',
  'webui/src/lib/views/ContentView.svelte',
  'webui/src/lib/state.svelte.ts',
  'webui/src/lib/drafts.svelte.ts',
  'webui/src/lib/api.ts',
  'webui/src/lib/browser.ts',
  'webui/src/lib/modules.ts',
  'webui/src/lib/templates.ts',
  'webui/src/lib/components/NewResourceDialog.svelte',
  'webui/src/lib/components/SaveAsTemplateDialog.svelte',
  'webui/src/lib/studio/Studio.svelte',
  'webui/src/lib/studio/BasedOn.svelte',
  'webui/src/lib/studio/ModulesArea.svelte',
  'webui/test-support/qa-browser.mjs',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
  'core/src/main/java/dev/materione/core/authoring/ResourceSummaryComputer.java',
  'core/src/main/java/dev/materione/core/pipeline/TemplateResolver.java',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'fixtures/packs/sample/templates/**',
  'fixtures/packs/sample/items/ruby_sword.yml',
];

async function openSampleContent(page) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
}

async function openTab(page, tab) {
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: tab }).click();
}

/** Deletes the catalog row that matches `name` (confirm accepted), leaving the pack as found. */
async function deleteRow(page, tab, name) {
  await page.getByRole('button', { name: /^back to/ }).click();
  await openTab(page, tab);
  await page.locator('input.search').fill(name);
  const row = page.getByRole('option', { name: new RegExp(name) });
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  await row.click();
  page.once('dialog', (dialog) => dialog.accept());
  await row.press('Delete');
  await row.waitFor({ state: 'detached', timeout: 20_000 });
  await page.locator('input.search').fill('');
}

export default [
  test({
    id: 'templates.browser-create-from-template',
    title: 'sample: a new item is created from base_item in the New dialog, shows its inherited modules, takes an override and detaches',
    covers: TEMPLATE_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleContent(page);
      await openTab(page, 'Items');
      await page.getByRole('button', { name: 'New item' }).first().click();

      const dialog = page.getByRole('dialog', { name: 'New item' });
      await dialog.getByRole('textbox', { name: 'item name' }).fill('QA Ruby Dagger');
      await dialog.getByRole('radio', { name: /A template/ }).check();
      const card = dialog.getByRole('option', { name: /Base item/ });
      await card.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await card.innerText()).toContain('base_item');
      await card.click();
      await dialog.getByRole('button', { name: 'Create' }).click();

      // The studio shows the link as "Based on", never as a raw field.
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible', timeout: 20_000 });
      const based = studio.locator('[data-based-on-button]');
      await based.waitFor({ state: 'visible' });
      ctx.expect(await based.innerText()).toContain('Base item');
      ctx.expect(await studio.getByLabel('Template', { exact: true }).count()).toBe(0);
      // base_item sets the material: it arrives as an inherited module, not a copy.
      const inherited = studio.locator('[data-inherited-module="material"]');
      await inherited.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await inherited.innerText()).toContain('Inherited from base_item');

      // Override it here, apply, then detach: the template's values become the item's own.
      await inherited.getByRole('button', { name: 'Override here' }).click();
      const save = studio.getByRole('button', { name: 'Save & apply' });
      await save.click();
      const deadline = Date.now() + 20_000;
      while (Date.now() < deadline && (await save.isEnabled())) await page.waitForTimeout(250);
      ctx.expect(await studio.locator('[data-module="material"]').count()).toBe(1);

      await based.click();
      await page.locator('[data-detach]').click();
      await studio.getByText('Nothing is inherited; every value is set here.').waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await studio.locator('[data-inherited-module]').count()).toBe(0);

      await deleteRow(page, 'Items', 'QA Ruby Dagger');
    },
  }),

  test({
    id: 'templates.browser-save-as-template',
    title: 'sample:ruby_sword is saved as a template; the new template opens in the item studio and lists no users, then is removed',
    covers: TEMPLATE_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleContent(page);
      await openTab(page, 'Items');
      await page.locator('input.search').fill('Ruby Sword');
      const row = page.getByRole('option', { name: /Ruby Sword/ });
      await row.waitFor({ state: 'visible', timeout: 20_000 });
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });

      await studio.locator('[data-save-as-template]').click();
      const dialog = page.getByRole('dialog', { name: 'Save as template' });
      await dialog.getByRole('textbox', { name: 'Template name' }).fill('qa sword base');
      // Everything the sword sets is offered; its own name is not preselected.
      await dialog.locator('input[data-field="material"]').waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await dialog.locator('input[data-field="display_name"]').isChecked()).toBe(false);
      await dialog.getByRole('button', { name: 'Save template' }).click();

      const badge = page.locator('[data-template-badge]');
      await badge.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await badge.innerText()).toContain('Template for Item');
      ctx.expect(await page.locator('[data-used-by]').innerText()).toContain('Not used yet');

      await deleteRow(page, 'Templates', 'Qa sword base');
    },
  }),

  test({
    id: 'templates.browser-templates-tab',
    title: 'the Templates tab lists sample:base_item with what it is for and that ruby_sword uses it',
    covers: TEMPLATE_COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleContent(page);
      await openTab(page, 'Templates');
      const row = page.getByRole('option', { name: /Base item/ });
      await row.waitFor({ state: 'visible', timeout: 20_000 });
      const text = await row.innerText();
      ctx.expect(text).toContain('Item');
      ctx.expect(text).toMatch(/used by \d+/);
      await row.dblclick();
      const used = page.locator('[data-used-by]');
      await used.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await used.innerText()).toContain('ruby_sword');
    },
  }),

  test({
    id: 'templates.browser-new-dialog-visual',
    title: 'the New dialog offers Blank / A template / An existing one with template cards, and the studio shows the Based on bar and inherited modules',
    mode: 'visual',
    dependsOn: ['templates.browser-create-from-template'],
    covers: TEMPLATE_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleContent(page);
      await openTab(page, 'Items');
      await page.getByRole('button', { name: 'New item' }).first().click();
      const dialog = page.getByRole('dialog', { name: 'New item' });
      await dialog.getByRole('textbox', { name: 'item name' }).fill('Ruby Dagger');
      await dialog.getByRole('radio', { name: /A template/ }).check();
      await dialog.getByRole('option', { name: /Base item/ }).waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('new-dialog-templates', '.scrim');
      await dialog.getByRole('button', { name: 'Cancel' }).click();

      await page.locator('input.search').fill('Ruby Sword');
      await page.getByRole('option', { name: /Ruby Sword/ }).dblclick();
      const studio = page.locator('.studio');
      await studio.locator('[data-based-on-button]').waitFor({ state: 'visible', timeout: 20_000 });
      await studio.locator('[data-based-on-button]').click();
      await ctx.browser.browserShot('based-on-menu', '.studio');
      ctx.needsEyes(
        'new-dialog-templates shows the name field, the Start from pills (Blank, A template with a count, An existing one), a search box and one card per template with a preview icon, its name and id, accent chips for the modules it sets and "Used by N"; based-on-menu shows the Based on bar with a searchable template menu plus Open template and Detach actions, the inherited-from chain beside it, and dashed inherited module cards below' + IDENTITY_EYES,
      );
    },
  }),
];
