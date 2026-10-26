// The M5 editor surface (roadmap M5): command checks use console replies and
// server markers; focused browser entries exercise the real local App through
// its normal trust flow. The transport protocol remains gated by :harness
// integration tests, including the relay gate over the fake mailbox.

import { test } from './_lib.mjs';

const TRANSPORT = 'plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java';
const COMMANDS = 'plugin/src/main/java/dev/materione/plugin/command/EditorCommands.java';
const ADMIN_API = 'plugin/src/main/java/dev/materione/plugin/runtime/api/AdminApiImpl.java';
const BROWSER_COVERS = [
  'webui/src/App.svelte',
  'webui/src/lib/protocol/session.ts',
  'webui/src/lib/components/AppHeader.svelte',
  'webui/src/lib/components/Workbench.svelte',
  'webui/src/lib/components/DocEditor.svelte',
  'webui/src/lib/views/AdvancedView.svelte',
  'webui/src/lib/views/ContentView.svelte',
  'webui/src/lib/views/FileTree.svelte',
  'webui/src/lib/state.svelte.ts',
  'webui/src/lib/drafts.svelte.ts',
  'webui/src/lib/diagnostics.ts',
  'webui/src/lib/views/DiagnosticsView.svelte',
  'webui/src/lib/components/AssetPreviewTab.svelte',
  'webui/src/lib/components/BlockbenchTab.svelte',
  'webui/src/lib/components/SettingsTab.svelte',
  'webui/src/lib/dock/ActivityDock.svelte',
  'webui/src/lib/studio/Studio.svelte',
  'webui/test-support/qa-browser.mjs',
  'qa/runner.mjs',
  'qa/evidence-redaction.mjs',
  'qa/evidence-redaction.test.mjs',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
  'core/src/main/java/dev/materione/core/authoring/ResourceSummaryComputer.java',
  'core/src/main/java/dev/materione/core/document/DocumentStore.java',
  'core/src/main/java/dev/materione/core/document/AssetStore.java',
  'core/src/main/java/dev/materione/core/document/PackWatcher.java',
  'api/src/main/java/dev/materione/api/diagnostics/DiagnosticCodes.java',
  'content/src/main/java/dev/materione/content/items/ItemType.java',
  'api/src/main/java/dev/materione/api/content/ResourcePresentation.java',
  'fixtures/packs/sample/items/ruby_sword.yml',
  'fixtures/packs/corpus/**',
  'plugin/src/main/java/dev/materione/plugin/runtime/EditorTransportService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/ItemAutoUpdater.java',
];
const CATALOG_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/api.ts',
  'webui/src/lib/browser.ts',
  'webui/src/lib/components/ResourceThumbnail.svelte',
  'webui/src/lib/thumbnails.ts',
  'webui/src/lib/state.svelte.ts',
  'webui/src/lib/views/ContentView.svelte',
  'web/src/main/java/dev/materione/web/rpc/ops/EditorContentOps.java',
  'core/src/main/java/dev/materione/core/authoring/AuthoringApiImpl.java',
];
const GLYPH_CATALOG_COVERS = [
  ...CATALOG_COVERS,
  'content/src/main/java/dev/materione/content/glyphs/GlyphType.java',
  'content/src/main/java/dev/materione/content/glyphs/GlyphDefinition.java',
  'fixtures/packs/sample/glyphs/**',
  'fixtures/packs/sample/assets/textures/gui/**',
];
const SUPPORT_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/views/ServerView.svelte',
];
const WORKBENCH_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/api.ts',
  'webui/src/lib/components/LegacyText.svelte',
  'webui/src/lib/components/LegacyTextEditor.svelte',
  'webui/src/lib/components/MaterialPicker.svelte',
  'webui/src/lib/legacy-text.ts',
  'webui/src/lib/materials.ts',
  'webui/src/lib/studio/Field.svelte',
  'webui/src/lib/studio/ItemPreview.svelte',
  'webui/src/lib/studio-visual.ts',
  'webui/src/lib/components/ResourceThumbnail.svelte',
  'webui/src/lib/thumbnails.ts',
  'platform/src/main/java/dev/materione/platform/items/ItemMaterialPalette.java',
  'platform/src/main/java/dev/materione/platform/text/ItemText.java',
];

async function openSampleItemFile(page) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Files', exact: true }).click();
  await page.locator('input.filter').fill('ruby_sword');
  const file = page.locator('button.label[title="items/ruby_sword.yml"]');
  await file.waitFor({ state: 'visible', timeout: 20_000 });
  await file.click();
  await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
    .waitFor({ state: 'visible', timeout: 20_000 });
  await page.locator('article').getByLabel('Display name', { exact: true }).waitFor({ state: 'visible' });
  await waitForDisplayName(page, 'Ruby Sword');
}

const VANILLA_ICON_COVERS = [
  ...CATALOG_COVERS,
  'webui/src/lib/vanilla-icons.ts',
  'webui/src/lib/studio/ItemPreview.svelte',
];

async function openSampleItemCatalog(page) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.locator('input.search').fill('Ruby Sword');
  const row = page.getByRole('option', { name: /Ruby Sword/ });
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  return row;
}

async function waitForDisplayName(page, expected) {
  const field = page.locator('article').getByLabel('Display name', { exact: true });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await field.inputValue() === expected) return;
    await page.waitForTimeout(100);
  }
  throw new Error('sample:ruby_sword display name did not load as expected');
}

async function assertHeldRubySwordName(ctx, expected) {
  let actual = '';
  for (let attempt = 0; attempt < 12; attempt++) {
    await ctx.rcon('clear Probe');
    await ctx.rcon('materione give sample:ruby_sword Probe');
    await ctx.waitForChat(/Given sample:ruby_sword/);
    await ctx.exec('player.getInventory().selected = 0');
    actual = String(await ctx.exec('return player.getMainHandItem().getHoverName().getString()'));
    if (actual === expected) return;
    await ctx.sleep(750);
  }
  ctx.expect(actual).toBe(expected);
}

async function waitForFileApply(page) {
  const editor = page.locator('article');
  await editor.getByRole('button', { name: 'Save & apply' }).waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('article header button')]
      .find((candidate) => candidate.textContent.trim() === 'Save & apply');
    return button instanceof HTMLButtonElement && button.disabled;
  }, undefined, { timeout: 30_000 });
}

export default [
  test({
    id: 'editor.local-url',
    title: '/materione editor local hands out a loopback capability URL (always-available direct mode)',
    covers: [TRANSPORT, COMMANDS, ADMIN_API],
    steps: async (ctx) => {
      // AdminApiImpl resolves the console actor to Bukkit's global console
      // sender, so successful API messages do not come back in the RCON reply.
      // Assert the server-side readiness and URL markers instead; never put a
      // capability URL into assertion output.
      await ctx.rcon('materione editor local');
      await ctx.waitForServerLog(/Editor session ready \(local\)/);
      await ctx.waitForServerLog(/MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/);
    },
  }),

  test({
    id: 'editor.default-surface',
    title: '/materione editor (cloud default) hands out an editor URL — cloud when the relay answers, local with MTR-0050 when it does not',
    covers: [
      TRANSPORT,
      COMMANDS,
      ADMIN_API,
      'web/src/main/java/dev/materione/web/relay/RelaySessions.java',
      'web/src/main/java/dev/materione/web/relay/RelayClient.java',
    ],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      await ctx.rcon('materione editor');
      const marker = await ctx.waitForServerLog(/MATERIONE-EDITOR https?:\/\//);
      const cloudUrl = /MATERIONE-EDITOR https:\/\/[^/\s]+\/\?s=/.test(marker);
      const localUrl = /MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/.test(marker);
      const ready = await ctx.waitForServerLog(/Editor session ready \((cloud|local)\)/);
      const localFallback = ready.includes('(local)');
      // Keep URL-bearing log values out of assertion output.
      ctx.expect(localFallback ? localUrl : cloudUrl).toBeTruthy();
      if (localFallback) {
        await ctx.waitForServerLog(/MTR-0050/);
      }
    },
  }),

  test({
    id: 'editor.status',
    title: '/materione editor status reports the running transport with its mode',
    covers: [TRANSPORT, COMMANDS, ADMIN_API],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione editor status');
      ctx.expect(reply.includes('editor transport: running (')).toBeTruthy();
      ctx.expect(reply.includes('mode cloud')).toBeTruthy();
    },
  }),

  test({
    id: 'editor.approve-unknown-nonce',
    title: 'approving an unknown nonce answers with a fail-soft refusal, not a crash',
    covers: [TRANSPORT, COMMANDS, ADMIN_API],
    dependsOn: ['editor.local-url'],
    steps: async (ctx) => {
      await ctx.rcon('materione editor approve deadbeef');
      await ctx.waitForServerLog(/no pending editor request with nonce deadbeef/);
    },
  }),

  test({
    id: 'editor.browser-support-navigation',
    title: 'sample editor keeps Problems navigable, Activity hidden until toggled, and collapsed rail buttons aligned',
    covers: SUPPORT_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      let pageErrors = 0;
      page.on('pageerror', () => { pageErrors++; });
      ctx.expect(await page.locator('#activity-dock').count()).toBe(0);
      ctx.expect(await page.locator('footer.statusbar').count()).toBe(0);
      const problemsButton = page.getByRole('button', { name: /^Problems(?:, .+)?$/ });
      await problemsButton.click();
      await page.getByRole('region', { name: 'Problems', exact: true })
        .getByRole('button', { name: /^all \d+$/ }).waitFor({ state: 'visible' });
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      const activity = page.getByRole('button', { name: /^Activity(?:, .+)?$/ });
      await activity.click();
      await page.locator('#activity-dock').waitFor({ state: 'visible' });
      await activity.click();
      await page.locator('#activity-dock').waitFor({ state: 'detached' });

      const buttons = page.getByRole('navigation', { name: 'Workspace navigation' }).locator('.nav-group button');
      const before = await buttons.evaluateAll((entries) => entries.map((entry) => entry.getBoundingClientRect().y));
      await page.getByRole('button', { name: 'Collapse navigation labels' }).click();
      const after = await buttons.evaluateAll((entries) => entries.map((entry) => entry.getBoundingClientRect().y));
      ctx.expect(after.length).toBe(before.length);
      ctx.expect(after.every((y, index) => Math.abs(y - before[index]) < 0.5)).toBeTruthy();
      await page.getByRole('button', { name: 'Server', exact: true }).click();
      await page.getByRole('button', { name: /^disconnect$/i }).waitFor({ state: 'visible' });
      await problemsButton.click();
      await page.getByRole('heading', { name: 'Problems', exact: true }).waitFor({ state: 'visible' });
      ctx.expect(pageErrors).toBe(0);
    },
  }),

  test({
    id: 'editor.browser-glyph-thumbnails',
    title: 'sample:heart glyph catalog decodes its source texture in list and grid',
    covers: GLYPH_CATALOG_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('button', { name: 'Glyphs', exact: true }).click();
      await page.getByRole('button', { name: 'Dense visual list' }).click();
      const heart = page.getByRole('option', { name: /Heart/i }).first();
      const image = heart.locator('.thumbnail img');
      await image.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await image.evaluate((entry) => entry.complete && entry.naturalWidth === 8)).toBeTruthy();
      await page.getByRole('button', { name: 'Compact grid' }).click();
      await image.waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await image.evaluate((entry) => entry.complete && entry.naturalHeight === 8)).toBeTruthy();
    },
  }),

  test({
    id: 'editor.browser-glyph-catalog-visual',
    title: 'sample glyph catalog shows source-texture miniatures and the Activity-free editor shell',
    mode: 'visual',
    dependsOn: ['editor.browser-glyph-thumbnails', 'editor.browser-support-navigation'],
    covers: [...GLYPH_CATALOG_COVERS, ...SUPPORT_COVERS],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('button', { name: 'Glyphs', exact: true }).click();
      await page.getByRole('option', { name: /Heart/i }).first()
        .locator('.thumbnail img').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('sample-glyph-texture-miniatures');
      await page.getByRole('button', { name: 'Collapse navigation labels' }).click();
      await page.setViewportSize({ width: 1024, height: 768 });
      await ctx.browser.browserShot('sample-glyphs-collapsed-navigation');
      ctx.needsEyes(
        'the real local App shows recognizable glyph source-texture miniatures, uncluttered footer-free space, no Activity bar until requested, and unchanged vertical navigation alignment after labels collapse; source sheets are texture previews, not proof of final font metrics or client pack acceptance',
      );
    },
  }),

  test({
    id: 'editor.browser-item-workbench-draft',
    title: 'sample:ruby_sword previews formatted name/lore locally and changes the held item only after explicit apply',
    covers: WORKBENCH_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      ctx.expect((await row.locator('.name').innerText()).trim()).toBe('Ruby Sword');
      ctx.expect((await row.locator('.summary').innerText()).trim()).toMatch(/Netherite/i);
      ctx.expect((await row.locator('.health').innerText()).trim()).toMatch(/^(error|unknown)$/i);
      await row.dblclick();

      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });
      const nameField = studio.getByLabel('Display name', { exact: true });
      const loreField = studio.getByLabel('Lore', { exact: true });
      const originalName = await nameField.inputValue();
      const originalLore = await loreField.inputValue();
      const material = studio.getByRole('combobox', { name: 'Base material' });
      await material.fill('netherite sword');
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
      ctx.expect(await material.inputValue()).toBe('Netherite Sword');

      await nameField.fill('&6Ruby Sword Browser Draft');
      await loreField.fill('Burns with inner fire\n\nLocal draft only');
      const previewName = studio.getByRole('button', { name: 'Edit item name' });
      const previewLore = studio.getByRole('button', { name: 'Edit item lore' });
      ctx.expect((await previewName.innerText()).trim()).toBe('Ruby Sword Browser Draft');
      ctx.expect(await previewLore.locator('.lore-line').count()).toBe(3);
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isEnabled()).toBeTruthy();
      await assertHeldRubySwordName(ctx, 'Ruby Sword');
      await studio.getByRole('button', { name: 'Save & apply' }).click();
      await assertHeldRubySwordName(ctx, 'Ruby Sword Browser Draft');
      await nameField.fill(originalName);
      await loreField.fill(originalLore);
      await studio.getByRole('button', { name: 'Save & apply' }).click();
      await assertHeldRubySwordName(ctx, 'Ruby Sword');
    },
  }),

  test({
    id: 'editor.browser-vanilla-item-icons',
    title: 'sample:ruby_disc has no custom visual and shows the pinned vanilla MUSIC_DISC_CAT icon from jsDelivr',
    covers: VANILLA_ICON_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      // Needs outbound access to cdn.jsdelivr.net; the icon is a runtime CDN load, never bundled.
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.locator('input.search').fill('Ruby disc');
      const row = page.getByRole('option', { name: /Ruby disc/i }).first();
      const image = row.locator('.thumbnail.vanilla img');
      await image.waitFor({ state: 'visible', timeout: 20_000 });
      const icon = await image.evaluate((entry) => ({
        src: entry.src, loaded: entry.complete && entry.naturalWidth === 32,
      }));
      ctx.expect(icon.src).toMatch(/^https:\/\/cdn\.jsdelivr\.net\/npm\/minecraft-textures@26\.3\.0\/dist\/textures\/assets\/[0-9a-f]+\.png$/);
      ctx.expect(icon.loaded).toBeTruthy();
      ctx.expect(await row.locator('.thumbnail').getAttribute('title')).toMatch(/Vanilla item icon/);
    },
  }),

  test({
    id: 'editor.browser-vanilla-item-icons-visual',
    title: 'sample item catalog shows recognizable vanilla icons for items without a custom visual',
    mode: 'visual',
    dependsOn: ['editor.browser-vanilla-item-icons'],
    covers: VANILLA_ICON_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('button', { name: 'Compact grid' }).click();
      await page.locator('.thumbnail.vanilla img').first().waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('sample-vanilla-item-icons');
      ctx.needsEyes(
        'items without a custom texture or model (e.g. sample:ruby_disc) show the matching vanilla inventory icon, crisp at grid size; items with their own texture still show it, and unsupported models keep their honest fallback',
      );
    },
  }),

  test({
    id: 'editor.browser-item-catalog-visual',
    title: 'sample:ruby_sword and corpus:gem_red catalog distinguishes unsupported models from textured inventory previews',
    mode: 'visual',
    dependsOn: ['editor.browser-item-workbench-draft'],
    covers: CATALOG_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.getByRole('img').waitFor({ state: 'visible', timeout: 20_000 });
      await row.locator('.thumbnail[aria-busy="false"]').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('sample-ruby-sword-content-catalog');
      await page.getByLabel('project').selectOption('corpus');
      await page.locator('input.search').fill('');
      const gem = page.getByRole('option', { name: /Red Gem/ });
      await gem.locator('.thumbnail img').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('corpus-textured-content-catalog');
      await gem.dblclick();
      await page.getByRole('button', { name: 'Edit item name' }).filter({ hasText: 'Red Gem' })
        .waitFor({ state: 'visible' });
      await page.locator('.item-preview .inventory img').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('corpus-red-gem-inventory-preview');
      ctx.needsEyes(
        'the real local App shows sample:ruby_sword with an honest unsupported-model fallback, then the corpus texture catalog and corpus:gem_red at inventory and enlarged inspection scales; screenshots are App-scoped and contain no capability URL or session material',
      );
    },
  }),

  test({
    id: 'editor.browser-item-workbench-visual',
    title: 'sample:ruby_sword Item Studio presents a browser-only formatted draft and honest game-check status',
    mode: 'visual',
    dependsOn: ['editor.browser-item-workbench-draft'],
    covers: WORKBENCH_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });
      await studio.getByLabel('Display name', { exact: true }).fill('&6Ruby Sword Workbench Draft');
      await studio.getByLabel('Lore', { exact: true }).fill('Burns with inner fire\n\nPreview only');
      await studio.getByRole('combobox', { name: 'Base material' }).fill('netherite sword');
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
      await studio.getByRole('button', { name: 'Edit item name' }).waitFor({ state: 'visible' });
      await ctx.browser.browserShot('sample-ruby-sword-item-workbench');
      ctx.needsEyes(
        'the real local Materione App shows sample:ruby_sword in Item Studio with a 32 px inventory slot, enlarged static visual, live formatted name/lore tooltip, local draft fields and the explicit Save & apply action; any game-check/candidate text must not claim that gameplay or player delivery was verified',
      );
    },
  }),

  test({
    id: 'editor.browser-files-draft-save',
    title: 'sample:ruby_sword Files draft survives Problems and Content navigation, then applies only on Save & apply',
    covers: BROWSER_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      let mayNeedRestore = false;
      let stepsError = null;
      try {
        await openSampleItemFile(page);
        const fileName = page.locator('article').getByLabel('Display name', { exact: true });

        await page.getByRole('button', { name: 'Open in Studio' }).click();
        const studio = page.locator('.studio');
        await studio.waitFor({ state: 'visible' });
        const studioMeta = await studio.locator('header .meta').innerText();
        ctx.expect(studioMeta.split(' · ')[0]).toBe('ruby_sword');
        ctx.expect(await studio.getByLabel('Display name', { exact: true }).inputValue()).toBe('Ruby Sword');
        await page.getByRole('button', { name: /Open in Files/ }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });

        await page.locator('article').getByLabel('Display name', { exact: true }).fill('Ruby Sword QA Draft');
        await page.locator('article').getByLabel('Display name', { exact: true }).press('Tab');
        const save = page.locator('article').getByRole('button', { name: 'Save & apply' });
        ctx.expect(await save.isDisabled()).toBe(false);

        await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).click();
        await page.getByRole('heading', { name: 'Problems', exact: true }).waitFor({ state: 'visible' });
        await page.getByRole('button', { name: 'Files', exact: true }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });
        ctx.expect(await fileName.inputValue()).toBe('Ruby Sword QA Draft');

        await page.getByRole('button', { name: 'Content', exact: true }).click();
        await studio.waitFor({ state: 'visible' });
        ctx.expect((await studio.locator('header .meta').innerText()).split(' · ')[0])
          .toBe('ruby_sword');
        ctx.expect(await studio.getByLabel('Display name', { exact: true }).inputValue()).toBe('Ruby Sword QA Draft');
        await page.getByRole('button', { name: /Open in Files/ }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });
        ctx.expect(await fileName.inputValue()).toBe('Ruby Sword QA Draft');

        await assertHeldRubySwordName(ctx, 'Ruby Sword');
        mayNeedRestore = true;
        await page.locator('article').getByRole('button', { name: 'Save & apply' }).click();
        await waitForFileApply(page);
        await ctx.waitForServerLog(/MTR-0012: applied: /, { timeoutMs: 45_000 });
        await assertHeldRubySwordName(ctx, 'Ruby Sword QA Draft');
      } catch (error) {
        stepsError = error;
        throw error;
      } finally {
        if (mayNeedRestore) {
          try {
            const currentPage = ctx.browser.page;
            const field = currentPage.locator('article').getByLabel('Display name', { exact: true });
            if (await field.isVisible() && (await field.inputValue()) !== 'Ruby Sword') {
              await field.fill('Ruby Sword');
              await field.press('Tab');
              await currentPage.locator('article').getByRole('button', { name: 'Save & apply' }).click();
              await waitForFileApply(currentPage);
            }
            await assertHeldRubySwordName(ctx, 'Ruby Sword');
          } catch {
            if (!stepsError) throw new Error('sample:ruby_sword could not be restored in the disposable probe copy');
          }
        }
      }
    },
  }),

  test({
    id: 'editor.browser-files-visual',
    title: 'sample:ruby_sword Files form presents a staged draft and distinct apply and publish actions',
    mode: 'visual',
    dependsOn: ['editor.browser-files-draft-save'],
    covers: BROWSER_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleItemFile(page);
      await page.locator('article').getByLabel('Display name', { exact: true }).fill('Ruby Sword Visual Draft');
      await page.locator('article').getByLabel('Display name', { exact: true }).press('Tab');
      await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).waitFor({ state: 'visible' });
      await page.getByRole('button', { name: 'Publish pack', exact: true }).waitFor({ state: 'visible' });
      await ctx.browser.browserShot('sample-ruby-sword-files-draft');
      ctx.needsEyes(
        'the real local Materione App shows sample:ruby_sword in Files with the staged Ruby Sword Visual Draft clearly unsaved; navigation reads Content, Files, Problems, the file header offers Open in Studio, Save & apply is distinct from Publish pack, and the screenshot contains no capability URL or session material',
      );
    },
  }),

  test({
    id: 'editor.revoke',
    title: 'revoking answers on every selector — bare (sessions), workstations, and an unknown prefix',
    covers: [
      TRANSPORT,
      COMMANDS,
      ADMIN_API,
      'web/src/main/java/dev/materione/web/EditorWorkstations.java',
    ],
    dependsOn: ['editor.default-surface'],
    steps: async (ctx) => {
      // Runs after default-surface so the session that test opened is swept.
      await ctx.rcon('materione editor revoke');
      await ctx.waitForServerLog(/all editor sessions revoked/);
      await ctx.rcon('materione editor revoke workstations');
      await ctx.waitForServerLog(/all trusted workstations revoked/);
      await ctx.rcon('materione editor revoke zz');
      await ctx.waitForServerLog(/no trusted workstation starts with zz/);
    },
  }),
];
