// Per-type display + modules studios (issue #153, building on the item studio of
// issue #152; since issue #174 a module's settings open in the settings panel beside
// the module rows): every content type is a display card plus module rows derived
// from the registry self-description, with a purpose-built smart input where the
// outcome has one (item chips, the block-scale spatial picture, ranges, ...).
// Real local App through the normal trust flow, against the `sample` pack; each
// test leaves the pack exactly as it found it (add a module, save, remove it).

import { test } from './_lib.mjs';

// Same identity pass the item studio entries ask for (docs/architecture/web.md §6.1).
const IDENTITY_EYES = ' Also: the Materione identity (docs/architecture/web.md §6.1) - navy surfaces, the orange accent only on the primary action and focus, Inter type, and text and focus that keep WCAG AA contrast.';

const MODULE_COVERS = [
  'webui/src/App.svelte',
  'webui/src/lib/protocol/session.ts',
  'webui/src/lib/components/Workbench.svelte',
  'webui/src/lib/views/ContentView.svelte',
  'webui/src/lib/state.svelte.ts',
  'webui/src/lib/drafts.svelte.ts',
  'webui/src/lib/api.ts',
  'webui/src/lib/modules.ts',
  'webui/src/lib/items.ts',
  'webui/src/lib/spatial.ts',
  'webui/src/lib/components/icons.ts',
  'webui/src/lib/studio/Studio.svelte',
  'webui/src/lib/studio/Field.svelte',
  'webui/src/lib/studio/ModulesArea.svelte',
  'webui/src/lib/studio/ModulePicker.svelte',
  'webui/src/lib/studio/StructuredList.svelte',
  'webui/src/lib/studio/ItemChips.svelte',
  'webui/src/lib/studio/ItemPicker.svelte',
  'webui/src/lib/studio/ItemIcon.svelte',
  'webui/test-support/qa-browser.mjs',
  'api/src/main/java/dev/materione/api/registry/ParameterSchema.java',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
];
const BLOCK_COVERS = [
  ...MODULE_COVERS,
  'content/src/main/java/dev/materione/content/blocks/BlockType.java',
  'fixtures/packs/sample/blocks/**',
];
const FURNITURE_COVERS = [
  ...MODULE_COVERS,
  'webui/src/lib/studio/SpatialEditor.svelte',
  'content/src/main/java/dev/materione/content/furniture/FurnitureType.java',
  'fixtures/packs/sample/furnitures/**',
];

/** Opens a sample-pack resource of a type from the Content view's catalog. */
async function openSampleResource(page, tab, name) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: tab }).click();
  await page.locator('input.search').fill(name);
  const row = page.getByRole('option', { name: new RegExp(name) });
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  await row.dblclick();
  const studio = page.locator('.studio');
  await studio.waitFor({ state: 'visible' });
  return studio;
}

/** Waits for the studio's Save & apply to settle (disabled again = clean draft). */
async function waitForSaved(page) {
  const save = page.getByRole('button', { name: 'Save & apply' });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (!(await save.isEnabled())) return;
    await page.waitForTimeout(250);
  }
  throw new Error('Save & apply never settled back to a clean state');
}

export default [
  test({
    id: 'modules.browser-block-modules',
    title: 'sample:amethyst_lamp edits through a display card plus block modules; drops chips add and remove losslessly',
    covers: BLOCK_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Blocks', 'Amethyst Lamp');

      const display = studio.getByRole('article', { name: 'Display' });
      await display.getByRole('textbox', { name: 'Display name' }).waitFor({ state: 'visible' });

      // The registry's words, not the file's: "Shape", not `technique`.
      const shape = studio.locator('[data-module="technique"]');
      await shape.waitFor({ state: 'visible' });
      ctx.expect(await shape.innerText()).toContain('Shape');
      ctx.expect(await studio.locator('[data-module="blast_resistant"]').innerText())
        .toContain('Explosion resistant');
      const placedBy = studio.locator('[data-module="item"]');
      ctx.expect(await placedBy.innerText()).toContain('Placed by item');
      ctx.expect(await studio.locator('[data-module="behavior"]').count()).toBe(1);

      // Add Drops through the picker (it is selected, so its settings open in the panel), then an item chip from the item picker.
      await studio.getByRole('button', { name: 'Add module' }).click();
      const picker = page.getByRole('dialog', { name: 'Add a module' });
      await picker.getByLabel('Search modules').fill('drops');
      await picker.getByLabel('Search modules').press('Enter');
      const drops = studio.getByRole('region', { name: 'Module settings' });
      await drops.getByRole('button', { name: 'Add item' }).click();
      await page.getByRole('combobox', { name: 'Search items for Add item' }).fill('diamond');
      await page.getByRole('option', { name: /^Diamond/ }).first().waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByRole('combobox', { name: 'Search items for Add item' }).press('Enter');
      await drops.locator('[data-item="minecraft:diamond"]').waitFor({ state: 'visible' });
      await drops.getByRole('button', { name: 'More Diamond' }).click();
      ctx.expect(await drops.locator('[data-item="minecraft:diamond"] output').innerText()).toBe('2');

      const save = studio.getByRole('button', { name: 'Save & apply' });
      await save.click();
      await waitForSaved(page);
      ctx.expect(await drops.locator('[data-item="minecraft:diamond"] output').innerText()).toBe('2');

      // Remove it again: the sample block ends exactly as it started.
      await studio.locator('[data-module="drops"]').getByRole('button', { name: 'Remove Drops' }).click();
      await save.click();
      await waitForSaved(page);
      ctx.expect(await studio.locator('[data-module="drops"]').count()).toBe(0);
      ctx.expect(await studio.locator('[data-module="blast_resistant"]').count()).toBe(1);
    },
  }),

  test({
    id: 'modules.browser-block-modules-visual',
    title: 'the block studio reads as a display card plus Shape, Drops and world-policy rows, with the selected module in a settings panel',
    mode: 'visual',
    dependsOn: ['modules.browser-block-modules'],
    covers: BLOCK_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Blocks', 'Amethyst Lamp');
      await studio.locator('[data-module="blast_resistant"]').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('block-modules-studio', '.studio');
      await studio.getByRole('button', { name: 'Add module' }).click();
      await page.getByRole('dialog', { name: 'Add a module' }).getByRole('option').first()
        .waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('block-modules-picker', '.scrim');
      ctx.needsEyes(
        'block-modules-studio shows the display card (name, texture/model slots, preview) then one compact row per module, each with its Lucide icon and no controls inside; the settings panel on the right holds the controls of the selected module (Explosion resistant as a checkbox, Placed by item as an item chip with the Ruby icon and an x, When... do...); block-modules-picker lists Shape, Drops, Explosion resistant, When pushed by pistons, Placed by item and When... do... with one-line descriptions, grouped under Appearance, Gameplay and Behaviour chips' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'modules.browser-furniture-modules',
    title: 'sample:chair edits seats, hitbox and light on a block-scale picture; rotation reads as labelled snaps; storage has slots and a range',
    covers: FURNITURE_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Furniture', 'Chair');

      // The Seats row opens its picture in the settings panel.
      await studio.locator('[data-module="seats"]').waitFor({ state: 'visible', timeout: 20_000 });
      await studio.getByRole('button', { name: 'Seats', exact: true }).click();
      const panel = studio.getByRole('region', { name: 'Module settings' });
      const front = panel.getByRole('group', { name: 'Seats, front view' });
      const handle = front.getByRole('button', { name: /^Seats 1 at x 0, y 0.3, z 0/ });
      await handle.waitFor({ state: 'visible' });

      // Arrow keys move a handle by 5 cm and rewrite only that row's offset.
      await handle.focus();
      await handle.press('ArrowRight');
      await front.getByRole('button', { name: /^Seats 1 at x 0.05, y 0.3, z 0/ }).waitFor({ state: 'visible' });
      await front.getByRole('button', { name: /^Seats 1/ }).press('ArrowLeft');
      await front.getByRole('button', { name: /^Seats 1 at x 0, y 0.3, z 0/ }).waitFor({ state: 'visible' });

      // Rotation is a set of labelled numbers, not a free number box.
      await studio.locator('[data-module="rotation"] [data-row-key]').click();
      ctx.expect(await panel.getByRole('radio', { name: '90°' }).getAttribute('aria-checked')).toBe('true');

      // Storage: add (it is selected, so its slot range and unit show in the panel), save, remove, save.
      await studio.getByRole('button', { name: 'Add module' }).click();
      const picker = page.getByRole('dialog', { name: 'Add a module' });
      await picker.getByLabel('Search modules').fill('storage');
      await picker.getByLabel('Search modules').press('Enter');
      ctx.expect(await panel.innerText()).toContain('slots');
      await panel.getByRole('spinbutton', { name: 'Storage' }).fill('27');
      await panel.getByRole('spinbutton', { name: 'Storage' }).press('Tab');
      const save = studio.getByRole('button', { name: 'Save & apply' });
      await save.click();
      await waitForSaved(page);
      await studio.locator('[data-module="storage"]').getByRole('button', { name: 'Remove Storage' }).click();
      await save.click();
      await waitForSaved(page);
      ctx.expect(await studio.locator('[data-module="storage"]').count()).toBe(0);
      ctx.expect(await studio.locator('[data-module="seats"]').count()).toBe(1);
    },
  }),

  test({
    id: 'modules.browser-furniture-modules-visual',
    title: 'the furniture studio draws hitboxes, seats and lights on front and top block-scale views',
    mode: 'visual',
    dependsOn: ['modules.browser-furniture-modules'],
    covers: FURNITURE_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Furniture', 'Chair');
      await studio.getByRole('button', { name: 'Seats', exact: true }).click();
      await studio.getByRole('region', { name: 'Module settings' }).locator('svg').first().waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('furniture-modules-studio', '.studio');
      ctx.needsEyes(
        'furniture-modules-studio shows the display card and compact rows for Rotation snapping (Free / 45 / 90 / 180 choices, 90 selected), Hitbox and Seats; with Seats selected the settings panel on the right shows its front (x, y) and top (x, z) views on a one-block grid, a 1x1 block outline, an orange draggable handle numbered 1 (the hitbox also a shaded rectangle) - and the exact numeric rows below the pictures' + IDENTITY_EYES,
      );
    },
  }),
];
