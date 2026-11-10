// Input durability, row S (roadmap M6, docs/architecture/gui-input-durability.md section 10 and 12, issue 308):
// `durable: required | best_effort` on `slots` layers is SCHEMA ONLY in this build. These entries drive the real
// local App against a server that has the `gui` type and read the saved file from the probe's own content copy:
// the studio's Durability field exists on a slots layer, defaults to Best effort without writing anything, says in
// the schema's own words that Required is not crash-safe yet, and is written to the file only when the author
// chooses it. Nothing here claims crash-safety: the runtime does not read `durable` until row P (#310).
//
// Blessing is pending: the entries were authored with the schema change; the ledger records a pass only after a
// blessing run on main.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER_SAMPLE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs', 'sample');
const GUI_FILE = path.join(SERVER_SAMPLE, 'guis', 'qa_durability.yml');

const COVERS = [
  'webui/src/lib/studio/gui/InspectorPanel.svelte',
  'webui/src/lib/gui/**',
  'webui/src/lib/api.ts',
  'webui/browser/specs/gui-studio.pw.ts',
  'webui/test-support/qa-browser.mjs',
  'content/src/main/java/dev/materione/content/gui/SlotsComponent.java',
  'content/src/main/java/dev/materione/content/gui/GuiType.java',
  'fixtures/gui/durable-slots.yml',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
  'qa/catalog/guidurability.mjs',
];

function removeFixtureFile() {
  fs.rmSync(GUI_FILE, { force: true });
}

async function openNewGui(ctx) {
  const page = await ctx.browser.openEditor();
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('button', { name: /new gui/i }).first().click();
  await page.getByRole('dialog').getByRole('textbox').first().fill('QA Durability');
  await page.keyboard.press('Enter');
  await page.getByTestId('gui-art').waitFor({ state: 'visible', timeout: 30_000 });
  return page;
}

async function addSlotsLayer(page) {
  await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Slots' }).click();
  await page.getByRole('region', { name: 'Layers' }).getByRole('button', { name: 'Slots', exact: true }).click();
}

async function save(page) {
  await page.keyboard.press('Control+s');
  await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
}

export default [
  test({
    id: 'guidurability.studio-field-auto',
    title: 'sample:qa_durability: a slots layer\'s Durability field defaults to Best effort and writes nothing; choosing Required on an input layer states that it is not crash-safe in this build, and `durable: required` reaches the file on that layer only',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewGui(ctx);
        await addSlotsLayer(page);
        const durability = page.getByRole('group', { name: 'Durability' });
        ctx.expect(await durability.getByRole('button').allInnerTexts()).toEqual(['Best effort', 'Required']);
        ctx.expect(await durability.getByRole('button', { name: 'Best effort' }).getAttribute('aria-pressed')).toBe('true');

        // a saved slots layer the author never touched the field on has no durable key
        await save(page);
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).not.toContain('durable');

        await page.getByRole('checkbox', { name: 'Input slots' }).check();
        await durability.getByRole('button', { name: 'Required' }).click();
        const help = (await page.getByTestId('choice-help-durable').innerText()).toLowerCase();
        ctx.expect(help).toContain('not available in this build');
        ctx.expect(help).toContain('not crash-safe');
        await save(page);
        const saved = fs.readFileSync(GUI_FILE, 'utf8');
        ctx.expect(saved).toContain('input: true');
        ctx.expect(saved.match(/durable: required/g)).toHaveLength(1);
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'guidurability.studio-field-visual',
    title: 'sample:qa_durability studio inspector: the Durability field under Input slots with Best effort chosen, then Required with the not-crash-safe-yet line',
    mode: 'visual',
    dependsOn: ['guidurability.studio-field-auto'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewGui(ctx);
        await addSlotsLayer(page);
        await page.getByRole('checkbox', { name: 'Input slots' }).check();
        await ctx.browser.browserShot('gui-durability-best-effort');
        await page.getByRole('group', { name: 'Durability' }).getByRole('button', { name: 'Required' }).click();
        await ctx.browser.browserShot('gui-durability-required');
        ctx.needsEyes(
          'the inspector of the Slots layer shows Region, Input slots (checked) and a Durability segmented control directly under it. First shot: Best effort is highlighted and the line under it says a server crash can lose or duplicate the items. Second shot: Required is highlighted and the line under it says it is not available in this build yet, behaves as Best effort and is not crash-safe. The text stays inside the inspector column, wraps instead of clipping, and keeps the editor tokens.',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),
];
