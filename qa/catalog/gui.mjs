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
import { createRequire } from 'node:module';

const SERVER_SAMPLE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs', 'sample');
const GUI_FILE = path.join(SERVER_SAMPLE, 'guis', 'qa_chest.yml');
const GUI_TEXTURES = path.join(SERVER_SAMPLE, 'assets', 'textures', 'gui', 'qa_chest');
const GUI_ITEMS = path.join(SERVER_SAMPLE, 'items', 'gui', 'qa_chest');
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const FONT_SOURCE = path.join(REPO, 'fonts', 'smallcaps');
const FONT_FIXTURES = path.join(REPO, 'fixtures', 'gui', 'smallcaps');
const requireWebui = createRequire(path.join(REPO, 'webui', 'package.json'));

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
  'fonts/vanilla-ascii/**',
  'fixtures/gui/vanilla-ascii/**',
  'content/src/main/java/dev/materione/content/gui/**',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
];

const LOGIC_COVERS = [
  'webui/src/lib/studio/logic/**',
  'webui/src/lib/logic/**',
  'webui/src/lib/studio/gui/GuiStudio.svelte',
  'webui/src/lib/gui/studio.svelte.ts',
  'webui/browser/specs/logic-tab.pw.ts',
  'webui/test-support/qa-browser.mjs',
  'web/src/main/java/dev/materione/web/rpc/ops/LogicAuthoringOps.java',
  'behavior/src/main/java/dev/materione/behavior/logic/authoring/**',
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

// The art canvas covers the whole art cap (gui.md section 11.2): its top-left is GUI pixel (-424, -243).
function pixel(page, x, y) {
  return page.evaluate(([px, py]) => {
    const element = document.querySelector('[data-testid=gui-art]');
    return [...element.getContext('2d').getImageData(px + 424, py + 243, 1, 1).data];
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
  // positions are measured on the vanilla chest (the screen), which the art canvas and the open space are laid around
  const box = await page.getByTestId('gui-underlay').boundingBox();
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
        ctx.expect(await page.getByTestId('gui-art').evaluate((element) => [element.width, element.height])).toEqual([1024, 1024]);
        ctx.expect(await page.getByTestId('gui-underlay').evaluate((element) => [element.width, element.height])).toEqual([176, 168]);
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
        const art = page.getByTestId('gui-underlay');
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
    id: 'gui.studio-open-canvas',
    title: 'sample:qa_chest open canvas: the vanilla chest is drawn under the GUI, painting outside the screen grows a paint layer that Save trims, and the window guides toggle',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        // the chest under the art is not a layer: it is there, and the layers panel does not list it
        ctx.expect(await page.getByTestId('gui-underlay').evaluate((element) => {
          const data = element.getContext('2d').getImageData(88, 10, 1, 1).data;
          return data[3];
        })).toBe(255);
        // paint outside the screen with the brush at 100%
        await page.getByRole('button', { name: 'Brush', exact: true }).click();
        await page.getByRole('textbox', { name: 'Paint colour hex' }).fill('#FF0000');
        await page.keyboard.press('Enter');
        await page.keyboard.press('1');
        const [x, y] = await canvasPoint(page, 200, 40); // right of the 176-wide screen
        await page.mouse.click(x, y);
        ctx.expect(await pixel(page, 200, 40)).toEqual([255, 0, 0, 255]);
        ctx.expect(await page.getByTestId('paint-size').innerText()).toContain('1 x 1 px');
        // Save trims the layer to its pixels and writes the box with it
        await page.keyboard.press('Control+s');
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ timeout: 30_000 });
        const saved = fs.readFileSync(GUI_FILE, 'utf8');
        ctx.expect(saved).toContain('type: paint');
        ctx.expect(saved).toMatch(/x: 200/);
        ctx.expect(saved).toMatch(/width: 1/);
        // the window guides toggle like the slot grid
        await page.getByRole('button', { name: 'Window guides', exact: true }).click();
        ctx.expect(await page.getByTestId('gui-window-guide').count()).toBe(3);
        await page.getByRole('button', { name: 'Window guides', exact: true }).click();
        ctx.expect(await page.getByTestId('gui-window-guide').count()).toBe(0);
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.studio-open-canvas-visual',
    title: 'sample:qa_chest studio look of the open canvas: the vanilla chest, the inventory veil lifting under paint, art outside the screen, the cap box and the window guides',
    mode: 'visual',
    dependsOn: ['gui.studio-open-canvas'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await ctx.browser.browserShot('gui-studio-open-canvas-chest');
        await page.getByRole('button', { name: 'Brush', exact: true }).click();
        await page.getByRole('textbox', { name: 'Paint colour hex' }).fill('#3C8527');
        await page.keyboard.press('Enter');
        for (const [gx, gy] of [[20, 100], [21, 100], [22, 101], [60, 60], [-30, 40], [-31, 41], [210, 90]]) {
          const [x, y] = await canvasPoint(page, gx, gy);
          await page.mouse.click(x, y);
        }
        await page.getByRole('button', { name: 'Window guides', exact: true }).click();
        await page.keyboard.press('0');
        await ctx.browser.browserShot('gui-studio-open-canvas-guides');
        ctx.needsEyes(
          'the canvas shows the vanilla chest with rounded corners under any layers (not a bevel redrawn by the studio); the player-inventory part is dimmed with grey, and the dimming is lifted exactly under the painted pixels there, with their neighbours still dimmed; painted pixels outside the screen sit in the open space beside the chest, with the dashed cap box (1024 x 1024) labelled "Art limit" and the screen outline; with Window guides on, three dashed rectangles (scale 2, 3 and 4 at 1920 x 1080) are visible around the screen with their margins in the labels, scale 4 closest to the chest; the layers panel lists only real layers; the chrome keeps the editor tokens and the labels stay legible over the art.',
        );
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
    id: 'gui.studio-text-default-chain',
    title: 'sample:qa_chest text layers: a default run followed by a smallcaps run on the same row is NOT flagged (the client widths place it, gui.md 9.4), the smallcaps run is pixel-exact on the canvas, and a default run above row 5 is still flagged',
    dependsOn: ['gui.studio-new-chest'],
    covers: STUDIO_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        // the default run is the first layer, so the compiler draws it before the smallcaps run after it
        const textButton = page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Text' });
        await textButton.click();
        await page.getByTestId('gui-text-edit').waitFor({ state: 'visible', timeout: 10_000 });
        await page.keyboard.type('Shop');
        await page.keyboard.press('Enter');
        await page.getByRole('group', { name: 'Font' }).getByRole('button', { name: 'Default', exact: true }).click();
        await page.getByRole('textbox', { name: 'X', exact: true }).fill('8');
        await page.getByRole('textbox', { name: 'Y', exact: true }).fill('2');
        await page.keyboard.press('Enter');
        // positive control: the verdict has arrived once the default run above row 5 is flagged
        await page.getByTestId('text-issue').first().waitFor({ state: 'visible', timeout: 10_000 });
        ctx.expect(await page.getByTestId('text-issue').first().innerText()).toContain('row 5 or lower');
        await page.getByRole('textbox', { name: 'Y', exact: true }).fill('6');
        await page.keyboard.press('Enter');
        await page.waitForFunction(() => document.querySelectorAll('[data-testid=text-issue]').length === 0, null,
          { timeout: 10_000 });

        // a smallcaps run after it: the old "text after a default run" flag must not come back
        await textButton.click();
        await page.getByTestId('gui-text-edit').waitFor({ state: 'visible', timeout: 10_000 });
        await page.keyboard.type('Gold');
        await page.keyboard.press('Enter');
        await page.getByRole('textbox', { name: 'X', exact: true }).fill('40');
        await page.getByRole('textbox', { name: 'Y', exact: true }).fill('6');
        await page.keyboard.press('Enter');
        await page.waitForTimeout(1500); // the verdict is debounced (gui.validate, §6)
        ctx.expect(await page.getByTestId('text-issue').count()).toBe(0);
        ctx.expect(await page.locator('[data-flag]').count()).toBe(0);
        for (const [px, py] of inkPixels('Gold', 40, 6)) ctx.expect(await pixel(page, px, py)).toEqual([64, 64, 64, 255]);
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

  test({
    id: 'gui.logic-tab-live-save',
    title: 'sample:qa_chest Logic tab saves a player-message flow and node position to canonical YAML, reopens it, and runs it when the player opens the GUI',
    dependsOn: ['gui.studio-new-chest'],
    covers: [...LOGIC_COVERS, 'plugin/src/main/java/dev/materione/plugin/gui/**',
      'plugin/src/main/java/dev/materione/plugin/logic/action/MessageAction.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      ctx.expect(fs.existsSync(GUI_FILE)).toBe(false);
      try {
        const { parse } = requireWebui('yaml');
        const page = await openNewChest(ctx);
        const original = fs.readFileSync(GUI_FILE, 'utf8');
        const starter = parse(original);
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.getByTestId('logic-canvas').waitFor({ state: 'visible', timeout: 30_000 });
        await page.getByRole('button', { name: /add a trigger/i }).click();
        await page.locator('[data-entry="trigger:gui.open"]').click();
        const port = await page.locator('[data-node="behavior.0"][data-port="out"]').boundingBox();
        await page.mouse.move(port.x + port.width / 2, port.y + port.height / 2);
        await page.mouse.down();
        await page.mouse.move(port.x + port.width / 2 + 150, port.y + port.height / 2 + 150, { steps: 8 });
        await page.mouse.up();
        await page.locator('[data-entry="action:message"]').click();
        const node = page.locator('.node[data-node="behavior.0.do.0"]');
        await node.getByRole('textbox', { name: 'Send message value' }).fill('MATERIONE-QA-LOGIC-SAVED');
        await page.keyboard.press('Enter');
        const beforeMove = await node.evaluate((element) => ({
          x: parseFloat(element.style.left), y: parseFloat(element.style.top),
        }));
        const head = await node.locator('header').boundingBox();
        await page.mouse.move(head.x + 40, head.y + 8);
        await page.mouse.down();
        await page.mouse.move(head.x + 100, head.y + 88, { steps: 6 });
        await page.mouse.up();
        const position = await node.evaluate((element) => ({
          x: parseFloat(element.style.left), y: parseFloat(element.style.top),
        }));
        ctx.expect(position.x).toBeGreaterThan(beforeMove.x + 20);
        ctx.expect(position.y).toBeGreaterThan(beforeMove.y + 20);
        ctx.expect((await page.locator('[data-status]').innerText()).trim()).toBe('Unsaved changes');
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).toBe(original);

        await page.getByRole('button', { name: 'Save', exact: true }).click();
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        const saved = fs.readFileSync(GUI_FILE, 'utf8');
        const expected = structuredClone(starter);
        expected.qa_chest.behavior = [{
          on: 'gui.open',
          do: [{ message: 'MATERIONE-QA-LOGIC-SAVED', node: position }],
        }];
        ctx.expect(parse(saved)).toEqual(expected);

        await page.getByRole('button', { name: 'back to GUIs' }).click();
        await page.getByRole('option', { name: /qa.chest/i }).dblclick();
        await page.getByTestId('gui-art').waitFor({ state: 'visible', timeout: 30_000 });
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.locator('.node[data-node="behavior.0.do.0"]').waitFor({ state: 'visible', timeout: 30_000 });
        ctx.expect(await page.locator('.node').count()).toBe(2);
        ctx.expect(await page.locator('[data-wire]').count()).toBe(1);
        ctx.expect(await page.locator('.node[data-node="behavior.0.do.0"] input.value').inputValue())
          .toBe('MATERIONE-QA-LOGIC-SAVED');
        ctx.expect(await page.locator('.node[data-node="behavior.0.do.0"]').evaluate((element) => ({
          x: parseFloat(element.style.left), y: parseFloat(element.style.top),
        }))).toEqual(position);
        ctx.expect(fs.readFileSync(GUI_FILE, 'utf8')).toBe(saved);

        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_chest Probe');
        await ctx.waitForChat(/MATERIONE-QA-LOGIC-SAVED/);
        await ctx.sleep(600);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).not.toBe(0);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.slots.size()'))).toBe(63);
        await ctx.exec('player.closeContainer(); return "closed"');
        await ctx.sleep(400);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).toBe(0);
      } finally {
        try {
          await ctx.exec('player.closeContainer(); return "closed"');
        } finally {
          fs.rmSync(GUI_FILE, { force: true });
          await ctx.rcon('materione reload');
        }
      }
    },
  }),

  test({
    id: 'gui.logic-inspector-live-save',
    title: 'sample:qa_chest Logic inspector: a message whose template is completed from the trigger context (${player.name}) is authored without typing YAML, saved to canonical YAML, and runs when the player opens the GUI',
    dependsOn: ['gui.logic-tab-live-save'],
    covers: [...LOGIC_COVERS, 'plugin/src/main/java/dev/materione/plugin/gui/**',
      'plugin/src/main/java/dev/materione/plugin/logic/action/MessageAction.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
      'api/src/main/java/dev/materione/api/registry/BehaviorVocabulary.java',
      'behavior/src/main/java/dev/materione/behavior/logic/VocabularyBuilder.java'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      ctx.expect(fs.existsSync(GUI_FILE)).toBe(false);
      try {
        const { parse } = requireWebui('yaml');
        const page = await openNewChest(ctx);
        const starter = parse(fs.readFileSync(GUI_FILE, 'utf8'));
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.getByTestId('logic-canvas').waitFor({ state: 'visible', timeout: 30_000 });
        await page.getByRole('button', { name: /add a trigger/i }).click();
        await page.locator('[data-entry="trigger:gui.open"]').click();
        const port = await page.locator('[data-node="behavior.0"][data-port="out"]').boundingBox();
        await page.mouse.move(port.x + port.width / 2, port.y + port.height / 2);
        await page.mouse.down();
        await page.mouse.move(port.x + port.width / 2 - 20, port.y + port.height / 2 + 80, { steps: 8 });
        await page.mouse.up();
        await page.locator('[data-entry="action:message"]').click();
        const inspector = page.getByTestId('logic-inspector');
        const text = inspector.getByRole('combobox', { name: 'Text' });
        await text.click();
        await page.keyboard.type('${pl');
        await inspector.getByRole('option', { name: /^player\./ }).waitFor({ state: 'visible', timeout: 10_000 });
        await page.keyboard.press('Enter');
        await page.keyboard.type('na');
        await inspector.getByRole('option', { name: /player\.name/ }).waitFor({ state: 'visible', timeout: 10_000 });
        await page.keyboard.press('Enter');
        await page.keyboard.type(' MATERIONE-QA-INSPECTOR-SAVED');
        await page.keyboard.press('Enter');
        ctx.expect(await text.inputValue()).toBe('${player.name} MATERIONE-QA-INSPECTOR-SAVED');
        ctx.expect(await inspector.locator('[data-lint]').count()).toBe(0);
        await page.getByRole('button', { name: 'Save', exact: true }).click();
        await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        const expected = structuredClone(starter);
        expected.qa_chest.behavior = [{ on: 'gui.open', do: [{ message: '${player.name} MATERIONE-QA-INSPECTOR-SAVED' }] }];
        ctx.expect(parse(fs.readFileSync(GUI_FILE, 'utf8'))).toEqual(expected);

        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_chest Probe');
        await ctx.waitForChat(/Probe MATERIONE-QA-INSPECTOR-SAVED/);
        await ctx.exec('player.closeContainer(); return "closed"');
      } finally {
        try {
          await ctx.exec('player.closeContainer(); return "closed"');
        } finally {
          fs.rmSync(GUI_FILE, { force: true });
          await ctx.rcon('materione reload');
        }
      }
    },
  }),

  test({
    id: 'gui.logic-inspector-visual',
    title: 'sample:qa_chest Logic inspector: typed fields for an action, the template field with its completion list and an inline diagnostic, the condition stack, and the prompt step with the unavailable sign method greyed',
    mode: 'visual',
    dependsOn: ['gui.logic-inspector-live-save'],
    covers: [...LOGIC_COVERS, 'api/src/main/java/dev/materione/api/registry/BehaviorVocabulary.java',
      'behavior/src/main/java/dev/materione/behavior/logic/VocabularyBuilder.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/PromptAction.java'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.getByTestId('logic-canvas').waitFor({ state: 'visible', timeout: 30_000 });
        await page.getByRole('button', { name: /add a trigger/i }).click();
        await page.locator('[data-entry="trigger:gui.click"]').click();
        const dragOut = async (node, port) => {
          const box = await page.locator('[data-node="' + node + '"][data-port="' + port + '"]').boundingBox();
          const x = box.x + box.width / 2;
          const y = box.y + box.height / 2;
          await page.mouse.move(x, y);
          await page.mouse.down();
          await page.mouse.move(x - 20, y + 60, { steps: 4 });
          await page.mouse.move(x - 40, y + 140, { steps: 4 });
          await page.mouse.up();
        };
        const inspector = page.getByTestId('logic-inspector');
        await ctx.browser.browserShot('logic-inspector-trigger');
        await dragOut('behavior.0', 'out');
        await page.locator('[data-entry="control:if"]').click();
        await inspector.getByRole('combobox', { name: 'Kind of condition' }).selectOption('predicate');
        await inspector.getByRole('button', { name: 'Also require an expression' }).click();
        await ctx.browser.browserShot('logic-inspector-condition-stack');
        await page.getByTestId('logic-canvas').focus();
        await page.keyboard.press('0');
        await dragOut('behavior.0.do.0', 'then');
        await page.locator('[data-entry="action:message"]').click();
        const text = inspector.getByRole('combobox', { name: 'Text' });
        await text.click();
        await page.keyboard.type('Hello ${');
        await ctx.browser.browserShot('logic-inspector-completion');
        await text.fill('Hello ${bogus.value}');
        await page.keyboard.press('Enter');
        await ctx.sleep(1500);
        await ctx.browser.browserShot('logic-inspector-diagnostic');
        await page.getByTestId('logic-canvas').focus();
        await page.keyboard.press('0');
        await dragOut('behavior.0.do.0', 'after');
        await page.locator('[data-entry="action:prompt"]').click();
        await ctx.browser.browserShot('logic-inspector-prompt');
        ctx.needsEyes(
          'the inspector is a panel to the right of the canvas that never overflows horizontally; the trigger shows its filters with the GUI layers offered, the if node shows a stack of condition rows (a typed predicate row, then a second row) with add, move and remove controls; the template field shows a completion list under it with roots such as player. and functions with their signatures, then, for the unknown name, an amber line on the field and an amber triangle on the message node only; the prompt step shows its input methods as choices with Sign greyed and dashed and the reason written below, and the node has an else port beside its after port; the chrome uses the editor tokens (navy surfaces, orange only for the primary action and focus).',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.logic-tab-visual',
    title: 'sample:qa_chest Logic tab: a flow built by dragging wires out of ports (open, sound, command, close), the search palette with an unavailable provider greyed, and a refused wire',
    mode: 'visual',
    dependsOn: ['gui.logic-tab-live-save'],
    covers: LOGIC_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.getByTestId('logic-canvas').waitFor({ state: 'visible', timeout: 30_000 });
        await ctx.browser.browserShot('logic-tab-empty');
        await page.getByRole('button', { name: /add a trigger/i }).click();
        await page.locator('[data-entry="trigger:gui.open"]').click();
        const dragOut = async (node, port) => {
          const box = await page.locator('[data-node="' + node + '"][data-port="' + port + '"]').boundingBox();
          const x = box.x + box.width / 2;
          const y = box.y + box.height / 2;
          await page.mouse.move(x, y);
          await page.mouse.down();
          await page.mouse.move(x + 80, y + 60, { steps: 4 });
          await page.mouse.move(x + 150, y + 150, { steps: 4 });
          await page.mouse.up();
        };
        const fill = async (node, value) => {
          await page.locator('.node[data-node="' + node + '"] input.value').fill(value);
          await page.keyboard.press('Enter');
        };
        await dragOut('behavior.0', 'out');
        await page.locator('[data-entry="action:sound"]').click();
        await fill('behavior.0.do.0', 'block.chest.open');
        await dragOut('behavior.0.do.0', 'after');
        await page.locator('[data-entry="action:run_command"]').click();
        await fill('behavior.0.do.1', 'say shop opened');
        await dragOut('behavior.0.do.1', 'after');
        await page.locator('[data-entry="action:gui.close"]').click();
        await page.getByTestId('logic-canvas').focus();
        await page.keyboard.press('0');
        await ctx.browser.browserShot('logic-tab-flow');
        await page.keyboard.press('/');
        await page.getByRole('textbox', { name: 'Search the palette' }).fill('money');
        await ctx.browser.browserShot('logic-tab-palette-unavailable');
        await page.keyboard.press('Escape');
        const from = await page.locator('[data-node="behavior.0"][data-port="out"]').boundingBox();
        const to = await page.locator('.node[data-node="behavior.0.do.2"] header').boundingBox();
        await page.mouse.move(from.x + 6, from.y + 6);
        await page.mouse.down();
        await page.mouse.move(to.x + 40, to.y + 10, { steps: 8 });
        await ctx.browser.browserShot('logic-tab-refused-wire');
        await page.mouse.up();
        ctx.needsEyes(
          'the Logic tab shows the four-node flow as colour-coded nodes (amber trigger, blue Play sound, slate Run command, purple Close GUI) joined by stepped pixel wires in the colour of the node they leave, with square ports on the node edges and a dust pixel at every bend; the empty state is readable and offers Add a trigger; the palette is grouped (Triggers first), searchable, and the Economy entries are greyed with the Vault reason rather than hidden; while a wire is dragged onto an already-fed node the status line says why in one line and the loose wire is dashed; the chrome uses the editor tokens (navy surfaces, orange only for the primary action and focus) and nothing overflows horizontally.',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),

test({
    id: 'gui.logic-linkage-live-save',
    title: 'sample:qa_chest Design/Logic linkage: Add click logic on a Button opens its rule in Logic, the Script tab edits it, the Close button recipe adds a second rule, and deleting the layer with "Delete the rules" removes both; each Save is the expected canonical YAML',
    dependsOn: ['gui.logic-inspector-live-save'],
    covers: [...LOGIC_COVERS, 'webui/browser/specs/logic-linkage.pw.ts', 'webui/browser/specs/logic-script.pw.ts',
      'webui/browser/specs/logic-recipes.pw.ts', 'webui/src/lib/studio/gui/LayersPanel.svelte',
      'webui/src/lib/studio/gui/InspectorPanel.svelte', 'webui/src/lib/gui/save.ts',
      'behavior/src/main/java/dev/materione/behavior/logic/recipe/**',
      'plugin/src/main/java/dev/materione/plugin/logic/recipe/**'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      ctx.expect(fs.existsSync(GUI_FILE)).toBe(false);
      try {
        const { parse } = requireWebui('yaml');
        const page = await openNewChest(ctx);
        const starter = parse(fs.readFileSync(GUI_FILE, 'utf8'));
        const save = async () => {
          await page.getByRole('button', { name: 'Save', exact: true }).click();
          await page.locator('[data-status]').filter({ hasText: 'Saved' }).waitFor({ state: 'visible', timeout: 20_000 });
        };

        // Design: a Button, then Add click logic from the inspector
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Button' }).click();
        await page.getByTestId('add-click-logic').click();
        await page.getByTestId('logic-canvas').waitFor({ state: 'visible', timeout: 30_000 });
        const trigger = page.locator('.node[data-kind="trigger"][data-node="behavior.0"]');
        await trigger.waitFor({ state: 'visible', timeout: 15_000 });
        const layer = await trigger.getAttribute('data-layer');
        ctx.expect(layer).toBeTruthy();
        ctx.expect(await trigger.getAttribute('aria-selected')).toBe('true');
        await save();
        const first = parse(fs.readFileSync(GUI_FILE, 'utf8'));
        ctx.expect(first.qa_chest.behavior).toEqual([{ on: 'gui.click', layer, do: [] }]);
        ctx.expect(first.qa_chest.layers.some((entry) => entry.id === layer)).toBeTruthy();

        // Script: give the rule a step by typing it
        await page.getByRole('tab', { name: 'Script' }).click();
        const editor = page.getByTestId('script-editor');
        await editor.fill('behavior:\n  - on: gui.click\n    layer: ' + layer + '\n    do:\n      - message: MATERIONE-QA-LINKAGE\n');
        await page.getByTestId('script-state').and(page.locator('[data-state="synced"]')).waitFor({ timeout: 15_000 });
        await save();
        ctx.expect(parse(fs.readFileSync(GUI_FILE, 'utf8')).qa_chest.behavior).toEqual([
          { on: 'gui.click', layer, do: [{ message: 'MATERIONE-QA-LINKAGE' }] },
        ]);

        // Recipes: Close button on the same layer
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.getByTestId('logic-recipes-toggle').click();
        await page.locator('[data-recipe="close_button"]').click();
        await page.getByTestId('logic-recipe-form').getByRole('combobox', { name: 'layer' }).selectOption(layer);
        await page.getByTestId('recipe-insert').click();
        await page.getByTestId('recipe-inserted').waitFor({ state: 'visible', timeout: 15_000 });
        await save();
        ctx.expect(parse(fs.readFileSync(GUI_FILE, 'utf8')).qa_chest.behavior).toEqual([
          { on: 'gui.click', layer, do: [{ message: 'MATERIONE-QA-LINKAGE' }] },
          { on: 'gui.click', layer, do: ['gui.close'] },
        ]);

        // Design: delete the layer, and the dialog's "Delete the rules" takes the rules with it
        await page.getByRole('tab', { name: 'Design' }).click();
        await page.locator('section[aria-label="Layers"] li.row').filter({ has: page.getByTestId('layer-bolt') }).getByRole('button').first().click();
        await page.getByRole('button', { name: 'Delete layer', exact: true }).click();
        await page.getByTestId('delete-layer-rules').click();
        await save();
        const after = parse(fs.readFileSync(GUI_FILE, 'utf8'));
        ctx.expect(after.qa_chest.layers.some((entry) => entry.id === layer)).toBe(false);
        ctx.expect(after.qa_chest.behavior ?? []).toEqual([]);
        ctx.expect(after.qa_chest.title).toBe(starter.qa_chest.title);
      } finally {
        removeFixtureFile();
      }
    },
  }),

  test({
    id: 'gui.logic-linkage-visual',
    title: 'sample:qa_chest studio flow: Design (a Button with its bolt and the Logic section of the inspector), Add click logic into Logic (the trigger with the layer\'s thumbnail and the mini preview on hover), the Script tab with an invalid draft, and the Recipes panel',
    mode: 'visual',
    dependsOn: ['gui.logic-linkage-live-save'],
    covers: [...LOGIC_COVERS, 'webui/src/lib/studio/gui/LayersPanel.svelte', 'webui/src/lib/studio/gui/InspectorPanel.svelte'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        const page = await openNewChest(ctx);
        await page.getByRole('region', { name: 'Components' }).getByRole('button', { name: 'Button' }).click();
        await ctx.browser.browserShot('linkage-design-before');
        await page.getByTestId('add-click-logic').click();
        const trigger = page.locator('.node[data-kind="trigger"][data-node="behavior.0"]');
        await trigger.waitFor({ state: 'visible', timeout: 15_000 });
        await trigger.hover();
        await page.getByTestId('logic-layer-preview').waitFor({ state: 'visible', timeout: 5_000 });
        await ctx.browser.browserShot('linkage-logic-hover', '[data-testid="logic-tab"]');
        await page.getByRole('tab', { name: 'Design' }).click();
        await ctx.browser.browserShot('linkage-design-bolt');
        await page.getByRole('tab', { name: 'Script' }).click();
        await page.getByTestId('script-editor').fill('behavior:\n  - on: gui.click\n    do: [\n');
        await page.getByTestId('script-error').waitFor({ state: 'visible', timeout: 10_000 });
        await ctx.browser.browserShot('linkage-script-error', '[data-testid="script-tab"]');
        await page.getByTestId('script-discard').click();
        await page.getByRole('tab', { name: 'Logic' }).click();
        await page.getByTestId('logic-recipes-toggle').click();
        await page.locator('[data-recipe="close_button"]').waitFor({ state: 'visible', timeout: 15_000 });
        await ctx.sleep(1500); // the panel asks the server which recipes it can run
        await ctx.browser.browserShot('linkage-recipes', '[data-testid="logic-tab"]');
        ctx.needsEyes(
          'Design: the Button row in the layers panel wears an amber bolt after Add click logic, and the inspector has a Logic section ("1 rule answers clicks on this layer", Open in Logic, Add another click rule) in the same panel style as the other sections; Logic: the gui.click trigger shows a small pixel thumbnail of the button and its name where the layer filter line would be, and hovering it shows a mini preview of the whole GUI with the button outlined in amber, bottom-left of the canvas, not covering the node; Script: the YAML editor has a line-number gutter, the invalid draft shows a red line number and a red problem row ("Line N: ...") and the state pill says Not applied; Recipes: a panel left of the canvas lists the eight recipes with a one-line description each, and Pay to receive is dimmed with a Vault reason when no economy is installed; the chrome uses the editor tokens and nothing overflows horizontally.',
        );
      } finally {
        removeFixtureFile();
      }
    },
  }),
];
