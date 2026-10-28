// The M5 editor surface (roadmap M5): command checks use console replies and
// server markers; focused browser entries exercise the real local App through
// its normal trust flow. The transport protocol remains gated by :harness
// integration tests, including the relay gate over the fake mailbox.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// What every visual pass also checks now that the editor wears the Materione identity
// (docs/architecture/web.md §6.1): tokens, not ad-hoc colors, sizes or fonts.
const IDENTITY_EYES = ' Also: the Materione identity (docs/architecture/web.md §6.1) - navy surfaces, the orange accent only on the primary action and focus, Inter type, and text and focus that keep WCAG AA contrast.';
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
// The cloud landing card and demo (web.md §6, cloud landing) ride the same SPA build the
// plugin serves; these entries open that build from the local listener and
// simulate the Cloudflare `_headers` host marker where the cloud is meant.
const LANDING_COVERS = [
  'webui/src/App.svelte',
  'webui/src/lib/host.ts',
  'webui/src/lib/demo/**',
  'fixtures/golden/editor-demo-snapshot.json',
  'plugin/src/test/java/dev/materione/plugin/runtime/EditorDemoSnapshotTest.java',
  'webui/src/lib/components/Landing.svelte',
  'webui/src/lib/components/Login.svelte',
  'webui/public/_headers',
  'webui/public/host.json',
  'webui/test-support/qa-browser.mjs',
  'web/src/main/java/dev/materione/web/EditorListener.java',
];

async function serveLikeCloud(page) {
  await page.route('**/host.json', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { 'X-Materione-Host': 'cloud' },
    body: '{}',
  }));
}

async function openListenerRoot(ctx, search = '') {
  const page = await ctx.browser.openEditor();
  const origin = new URL(page.url()).origin;
  return { page, root: `${origin}/${search}` };
}

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
// The probe server's own content copy (the repo fixtures are never touched).
const SERVER_SAMPLE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs', 'sample');

const GUIDED_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/api.ts',
  'webui/src/lib/behavior.ts',
  'webui/src/lib/studio.ts',
  'webui/src/lib/views/StartView.svelte',
  'webui/src/lib/studio/MechanicsEditor.svelte',
  'webui/src/lib/studio/BehaviorRulesEditor.svelte',
  'webui/src/lib/studio/Field.svelte',
  'webui/src/lib/components/AssetPicker.svelte',
  'webui/src/lib/studio/TextureEditor.svelte',
  'api/src/main/java/dev/materione/api/registry/BehaviorVocabulary.java',
  'api/src/main/java/dev/materione/api/registry/SelfDescription.java',
  'api/src/main/java/dev/materione/api/diagnostics/Diagnostic.java',
  'api/src/main/java/dev/materione/api/diagnostics/DiagnosticTarget.java',
  'behavior/src/main/java/dev/materione/behavior/rule/EventTypes.java',
  'behavior/src/main/java/dev/materione/behavior/rule/RuleParser.java',
  'behavior/src/main/java/dev/materione/behavior/runtime/RuleEngine.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/BukkitActionExecutors.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/builtin/**',
  'core/src/main/java/dev/materione/core/pipeline/TemplateResolver.java',
  'core/src/main/java/dev/materione/core/document/LosslessYamlDocument.java',
  'core/src/main/java/dev/materione/core/pipeline/validate/ReferenceValidator.java',
  'core/src/main/java/dev/materione/core/pipeline/validate/ValidateStage.java',
  'web/src/main/java/dev/materione/web/SessionPush.java',
  'fixtures/packs/sample/items/templated_ruby.yml',
  'fixtures/packs/sample/templates/base_item.yml',
];

const ASSETS_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/api.ts',
  'webui/src/lib/bulk.ts',
  'webui/src/lib/bulk-io.ts',
  'webui/src/lib/views/AssetsView.svelte',
  'webui/src/lib/components/BulkDialog.svelte',
  'webui/src/lib/components/CommandPalette.svelte',
  'webui/src/lib/components/AssetPicker.svelte',
  'core/src/main/java/dev/materione/core/authoring/ReferenceScanner.java',
  'fixtures/packs/sample/items/ruby.yml',
  'fixtures/packs/sample/items/templated_ruby.yml',
  'fixtures/packs/sample/assets/textures/item/ruby.png',
];

async function openAssets(page, name) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Assets', exact: true }).click();
  await page.getByRole('option', { name: new RegExp(name) }).click();
  const detail = page.getByRole('region', { name: 'Selected asset' });
  await detail.getByRole('heading', { name: /^Used by/ }).waitFor({ state: 'visible', timeout: 20_000 });
  return detail;
}

async function openSampleStudio(page, name) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.locator('input.search').fill(name);
  const row = page.getByRole('option', { name: new RegExp(name) });
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  await row.dblclick();
  const studio = page.locator('.studio');
  await studio.waitFor({ state: 'visible' });
  return studio;
}

async function heldMaterial(ctx, id) {
  await ctx.rcon('clear Probe');
  await ctx.rcon(`materione give ${id} Probe`);
  await ctx.waitForChat(new RegExp(`Given ${id}`));
  await ctx.exec('player.getInventory().selected = 0');
  return String(await ctx.exec('return player.getInventory().items.get(0).getItem().getDescriptionId()'));
}

/** Polls the given item's in-game material until it matches (the apply lands after the reload marker). */
async function expectHeldMaterial(ctx, id, expected) {
  const deadline = Date.now() + 30_000;
  let actual = '';
  while (Date.now() < deadline) {
    try {
      actual = await heldMaterial(ctx, id);
      if (actual === expected) break;
    } catch (e) {
      actual = String(e?.message ?? e);
    }
    await ctx.sleep(750);
  }
  ctx.expect(actual).toBe(expected);
}

const SUPPORT_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/views/ServerView.svelte',
];
// Every icon the editor draws (issue #149): the shared Icon component, its
// icon registry, and each surface that places one.
const ICON_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/components/Icon.svelte',
  'webui/src/lib/components/icons.ts',
  'webui/src/lib/components/TabBar.svelte',
  'webui/src/lib/components/ResourceThumbnail.svelte',
  'webui/src/lib/views/FileTree.svelte',
  'webui/src/lib/views/StartView.svelte',
  'webui/src/lib/studio/Studio.svelte',
  'webui/src/lib/studio/TextureEditor.svelte',
  'webui/src/lib/studio/StructuredList.svelte',
  'webui/src/lib/studio/ModelWorkbench.svelte',
  'webui/src/lib/dock/ActivityDock.svelte',
  'webui/test/icons.spec.ts',
];
const WORKBENCH_COVERS = [
  ...BROWSER_COVERS,
  'webui/src/lib/api.ts',
  'webui/src/lib/components/RichText.svelte',
  'webui/src/lib/components/RichTextEditor.svelte',
  'webui/src/lib/components/MaterialPicker.svelte',
  'webui/src/lib/rich-text/**',
  'webui/src/lib/materials.ts',
  'webui/src/lib/studio/Field.svelte',
  'webui/src/lib/studio/ItemPreview.svelte',
  'webui/src/lib/studio/ItemAppearance.svelte',
  'webui/src/lib/studio/AssetExplorer.svelte',
  'webui/src/lib/asset-choice.ts',
  'webui/src/lib/studio-visual.ts',
  'webui/src/lib/components/ResourceThumbnail.svelte',
  'webui/src/lib/thumbnails.ts',
  'platform/src/main/java/dev/materione/platform/items/ItemMaterialPalette.java',
  'platform/src/main/java/dev/materione/platform/text/ItemText.java',
];
// Purposeful motion (issue #155, web.md "Motion"): the editor's transitions, and the reduced-motion path that turns them off.
const MOTION_COVERS = [
  ...WORKBENCH_COVERS,
  'webui/src/app.css',
  'webui/src/lib/motion.ts',
  'webui/src/lib/ui/Dialog.svelte',
  'webui/src/lib/ui/Skeleton.svelte',
  'webui/src/lib/studio/ModulePicker.svelte',
  'webui/src/lib/studio/ModulesArea.svelte',
  'webui/src/lib/studio/StructuredList.svelte',
  'webui/src/lib/views/ContentView.svelte',
  'webui/src/lib/components/BlockbenchTab.svelte',
  'webui/src/lib/components/ContextMenu.svelte',
  'webui/src/lib/components/CommandPalette.svelte',
  'webui/playwright.config.ts',
  'webui/browser/specs/motion.pw.ts',
];
// Rich text (issue #151, master §5.7): the editor writes MiniMessage, the one plugin
// pipeline draws it, and both read the shared fixture.
const RICH_TEXT_COVERS = [
  ...WORKBENCH_COVERS,
  'webui/src/app.css',
  'webui/src/assets/fonts/Minecraftia.ttf',
  'platform/src/main/java/dev/materione/platform/text/MaterioneText.java',
  'platform/src/main/java/dev/materione/platform/items/MaterioneStacks.java',
  'fixtures/text/rich-text.json',
];

/** A rich text field's text as the author reads it, one line per lore line. */
async function fieldText(field) {
  return (await field.innerText()).replace(/\n$/, '');
}

/** Replaces a rich text field's content by typing, Enter between lines, like an author. */
async function typeLines(page, field, lines) {
  await field.click();
  await field.press('Control+A');
  await page.keyboard.press('Delete');
  for (const [index, line] of lines.entries()) {
    if (index > 0) await page.keyboard.press('Enter');
    if (line) await page.keyboard.type(line);
  }
}

/** The held sample:ruby_sword's name as the client styles it: `rrggbb=text|` per flat run. */
async function heldNameRuns(ctx) {
  // indexed Groovy only: DebugBridge wraps collections, closures do not resolve
  return String(await ctx.exec([
    'def flat = player.getMainHandItem().getHoverName().toFlatList()',
    "def out = ''",
    'for (int i = 0; i < flat.size(); i++) {',
    '  def part = flat.get(i)',
    '  def color = part.getStyle().getColor()',
    "  out += (color == null ? 'none' : String.format('%06x', color.getValue())) + '=' + part.getString() + '|'",
    '}',
    'return out',
  ].join('\n')));
}

/** Selects the whole Display name in Studio and applies the default gradient from the toolbar. */
async function applyNameGradient(page, studio) {
  const name = studio.getByLabel('Display name', { exact: true });
  await name.click();
  await name.press('Control+A');
  await page.getByRole('button', { name: 'Display name: Gradient' }).click();
  await page.getByRole('button', { name: 'Apply gradient' }).click();
}

/** Puts sample:ruby_sword's plain name back in the disposable probe copy. */
async function restoreRubySwordName(ctx, page) {
  const studio = page.locator('.studio');
  await typeLines(page, studio.getByLabel('Display name', { exact: true }), ['Ruby Sword']);
  const save = studio.getByRole('button', { name: 'Save & apply' });
  if (await save.isEnabled()) await save.click();
  await assertHeldRubySwordName(ctx, 'Ruby Sword');
}

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

const MODEL_PREVIEW_COVERS = [
  ...CATALOG_COVERS,
  'webui/src/lib/model-render.ts',
  'webui/src/lib/model-preview.ts',
  'webui/src/lib/studio/StudioPreview.svelte',
  'webui/src/lib/studio/Studio.svelte',
  'api/src/main/java/dev/materione/api/content/ResourcePresentation.java',
  'api/src/main/java/dev/materione/api/authoring/AuthoringApi.java',
  'content/src/main/java/dev/materione/content/blocks/BlockType.java',
  'content/src/main/java/dev/materione/content/furniture/FurnitureType.java',
  'core/src/main/java/dev/materione/core/authoring/ResourceSummaryComputer.java',
  'fixtures/packs/sample/blocks/**',
  'fixtures/packs/sample/furnitures/**',
];

async function openSampleItemCatalog(page) {
  await page.getByLabel('project').selectOption('sample');
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.locator('input.search').fill('Ruby Sword');
  const row = page.getByRole('option', { name: /Ruby Sword/ });
  await row.waitFor({ state: 'visible', timeout: 20_000 });
  return row;
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

async function waitForDisplayName(page, expected) {
  const field = page.locator('article').getByLabel('Display name', { exact: true });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await fieldText(field) === expected) return;
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
    id: 'editor.browser-rapid-saves',
    title: 'rapid sample:ruby_sword Files saves keep the final name live after the queued reload completes',
    covers: [
      ...BROWSER_COVERS,
      'plugin/src/main/java/dev/materione/plugin/runtime/api/PipelineApiImpl.java',
      'core/src/main/java/dev/materione/core/pipeline/PipelineRunner.java',
      'core/src/main/java/dev/materione/core/pipeline/ParseStage.java',
      'pack/src/main/java/dev/materione/pack/compile/PackCompiler.java',
    ],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const file = path.join(SERVER_SAMPLE, 'items', 'ruby_sword.yml');
      const original = fs.readFileSync(file);
      try {
        await openSampleItemFile(page);
        const editor = page.locator('article');
        const field = editor.getByLabel('Display name', { exact: true });
        for (const name of ['Ruby QA First', 'Ruby QA Second', 'Ruby QA Final']) {
          await field.fill(name);
          await field.press('Tab');
          await editor.getByRole('button', { name: 'Save & apply' }).click();
          await waitForFileApply(page);
        }
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await assertHeldRubySwordName(ctx, 'Ruby QA Final');
      } finally {
        fs.writeFileSync(file, original);
        await ctx.rcon('materione reload');
        await assertHeldRubySwordName(ctx, 'Ruby Sword');
      }
    },
  }),

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
        'the real local App shows recognizable glyph source-texture miniatures, uncluttered footer-free space, no Activity bar until requested, and unchanged vertical navigation alignment after labels collapse; source sheets are texture previews, not proof of final font metrics or client pack acceptance' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-motion-reduced',
    title: 'with prefers-reduced-motion the editor stops its motion: the tokens read 0ms, and a dialog opens with no running animation',
    covers: MOTION_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.emulateMedia({ reducedMotion: 'reduce' });
      const tokens = await page.evaluate(() => ['fast', 'base', 'slow']
        .map((name) => getComputedStyle(document.documentElement).getPropertyValue(`--motion-${name}`).trim()));
      ctx.expect(tokens).toEqual(['0ms', '0ms', '0ms']);
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('button', { name: 'New folder' }).click();
      await page.getByRole('dialog', { name: 'New folder' }).waitFor({ state: 'visible', timeout: 20_000 });
      const running = await page.evaluate(() => document.getAnimations()
        .filter((animation) => Number(animation.effect?.getTiming().duration ?? 0) > 0).length);
      ctx.expect(running).toBe(0);
      await page.keyboard.press('Escape');
    },
  }),

  test({
    id: 'editor.browser-motion-visual',
    title: 'the module picker opens with its rows staggered in, and the settled picker is still and legible',
    mode: 'visual',
    dependsOn: ['editor.browser-motion-reduced'],
    covers: MOTION_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await openSampleItemFile(page);
      await page.getByRole('button', { name: 'Add module' }).click();
      await page.getByLabel('Search modules').waitFor({ state: 'visible', timeout: 20_000 });
      // The stagger lasts about 300 ms; the settled frame is what the eyes judge.
      await page.waitForTimeout(500);
      await ctx.browser.browserShot('sample-motion-module-picker');
      ctx.needsEyes(
        'the module picker is fully opaque and unclipped once settled, its rows are evenly spaced with no half-faded row, and the accent appears only on the focused control or the active row' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-icon-set',
    title: 'sample editor draws its rail, type tabs, toolbars and Start tiles from one icon set, and icon-only controls keep their names',
    covers: ICON_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      const rail = page.getByRole('navigation', { name: 'Workspace navigation' });
      // Four authoring views, three support views and the labels toggle.
      ctx.expect(await rail.locator('svg.lucide-icon').count()).toBe(8);
      ctx.expect(await rail.getByRole('button', { name: 'Content', exact: true }).locator('svg.lucide-icon').count()).toBe(1);
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      const types = page.getByRole('navigation', { name: 'content types' });
      const typeButtons = await types.locator('button').count();
      ctx.expect(typeButtons).toBeGreaterThan(1);
      ctx.expect(await types.locator('button svg.lucide-icon').count()).toBe(typeButtons);
      ctx.expect(await page.getByRole('button', { name: 'Back', exact: true }).locator('svg.lucide-icon').count()).toBe(1);
      ctx.expect(await page.getByRole('button', { name: 'Up one folder', exact: true }).count()).toBe(1);
      ctx.expect(await page.getByRole('button', { name: 'Dense visual list' }).locator('svg.lucide-icon').count()).toBe(1);
      ctx.expect(await page.getByRole('button', { name: 'Compact grid' }).locator('svg.lucide-icon').count()).toBe(1);
      await page.getByRole('button', { name: 'New item', exact: true }).first().waitFor({ state: 'visible' });
      await page.getByRole('button', { name: 'Start', exact: true }).click();
      const starters = await page.locator('.starter').count();
      ctx.expect(starters).toBeGreaterThan(1);
      ctx.expect(await page.locator('.starter svg.lucide-icon').count()).toBe(starters);
      // Decorative icons stay out of the accessibility tree.
      ctx.expect(await page.locator('.starter svg.lucide-icon[aria-hidden="true"]').count()).toBe(starters);
    },
  }),

  test({
    id: 'editor.browser-icon-set-visual',
    title: 'the Lucide icon set reads as one consistent stroke family across the rail, type tabs, Start tiles and Problems',
    mode: 'visual',
    dependsOn: ['editor.browser-icon-set'],
    covers: ICON_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('navigation', { name: 'content types' }).locator('button svg.lucide-icon').first().waitFor({ state: 'visible' });
      await ctx.browser.browserShot('icon-set-content-toolbar');
      await page.getByRole('button', { name: 'Start', exact: true }).click();
      await page.locator('.starter svg.lucide-icon').first().waitFor({ state: 'visible' });
      await ctx.browser.browserShot('icon-set-start-tiles');
      await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).click();
      await page.getByRole('heading', { name: 'Problems', exact: true }).waitFor({ state: 'visible' });
      await ctx.browser.browserShot('icon-set-rail-collapsed');
      ctx.needsEyes(
        'every icon is one Lucide stroke family (ISC, bundled, no runtime CDN) at the same visual weight: the rail (Start house, Content layers, Assets image, Files folder, Problems triangle, Activity pulse, Server, labels chevron) and its collapsed 48px form; the Content toolbar (back, up, list, grid, New item plus) and the type tabs, where each content type has its own icon (Items package, Blocks box, Furniture armchair, Recipes chef hat, Templates layout, Glyphs type, Sounds speaker, Paintings frame, Text effects sparkles); the Start tiles repeat the per-type icon; no unicode glyph such as an arrow, cross or diamond appears as an icon. Colours and spacing are unchanged from before the icon set (issue #149; the visual identity work is tracked in #150).',
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
      const originalName = await fieldText(nameField);
      const originalLore = (await fieldText(loreField)).split('\n');
      const material = studio.getByRole('combobox', { name: 'Base material' });
      await material.fill('netherite sword');
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
      ctx.expect(await material.inputValue()).toBe('Netherite Sword');

      // typed & codes become formatting; the stored value is MiniMessage
      await nameField.fill('&6Ruby Sword Browser Draft');
      await typeLines(page, loreField, ['Burns with inner fire', '', 'Local draft only']);
      // the tooltip is the editor (issue 173): the same two textboxes show the typed result
      ctx.expect((await nameField.innerText()).trim()).toBe('Ruby Sword Browser Draft');
      ctx.expect(await loreField.locator('.mc-line').count()).toBe(3);
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isEnabled()).toBeTruthy();
      await assertHeldRubySwordName(ctx, 'Ruby Sword');
      await studio.getByRole('button', { name: 'Save & apply' }).click();
      await assertHeldRubySwordName(ctx, 'Ruby Sword Browser Draft');
      await typeLines(page, nameField, [originalName]);
      await typeLines(page, loreField, originalLore);
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
        'items without a custom texture or model (e.g. sample:ruby_disc) show the matching vanilla inventory icon, crisp at grid size; items with their own texture still show it, and unsupported models keep their honest fallback' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-block-model-previews',
    title: 'sample blocks and furniture render live model previews (cube, slab, stairs) from their compiled models',
    covers: MODEL_PREVIEW_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('button', { name: 'Blocks', exact: true }).click();
      for (const name of ['Amethyst Lamp', 'Marble Slab', 'Marble Stairs']) {
        const image = page.getByRole('option', { name: new RegExp(name) }).first().locator('.thumbnail.live img');
        await image.waitFor({ state: 'visible', timeout: 20_000 });
        ctx.expect(await image.evaluate((entry) => entry.complete && entry.naturalWidth === 128)).toBeTruthy();
      }
      await page.getByRole('button', { name: 'Furniture', exact: true }).click();
      const chair = page.getByRole('option', { name: /Chair/ }).first().locator('.thumbnail.live img');
      await chair.waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),

  test({
    id: 'editor.browser-block-model-previews-visual',
    title: 'sample block catalog and stairs studio show recognizable inventory-style model renders',
    mode: 'visual',
    dependsOn: ['editor.browser-block-model-previews'],
    covers: MODEL_PREVIEW_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Content', exact: true }).click();
      await page.getByRole('button', { name: 'Blocks', exact: true }).click();
      await page.getByRole('button', { name: 'Compact grid' }).click();
      await page.locator('.thumbnail.live img').first().waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('sample-block-model-previews');
      await page.getByRole('option', { name: /Marble Stairs/ }).first().dblclick();
      await page.locator('.preview .thumbnail.live img').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('sample-marble-stairs-studio-preview');
      ctx.needsEyes(
        'sample:amethyst_lamp, marble_slab and marble_stairs show 3D inventory-angle renders (top brightest, left side lighter than right) matching their in-game inventory icons in shape and orientation; the stairs studio preview shows the same render at 180 px; vanilla textures, if any, appear as grey checkers' + IDENTITY_EYES,
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
      await page.getByRole('textbox', { name: 'Display name' }).filter({ hasText: 'Red Gem' })
        .waitFor({ state: 'visible' });
      await page.getByRole('article', { name: 'Appearance' }).locator('.thumbnail img')
        .waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('corpus-red-gem-inventory-preview');
      ctx.needsEyes(
        'the real local App shows sample:ruby_sword with an honest unsupported-model fallback, then the corpus texture catalog and corpus:gem_red as the one large Appearance render; screenshots are App-scoped and contain no capability URL or session material' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-item-workbench-visual',
    title: 'sample:ruby_sword Item Studio presents a browser-only formatted draft under its id, with no explanatory copy beside the fields',
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
      await typeLines(page, studio.getByLabel('Lore', { exact: true }), ['Burns with inner fire', '', 'Preview only']);
      await studio.getByRole('combobox', { name: 'Base material' }).fill('netherite sword');
      await page.keyboard.press('ArrowDown');
      await page.keyboard.press('Enter');
      await studio.getByRole('textbox', { name: 'Display name' }).waitFor({ state: 'visible' });
      await ctx.browser.browserShot('sample-ruby-sword-item-workbench');
      ctx.needsEyes(
        'the real local Materione App shows sample:ruby_sword in Item Studio with the resource id as its header title, one large Appearance render with the texture and 3D model pickers under it, the formatted name/lore edited in place in the Minecraft-style tooltip, the material module and the explicit Save & apply action, with no explanatory copy under the fields; nothing claims that gameplay or player delivery was verified' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-rich-text-gradient',
    title: 'sample:ruby_sword display name authored as a gradient in the rich text editor reaches the held item with the same gradient',
    covers: RICH_TEXT_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });
      const name = studio.getByLabel('Display name', { exact: true });
      ctx.expect(await name.getAttribute('contenteditable')).toBe('true');
      ctx.expect(await fieldText(name)).toBe('Ruby Sword');
      // untouched: focusing and selecting writes nothing
      await name.click();
      await name.press('Control+A');
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isDisabled()).toBe(true);
      try {
        await applyNameGradient(page, studio);
        ctx.expect(await name.locator('.mc-run').count()).toBe('Ruby Sword'.length);
        await studio.getByRole('button', { name: 'Save & apply' }).click();
        await ctx.waitForServerLog(/MTR-0012: applied: /, { timeoutMs: 45_000 });
        await assertHeldRubySwordName(ctx, 'Ruby Sword');
        const runs = (await heldNameRuns(ctx)).split('|').filter(Boolean);
        ctx.log(`held name runs: ${runs.join(' ')}`);
        // the editor's default gradient: Materione orange to gold, one color per character
        ctx.expect(runs[0]).toBe('f9731e=R');
        ctx.expect(runs.at(-1)).toBe('ffd27a=d');
        ctx.expect(new Set(runs.map((run) => run.split('=')[0])).size).toBeGreaterThan(5);
      } finally {
        await restoreRubySwordName(ctx, page);
      }
    },
  }),

  test({
    id: 'editor.browser-rich-text-gradient-visual',
    title: 'sample:ruby_sword gradient name looks the same in the editor surface and above the in-game hotbar',
    mode: 'visual',
    dependsOn: ['editor.browser-rich-text-gradient'],
    covers: RICH_TEXT_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });
      try {
        await applyNameGradient(page, studio);
        const name = studio.getByLabel('Display name', { exact: true });
        await name.press('End');
        await name.press('Shift+ArrowLeft');
        await name.press('Shift+ArrowLeft');
        await name.press('Shift+ArrowLeft');
        await name.press('Shift+ArrowLeft');
        await name.press('Shift+ArrowLeft');
        await page.getByRole('button', { name: 'Display name: Bold' }).click();
        await ctx.browser.browserShot('rich-text-editor-gradient', '[data-field="display_name"]');
        await ctx.browser.browserShot('rich-text-tooltip-preview', '.item-preview .tooltip');
        await studio.getByRole('button', { name: 'Save & apply' }).click();
        await ctx.waitForServerLog(/MTR-0012: applied: /, { timeoutMs: 45_000 });
        await ctx.reset();
        await ctx.rcon('materione give sample:ruby_sword Probe');
        await ctx.waitForChat(/Given sample:ruby_sword/);
        // switching onto the slot shows the item name above the hotbar for a moment
        await ctx.exec('player.getInventory().selected = 1');
        await ctx.sleep(400);
        await ctx.exec('player.getInventory().selected = 0');
        await ctx.sleep(300);
        await ctx.shot('hotbar-name');
        ctx.needsEyes(
          'the editor shot shows "Ruby Sword" in the Minecraft-style face (Minecraftia) on the dark purple tooltip panel, colored as one smooth orange (#f9731e) to gold (#ffd27a) gradient with a drop shadow, "Sword" bold, and the floating toolbar above it (B, I, U, S, obfuscated, color, gradient pressed, clear); the tooltip preview shot draws the same; the client shot shows the held item name above the hotbar with the same orange-to-gold gradient and bold "Sword", not white text and not literal <gradient> tags (issue #151)' + IDENTITY_EYES,
        );
      } finally {
        await restoreRubySwordName(ctx, page);
      }
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
        ctx.expect(await studio.getByRole('heading', { name: 'ruby_sword', exact: true }).count()).toBe(1);
        ctx.expect(await fieldText(studio.getByLabel('Display name', { exact: true }))).toBe('Ruby Sword');
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
        ctx.expect(await fieldText(fileName)).toBe('Ruby Sword QA Draft');

        await page.getByRole('button', { name: 'Content', exact: true }).click();
        await studio.waitFor({ state: 'visible' });
        ctx.expect(await studio.getByRole('heading', { name: 'ruby_sword', exact: true }).count()).toBe(1);
        ctx.expect(await fieldText(studio.getByLabel('Display name', { exact: true }))).toBe('Ruby Sword QA Draft');
        await page.getByRole('button', { name: /Open in Files/ }).click();
        await page.getByRole('heading', { name: 'sample:ruby_sword', exact: true })
          .waitFor({ state: 'visible' });
        ctx.expect(await fieldText(fileName)).toBe('Ruby Sword QA Draft');

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
            if (await field.isVisible() && (await fieldText(field)) !== 'Ruby Sword') {
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
        'the real local Materione App shows sample:ruby_sword in Files with the staged Ruby Sword Visual Draft clearly unsaved; navigation reads Content, Files, Problems, the file header offers Open in Studio, Save & apply is distinct from Publish pack, and the screenshot contains no capability URL or session material' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-guided-start',
    title: 'Start offers registry-driven starters and explains the abilities this server registers',
    covers: GUIDED_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      let pageErrors = 0;
      page.on('pageerror', () => { pageErrors += 1; });
      await page.getByLabel('project').selectOption('sample');
      await page.getByRole('button', { name: 'Start', exact: true }).click();
      const start = page.getByRole('region', { name: 'Start', exact: true });
      await start.getByRole('button', { name: /^New item/i }).waitFor({ state: 'visible', timeout: 20_000 });
      ctx.expect(await start.getByRole('button', { name: /^New block/i }).count()).toBe(1);
      // The real registry's built-ins, with their declared activation sentences.
      const behaviour = await start.locator('ul.abilities').innerText();
      ctx.expect(behaviour).toContain('Soulbound');
      ctx.expect(behaviour).toContain('When the owner dies');
      ctx.expect(behaviour).toContain('Commands');
      await start.getByRole('button', { name: /^New item/i }).click();
      ctx.expect(await page.getByRole('dialog').innerText()).toContain('New item');
      await page.keyboard.press('Escape');
      ctx.expect(pageErrors).toBe(0);
    },
  }),

  test({
    id: 'editor.browser-template-reset',
    title: 'sample:templated_ruby shows its inherited material; an override and Reset to inherited apply in game and restore the file byte-for-byte',
    covers: GUIDED_COVERS,
    dependsOn: ['items.template-inheritance'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const file = path.join(SERVER_SAMPLE, 'items', 'templated_ruby.yml');
      const original = fs.readFileSync(file);
      try {
        const page = await ctx.browser.openEditor();
        const studio = await openSampleStudio(page, 'Templated Ruby');
        ctx.expect(await studio.locator('.inherits').innerText()).toContain('base_item');
        const material = studio.locator('[data-provenance="material"]');
        ctx.expect((await material.innerText()).trim()).toBe('Inherited from base_item: PAPER');

        await studio.getByRole('combobox', { name: 'Base material' }).fill('stick');
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');
        await studio.getByRole('button', { name: 'Save & apply' }).click();
        await expectHeldMaterial(ctx, 'sample:templated_ruby', 'item.minecraft.stick');
        ctx.expect((await material.innerText())).toContain('Overrides base_item (PAPER)');

        await material.getByRole('button', { name: 'Reset to inherited' }).click();
        ctx.expect(await material.innerText()).toContain('Resets to the value from base_item');
        await studio.getByRole('button', { name: 'Save & apply' }).click();
        await expectHeldMaterial(ctx, 'sample:templated_ruby', 'item.minecraft.paper');
        // Lossless (master §5.6): the removal deletes exactly the inserted line.
        ctx.expect(fs.readFileSync(file).equals(original)).toBeTruthy();
      } finally {
        if (!fs.readFileSync(file).equals(original)) {
          fs.writeFileSync(file, original);
          try { await ctx.rcon('materione reload'); } catch (e) { ctx.log(`restore reload failed: ${e?.message ?? e}`); }
        }
      }
    },
  }),

  test({
    id: 'editor.browser-ability-activation',
    title: 'an item studio adds a registered ability module with its activation explained, and rules only offer events items fire',
    covers: GUIDED_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleStudio(page, 'Ruby Sword');
      // Abilities are module rows; adding one goes through the picker and selects it, so its settings open in the panel.
      const panel = studio.getByRole('region', { name: 'Module settings' });
      await studio.getByRole('button', { name: 'Add module' }).click();
      const picker = page.getByRole('dialog', { name: 'Add a module' });
      await picker.getByLabel('Search modules').fill('food');
      await picker.getByRole('option', { name: /Food/ }).waitFor({ state: 'visible' });
      await picker.getByLabel('Search modules').press('Enter');
      const card = studio.locator('[data-module="mechanic:materione:food"]');
      await card.waitFor({ state: 'visible' });
      // Origin and activation are a hover hint on the ability's name, not copy on the row.
      const hint = await panel.locator('header strong').getAttribute('title');
      ctx.expect(hint).toContain('Built in');
      ctx.expect(hint).toContain('Activates: When the player eats');

      // Rules are their own When… do… module; events come from the server's table.
      await studio.getByRole('button', { name: 'Add module' }).click();
      await page.getByLabel('Search modules').fill('when');
      await page.getByLabel('Search modules').press('Enter');
      const rules = panel;
      await rules.getByRole('button', { name: 'Add rule' }).click();
      const events = await rules.getByLabel('When').locator('option').allInnerTexts();
      ctx.expect(events.includes('Click')).toBeTruthy();
      ctx.expect(events.includes('Place')).toBe(false);
      ctx.expect(events.includes('Tick')).toBe(false);
      // Staged only: nothing reaches the server until Save & apply.
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isEnabled()).toBeTruthy();
    },
  }),

  test({
    id: 'editor.browser-bare-on-rule',
    title: 'a block rule written with a bare `on: interact` shows its event in the Studio, not "no event"',
    // SnakeYAML loads a bare `on:` key as boolean true; the editor's parsed
    // view must hand the rule editor the key the author wrote.
    covers: [
      ...GUIDED_COVERS,
      'core/src/main/java/dev/materione/core/document/LosslessYaml.java',
      'fixtures/packs/sample/blocks/amethyst_lamp.yml',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleStudio(page, 'Amethyst Lamp');
      const rules = studio.getByRole('region', { name: 'Behaviour rules' });
      const sentence = (await rules.locator('[data-rule="0"] .sentence').innerText()).toLowerCase();
      ctx.expect(sentence).toContain('on interact');
      ctx.expect(sentence.includes('no event')).toBe(false);
    },
  }),

  test({
    id: 'editor.browser-missing-texture-repair',
    title: 'a quarantined item with a missing texture is repaired from Problems by drawing the file, without Files or YAML',
    covers: GUIDED_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const item = path.join(SERVER_SAMPLE, 'items', 'qa_cracked_gem.yml');
      const texture = path.join(SERVER_SAMPLE, 'assets', 'textures', 'item', 'qa_cracked_gem.png');
      fs.writeFileSync(item, 'qa_cracked_gem:\n  material: STICK\n  display_name: "QA Cracked Gem"\n  texture: item/qa_cracked_gem\n');
      try {
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        const page = await ctx.browser.openEditor();
        await page.getByLabel('project').selectOption('sample');
        await page.getByRole('button', { name: /^Problems(?:, .+)?$/ }).click();
        const problems = page.getByRole('region', { name: 'Problems', exact: true });
        const row = problems.locator('li', { hasText: 'qa_cracked_gem quarantined' }).first();
        await row.getByRole('button', { name: 'Fix texture' }).click();
        const repair = page.getByRole('group', { name: 'Repair Texture' });
        await repair.waitFor({ state: 'visible', timeout: 20_000 });
        ctx.expect(await repair.innerText()).toContain('assets/textures/item/qa_cracked_gem.png');
        await repair.getByRole('button', { name: 'Draw it now' }).click();
        const canvas = page.locator('canvas');
        await canvas.waitFor({ state: 'visible' });
        await canvas.click();
        await page.getByRole('button', { name: 'Save & apply' }).last().click();
        const deadline = Date.now() + 20_000;
        while (!fs.existsSync(texture) && Date.now() < deadline) await ctx.sleep(200);
        ctx.expect(fs.existsSync(texture)).toBeTruthy();
        await expectHeldMaterial(ctx, 'sample:qa_cracked_gem', 'item.minecraft.stick');
      } finally {
        fs.rmSync(item, { force: true });
        fs.rmSync(texture, { force: true });
        try { await ctx.rcon('materione reload'); } catch (e) { ctx.log(`cleanup reload failed: ${e?.message ?? e}`); }
      }
    },
  }),

  test({
    id: 'editor.browser-guided-authoring-visual',
    title: 'Item Studio reads abilities and rules as sentences, with inheritance provenance and an honest activation note',
    mode: 'visual',
    dependsOn: ['editor.browser-ability-activation'],
    covers: GUIDED_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const studio = await openSampleStudio(page, 'Templated Ruby');
      const panel = studio.getByRole('region', { name: 'Module settings' });
      // The inherited Base material row selects into the panel, which names its template and value.
      await studio.getByRole('button', { name: 'Base material', exact: true }).click();
      ctx.expect((await panel.innerText())).toContain('Inherited from base_item');
      ctx.expect((await panel.innerText())).toContain('PAPER');

      await studio.getByRole('button', { name: 'Add module' }).click();
      await page.getByLabel('Search modules').fill('soulbound');
      await page.getByLabel('Search modules').press('Enter');
      await studio.locator('[data-module="mechanic:materione:soulbound"]').waitFor({ state: 'visible' });

      await studio.getByRole('button', { name: 'Add module' }).click();
      await page.getByLabel('Search modules').fill('when');
      await page.getByLabel('Search modules').press('Enter');
      const rules = panel;
      await rules.getByLabel('Rule starting point').selectOption({ label: 'Right-click sends a message' });
      await rules.getByRole('button', { name: 'Add rule' }).click();
      await ctx.browser.browserShot('sample-templated-ruby-guided-authoring');
      ctx.needsEyes(
        'the studio shows the resource id with a compact template chip naming the base_item template (no full-width Based on row); the module list holds compact rows (title and markers only, the inherited Base material row marked Inherited); the settings panel on the right holds only the selected module, and the selected Base material reads "Inherited from base_item" with PAPER and Override here; a Soulbound row has no settings or activation copy in the list; a When… do… module in the panel holds a rule reading "On right click: send message …" with no explanatory line under it; nothing claims the behaviour was verified in game and Save & apply is the only write' + IDENTITY_EYES,
      );
    },
  }),

  test({
    id: 'editor.browser-asset-usage-replace',
    title: 'Assets lists every user of item/ruby with honest coverage, and Replace everywhere retargets both items in one revision-checked change',
    covers: ASSETS_COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const files = ['ruby.yml', 'templated_ruby.yml'].map((name) => path.join(SERVER_SAMPLE, 'items', name));
      const originals = files.map((file) => fs.readFileSync(file, 'utf8'));
      try {
        const page = await ctx.browser.openEditor();
        const detail = await openAssets(page, 'item/ruby.png');
        ctx.expect(await detail.getByRole('heading', { name: /^Used by/ }).innerText()).toMatch(/2 items/);
        ctx.expect(await detail.getByRole('button', { name: 'ruby', exact: true }).count()).toBe(1);
        ctx.expect(await detail.getByRole('button', { name: 'templated_ruby' }).count()).toBe(1);
        ctx.expect(await detail.innerText()).toContain('documents read');
        ctx.expect(files.map((file, i) => fs.readFileSync(file, 'utf8') === originals[i]).every(Boolean)).toBeTruthy();

        await detail.getByRole('button', { name: 'Replace everywhere…' }).click();
        await detail.getByLabel('Point them at').selectOption({ label: 'block/marble.png' });
        await detail.getByRole('button', { name: 'Preview changes' }).click();
        await detail.getByLabel('impact summary').waitFor({ state: 'visible' });
        // The preview is a dry run: nothing on disk changed yet.
        ctx.expect(files.map((file, i) => fs.readFileSync(file, 'utf8') === originals[i]).every(Boolean)).toBeTruthy();
        await detail.getByRole('button', { name: /^Replace in 2 uses/ }).click();
        const deadline = Date.now() + 20_000;
        while (!files.every((file) => fs.readFileSync(file, 'utf8').includes('block/marble')) && Date.now() < deadline) {
          await ctx.sleep(200);
        }
        // Lossless: only the texture line of each file changed.
        files.forEach((file, i) => ctx.expect(fs.readFileSync(file, 'utf8')).toBe(originals[i].replace('item/ruby', 'block/marble')));
      } finally {
        files.forEach((file, i) => fs.writeFileSync(file, originals[i]));
        try { await ctx.rcon('materione reload'); } catch (e) { ctx.log(`restore reload failed: ${e?.message ?? e}`); }
      }
    },
  }),

  test({
    id: 'editor.browser-bulk-stale-refusal',
    title: 'a bulk change refuses entirely when one document changed on disk after its preview: no document is written',
    covers: ASSETS_COVERS,
    dependsOn: ['editor.browser-asset-usage-replace'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const files = ['ruby.yml', 'templated_ruby.yml'].map((name) => path.join(SERVER_SAMPLE, 'items', name));
      const originals = files.map((file) => fs.readFileSync(file, 'utf8'));
      try {
        const page = await ctx.browser.openEditor();
        const detail = await openAssets(page, 'item/ruby.png');
        await detail.getByRole('button', { name: 'Replace everywhere…' }).click();
        await detail.getByLabel('Point them at').selectOption({ label: 'block/marble.png' });
        await detail.getByRole('button', { name: 'Preview changes' }).click();
        await detail.getByLabel('impact summary').waitFor({ state: 'visible' });

        // Someone edits one of the two files by hand between preview and apply.
        fs.writeFileSync(files[1], `${originals[1]}# hand edit\n`);
        await detail.getByRole('button', { name: /^Replace in 2 uses/ }).click();
        await detail.getByRole('alert').waitFor({ state: 'visible', timeout: 20_000 });
        ctx.expect(fs.readFileSync(files[0], 'utf8')).toBe(originals[0]);
        ctx.expect(fs.readFileSync(files[1], 'utf8')).toBe(`${originals[1]}# hand edit\n`);
      } finally {
        files.forEach((file, i) => fs.writeFileSync(file, originals[i]));
        try { await ctx.rcon('materione reload'); } catch (e) { ctx.log(`restore reload failed: ${e?.message ?? e}`); }
      }
    },
  }),

  test({
    id: 'editor.browser-assets-bulk-visual',
    title: 'the Assets workspace and the bulk-change preview read as one clear, honest flow',
    mode: 'visual',
    dependsOn: ['editor.browser-asset-usage-replace'],
    covers: ASSETS_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const detail = await openAssets(page, 'item/ruby.png');
      await ctx.browser.browserShot('assets-used-by');
      await detail.getByRole('button', { name: 'Replace everywhere…' }).click();
      await detail.getByLabel('Point them at').selectOption({ label: 'block/marble.png' });
      await detail.getByRole('button', { name: 'Preview changes' }).click();
      await detail.getByLabel('impact summary').waitFor({ state: 'visible' });
      await ctx.browser.browserShot('assets-replace-preview');
      ctx.needsEyes(
        'assets-used-by lists ruby and templated_ruby under "Used by — 2 items" with a coverage line that says how many documents were read and that asset files are not searched; assets-replace-preview shows the before → after texture text per document, the line "Written together, or not at all", and an Apply button naming the number of uses; nothing is written until Apply' + IDENTITY_EYES,
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
  test({
    id: 'editor.browser-plugin-keeps-login',
    title: 'the plugin-served editor root (and ?demo) keeps the paste-a-link card: no landing, no demo, no host marker',
    covers: LANDING_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const { page, root } = await openListenerRoot(ctx, '?demo');
      await page.goto(root, { waitUntil: 'domcontentloaded' });
      await page.getByRole('heading', { name: 'Open a session' }).waitFor({ state: 'visible', timeout: 15_000 });
      ctx.expect(await page.getByRole('button', { name: 'View Demo' }).count()).toBe(0);
      ctx.expect(await page.getByText('Sample content in this tab only').count()).toBe(0);
      const marker = await page.evaluate(async () =>
        (await fetch('/host.json', { cache: 'no-store' })).headers.get('X-Materione-Host'));
      ctx.expect(marker).toBe(null);
    },
  }),

  test({
    id: 'editor.browser-cloud-landing-demo',
    title: 'with the cloud host marker the same build shows the Materione landing card, and View Demo opens the recorded sample server client-side',
    covers: LANDING_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const { page, root } = await openListenerRoot(ctx);
      await serveLikeCloud(page);
      const backendCalls = [];
      page.on('request', (request) => {
        const path = new URL(request.url()).pathname;
        if (path.startsWith('/s/') || path.startsWith('/v1/') || path === '/editor/ws') backendCalls.push(path);
      });
      await page.goto(root, { waitUntil: 'domcontentloaded' });
      await page.getByRole('heading', { level: 1, name: 'Materione' }).waitFor({ state: 'visible', timeout: 15_000 });
      ctx.expect(await page.getByText('Web content editor', { exact: true }).count()).toBe(1);
      ctx.expect(await page.getByText('To start a new session, run /materione editor in-game').count()).toBe(1);
      await page.getByRole('button', { name: 'View Demo' }).click();
      await page.getByText('Sample content in this tab only').waitFor({ state: 'visible', timeout: 15_000 });
      await page.getByRole('navigation', { name: 'Workspace navigation' }).waitFor({ state: 'visible' });
      ctx.expect(await page.getByRole('combobox').first().inputValue()).toBe('sample');
      ctx.expect(new URL(page.url()).search).toBe('?demo');
      ctx.expect(backendCalls.length).toBe(0);
    },
  }),

  test({
    id: 'editor.browser-cloud-landing-visual',
    title: 'the cloud landing card and the demo workbench (recorded sample server) read as the Materione editor',
    mode: 'visual',
    dependsOn: ['editor.browser-cloud-landing-demo'],
    covers: LANDING_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const { page, root } = await openListenerRoot(ctx);
      await serveLikeCloud(page);
      await page.goto(root, { waitUntil: 'domcontentloaded' });
      await page.getByRole('button', { name: 'View Demo' }).waitFor({ state: 'visible', timeout: 15_000 });
      await ctx.browser.browserShot('cloud-landing', 'main');
      await page.getByRole('button', { name: 'View Demo' }).click();
      await page.getByText('Sample content in this tab only').waitFor({ state: 'visible', timeout: 15_000 });
      await ctx.browser.browserShot('cloud-demo-workbench', 'main');
      ctx.needsEyes(
        'cloud-landing shows one centred card with the Materione logo, the title Materione, the subtitle Web content editor, a primary View Demo button and the line "To start a new session, run /materione editor in-game"; cloud-demo-workbench shows the Demo banner above the normal workbench with the sample pack selected, every content type tab (Items to Text effects) and the sample items (Ruby Sword, Obsidian Axe, ...) with their textures in Content' + IDENTITY_EYES,
      );
    },
  }),

  // The item studio as display + modules (issue 152): the schema's module
  // metadata and the server's mechanic registry drive the cards; a save is
  // still one lossless authoring.setFields batch (D1/D6).
  test({
    id: 'editor.browser-item-modules',
    title: 'sample:ruby_sword edits through a display card, a module list and a settings panel, and modules add/remove losslessly',
    covers: [
      ...WORKBENCH_COVERS,
      'api/src/main/java/dev/materione/api/registry/ParameterSchema.java',
      'webui/src/lib/modules.ts',
      'webui/src/lib/studio/ModulePicker.svelte',
      'webui/src/lib/studio/ModulesArea.svelte',
      'webui/src/lib/studio/Studio.svelte',
      'webui/src/lib/drafts.svelte.ts',
      'webui/src/lib/components/icons.ts',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });

      const display = studio.getByRole('article', { name: 'Appearance' });
      await display.getByRole('textbox', { name: 'Display name' }).waitFor({ state: 'visible' });
      await display.getByRole('textbox', { name: 'Lore' }).waitFor({ state: 'visible' });

      // Required module: the base material is the floor, not a removable row.
      const material = studio.locator('[data-module="material"]');
      await material.waitFor({ state: 'visible' });
      ctx.expect(await material.getByRole('button', { name: 'Remove' }).count()).toBe(0);

      // Rows hold titles and markers only (no inputs); the selected module's settings open in the panel.
      const panel = studio.getByRole('region', { name: 'Module settings' });
      ctx.expect(await studio.getByRole('list', { name: 'Modules' }).locator('input, select, textarea').count()).toBe(0);
      await studio.getByRole('button', { name: 'Durability', exact: true }).click();
      ctx.expect(await panel.getByRole('spinbutton').inputValue()).toBe('500');
      await studio.getByRole('button', { name: 'Soulbound', exact: true }).click();
      // Origin and activation are a hover hint on the panel's title, not copy in the list.
      ctx.expect(await panel.locator('header strong').getAttribute('title')).toContain('When the owner dies');
      ctx.expect(await panel.getByRole('spinbutton').count()).toBe(0);

      // Unknown keys keep the honest Advanced-only report.
      ctx.expect(await studio.getByText('only editable in Advanced Files').innerText())
        .toContain('future_component');

      // Add a module through the picker: it is selected, so its settings open in the panel.
      // Configure it, save, then remove it again — the sample pack must end the test exactly as it started.
      await studio.getByRole('button', { name: 'Add module' }).click();
      const picker = page.getByRole('dialog', { name: 'Add a module' });
      await picker.getByLabel('Search modules').fill('food');
      await picker.getByRole('option', { name: /Food/ }).waitFor({ state: 'visible' });
      await picker.getByLabel('Search modules').press('Enter');
      const food = studio.locator('[data-module="mechanic:materione:food"]');
      await panel.getByLabel('Nutrition').fill('4');
      await panel.getByLabel('Nutrition').press('Tab');
      await panel.getByLabel('Saturation').fill('0.3');
      await panel.getByLabel('Saturation').press('Tab');
      await panel.getByLabel('Can always eat').check();

      const save = studio.getByRole('button', { name: 'Save & apply' });
      await save.click();
      await waitForSaved(page);
      // The other abilities are untouched by the food save.
      ctx.expect(await studio.locator('[data-module="mechanic:materione:durability"]').count()).toBe(1);

      await food.getByRole('button', { name: 'Remove Food' }).click();
      await save.click();
      await waitForSaved(page);
      ctx.expect(await studio.locator('[data-module="mechanic:materione:food"]').count()).toBe(0);
      await studio.getByRole('button', { name: 'Durability', exact: true }).click();
      ctx.expect(await panel.getByRole('spinbutton').inputValue()).toBe('500');
    },
  }),

  test({
    id: 'editor.browser-item-modules-visual',
    title: 'the item studio shows compact module rows beside a settings panel holding only the selected module, with a searchable, categorised picker',
    mode: 'visual',
    dependsOn: ['editor.browser-item-modules'],
    covers: [
      ...WORKBENCH_COVERS,
      'webui/src/lib/modules.ts',
      'webui/src/lib/studio/ModulePicker.svelte',
      'webui/src/lib/studio/ModulesArea.svelte',
      'webui/src/lib/studio/Studio.svelte',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.locator('[data-module="material"]').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('item-modules-studio', '.studio');
      // Selecting a row opens its settings in the panel on the right (issue 174).
      await studio.getByRole('button', { name: 'Durability', exact: true }).click();
      await studio.getByRole('region', { name: 'Module settings' }).getByRole('spinbutton').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('item-modules-panel', '.studio');
      await studio.getByRole('button', { name: 'Add module' }).click();
      await page.getByRole('dialog', { name: 'Add a module' }).getByRole('option').first()
        .waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('item-modules-picker', '.scrim');
      ctx.needsEyes(
        'item-modules-studio shows the Appearance component at the top of the centered column, then the module list below it: compact rows with an icon and the name only (Base material, Durability, Soulbound, Console Commands), no settings inside a row, a trash icon at the end of each removable row, and the dashed Add module button after the rows; the settings panel beside the column is empty with nothing selected; item-modules-panel shows only the selected Durability settings (Max durability 500) in the panel with the row highlighted; item-modules-picker shows the search field, category chips (All, Identity, Appearance, Gameplay, Behaviour) and one row per module with icon, label and one-line description, Added rows clearly marked' + IDENTITY_EYES,
      );
    },
  }),

  // The centered studio layout: on a wide window the studio body is one capped group in the
  // middle, with the settings panel next to the list and empty background on both sides.
  test({
    id: 'editor.browser-studio-centered-layout',
    title: 'on a wide window the item studio is a centered group: capped Appearance card, rows and panel side by side, empty background on both sides',
    mode: 'visual',
    dependsOn: ['editor.browser-item-modules'],
    covers: [
      ...WORKBENCH_COVERS,
      'webui/src/app.css',
      'webui/src/lib/studio/ItemAppearance.svelte',
      'webui/src/lib/studio/ModulesArea.svelte',
      'webui/src/lib/studio/Studio.svelte',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      await page.setViewportSize({ width: 1920, height: 1080 });
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.locator('[data-module="material"]').waitFor({ state: 'visible', timeout: 20_000 });
      await studio.getByRole('button', { name: 'Durability', exact: true }).click();
      const panel = studio.getByRole('region', { name: 'Module settings' });
      await panel.getByRole('spinbutton').waitFor({ state: 'visible', timeout: 20_000 });

      // The caps hold and the group is centered: equal empty margins, the panel beside the column.
      const body = await studio.locator('.body').boundingBox();
      const card = await studio.getByRole('article', { name: 'Appearance' }).boundingBox();
      const side = await panel.boundingBox();
      ctx.expect(card.width).toBeLessThan(801);
      ctx.expect(side.width).toBeLessThan(321);
      const left = card.x - body.x;
      const right = body.x + body.width - (side.x + side.width);
      ctx.expect(left).toBeGreaterThan(120);
      ctx.expect(Math.abs(left - right)).toBeLessThan(25);
      ctx.expect(side.x - (card.x + card.width)).toBeLessThan(41);
      await ctx.browser.browserShot('item-studio-wide', 'main');

      // 1440 px: the same group, narrower margins; the panel stays beside the list.
      await page.setViewportSize({ width: 1440, height: 900 });
      await panel.getByRole('spinbutton').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('item-studio-1440', 'main');
      ctx.needsEyes(
        'item-studio-wide shows the Appearance card (render with the tooltip right beside it, not at the far edge), the module rows and the dashed Add module bar as one column about 800 px wide, the selected Durability settings panel directly to its right, and plain empty background on both sides, the group centered under the full-width header; item-studio-1440 shows the same group with narrower margins and nothing cramped or clipped' + IDENTITY_EYES,
      );
    },
  }),

  // The module list and its settings panel (issue 174): rows hold titles and markers only, one
  // selected module's settings are in the panel, the arrow keys move the list, Escape returns from
  // the panel, and removal is an icon with Undo. Nothing is saved, so the sample pack ends as it started.
  test({
    id: 'editor.browser-item-module-panel',
    title: 'sample:ruby_sword opens one module\'s settings in the panel; arrows move the module list, Enter and Escape move focus, and a removed module comes back with Undo',
    dependsOn: ['editor.browser-item-modules'],
    covers: [
      ...WORKBENCH_COVERS,
      'webui/src/lib/modules.ts',
      'webui/src/lib/studio/ModulesArea.svelte',
      'webui/src/lib/studio/Studio.svelte',
      'webui/src/lib/drafts.svelte.ts',
      'webui/src/lib/components/icons.ts',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.locator('[data-module="material"]').waitFor({ state: 'visible', timeout: 20_000 });
      const panel = studio.getByRole('region', { name: 'Module settings' });
      const rowNamed = (name) => studio.getByRole('button', { name: new RegExp(`^${name}( Inherited)?( Has a problem)?$`) });

      // Nothing selected: the panel holds no module's settings.
      ctx.expect(await panel.getByRole('spinbutton').count()).toBe(0);
      await rowNamed('Durability').click();
      ctx.expect(await panel.getByRole('spinbutton').inputValue()).toBe('500');
      // Selecting another row swaps the settings: only one module is in the panel at a time.
      await rowNamed('Console Commands').click();
      ctx.expect(await panel.getByRole('spinbutton').count()).toBe(0);
      ctx.expect(await rowNamed('Durability').getAttribute('aria-current')).toBe(null);

      // Arrow keys move through the list and select as they go.
      await rowNamed('Base material').focus();
      await page.keyboard.press('ArrowDown');
      ctx.expect(await rowNamed('Soulbound').evaluate((node) => node === document.activeElement)).toBeTruthy();
      // Enter opens the settings and moves focus into the panel; Escape returns focus to the list.
      await rowNamed('Durability').focus();
      await page.keyboard.press('Enter');
      ctx.expect(await page.evaluate(() => Boolean(document.activeElement?.closest('[aria-label="Module settings"]')))).toBeTruthy();
      await page.keyboard.press('Escape');
      ctx.expect(await rowNamed('Durability').evaluate((node) => node === document.activeElement)).toBeTruthy();
      await studio.getByRole('button', { name: /open in files/i }).waitFor({ state: 'visible' });

      // Remove is a trash icon on the row; Undo brings it back, and nothing was staged to save.
      await studio.locator('[data-module="mechanic:materione:soulbound"]').getByRole('button', { name: 'Remove Soulbound' }).click();
      ctx.expect(await studio.locator('[data-module="mechanic:materione:soulbound"]').count()).toBe(0);
      await studio.getByRole('status').filter({ hasText: 'Removed Soulbound' }).getByRole('button', { name: 'Undo' }).click();
      ctx.expect(await studio.locator('[data-module="mechanic:materione:soulbound"]').count()).toBe(1);
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isDisabled()).toBeTruthy();
    },
  }),

  // The item studio's one Appearance component (issue 173): the picker and the in-place
  // tooltip are exercised without saving, so the sample pack ends as it started.
  test({
    id: 'editor.browser-item-appearance',
    title: 'sample:ruby_sword has one Appearance render; the texture and model pickers open, search and close; an obfuscated name is scrambled until the caret enters it',
    covers: [
      ...WORKBENCH_COVERS,
      'webui/src/lib/modules.ts',
      'webui/src/lib/studio/ModulesArea.svelte',
      'webui/src/app.css',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      await studio.waitFor({ state: 'visible' });
      const appearance = studio.getByRole('article', { name: 'Appearance' });
      await appearance.waitFor({ state: 'visible', timeout: 20_000 });

      // one render, no duplicate inventory slot, no Display name / Lore form rows outside the tooltip
      ctx.expect(await appearance.getByRole('img').count()).toBe(1);
      ctx.expect(await studio.getByText('Inventory scale').count()).toBe(0);
      ctx.expect(await studio.locator('select').filter({ hasText: 'none' }).count()).toBe(0);

      // the texture picker: a dialog with a searchbox and thumbnails; Escape closes it and returns focus
      await appearance.getByRole('button', { name: /^Texture:/ }).click();
      const textures = page.getByRole('dialog', { name: 'Choose texture' });
      await textures.getByRole('searchbox', { name: 'Search textures' }).waitFor({ state: 'visible' });
      ctx.expect(await textures.getByRole('option').count()).toBeGreaterThan(1);
      await textures.getByRole('searchbox', { name: 'Search textures' }).fill('zzz-no-such-texture');
      ctx.expect(await textures.getByText(/No texture matches/).count()).toBe(1);
      await page.keyboard.press('Escape');
      await textures.waitFor({ state: 'detached' });
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isDisabled()).toBeTruthy();

      // the model picker offers Blockbench for this entry
      await appearance.getByRole('button', { name: /^3D model:/ }).click();
      const models = page.getByRole('dialog', { name: 'Choose 3D model' });
      await models.getByRole('button', { name: /in Blockbench/ }).waitFor({ state: 'visible' });
      await page.keyboard.press('Escape');
      await models.waitFor({ state: 'detached' });

      // obfuscated: scrambled while idle, real characters while the caret is inside (nothing is saved)
      const name = appearance.getByRole('textbox', { name: 'Display name' });
      const real = (await name.innerText()).trim();
      await name.click();
      await name.press('Control+A');
      await name.press('Control+Shift+K');
      const run = name.locator('.mc-obf').first();
      ctx.expect((await run.innerText()).trim()).toBe(real);
      await appearance.getByRole('textbox', { name: 'Lore' }).click();
      await page.waitForTimeout(300);
      ctx.expect((await run.innerText()).trim()).not.toBe(real);
      await run.click();
      ctx.expect((await run.innerText()).trim()).toBe(real);
      await name.press('Control+Z');
      ctx.expect(await studio.getByRole('button', { name: 'Save & apply' }).isDisabled()).toBeTruthy();
    },
  }),

  test({
    id: 'editor.browser-item-appearance-visual',
    title: 'the Appearance component shows one large render with the texture and model pickers, the tooltip as the editor, and scrambled obfuscated text',
    mode: 'visual',
    dependsOn: ['editor.browser-item-appearance'],
    covers: [
      ...WORKBENCH_COVERS,
      'webui/src/lib/modules.ts',
      'webui/src/lib/studio/ModulesArea.svelte',
      'webui/src/app.css',
    ],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const page = await ctx.browser.openEditor();
      const row = await openSampleItemCatalog(page);
      await row.dblclick();
      const studio = page.locator('.studio');
      const appearance = studio.getByRole('article', { name: 'Appearance' });
      await appearance.waitFor({ state: 'visible', timeout: 20_000 });
      await appearance.locator('.thumbnail[aria-busy="false"]').waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('item-appearance-component', '.studio');
      await appearance.getByRole('button', { name: /^Texture:/ }).click();
      await page.getByRole('dialog', { name: 'Choose texture' }).getByRole('option').first()
        .waitFor({ state: 'visible', timeout: 20_000 });
      await ctx.browser.browserShot('item-appearance-texture-picker', '.scrim');
      await page.keyboard.press('Escape');
      const name = appearance.getByRole('textbox', { name: 'Display name' });
      await name.click();
      await name.press('Control+A');
      await name.press('Control+Shift+K');
      await appearance.getByRole('textbox', { name: 'Lore' }).click();
      await page.waitForTimeout(300);
      await ctx.browser.browserShot('item-appearance-obfuscated', '.studio');
      await name.press('Control+Z');
      ctx.needsEyes(
        'item-appearance-component shows sample:ruby_sword with ONE large item render (roughly a third to half of the studio width, no 32 px inventory slot), the Texture and 3D model buttons under it, and the Minecraft-style tooltip beside it with the name and lore as the editable text (no Display name / Lore form rows); item-appearance-texture-picker shows the compact explorer dialog with a search field, thumbnail rows grouped by folder, the current texture marked and Upload / Edit current / New texture actions; item-appearance-obfuscated shows the name drawn as scrambled characters of the same length while the lore is focused' + IDENTITY_EYES,
      );
    },
  }),
];
