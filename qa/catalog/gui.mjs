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
const GUI_TEXTURES = path.join(SERVER_SAMPLE, 'assets', 'textures', 'gui', 'qa_chest');
const GUI_ITEMS = path.join(SERVER_SAMPLE, 'items', 'gui', 'qa_chest');
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const FONT_SOURCE = path.join(REPO, 'fonts', 'smallcaps');
const FONT_FIXTURES = path.join(REPO, 'fixtures', 'gui', 'smallcaps');

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
  'fonts/smallcaps/**',
  'fixtures/gui/smallcaps/**',
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
  fs.rmSync(GUI_TEXTURES, { recursive: true, force: true });
  fs.rmSync(GUI_ITEMS, { recursive: true, force: true });
}

// A 16 x 16 PNG (the studio's sample coin), to upload as an image layer.
const COIN_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAY0lEQVR42mNgoDaYlSTwHx8mSvP/Z1Vw/PWAHhzjNYSQZryG4NIMEydoCC6bcRkAk8NqADabiDYAl1OJMgBfgFHdgBv9YuQbANKM1QB80YVNM860gM0lBDUTMoQozVTJTOQAACrcunqOCgT3AAAAAElFTkSuQmCC';

/** The ink pixels of a smallcaps run, parsed straight from the font source and the advance fixture (never from the studio's own code). */
function inkPixels(text, x, y) {
  const glyphs = new Map();
  let rows = null;
  for (const raw of fs.readFileSync(path.join(FONT_SOURCE, 'smallcaps.txt'), 'utf8').split('\n')) {
    const line = raw.trimEnd();
    const header = /^glyph U\+([0-9A-Fa-f]{4}) /.exec(line);
    if (header) {
      rows = [];
      glyphs.set(parseInt(header[1], 16), rows);
    } else if (rows && /^[#.]+$/.test(line)) rows.push(line);
    else if (line === '') rows = null;
  }
  const advances = new Map();
  for (const raw of fs.readFileSync(path.join(FONT_FIXTURES, 'advances.txt'), 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith(';')) continue;
    const [key, value] = line.split(' ');
    advances.set(key === 'space' ? 32 : Number(key), Number(value));
  }
  const out = [];
  let pen = x;
  for (const char of text) {
    const cp = char.codePointAt(0);
    (glyphs.get(cp) ?? []).forEach((row, dy) => {
      for (let dx = 0; dx < row.length; dx++) if (row[dx] === '#') out.push([pen + dx, y + dy]);
    });
    pen += advances.get(cp);
  }
  return out;
}

async function canvasPoint(page, x, y) {
  const box = await page.getByTestId('gui-art').boundingBox();
  const zoom = box.width / 176;
  return [box.x + x * zoom + zoom / 2, box.y + y * zoom + zoom / 2];
}

export default [
  test({
    id: 'gui.studio-new-chest',
    title: 'a new GUI (sample:qa_chest) opens as the starter chest from the server: panel + slots layers, 176x168, the compiled bevel pixels on the canvas',
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        ctx.expect(await page.getByTestId('gui-art').evaluate((element) => [element.width, element.height])).toEqual([176, 168]);
        ctx.expect(await page.locator('.layers .row .name').allInnerTexts()).toEqual(['wells', 'frame']);
        // the same bevel rules as fixtures/gui/raster (panel + slots, region all)
        ctx.expect(await pixel(page, 0, 0)).toEqual([0, 0, 0, 0]);
        ctx.expect(await pixel(page, 1, 0)).toEqual([0, 0, 0, 255]);
        ctx.expect(await pixel(page, 1, 1)).toEqual([255, 255, 255, 255]);
        ctx.expect(await pixel(page, 7, 17)).toEqual([55, 55, 55, 255]);
        ctx.expect(await pixel(page, 8, 85)).toEqual([139, 139, 139, 255]);
        ctx.expect(await pixel(page, 8, 143)).toEqual([139, 139, 139, 255]);
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
        ctx.expect(await names.allInnerTexts()).toEqual(['Panel', 'Buy', 'wells', 'frame']);

        await page.locator('.layers .row', { hasText: 'Buy' }).dragTo(
          page.locator('.layers .row', { hasText: 'Panel' }), { targetPosition: { x: 60, y: 2 } });
        ctx.expect(await names.allInnerTexts()).toEqual(['Buy', 'Panel', 'wells', 'frame']);
        await page.keyboard.press('Control+z');
        ctx.expect(await names.allInnerTexts()).toEqual(['Panel', 'Buy', 'wells', 'frame']);

        const before = await pixel(page, 88, 84);
        await page.getByRole('button', { name: 'Hide Panel' }).click();
        await page.getByRole('button', { name: 'Hide Buy' }).click();
        const hidden = await pixel(page, 88, 84);
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

  test({
    id: 'gui.studio-paint-tools',
    title: 'sample:qa_chest paint tools: the brush starts a Paint layer and draws a continuous stroke, the whole stroke is ONE undo step, and Save writes the PNG under textures/gui/qa_chest (a paint-only save needs no field edit)',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('button', { name: 'Brush', exact: true }).click();
        await page.getByRole('textbox', { name: 'Paint colour hex' }).fill('#FF0000');
        await page.keyboard.press('Enter');
        const before = await pixel(page, 40, 40);
        const [x0, y0] = await canvasPoint(page, 20, 40);
        const [x1, y1] = await canvasPoint(page, 60, 40);
        await page.mouse.move(x0, y0);
        await page.mouse.down();
        await page.mouse.move(x1, y1, { steps: 3 }); // three big pointer jumps: the stroke must still be continuous
        await page.mouse.up();
        for (const x of [20, 33, 47, 60]) ctx.expect(await pixel(page, x, 40)).toEqual([255, 0, 0, 255]);
        ctx.expect((await page.locator('.layers .row .name').allInnerTexts())[0]).toBe('Paint');
        await page.keyboard.press('Control+z'); // one step for the whole stroke
        ctx.expect(await pixel(page, 40, 40)).toEqual(before);
        await page.keyboard.press('Control+Shift+z');
        ctx.expect(await pixel(page, 40, 40)).toEqual([255, 0, 0, 255]);

        await page.keyboard.press('Control+s');
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        const png = path.join(GUI_TEXTURES, 'paint.png');
        ctx.expect(fs.existsSync(png)).toBeTruthy();
        ctx.expect(fs.readFileSync(png).subarray(1, 4).toString()).toBe('PNG');
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).toContain('image: gui/qa_chest/paint');
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-image-upload',
    title: 'sample:qa_chest image layer: upload a PNG, it is stored beside the GUI byte for byte, drawn at natural size and scaled in whole multiples, and the layer points at it',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        const coin = Buffer.from(COIN_PNG_BASE64, 'base64');
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Image' }).click();
        await page.getByLabel('Upload an image').first().setInputFiles({ name: 'Gold Coin.png', mimeType: 'image/png', buffer: coin });
        await page.getByTestId('image-size').waitFor({ state: 'visible', timeout: 15_000 });
        ctx.expect((await page.getByTestId('image-size').innerText()).replace(/\s+/g, ' ')).toContain('16 x 16 px · natural 16 x 16 · 1x');
        await page.getByRole('button', { name: '2x', exact: true }).click();
        ctx.expect((await page.getByTestId('image-size').innerText()).replace(/\s+/g, ' ')).toContain('32 x 32 px');
        await page.keyboard.press('Control+s');
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        const png = path.join(GUI_TEXTURES, 'gold_coin.png');
        ctx.expect(fs.existsSync(png)).toBeTruthy();
        ctx.expect(fs.readFileSync(png).equals(coin)).toBeTruthy(); // the chosen bytes, not a re-encode
        const saved = fs.readFileSync(GUI_FILE, 'utf8');
        ctx.expect(saved).toContain('image: gui/qa_chest/gold_coin');
        ctx.expect(saved).toContain('width: 32');
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-text-smallcaps',
    title: 'sample:qa_chest text layer: a new Text layer edits in place on the canvas, the smallcaps preview is pixel-exact against the shared font fixtures, the default font says its preview is approximate, and a run the compiler would skip is flagged',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Text' }).click();
        await page.getByTestId('gui-text-edit').waitFor({ state: 'visible', timeout: 10_000 });
        await page.keyboard.type('Gold Shop');
        await page.keyboard.press('Enter');
        const x = Number(await page.getByRole('textbox', { name: 'X', exact: true }).inputValue());
        const y = Number(await page.getByRole('textbox', { name: 'Y', exact: true }).inputValue());
        for (const [px, py] of inkPixels('Gold Shop', x, y)) ctx.expect(await pixel(page, px, py)).toEqual([64, 64, 64, 255]);
        ctx.expect(await page.getByTestId('smallcaps-hint').innerText()).toContain('Pixel-exact');

        await page.getByRole('group', { name: 'Font' }).getByRole('button', { name: 'Default', exact: true }).click();
        ctx.expect(await page.getByTestId('default-font-hint').innerText()).toContain('Approximate preview');
        await page.getByRole('textbox', { name: 'Y', exact: true }).fill('2');
        await page.keyboard.press('Enter');
        // the compiler's verdict comes from gui.validate a moment after the edit: wait for it
        await page.getByTestId('text-issue').first().waitFor({ state: 'visible', timeout: 10_000 });
        ctx.expect(await page.getByTestId('text-issue').first().innerText()).toContain('row 5 or lower');
        await page.locator('[data-flag]').first().waitFor({ state: 'visible', timeout: 10_000 });
        ctx.expect(await page.locator('[data-flag]').count()).toBeGreaterThan(0);
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-item-customize',
    title: 'sample:qa_chest item layer: Customize creates an ordinary item under items/gui/qa_chest/ and points the layer at it, and the appearance opens inline',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Item' }).click();
        await page.getByTestId('item-extras').waitFor({ state: 'visible', timeout: 10_000 });
        await page.getByRole('button', { name: 'Customize', exact: true }).click();
        await page.getByTestId('gui-item-appearance').waitFor({ state: 'visible', timeout: 30_000 });
        const created = fs.existsSync(GUI_ITEMS) ? fs.readdirSync(GUI_ITEMS) : [];
        ctx.expect(created.length).toBe(1);
        const item = fs.readFileSync(path.join(GUI_ITEMS, created[0]), 'utf8');
        ctx.expect(item).toMatch(/material: *"?STONE/);
        const id = created[0].replace(/\.yml$/, '');
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).toContain(`item: sample:${id}`);
        ctx.expect((await page.locator('[data-status]').innerText()).trim()).toBe('Saved');
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-components-visual',
    title: 'sample:qa_chest studio components: paint tools with the brush ghost, a text layer in edit mode, the default font with its warning, and a customized item with its inline appearance',
    mode: 'visual',
    dependsOn: ['gui.studio-paint-tools', 'gui.studio-text-smallcaps', 'gui.studio-item-customize', 'gui.studio-image-upload'],
    covers: STUDIO_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('button', { name: 'Brush', exact: true }).click();
        await page.getByRole('button', { name: 'Brush size 4', exact: true }).click();
        await page.getByRole('button', { name: 'Round brush' }).click();
        const [bx, by] = await canvasPoint(page, 40, 40);
        await page.mouse.move(bx, by);
        await page.mouse.down();
        await page.mouse.move(bx + 120, by + 40, { steps: 8 });
        await page.mouse.up();
        await page.mouse.move(bx + 200, by + 60);
        await ctx.browser.browserShot('gui-studio-brush');
        await page.keyboard.press('v');
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Text' }).click();
        await page.keyboard.type('Gold Shop');
        await ctx.browser.browserShot('gui-studio-text-edit');
        await page.keyboard.press('Enter');
        await page.getByRole('group', { name: 'Font' }).getByRole('button', { name: 'Default', exact: true }).click();
        await ctx.browser.browserShot('gui-studio-default-font');
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Item' }).click();
        await page.getByRole('button', { name: 'Customize', exact: true }).click();
        await page.getByTestId('gui-item-appearance').waitFor({ state: 'visible', timeout: 30_000 });
        await ctx.browser.browserShot('gui-studio-item-customized');
        ctx.needsEyes(
          'the brush leaves a smooth round stroke with no gaps and a ghost footprint (white line with a dark edge) follows the cursor; the Text layer in edit mode shows pixel-font letters with a blinking orange caret and a dashed box, never a second font; the default-font text is visibly the client font with the "Approximate preview" note and an orange "!" flag when a restriction applies; the customized item shows its real icon in the slot and the item studio Appearance card (render, texture and model slots, tooltip) inline in the inspector without layout overflow.',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),
];
