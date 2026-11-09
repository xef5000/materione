// The GUI studio for every container (roadmap M6, docs/architecture/gui.md section 12.7, row S, issue 294): the
// Screen panel's container select, a canvas drawn from the container's profile (a hopper and a dispenser as much as
// a chest), the generated diagram where no owner art exists, and the container switch as one undo step that edits no
// layer. These entries drive the real local App against a server that has the `gui` type and the `guiContainers`
// list, and read the saved file from the probe's own content copy, so "saved losslessly" is judged on the file.
//
// Blessing is pending: the entries were authored with the studio change; the ledger records a pass only after a
// blessing run on main. The visual entry captures the canvases for each container; the human/vision pass closes it.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER_SAMPLE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs', 'sample');
const GUI_FILE = path.join(SERVER_SAMPLE, 'guis', 'qa_containers.yml');

const COVERS = [
  'webui/src/lib/studio/gui/**',
  'webui/src/lib/gui/**',
  'webui/src/lib/api.ts',
  'webui/browser/specs/gui-containers.pw.ts',
  'webui/test-support/qa-browser.mjs',
  'shared/gui/containers.json',
  'content/src/main/java/dev/materione/content/gui/**',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
  'qa/catalog/guicontainerstudio.mjs',
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
  await page.getByRole('dialog').getByRole('textbox').first().fill('QA Containers');
  await page.keyboard.press('Enter');
  await page.getByTestId('gui-art').waitFor({ state: 'visible', timeout: 30_000 });
  return page;
}

function underlaySize(page) {
  return page.getByTestId('gui-underlay').evaluate((element) => [element.width, element.height]);
}

async function switchTo(page, id) {
  await page.getByTestId('container-select').selectOption(id);
  await page.getByTestId('container-switch-apply').click();
  await page.waitForFunction((value) => document.querySelector('[data-testid=container-select]')?.value === value, id);
}

export default [
  test({
    id: 'guicontainerstudio.switch-auto',
    title: 'sample:qa_containers: the container select lists every profile the server describes, grouped; a hopper (176x133, slots 0-4) and a dispenser (176x166, slots 0-8) are drawn from their profiles; the switch is one undo step and Save writes only the container line',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewGui(ctx);
        const select = page.getByTestId('container-select');
        ctx.expect(await select.inputValue()).toBe('chest_3');
        const groups = await select.evaluate((element) =>
          [...element.querySelectorAll('optgroup')].map((group) => [group.label, [...group.querySelectorAll('option')].map((option) => option.value)]));
        ctx.expect(groups).toEqual([
          ['Chests', ['chest_1', 'chest_2', 'chest_3', 'chest_4', 'chest_5', 'chest_6']],
          ['Hopper', ['hopper']],
          ['Dispenser', ['dispenser']],
        ]);
        const original = fs.readFileSync(GUI_FILE, 'utf8');

        await switchTo(page, 'hopper');
        ctx.expect(await underlaySize(page)).toEqual([176, 133]);
        await page.getByRole('button', { name: 'Slot grid', exact: true }).click();
        ctx.expect(await page.getByTestId('slot-id').allInnerTexts()).toEqual(['0', '1', '2', '3', '4']);
        await page.getByRole('button', { name: 'Slot grid', exact: true }).click();

        await switchTo(page, 'dispenser');
        ctx.expect(await underlaySize(page)).toEqual([176, 166]);
        await page.getByRole('button', { name: 'Slot grid', exact: true }).click();
        ctx.expect(await page.getByTestId('slot-id').allInnerTexts()).toEqual(['0', '1', '2', '3', '4', '5', '6', '7', '8']);
        await page.getByRole('button', { name: 'Slot grid', exact: true }).click();

        // one undo step per switch: two undos return to the starter, byte for byte on disk (nothing was saved)
        await page.getByTestId('gui-art').focus();
        await page.keyboard.press('Control+z');
        ctx.expect(await select.inputValue()).toBe('hopper');
        await page.keyboard.press('Control+z');
        ctx.expect(await select.inputValue()).toBe('chest_3');
        ctx.expect(await underlaySize(page)).toEqual([176, 168]);
        ctx.expect((await page.locator('[data-status]').innerText()).trim()).toBe('Saved');
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).toBe(original);

        await switchTo(page, 'hopper');
        await page.keyboard.press('Control+s');
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        const saved = fs.readFileSync(GUI_FILE, 'utf8');
        ctx.expect(saved).toContain('container: hopper');
        // the file differs from the starter by that one line
        const before = original.split('\n');
        const after = saved.split('\n');
        ctx.expect(after.length).toBe(before.length);
        ctx.expect(after.filter((line, index) => line !== before[index])).toEqual(['  container: hopper']);
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'guicontainerstudio.diagram-visual',
    title: 'sample:qa_containers studio canvases: a chest with the owner\'s art, a hopper and a dispenser as the generated diagram with the slot grid and its ids, and the one-line switch cost before the switch',
    mode: 'visual',
    dependsOn: ['guicontainerstudio.switch-auto'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewGui(ctx);
        await ctx.browser.browserShot('gui-containers-chest');
        await page.getByTestId('container-select').selectOption('hopper');
        await page.getByTestId('container-switch-note').waitFor({ state: 'visible' });
        await ctx.browser.browserShot('gui-containers-switch-cost');
        await page.getByTestId('container-switch-apply').click();
        await page.getByRole('button', { name: 'Slot grid', exact: true }).click();
        await ctx.browser.browserShot('gui-containers-hopper');
        await switchTo(page, 'dispenser');
        await ctx.browser.browserShot('gui-containers-dispenser');
        ctx.needsEyes(
          'the chest canvas is the owner\'s vanilla-look art with rounded corners and the player inventory under a grey veil; the Screen panel shows the Container select (Chests, Hopper, Dispenser groups) and "176 x 168 px, 27 slots". After choosing Hopper and before applying, one line states what the switch flags and the Switch/Cancel buttons are shown. The hopper canvas is a 176 x 133 neutral cool-grey diagram (not the vanilla look) labelled "Generated diagram, vanilla art not shown", with five slot wells numbered 0-4 at the top, the player\'s inventory block below under the veil starting at row 37, and the faint "Inventory" label at the veil\'s top-left. The dispenser canvas is 176 x 166: a 3 x 3 grid of wells numbered 0-8 in the middle, the veil starting at row 70, the "Inventory" label at (8, 72). The slot ids sit inside each container well and stay legible; the chrome keeps the editor tokens.',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),
];
