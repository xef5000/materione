// The GUI studio (roadmap M6, docs/architecture/gui.md section 6, issue 186): the
// canvas tool that designs a container screen layer by layer. These entries
// drive the real local App against a server that has the `gui` content type
// (issue 183) and read the saved file from the probe's own content copy, so
// "saved losslessly" is judged on the file, not on the studio's say-so.
//
// What cannot be judged without eyes (does the canvas read like a vanilla
// chest, do the handles and guides feel right, is the chrome legible) is
// captured as App screenshots in the visual entry. The raster rules themselves
// are pinned in CI by webui/test/gui-raster.spec.ts against the shared vectors
// in fixtures/gui/raster/ (the Java compiler tests the same files).

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER_SAMPLE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs', 'sample');
const GUI_FILE = path.join(SERVER_SAMPLE, 'guis', 'qa_chest.yml');

const STUDIO_COVERS = [
  'webui/src/lib/studio/gui/**',
  'webui/src/lib/gui/**',
  'webui/src/lib/views/ContentView.svelte',
  'webui/src/lib/browser.ts',
  'webui/src/lib/drafts.svelte.ts',
  'webui/src/lib/api.ts',
  'webui/src/lib/demo/gui.ts',
  'webui/browser/specs/gui-studio.pw.ts',
  'webui/test-support/qa-browser.mjs',
  'fixtures/gui/raster/**',
  'content/src/main/java/dev/materione/content/gui/**',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
];

async function openNewChest(ctx) {
  const page = await ctx.browser.openEditor();
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('navigation', { name: 'content types' }).getByRole('button', { name: 'GUIs' }).click();
  await page.getByRole('button', { name: /new gui/i }).first().click();
  await page.getByRole('dialog').getByRole('textbox').first().fill('QA Chest');
  await page.keyboard.press('Enter');
  await page.getByTestId('gui-art').waitFor({ state: 'visible', timeout: 30_000 });
  return page;
}

function pixel(page, x, y) {
  return page.evaluate(([px, py]) => {
    const element = document.querySelector('[data-testid=gui-art]');
    return [...element.getContext('2d').getImageData(px, py, 1, 1).data];
  }, [x, y]);
}

function removeFixtureFile() {
  fs.rmSync(GUI_FILE, { force: true });
}

export default [
  test({
    id: 'gui.studio-new-chest',
    title: 'a new GUI (sample:qa_chest) opens as a vanilla 6-row chest: panel + slots layers, 176x222, the compiled bevel pixels on the canvas',
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        ctx.expect(await page.getByTestId('gui-art').evaluate((element) => [element.width, element.height])).toEqual([176, 222]);
        ctx.expect(await page.locator('.layers .row .name').allInnerTexts()).toEqual(['Slots', 'Frame']);
        // the same pixels as fixtures/gui/raster/chest-6-rows.png
        ctx.expect(await pixel(page, 0, 0)).toEqual([0, 0, 0, 0]);
        ctx.expect(await pixel(page, 1, 0)).toEqual([0, 0, 0, 255]);
        ctx.expect(await pixel(page, 1, 1)).toEqual([255, 255, 255, 255]);
        ctx.expect(await pixel(page, 7, 17)).toEqual([55, 55, 55, 255]);
        ctx.expect(await pixel(page, 8, 139)).toEqual([139, 139, 139, 255]);
        ctx.expect(await pixel(page, 8, 197)).toEqual([139, 139, 139, 255]);
        ctx.expect((await page.locator('[data-status]').innerText()).trim()).toBe('Saved');
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-edit-undo-save',
    title: 'sample:qa_chest: add a Button from the registry palette, nudge it, undo and redo, then Save writes the layers losslessly and only the layers',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        const original = fs.readFileSync(GUI_FILE, 'utf8');
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Button' }).click();
        const x = page.getByRole('textbox', { name: 'X', exact: true });
        const start = Number(await x.inputValue());
        await page.getByTestId('gui-art').focus();
        await page.keyboard.press('ArrowRight');
        await page.keyboard.press('Shift+ArrowRight');
        ctx.expect(Number(await x.inputValue())).toBe(start + 11);
        await page.keyboard.press('Control+z');
        ctx.expect(Number(await x.inputValue())).toBe(start);
        await page.keyboard.press('Control+Shift+z');
        ctx.expect(Number(await x.inputValue())).toBe(start + 11);
        ctx.expect((await page.locator('[data-status]').innerText()).trim()).toBe('Unsaved changes');
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).toBe(original); // nothing is written before Save

        await page.keyboard.press('Control+s');
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        const saved = fs.readFileSync(GUI_FILE, 'utf8');
        ctx.expect(saved).toContain('type: button');
        // the untouched starter layers and the file's own comment are still there, byte for byte
        ctx.expect(saved.startsWith(original.split('layers:')[0])).toBeTruthy();
        ctx.expect(saved).toContain('type: panel');
        ctx.expect(saved).toContain('type: slots');
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-layers-panel',
    title: 'sample:qa_chest layers panel: rename in place, drag to reorder, hide and lock, and a hidden layer leaves the canvas',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        const names = page.locator('.layers .row .name');
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Button' }).click();
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Panel' }).click();
        ctx.expect(await names.first().innerText()).toBe('Panel');

        await page.getByRole('button', { name: 'Button', exact: true }).first().dblclick();
        await page.getByLabel('Layer name').fill('Buy');
        await page.keyboard.press('Enter');
        ctx.expect(await names.allInnerTexts()).toEqual(['Panel', 'Buy', 'Slots', 'Frame']);

        await page.locator('.layers .row', { hasText: 'Buy' }).dragTo(
          page.locator('.layers .row', { hasText: 'Panel' }), { targetPosition: { x: 60, y: 2 } });
        ctx.expect(await names.allInnerTexts()).toEqual(['Buy', 'Panel', 'Slots', 'Frame']);
        await page.keyboard.press('Control+z');
        ctx.expect(await names.allInnerTexts()).toEqual(['Panel', 'Buy', 'Slots', 'Frame']);

        const before = await pixel(page, 88, 111);
        await page.getByRole('button', { name: 'Hide Panel' }).click();
        await page.getByRole('button', { name: 'Hide Buy' }).click();
        const hidden = await pixel(page, 88, 111);
        ctx.expect(hidden).not.toEqual(before);
        await page.getByRole('button', { name: 'Show Panel' }).click();
        await page.getByRole('button', { name: 'Lock Panel' }).click();
        ctx.expect(await page.getByRole('button', { name: 'Unlock Panel' }).getAttribute('aria-pressed')).toBe('true');
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-canvas-navigation',
    title: 'sample:qa_chest canvas: wheel zoom keeps the cursor anchored, 16x draws the pixel grid, 0 fits, 1 is 100%, and space-drag pans',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        const zoom = page.locator('[data-zoom]');
        const art = page.getByTestId('gui-art');
        const box = await art.boundingBox();
        await page.mouse.move(box.x + 40, box.y + 40);
        await page.mouse.wheel(0, -100);
        ctx.expect(Number.parseInt(await zoom.innerText(), 10)).toBeGreaterThan(300);
        for (let i = 0; i < 12; i++) await page.keyboard.press('=');
        ctx.expect((await zoom.innerText()).trim()).toBe('1600%');
        ctx.expect(await page.locator('.pixels').count()).toBe(1);
        await page.keyboard.press('1');
        ctx.expect((await zoom.innerText()).trim()).toBe('100%');
        ctx.expect(await page.locator('.pixels').count()).toBe(0);
        await page.keyboard.press('0');
        const fitted = await art.boundingBox();
        await page.keyboard.down(' ');
        await page.mouse.move(fitted.x + 100, fitted.y + 100);
        await page.mouse.down();
        await page.mouse.move(fitted.x + 70, fitted.y + 80, { steps: 4 });
        await page.mouse.up();
        await page.keyboard.up(' ');
        const panned = await art.boundingBox();
        ctx.expect(Math.round(panned.x - fitted.x)).toBe(-30);
        ctx.expect(Math.round(panned.y - fitted.y)).toBe(-20);
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-visual',
    title: 'sample:qa_chest studio: the new chest, a selected button with handles and inspector, and 16x with pixel grid and slot overlay',
    mode: 'visual',
    dependsOn: ['gui.studio-new-chest', 'gui.studio-edit-undo-save'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await ctx.browser.browserShot('gui-studio-new-chest');
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Button' }).click();
        await ctx.browser.browserShot('gui-studio-selected-button');
        await page.getByRole('button', { name: 'Slot grid', exact: true }).click();
        await page.getByTestId('gui-art').focus();
        for (let i = 0; i < 12; i++) await page.keyboard.press('=');
        await ctx.browser.browserShot('gui-studio-zoom-16');
        ctx.needsEyes(
          'the canvas reads as a vanilla chest drawn by the shared bevel (light top/left, dark bottom/right, black outline, transparent corners, inset 18x18 slot wells) at crisp nearest-neighbour pixels with no blur at any zoom; the selected Button shows eight resize handles, an orange selection outline and a size readout is absent at rest; the inspector shows Transform (X/Y/W/H), Style with four color fields and hex text, and Opacity; at 16x the pixel grid and the dashed slot overlay are visible but do not hide the art; the layers panel lists top-of-stack first with eye and lock toggles; the chrome uses the editor tokens (navy surfaces, orange accent only for selection and the primary Save) and stays legible.',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),
];
