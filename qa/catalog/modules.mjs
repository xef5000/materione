// Per-type display + modules studios (issue #153, building on the item studio of
// issue #152): every content type is a display card plus module cards derived
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
const RECIPE_COVERS = [
  ...MODULE_COVERS,
  'webui/src/lib/studio/RecipeGrid.svelte',
  'webui/src/lib/studio/RecipePreview.svelte',
  'content/src/main/java/dev/materione/content/recipes/RecipeType.java',
  'fixtures/packs/sample/recipes/**',
];
const GLYPH_COVERS = [
  ...MODULE_COVERS,
  'webui/src/lib/studio/GlyphPreview.svelte',
  'webui/src/lib/studio/FrameStrip.svelte',
  'webui/src/lib/studio/ChipsEditor.svelte',
  'webui/src/lib/studio/TextureThumb.svelte',
  'content/src/main/java/dev/materione/content/glyphs/GlyphType.java',
  'fixtures/packs/sample/glyphs/**',
];
const PAINTING_COVERS = [
  ...MODULE_COVERS,
  'webui/src/lib/studio/PaintingPreview.svelte',
  'content/src/main/java/dev/materione/content/paintings/PaintingType.java',
  'fixtures/packs/sample/paintings/**',
];
const SOUND_COVERS = [
  ...MODULE_COVERS,
  'webui/src/lib/audio.ts',
  'webui/src/lib/studio/SoundSources.svelte',
  'webui/src/lib/studio/SoundSummary.svelte',
  'webui/src/lib/studio/ObjectFields.svelte',
  'content/src/main/java/dev/materione/content/sounds/SoundType.java',
  'fixtures/packs/sample/sounds/**',
];
const EFFECT_COVERS = [
  ...MODULE_COVERS,
  'webui/src/lib/effects.ts',
  'webui/src/lib/studio/EffectPreview.svelte',
  'webui/src/lib/studio/PaletteEditor.svelte',
  'content/src/main/java/dev/materione/content/effects/EffectType.java',
  'fixtures/packs/sample/effects/**',
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

      // Add Drops through the picker, then an item chip from the item picker.
      await studio.getByRole('button', { name: 'Add module' }).click();
      const picker = page.getByRole('dialog', { name: 'Add a module' });
      await picker.getByLabel('Search modules').fill('drops');
      await picker.getByLabel('Search modules').press('Enter');
      const drops = studio.locator('[data-module="drops"]');
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
      await drops.getByRole('button', { name: 'Remove Drops' }).click();
      await save.click();
      await waitForSaved(page);
      ctx.expect(await studio.locator('[data-module="drops"]').count()).toBe(0);
      ctx.expect(await studio.locator('[data-module="blast_resistant"]').count()).toBe(1);
    },
  }),

  test({
    id: 'modules.browser-block-modules-visual',
    title: 'the block studio reads as a display card plus Shape, Drops and world-policy cards with item chips',
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
        'block-modules-studio shows the display card (name, texture/model slots, preview) then one compact card per module - Explosion resistant (a checkbox), Placed by item (an item chip with the Ruby icon and an x) and When... do... - each with its Lucide icon; block-modules-picker lists Shape, Drops, Explosion resistant, When pushed by pistons, Placed by item and When... do... with one-line descriptions, grouped under Appearance, Gameplay and Behaviour chips' + IDENTITY_EYES,
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

      const seats = studio.locator('[data-module="seats"]');
      await seats.waitFor({ state: 'visible', timeout: 20_000 });
      const front = seats.getByRole('group', { name: 'Seats, front view' });
      const handle = front.getByRole('button', { name: /^Seats 1 at x 0, y 0.3, z 0/ });
      await handle.waitFor({ state: 'visible' });

      // Arrow keys move a handle by 5 cm and rewrite only that row's offset.
      await handle.focus();
      await handle.press('ArrowRight');
      await front.getByRole('button', { name: /^Seats 1 at x 0.05, y 0.3, z 0/ }).waitFor({ state: 'visible' });
      await front.getByRole('button', { name: /^Seats 1/ }).press('ArrowLeft');
      await front.getByRole('button', { name: /^Seats 1 at x 0, y 0.3, z 0/ }).waitFor({ state: 'visible' });

      // Rotation is a set of labelled numbers, not a free number box.
      const rotation = studio.locator('[data-module="rotation"]');
      ctx.expect(await rotation.getByRole('radio', { name: '90°' }).getAttribute('aria-checked')).toBe('true');

      // Storage: add (with its slot range and unit), save, remove, save.
      await studio.getByRole('button', { name: 'Add module' }).click();
      const picker = page.getByRole('dialog', { name: 'Add a module' });
      await picker.getByLabel('Search modules').fill('storage');
      await picker.getByLabel('Search modules').press('Enter');
      const storage = studio.locator('[data-module="storage"]');
      ctx.expect(await storage.innerText()).toContain('slots');
      await storage.getByRole('spinbutton', { name: 'Storage' }).fill('27');
      await storage.getByRole('spinbutton', { name: 'Storage' }).press('Tab');
      const save = studio.getByRole('button', { name: 'Save & apply' });
      await save.click();
      await waitForSaved(page);
      await storage.getByRole('button', { name: 'Remove Storage' }).click();
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
      await studio.locator('[data-module="seats"] svg').first().waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('furniture-modules-studio', '.studio');
      ctx.needsEyes(
        'furniture-modules-studio shows the display card then Rotation snapping (Free / 45 / 90 / 180 choices, 90 selected), Hitbox and Seats cards - each with a front (x, y) and a top (x, z) view on a one-block grid, a 1x1 block outline, an orange draggable handle numbered 1 (the hitbox also a shaded rectangle) - and the exact numeric rows below the pictures' + IDENTITY_EYES,
      );
    },
  }),

  // The sample entries below only edit drafts: nothing is saved, so the pack is untouched.
  test({
    id: 'modules.browser-recipe-studio',
    title: 'sample:ruby_sword reads as a mode switch, a crafting grid of item slots and a result card',
    covers: RECIPE_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Recipes', 'ruby_sword');
      const display = studio.getByRole('article', { name: 'Display' });
      const mode = display.getByRole('radiogroup', { name: 'Recipe kind' });
      await mode.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await mode.getByRole('radio', { name: 'Shaped' }).getAttribute('aria-checked')).toBe('true');
      const grid = display.getByRole('group', { name: 'Crafting grid' });
      await grid.getByRole('button', { name: /^Slot 1,2: Ruby/ }).waitFor({ state: 'visible' });
      await grid.getByRole('button', { name: 'Slot 3,2: Stick' }).waitFor({ state: 'visible' });

      // Switching the mode swaps the body; the shaped keys stay in the file.
      await mode.getByRole('radio', { name: 'Smelting' }).click();
      await display.getByRole('button', { name: /^Smelts:/ }).waitFor({ state: 'visible' });
      ctx.expect(await display.getByRole('group', { name: 'Crafting grid' }).count()).toBe(0);
    },
  }),

  test({
    id: 'modules.browser-recipe-studio-visual',
    title: 'the recipe studio shows the crafting preview card next to the mode switch and the item grid',
    mode: 'visual',
    dependsOn: ['modules.browser-recipe-studio'],
    covers: RECIPE_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Recipes', 'ruby_sword');
      await studio.getByRole('group', { name: 'Crafting grid' }).waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('recipe-studio', '.studio');
      ctx.needsEyes(
        'recipe-studio shows, left, a Crafting table preview (a 3x3 grid with the Ruby and Stick icons in the sword shape, an arrow, and the result card with the Ruby Sword icon and name); right, the display card with the Shaped / Shapeless / Smelting segmented choice (Shaped selected), the Result item chip with its icon, and the 3x3 crafting grid whose filled slots show real item icons (the vanilla stick icon from the CDN, the pack items with their own visuals)' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'modules.browser-glyph-studio',
    title: 'sample:heart previews inline in chat and an item name; size presets, baseline, placeholder chips and chat modules',
    covers: GLYPH_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Glyphs', 'heart');
      await studio.locator('[data-context="chat"]').waitFor({ state: 'visible', timeout: 20_000 });
      await studio.locator('[data-context="item-name"]').waitFor({ state: 'visible' });
      const size = studio.locator('[data-module="height"]');
      ctx.expect(await size.getByRole('radio', { name: 'Emoji' }).getAttribute('aria-checked')).toBe('true');
      await size.getByRole('radio', { name: 'Big title' }).click();
      ctx.expect(await size.getByRole('spinbutton', { name: 'Size (custom)' }).inputValue()).toBe('24');
      const placeholders = studio.locator('[data-module="placeholders"]');
      await placeholders.locator('[data-chip=":heart:"]').waitFor({ state: 'visible' });
      ctx.expect(await studio.locator('[data-module="emoji"]').count()).toBe(1);
    },
  }),

  test({
    id: 'modules.browser-glyph-studio-visual',
    title: 'the glyph studio shows the glyph in a chat line and an item name next to Size, Baseline and chip modules',
    mode: 'visual',
    dependsOn: ['modules.browser-glyph-studio'],
    covers: GLYPH_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Glyphs', 'heart');
      await studio.locator('[data-context="chat"] img').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('glyph-studio', '.studio');
      ctx.needsEyes(
        'glyph-studio shows the zoomed glyph on a checkerboard, below it a dark chat line (<Steve> look at this + the heart image + nice) and an item-name tooltip (Ruby + the heart + Sword) in the Minecraft font with the glyph baseline-aligned at 2x text size; the modules are Baseline (a number with px), Size (Emoji / Inline icon / Big title choices plus a custom px box), Chat placeholders (rounded chips with a small x and a text entry) and Emoji mode' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'modules.browser-painting-studio',
    title: 'sample:sunrise resizes by dragging a corner on a block-scale wall; width and height follow',
    covers: PAINTING_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Paintings', 'sunrise');
      const handle = studio.getByRole('slider', { name: 'Painting size in blocks' });
      await handle.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await handle.getAttribute('aria-valuetext')).toBe('2 by 1 blocks');
      await handle.focus();
      await handle.press('ArrowDown');
      await handle.press('ArrowRight');
      ctx.expect(await handle.getAttribute('aria-valuetext')).toBe('3 by 2 blocks');
      ctx.expect(await studio.getByRole('spinbutton', { name: 'Width' }).inputValue()).toBe('3');
      ctx.expect(await studio.getByRole('spinbutton', { name: 'Height' }).inputValue()).toBe('2');
    },
  }),

  test({
    id: 'modules.browser-painting-studio-visual',
    title: 'the painting studio draws the painting on a block grid wall with a corner handle beside the size fields',
    mode: 'visual',
    dependsOn: ['modules.browser-painting-studio'],
    covers: PAINTING_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Paintings', 'sunrise');
      await studio.getByRole('slider', { name: 'Painting size in blocks' }).waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('painting-studio', '.studio');
      ctx.needsEyes(
        'painting-studio shows, left, a grid wall (one square per block) with the Sunrise texture filling exactly a 2x1 block rectangle outlined in the accent color and a small accent handle on its bottom-right corner; right, the display card with Title and Author as rich text, the texture slot, and Width / Height number boxes with a blocks unit and a 1-16 range, then a Random placement card' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'modules.browser-sound-studio',
    title: 'sample:anthem lists its sources with play, waveform and duration; the music-disc module wraps the jukebox fields',
    covers: SOUND_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Sounds', 'anthem');
      const row = studio.locator('[data-source="ui/anthem"]');
      await row.getByRole('img', { name: 'Waveform of ui/anthem' }).waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect((await row.locator('[data-duration]').innerText()).trim()).toMatch(/^\d+(\.\d)? s$|^\d+:\d\d$/);
      const play = row.getByRole('button', { name: 'Play ui/anthem' });
      ctx.expect(await play.isEnabled()).toBe(true);

      const disc = studio.locator('[data-module="jukebox"]');
      await disc.waitFor({ state: 'visible' });
      ctx.expect(await disc.innerText()).toContain('Music disc');
      ctx.expect(await disc.getByRole('textbox', { name: 'Song title' }).inputValue()).toBe('Ruby Anthem');
      ctx.expect(await disc.getByRole('spinbutton', { name: 'Length' }).inputValue()).toBe('90');
      ctx.expect(await disc.getByRole('spinbutton', { name: 'Comparator signal' }).inputValue()).toBe('7');

      // Subtitle presets fill the box (draft only; nothing is saved).
      await studio.getByRole('group', { name: 'Subtitle suggestions' }).getByRole('button', { name: 'Click' }).click();
      ctx.expect(await studio.getByRole('textbox', { name: 'Subtitle' }).inputValue()).toBe('Something clicks');
    },
  }),

  test({
    id: 'modules.browser-sound-studio-visual',
    title: 'the sound studio shows source rows with a waveform and a Music disc card',
    mode: 'visual',
    dependsOn: ['modules.browser-sound-studio'],
    covers: SOUND_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Sounds', 'anthem');
      await studio.getByRole('img', { name: 'Waveform of ui/anthem' }).waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('sound-studio', '.studio');
      ctx.needsEyes(
        'sound-studio shows the Sources card with one row per source (a round play button, the file name, an accent-colored waveform, its duration, Replace and an x), an Upload .ogg button, the Subtitle box with suggestion chips below it, Volume and Pitch number boxes with a multiplication unit and ranges, and below a Music disc card with Song title, Length (seconds), Comparator signal (0-15) and Hearing range (blocks) boxes' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'modules.browser-effect-studio',
    title: 'sample:rainbow previews live on sample text; kind, speed and palette chips drive it',
    covers: EFFECT_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Text effects', 'rainbow');
      const preview = studio.getByRole('region', { name: 'Effect preview' });
      await preview.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await preview.getAttribute('data-effect-kind')).toBe('rainbow');
      const first = preview.locator('[data-ch="0"]');
      const before = await first.evaluate((element) => getComputedStyle(element).color);
      let changed = false;
      for (let attempt = 0; attempt < 20 && !changed; attempt++) {
        await page.waitForTimeout(100);
        changed = (await first.evaluate((element) => getComputedStyle(element).color)) !== before;
      }
      ctx.expect(changed).toBe(true);

      const kind = studio.getByRole('radiogroup', { name: 'Kind' });
      await kind.getByRole('radio', { name: 'Wave' }).click();
      ctx.expect(await preview.getAttribute('data-effect-kind')).toBe('wave');
      await kind.getByRole('radio', { name: 'Rainbow' }).click();
      ctx.expect(await studio.locator('[data-module="colors"] [data-color]').count()).toBeGreaterThan(0);
    },
  }),

  test({
    id: 'modules.browser-effect-studio-visual',
    title: 'the effect studio shows animated sample text beside the kind choice and palette chips',
    mode: 'visual',
    dependsOn: ['modules.browser-effect-studio'],
    covers: EFFECT_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleResource(page, 'Text effects', 'rainbow');
      await studio.getByRole('region', { name: 'Effect preview' }).waitFor({ state: 'visible', timeout: 20_000 });
      await page.waitForTimeout(600);
      await ctx.browser.browserShot('effect-studio', '.studio');
      ctx.needsEyes(
        'effect-studio shows, left, a dark stage with the sample text in the Minecraft font whose letters carry flowing rainbow colors, a sample-text box and a pause button; right, the display card with the Rainbow / Wave / Shake / Pulse segmented choice (Rainbow selected) and Speed and Strength number boxes with ranges, and a Palette card of round color swatch chips with hex codes, an x each, and an Add color button' + IDENTITY_EYES,
      );
    },
  }),
];
